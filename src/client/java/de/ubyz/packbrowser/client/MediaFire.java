package de.ubyz.packbrowser.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** MediaFire share links: single files and whole folders (via MediaFire's public folder API). */
public final class MediaFire {
	private static final String API = "https://www.mediafire.com/api/1.5/folder/get_content.php?response_format=json";
	private static final int MAX_FILES = 300;
	private static final int MAX_DEPTH = 3;

	private static final Pattern FOLDER = Pattern.compile("/folder/([A-Za-z0-9]+)");
	private static final Pattern FILE = Pattern.compile("/(?:file|file_premium|view|download)/([A-Za-z0-9]+)");
	private static final Pattern OLD_KEY = Pattern.compile("^([A-Za-z0-9]{6,20})");

	public record MfFile(String quickKey, String name, long size) {
	}

	private MediaFire() {
	}

	/** True for share pages (www/app.mediafire.com), false for direct downloadXXXX.mediafire.com links. */
	public static boolean isSharePage(URI u) {
		String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
		return host.equals("mediafire.com") || host.equals("www.mediafire.com") || host.equals("app.mediafire.com")
				|| host.equals("m.mediafire.com");
	}

	public static @Nullable String folderKey(URI u) {
		Matcher m = FOLDER.matcher(u.getRawPath() == null ? "" : u.getRawPath());
		if (m.find()) {
			return m.group(1);
		}
		String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
		if (host.equals("app.mediafire.com") && u.getRawPath() != null) {
			Matcher a = Pattern.compile("^/([A-Za-z0-9]{6,20})/?$").matcher(u.getRawPath());
			if (a.find()) {
				return a.group(1);
			}
		}
		String frag = u.getRawFragment();
		if (frag != null) {
			Matcher f = OLD_KEY.matcher(frag);
			if (f.find()) {
				return f.group(1);
			}
		}
		return null;
	}

	public static @Nullable String fileKey(URI u) {
		Matcher m = FILE.matcher(u.getRawPath() == null ? "" : u.getRawPath());
		if (m.find()) {
			return m.group(1);
		}
		String q = u.getRawQuery();
		if (q != null) {
			Matcher o = OLD_KEY.matcher(q);
			if (o.find()) {
				return o.group(1);
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ folder

	/** Lists all files in a public folder (and sub-folders). Blocking. */
	public static List<MfFile> listFolder(String folderKey) throws IOException, InterruptedException {
		List<MfFile> out = new ArrayList<>();
		collect(folderKey, "", 0, out);
		return out;
	}

	private static void collect(String folderKey, String prefix, int depth, List<MfFile> out)
			throws IOException, InterruptedException {
		for (int chunk = 1; chunk < 50 && out.size() < MAX_FILES; chunk++) {
			JsonObject content = request(folderKey, "files", chunk);
			if (content.has("files") && content.get("files").isJsonArray()) {
				for (JsonElement e : content.getAsJsonArray("files")) {
					JsonObject f = e.getAsJsonObject();
					if ("yes".equals(str(f, "password_protected"))) {
						continue;
					}
					long size = 0;
					try {
						size = Long.parseLong(str(f, "size"));
					} catch (NumberFormatException ignored) {
					}
					out.add(new MfFile(str(f, "quickkey"), prefix + str(f, "filename"), size));
					if (out.size() >= MAX_FILES) {
						return;
					}
				}
			}
			if (!"yes".equals(str(content, "more_chunks"))) {
				break;
			}
		}
		if (depth >= MAX_DEPTH) {
			return;
		}
		for (int chunk = 1; chunk < 20; chunk++) {
			JsonObject content = request(folderKey, "folders", chunk);
			if (content.has("folders") && content.get("folders").isJsonArray()) {
				for (JsonElement e : content.getAsJsonArray("folders")) {
					JsonObject f = e.getAsJsonObject();
					collect(str(f, "folderkey"), prefix + str(f, "name") + "/", depth + 1, out);
					if (out.size() >= MAX_FILES) {
						return;
					}
				}
			}
			if (!"yes".equals(str(content, "more_chunks"))) {
				break;
			}
		}
	}

	private static JsonObject request(String folderKey, String type, int chunk) throws IOException, InterruptedException {
		String url = API + "&folder_key=" + folderKey + "&content_type=" + type + "&chunk=" + chunk + "&chunk_size=100";
		JsonObject response = JsonParser.parseString(ModrinthApi.getTextBlocking(URI.create(url)))
				.getAsJsonObject().getAsJsonObject("response");
		if (!"Success".equalsIgnoreCase(str(response, "result"))) {
			String msg = str(response, "message");
			throw new IOException("MediaFire: " + (msg.isEmpty() ? "Ordner nicht gefunden oder privat" : msg));
		}
		return response.getAsJsonObject("folder_content");
	}

	// -------------------------------------------------------------------- file

	/** Opens the file's share page and reads the real download link from it. Blocking. */
	public static String resolveDirect(String quickKey) throws IOException, InterruptedException {
		String html = ModrinthApi.getTextBlocking(URI.create("https://www.mediafire.com/file/" + quickKey));
		String direct = extractDirect(html);
		if (direct == null) {
			String lower = html.toLowerCase(Locale.ROOT);
			if (lower.contains("password")) {
				throw new IOException("Datei ist mit Passwort geschützt");
			}
			throw new IOException("MediaFire-Datei nicht gefunden oder gelöscht");
		}
		return direct;
	}

	static @Nullable String extractDirect(String html) {
		Matcher m = Pattern.compile("data-scrambled-url=\"([^\"]+)\"").matcher(html);
		while (m.find()) {
			try {
				String decoded = new String(Base64.getDecoder().decode(m.group(1).trim()), StandardCharsets.UTF_8);
				if (decoded.startsWith("http")) {
					return decoded;
				}
			} catch (IllegalArgumentException ignored) {
			}
		}
		m = Pattern.compile("href=\"(https?://download[0-9]*\\.mediafire\\.com/[^\"]+)\"").matcher(html);
		if (m.find()) {
			return m.group(1).replace("&amp;", "&");
		}
		m = Pattern.compile("(https?://download[0-9]*\\.mediafire\\.com/[^\"'\\s<>]+)").matcher(html);
		if (m.find()) {
			return m.group(1).replace("&amp;", "&");
		}
		return null;
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? "" : e.getAsString();
	}
}
