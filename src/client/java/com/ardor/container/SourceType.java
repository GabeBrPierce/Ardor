package com.ardor.container;

/** PHYSICAL: a real Container block entity (chest, furnace, dispenser, dropper, barrel, ...) at a known position. SUBCONTAINER: a shulker box or bundle sitting in a slot of another source. ENDER_CHEST: the player's own persistent container, not position-bound. COMMAND: a server command that grants items, purely declarative (see ContentsEntry). CAULDRON: a filled lava/water/powder-snow cauldron at a known position, fetchable as its corresponding bucket item if the player has an empty bucket -- see CauldronAccess. */
public enum SourceType {
    PHYSICAL, SUBCONTAINER, ENDER_CHEST, COMMAND, CAULDRON
}
