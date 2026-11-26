package de.ubyz.packbrowser.client.gui;

import de.ubyz.packbrowser.client.LinkImports;
import de.ubyz.packbrowser.client.PackBrowserClient;
import de.ubyz.packbrowser.client.PackInstaller;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.stream.Collectors;

/** Tab 3: paste a direct download link (GitHub, Dropbox, Google Drive, Discord, ...). */
public class LinkScreen extends BaseBrowserScreen {
	private static String lastUrl = "";

	private @Nullable EditBox urlBox;

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
		this.urlBox.setMaxLength(2048);
		this.urlBox.setHint(Component.translatable("packbrowser.link.hint").withStyle(ChatFormatting.DARK_GRAY));
		this.urlBox.setValue(lastUrl);
		this.urlBox.setResponder(text -> lastUrl = text);
		this.addRenderableWidget(this.urlBox);

		this.addRenderableWidget(button("packbrowser.link.load", b -> this.load())
				.pos(left + boxWidth + gap, CONTROLS_Y).size(buttonWidth, 20).build());

		this.addFooter(
				button("packbrowser.openPackFolder", b -> openFolder(PackBrowserClient.resourcePackDir())),
				Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()));

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
		for (LinkImports.Job job : LinkImports.jobs()) {
			if (job.reported || job.running()) {
				continue;
			}
			job.reported = true;
			if (job.phase == LinkImports.Phase.FAILED) {
				this.showFlash(Component.translatable("packbrowser.link.failed").withStyle(ChatFormatting.RED));
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
		return Component.translatable("packbrowser.link.status").withStyle(ChatFormatting.GRAY);
	}

	private Component jobLine(LinkImports.Job job) {
		String name = job.fileName.isEmpty() ? job.url : job.fileName;
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
					c.append(Component.literal("\u2B07 " + progress + "  ").withStyle(ChatFormatting.YELLOW))
							.append(Component.literal(name).withStyle(ChatFormatting.GRAY));
				}
				yield c;
			}
			case DONE -> {
				MutableComponent c = Component.literal("\u2714 ").withStyle(ChatFormatting.GREEN);
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
			case FAILED -> Component.literal("\u2716 " + job.error).withStyle(ChatFormatting.RED);
		};
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		int left = this.controlsLeft();
		int width = this.controlsWidth();
		int y = LIST_TOP + 4;

		for (String key : new String[]{"packbrowser.link.help1", "packbrowser.link.help2", "packbrowser.link.help3"}) {
			for (FormattedCharSequence line : this.font.split(Component.translatable(key), width)) {
				graphics.text(this.font, line, left, y, 0xFFAAAAAA);
				y += 10;
			}
			y += 4;
		}

		List<LinkImports.Job> jobs = LinkImports.jobs();
		if (jobs.isEmpty()) {
			return;
		}
		y += 6;
		graphics.text(this.font, Component.translatable("packbrowser.link.recent").withStyle(ChatFormatting.WHITE), left, y, 0xFFFFFFFF);
		y += 13;
		int bottom = this.height - LIST_BOTTOM_MARGIN;
		for (LinkImports.Job job : jobs) {
			List<FormattedCharSequence> lines = this.font.split(this.jobLine(job), width);
			for (int i = 0; i < Math.min(2, lines.size()); i++) {
				if (y + 9 > bottom) {
					return;
				}
				graphics.text(this.font, lines.get(i), left, y, 0xFFFFFFFF);
				y += 10;
			}
			y += 3;
		}
	}
}
