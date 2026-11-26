package de.ubyz.packbrowser.client;

import com.mojang.blaze3d.Blaze3D;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** "Link einfügen": downloads pasted links (incl. MediaFire files and folders) and installs the packs inside. */
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
		// folder downloads
		public volatile boolean folder;
		public volatile int batchTotal;
		public volatile int batchIndex;
		public volatile int skipped;
		public volatile int failed;
		// opened in the browser (auto import watches the Downloads folder)
		public volatile boolean browser;
		public volatile String site = "";

		Job(String url) {
			this.url = url;
		}

		public boolean running() {
			return this.phase == Phase.DOWNLOADING || this.phase == Phase.INSTALLING;
		}

		public int percent() {
			return this.total > 0 ? (int) Math.min(100, this.done * 100 / this.total) : -1;
		}
	}

	/** Sites where the download has to happen in the browser (ads, link lockers, creator support, API rules). */
	private static final String[][] BROWSER_SITES = {
			{"pvprp.com", "PvPRP"},
			{"curseforge.com", "CurseForge"},
			{"packsmc.com", "PacksMC"},
			{"planetminecraft.com", "Planet Minecraft"},
			{"youtube.com", "YouTube"},
			{"youtu.be", "YouTube"},
			{"resourcepack.net", "ResourcePack.net"},
			{"mcpedl.com", "MCPEDL"},
			{"9minecraft.net", "9Minecraft"}
	};

	/** Thrown when a link returns a web page instead of a file. */
	static final class WebPageException extends IOException {
		WebPageException() {
			super("Webseite statt Datei");
		}
	}

	private static final List<Job> JOBS = new CopyOnWriteArrayList<>();

	/** MediaFire jobs run one after another to be gentle with their servers. */
	private static final ExecutorService MEDIAFIRE = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "PackBrowser-MediaFire");
		t.setDaemon(true);
		return t;
	});

	private LinkImports() {
	}

	public static List<Job> jobs() {
		return JOBS;
	}

	/** Accepts one or several links (pasted together). */
	public static List<Job> startAll(String raw) {
		List<Job> out = new ArrayList<>();
		for (String part : raw.trim().split("(?=https?://)|\\s+")) {
			if (!part.isBlank()) {
				out.add(start(part.trim()));
			}
		}
		return out;
	}

	public static Job start(String raw) {
		Job job = new Job(raw.trim());
		JOBS.add(0, job);
		while (JOBS.size() > 15) {
			JOBS.remove(JOBS.size() - 1);
		}

		URI uri;
		try {
			uri = normalize(raw);
		} catch (IllegalArgumentException e) {
			fail(job, e.getMessage());
			return job;
		}

		String browserSite = browserSite(uri);
		if (browserSite != null) {
			openInBrowser(job, uri, browserSite);
			return job;
		}

		if (MediaFire.isSharePage(uri)) {
			MEDIAFIRE.execute(() -> runMediaFire(job, uri));
			return job;
		}

		String modrinthSlug = modrinthSlug(uri);
		ModrinthApi.executor().execute(() -> {
			try {
				URI target = uri;
				if (modrinthSlug != null) {
					ModrinthApi.PackFile file = ModrinthApi.resolveFile(modrinthSlug, PackBrowserClient.gameVersion()).join();
					target = URI.create(file.url());
				}
				List<String> files = downloadAndInstall(job, target, true);
				finish(job, files);
			} catch (WebPageException page) {
				// unknown site with a download page -> let the user download it in the browser
				openInBrowser(job, uri, uri.getHost());
			} catch (Exception e) {
				fail(job, Downloads.message(e));
				PackBrowserClient.LOGGER.warn("Link import failed: {}", job.url, e);
			}
		});
		return job;
	}

	/** Opens the page in the normal browser and starts watching the Downloads folder. */
	public static void openInBrowser(Job job, URI uri, String siteName) {
		job.browser = true;
		job.site = siteName;
		job.fileName = siteName;
		AutoImport.arm(siteName);
		Minecraft.getInstance().execute(() -> {
			try {
				Blaze3D.openUri(uri);
			} catch (Exception e) {
				PackBrowserClient.LOGGER.warn("Could not open {}", uri, e);
			}
		});
		job.phase = Phase.DONE;
	}

	/** Opens a site's texture pack section (buttons in the link tab). */
	public static Job openSite(String siteName, String url) {
		Job job = new Job(url);
		JOBS.add(0, job);
		while (JOBS.size() > 15) {
			JOBS.remove(JOBS.size() - 1);
		}
		openInBrowser(job, URI.create(url), siteName);
		return job;
	}

	private static @Nullable String browserSite(URI u) {
		String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
		for (String[] site : BROWSER_SITES) {
			if (host.equals(site[0]) || host.endsWith("." + site[0])) {
				return site[1];
			}
		}
		return null;
	}

	private static @Nullable String modrinthSlug(URI u) {
		String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
		if (!host.equals("modrinth.com") && !host.equals("www.modrinth.com")) {
			return null;
		}
		Matcher m = Pattern.compile("^/(?:resourcepack|project|mod|datapack|shader)/([^/?#]+)").matcher(u.getRawPath() == null ? "" : u.getRawPath());
		return m.find() ? m.group(1) : null;
	}

	// --------------------------------------------------------------- MediaFire

	private static void runMediaFire(Job job, URI uri) {
		try {
			String folderKey = MediaFire.folderKey(uri);
			if (folderKey != null) {
				runFolder(job, folderKey);
				return;
			}
			String fileKey = MediaFire.fileKey(uri);
			if (fileKey == null) {
				throw new IOException("Unbekannter MediaFire-Link");
			}
			String direct;
			try {
				direct = MediaFire.resolveDirect(fileKey);
			} catch (IOException notAFile) {
				// old style links (mediafire.com/?key) can also be folders
				if (uri.getRawQuery() != null) {
					runFolder(job, fileKey);
					return;
				}
				throw notAFile;
			}
			List<String> files = downloadAndInstall(job, URI.create(direct), true);
			finish(job, files);
		} catch (Exception e) {
			fail(job, Downloads.message(e));
			PackBrowserClient.LOGGER.warn("MediaFire import failed: {}", job.url, e);
		}
	}

	private static void runFolder(Job job, String folderKey) throws IOException, InterruptedException {
		job.folder = true;
		job.fileName = "MediaFire-Ordner";
		List<MediaFire.MfFile> all = MediaFire.listFolder(folderKey);
		List<MediaFire.MfFile> zips = new ArrayList<>();
		for (MediaFire.MfFile f : all) {
			if (f.name().toLowerCase(Locale.ROOT).endsWith(".zip")) {
				zips.add(f);
			}
		}
		job.skipped = all.size() - zips.size();
		job.batchTotal = zips.size();
		if (zips.isEmpty()) {
			throw new IOException(all.isEmpty() ? "Der Ordner ist leer oder privat" : "Im Ordner sind keine .zip-Dateien");
		}

		List<String> installed = new ArrayList<>();
		boolean allPresent = true;
		for (int i = 0; i < zips.size(); i++) {
			MediaFire.MfFile f = zips.get(i);
			job.batchIndex = i + 1;
			job.fileName = f.name();
			job.done = 0;
			job.total = f.size() > 0 ? f.size() : -1;
			job.phase = Phase.DOWNLOADING;
			try {
				String direct = MediaFire.resolveDirect(f.quickKey());
				int before = installed.size();
				boolean[] present = {false};
				installed.addAll(downloadAndInstall(job, URI.create(direct), false, present));
				if (installed.size() > before && !present[0]) {
					allPresent = false;
				}
			} catch (Exception e) {
				job.failed++;
				PackBrowserClient.LOGGER.warn("MediaFire file {} failed", f.name(), e);
			}
			Thread.sleep(300);
		}
		if (installed.isEmpty()) {
			throw new IOException("Keine der " + zips.size() + " ZIPs war ein Texturepack");
		}
		job.alreadyPresent = allPresent;
		finish(job, installed);
	}

	// ----------------------------------------------------------------- common

	private static List<String> downloadAndInstall(Job job, URI uri, boolean setName) throws IOException {
		return downloadAndInstall(job, uri, setName, null);
	}

	private static List<String> downloadAndInstall(Job job, URI uri, boolean setName, boolean[] presentOut)
			throws IOException {
		ModrinthApi.Fetched fetched;
		try {
			fetched = ModrinthApi.fetchToTemp(uri, d -> job.done = d, t -> {
				if (t > 0) {
					job.total = t;
				}
			}).join();
		} catch (CompletionException e) {
			Throwable c = e.getCause() != null ? e.getCause() : e;
			throw c instanceof IOException io ? io : new IOException(Downloads.message(c), c);
		}
		if (setName) {
			job.fileName = fetched.fileName();
		}
		job.phase = Phase.INSTALLING;
		try {
			checkIsZip(fetched.file(), fetched.contentType());
			PackInstaller.Result result = PackInstaller.install(fetched.file(), fetched.fileName());
			if (presentOut != null) {
				presentOut[0] = result.alreadyPresent();
			} else {
				job.alreadyPresent = result.alreadyPresent();
			}
			return result.files();
		} finally {
			Files.deleteIfExists(fetched.file());
		}
	}

	private static void finish(Job job, List<String> files) {
		job.files = List.copyOf(files);
		InstalledPacks.markInstalled("link:" + job.url, String.join("|", files));
		job.phase = Phase.DONE;
	}

	private static void fail(Job job, String message) {
		job.error = message;
		job.phase = Phase.FAILED;
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
			throw new WebPageException();
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
