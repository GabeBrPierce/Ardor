package com.ardor.client;

import java.util.List;

/**
 * A human-in-the-loop checklist -- for steps Ardor can't do itself and needs the PLAYER to
 * physically perform (e.g. GuidedCalibration's calibration clicks), rendered by
 * QuestTrackerOverlay as a real on-screen checklist instead of relying on chat narration the
 * player might not be watching. Exactly one board is active at a time; starting a new one
 * replaces whatever was showing. Any feature with steps for the player to carry out can use this
 * the same way -- it's not enchant-specific.
 */
public final class PlayerTaskBoard {

    public record Step(String text) {}

    private static String title = null;
    private static List<Step> steps = List.of();
    private static int currentIndex = -1;
    private static String detail = null;

    private PlayerTaskBoard() {}

    public static void start(String title, List<String> stepTexts) {
        PlayerTaskBoard.title = title;
        PlayerTaskBoard.steps = stepTexts.stream().map(Step::new).toList();
        PlayerTaskBoard.currentIndex = 0;
        PlayerTaskBoard.detail = null;
    }

    public static void advanceTo(int index) {
        currentIndex = index;
        detail = null;
    }

    /** Extra live-progress line shown under the current step (e.g. "Dropped 3/7") -- cleared automatically on the next advanceTo(). */
    public static void setDetail(String detail) {
        PlayerTaskBoard.detail = detail;
    }

    public static void clear() {
        title = null;
        steps = List.of();
        currentIndex = -1;
        detail = null;
    }

    public static boolean isActive() {
        return title != null;
    }

    public static String title() {
        return title;
    }

    public static List<Step> steps() {
        return steps;
    }

    public static int currentIndex() {
        return currentIndex;
    }

    public static String detail() {
        return detail;
    }
}
