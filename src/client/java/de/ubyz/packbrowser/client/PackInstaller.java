package de.ubyz.packbrowser.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Turns any downloaded .zip into working resource packs in the resourcepacks folder.
 * Handles: normal packs, packs inside an extra folder, and zips that contain several pack zips.
 */
public final class PackInstaller {
	private static final long MAX_INNER = 512L * 1024 * 1024;
	private static final int MAX_META = 256 * 1024;
	private static final int MAX_ICON = 2 * 1024 * 1024;

	private PackInstaller() {
	}

	/** What a zip contains (for the list in the Downloads tab). */
	public record Info(int packCount, String name, String description, @Nullable Integer format,
					   byte @Nullable [] icon) {
	}

	public record Result(List<String> files, boolean alreadyPresent) {
	}

	// ----------------------------------------------------------------- analyse

	public static @Nullable Info analyze(Path zipPath) {
		try (ZipFile zip = new ZipFile(zipPath.toFile())) {
			List<String> names = names(zip);
			List<String> roots = packRoots(names);
			if (!roots.isEmpty()) {
				String root = roots.get(0);
				Meta meta = parseMeta(read(zip, root + "pack.mcmeta", MAX_META));
				byte[] icon = read(zip, root + "pack.png", MAX_ICON);
				String name = root.isEmpty() ? stripZip(zipPath.getFileName().toString()) : lastSegment(root);
				return new Info(roots.size(), name, meta.description, meta.format, icon);
			}
			// zip with pack zips inside
			int count = 0;
			Meta firstMeta = null;
			byte[] firstIcon = null;
			for (ZipEntry e : Collections.list(zip.entries())) {
				if (!isInnerZip(e)) {
					continue;
				}
				try (ZipInputStream in = new ZipInputStream(zip.getInputStream(e))) {
					Meta meta = null;
					byte[] icon = null;
					ZipEntry ie;
					while ((ie = in.getNextEntry()) != null) {
						String n = normalize(ie.getName());
						if (isRootFile(n, "pack.mcmeta")) {
							meta = parseMeta(readLimited(in, MAX_META));
						} else if (isRootFile(n, "pack.png") && icon == null) {
							icon = readLimited(in, MAX_ICON);
						}
					}
					if (meta != null) {
						count++;
						if (firstMeta == null) {
							firstMeta = meta;
							firstIcon = icon;
						}
					}
				} catch (IOException ignored) {
					// broken inner zip: ignore it
				}
			}
			if (count == 0) {
				return null;
			}
			return new Info(count, stripZip(zipPath.getFileName().toString()), firstMeta.description, firstMeta.format, firstIcon);
		} catch (IOException e) {
			return null;
		}
	}

	// ----------------------------------------------------------------- install

	public static Result install(Path zipPath, String suggestedName) throws IOException {
		Path dir = PackBrowserClient.resourcePackDir();
		Files.createDirectories(dir);
		List<String> out = new ArrayList<>();
		boolean[] allPresent = {true};
		installInto(zipPath, suggestedName, dir, out, allPresent, 0);
		if (out.isEmpty()) {
			throw new IOException(Text.NO_PACK);
		}
		return new Result(out, allPresent[0]);
	}

	private static void installInto(Path zipPath, String suggestedName, Path dir, List<String> out,
									boolean[] allPresent, int depth) throws IOException {
		try (ZipFile zip = new ZipFile(zipPath.toFile())) {
			List<String> names = names(zip);
			List<String> roots = packRoots(names);

			if (roots.size() == 1 && roots.get(0).isEmpty()) {
				Path tmp = Files.createTempFile("packbrowser-", ".zip");
				try (InputStream in = Files.newInputStream(zipPath)) {
					Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
				}
				out.add(place(tmp, dir, stripZip(suggestedName), allPresent));
				return;
			}

			if (!roots.isEmpty()) {
				for (String root : roots) {
					Path tmp = Files.createTempFile("packbrowser-", ".zip");
					rezip(zip, root, tmp);
					out.add(place(tmp, dir, lastSegment(root), allPresent));
				}
				return;
			}

			if (depth > 0) {
				return;
			}
			for (ZipEntry e : Collections.list(zip.entries())) {
				if (!isInnerZip(e)) {
					continue;
				}
				Path tmp = Files.createTempFile("packbrowser-inner-", ".zip");
				try {
					try (InputStream in = zip.getInputStream(e)) {
						Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
					}
					installInto(tmp, lastSegment(normalize(e.getName())), dir, out, allPresent, depth + 1);
				} catch (IOException ignored) {
					// not a valid zip: skip
				} finally {
					Files.deleteIfExists(tmp);
				}
			}
		}
	}

	/** Moves tmp into dir as name.zip; reuses an identical existing file instead of duplicating. */
	private static String place(Path tmp, Path dir, String baseName, boolean[] allPresent) throws IOException {
		String base = ModrinthApi.safeFileName(baseName);
		if (base.isBlank()) {
			base = "pack";
		}
		for (int i = 1; i < 100; i++) {
			String name = i == 1 ? base + ".zip" : base + " (" + i + ").zip";
			Path target = dir.resolve(name);
			if (!Files.exists(target)) {
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
				allPresent[0] = false;
				return name;
			}
			if (Files.size(target) == Files.size(tmp) && Files.mismatch(target, tmp) == -1L) {
				Files.deleteIfExists(tmp);
				return name;
			}
		}
		throw new IOException("Zu viele Dateien mit gleichem Namen");
	}

	private static void rezip(ZipFile zip, String prefix, Path target) throws IOException {
		try (OutputStream os = Files.newOutputStream(target); ZipOutputStream zos = new ZipOutputStream(os)) {
			for (ZipEntry e : Collections.list(zip.entries())) {
				String n = normalize(e.getName());
				if (!n.startsWith(prefix) || n.startsWith("__MACOSX/")) {
					continue;
				}
				String rel = n.substring(prefix.length());
				if (rel.isEmpty() || rel.startsWith("/")) {
					continue;
				}
				ZipEntry ne = new ZipEntry(rel);
				ne.setTime(e.getTime());
				zos.putNextEntry(ne);
				if (!e.isDirectory()) {
					try (InputStream in = zip.getInputStream(e)) {
						in.transferTo(zos);
					}
				}
				zos.closeEntry();
			}
		}
	}

	// ----------------------------------------------------------------- helpers

	private static List<String> names(ZipFile zip) {
		List<String> out = new ArrayList<>();
		for (ZipEntry e : Collections.list(zip.entries())) {
			out.add(normalize(e.getName()));
		}
		return out;
	}

	private static String normalize(String name) {
		return name.replace('\\', '/');
	}

	/** "" for a pack at the zip root, "Folder/" for a pack inside a folder. */
	static List<String> packRoots(List<String> names) {
		List<String> roots = new ArrayList<>();
		for (String n : names) {
			if (n.startsWith("__MACOSX/")) {
				continue;
			}
			if (n.equals("pack.mcmeta")) {
				roots.add("");
			} else if (n.endsWith("/pack.mcmeta")) {
				roots.add(n.substring(0, n.length() - "pack.mcmeta".length()));
			}
		}
		roots.sort(Comparator.comparingInt(String::length));
		List<String> out = new ArrayList<>();
		for (String r : roots) {
			boolean nested = false;
			for (String o : out) {
				if (r.startsWith(o)) {
					nested = true;
					break;
				}
			}
			if (!nested) {
				out.add(r);
			}
		}
		return out;
	}

	private static boolean isInnerZip(ZipEntry e) {
		String n = normalize(e.getName());
		return !e.isDirectory() && !n.startsWith("__MACOSX/") && n.toLowerCase(Locale.ROOT).endsWith(".zip")
				&& (e.getSize() < 0 || e.getSize() <= MAX_INNER);
	}

	private static boolean isRootFile(String n, String file) {
		if (n.equals(file)) {
			return true;
		}
		// one folder deep is fine too (we repackage it)
		int slash = n.indexOf('/');
		return slash > 0 && n.indexOf('/', slash + 1) < 0 && n.endsWith("/" + file) && !n.startsWith("__MACOSX/");
	}

	private static byte @Nullable [] read(ZipFile zip, String name, int limit) throws IOException {
		ZipEntry e = zip.getEntry(name);
		if (e == null) {
			e = zip.getEntry(name.replace('/', '\\'));
		}
		if (e == null || e.isDirectory()) {
			return null;
		}
		try (InputStream in = zip.getInputStream(e)) {
			return readLimited(in, limit);
		}
	}

	private static byte[] readLimited(InputStream in, int limit) throws IOException {
		byte[] data = in.readNBytes(limit + 1);
		if (data.length > limit) {
			throw new IOException("Datei zu groß");
		}
		return data;
	}

	public static String stripZip(String name) {
		return name.toLowerCase(Locale.ROOT).endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
	}

	private static String lastSegment(String path) {
		String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
		int slash = p.lastIndexOf('/');
		return stripZip(slash >= 0 ? p.substring(slash + 1) : p);
	}

	// ------------------------------------------------------------- pack.mcmeta

	private record Meta(String description, @Nullable Integer format) {
	}

	private static Meta parseMeta(byte @Nullable [] data) {
		if (data == null) {
			return new Meta("", null);
		}
		try {
			String json = new String(data, StandardCharsets.UTF_8);
			if (!json.isEmpty() && json.charAt(0) == '﻿') {
				json = json.substring(1);
			}
			JsonObject pack = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("pack");
			Integer format = null;
			if (pack.has("pack_format")) {
				format = pack.get("pack_format").getAsInt();
			} else if (pack.has("min_format")) {
				JsonElement m = pack.get("min_format");
				format = m.isJsonArray() ? m.getAsJsonArray().get(0).getAsInt() : m.getAsInt();
			}
			return new Meta(flatten(pack.get("description")), format);
		} catch (Exception e) {
			return new Meta("", null);
		}
	}

	private static String flatten(@Nullable JsonElement e) {
		if (e == null || e.isJsonNull()) {
			return "";
		}
		if (e.isJsonPrimitive()) {
			return e.getAsString();
		}
		StringBuilder sb = new StringBuilder();
		if (e.isJsonArray()) {
			for (JsonElement x : (JsonArray) e) {
				sb.append(flatten(x));
			}
		} else if (e.isJsonObject()) {
			JsonObject o = e.getAsJsonObject();
			if (o.has("text")) {
				sb.append(o.get("text").getAsString());
			} else if (o.has("translate")) {
				sb.append(o.get("translate").getAsString());
			}
			if (o.has("extra")) {
				sb.append(flatten(o.get("extra")));
			}
		}
		return sb.toString();
	}

	/** Rough Minecraft version for a pack format number. */
	public static String versionFor(int format) {
		return switch (format) {
			case 1 -> "1.6–1.8";
			case 2 -> "1.9–1.10";
			case 3 -> "1.11–1.12";
			case 4 -> "1.13–1.14";
			case 5 -> "1.15–1.16.1";
			case 6 -> "1.16.2–1.16.5";
			case 7 -> "1.17";
			case 8 -> "1.18";
			case 9 -> "1.19–1.19.2";
			case 12 -> "1.19.3";
			case 13 -> "1.19.4";
			case 15 -> "1.20–1.20.1";
			case 18 -> "1.20.2";
			case 22 -> "1.20.3–1.20.4";
			case 32 -> "1.20.5–1.20.6";
			case 34 -> "1.21–1.21.1";
			case 42 -> "1.21.2–1.21.3";
			case 46 -> "1.21.4";
			case 55 -> "1.21.5";
			case 63 -> "1.21.6";
			case 64 -> "1.21.7–1.21.8";
			case 69 -> "1.21.9–1.21.10";
			case 75 -> "1.21.11";
			case 97 -> "26.3";
			default -> format > 75 ? "26.x" : "?";
		};
	}

	/** Text constants used in exceptions (shown to the user). */
	public static final class Text {
		public static final String NO_PACK = "Keine pack.mcmeta gefunden – das ist kein Java-Texturepack";

		private Text() {
		}
	}
}
