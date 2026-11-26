package de.ubyz.packbrowser.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Remembers which Modrinth project / local file was saved under which file name(s), separated by "|". */
public final class InstalledPacks {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<String, String> FILES = new HashMap<>();
	private static boolean loaded;

	private InstalledPacks() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("packbrowser-installed.json");
	}

	private static synchronized void ensureLoaded() {
		if (loaded) {
			return;
		}
		loaded = true;
		try {
			Path f = file();
			if (Files.exists(f)) {
				JsonObject obj = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
				for (String key : obj.keySet()) {
					FILES.put(key, obj.get(key).getAsString());
				}
			}
		} catch (Exception e) {
			PackBrowserClient.LOGGER.warn("Could not read installed pack list", e);
		}
	}

	public static synchronized boolean isInstalled(String projectId) {
		ensureLoaded();
		String names = FILES.get(projectId);
		if (names == null) {
			return false;
		}
		for (String name : names.split("\\|")) {
			if (!name.isEmpty() && Files.exists(PackBrowserClient.resourcePackDir().resolve(name))) {
				return true;
			}
		}
		return false;
	}

	public static synchronized void markInstalled(String projectId, String fileName) {
		ensureLoaded();
		FILES.put(projectId, fileName);
		try {
			JsonObject obj = new JsonObject();
			FILES.forEach(obj::addProperty);
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(obj), StandardCharsets.UTF_8);
		} catch (Exception e) {
			PackBrowserClient.LOGGER.warn("Could not save installed pack list", e);
		}
	}
}
