package red.jackf.chesttracker.impl.gui.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import com.mojang.blaze3d.platform.InputConstants;
import red.jackf.chesttracker.impl.ChestTracker;
import red.jackf.chesttracker.api.memory.Memory;
import red.jackf.chesttracker.api.memory.counting.CountingPredicate;
import red.jackf.chesttracker.api.providers.ProviderUtils;
import red.jackf.chesttracker.impl.compat.Compatibility;
import red.jackf.chesttracker.impl.compat.mods.searchables.SearchablesUtil;
import red.jackf.chesttracker.impl.config.ChestTrackerConfig;
import red.jackf.chesttracker.impl.config.ChestTrackerConfigScreenBuilder;
import red.jackf.chesttracker.impl.gui.GuiConstants;
import red.jackf.chesttracker.impl.gui.util.TextColours;
import red.jackf.chesttracker.impl.gui.widget.*;
import red.jackf.chesttracker.impl.memory.MemoryBankAccessImpl;
import red.jackf.chesttracker.impl.memory.MemoryBankImpl;
import red.jackf.chesttracker.impl.util.GuiUtil;
import red.jackf.chesttracker.impl.util.ItemStacks;
import red.jackf.chesttracker.impl.util.Misc;
import red.jackf.chesttracker.impl.util.Strings;
import red.jackf.chesttracker.impl.waypoint.WaypointRenderer;
import red.jackf.chesttracker.impl.waypoint.Waypoints;
import red.jackf.whereisit.api.search.NestedItemsGrabber;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static net.minecraft.network.chat.Component.translatable;

/**
 * The main screen
 */
public class ChestTrackerScreen extends Screen {
    private static final Component TITLE = translatable("chesttracker.title");
    private static final int TITLE_LEFT = 8;
    private static final int TITLE_TOP = 8;
    private static final int SEARCH_LEFT = 8;
    private static final int SEARCH_TOP = 27;
    private static final int GRID_LEFT = 7;
    private static final int GRID_TOP = 41;
    private static final int BUTTON_SIZE = 14;
    private static final int MEMORY_ICON_OFFSET = 24;
    private static final int MEMORY_ICON_SPACING = 24;
    private static final int SMALL_MENU_WIDTH = 192;
    private static final int SMALL_MENU_HEIGHT = 156;
    private static final int TAG_PANEL_GAP = 4;
    private static final int TAG_PANEL_WIDTH = 108;
    private static final int TAG_PANEL_SPACE = TAG_PANEL_GAP + TAG_PANEL_WIDTH;
    private static final int TAG_LIST_MARGIN = 7;
    private static final int TAG_LIST_TOP = 20;
    private static final int CLEAR_WAYPOINTS_HEIGHT = 14;
    private static final int CLEAR_WAYPOINTS_GAP = 2;
    private static final Component TAG_PANEL_TITLE = translatable("chesttracker.gui.containerTags");
    private static final int CONTEXT_MENU_HEIGHT = 14;
    private static final int CONTEXT_MENU_PADDING = 4;
    private static final int CONTEXT_MENU_BORDER = 0xFF000000;
    private static final int CONTEXT_MENU_BACKGROUND = 0xFF303030;
    private static final int CONTEXT_MENU_HOVER = 0xFF1E88E5;
    private boolean initializedOnce = false;

    private static ContainerFilter containerFilter = ContainerFilter.ALL;
    private static ItemSort itemSort = ItemSort.COUNT_DESCENDING;
    /** Normalized name of the selected container group, or null to show every container. */
    @Nullable
    private static String selectedTag = null;
    private final Screen parent;
    private int left = 0;
    private int top = 0;
    private int menuWidth;
    private int menuHeight;
    // borrowed from creative screen, pressing `t` to focus search also triggers an input on charTyped
    private boolean ignoreTextInput = false;
    private EditBox search;
    private ItemListWidget itemList;
    private ContainerTagListWidget tagList;
    private Button clearWaypoints;
    @Nullable
    private ResizeWidget resize = null;
    private VerticalScrollWidget scroll;
    private Identifier currentMemoryKey;
    private List<ItemStack> items = Collections.emptyList();
    @Nullable
    private ContextMenu contextMenu = null;
    /** The last item looked up, as hovering asks again every frame. */
    @Nullable
    private ItemStack lookedUpStack = null;
    private List<Map.Entry<BlockPos, Memory>> lookedUpContainers = List.of();

    public ChestTrackerScreen(@Nullable Screen parent) {
        super(TITLE);
        ChestTracker.LOGGER.debug("Open Screen");
        this.parent = parent;
        this.currentMemoryKey = ProviderUtils.getPlayersCurrentKey()
                .orElseGet(() -> ChestTracker.id("unknown"));
    }

    @Override
    protected void init() {
        MemoryBankImpl bank = MemoryBankAccessImpl.INSTANCE.getLoadedInternal().orElse(null);
        if (bank == null) {
            if (!initializedOnce) {
                initializedOnce = true;
                Minecraft.getInstance().gui.setScreen(new MemoryBankManagerScreen(parent, () -> new ChestTrackerScreen(this)));
            } else {
                this.onClose();
            }
            return;
        }
        initializedOnce = true;
        var config = ChestTrackerConfig.INSTANCE.instance();
        var liveGridWidth = config.gui.gridWidth + 1;
        var liveGridHeight = config.gui.gridHeight + 1;

        // shrink until fits on screen
        do
            this.menuWidth = SMALL_MENU_WIDTH + (--liveGridWidth - 9) * GuiConstants.GRID_SLOT_SIZE;
        while (this.menuWidth > width - TAG_PANEL_SPACE && liveGridWidth > GuiConstants.MIN_GRID_COLUMNS);
        do
            this.menuHeight = SMALL_MENU_HEIGHT + (--liveGridHeight - 6) * GuiConstants.GRID_SLOT_SIZE;
        while (this.menuHeight > height && liveGridHeight > GuiConstants.MIN_GRID_ROWS);

        this.left = (this.width - menuWidth - TAG_PANEL_SPACE) / 2;
        this.top = (this.height - menuHeight) / 2;

        super.init();

        // container groups, by name
        this.tagList = this.addRenderableWidget(new ContainerTagListWidget(
                tagPanelLeft() + TAG_LIST_MARGIN,
                top + TAG_LIST_TOP,
                TAG_PANEL_WIDTH - 2 * TAG_LIST_MARGIN,
                menuHeight - TAG_LIST_TOP - TAG_LIST_MARGIN - CLEAR_WAYPOINTS_HEIGHT - CLEAR_WAYPOINTS_GAP,
                tag -> {
                    selectedTag = tag;
                    updateItems();
                }));

        this.clearWaypoints = this.addRenderableWidget(Button.builder(translatable("chesttracker.gui.waypoint.clearAll"), button -> Waypoints.clear())
                .bounds(tagPanelLeft() + TAG_LIST_MARGIN,
                        top + menuHeight - TAG_LIST_MARGIN - CLEAR_WAYPOINTS_HEIGHT,
                        TAG_PANEL_WIDTH - 2 * TAG_LIST_MARGIN,
                        CLEAR_WAYPOINTS_HEIGHT)
                .tooltip(Tooltip.create(translatable("chesttracker.gui.waypoint.clearAll.tooltip")))
                .build());

        // items
        this.itemList = new ItemListWidget(left + GRID_LEFT, top + GRID_TOP, liveGridWidth, liveGridHeight);

        // scroll
        this.scroll = this.addRenderableWidget(new VerticalScrollWidget(left + menuWidth - 19, top + GRID_TOP, this.itemList.getHeight(), Component.empty()));
        this.scroll.setResponder(this.itemList::onScroll);

        this.addRenderableWidget(this.itemList);
        this.itemList.setExtraTooltipLines(this::distanceTooltip);
        this.itemList.setOnItemClicked(stack -> WaypointRenderer.highlightClicked(this.currentMemoryKey, containersHolding(stack)));

        // search
        var shouldFocusSearch = this.search == null || this.search.isFocused();
        shouldFocusSearch &= config.gui.autofocusSearchBar;
        if (config.gui.showAutocomplete && Compatibility.SEARCHABLES) {
            this.search = addRenderableWidget(SearchablesUtil.getEditBox(
                    font,
                    left + SEARCH_LEFT,
                    top + SEARCH_TOP,
                    menuWidth - 16,
                    12,
                    this.search,
                    () -> items,
                    this::filter
            ));
        } else {
            this.search = addRenderableWidget(new CustomEditBox(
                    font,
                    left + SEARCH_LEFT,
                    top + SEARCH_TOP,
                    menuWidth - 16,
                    12,
                    this.search,
                    CustomEditBox.SEARCH_MESSAGE
            ));
            this.search.setHint(CustomEditBox.SEARCH_MESSAGE);
            this.search.setResponder(this::filter);
        }
        this.search.setTextColor(TextColours.getTextColour());
        this.search.setBordered(false);
        this.search.setValue(this.search.getValue());
        this.search.setTabOrderGroup(-1);
        ifSearchables(() -> this.addRenderableWidget(SearchablesUtil.getWrappedAutocomplete(this.search)));

        if (shouldFocusSearch)
            this.setInitialFocus(search);

        // close
        this.addRenderableWidget(GuiUtil.close(
                this.left + this.menuWidth - (3 + BUTTON_SIZE),
                this.top + GuiConstants.SMALL_MARGIN,
                button -> this.onClose()));

        // mod settings
        this.addRenderableWidget(new ImageButton(
                        this.left + this.menuWidth - 2 * (3 + BUTTON_SIZE),
                        this.top + GuiConstants.SMALL_MARGIN,
                        BUTTON_SIZE,
                        BUTTON_SIZE,
                        GuiUtil.twoSprite("mod_settings/button"),
                        button -> Minecraft.getInstance().gui.setScreen(ChestTrackerConfigScreenBuilder.build(this))))
                .setTooltip(Tooltip.create(translatable("chesttracker.gui.modSettings")));

        // change memory bank
        this.addRenderableWidget(new ImageButton(
                        this.left + this.menuWidth - 3 * (3 + BUTTON_SIZE),
                        this.top + GuiConstants.SMALL_MARGIN,
                        BUTTON_SIZE,
                        BUTTON_SIZE,
                        GuiUtil.twoSprite("change_memory_bank/button"),
                        this::openMemoryManager))
                .setTooltip(Tooltip.create(translatable("chesttracker.gui.openMemoryManager")));

        // memory bank settings
        this.addRenderableWidget(new ImageButton(
                        this.left + this.menuWidth - 4 * (3 + BUTTON_SIZE),
                        this.top + GuiConstants.SMALL_MARGIN,
                        BUTTON_SIZE,
                        BUTTON_SIZE,
                        GuiUtil.twoSprite("memory_bank_settings/button"),
                        this::openMemoryBankSettings))
                .setTooltip(Tooltip.create(translatable("chesttracker.gui.memoryBankSettings")));

        // filtering
        this.addRenderableWidget(new ChangeableImageButton(
                        this.left + this.menuWidth - 5 * (3 + BUTTON_SIZE),
                        this.top + GuiConstants.SMALL_MARGIN,
                        BUTTON_SIZE,
                        BUTTON_SIZE,
                        containerFilter.sprites,
                        CommonComponents.EMPTY,
                        this::cycleContainerFilter))
                .setTooltip(this.getContainerFilterTooltip());

        // item sort
        this.addRenderableWidget(new ChangeableImageButton(
                        this.left + this.menuWidth - 6 * (3 + BUTTON_SIZE),
                        this.top + GuiConstants.SMALL_MARGIN,
                        BUTTON_SIZE,
                        BUTTON_SIZE,
                        itemSort.sprites,
                        CommonComponents.EMPTY,
                        this::cycleItemSort))
                .setTooltip(this.getItemSortTooltip());

        // resize
        if (config.gui.showResizeWidget)
            this.resize = this.addRenderableWidget(new ResizeWidget(left + menuWidth - 10, top + menuHeight - 10, left, top,
                    GuiConstants.GRID_SLOT_SIZE, config.gui.gridWidth, config.gui.gridHeight,
                    GuiConstants.MIN_GRID_COLUMNS, GuiConstants.MIN_GRID_ROWS, GuiConstants.MAX_GRID_WIDTH, GuiConstants.MAX_GRID_HEIGHT, (w, h) -> {
                ChestTracker.LOGGER.debug("Resizing to {}w, {}h", w, h);
                ChestTrackerConfig.INSTANCE.instance().gui.gridWidth = w;
                ChestTrackerConfig.INSTANCE.instance().gui.gridHeight = h;
                ChestTrackerConfig.INSTANCE.save();
                rebuildWidgets();
            }));

        // key buttons
        // fix bad order on first open of screen, kind of hacky
        bank.getKeys().forEach(loc -> bank.getMetadata().getVisualSettings().getOrCreateIcon(loc));

        var todo = bank.getKeys().stream()
                .sorted(Misc.bringToFront(bank.getMetadata()
                        .getVisualSettings()
                        .getKeyOrder())).toList();
        Map<Identifier, ItemButton> buttons = new HashMap<>();

        for (int index = 0; index < todo.size(); index++) {
            var resloc = todo.get(index);

            // get the relevant icon
            var icon = bank.getMetadata().getVisualSettings().getOrCreateIcon(resloc);
            var button = this.addRenderableWidget(new ItemButton(icon,
                    this.left - MEMORY_ICON_OFFSET,
                    this.top + index * MEMORY_ICON_SPACING, b -> {
                // unhighlight old
                if (buttons.containsKey(this.currentMemoryKey))
                    buttons.get(this.currentMemoryKey).setHighlighted(false);

                // set item list
                this.currentMemoryKey = resloc;
                updateItems();

                // highlight new
                buttons.get(resloc).setHighlighted(true);
            }, ItemButton.Background.CUSTOM));

            button.setTooltip(Tooltip.create(Component.literal(resloc.toString())));

            buttons.put(resloc, button);

            // inital button highlight
            if (currentMemoryKey.equals(resloc)) button.setHighlighted(true);
        }

        updateItems();
    }

    private void cycleItemSort(ChangeableImageButton button) {
        itemSort = Misc.next(itemSort);
        button.setTooltip(getItemSortTooltip());
        button.setSprites(itemSort.sprites);
        updateItems();
    }

    private Tooltip getItemSortTooltip() {
        return Tooltip.create(translatable("chesttracker.gui.itemSort", itemSort.tooltip));
    }

    private Tooltip getContainerFilterTooltip() {
        return Tooltip.create(translatable("chesttracker.gui.containerFilter", containerFilter.tooltip));
    }

    private void cycleContainerFilter(ChangeableImageButton button) {
        containerFilter = Misc.next(containerFilter);
        button.setTooltip(getContainerFilterTooltip());
        button.setSprites(containerFilter.sprites);
        updateItems();
    }

    @Override
    protected void repositionElements() {
        super.repositionElements();
    }

    /**
     * Update the cached item list from the current Memory Bank, then runs a filter operation.
     */
    private void updateItems() {
        this.lookedUpStack = null;
        MemoryBankAccessImpl.INSTANCE.getLoadedInternal().ifPresent(bank -> {
            updateContainerTags(bank);

            int maxRange = bank.getMetadata().getSearchSettings().itemListRange;

            CountingPredicate predicate = getItemListFilter(maxRange);

            this.items = bank.getCounts(currentMemoryKey, predicate, bank.getMetadata().getSearchSettings().stackMergeMode, bank.getMetadata().getSearchSettings().unpackNested)
                    .stream()
                    .sorted(itemSort.sort)
                    .toList();

            filter(this.search.getValue());
        });
    }

    /**
     * @return the containers currently listed that hold this item, which moves with the filters in use. Entity
     * containers are left out, as their position in the memory is out of date.
     */
    private List<Map.Entry<BlockPos, Memory>> containersHolding(ItemStack stack) {
        if (stack == this.lookedUpStack) return this.lookedUpContainers;

        MemoryBankImpl bank = MemoryBankAccessImpl.INSTANCE.getLoadedInternal().orElse(null);
        List<Map.Entry<BlockPos, Memory>> found = List.of();
        if (bank != null) {
            CountingPredicate filter = getItemListFilter(bank.getMetadata().getSearchSettings().itemListRange);
            boolean unpackNested = bank.getMetadata().getSearchSettings().unpackNested;
            found = bank.getKeyInternal(currentMemoryKey).map(key -> key.getMemories().entrySet().stream()
                    .filter(entry -> entry.getValue().entityId() == null)
                    .filter(entry -> filter.test(entry.getKey(), entry.getValue()))
                    .filter(entry -> holds(entry.getValue(), stack, unpackNested))
                    .toList()).orElse(List.of());
        }

        this.lookedUpStack = stack;
        this.lookedUpContainers = found;
        return found;
    }

    private static boolean holds(Memory memory, ItemStack wanted, boolean unpackNested) {
        return memory.items().stream()
                .flatMap(stack -> unpackNested ? Stream.concat(Stream.of(stack), NestedItemsGrabber.get(stack)) : Stream.of(stack))
                .anyMatch(stack -> ItemStack.isSameItemSameComponents(stack, wanted));
    }

    private static double distanceSquaredToPlayer(BlockPos pos) {
        var player = Minecraft.getInstance().player;
        return player == null ? 0 : pos.distToCenterSqr(player.getEyePosition());
    }

    private Optional<Map.Entry<BlockPos, Memory>> nearest(List<Map.Entry<BlockPos, Memory>> containers) {
        return containers.stream().min(Comparator.comparingDouble(entry -> distanceSquaredToPlayer(entry.getKey())));
    }

    private List<Component> distanceTooltip(ItemStack stack) {
        var containers = containersHolding(stack);
        var closest = nearest(containers).orElse(null);
        if (closest == null) return List.of();

        if (!Objects.equals(ProviderUtils.getPlayersCurrentKey().orElse(null), currentMemoryKey)) {
            return List.of(translatable("chesttracker.gui.distance.otherDimension").withStyle(ChatFormatting.AQUA));
        }

        String blocks = Strings.commaSeparated((int) Math.round(Math.sqrt(distanceSquaredToPlayer(closest.getKey()))));
        return List.of(translatable(containers.size() > 1 ? "chesttracker.gui.distance.nearest" : "chesttracker.gui.distance", blocks)
                .withStyle(ChatFormatting.AQUA));
    }

    /** Offers to mark the nearest container with this item, or to clear a waypoint already on one of them. */
    private void openContextMenu(ItemStack stack, int mouseX, int mouseY) {
        var containers = containersHolding(stack);
        var closest = nearest(containers).orElse(null);
        if (closest == null) return;

        Identifier dimension = this.currentMemoryKey;
        var marked = containers.stream().filter(entry -> Waypoints.has(dimension, entry.getKey())).findFirst();
        Component label = translatable(marked.isPresent() ? "chesttracker.gui.waypoint.remove" : "chesttracker.gui.waypoint.add");
        Runnable action = marked.isPresent()
                ? () -> Waypoints.removeAt(dimension, marked.get().getKey())
                : () -> Waypoints.add(dimension, closest.getKey(), closest.getValue().otherPositions());

        int menuWidth = this.font.width(label) + 2 * CONTEXT_MENU_PADDING;
        this.contextMenu = new ContextMenu(
                Math.min(mouseX, this.width - menuWidth),
                Math.min(mouseY, this.height - CONTEXT_MENU_HEIGHT),
                menuWidth, label, action);
    }

    private int tagPanelLeft() {
        return this.left + this.menuWidth + TAG_PANEL_GAP;
    }

    /**
     * Containers sharing a name form one group; capitalization and surrounding spaces don't matter.
     *
     * @return The group this memory belongs to, or null if it has no name.
     */
    @Nullable
    private static String containerTag(Memory memory) {
        Component name = memory.renderName();
        if (name == null) return null;
        String stripped = name.getString().strip();
        return stripped.isEmpty() ? null : stripped.toLowerCase(Locale.ROOT);
    }

    /**
     * Rebuilds the group list from the current memory key, dropping the selection if that group no longer exists there.
     */
    private void updateContainerTags(MemoryBankImpl bank) {
        Map<String, String> labels = new TreeMap<>();
        Map<String, Integer> counts = new HashMap<>();
        bank.getKeyInternal(currentMemoryKey).ifPresent(key -> key.getMemories().values().forEach(memory -> {
            String tag = containerTag(memory);
            if (tag == null) return;
            labels.putIfAbsent(tag, Objects.requireNonNull(memory.renderName()).getString().strip());
            counts.merge(tag, 1, Integer::sum);
        }));

        if (selectedTag != null && !labels.containsKey(selectedTag)) selectedTag = null;

        List<ContainerTagListWidget.Row> rows = new ArrayList<>();
        rows.add(new ContainerTagListWidget.Row(null, translatable("chesttracker.gui.containerTags.all").getString(), 0));
        labels.forEach((tag, label) -> rows.add(new ContainerTagListWidget.Row(tag, label, counts.get(tag))));
        this.tagList.setRows(rows, selectedTag);
    }

    private CountingPredicate getItemListFilter(int maxRange) {
        CountingPredicate predicate = containerFilter.filter;

        // a group means every container with that name, however far away it is
        if (selectedTag != null) {
            String tag = selectedTag;
            return predicate.and((pos, memory) -> tag.equals(containerTag(memory)));
        }

        // apply max range if necessary
        if (Minecraft.getInstance().player != null && Objects.equals(ProviderUtils.getPlayersCurrentKey().orElse(null), currentMemoryKey)) {
            Vec3 origin = Minecraft.getInstance().player.getEyePosition();
            predicate = predicate.and(CountingPredicate.within(origin, maxRange));
        }
        return predicate;
    }

    /**
     * Update the items list with the currently cached items, filtered by the search bar.
     */
    private void filter(String filter) {
        List<ItemStack> filtered;
        if (Compatibility.SEARCHABLES) {
            filtered = SearchablesUtil.ITEM_STACK.filterEntries(this.items, filter.toLowerCase());
        } else {
            filtered = this.items.stream().filter(stack -> ItemStacks.defaultPredicate(stack, filter.toLowerCase()))
                    .toList();
        }
        this.itemList.setItems(filtered);
        var guiConfig = ChestTrackerConfig.INSTANCE.instance().gui;
        this.scroll.setDisabled(filtered.size() <= (guiConfig.gridWidth * guiConfig.gridHeight));
    }

    /**
     * Test for autocomplete-specific operations. Used to check mouse operations over the autocomplete bar.
     */
    private boolean ifSearchables(Predicate<AbstractWidget> predicate) {
        return Compatibility.SEARCHABLES && SearchablesUtil.ifSearchables(this.search, predicate);
    }

    private void ifSearchables(Runnable ifSearchables) {
        ifSearchables(ignored -> {
            ifSearchables.run();
            return false;
        });
    }

    @Override
    public void extractRenderState(@NotNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float tickDelta) {
        this.itemList.setHideTooltip(this.contextMenu != null || this.search.isFocused() && ifSearchables(a -> a.isMouseOver(mouseX, mouseY)));
        this.clearWaypoints.active = !Waypoints.active().isEmpty();
        super.extractRenderState(graphics, mouseX, mouseY, tickDelta); // widgets
        graphics.text(this.font, this.title, left + TITLE_LEFT, top + TITLE_TOP, TextColours.getLabelColour(), false); // title
        graphics.text(this.font, TAG_PANEL_TITLE, tagPanelLeft() + TITLE_LEFT, top + TITLE_TOP, TextColours.getLabelColour(), false);
        this.itemList.renderTooltip(graphics);

        if (this.contextMenu != null) {
            graphics.nextStratum();
            this.contextMenu.draw(graphics, this.font, mouseX, mouseY);
        }
    }

    @Override
    public void extractBackground(@NotNull GuiGraphicsExtractor graphics, int i, int j, float f) {
        super.extractBackground(graphics, i, j, f);
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, GuiUtil.BACKGROUND_SPRITE, left, top, menuWidth, menuHeight);
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, GuiUtil.BACKGROUND_SPRITE, tagPanelLeft(), top, TAG_PANEL_WIDTH, menuHeight);
        ifSearchables(() -> graphics.blitSprite(RenderPipelines.GUI_TEXTURED, GuiUtil.SEARCH_BAR_SPRITE, search.getX() - 2, search.getY() - 2, search.getWidth() + 4, search.getHeight()));
    }

    @Override
    public boolean charTyped(@NotNull CharacterEvent event) {
        if (ignoreTextInput) {
            return false;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(@NotNull KeyEvent event) {
        this.ignoreTextInput = false;
        boolean searchFocused = this.search != null && this.search.isFocused();
        if (!searchFocused && red.jackf.chesttracker.impl.ChestTracker.OPEN_GUI.matches(event)) {
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(@NotNull MouseButtonEvent event, boolean isDoubleClick) {
        if (this.contextMenu != null) {
            ContextMenu menu = this.contextMenu;
            this.contextMenu = null;
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
                if (menu.contains(event.x(), event.y())) menu.action.run();
                return true;
            }
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            var stack = this.itemList.stackAt(event.x(), event.y());
            if (stack.isPresent()) {
                openContextMenu(stack.get(), (int) event.x(), (int) event.y());
                return true;
            }
        }

        if (resize != null && resize.isMouseOver(event.x(), event.y())) {
            boolean handled = resize.mouseClicked(event, isDoubleClick);
            if (handled) return true;
        }
        if (this.search.isFocused() && ifSearchables(a -> a.mouseClicked(event, isDoubleClick)))
            return true;

        return super.mouseClicked(event, isDoubleClick);
    }

    @Override
    public boolean mouseReleased(@NotNull MouseButtonEvent event) {
        if (scroll != null && (scroll.isScrolling() || scroll.isMouseOver(event.x(), event.y()))) {
            if (scroll.mouseReleased(event)) return true;
        }
        if (resize != null) {
            boolean handled = resize.mouseReleased(event);
            if (handled) return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(@NotNull MouseButtonEvent event, double mouseX, double mouseY) {
        if (scroll != null && (scroll.isScrolling() || scroll.isMouseOver(mouseX, mouseY))) {
            if (scroll.mouseDragged(event, mouseX, mouseY)) {
                return true;
            }
        }
        if (resize != null && resize.mouseDragged(event, mouseX, mouseY)) {
            return true;
        }
        return super.mouseDragged(event, mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        // Searchables Edit Box Support
        double finalDelta = deltaY;
        if (search.isFocused() && ifSearchables(a -> a.mouseScrolled(mouseX, mouseY, deltaX, finalDelta))) {
            return true;
        } else if (itemList.isMouseOver(mouseX, mouseY) || scroll.isMouseOver(mouseX, mouseY)) {
            deltaY /= Math.max(1, itemList.getRows() - ChestTrackerConfig.INSTANCE.instance().gui.gridHeight);
            return scroll.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }


    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void openMemoryManager(Button ignored) {
        Minecraft.getInstance().gui.setScreen(new MemoryBankManagerScreen(
                () -> MemoryBankAccessImpl.INSTANCE.getLoadedInternal().isEmpty() ? parent : this,
                // return to this screen unless the memories have been unloaded, in which case go to the parent
                () -> Minecraft.getInstance().gui.setScreen(this)
        ));
    }

    private void openMemoryBankSettings(Button button) {
        MemoryBankAccessImpl.INSTANCE.getLoadedInternal().ifPresent(bank -> {
            Minecraft.getInstance().gui.setScreen(new EditMemoryBankScreen(
                    this,
                    this::updateItems,
                    bank.getId()
            ));
        });
    }

    private record ContextMenu(int x, int y, int width, Component label, Runnable action) {
        private boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + CONTEXT_MENU_HEIGHT;
        }

        private void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
            graphics.fill(x, y, x + width, y + CONTEXT_MENU_HEIGHT, CONTEXT_MENU_BORDER);
            graphics.fill(x + 1, y + 1, x + width - 1, y + CONTEXT_MENU_HEIGHT - 1,
                    contains(mouseX, mouseY) ? CONTEXT_MENU_HOVER : CONTEXT_MENU_BACKGROUND);
            graphics.text(font, label, x + CONTEXT_MENU_PADDING, y + 3, 0xFFFFFFFF);
        }
    }

    public enum ContainerFilter {
        ALL(GuiUtil.twoSprite("container_filter/all"),
                translatable("chesttracker.gui.containerFilter.all"),
                (pos, memory) -> true),
        CHESTS(GuiUtil.twoSprite("container_filter/chests"),
                translatable("chesttracker.gui.containerFilter.chests"),
                (pos, memory) -> memory.container().map(b -> b instanceof AbstractChestBlock<?>).orElse(false)),
        BARRELS(GuiUtil.twoSprite("container_filter/barrels"),
                translatable("chesttracker.gui.containerFilter.barrels"),
                (pos, memory) -> memory.container().map(b -> b instanceof BarrelBlock).orElse(false)),
        SHULKER_BOXES(GuiUtil.twoSprite("container_filter/shulker_boxes"),
                translatable("chesttracker.gui.containerFilter.shulkerBoxes"),
                (pos, memory) -> memory.container().map(b -> b instanceof ShulkerBoxBlock).orElse(false)),
        HOPPERS(GuiUtil.twoSprite("container_filter/hoppers"),
                translatable("chesttracker.gui.containerFilter.hoppers"),
                (pos, memory) -> memory.container().map(b -> b instanceof HopperBlock).orElse(false)),
        FURNACES(GuiUtil.twoSprite("container_filter/furnaces"),
                translatable("chesttracker.gui.containerFilter.furnaces"),
                (pos, memory) -> memory.container().map(b -> b instanceof AbstractFurnaceBlock).orElse(false));

        private final WidgetSprites sprites;
        private final Component tooltip;
        private final CountingPredicate filter;

        ContainerFilter(WidgetSprites sprites, Component tooltip, CountingPredicate filter) {
            this.sprites = sprites;
            this.tooltip = tooltip;
            this.filter = filter;
        }
    }

    public enum ItemSort {
        COUNT_DESCENDING(GuiUtil.twoSprite("item_sort/count_descending"),
                translatable("chesttracker.gui.itemSort.countDescending"),
                Comparator.comparingInt(ItemStack::getCount).reversed()),
        COUNT_ASCENDING(GuiUtil.twoSprite("item_sort/count_ascending"),
                translatable("chesttracker.gui.itemSort.countAscending"),
                Comparator.comparingInt(ItemStack::getCount)),
        ALPHABETICAL_DESCENDING(GuiUtil.twoSprite("item_sort/alphabetical_descending"),
                translatable("chesttracker.gui.itemSort.alphabeticalDescending"),
                Comparator.comparing(stack -> stack.getDisplayName().getString().toLowerCase(Locale.ROOT))),
        ALPHABETICAL_ASCENDING(GuiUtil.twoSprite("item_sort/alphabetical_ascending"),
                translatable("chesttracker.gui.itemSort.alphabeticalAscending"),
                Comparator.<ItemStack, String>comparing(stack -> stack.getDisplayName().getString()
                        .toLowerCase(Locale.ROOT)).reversed());

        private final WidgetSprites sprites;
        private final Component tooltip;
        private final Comparator<ItemStack> sort;

        ItemSort(WidgetSprites sprites, Component tooltip, Comparator<ItemStack> sort) {
            this.sprites = sprites;
            this.tooltip = tooltip;
            this.sort = sort;
        }
    }
}
