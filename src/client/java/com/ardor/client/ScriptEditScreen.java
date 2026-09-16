package com.ardor.client;

import com.ardor.script.ScriptEngine;
import com.ardor.script.ScriptStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    // The debug panel has its own Step/Stop Debug button row (it sits under the top row's Help/
    // Save/Close, which occupy the panel's X range) -- its content starts one row lower than the
    // main editor's.
    private static final int PANEL_CONTENT_TOP = EDITOR_TOP + 24;
    private static final int FOOTER_H = 26;
    private static final int INDENT = 4;
    private static final int PANEL_WIDTH = 200;
    private static final int LINE_HIGHLIGHT_COLOR = 0x40FFD700;

    private final String scriptName;
    private String source;
    private String statusLine = "";
    private MultilineTextField textField;
    private int scrollLine;

    // ---- step debugging (first pass: flat locals/globals list; a real recursive tree view with
    // collapse glyphs, hover-tooltips-over-code, and a multi-script simultaneous view are follow-up
    // work, not attempted here -- see TODO.md) ----
    private boolean debugEnabled;
    private ScriptEngine.DebugSession debugSession;
    private int currentDebugLine = -1;
    private int panelScroll;
    private Button stepButton;
    private Button stopDebugButton;

    private Integer completionStart;
    private List<String> completionMatches = List.of();
    private int completionIndex;

    private Map<String, LuaSignatures.FunctionDoc> userFunctions = Map.of();
    private String syntaxError;
    private static final Pattern SYNTAX_ERROR_LINE = Pattern.compile("^[^:]*:(\\d+):\\s*(.*)$", Pattern.DOTALL);

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
        // learned about re-seeding from a source of truth on every rebuild. The panel is a permanent
        // reserved column rather than something that appears/disappears with the Debug checkbox --
        // toggling it would mean recreating textField at a new width, losing cursor/selection state.
        textField = new MultilineTextField(font, panelLeft() - EDITOR_LEFT - 10);
        textField.setValue(source);
        recomputeDerived(source);
        // Deliberately NOT resetCompletion() here -- handleTab()'s own insertText() call fires this
        // same listener as a side effect, which would immediately erase the completion state
        // handleTab had just set up one line earlier. keyPressed/charTyped/mouseClicked already call
        // resetCompletion() themselves at the actual "this is new user input" points; a value change
        // that arrives via this listener without one of those having run first is handleTab's own
        // programmatic edit, not new input, and shouldn't cancel the cycle it's mid-way through.
        textField.setValueListener(v -> { source = v; recomputeDerived(v); });

        addRenderableWidget(Button.builder(Component.literal("Run"), b -> onRun())
                .bounds(10, 10, 55, 20).build());
        addRenderableWidget(Checkbox.builder(Component.literal("Debug"), font)
                .pos(70, 12).selected(debugEnabled)
                .onValueChange((cb, v) -> debugEnabled = v).build());
        addRenderableWidget(Button.builder(Component.literal("Help"), b -> onHelp())
                .bounds(width - 190, 10, 55, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> onSave())
                .bounds(width - 125, 10, 55, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(width - 65, 10, 55, 20).build());

        stepButton = addRenderableWidget(Button.builder(Component.literal("Step"), b -> onStep())
                .bounds(panelLeft(), EDITOR_TOP, 95, 20).build());
        stopDebugButton = addRenderableWidget(Button.builder(Component.literal("Stop Debug"), b -> onStopDebug())
                .bounds(panelLeft() + 100, EDITOR_TOP, 100, 20).build());
    }

    private int panelLeft() {
        return width - PANEL_WIDTH - 10;
    }

    /** Saves before leaving for the docs -- Help navigates away from the editor same as Close would, so it shouldn't discard an unsaved edit to get there. */
    private void onHelp() {
        ScriptStore.save(scriptName, source);
        Minecraft.getInstance().setScreen(new ScriptDocsScreen(this));
    }

    /** Saves first (so ScriptStore/other call sites see exactly what just ran) then runs the live editor text directly, same error-reporting shape ScriptKeybinds/ScriptWheelKey already use. Screen stays open -- running is meant for iterating on a script, not a one-way trip. */
    private void onRun() {
        ScriptStore.save(scriptName, source);
        if (debugEnabled) {
            onDebugRun();
            return;
        }
        statusLine = "Running " + scriptName + ".lua";
        ScriptEngine.run(source, scriptName, error ->
                Minecraft.getInstance().execute(() -> StatusIndicator.show("Script '" + scriptName + "' failed: " + error)));
    }

    /** Starts a stepped session paused at line 1; Step advances one line at a time (see ScriptEngine.startDebug for how pausing works without blocking the client thread). Starting fresh drops any stale prior session -- it's already either finished or been abandoned if a new Run was clicked. */
    private void onDebugRun() {
        if (debugSession != null) ScriptEngine.cancelDebug(debugSession);
        currentDebugLine = -1;
        panelScroll = 0;
        statusLine = "Debugging " + scriptName + ".lua (stepping)";
        debugSession = ScriptEngine.startDebug(source, scriptName,
                line -> currentDebugLine = line,
                error -> {
                    debugSession = null;
                    currentDebugLine = -1;
                    StatusIndicator.show("Script '" + scriptName + "' failed: " + error);
                });
    }

    private void onStep() {
        if (debugSession == null) return;
        ScriptEngine.step(debugSession);
        if (!debugSession.isPaused()) {
            // Either finished cleanly or errored (the onError callback above already handles error
            // and nulls debugSession) -- a clean finish leaves the session non-null but no longer
            // paused, so clear it here too rather than leaving a dead handle around.
            debugSession = null;
            currentDebugLine = -1;
            statusLine = "Finished " + scriptName + ".lua";
        }
    }

    private void onStopDebug() {
        if (debugSession == null) return;
        ScriptEngine.cancelDebug(debugSession);
        debugSession = null;
        currentDebugLine = -1;
        statusLine = "Stopped " + scriptName + ".lua";
    }

    private int visibleLines() {
        return Math.max(1, (height - EDITOR_TOP - FOOTER_H) / font.lineHeight);
    }

    /** 1-based Lua source line number containing character offset `charOffset` -- a plain newline count, matching how LuaJ itself numbers lines for hook/error reporting. */
    private int sourceLineNumber(int charOffset) {
        int line = 1;
        for (int i = 0; i < charOffset && i < source.length(); i++) {
            if (source.charAt(i) == '\n') line++;
        }
        return line;
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

    /** Re-parses on every edit: cheap at the script sizes this editor sees, and correctness (an up to date lint/tooltip) matters more than shaving a recompute that only costs microseconds. */
    private void recomputeDerived(String v) {
        userFunctions = LuaDocComments.parse(v);
        syntaxError = ScriptEngine.checkSyntax(v);
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

    // ------------------------------------------------------------------ signature help

    private record CallInfo(String name, int argIndex) {}

    /**
     * Which function call (if any) the cursor is currently inside, and which argument position --
     * for the parameter-hint tooltip. Reuses LuaHighlighter's tokenizer (already handles strings/
     * comments correctly) rather than a second hand-rolled scanner: walk the tokens on the current
     * LINE up to the cursor, tracking a stack of open calls (name + running comma count), pushing a
     * nameless entry for a plain grouping "(" so paren depth still balances when one appears. Only
     * looks at the current line -- a call whose "(" was opened on an earlier line won't be detected.
     */
    // ArrayDeque rejects null elements outright, so a plain grouping "(" (not a call) pushes this
    // sentinel instead of null -- still needed so paren depth balances correctly for the calls
    // around it, e.g. "kill(queryEntity(" while typing "if (x) then kill(".
    private static final String NOT_A_CALL = "\0";

    private CallInfo activeCall() {
        int cursor = textField.cursor();
        Object lineView = textField.getLineView(textField.getLineAtCursor());
        String upToCursor = source.substring(viewBegin(lineView), cursor);

        Deque<String> names = new ArrayDeque<>();
        Deque<int[]> argIndexes = new ArrayDeque<>();

        List<LuaHighlighter.Segment> segments = LuaHighlighter.tokenize(upToCursor);
        int i = 0;
        while (i < segments.size()) {
            String text = segments.get(i).text();
            if (isIdentSeg(text)) {
                StringBuilder name = new StringBuilder(text);
                int j = i + 1;
                while (j + 1 < segments.size() && segments.get(j).text().equals(".") && isIdentSeg(segments.get(j + 1).text())) {
                    name.append('.').append(segments.get(j + 1).text());
                    j += 2;
                }
                if (j < segments.size() && segments.get(j).text().equals("(")) {
                    names.push(name.toString());
                    argIndexes.push(new int[]{0});
                    i = j + 1;
                    continue;
                }
                i = j;
                continue;
            }
            switch (text) {
                case "(" -> { names.push(NOT_A_CALL); argIndexes.push(new int[]{0}); }
                case ")" -> { if (!names.isEmpty()) { names.pop(); argIndexes.pop(); } }
                case "," -> { if (!argIndexes.isEmpty()) argIndexes.peek()[0]++; }
                default -> {}
            }
            i++;
        }

        if (names.isEmpty() || names.peek().equals(NOT_A_CALL)) return null;
        return new CallInfo(names.peek(), argIndexes.peek()[0]);
    }

    private static boolean isIdentSeg(String text) {
        char c = text.charAt(0);
        return Character.isLetter(c) || c == '_';
    }

    private List<LuaHighlighter.Segment> signatureSegments(LuaSignatures.FunctionDoc doc, int argIndex) {
        String sig = doc.signature();
        int open = sig.indexOf('(');
        int close = sig.lastIndexOf(')');
        if (open < 0 || close < open) return List.of(new LuaHighlighter.Segment(sig, 0xFFDCDCAA));

        List<LuaHighlighter.Segment> segs = new ArrayList<>();
        segs.add(new LuaHighlighter.Segment(sig.substring(0, open + 1), 0xFFDCDCAA));
        String inner = sig.substring(open + 1, close).strip();
        String[] params = inner.isEmpty() ? new String[0] : inner.split(",\\s*");
        for (int p = 0; p < params.length; p++) {
            if (p > 0) segs.add(new LuaHighlighter.Segment(", ", 0xFF888888));
            boolean active = p == Math.min(argIndex, params.length - 1);
            segs.add(new LuaHighlighter.Segment(params[p], active ? 0xFFFFD700 : 0xFFAAAAAA));
        }
        segs.add(new LuaHighlighter.Segment(")", 0xFFDCDCAA));
        return segs;
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
        return x >= EDITOR_LEFT && x <= panelLeft() - 10 && y >= EDITOR_TOP && y <= height - FOOTER_H;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= panelLeft()) {
            int maxOffset = Math.max(0, panelRowCount() - panelVisibleRows());
            int updated = ScrollState.scrolled(panelScroll, maxOffset, scrollY, 1);
            if (updated != panelScroll) {
                panelScroll = updated;
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
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

    // ------------------------------------------------------------------ debug panel

    private record PanelRow(String text, boolean header) {}

    private static final int PANEL_ROW_H = 12; // tighter than the editor's own line height -- a dense list, not prose

    /**
     * Flat locals-then-globals list for the current pause point -- deliberately NOT the recursive
     * tree view with collapse glyphs the full design calls for (grouped by source, indented,
     * expandable into a table's own fields) -- that's real follow-up work, this proves the
     * underlying step engine is correct first. "[L]"/"[G]" tags stand in for a literal padlock
     * icon: Minecraft's bitmap font has no glyph for one, and which of locals/globals it was even
     * meant to mark was never confirmed (the request cut off mid-sentence) -- [G] leans on the
     * already-documented fact that globals persist across script runs while locals don't, the
     * closest confirmed reading of "locked" available.
     */
    private List<PanelRow> panelRows() {
        List<PanelRow> rows = new ArrayList<>();
        if (debugSession == null) {
            rows.add(new PanelRow("Check Debug, then Run, to step.", false));
            return rows;
        }
        if (!debugSession.isPaused()) {
            rows.add(new PanelRow("Running...", false));
            return rows;
        }
        rows.add(new PanelRow("Locals [L]", true));
        for (String[] kv : ScriptEngine.debugLocals(debugSession)) {
            rows.add(new PanelRow(kv[0] + " = " + kv[1], false));
        }
        rows.add(new PanelRow("Globals [G] (persistent)", true));
        for (String[] kv : ScriptEngine.debugGlobals()) {
            rows.add(new PanelRow(kv[0] + " = " + kv[1], false));
        }
        return rows;
    }

    private int panelVisibleRows() {
        return Math.max(1, (height - PANEL_CONTENT_TOP - FOOTER_H) / PANEL_ROW_H);
    }

    private int panelRowCount() {
        return panelRows().size();
    }

    private void renderDebugPanel(GuiGraphicsExtractor g) {
        g.fill(panelLeft() - 6, PANEL_CONTENT_TOP - 4, width - 4, height - FOOTER_H, 0x80000000);
        List<PanelRow> rows = panelRows();
        int maxOffset = Math.max(0, rows.size() - panelVisibleRows());
        panelScroll = Math.min(panelScroll, maxOffset);

        int y = PANEL_CONTENT_TOP;
        int lastRow = Math.min(rows.size(), panelScroll + panelVisibleRows());
        for (int i = panelScroll; i < lastRow; i++) {
            PanelRow row = rows.get(i);
            String text = row.text();
            if (text.length() > 34) text = text.substring(0, 31) + "..."; // rough char-count clip, not font.width-measured -- good enough ahead of the real tree view
            g.text(font, text, panelLeft(), y, row.header() ? 0xFF569CD6 : 0xFFCCCCCC);
            y += PANEL_ROW_H;
        }
        if (rows.size() > panelVisibleRows()) {
            g.text(font, "Scroll for more.", panelLeft(), height - FOOTER_H - PANEL_ROW_H, 0xFF808080);
        }
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

            // currentDebugLine is a real Lua SOURCE line number (1-based, from the debug hook), not
            // a display-line index -- only ever matches display line i when that source line hasn't
            // been word-wrapped into more than one display line, which is the common case for a
            // script written with reasonably short lines; a wrapped long line could highlight only
            // its first display line. Good enough for a first pass, not attempted to fix further here.
            if (debugSession != null && sourceLineNumber(lineBegin) == currentDebugLine) {
                g.fill(EDITOR_LEFT - 4, y, panelLeft() - 10, y + font.lineHeight, LINE_HIGHLIGHT_COLOR);
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

        renderSyntaxStatus(g);
        if (lineCount > visible) {
            g.text(font, "Scroll for more.", 10, height - 14, 0xFF808080);
        }
        g.text(font, statusLine, width / 2 - 60, height - 14, 0xFFAAAAAA);
        if (completionStart != null) {
            g.text(font, "Tab: " + (completionIndex + 1) + "/" + completionMatches.size(), panelLeft() - 110, height - 14, 0xFFDCDCAA);
        }

        stepButton.active = debugSession != null && debugSession.isPaused();
        stopDebugButton.active = debugSession != null;
        renderDebugPanel(g);

        renderSignatureHelp(g);

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void renderSyntaxStatus(GuiGraphicsExtractor g) {
        if (syntaxError == null) {
            g.text(font, "Syntax OK", 10, height - 24, 0xFF6A9955);
            return;
        }
        Matcher m = SYNTAX_ERROR_LINE.matcher(syntaxError);
        String message = m.matches() ? "Line " + m.group(1) + ": " + m.group(2) : syntaxError;
        g.text(font, message, 10, height - 24, 0xFFFF5555);
    }

    /**
     * Small floating box near the cursor showing the signature of whatever function call it's
     * currently inside, with the active parameter picked out -- checks the CURRENT script's own
     * doc-commented functions (LuaDocComments) before the built-in API (LuaSignatures.BUILTIN), so
     * a user's own function of the same name wins.
     */
    private void renderSignatureHelp(GuiGraphicsExtractor g) {
        CallInfo call = activeCall();
        if (call == null) return;
        LuaSignatures.FunctionDoc doc = userFunctions.get(call.name());
        if (doc == null) doc = LuaSignatures.BUILTIN.get(call.name());
        if (doc == null) return;

        List<LuaHighlighter.Segment> sigSegs = signatureSegments(doc, call.argIndex());
        int sigWidth = sigSegs.stream().mapToInt(s -> font.width(s.text())).sum();
        List<FormattedCharSequence> descLines = font.split(Component.literal(doc.description()), Math.max(150, sigWidth));

        int boxWidth = sigWidth;
        for (FormattedCharSequence line : descLines) boxWidth = Math.max(boxWidth, font.width(line));
        int boxHeight = font.lineHeight + 3 + descLines.size() * (font.lineHeight + 1) + 4;

        int cursorLine = textField.getLineAtCursor();
        Object lineView = textField.getLineView(cursorLine);
        int lineBegin = viewBegin(lineView);
        int cx = EDITOR_LEFT + font.width(source.substring(lineBegin, textField.cursor()));
        int lineScreenY = EDITOR_TOP + (cursorLine - scrollLine) * font.lineHeight;

        int boxX = Math.max(4, Math.min(cx, width - boxWidth - 14));
        int boxY = lineScreenY + font.lineHeight + 4;
        if (boxY + boxHeight > height - FOOTER_H) boxY = lineScreenY - boxHeight - 2;

        g.fill(boxX - 4, boxY - 3, boxX + boxWidth + 4, boxY + boxHeight, 0xF0202020);
        g.fill(boxX - 4, boxY - 3, boxX + boxWidth + 4, boxY - 2, 0xFF569CD6);

        int sx = boxX;
        for (LuaHighlighter.Segment seg : sigSegs) {
            g.text(font, seg.text(), sx, boxY, seg.color());
            sx += font.width(seg.text());
        }
        int dy = boxY + font.lineHeight + 3;
        for (FormattedCharSequence line : descLines) {
            g.text(font, line, boxX, dy, 0xFFCCCCCC);
            dy += font.lineHeight + 1;
        }
    }

    @Override
    public void onClose() {
        // A paused session's LuaThread would otherwise just sit parked forever with no strong
        // reference left once this screen closes -- it'd eventually self-clean (LuaJ's own 5s
        // orphan check throws once the thread is garbage collected), but there's no reason to leave
        // a dangling coroutine around when cancelling it here is one line.
        if (debugSession != null) ScriptEngine.cancelDebug(debugSession);
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
