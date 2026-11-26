package de.ubyz.packbrowser.client.gui;

import de.ubyz.packbrowser.client.Downloads;
import de.ubyz.packbrowser.client.InstalledPacks;
import de.ubyz.packbrowser.client.ModrinthApi;
import de.ubyz.packbrowser.client.PackBrowserClient;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PackBrowserScreen extends Screen {
	private static final String[] SORTS = {"relevance", "downloads", "follows", "newest", "updated"};
	private static final String[] CATEGORIES = {"", "combat", "simplistic", "vanilla-like", "realistic", "themed",
			"tweaks", "utility", "decoration", "cursed", "8x-", "16x", "32x", "64x", "128x", "256x", "512x+"};
	private static final int PAGE_SIZE = 30;
	private static final int LIST_TOP = 46;

	// remembered while the game is running
	private static String lastQuery = "";
	private static boolean lastOnlyCurrent = true;
	private static int lastSort = 0;
	private static int lastCategory = 0;

	private final Screen parent;
	private final String gameVersion = PackBrowserClient.gameVersion();
	private final List<ModrinthApi.Hit> results = new ArrayList<>();
	private final Map<String, Downloads.State> watching = new LinkedHashMap<>();

	private @Nullable EditBox searchBox;
	private @Nullable PackList list;
	private @Nullable Button downloadButton;
	private @Nullable Button openButton;

	private String query = lastQuery;
	private boolean onlyCurrent = lastOnlyCurrent;
	private int sort = lastSort;
	private int category = lastCategory;

	private int totalHits;
	private boolean loading;
	private boolean loadedOnce;
	private @Nullable String error;
	private long searchDueAt;
	private int requestSeq;
	private ModrinthApi.@Nullable Hit selected;
	private @Nullable Component flash;
	private long flashUntil;

	public PackBrowserScreen(Screen parent) {
		super(Component.translatable("packbrowser.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int gap = 4;
		int controlsWidth = Math.min(this.width - 12, 560);
		int left = (this.width - controlsWidth) / 2;
		int buttonWidth = Math.max(60, Math.min(112, (controlsWidth - 110 - 3 * gap) / 3));
		int searchWidth = controlsWidth - 3 * (buttonWidth + gap);
		int y = 20;

		this.searchBox = new EditBox(this.font, left, y, searchWidth, 20, this.searchBox,
				Component.translatable("packbrowser.search"));
		this.searchBox.setMaxLength(100);
		this.searchBox.setHint(Component.translatable("packbrowser.search.hint").withStyle(ChatFormatting.DARK_GRAY));
		this.searchBox.setValue(this.query);
		this.searchBox.setResponder(text -> {
			if (!text.equals(this.query)) {
				this.query = text;
				this.searchDueAt = Util.getMillis() + 350;
			}
		});
		this.addRenderableWidget(this.searchBox);

		int bx = left + searchWidth + gap;
		this.addRenderableWidget(Button.builder(this.versionText(), button -> {
					this.onlyCurrent = !this.onlyCurrent;
					button.setMessage(this.versionText());
					this.newSearch();
				})
				.pos(bx, y).size(buttonWidth, 20)
				.tooltip(Tooltip.create(Component.translatable("packbrowser.version.tooltip", this.gameVersion)))
				.build());
		bx += buttonWidth + gap;
		this.addRenderableWidget(Button.builder(this.sortText(), button -> {
					this.sort = Math.floorMod(this.sort + this.direction(), SORTS.length);
					button.setMessage(this.sortText());
					this.newSearch();
				})
				.pos(bx, y).size(buttonWidth, 20)
				.build());
		bx += buttonWidth + gap;
		this.addRenderableWidget(Button.builder(this.categoryText(), button -> {
					this.category = Math.floorMod(this.category + this.direction(), CATEGORIES.length);
					button.setMessage(this.categoryText());
					this.newSearch();
				})
				.pos(bx, y).size(buttonWidth, 20)
				.tooltip(Tooltip.create(Component.translatable("packbrowser.category.tooltip")))
				.build());

		this.list = new PackList(this.minecraft, this.width, this.height - LIST_TOP - 52, LIST_TOP, 36, this);
		this.list.setHits(this.results, this.selected == null ? null : this.selected.id());
		this.addRenderableWidget(this.list);

		int footerGap = 6;
		int footerWidth = Math.min(130, (this.width - 16 - 2 * footerGap) / 3);
		int fx = this.width / 2 - (3 * footerWidth + 2 * footerGap) / 2;
		int fy = this.height - 26;
		this.downloadButton = this.addRenderableWidget(Button.builder(Component.translatable("packbrowser.download"),
						button -> {
							if (this.selected != null) {
								this.startDownload(this.selected);
							}
						})
				.pos(fx, fy).size(footerWidth, 20)
				.build());
		this.openButton = this.addRenderableWidget(Button.builder(Component.translatable("packbrowser.open"),
						button -> {
							if (this.selected != null) {
								ConfirmLinkScreen.confirmLinkNow(this,
										URI.create("https://modrinth.com/resourcepack/" + this.selected.slug()), true);
							}
						})
				.pos(fx + footerWidth + footerGap, fy).size(footerWidth, 20)
				.build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
				.pos(fx + 2 * (footerWidth + footerGap), fy).size(footerWidth, 20)
				.build());

		this.setInitialFocus(this.searchBox);
		this.updateButtons();

		if (!this.loadedOnce && !this.loading) {
			this.newSearch();
		}
	}

	private int direction() {
		return this.minecraft != null && this.minecraft.hasShiftDown() ? -1 : 1;
	}

	private Component versionText() {
		return this.onlyCurrent
				? Component.translatable("packbrowser.version.only", this.gameVersion)
				: Component.translatable("packbrowser.version.all");
	}

	private Component sortText() {
		return Component.translatable("packbrowser.sort." + SORTS[this.sort]);
	}

	private Component categoryText() {
		String cat = CATEGORIES[this.category];
		return Component.translatable("packbrowser.category",
				Component.translatable("packbrowser.category." + (cat.isEmpty() ? "all" : cat)));
	}

	// ------------------------------------------------------------------ search

	private void newSearch() {
		this.requestSeq++;
		this.results.clear();
		this.totalHits = 0;
		this.error = null;
		this.loadedOnce = false;
		this.selected = null;
		if (this.list != null) {
			this.list.setHits(this.results, null);
			this.list.setScrollAmount(0);
		}
		this.fetch();
	}

	private void fetch() {
		int seq = this.requestSeq;
		this.loading = true;
		ModrinthApi.search(this.query, this.onlyCurrent ? this.gameVersion : null, CATEGORIES[this.category],
						SORTS[this.sort], this.results.size(), PAGE_SIZE)
				.whenComplete((page, err) -> Minecraft.getInstance().execute(() -> {
					if (seq != this.requestSeq) {
						return;
					}
					this.loading = false;
					this.loadedOnce = true;
					if (err != null) {
						this.error = Downloads.message(err);
						PackBrowserClient.LOGGER.warn("Modrinth search failed", err);
						return;
					}
					this.totalHits = page.totalHits();
					this.results.addAll(page.hits());
					if (this.list != null) {
						this.list.appendHits(page.hits());
					}
				}));
	}

	// ---------------------------------------------------------------- download

	void onSelected(ModrinthApi.@Nullable Hit hit) {
		this.selected = hit;
		this.updateButtons();
	}

	void startDownload(ModrinthApi.Hit hit) {
		Downloads.State current = Downloads.get(hit.id());
		if (current != null && current.running()) {
			return;
		}
		this.watching.put(hit.id(), Downloads.start(hit));
		this.updateButtons();
	}

	private void updateButtons() {
		if (this.downloadButton == null || this.openButton == null) {
			return;
		}
		ModrinthApi.Hit hit = this.selected;
		this.openButton.active = hit != null;
		if (hit == null) {
			this.downloadButton.active = false;
			this.downloadButton.setMessage(Component.translatable("packbrowser.download"));
			return;
		}
		Downloads.State st = Downloads.get(hit.id());
		if (st != null && st.running()) {
			this.downloadButton.active = false;
			this.downloadButton.setMessage(Component.translatable("packbrowser.download.running", st.percent()));
		} else if ((st != null && st.phase == Downloads.Phase.DONE) || InstalledPacks.isInstalled(hit.id())) {
			this.downloadButton.active = false;
			this.downloadButton.setMessage(Component.translatable("packbrowser.entry.installed"));
		} else {
			this.downloadButton.active = true;
			this.downloadButton.setMessage(Component.translatable(
					st != null && st.phase == Downloads.Phase.FAILED ? "packbrowser.download.retry" : "packbrowser.download"));
		}
	}

	private void showFlash(Component message) {
		this.flash = message;
		this.flashUntil = Util.getMillis() + 7000;
	}

	@Override
	public void tick() {
		super.tick();

		if (this.searchDueAt != 0 && Util.getMillis() >= this.searchDueAt) {
			this.searchDueAt = 0;
			this.newSearch();
		}

		if (!this.loading && this.loadedOnce && this.error == null && this.results.size() < this.totalHits
				&& this.list != null && this.list.nearBottom()) {
			this.fetch();
		}

		Iterator<Map.Entry<String, Downloads.State>> it = this.watching.entrySet().iterator();
		while (it.hasNext()) {
			Downloads.State st = it.next().getValue();
			if (st.phase == Downloads.Phase.DONE) {
				if (st.alreadyExisted) {
					this.showFlash(Component.translatable("packbrowser.flash.exists", st.fileName).withStyle(ChatFormatting.YELLOW));
				} else if (st.versionMismatch) {
					this.showFlash(Component.translatable("packbrowser.flash.mismatch", st.title, this.gameVersion).withStyle(ChatFormatting.YELLOW));
				} else {
					this.showFlash(Component.translatable("packbrowser.flash.done", st.title).withStyle(ChatFormatting.GREEN));
				}
				it.remove();
			} else if (st.phase == Downloads.Phase.FAILED) {
				this.showFlash(Component.translatable("packbrowser.flash.failed", st.title, st.error).withStyle(ChatFormatting.RED));
				it.remove();
			}
		}

		this.updateButtons();
	}

	// ------------------------------------------------------------------ render

	private Component statusLine() {
		if (this.flash != null && Util.getMillis() < this.flashUntil) {
			return this.flash;
		}
		if (this.loading) {
			return Component.translatable("packbrowser.status.loading").withStyle(ChatFormatting.GRAY);
		}
		if (this.error != null) {
			return Component.translatable("packbrowser.status.error", this.error).withStyle(ChatFormatting.RED);
		}
		return Component.translatable("packbrowser.status.count", this.totalHits).withStyle(ChatFormatting.GRAY);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		graphics.centeredText(this.font, this.title, this.width / 2, 7, 0xFFFFFFFF);
		graphics.centeredText(this.font, this.statusLine(), this.width / 2, this.height - 40, 0xFFFFFFFF);

		if (this.results.isEmpty() && this.loadedOnce && !this.loading) {
			int cy = LIST_TOP + (this.height - LIST_TOP - 52) / 2 - 10;
			if (this.error != null) {
				graphics.centeredText(this.font, Component.translatable("packbrowser.empty.error"), this.width / 2, cy, 0xFFFF5555);
			} else {
				graphics.centeredText(this.font, Component.translatable("packbrowser.empty"), this.width / 2, cy, 0xFFFFFFFF);
				if (this.onlyCurrent) {
					graphics.centeredText(this.font, Component.translatable("packbrowser.empty.hint"), this.width / 2, cy + 12, 0xFFAAAAAA);
				}
			}
		}
	}

	@Override
	public void onClose() {
		lastQuery = this.query;
		lastOnlyCurrent = this.onlyCurrent;
		lastSort = this.sort;
		lastCategory = this.category;
		this.minecraft.gui.setScreen(this.parent);
	}
}
