package de.ubyz.packbrowser.client.gui;

import de.ubyz.packbrowser.client.AutoImport;
import de.ubyz.packbrowser.client.LinkImports;
import de.ubyz.packbrowser.client.PackBrowserClient;
import de.ubyz.packbrowser.client.PackInstaller;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Tab 3: paste links (MediaFire incl. folders, Modrinth, GitHub, Dropbox, ...) or open pack sites with auto import. */
public class LinkScreen extends BaseBrowserScreen {
	private static final String[][] SITES = {
			{"PvPRP", "https://pvprp.com/pvp-texture-packs"},
			{"CurseForge", "https://www.curseforge.com/minecraft/texture-packs"},
			{"PacksMC", "https://packsmc.com/texture-packs"},
			{"Planet Minecraft", "https://www.planetminecraft.com/texture-packs/"}
	};
	private static final int SITES_Y = LIST_TOP;

	private static String lastUrl = "";

	private @Nullable EditBox urlBox;
	private @Nullable Button stopButton;

	public LinkScreen(Screen parent) {
		super(parent, TAB_LINK);
	}

	@Override
	protected void init() {
		this.addTabs();

		int gap = 4;
		int controlsWidth = this.controlsWidth();
		int left = this.controlsLeft();
		int buttonWidth = 90;
		int boxWidth = controlsWidth - buttonWidth - gap;

		this.urlBox = new EditBox(this.font, left, CONTROLS_Y, boxWidth, 20, this.urlBox,
				Component.translatable("packbrowser.link.field"));
		this.urlBox.setMaxLength(4096);
		this.urlBox.setHint(Component.translatable("packbrowser.link.hint").withStyle(ChatFormatting.DARK_GRAY));
		this.urlBox.setValue(lastUrl);
		this.urlBox.setResponder(text -> lastUrl = text);
		this.addRenderableWidget(this.urlBox);

		this.addRenderableWidget(button("packbrowser.link.load", b -> this.load())
				.pos(left + boxWidth + gap, CONTROLS_Y).size(buttonWidth, 20).build());

		// quick buttons: open a pack site in the browser, downloads get imported automatically
		int n = SITES.length;
		int siteWidth = (controlsWidth - (n - 1) * gap) / n;
		for (int i = 0; i < n; i++) {
			String name = SITES[i][0];
			String url = SITES[i][1];
			this.addRenderableWidget(Button.builder(Component.literal("↗ " + name), b -> {
						LinkImports.openSite(name, url);
						this.showFlash(Component.translatable("packbrowser.flash.browser", name).withStyle(ChatFormatting.AQUA));
					})
					.pos(left + i * (siteWidth + gap), SITES_Y).size(siteWidth, 20)
					.tooltip(Tooltip.create(Component.translatable("packbrowser.link.site.tooltip", name)))
					.build());
		}

		Button[] footer = this.addFooter(
				button("packbrowser.link.stop", b -> AutoImport.stop()),
				button("packbrowser.openPackFolder", b -> openFolder(PackBrowserClient.resourcePackDir())),
				Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()));
		this.stopButton = footer[0];
		this.stopButton.active = AutoImport.armed();

		this.setInitialFocus(this.urlBox);
	}

	private void load() {
		if (this.urlBox == null || this.urlBox.getValue().isBlank()) {
			this.showFlash(Component.translatable("packbrowser.link.empty").withStyle(ChatFormatting.RED));
			return;
		}
		LinkImports.startAll(this.urlBox.getValue());
		this.urlBox.setValue("");
	}

	@Override
	public boolean keyPressed(KeyEvent input) {
		if (this.urlBox != null && this.urlBox.isFocused() && input.isConfirmation()) {
			this.load();
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public void tick() {
		super.tick();
		if (this.stopButton != null) {
			this.stopButton.active = AutoImport.armed();
		}
		for (LinkImports.Job job : LinkImports.jobs()) {
			if (job.reported || job.running()) {
				continue;
			}
			job.reported = true;
			if (job.phase == LinkImports.Phase.FAILED) {
				this.showFlash(Component.translatable("packbrowser.link.failed").withStyle(ChatFormatting.RED));
			} else if (job.browser) {
				this.showFlash(Component.translatable("packbrowser.flash.browser", job.site).withStyle(ChatFormatting.AQUA));
			} else if (job.alreadyPresent) {
				this.showFlash(Component.translatable("packbrowser.flash.exists", String.join(", ", job.files)).withStyle(ChatFormatting.YELLOW));
			} else if (job.files.size() > 1) {
				this.showFlash(Component.translatable("packbrowser.flash.multi", job.files.size(), String.join(", ", job.files)).withStyle(ChatFormatting.GREEN));
			} else if (!job.files.isEmpty()) {
				this.showFlash(Component.translatable("packbrowser.flash.done", PackInstaller.stripZip(job.files.get(0))).withStyle(ChatFormatting.GREEN));
			}
		}
	}

	@Override
	protected Component status() {
		if (AutoImport.armed()) {
			return Component.translatable("packbrowser.link.auto", clock(AutoImport.secondsLeft())).withStyle(ChatFormatting.AQUA);
		}
		return Component.translatable("packbrowser.link.status").withStyle(ChatFormatting.GRAY);
	}

	static String clock(long seconds) {
		return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
	}

	private Component jobLine(LinkImports.Job job) {
		String name = job.fileName.isEmpty() ? job.url : job.fileName;
		if (job.browser) {
			return Component.translatable("packbrowser.link.browser", job.site).withStyle(ChatFormatting.AQUA);
		}
		return switch (job.phase) {
			case DOWNLOADING, INSTALLING -> {
				MutableComponent c = Component.empty();
				if (job.folder) {
					c.append(Component.translatable("packbrowser.link.folder", job.batchIndex, job.batchTotal)
							.withStyle(ChatFormatting.AQUA)).append(" ");
				}
				if (job.phase == LinkImports.Phase.INSTALLING) {
					c.append(Component.translatable("packbrowser.link.installing", name).withStyle(ChatFormatting.YELLOW));
				} else {
					int pct = job.percent();
					String progress = job.batchTotal == 0 && job.folder ? "..." : pct >= 0 ? pct + "%" : (job.done / 1024) + " KB";
					c.append(Component.literal("⬇ " + progress + "  ").withStyle(ChatFormatting.YELLOW))
							.append(Component.literal(name).withStyle(ChatFormatting.GRAY));
				}
				yield c;
			}
			case DONE -> {
				MutableComponent c = Component.literal("✔ ").withStyle(ChatFormatting.GREEN);
				if (job.folder) {
					c.append(Component.translatable("packbrowser.link.folderDone", job.files.size()).withStyle(ChatFormatting.GREEN));
					if (job.failed > 0 || job.skipped > 0) {
						c.append(Component.translatable("packbrowser.link.folderExtra", job.skipped, job.failed).withStyle(ChatFormatting.GOLD));
					}
					c.append(" ");
				}
				c.append(Component.literal(job.files.stream().map(PackInstaller::stripZip).collect(Collectors.joining(", ")))
						.withStyle(ChatFormatting.WHITE));
				if (job.alreadyPresent) {
					c.append(Component.translatable("packbrowser.link.present").withStyle(ChatFormatting.YELLOW));
				}
				yield c;
			}
			case FAILED -> Component.literal("✖ " + job.error).withStyle(ChatFormatting.RED);
		};
	}

	private Component eventLine(AutoImport.Event e) {
		if (!e.ok()) {
			return Component.literal("✖ " + e.fileName() + ": " + e.error()).withStyle(ChatFormatting.RED);
		}
		return Component.literal("✔ ").withStyle(ChatFormatting.GREEN)
				.append(Component.literal(e.packs().stream().map(PackInstaller::stripZip).collect(Collectors.joining(", ")))
						.withStyle(ChatFormatting.WHITE))
				.append(Component.translatable("packbrowser.link.autoTag").withStyle(ChatFormatting.DARK_AQUA));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		int left = this.controlsLeft();
		int width = this.controlsWidth();
		int bottom = this.height - LIST_BOTTOM_MARGIN;
		int y = SITES_Y + 28;

		if (AutoImport.armed()) {
			Component line = Component.translatable("packbrowser.link.autoBanner", AutoImport.site(), clock(AutoImport.secondsLeft()))
					.withStyle(ChatFormatting.AQUA);
			for (FormattedCharSequence l : this.font.split(line, width)) {
				graphics.text(this.font, l, left, y, 0xFFFFFFFF);
				y += 10;
			}
			y += 4;
		}

		for (String key : new String[]{"packbrowser.link.help1", "packbrowser.link.help2"}) {
			for (FormattedCharSequence line : this.font.split(Component.translatable(key), width)) {
				graphics.text(this.font, line, left, y, 0xFFAAAAAA);
				y += 10;
			}
			y += 4;
		}

		List<AutoImport.Event> events = AutoImport.events();
		List<LinkImports.Job> jobs = LinkImports.jobs();
		if (events.isEmpty() && jobs.isEmpty()) {
			return;
		}
		y += 4;
		graphics.text(this.font, Component.translatable("packbrowser.link.recent").withStyle(ChatFormatting.WHITE), left, y, 0xFFFFFFFF);
		y += 13;
		for (AutoImport.Event e : events) {
			y = this.drawLines(graphics, this.eventLine(e), left, y, width, bottom);
			if (y < 0) {
				return;
			}
		}
		for (LinkImports.Job job : jobs) {
			y = this.drawLines(graphics, this.jobLine(job), left, y, width, bottom);
			if (y < 0) {
				return;
			}
		}
	}

	/** Draws up to two wrapped lines; returns the next y or -1 when out of space. */
	private int drawLines(GuiGraphicsExtractor graphics, Component text, int left, int y, int width, int bottom) {
		List<FormattedCharSequence> lines = this.font.split(text, width);
		for (int i = 0; i < Math.min(2, lines.size()); i++) {
			if (y + 9 > bottom) {
				return -1;
			}
			graphics.text(this.font, lines.get(i), left, y, 0xFFFFFFFF);
			y += 10;
		}
		return y + 3;
	}
}
