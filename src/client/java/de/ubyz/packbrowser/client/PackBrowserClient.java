package de.ubyz.packbrowser.client;

import de.ubyz.packbrowser.client.gui.PackBrowserScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class PackBrowserClient implements ClientModInitializer {
	public static final String MOD_ID = "packbrowser";
	public static final Logger LOGGER = LoggerFactory.getLogger("PackBrowser");

	@Override
	public void onInitializeClient() {
		ScreenEvents.AFTER_INIT.register(PackBrowserClient::afterScreenInit);
		LOGGER.info("Pack Browser loaded (Minecraft {})", gameVersion());
	}

	private static void afterScreenInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
		if (!(screen instanceof PackSelectionScreen) || isDataPackScreen(screen)) {
			return;
		}

		Button open = Button.builder(Component.translatable("packbrowser.button"),
						button -> client.gui.setScreen(new PackBrowserScreen(screen)))
				.pos(4, 4)
				.size(84, 20)
				.tooltip(Tooltip.create(Component.translatable("packbrowser.button.tooltip")))
				.build();
		Screens.getWidgets(screen).add(open);
	}

	private static boolean isDataPackScreen(Screen screen) {
		return screen.getTitle().getContents() instanceof TranslatableContents contents
				&& contents.getKey().startsWith("dataPack");
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
