package com.ardor.region;

/**
 * Per-region, per-category combat setting -- see Region.passiveMobs/hostileMobs/players and
 * RegionManager.resolveAggressiveness. Applies only to IMPLICIT targeting (the bot deciding on its
 * own to fight something); an explicit user/task command to attack a specific entity always works
 * regardless of this setting.
 */
public enum Aggressiveness {
    /** Never implicitly target this category. */
    OFF,
    /** Implicitly target only in response to being damaged by this category (or as part of an explicit task's own loot needs). */
    REACTIVE,
    /** Actively hunt the nearest matching entity. */
    PROACTIVE
}
