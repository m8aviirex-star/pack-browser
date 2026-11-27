package de.ubyz.packbrowser.client;

import de.ubyz.packbrowser.client.gui.BaseBrowserScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PackBrowserClient implements ClientModInitializer {
	public static final String MOD_ID = "packbrowser";
	public static final Logger LOGGER = LoggerFactory.getLogger("PackBrowser");

	private static final int BUTTON_W = 84;
	private static final int BUTTON_H = 20;
	private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

	@Override
	public void onInitializeClient() {
		ScreenEvents.AFTER_INIT.register(PackBrowserClient::afterScreenInit);
		LOGGER.info("Pack Browser {} loaded (Minecraft {})", modVersion(), gameVersion());
	}

	private static void afterScreenInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
		try {
			List<AbstractWidget> widgets = Screens.getWidgets(screen);

			if (isResourcePackScreen(screen, widgets)) {
				int[] spot = findFreeSpot(screen, widgets, BUTTON_W, BUTTON_H);
				widgets.add(Button.builder(Component.translatable("packbrowser.button"),
								button -> client.gui.setScreen(BaseBrowserScreen.create(BaseBrowserScreen.lastTab, screen)))
						.pos(spot[0], spot[1])
						.size(BUTTON_W, BUTTON_H)
						.tooltip(Tooltip.create(Component.translatable("packbrowser.button.tooltip")))
						.build());
				logOnce(screen, "button added at " + spot[0] + "," + spot[1]);
				return;
			}

			// Second way in: a small button next to "Resource Packs..." in the options menu.
			Button resourcePacks = findButton(widgets, "options.resourcepack");
			if (resourcePacks != null && resourcePacks.getWidth() > 60) {
				resourcePacks.setWidth(resourcePacks.getWidth() - 24);
				widgets.add(Button.builder(Component.literal("⬇"),
								button -> client.gui.setScreen(BaseBrowserScreen.create(BaseBrowserScreen.lastTab, screen)))
						.pos(resourcePacks.getX() + resourcePacks.getWidth() + 4, resourcePacks.getY())
						.size(20, resourcePacks.getHeight())
						.tooltip(Tooltip.create(Component.translatable("packbrowser.button.tooltip")))
						.build());
				logOnce(screen, "options button added");
			} else if (screen.getClass().getName().toLowerCase(Locale.ROOT).contains("pack")) {
				logOnce(screen, "not recognised as resource pack screen");
			}
		} catch (Throwable t) {
			LOGGER.warn("Could not add Pack Browser button to {}", screen.getClass().getName(), t);
		}
	}

	private static void logOnce(Screen screen, String what) {
		String key = screen.getClass().getName() + "|" + what;
		if (LOGGED.add(key)) {
			LOGGER.info("Pack Browser: {} ({}, title key: {})", what, screen.getClass().getName(), titleKey(screen));
		}
	}

	/** Vanilla pack screen, or a replacement from another mod (e.g. a custom resource pack menu). */
	private static boolean isResourcePackScreen(Screen screen, List<AbstractWidget> widgets) {
		String key = titleKey(screen);
		if (key != null && key.toLowerCase(Locale.ROOT).startsWith("datapack")) {
			return false;
		}
		if (screen instanceof PackSelectionScreen) {
			return true;
		}
		if ("resourcePack.title".equals(key)) {
			return true;
		}
		String name = screen.getClass().getName().toLowerCase(Locale.ROOT);
		if (name.contains("datapack")) {
			return false;
		}
		if (name.contains("resourcepack") || name.contains("packselection") || name.contains("packscreen")) {
			return true;
		}
		// a screen with the "Open Pack Folder" button is a pack screen too
		return findButton(widgets, "pack.openFolder") != null;
	}

	private static @Nullable String titleKey(Screen screen) {
		ComponentContents contents = screen.getTitle().getContents();
		return contents instanceof TranslatableContents t ? t.getKey() : null;
	}

	private static @Nullable Button findButton(List<AbstractWidget> widgets, String translationKey) {
		for (AbstractWidget w : widgets) {
			if (w instanceof Button b && b.getMessage().getContents() instanceof TranslatableContents t
					&& t.getKey().equals(translationKey)) {
				return b;
			}
		}
		return null;
	}

	/** Top-right first (usually empty), then top-left, then the bottom corners, then anywhere in the top row. */
	private static int[] findFreeSpot(Screen screen, List<AbstractWidget> widgets, int w, int h) {
		List<int[]> candidates = new ArrayList<>();
		candidates.add(new int[]{screen.width - w - 4, 4});
		candidates.add(new int[]{4, 4});
		candidates.add(new int[]{screen.width - w - 4, screen.height - h - 4});
		candidates.add(new int[]{4, screen.height - h - 4});
		for (int x = 4; x + w <= screen.width - 4; x += 8) {
			candidates.add(new int[]{x, 4});
		}
		for (int[] c : candidates) {
			if (isFree(widgets, c[0], c[1], w, h)) {
				return c;
			}
		}
		return candidates.get(0);
	}

	private static boolean isFree(List<AbstractWidget> widgets, int x, int y, int w, int h) {
		for (AbstractWidget a : widgets) {
			if (!a.visible) {
				continue;
			}
			if (x < a.getX() + a.getWidth() && x + w > a.getX() && y < a.getY() + a.getHeight() && y + h > a.getY()) {
				return false;
			}
		}
		return true;
	}

	public static String gameVersion() {
		return FabricLoader.getInstance().getModContainer("minecraft")
				.map(c -> c.getMetadata().getVersion().getFriendlyString())
				.orElse("26.3");
	}

	public static String modVersion() {
		return FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString())
				.orElse("dev");
	}

	public static Path resourcePackDir() {
		return FabricLoader.getInstance().getGameDir().resolve("resourcepacks");
	}
}
