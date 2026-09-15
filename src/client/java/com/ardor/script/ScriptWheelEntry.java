package com.ardor.script;

/** One configured wedge of the open-wheel-gui: a label plus either a saved script (ScriptStore) or a saved macro (macro.MacroStore) to run when picked. */
public final class ScriptWheelEntry {
    public String label;
    /** "script" or "macro". */
    public String kind;
    public String name;

    public ScriptWheelEntry() {}

    public ScriptWheelEntry(String label, String kind, String name) {
        this.label = label;
        this.kind = kind;
        this.name = name;
    }
}
