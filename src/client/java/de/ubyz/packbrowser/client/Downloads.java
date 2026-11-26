package de.ubyz.packbrowser.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/** Keeps track of running and finished downloads (survives closing the screen). */
public final class Downloads {
	public enum Phase { RESOLVING, DOWNLOADING, DONE, FAILED }

	public static final class State {
		public final String title;
		public volatile Phase phase = Phase.RESOLVING;
		public volatile long done;
		public volatile long total;
		public volatile String fileName = "";
		public volatile String versionName = "";
		public volatile String error = "";
		public volatile boolean versionMismatch;
		public volatile boolean alreadyExisted;

		State(String title) {
			this.title = title;
		}

		public boolean running() {
			return phase == Phase.RESOLVING || phase == Phase.DOWNLOADING;
		}

		public int percent() {
			return total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
		}
	}

	private static final Map<String, State> STATES = new ConcurrentHashMap<>();

	private Downloads() {
	}

	public static State get(String projectId) {
		return STATES.get(projectId);
	}

	public static State start(ModrinthApi.Hit hit) {
		State existing = STATES.get(hit.id());
		if (existing != null && existing.running()) {
			return existing;
		}
		State st = new State(hit.title());
		STATES.put(hit.id(), st);

		String gameVersion = PackBrowserClient.gameVersion();
		ModrinthApi.resolveFile(hit.id(), gameVersion).thenCompose(file -> {
			st.fileName = ModrinthApi.safeFileName(file.filename());
			st.versionName = file.versionName();
			st.total = file.size();
			st.versionMismatch = !file.matchesGameVersion();
			Path target = PackBrowserClient.resourcePackDir().resolve(st.fileName);
			if (Files.exists(target)) {
				st.alreadyExisted = true;
				return CompletableFuture.completedFuture(target);
			}
			st.phase = Phase.DOWNLOADING;
			return ModrinthApi.download(file, target, d -> st.done = d);
		}).whenComplete((path, err) -> {
			if (err != null) {
				st.error = message(err);
				st.phase = Phase.FAILED;
				PackBrowserClient.LOGGER.warn("Download of {} failed", hit.title(), err);
			} else {
				InstalledPacks.markInstalled(hit.id(), st.fileName);
				st.phase = Phase.DONE;
				PackBrowserClient.LOGGER.info("Downloaded {} -> {}", hit.title(), path);
			}
		});
		return st;
	}

	public static String message(Throwable t) {
		while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
			t = t.getCause();
		}
		String msg = t.getMessage();
		if (t instanceof java.net.ConnectException || t instanceof java.nio.channels.UnresolvedAddressException) {
			return "Keine Verbindung zu Modrinth";
		}
		if (t instanceof java.net.http.HttpTimeoutException) {
			return "Zeitüberschreitung";
		}
		return msg == null || msg.isBlank() ? t.getClass().getSimpleName() : msg;
	}
}
