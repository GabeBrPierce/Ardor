package com.ardor.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Lua scripting reference (ScriptDocsContent) as one continuously scrollable document, with a
 * table of contents down the left that jumps to a section on click and highlights ("scroll spies")
 * whichever section is currently at the top of the viewport as the user scrolls.
 */
public final class ScriptDocsScreen extends Screen {

    private record Line(FormattedCharSequence text, int color, int y, boolean isHeader) {}

    private static final int TOC_LEFT = 10;
    private static final int TOC_WIDTH = 140;
    private static final int CONTENT_LEFT = 160;
    private static final int CONTENT_TOP = 34;
    private static final int FOOTER_H = 16;
    private static final int PARAGRAPH_GAP = 6;
    private static final int SECTION_GAP = 14;
    private static final int HEADER_COLOR = 0xFF569CD6;
    private static final int BODY_COLOR = 0xFFE0E0E0;
    private static final int CODE_COLOR = 0xFFDCDCAA;

    private final Screen parent;
    private List<Line> lines = List.of();
    private int[] sectionStartY = new int[0];
    private int totalHeight;
    private int scrollY;

    public ScriptDocsScreen(Screen parent) {
        super(Component.literal("Lua Scripting Reference"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearWidgets();
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(width - 65, 10, 55, 20).build());
        layoutContent();
    }

    private int contentWidth() {
        return Math.max(50, width - CONTENT_LEFT - 10);
    }

    private void layoutContent() {
        List<Line> built = new ArrayList<>();
        int[] starts = new int[ScriptDocsContent.SECTIONS.size()];
        int y = 0;

        for (int s = 0; s < ScriptDocsContent.SECTIONS.size(); s++) {
            ScriptDocsContent.Section section = ScriptDocsContent.SECTIONS.get(s);
            starts[s] = y;

            for (FormattedCharSequence headerLine : font.split(
                    Component.literal(section.title()).withStyle(ChatFormatting.BOLD), contentWidth())) {
                built.add(new Line(headerLine, HEADER_COLOR, y, true));
                y += font.lineHeight + 2;
            }
            y += PARAGRAPH_GAP;

            for (String block : section.body().split("\n\n")) {
                boolean isCode = block.stripLeading().startsWith(">");
                if (isCode) {
                    for (String codeLine : block.split("\n")) {
                        // Strip only the "> " marker itself, not all leading whitespace -- a plain
                        // strip() here ate any indentation an example deliberately used to show
                        // nested Lua blocks (if/for bodies), flattening every example to one column.
                        String text = codeLine.stripTrailing();
                        int marker = text.indexOf('>');
                        if (marker >= 0 && text.substring(0, marker).isBlank()) {
                            text = text.substring(marker + 1);
                            if (!text.isEmpty() && text.charAt(0) == ' ') text = text.substring(1);
                        }
                        built.add(new Line(FormattedCharSequence.forward(text, net.minecraft.network.chat.Style.EMPTY), CODE_COLOR, y, false));
                        y += font.lineHeight + 1;
                    }
                } else {
                    String paragraph = block.replace("\n", " ").strip();
                    if (paragraph.isEmpty()) continue;
                    for (FormattedCharSequence wrapped : font.split(Component.literal(paragraph), contentWidth())) {
                        built.add(new Line(wrapped, BODY_COLOR, y, false));
                        y += font.lineHeight + 1;
                    }
                }
                y += PARAGRAPH_GAP;
            }
            y += SECTION_GAP;
        }

        lines = built;
        sectionStartY = starts;
        totalHeight = y;
        scrollY = Math.min(scrollY, maxScroll());
    }

    private int viewportHeight() {
        return Math.max(1, height - CONTENT_TOP - FOOTER_H);
    }

    private int maxScroll() {
        return Math.max(0, totalHeight - viewportHeight());
    }

    /** The section whose heading is at or above the current scroll position -- what the TOC highlights. */
    private int activeSection() {
        int active = 0;
        for (int s = 0; s < sectionStartY.length; s++) {
            if (sectionStartY[s] <= scrollY) active = s; else break;
        }
        return active;
    }

    private void jumpTo(int section) {
        scrollY = Math.min(sectionStartY[section], maxScroll());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && event.x() >= TOC_LEFT && event.x() <= TOC_LEFT + TOC_WIDTH
                && event.y() >= CONTENT_TOP) {
            // Integer division truncates toward zero, not floor -- without the y >= CONTENT_TOP guard
            // above, a click just above the TOC (e.g. near the title) would compute row 0 instead of
            // a negative row, wrongly registering as a click on the first section.
            int row = (int) ((event.y() - CONTENT_TOP) / (font.lineHeight + 4));
            if (row >= 0 && row < ScriptDocsContent.SECTIONS.size()) {
                jumpTo(row);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int updated = ScrollState.scrolled(this.scrollY, maxScroll(), scrollY, font.lineHeight * 3);
        if (updated != this.scrollY) {
            this.scrollY = updated;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xC0101010);
        g.text(font, getTitle().getString(), 10, 1, 0xFFFFFFFF);

        int active = activeSection();
        int rowH = font.lineHeight + 4;
        for (int s = 0; s < ScriptDocsContent.SECTIONS.size(); s++) {
            int rowY = CONTENT_TOP + s * rowH;
            boolean isActive = s == active;
            if (isActive) g.fill(TOC_LEFT - 2, rowY - 2, TOC_LEFT + TOC_WIDTH, rowY + font.lineHeight + 2, 0x803A6EA5);
            g.text(font, ScriptDocsContent.SECTIONS.get(s).title(), TOC_LEFT, rowY, isActive ? 0xFFFFFFFF : 0xFFAAAAAA);
        }

        int viewport = viewportHeight();
        for (Line line : lines) {
            int screenY = CONTENT_TOP + (line.y() - scrollY);
            if (screenY < CONTENT_TOP - font.lineHeight || screenY > CONTENT_TOP + viewport) continue;
            g.text(font, line.text(), CONTENT_LEFT, screenY, line.color());
        }

        if (maxScroll() > 0) {
            g.text(font, "Scroll for more.", CONTENT_LEFT, height - FOOTER_H + 2, 0xFF808080);
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
