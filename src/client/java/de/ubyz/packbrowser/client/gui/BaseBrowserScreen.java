package de.ubyz.packbrowser.client.gui;

import com.mojang.blaze3d.Blaze3D;
import de.ubyz.packbrowser.client.PackBrowserClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;

/** Shared layout: tab row on top, status line and footer at the bottom. */
public abstract class BaseBrowserScreen extends Screen {
	public static final int TAB_MODRINTH = 0;
	public static final int TAB_DOWNLOADS = 1;
	public static final int TAB_LINK = 2;

	protected static final int CONTROLS_Y = 28;
	protected static final int LIST_TOP = 52;
	protected static final int LIST_BOTTOM_MARGIN = 52;

	private static final String[] TAB_KEYS = {"packbrowser.tab.modrinth", "packbrowser.tab.downloads", "packbrowser.tab.link"};

	/** Tab that is opened by the "Pack Browser" button (last used one). */
	public static int lastTab = TAB_MODRINTH;

	protected final Screen parent;
	private final int tab;
	private @Nullable Component flash;
	private long flashUntil;

	protected BaseBrowserScreen(Screen parent, int tab) {
		super(Component.translatable(TAB_KEYS[tab]));
		this.parent = parent;
		this.tab = tab;
		lastTab = tab;
	}

	public static Screen create(int tab, Screen parent) {
		return switch (tab) {
			case TAB_DOWNLOADS -> new DownloadsScreen(parent);
			case TAB_LINK -> new LinkScreen(parent);
			default -> new PackBrowserScreen(parent);
		};
	}

	/** Adds the three tab buttons. Call first in init(). */
	protected void addTabs() {
		int gap = 4;
		int w = Math.min(120, (this.width - 16 - 2 * gap) / 3);
		int x = this.width / 2 - (3 * w + 2 * gap) / 2;
		for (int i = 0; i < 3; i++) {
			final int target = i;
			Button b = Button.builder(Component.translatable(TAB_KEYS[i]), button -> {
						if (target != this.tab) {
							this.saveState();
							Minecraft.getInstance().gui.setScreen(create(target, this.parent));
						}
					})
					.pos(x + i * (w + gap), 4).size(w, 20)
					.build();
			b.active = i != this.tab;
			this.addRenderableWidget(b);
		}
	}

	/** Width/left of the controls row. */
	protected int controlsWidth() {
		return Math.min(this.width - 12, 560);
	}

	protected int controlsLeft() {
		return (this.width - this.controlsWidth()) / 2;
	}

	protected int listHeight() {
		return this.height - LIST_TOP - LIST_BOTTOM_MARGIN;
	}

	/** Adds up to three equally sized footer buttons. */
	protected Button[] addFooter(Button.Builder... builders) {
		int gap = 6;
		int n = builders.length;
		int w = Math.min(130, (this.width - 16 - (n - 1) * gap) / n);
		int x = this.width / 2 - (n * w + (n - 1) * gap) / 2;
		Button[] out = new Button[n];
		for (int i = 0; i < n; i++) {
			out[i] = this.addRenderableWidget(builders[i].pos(x + i * (w + gap), this.height - 26).size(w, 20).build());
		}
		return out;
	}

	protected void showFlash(Component message) {
		this.flash = message;
		this.flashUntil = Util.getMillis() + 7000;
	}

	/** Status line shown above the footer when no flash message is active. */
	protected abstract Component status();

	/** Remember per-tab state before switching or closing. */
	protected void saveState() {
	}

	protected static void openFolder(Path dir) {
		try {
			Files.createDirectories(dir);
			Blaze3D.openUri(dir.toUri());
		} catch (Exception e) {
			PackBrowserClient.LOGGER.warn("Could not open {}", dir, e);
		}
	}

	protected static Button.Builder button(String key, Button.OnPress onPress) {
		return Button.builder(Component.translatable(key), onPress);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		Component line = this.flash != null && Util.getMillis() < this.flashUntil ? this.flash : this.status();
		graphics.centeredText(this.font, line, this.width / 2, this.height - 40, 0xFFFFFFFF);
	}

	@Override
	public void onClose() {
		this.saveState();
		this.minecraft.gui.setScreen(this.parent);
	}
}
