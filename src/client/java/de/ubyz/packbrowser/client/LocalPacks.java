package de.ubyz.packbrowser.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/** Scans a folder (Downloads by default) for texture pack zips and imports them. */
public final class LocalPacks {
	private static final int MAX_FILES = 80;
	private static final Map<String, Optional<PackInstaller.Info>> CACHE = new ConcurrentHashMap<>();
	private static final Set<String> IMPORTING = ConcurrentHashMap.newKeySet();

	public record Item(Path path, String fileName, long size, long modified, PackInstaller.Info info) {
		public String key() {
			return "local:" + this.fileName + ":" + this.size;
		}
	}

	private LocalPacks() {
	}

	public static CompletableFuture<List<Item>> scan(Path folder) {
		return CompletableFuture.supplyAsync(() -> {
			if (!Files.isDirectory(folder)) {
				throw new CompletionException(new IOException("Ordner nicht gefunden: " + folder));
			}
			List<Path> zips;
			try (Stream<Path> s = Files.list(folder)) {
				zips = s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
						.filter(Files::isRegularFile)
						.sorted(Comparator.comparingLong(LocalPacks::modified).reversed())
						.limit(MAX_FILES)
						.toList();
			} catch (IOException e) {
				throw new CompletionException(e);
			}
			List<Item> out = new ArrayList<>();
			for (Path p : zips) {
				long size;
				try {
					size = Files.size(p);
				} catch (IOException e) {
					continue;
				}
				long mod = modified(p);
				String cacheKey = p + "|" + size + "|" + mod;
				Optional<PackInstaller.Info> info = CACHE.computeIfAbsent(cacheKey,
						k -> Optional.ofNullable(PackInstaller.analyze(p)));
				info.ifPresent(i -> out.add(new Item(p, p.getFileName().toString(), size, mod, i)));
			}
			return out;
		}, ModrinthApi.executor());
	}

	public static long modified(Path p) {
		try {
			return Files.getLastModifiedTime(p).toMillis();
		} catch (IOException e) {
			return 0L;
		}
	}

	public static boolean importing(Item item) {
		return IMPORTING.contains(item.key());
	}

	public static boolean imported(Item item) {
		return InstalledPacks.isInstalled(item.key());
	}

	public static CompletableFuture<PackInstaller.Result> importPack(Item item) {
		IMPORTING.add(item.key());
		return CompletableFuture.supplyAsync(() -> {
			try {
				PackInstaller.Result result = PackInstaller.install(item.path(), item.fileName());
				InstalledPacks.markInstalled(item.key(), String.join("|", result.files()));
				return result;
			} catch (IOException e) {
				throw new CompletionException(e);
			} finally {
				IMPORTING.remove(item.key());
			}
		}, ModrinthApi.executor());
	}
}
