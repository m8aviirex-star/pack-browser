package de.ubyz.packbrowser.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** "Link einfügen": downloads a pasted link and installs the pack(s) inside. */
public final class LinkImports {
	public enum Phase { DOWNLOADING, INSTALLING, DONE, FAILED }

	public static final class Job {
		public final String url;
		public volatile Phase phase = Phase.DOWNLOADING;
		public volatile long done;
		public volatile long total = -1;
		public volatile String fileName = "";
		public volatile String error = "";
		public volatile List<String> files = List.of();
		public volatile boolean alreadyPresent;
		public volatile boolean reported;

		Job(String url) {
			this.url = url;
		}

		public boolean running() {
			return this.phase == Phase.DOWNLOADING || this.phase == Phase.INSTALLING;
		}
	}

	/** Pages that show a download button instead of being the file itself. */
	private static final String[] PAGE_HOSTS = {"pvprp.com", "mediafire.com", "planetminecraft.com", "curseforge.com",
			"youtube.com", "youtu.be"};

	private static final List<Job> JOBS = new CopyOnWriteArrayList<>();

	private LinkImports() {
	}

	public static List<Job> jobs() {
		return JOBS;
	}

	public static Job start(String raw) {
		Job job = new Job(raw.trim());
		JOBS.add(0, job);
		while (JOBS.size() > 10) {
			JOBS.remove(JOBS.size() - 1);
		}

		URI uri;
		try {
			uri = normalize(raw);
		} catch (IllegalArgumentException e) {
			job.error = e.getMessage();
			job.phase = Phase.FAILED;
			return job;
		}

		ModrinthApi.fetchToTemp(uri, d -> job.done = d, t -> job.total = t)
				.thenApplyAsync(fetched -> {
					job.fileName = fetched.fileName();
					job.phase = Phase.INSTALLING;
					try {
						checkIsZip(fetched.file(), fetched.contentType());
						return PackInstaller.install(fetched.file(), fetched.fileName());
					} catch (IOException e) {
						throw new CompletionException(e);
					} finally {
						try {
							Files.deleteIfExists(fetched.file());
						} catch (IOException ignored) {
						}
					}
				}, ModrinthApi.executor())
				.whenComplete((result, err) -> {
					if (err != null) {
						job.error = Downloads.message(err);
						job.phase = Phase.FAILED;
						PackBrowserClient.LOGGER.warn("Link import failed: {}", job.url, err);
					} else {
						job.files = result.files();
						job.alreadyPresent = result.alreadyPresent();
						InstalledPacks.markInstalled("link:" + job.url, String.join("|", result.files()));
						job.phase = Phase.DONE;
					}
				});
		return job;
	}

	private static void checkIsZip(Path file, String contentType) throws IOException {
		byte[] head;
		try (InputStream in = Files.newInputStream(file)) {
			head = in.readNBytes(8);
		}
		if (head.length >= 4 && head[0] == 'P' && head[1] == 'K' && (head[2] == 3 || head[2] == 5)) {
			return;
		}
		if (head.length >= 4 && head[0] == 'R' && head[1] == 'a' && head[2] == 'r' && head[3] == '!') {
			throw new IOException("RAR-Dateien gehen nicht – bitte als ZIP herunterladen");
		}
		String start = new String(head, java.nio.charset.StandardCharsets.ISO_8859_1).trim();
		if (start.startsWith("<") || contentType.toLowerCase(Locale.ROOT).contains("html")) {
			throw new IOException("Das ist eine Webseite, kein direkter Download-Link. Lade den Pack im Browser runter und nimm den Tab „Aus Downloads“.");
		}
		throw new IOException("Das ist keine ZIP-Datei");
	}

	static URI normalize(String raw) {
		String s = raw.trim();
		if (s.isEmpty()) {
			throw new IllegalArgumentException("Bitte zuerst einen Link einfügen");
		}
		if (!s.toLowerCase(Locale.ROOT).startsWith("http://") && !s.toLowerCase(Locale.ROOT).startsWith("https://")) {
			s = "https://" + s;
		}
		s = s.replace(" ", "%20");
		URI u;
		try {
			u = new URI(s);
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("Ungültiger Link");
		}
		String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
		if (host.isEmpty()) {
			throw new IllegalArgumentException("Ungültiger Link");
		}

		if (host.equals("modrinth.com") || host.equals("www.modrinth.com")) {
			throw new IllegalArgumentException("Modrinth-Packs findest du direkt im Tab „Modrinth“");
		}
		for (String page : PAGE_HOSTS) {
			if (host.equals(page) || host.equals("www." + page) || (page.startsWith("youtu") && host.endsWith("." + page))) {
				throw new IllegalArgumentException("Das ist eine Webseite mit Download-Knopf, kein direkter Link. "
						+ "Lade den Pack dort im Browser runter und nimm dann den Tab „Aus Downloads“.");
			}
		}

		// Dropbox share link -> direct download
		if (host.equals("dropbox.com") || host.endsWith(".dropbox.com")) {
			String q = u.getRawQuery();
			if (q == null || q.isEmpty()) {
				q = "dl=1";
			} else if (q.contains("dl=0")) {
				q = q.replace("dl=0", "dl=1");
			} else if (!q.contains("dl=1")) {
				q = q + "&dl=1";
			}
			return URI.create(u.getScheme() + "://" + u.getRawAuthority() + u.getRawPath() + "?" + q);
		}

		// Google Drive share link -> direct download
		if (host.equals("drive.google.com")) {
			String id = null;
			Matcher m = Pattern.compile("/file/d/([A-Za-z0-9_-]+)").matcher(u.getPath() == null ? "" : u.getPath());
			if (m.find()) {
				id = m.group(1);
			} else if (u.getRawQuery() != null) {
				Matcher q = Pattern.compile("(?:^|&)id=([A-Za-z0-9_-]+)").matcher(u.getRawQuery());
				if (q.find()) {
					id = q.group(1);
				}
			}
			if (id != null) {
				return URI.create("https://drive.usercontent.google.com/download?id=" + id + "&export=download&confirm=t");
			}
		}

		// GitHub file view -> raw file
		if (host.equals("github.com") && u.getRawPath() != null && u.getRawPath().contains("/blob/")) {
			return URI.create(u.getScheme() + "://" + u.getRawAuthority() + u.getRawPath().replaceFirst("/blob/", "/raw/"));
		}

		return u;
	}
}
