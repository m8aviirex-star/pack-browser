package de.ubyz.packbrowser.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongConsumer;

/** Tiny client for the public Modrinth API v2 (no key needed). */
public final class ModrinthApi {
	private static final String BASE = "https://api.modrinth.com/v2";

	private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4, r -> {
		Thread t = new Thread(r, "PackBrowser-IO");
		t.setDaemon(true);
		return t;
	});

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.executor(EXECUTOR)
			.build();

	private ModrinthApi() {
	}

	public static ExecutorService executor() {
		return EXECUTOR;
	}

	private static String userAgent() {
		return "Ubyz/pack-browser/" + PackBrowserClient.modVersion() + " (Fabric mod for Minecraft " + PackBrowserClient.gameVersion() + ")";
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	private static HttpRequest.Builder request(String url) {
		return HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", userAgent())
				.timeout(Duration.ofSeconds(30));
	}

	private static CompletableFuture<JsonElement> getJson(String url) {
		return HTTP.sendAsync(request(url).header("Accept", "application/json").GET().build(),
						HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
				.thenApply(resp -> {
					if (resp.statusCode() != 200) {
						throw new CompletionException(new IOException("HTTP " + resp.statusCode()));
					}
					return JsonParser.parseString(resp.body());
				});
	}

	public static CompletableFuture<byte[]> getBytes(String url) {
		return HTTP.sendAsync(request(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
				.thenApply(resp -> {
					if (resp.statusCode() != 200) {
						throw new CompletionException(new IOException("HTTP " + resp.statusCode()));
					}
					return resp.body();
				});
	}

	// ------------------------------------------------------------------ search

	public record Hit(String id, String slug, String title, String author, String description,
					  long downloads, long follows, String iconUrl, List<String> gameVersions,
					  List<String> categories) {
		public String versionRange() {
			if (gameVersions.isEmpty()) {
				return "?";
			}
			String first = gameVersions.get(0);
			String last = gameVersions.get(gameVersions.size() - 1);
			return first.equals(last) ? first : first + " – " + last;
		}

		public boolean supports(String version) {
			return gameVersions.contains(version);
		}
	}

	public record Page(List<Hit> hits, int totalHits) {
	}

	public static CompletableFuture<Page> search(String query, String gameVersion, String category,
												 String sort, int offset, int limit) {
		JsonArray facets = new JsonArray();
		facets.add(single("project_type:resourcepack"));
		if (gameVersion != null) {
			facets.add(single("versions:" + gameVersion));
		}
		if (category != null && !category.isEmpty()) {
			facets.add(single("categories:" + category));
		}

		String url = BASE + "/search?query=" + enc(query == null ? "" : query.trim())
				+ "&facets=" + enc(facets.toString())
				+ "&index=" + enc(sort)
				+ "&offset=" + offset
				+ "&limit=" + limit;

		return getJson(url).thenApply(json -> {
			JsonObject obj = json.getAsJsonObject();
			List<Hit> hits = new ArrayList<>();
			for (JsonElement e : obj.getAsJsonArray("hits")) {
				JsonObject h = e.getAsJsonObject();
				hits.add(new Hit(
						str(h, "project_id"),
						str(h, "slug"),
						str(h, "title"),
						str(h, "author"),
						str(h, "description"),
						num(h, "downloads"),
						num(h, "follows"),
						str(h, "icon_url"),
						strList(h, "versions"),
						strList(h, "display_categories")));
			}
			return new Page(hits, (int) num(obj, "total_hits"));
		});
	}

	// ---------------------------------------------------------------- versions

	public record PackFile(String url, String filename, long size, String sha1, String versionName,
						   boolean matchesGameVersion) {
	}

	/**
	 * Picks the newest file for the given game version, or the newest file overall
	 * if the project has nothing for that version.
	 */
	public static CompletableFuture<PackFile> resolveFile(String projectId, String gameVersion) {
		return getJson(BASE + "/project/" + enc(projectId) + "/version").thenApply(json -> {
			JsonArray versions = json.getAsJsonArray();
			JsonObject chosen = null;
			boolean matches = false;
			for (JsonElement e : versions) {
				JsonObject v = e.getAsJsonObject();
				if (strList(v, "game_versions").contains(gameVersion)) {
					chosen = v;
					matches = true;
					break;
				}
			}
			if (chosen == null && !versions.isEmpty()) {
				chosen = versions.get(0).getAsJsonObject();
			}
			if (chosen == null) {
				throw new CompletionException(new IOException("Keine Dateien"));
			}

			JsonArray files = chosen.getAsJsonArray("files");
			JsonObject file = null;
			for (JsonElement e : files) {
				JsonObject f = e.getAsJsonObject();
				if (f.has("primary") && f.get("primary").getAsBoolean()) {
					file = f;
					break;
				}
			}
			if (file == null && !files.isEmpty()) {
				file = files.get(0).getAsJsonObject();
			}
			if (file == null) {
				throw new CompletionException(new IOException("Keine Dateien"));
			}

			String sha1 = null;
			if (file.has("hashes") && file.getAsJsonObject("hashes").has("sha1")) {
				sha1 = file.getAsJsonObject("hashes").get("sha1").getAsString();
			}
			return new PackFile(str(file, "url"), str(file, "filename"), num(file, "size"), sha1,
					str(chosen, "version_number"), matches);
		});
	}

	// ---------------------------------------------------------------- download

	public static CompletableFuture<Path> download(PackFile file, Path target, LongConsumer progress) {
		return HTTP.sendAsync(request(file.url()).timeout(Duration.ofMinutes(10)).GET().build(),
						HttpResponse.BodyHandlers.ofInputStream())
				.thenApplyAsync(resp -> {
					Path tmp = null;
					try {
						if (resp.statusCode() != 200) {
							resp.body().close();
							throw new IOException("HTTP " + resp.statusCode());
						}
						Files.createDirectories(target.getParent());
						tmp = Files.createTempFile("packbrowser-", ".part");
						MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
						try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(tmp)) {
							byte[] buf = new byte[1 << 16];
							long done = 0;
							int n;
							while ((n = in.read(buf)) != -1) {
								out.write(buf, 0, n);
								sha1.update(buf, 0, n);
								done += n;
								progress.accept(done);
							}
						}
						String hex = HexFormat.of().formatHex(sha1.digest());
						if (file.sha1() != null && !file.sha1().equalsIgnoreCase(hex)) {
							throw new IOException("Prüfsumme stimmt nicht");
						}
						Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
						return target;
					} catch (Exception e) {
						if (tmp != null) {
							try {
								Files.deleteIfExists(tmp);
							} catch (IOException ignored) {
							}
						}
						throw new CompletionException(e);
					}
				}, EXECUTOR);
	}

	// ------------------------------------------------------- any direct link

	public record Fetched(Path file, String fileName, String contentType) {
	}

	private static final long MAX_LINK_SIZE = 1024L * 1024 * 1024;

	/** Downloads any URL into a temp file (used for "Link einfügen"). */
	public static CompletableFuture<Fetched> fetchToTemp(URI uri, LongConsumer progress, LongConsumer totalSize) {
		HttpRequest req = HttpRequest.newBuilder(uri)
				.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) PackBrowser/" + PackBrowserClient.modVersion())
				.timeout(Duration.ofMinutes(10))
				.GET()
				.build();
		return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream()).thenApplyAsync(resp -> {
			Path tmp = null;
			try {
				if (resp.statusCode() != 200) {
					resp.body().close();
					throw new IOException("HTTP " + resp.statusCode());
				}
				long total = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);
				if (total > MAX_LINK_SIZE) {
					resp.body().close();
					throw new IOException("Datei ist größer als 1 GB");
				}
				totalSize.accept(total);
				tmp = Files.createTempFile("packbrowser-link-", ".tmp");
				try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(tmp)) {
					byte[] buf = new byte[1 << 16];
					long done = 0;
					int n;
					while ((n = in.read(buf)) != -1) {
						out.write(buf, 0, n);
						done += n;
						if (done > MAX_LINK_SIZE) {
							throw new IOException("Datei ist größer als 1 GB");
						}
						progress.accept(done);
					}
				}
				String type = resp.headers().firstValue("Content-Type").orElse("");
				return new Fetched(tmp, fileNameFrom(resp), type);
			} catch (Exception e) {
				if (tmp != null) {
					try {
						Files.deleteIfExists(tmp);
					} catch (IOException ignored) {
					}
				}
				throw new CompletionException(e);
			}
		}, EXECUTOR);
	}

	private static String fileNameFrom(HttpResponse<?> resp) {
		String cd = resp.headers().firstValue("Content-Disposition").orElse("");
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("filename\\*=(?:UTF-8|utf-8)''([^;]+)").matcher(cd);
		if (m.find()) {
			return java.net.URLDecoder.decode(m.group(1).trim(), StandardCharsets.UTF_8);
		}
		m = java.util.regex.Pattern.compile("filename=\"?([^\";]+)\"?").matcher(cd);
		if (m.find()) {
			return m.group(1).trim();
		}
		String path = resp.uri().getPath();
		if (path != null) {
			int slash = path.lastIndexOf('/');
			String last = slash >= 0 ? path.substring(slash + 1) : path;
			if (!last.isBlank()) {
				return java.net.URLDecoder.decode(last.replace("+", "%2B"), StandardCharsets.UTF_8);
			}
		}
		return "pack.zip";
	}

	// ----------------------------------------------------------------- helpers

	private static JsonArray single(String value) {
		JsonArray a = new JsonArray();
		a.add(value);
		return a;
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? "" : e.getAsString();
	}

	private static long num(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? 0L : e.getAsLong();
	}

	private static List<String> strList(JsonObject o, String key) {
		List<String> out = new ArrayList<>();
		JsonElement e = o.get(key);
		if (e != null && e.isJsonArray()) {
			for (JsonElement x : e.getAsJsonArray()) {
				out.add(x.getAsString());
			}
		}
		return out;
	}

	/** Strips characters that are not allowed in file names on Windows. */
	public static String safeFileName(String name) {
		String s = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
		if (s.isEmpty() || s.equals(".") || s.equals("..")) {
			s = "pack.zip";
		}
		return s;
	}
}
