package de.ubyz.packbrowser.client.gui;

import de.ubyz.packbrowser.client.Downloads;
import de.ubyz.packbrowser.client.LocalPacks;
import de.ubyz.packbrowser.client.PackBrowserClient;
import de.ubyz.packbrowser.client.PackInstaller;
import de.ubyz.packbrowser.client.Settings;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Tab 2: import packs you downloaded in the browser (PvPRP, Planet Minecraft, CurseForge, ...). */
public class DownloadsScreen extends BaseBrowserScreen {
	private Path folder = Settings.importFolder();
	private List<LocalPacks.Item> items = new ArrayList<>();
	private @Nullable EditBox folderBox;
	private @Nullable LocalPackList list;
	private @Nullable Button importButton;
	private LocalPacks.@Nullable Item selected;

	private boolean scanning;
	private boolean scannedOnce;
	private @Nullable String error;
	private int scanSeq;
	private long lastFolderStamp = -1;
	private long folderDueAt;
	private int ticks;

	public DownloadsScreen(Screen parent) {
		super(parent, TAB_DOWNLOADS);
	}

	@Override
	protected void init() {
		this.addTabs();

		int gap = 4;
		int controlsWidth = this.controlsWidth();
		int left = this.controlsLeft();
		int buttonWidth = Math.max(60, Math.min(100, (controlsWidth - 140) / 2));
		int boxWidth = controlsWidth - 2 * (buttonWidth + gap);

		this.folderBox = new EditBox(this.font, left, CONTROLS_Y, boxWidth, 20, this.folderBox,
				Component.translatable("packbrowser.local.folder"));
		this.folderBox.setMaxLength(1024);
		this.folderBox.setValue(this.folder.toString());
		this.folderBox.setTooltip(Tooltip.create(Component.translatable("packbrowser.local.folder.tooltip")));
		this.folderBox.setResponder(text -> this.folderDueAt = Util.getMillis() + 700);
		this.addRenderableWidget(this.folderBox);

		int bx = left + boxWidth + gap;
		this.addRenderableWidget(button("packbrowser.local.refresh", b -> this.rescan())
				.pos(bx, CONTROLS_Y).size(buttonWidth, 20).build());
		bx += buttonWidth + gap;
		this.addRenderableWidget(button("packbrowser.local.openFolder", b -> openFolder(this.folder))
				.pos(bx, CONTROLS_Y).size(buttonWidth, 20).build());

		this.list = new LocalPackList(this.minecraft, this.width, this.listHeight(), LIST_TOP, 36, this);
		this.list.setItems(this.items, this.selected == null ? null : this.selected.path());
		this.addRenderableWidget(this.list);

		Button[] footer = this.addFooter(
				button("packbrowser.local.import", b -> {
					if (this.selected != null) {
						this.importItem(this.selected);
					}
				}),
				button("packbrowser.openPackFolder", b -> openFolder(PackBrowserClient.resourcePackDir())),
				Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()));
		this.importButton = footer[0];
		this.updateButtons();

		if (!this.scannedOnce && !this.scanning) {
			this.rescan();
		}
	}

	void onSelected(LocalPacks.@Nullable Item item) {
		this.selected = item;
		this.updateButtons();
	}

	private void updateButtons() {
		if (this.importButton == null) {
			return;
		}
		LocalPacks.Item item = this.selected;
		if (item == null) {
			this.importButton.active = false;
			this.importButton.setMessage(Component.translatable("packbrowser.local.import"));
		} else if (LocalPacks.importing(item)) {
			this.importButton.active = false;
			this.importButton.setMessage(Component.translatable("packbrowser.local.importing"));
		} else if (LocalPacks.imported(item)) {
			this.importButton.active = false;
			this.importButton.setMessage(Component.translatable("packbrowser.local.imported"));
		} else {
			this.importButton.active = true;
			this.importButton.setMessage(Component.translatable("packbrowser.local.import"));
		}
	}

	private void rescan() {
		int seq = ++this.scanSeq;
		this.scanning = true;
		this.error = null;
		this.lastFolderStamp = LocalPacks.modified(this.folder);
		LocalPacks.scan(this.folder).whenComplete((found, err) -> Minecraft.getInstance().execute(() -> {
			if (seq != this.scanSeq) {
				return;
			}
			this.scanning = false;
			this.scannedOnce = true;
			if (err != null) {
				this.error = Downloads.message(err);
				this.items = new ArrayList<>();
			} else {
				this.items = new ArrayList<>(found);
			}
			if (this.selected != null && this.items.stream().noneMatch(i -> i.path().equals(this.selected.path()))) {
				this.selected = null;
			}
			if (this.list != null) {
				this.list.setItems(this.items, this.selected == null ? null : this.selected.path());
			}
			this.updateButtons();
		}));
	}

	void importItem(LocalPacks.Item item) {
		if (LocalPacks.importing(item)) {
			return;
		}
		LocalPacks.importPack(item).whenComplete((result, err) -> Minecraft.getInstance().execute(() -> {
			if (err != null) {
				this.showFlash(Component.translatable("packbrowser.flash.failed", item.info().name(), Downloads.message(err))
						.withStyle(ChatFormatting.RED));
			} else if (result.alreadyPresent()) {
				this.showFlash(Component.translatable("packbrowser.flash.exists", String.join(", ", result.files()))
						.withStyle(ChatFormatting.YELLOW));
			} else if (result.files().size() > 1) {
				this.showFlash(Component.translatable("packbrowser.flash.multi", result.files().size(), String.join(", ", result.files()))
						.withStyle(ChatFormatting.GREEN));
			} else {
				this.showFlash(Component.translatable("packbrowser.flash.done", PackInstaller.stripZip(result.files().get(0)))
						.withStyle(ChatFormatting.GREEN));
			}
			this.updateButtons();
		}));
		this.updateButtons();
	}

	@Override
	public void tick() {
		super.tick();
		this.ticks++;

		if (this.folderDueAt != 0 && Util.getMillis() >= this.folderDueAt && this.folderBox != null) {
			this.folderDueAt = 0;
			try {
				Path p = Path.of(this.folderBox.getValue().trim());
				if (!p.equals(this.folder)) {
					this.folder = p;
					Settings.setImportFolder(p);
					this.rescan();
				}
			} catch (Exception e) {
				this.error = Component.translatable("packbrowser.local.badFolder").getString();
			}
		}

		// new file downloaded in the browser? -> refresh automatically
		if (this.ticks % 40 == 0 && !this.scanning) {
			long stamp = LocalPacks.modified(this.folder);
			if (stamp != this.lastFolderStamp) {
				this.rescan();
			}
		}

		this.updateButtons();
	}

	@Override
	protected Component status() {
		if (this.scanning && !this.scannedOnce) {
			return Component.translatable("packbrowser.local.scanning").withStyle(ChatFormatting.GRAY);
		}
		if (this.error != null) {
			return Component.translatable("packbrowser.status.error", this.error).withStyle(ChatFormatting.RED);
		}
		return Component.translatable("packbrowser.local.count", this.items.size()).withStyle(ChatFormatting.GRAY);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		if (this.items.isEmpty() && this.scannedOnce && !this.scanning) {
			int cy = LIST_TOP + this.listHeight() / 2 - 16;
			graphics.centeredText(this.font, Component.translatable("packbrowser.local.empty"), this.width / 2, cy, 0xFFFFFFFF);
			graphics.centeredText(this.font, Component.translatable("packbrowser.local.empty.hint1"), this.width / 2, cy + 14, 0xFFAAAAAA);
			graphics.centeredText(this.font, Component.translatable("packbrowser.local.empty.hint2"), this.width / 2, cy + 26, 0xFFAAAAAA);
		}
	}
}
