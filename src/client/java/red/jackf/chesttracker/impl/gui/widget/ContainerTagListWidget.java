package red.jackf.chesttracker.impl.gui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import red.jackf.chesttracker.impl.gui.util.TextColours;
import red.jackf.chesttracker.impl.util.GuiUtil;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Scrollable list of the container names in a memory key. Selecting one narrows the item grid to the containers
 * with that name.
 */
public class ContainerTagListWidget extends AbstractWidget {
    private static final int ROW_HEIGHT = 12;
    private static final int PADDING = 3;
    private static final int SELECTED_BACKGROUND = 0xFF1E88E5;
    private static final int SELECTED_TEXT = 0xFFFFFFFF;

    /**
     * @param tag   normalized name, or null for the row showing everything
     * @param label text shown to the player
     * @param count how many containers have this name; ignored for the row showing everything
     */
    public record Row(@Nullable String tag, String label, int count) {}

    private final Consumer<@Nullable String> onSelect;
    private List<Row> rows = List.of();
    private @Nullable String selected = null;
    private int firstVisibleRow = 0;

    public ContainerTagListWidget(int x, int y, int width, int height, Consumer<@Nullable String> onSelect) {
        super(x, y, width, height, Component.empty());
        this.onSelect = onSelect;
    }

    public void setRows(List<Row> rows, @Nullable String selected) {
        this.rows = rows;
        this.selected = selected;
        scrollTo(firstVisibleRow);
    }

    public List<Row> getRows() {
        return rows;
    }

    public void select(@Nullable String tag) {
        this.selected = tag;
        this.onSelect.accept(tag);
    }

    private int visibleRows() {
        return (getHeight() - 2) / ROW_HEIGHT;
    }

    private void scrollTo(int row) {
        this.firstVisibleRow = Mth.clamp(row, 0, Math.max(0, rows.size() - visibleRows()));
    }

    private int rowAt(double mouseY) {
        return firstVisibleRow + Mth.floor((mouseY - getY() - 1) / ROW_HEIGHT);
    }

    @Override
    protected void extractWidgetRenderState(@NotNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        var font = Minecraft.getInstance().font;
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, GuiUtil.SEARCH_BAR_SPRITE, getX(), getY(), getWidth(), getHeight());

        int hovered = isMouseOver(mouseX, mouseY) ? rowAt(mouseY) : -1;
        int lastRow = Math.min(rows.size(), firstVisibleRow + visibleRows());
        for (int i = firstVisibleRow; i < lastRow; i++) {
            Row row = rows.get(i);
            int textY = getY() + 2 + ROW_HEIGHT * (i - firstVisibleRow);

            String count = row.tag() == null ? "" : String.valueOf(row.count());
            int countWidth = font.width(count);
            String label = font.plainSubstrByWidth(row.label(), getWidth() - 2 * PADDING - countWidth - PADDING);

            boolean isSelected = Objects.equals(row.tag(), selected);
            if (isSelected) graphics.fill(getX() + 1, textY - 1, getX() + getWidth() - 1, textY + ROW_HEIGHT - 2, SELECTED_BACKGROUND);

            int colour = isSelected ? SELECTED_TEXT
                    : i == hovered ? TextColours.getSearchTermColour()
                    : TextColours.getTextColour();
            graphics.text(font, Component.literal(label), getX() + PADDING, textY, colour);
            graphics.text(font, Component.literal(count), getX() + getWidth() - PADDING - countWidth, textY, isSelected ? SELECTED_TEXT : TextColours.getHintColour());
        }
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean isDoubleClick) {
        int row = rowAt(event.y());
        if (row >= 0 && row < rows.size()) select(rows.get(row).tag());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (!isMouseOver(mouseX, mouseY)) return false;
        scrollTo(firstVisibleRow - (int) Math.signum(deltaY));
        return true;
    }

    @Override
    protected void updateWidgetNarration(@NotNull NarrationElementOutput narrationElementOutput) {

    }
}
