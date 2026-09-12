package com.ardor.container;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One item source: a physical container, a sub-container (shulker/bundle) nested in another
 * source's slot, the player's ender chest, or a command that grants items. Which fields are
 * meaningful depends on type -- see SourceManager/ContainerCache for how each is read.
 */
public final class ContainerSource {

    public String id = UUID.randomUUID().toString();
    public String name;
    public SourceType type;
    public boolean enabled = true;

    // PHYSICAL
    public Integer x, y, z;

    // SUBCONTAINER -- exactly one of (parentSourceId + parentSlot) or (parentX/Y/Z + parentSlot) is set
    public SubKind subKind;
    public String parentSourceId;
    public Integer parentSlot;
    public Integer parentX, parentY, parentZ;

    // COMMAND
    public String command;
    public Integer cooldownSeconds; // null/0 = no cooldown -- see container.CommandCooldowns

    public List<ContentsEntry> desiredContents = new ArrayList<>();

    public ContainerSource() {}

    /** "Name (or, if unnamed, its location -- or for a sub-container, its parent's location + slot number)" -- the Item Sources screen's row label. */
    public String displayLabel(SourceManager manager) {
        if (name != null && !name.isBlank()) return name;
        return switch (type) {
            case ENDER_CHEST -> "Ender Chest";
            case PHYSICAL, CAULDRON -> x + ", " + y + ", " + z;
            case COMMAND -> command != null ? command : "(no command set)";
            case SUBCONTAINER -> subKind + " @ " + parentLabel(manager) + " slot " + parentSlot;
        };
    }

    private String parentLabel(SourceManager manager) {
        if (parentSourceId != null) {
            ContainerSource parent = manager.get(parentSourceId);
            return parent != null ? parent.displayLabel(manager) : parentSourceId;
        }
        return parentX + ", " + parentY + ", " + parentZ;
    }
}
