package com.ardor.event;

import com.ardor.script.ScriptWheelEntry;

import java.util.ArrayList;
import java.util.List;

/** A UI-configured script-driven event: poll predicateScript every intervalTicks, and on true, run every subscriber. Reuses ScriptWheelEntry {label, kind, name} for subscribers -- same "script or macro by name" shape a wheel wedge already has. */
public final class ScriptEventDef {
    public String name;
    public int intervalTicks = 20;
    public String predicateScript = "";
    public List<ScriptWheelEntry> subscribers = new ArrayList<>();

    public ScriptEventDef() {}

    public ScriptEventDef(String name) {
        this.name = name;
    }
}
