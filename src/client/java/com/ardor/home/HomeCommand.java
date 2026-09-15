package com.ardor.home;

/** One taught "home command": a real server/client command that teleports the player (a common SMP convenience, e.g. `/home kitchen`) plus how many seconds to hold still afterward for a hold-still-triggered server-side teleport to actually fire, per the exact case that prompted this: "it will automatically teleport your player to a specific location if we hold still for X amount of seconds." */
public final class HomeCommand {
    public String name;
    public String command;
    public int holdSeconds;

    public HomeCommand() {}

    public HomeCommand(String name, String command, int holdSeconds) {
        this.name = name;
        this.command = command;
        this.holdSeconds = holdSeconds;
    }
}
