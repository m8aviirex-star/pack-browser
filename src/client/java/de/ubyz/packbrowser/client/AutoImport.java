package de.ubyz.packbrowser.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * After a site was opened in the browser (PvPRP, CurseForge, PacksMC, ...), watches the Downloads folder
 * and imports every new texture pack zip automatically.
 */
public final class AutoImport {
	private static final long WINDOW_MS = 10 * 60 * 1000L;

	public record Event(String fileName, List<String> packs, String error, long time) {
		public boolean ok() {
			return this.error.isEmpty();
		}
	}

	private static volatile long armedSince;
	private static volatile long armedUntil;
	private static volatile String site = "";

	private static final Map<Path, Long> LAST_SIZE = new ConcurrentHashMap<>();
	private static final Set<Path> HANDLED = ConcurrentHashMap.newKeySet();
	private static final List<Event> EVENTS = new CopyOnWriteArrayList<>();

	private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "PackBrowser-AutoImport");
		t.setDaemon(true);
		return t;
	});

	static {
		TIMER.scheduleWithFixedDelay(AutoImport::poll, 2, 2, TimeUnit.SECONDS);
	}

	private AutoImport() {
	}

	/** Start (or extend) watching the Downloads folder. */
	public static void arm(String siteName) {
		long now = System.currentTimeMillis();
		if (!armed()) {
			armedSince = now - 5000;
		}
		armedUntil = now + WINDOW_MS;
		site = siteName;
	}

	public static void stop() {
		armedUntil = 0;
	}

	public static boolean armed() {
		return System.currentTimeMillis() < armedUntil;
	}

	public static long secondsLeft() {
		return Math.max(0, (armedUntil - System.currentTimeMillis()) / 1000);
	}

	public static String site() {
		return site;
	}

	public static List<Event> events() {
		return EVENTS;
	}

	private static void poll() {
		if (!armed()) {
			return;
		}
		try {
			Path dir = Settings.importFolder();
			if (!Files.isDirectory(dir)) {
				return;
			}
			List<Path> fresh;
			try (Stream<Path> s = Files.list(dir)) {
				fresh = s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
						.filter(Files::isRegularFile)
						.filter(p -> LocalPacks.modified(p) >= armedSince)
						.filter(p -> !HANDLED.contains(p))
						.toList();
			}
			for (Path p : fresh) {
				long size = Files.size(p);
				Long before = LAST_SIZE.put(p, size);
				if (before == null || before != size || size == 0) {
					continue; // still being written – check again next round
				}
				HANDLED.add(p);
				handle(p, size);
			}
		} catch (Exception e) {
			PackBrowserClient.LOGGER.debug("Auto import poll failed", e);
		}
	}

	private static void handle(Path p, long size) {
		String name = p.getFileName().toString();
		if (PackInstaller.analyze(p) == null) {
			add(new Event(name, List.of(), "kein Texturepack", System.currentTimeMillis()));
			return;
		}
		try {
			PackInstaller.Result result = PackInstaller.install(p, name);
			InstalledPacks.markInstalled("local:" + name + ":" + size, String.join("|", result.files()));
			add(new Event(name, result.files(), "", System.currentTimeMillis()));
			// keep watching a bit longer – people often download several packs in a row
			armedUntil = Math.max(armedUntil, System.currentTimeMillis() + 3 * 60 * 1000L);
			PackBrowserClient.LOGGER.info("Auto-imported {} -> {}", name, result.files());
		} catch (IOException e) {
			add(new Event(name, List.of(), Downloads.message(e), System.currentTimeMillis()));
		}
	}

	private static void add(Event e) {
		EVENTS.add(0, e);
		while (EVENTS.size() > 20) {
			EVENTS.remove(EVENTS.size() - 1);
		}
	}
}
