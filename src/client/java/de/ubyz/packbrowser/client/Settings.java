package de.ubyz.packbrowser.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Small persistent settings (config/packbrowser.json). */
public final class Settings {
	private static JsonObject data;

	private Settings() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("packbrowser.json");
	}

	private static synchronized JsonObject data() {
		if (data == null) {
			data = new JsonObject();
			try {
				if (Files.exists(file())) {
					data = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonObject();
				}
			} catch (Exception e) {
				PackBrowserClient.LOGGER.warn("Could not read settings", e);
			}
		}
		return data;
	}

	private static synchronized void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), new GsonBuilder().setPrettyPrinting().create().toJson(data()), StandardCharsets.UTF_8);
		} catch (Exception e) {
			PackBrowserClient.LOGGER.warn("Could not save settings", e);
		}
	}

	public static Path importFolder() {
		JsonObject d = data();
		if (d.has("importFolder")) {
			try {
				return Path.of(d.get("importFolder").getAsString());
			} catch (Exception ignored) {
			}
		}
		return defaultDownloads();
	}

	public static void setImportFolder(Path folder) {
		data().addProperty("importFolder", folder.toString());
		save();
	}

	public static Path defaultDownloads() {
		Path home = Path.of(System.getProperty("user.home"));
		Path[] candidates = {
				home.resolve("Downloads"),
				home.resolve("OneDrive").resolve("Downloads"),
				home.resolve("Download")
		};
		for (Path p : candidates) {
			if (Files.isDirectory(p)) {
				return p;
			}
		}
		return candidates[0];
	}
}
