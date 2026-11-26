package de.ubyz.packbrowser.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Downloads Modrinth project icons (png/jpg/gif/webp) and turns them into GUI textures. */
public final class IconCache {
	private static final int SIZE = 64;
	private static final Map<String, Identifier> LOADED = new ConcurrentHashMap<>();
	private static final Set<String> REQUESTED = ConcurrentHashMap.newKeySet();

	private IconCache() {
	}

	public static @Nullable Identifier get(String projectId, String url) {
		Identifier id = LOADED.get(projectId);
		if (id != null) {
			return id;
		}
		if (url != null && !url.isEmpty() && REQUESTED.add(projectId)) {
			ModrinthApi.getBytes(url)
					.thenApplyAsync(IconCache::toPng, ModrinthApi.executor())
					.thenAccept(png -> Minecraft.getInstance().execute(() -> register(projectId, png)))
					.exceptionally(err -> {
						PackBrowserClient.LOGGER.debug("Icon for {} failed: {}", projectId, err.toString());
						return null;
					});
		}
		return null;
	}

	/** Icon from raw image bytes (e.g. pack.png inside a downloaded zip). */
	public static @Nullable Identifier getLocal(String key, byte @Nullable [] data) {
		Identifier id = LOADED.get(key);
		if (id != null) {
			return id;
		}
		if (data != null && data.length > 0 && REQUESTED.add(key)) {
			java.util.concurrent.CompletableFuture.supplyAsync(() -> toPng(data), ModrinthApi.executor())
					.thenAccept(png -> Minecraft.getInstance().execute(() -> register(key, png)))
					.exceptionally(err -> null);
		}
		return null;
	}

	private static void register(String projectId, byte[] png) {
		try {
			NativeImage image = NativeImage.read(new ByteArrayInputStream(png));
			Identifier id = Identifier.fromNamespaceAndPath(PackBrowserClient.MOD_ID, "icons/" + textureName(projectId));
			DynamicTexture texture = new DynamicTexture(() -> "packbrowser icon " + projectId, image);
			Minecraft.getInstance().getTextureManager().register(id, texture);
			LOADED.put(projectId, id);
		} catch (Throwable t) {
			PackBrowserClient.LOGGER.debug("Could not register icon for {}", projectId, t);
		}
	}

	private static String textureName(String projectId) {
		String clean = projectId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
		if (clean.length() > 40) {
			clean = clean.substring(clean.length() - 40);
		}
		return clean + "_" + Integer.toHexString(projectId.hashCode());
	}

	/** Decodes any supported format, scales it to 64x64 and re-encodes as PNG for NativeImage. */
	private static byte[] toPng(byte[] data) {
		try {
			BufferedImage src = isWebp(data) ? readWebp(data) : ImageIO.read(new ByteArrayInputStream(data));
			if (src == null) {
				throw new IOException("Unbekanntes Bildformat");
			}
			BufferedImage dst = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = dst.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
					Math.max(src.getWidth(), src.getHeight()) <= 32
							? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
							: RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			// keep aspect ratio, centre in the square
			double scale = Math.min((double) SIZE / src.getWidth(), (double) SIZE / src.getHeight());
			int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
			int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
			g.drawImage(src, (SIZE - w) / 2, (SIZE - h) / 2, w, h, null);
			g.dispose();
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(dst, "png", out);
			return out.toByteArray();
		} catch (IOException e) {
			throw new java.util.concurrent.CompletionException(e);
		}
	}

	private static boolean isWebp(byte[] d) {
		return d.length > 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
				&& d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P';
	}

	private static BufferedImage readWebp(byte[] data) throws IOException {
		ImageReader reader = new com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi().createReaderInstance(null);
		try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
			reader.setInput(in);
			return reader.read(0);
		} finally {
			reader.dispose();
		}
	}
}
