package com.ardor.client;

/**
 * F6 now cycles through three modes instead of toggling one on/off: NORMAL (vanilla, unchanged) ->
 * SIMS (a fixed-ish point-and-click camera -- see SimsCameraController) -> FLY (full WASD freecam --
 * see FreecamController, "the way things are right now" per the user's own framing when this was
 * added) -> back to NORMAL. Only one of SIMS/FLY is ever active at a time; this class is the single
 * place that enforces that and owns the actual mode value everything else (HudManager, the movement/
 * click/turn mixins) reads.
 */
public final class CameraModeController {

    public enum Mode { NORMAL, SIMS, FLY }

    private static volatile Mode mode = Mode.NORMAL;

    private CameraModeController() {}

    public static Mode mode() {
        return mode;
    }

    public static void cycle() {
        setMode(switch (mode) {
            case NORMAL -> Mode.SIMS;
            case SIMS -> Mode.FLY;
            case FLY -> Mode.NORMAL;
        });
    }

    private static void setMode(Mode next) {
        if (mode == next) return;
        switch (mode) {
            case SIMS -> {
                SimsCameraController.deactivate();
                SingleSelectionMode.stop();
            }
            case FLY -> FreecamController.deactivate();
            case NORMAL -> {}
        }
        mode = next;
        switch (next) {
            case SIMS -> {
                SimsCameraController.activate();
                SingleSelectionMode.start(); // tracks the free cursor instead of the crosshair -- see its own tick()
            }
            case FLY -> FreecamController.activate();
            case NORMAL -> {}
        }
    }
}
