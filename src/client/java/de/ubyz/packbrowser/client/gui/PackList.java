package de.ubyz.packbrowser.client.gui;

import de.ubyz.packbrowser.client.Downloads;
import de.ubyz.packbrowser.client.IconCache;
import de.ubyz.packbrowser.client.InstalledPacks;
import de.ubyz.packbrowser.client.ModrinthApi;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;

public class PackList extends ObjectSelectionList<PackList.Entry> {
	private static final Identifier UNKNOWN_ICON = Identifier.withDefaultNamespace("textures/misc/unknown_pack.png");

	private final PackBrowserScreen screen;

	public PackList(Minecraft minecraft, int width, int height, int y, int itemHeight, PackBrowserScreen screen) {
		super(minecraft, width, height, y, itemHeight);
		this.screen = screen;
	}

	public void setHits(List<ModrinthApi.Hit> hits, @Nullable String selectedId) {
		this.clearEntries();
		for (ModrinthApi.Hit hit : hits) {
			Entry entry = new Entry(hit);
			this.addEntry(entry);
			if (hit.id().equals(selectedId)) {
				super.setSelected(entry);
			}
		}
	}

	public void appendHits(List<ModrinthApi.Hit> hits) {
		for (ModrinthApi.Hit hit : hits) {
			this.addEntry(new Entry(hit));
		}
	}

	public boolean nearBottom() {
		int max = Math.max(0, this.contentHeight() - (this.getBottom() - this.getY() - 4));
		return this.scrollAmount() >= max - 40;
	}

	@Override
	public int getRowWidth() {
		return Math.min(440, this.width - 30);
	}

	@Override
	public void setSelected(@Nullable Entry entry) {
		super.setSelected(entry);
		this.screen.onSelected(entry == null ? null : entry.hit);
	}

	static FormattedText trim(Font font, FormattedText text, int maxWidth) {
		if (font.width(text) <= maxWidth) {
			return text;
		}
		FormattedText ellipsis = FormattedText.of("...");
		return FormattedText.composite(font.substrByWidth(text, Math.max(0, maxWidth - font.width(ellipsis))), ellipsis);
	}

	static String compact(long n) {
		if (n < 1_000) {
			return Long.toString(n);
		}
		if (n < 1_000_000) {
			return String.format(Locale.ROOT, "%.1fk", n / 1_000.0);
		}
		return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
	}

	public final class Entry extends ObjectSelectionList.Entry<Entry> {
		final ModrinthApi.Hit hit;
		private final boolean installedAtStart;

		Entry(ModrinthApi.Hit hit) {
			this.hit = hit;
			this.installedAtStart = InstalledPacks.isInstalled(hit.id());
		}

		@Override
		public Component getNarration() {
			return Component.translatable("narrator.select", this.hit.title());
		}

		private Component status() {
			Downloads.State st = Downloads.get(this.hit.id());
			if (st != null && st.running()) {
				String text = st.phase == Downloads.Phase.RESOLVING ? "..." : st.percent() + "%";
				return Component.literal(text).withStyle(ChatFormatting.YELLOW);
			}
			if (st != null && st.phase == Downloads.Phase.FAILED) {
				return Component.translatable("packbrowser.entry.failed").withStyle(ChatFormatting.RED);
			}
			if ((st != null && st.phase == Downloads.Phase.DONE) || this.installedAtStart) {
				return Component.translatable("packbrowser.entry.installed").withStyle(ChatFormatting.GREEN);
			}
			return Component.literal("⬇ " + compact(this.hit.downloads())).withStyle(ChatFormatting.GRAY);
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float delta) {
			Font font = PackList.this.minecraft.font;
			int x = this.getContentX();
			int y = this.getContentY();
			int width = this.getContentWidth();

			Identifier icon = IconCache.get(this.hit.id(), this.hit.iconUrl());
			graphics.blit(RenderPipelines.GUI_TEXTURED, icon != null ? icon : UNKNOWN_ICON,
					x, y, 0.0F, 0.0F, 32, 32, 32, 32, ARGB.white(1.0F));

			int textX = x + 36;
			int textWidth = width - 38;

			Component status = this.status();
			int statusWidth = font.width(status);
			graphics.text(font, status, x + width - statusWidth - 2, y + 1, 0xFFFFFFFF);

			Component title = Component.literal(this.hit.title()).withStyle(ChatFormatting.WHITE)
					.append(Component.literal(" ").append(Component.translatable("packbrowser.entry.by", this.hit.author()))
							.withStyle(ChatFormatting.DARK_GRAY));
			graphics.text(font, Language.getInstance().getVisualOrder(trim(font, title, textWidth - statusWidth - 6)),
					textX, y + 1, 0xFFFFFFFF);

			String description = this.hit.description().replace('\n', ' ').replace('\r', ' ');
			graphics.text(font, Language.getInstance().getVisualOrder(trim(font, Component.literal(description), textWidth)),
					textX, y + 12, 0xFFAAAAAA);

			StringBuilder meta = new StringBuilder("MC ").append(this.hit.versionRange());
			if (!this.hit.categories().isEmpty()) {
				meta.append("  ·  ").append(String.join(", ", this.hit.categories()));
			}
			graphics.text(font, Language.getInstance().getVisualOrder(trim(font, Component.literal(meta.toString()), textWidth)),
					textX, y + 23, 0xFF808080);
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			PackList.this.setSelected(this);
			if (doubleClick) {
				PackList.this.screen.startDownload(this.hit);
			}
			return true;
		}
	}
}
