package de.ubyz.packbrowser.client.gui;

import de.ubyz.packbrowser.client.IconCache;
import de.ubyz.packbrowser.client.LocalPacks;
import de.ubyz.packbrowser.client.PackInstaller;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public class LocalPackList extends ObjectSelectionList<LocalPackList.Entry> {
	private static final Identifier UNKNOWN_ICON = Identifier.withDefaultNamespace("textures/misc/unknown_pack.png");

	private final DownloadsScreen screen;

	public LocalPackList(Minecraft minecraft, int width, int height, int y, int itemHeight, DownloadsScreen screen) {
		super(minecraft, width, height, y, itemHeight);
		this.screen = screen;
	}

	public void setItems(List<LocalPacks.Item> items, @Nullable Path selected) {
		this.clearEntries();
		for (LocalPacks.Item item : items) {
			Entry entry = new Entry(item);
			this.addEntry(entry);
			if (item.path().equals(selected)) {
				super.setSelected(entry);
			}
		}
	}

	@Override
	public int getRowWidth() {
		return Math.min(440, this.width - 30);
	}

	@Override
	public void setSelected(@Nullable Entry entry) {
		super.setSelected(entry);
		this.screen.onSelected(entry == null ? null : entry.item);
	}

	static MutableComponent age(long modified) {
		long sec = Math.max(0, (System.currentTimeMillis() - modified) / 1000);
		if (sec < 60) {
			return Component.translatable("packbrowser.age.now");
		}
		if (sec < 3600) {
			return Component.translatable("packbrowser.age.minutes", sec / 60);
		}
		if (sec < 86400) {
			return Component.translatable("packbrowser.age.hours", sec / 3600);
		}
		return Component.translatable("packbrowser.age.days", sec / 86400);
	}

	static String size(long bytes) {
		if (bytes < 1024 * 1024) {
			return String.format(Locale.ROOT, "%d KB", Math.max(1, bytes / 1024));
		}
		return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
	}

	public final class Entry extends ObjectSelectionList.Entry<Entry> {
		final LocalPacks.Item item;

		Entry(LocalPacks.Item item) {
			this.item = item;
		}

		@Override
		public Component getNarration() {
			return Component.translatable("narrator.select", this.item.info().name());
		}

		private Component status() {
			if (LocalPacks.importing(this.item)) {
				return Component.literal("...").withStyle(ChatFormatting.YELLOW);
			}
			if (LocalPacks.imported(this.item)) {
				return Component.translatable("packbrowser.local.imported").withStyle(ChatFormatting.GREEN);
			}
			return age(this.item.modified()).withStyle(ChatFormatting.GRAY);
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float delta) {
			Font font = LocalPackList.this.minecraft.font;
			int x = this.getContentX();
			int y = this.getContentY();
			int width = this.getContentWidth();
			PackInstaller.Info info = this.item.info();

			Identifier icon = IconCache.getLocal(this.item.key() + ":" + this.item.modified(), info.icon());
			graphics.blit(RenderPipelines.GUI_TEXTURED, icon != null ? icon : UNKNOWN_ICON,
					x, y, 0.0F, 0.0F, 32, 32, 32, 32, ARGB.white(1.0F));

			int textX = x + 36;
			int textWidth = width - 38;

			Component status = this.status();
			int statusWidth = font.width(status);
			graphics.text(font, status, x + width - statusWidth - 2, y + 1, 0xFFFFFFFF);

			MutableComponent title = Component.literal(info.name()).withStyle(ChatFormatting.WHITE);
			if (info.packCount() > 1) {
				title.append(Component.literal(" ").append(Component.translatable("packbrowser.local.contains", info.packCount()))
						.withStyle(ChatFormatting.AQUA));
			}
			graphics.text(font, Language.getInstance().getVisualOrder(PackList.trim(font, title, textWidth - statusWidth - 6)),
					textX, y + 1, 0xFFFFFFFF);

			String description = info.description().replace('\n', ' ').replace('\r', ' ');
			graphics.text(font, Language.getInstance().getVisualOrder(
							PackList.trim(font, Component.literal(description.isBlank() ? " " : description), textWidth)),
					textX, y + 12, 0xFFAAAAAA);

			MutableComponent meta = Component.literal(this.item.fileName() + "  ·  " + size(this.item.size()))
					.withStyle(ChatFormatting.DARK_GRAY);
			Integer format = info.format();
			if (format != null) {
				meta.append(Component.literal("  ·  MC " + PackInstaller.versionFor(format))
						.withStyle(format <= 3 ? ChatFormatting.GOLD : ChatFormatting.DARK_GRAY));
				if (format <= 3) {
					meta.append(Component.translatable("packbrowser.local.old").withStyle(ChatFormatting.GOLD));
				}
			}
			graphics.text(font, Language.getInstance().getVisualOrder(PackList.trim(font, meta, textWidth)),
					textX, y + 23, 0xFF808080);
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			LocalPackList.this.setSelected(this);
			if (doubleClick) {
				LocalPackList.this.screen.importItem(this.item);
			}
			return true;
		}
	}
}
