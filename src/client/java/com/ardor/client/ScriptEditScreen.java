package com.ardor.client;

import com.ardor.script.ScriptStore;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Lua source editor for one script, opened from ScriptListScreen. Syntax-colored (LuaHighlighter)
 * and Tab-completes known API names (LuaHighlighter.KNOWN_NAMES), on top of real editing/selection/
 * word-wrap -- but NOT vanilla's MultiLineEditBox widget, since it renders flat single-color text
 * with no per-token hook and no way to subclass it (private constructor). Instead this drives
 * MultilineTextField directly -- the actual editing engine underneath that widget, which IS public
 * and has everything (cursor, selection, line wrap, keyPressed) except rendering and scrolling; both
 * of those are handled here so each visual line can be colored token-by-token.
 */
public final class ScriptEditScreen extends Screen {

    private static final int EDITOR_LEFT = 10;
    private static final int EDITOR_TOP = 34;
    private static final int FOOTER_H = 26;
    private static final int INDENT = 4;

    private final String scriptName;
    private String source;
    private String statusLine = "";
    private MultilineTextField textField;
    private int scrollLine;

    private Integer completionStart;
    private List<String> completionMatches = List.of();
    private int completionIndex;

    // MultilineTextField.getLineView/getSelected return StringView, which Mojang's own bytecode
    // marks `protected` as a MEMBER of MultilineTextField (see the InnerClasses attribute -- its
    // own class file is public, but that's not what the compiler checks for `Outer.Inner` access),
    // so it can't be named as a type from this package. beginIndex()/endIndex() are genuinely public
    // methods on it though, so a cached reflective handle reaches them without needing to name the type.
    private static final Method VIEW_BEGIN;
    private static final Method VIEW_END;
    static {
        try {
            Class<?> viewClass = Class.forName("net.minecraft.client.gui.components.MultilineTextField$StringView");
            VIEW_BEGIN = viewClass.getMethod("beginIndex");
            VIEW_END = viewClass.getMethod("endIndex");
            VIEW_BEGIN.setAccessible(true);
            VIEW_END.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static int viewBegin(Object stringView) {
        try {
            return (int) VIEW_BEGIN.invoke(stringView);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static int viewEnd(Object stringView) {
        try {
            return (int) VIEW_END.invoke(stringView);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public ScriptEditScreen(String scriptName) {
        super(Component.literal("Edit Script: " + scriptName));
        this.scriptName = scriptName;
        this.source = ScriptStore.exists(scriptName) ? ScriptStore.load(scriptName) : "";
    }

    @Override
    protected void init() {
        clearWidgets();
        // init() also runs on window resize, so the field is seeded from the held `source` (kept
        // current by the value listener), never re-read from disk -- same lesson RegionEditScreen
        // learned about re-seeding from a source of truth on every rebuild.
        textField = new MultilineTextField(font, width - EDITOR_LEFT - 10);
        textField.setValue(source);
        textField.setValueListener(v -> { source = v; resetCompletion(); });

        addRenderableWidget(Button.builder(Component.literal("Save"), b -> onSave())
                .bounds(width - 125, 10, 55, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(width - 65, 10, 55, 20).build());
    }

    private int visibleLines() {
        return Math.max(1, (height - EDITOR_TOP - FOOTER_H) / font.lineHeight);
    }

    private void scrollToCursor() {
        int line = textField.getLineAtCursor();
        if (line < scrollLine) scrollLine = line;
        int visible = visibleLines();
        if (line >= scrollLine + visible) scrollLine = line - visible + 1;
    }

    private void resetCompletion() {
        completionStart = null;
        completionMatches = List.of();
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_TAB) {
            handleTab();
            return true;
        }
        resetCompletion();
        boolean handled = textField.keyPressed(event);
        if (handled) {
            scrollToCursor();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        resetCompletion();
        textField.insertText(event.codepointAsString());
        scrollToCursor();
        return true;
    }

    private void handleTab() {
        if (completionStart != null) {
            // Cycling through an already-started completion: select [completionStart, cursor) --
            // exactly the match just inserted -- then let insertText replace it with the next one.
            completionIndex = (completionIndex + 1) % completionMatches.size();
            selectRange(completionStart);
            textField.insertText(completionMatches.get(completionIndex));
            scrollToCursor();
            return;
        }

        String value = textField.value();
        int cursor = textField.cursor();
        int wordStart = cursor;
        while (wordStart > 0 && isWordChar(value.charAt(wordStart - 1))) wordStart--;
        String prefix = value.substring(wordStart, cursor);

        List<String> matches = prefix.isEmpty() ? List.of() : LuaHighlighter.KNOWN_NAMES.stream()
                .filter(n -> n.startsWith(prefix) && !n.equals(prefix))
                .sorted()
                .toList();

        if (matches.isEmpty()) {
            for (int i = 0; i < INDENT; i++) textField.insertText(" ");
            scrollToCursor();
            return;
        }

        completionStart = wordStart;
        completionMatches = matches;
        completionIndex = 0;
        selectRange(wordStart);
        textField.insertText(matches.get(0));
        scrollToCursor();
    }

    /**
     * Selects [from, cursor) so a following insertText() replaces exactly that range. seekCursor
     * ALSO collapses selectCursor to match the new cursor whenever `selecting` is false (confirmed
     * via bytecode -- easy to miss since the collapse is the very last two instructions of the
     * method), so moving the cursor back to `from` without first flipping selecting=true would just
     * relocate the cursor instead of selecting anything.
     */
    private void selectRange(int from) {
        textField.setSelecting(true);
        textField.seekCursor(Whence.ABSOLUTE, from);
        textField.setSelecting(false);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!inEditorBounds(event.x(), event.y())) return super.mouseClicked(event, doubleClick);
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubleClick);
        resetCompletion();
        textField.setSelecting(false);
        seekToScreenPoint(event.x(), event.y());
        if (doubleClick) textField.selectWordAtCursor();
        scrollToCursor();
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseDragged(event, dragX, dragY);
        textField.setSelecting(true);
        seekToScreenPoint(event.x(), event.y());
        scrollToCursor();
        return true;
    }

    private void seekToScreenPoint(double screenX, double screenY) {
        double relX = screenX - EDITOR_LEFT;
        double relY = scrollLine * font.lineHeight + (screenY - EDITOR_TOP);
        textField.seekCursorToPoint(relX, relY);
    }

    private boolean inEditorBounds(double x, double y) {
        return x >= EDITOR_LEFT && x <= width - 10 && y >= EDITOR_TOP && y <= height - FOOTER_H;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxOffset = Math.max(0, textField.getLineCount() - visibleLines());
        int updated = ScrollState.scrolled(scrollLine, maxOffset, scrollY, 1);
        if (updated != scrollLine) {
            scrollLine = updated;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void onSave() {
        ScriptStore.save(scriptName, source);
        statusLine = "Saved " + scriptName + ".lua";
    }

    // ------------------------------------------------------------------ render

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xC0101010);
        g.text(font, getTitle().getString(), 10, 1, 0xFFFFFFFF);

        int lineCount = textField.getLineCount();
        int visible = visibleLines();
        int lastLine = Math.min(lineCount, scrollLine + visible);

        boolean hasSelection = textField.hasSelection();
        Object selection = hasSelection ? textField.getSelected() : null;

        int y = EDITOR_TOP;
        for (int i = scrollLine; i < lastLine; i++) {
            Object lineView = textField.getLineView(i);
            int lineBegin = viewBegin(lineView), lineEnd = viewEnd(lineView);
            String lineText = source.substring(lineBegin, lineEnd);

            if (hasSelection && viewEnd(selection) > lineBegin && viewBegin(selection) < lineEnd) {
                int selStart = Math.max(viewBegin(selection), lineBegin) - lineBegin;
                int selEnd = Math.min(viewEnd(selection), lineEnd) - lineBegin;
                int x0 = EDITOR_LEFT + font.width(lineText.substring(0, selStart));
                int x1 = EDITOR_LEFT + font.width(lineText.substring(0, selEnd));
                g.fill(x0, y, x1, y + font.lineHeight, 0x803A6EA5);
            }

            int x = EDITOR_LEFT;
            for (LuaHighlighter.Segment segment : LuaHighlighter.tokenize(lineText)) {
                g.text(font, segment.text(), x, y, segment.color());
                x += font.width(segment.text());
            }

            if (i == textField.getLineAtCursor()) {
                int col = textField.cursor() - lineBegin;
                int cx = EDITOR_LEFT + font.width(lineText.substring(0, col));
                g.fill(cx, y, cx + 1, y + font.lineHeight, 0xFFFFFFFF);
            }

            y += font.lineHeight;
        }

        if (lineCount > visible) {
            g.text(font, "Scroll for more.", 10, height - 14, 0xFF808080);
        }
        g.text(font, statusLine, width / 2 - 60, height - 18, 0xFFAAAAAA);
        if (completionStart != null) {
            g.text(font, "Tab: " + (completionIndex + 1) + "/" + completionMatches.size(), width - 200, height - 18, 0xFFDCDCAA);
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
