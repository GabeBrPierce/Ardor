package com.ardor.client;

import net.fabricmc.fabric.api.event.client.player.ClientHotbarScrollEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Mode 2 ("Sims mode"): a fixed point-and-click camera, not a flying one -- the cursor is freed
 * (releaseMouse() on activate) and drives a little select-cube under it (SingleSelectionMode, fed
 * this class's own CursorRaycast hit result instead of the crosshair) that right-click opens a
 * contextual wheel for and left-click selects. No WASD flight here at all -- that's FLY mode's job.
 *
 * Controls, per live feedback after the first version (which just rotated/panned in place):
 *   - Middle-click + drag: ORBITS around whatever the cursor was pointing at when the drag started
 *     (the pivot + the camera's distance from it are both captured once, on press -- not
 *     re-picked every frame, or the orbit would swim instead of feeling anchored), not a
 *     rotate-in-place turn.
 *   - Left-click + drag: pans (translates the camera without changing look direction or the orbit
 *     pivot).
 *   - Scroll: dollies the camera forward/backward along its current look direction.
 *   - Alt+scroll: moves the camera closer to/further from whatever the cursor currently points at
 *     specifically (a separate axis from plain dolly -- this one is always relative to the live
 *     cursor target, not the camera's facing).
 */
public final class SimsCameraController {

    private static final float ROTATE_DEG_PER_PIXEL = 0.15f;
    private static final double PAN_BLOCKS_PER_PIXEL = 0.03;
    private static final double DOLLY_BLOCKS_PER_SCROLL = 1.5;
    private static final double ZOOM_BLOCKS_PER_SCROLL = 1.5;
    private static final double MIN_ZOOM_DISTANCE = 0.5; // never let alt+scroll push the camera through its own target
    private static final double DEFAULT_ORBIT_DISTANCE = 8.0; // used when a middle-drag starts with nothing under the cursor

    private static volatile boolean active;
    private static Vec3 position = Vec3.ZERO;
    private static float yaw;
    private static float pitch;
    private static double lastMouseX;
    private static double lastMouseY;
    private static HitResult cursorHit;

    // Captured once when a middle-drag begins (see onMiddleDown) -- orbit() below holds these fixed
    // for the whole gesture so the camera swings around one anchored point instead of the pivot
    // sliding around under the cursor as it moves during the drag.
    private static Vec3 orbitPivot;
    private static double orbitRadius;

    // Fed by the unified click mixin (see FreecamClickMixin -- one mixin has to own every button for
    // ALL camera modes, since two separate cancelling @Inject(HEAD)s on the same vanilla method can't
    // safely coexist: whichever cancels first silently prevents the other from ever running).
    private static volatile boolean leftDown;
    private static volatile boolean middleDown;
    private static double leftPressX;
    private static double leftPressY;
    private static boolean dragging;
    private static final double DRAG_THRESHOLD_PX = 4.0;

    private static CameraType previousCameraType;
    private static boolean scrollHookRegistered;

    private SimsCameraController() {}

    public static boolean isActive() {
        return active;
    }

    public static float yaw() {
        return yaw;
    }

    public static float pitch() {
        return pitch;
    }

    public static Vec3 position() {
        return position;
    }

    /** Whatever CursorRaycast last found under the free cursor -- null until the first frame after activation. Read by SingleSelectionMode when in Sims mode. */
    public static HitResult cursorHit() {
        return cursorHit;
    }

    /** Called only by CameraModeController when switching INTO SIMS mode. */
    public static void activate() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        position = player.getEyePosition();
        yaw = player.getYRot();
        pitch = player.getXRot();

        previousCameraType = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);

        mc.mouseHandler.releaseMouse();
        lastMouseX = mc.mouseHandler.getScaledXPos(mc.getWindow());
        lastMouseY = mc.mouseHandler.getScaledYPos(mc.getWindow());

        ensureScrollHook();
        active = true;
    }

    /** Called by CameraModeController when switching OUT of SIMS mode. */
    public static void deactivate() {
        active = false;
        cursorHit = null;
        Minecraft mc = Minecraft.getInstance();
        if (previousCameraType != null) mc.options.setCameraType(previousCameraType);
        mc.mouseHandler.grabMouse();
    }

    /**
     * Called every render frame by mixin/CameraFreecamMixin -- reads the free cursor's movement
     * since last frame and applies orbit/pan, then recomputes the cursor raycast. Runs OUTSIDE any
     * try/catch of its own on the mixin side deliberately matched with one here: an exception
     * escaping into Camera.update() would abort that frame's camera positioning silently (no crash,
     * just a frozen/wrong camera that frame) with nothing printed anywhere -- exactly what "the
     * select cursor isn't showing at all" would look like if this were ever throwing every frame.
     * Logs loudly (not just once) if it ever does, instead of failing silently like that again.
     */
    public static void onRenderFrame() {
        try {
            onRenderFrameInner();
        } catch (RuntimeException e) {
            System.err.println("[ardor] Sims camera render update failed: " + e);
        }
    }

    private static void onRenderFrameInner() {
        // Self-healing: AreaSelectionMode.startCorners() stops SingleSelectionMode for mutual
        // exclusivity (same as it always has in NORMAL mode); once an area selection finishes (or is
        // cancelled) neither is left active, and Sims mode's cursor cube would otherwise just stay
        // dark for the rest of the session. Restart it the instant both go idle.
        if (!SingleSelectionMode.isActive() && !AreaSelectionMode.isActive()) {
            SingleSelectionMode.start();
        }

        Minecraft mc = Minecraft.getInstance();
        double x = mc.mouseHandler.getScaledXPos(mc.getWindow());
        double y = mc.mouseHandler.getScaledYPos(mc.getWindow());
        double dx = x - lastMouseX;
        double dy = y - lastMouseY;
        lastMouseX = x;
        lastMouseY = y;

        if (!dragging && leftDown && (Math.abs(x - leftPressX) > DRAG_THRESHOLD_PX || Math.abs(y - leftPressY) > DRAG_THRESHOLD_PX)) {
            dragging = true;
        }

        if (middleDown) {
            orbit(dx, dy);
        } else if (dragging) {
            pan(dx, dy);
        }

        cursorHit = CursorRaycast.cast(position, yaw, pitch, x, y);
    }

    /** Fed by the unified click mixin. Left button down starts a click/drag distinction -- see the DRAG_THRESHOLD_PX check in onRenderFrame(). */
    public static void onLeftPress(double x, double y) {
        leftDown = true;
        leftPressX = x;
        leftPressY = y;
        dragging = false;
    }

    /**
     * A plain click (not a drag) is the SAME tap gesture PickWheelKey's release already runs in
     * NORMAL mode -- Go Here / edit a container / advance an in-progress Area Selection -- just
     * triggered by a plain left-click here instead of a middle-click tap, since Sims mode already
     * repurposes middle-click for camera rotation.
     */
    public static void onLeftRelease() {
        leftDown = false;
        boolean wasClick = !dragging;
        dragging = false;
        if (wasClick) {
            if (!SingleSelectionMode.tryGoHere() && !SingleSelectionMode.tryEditContainer()) {
                AreaSelectionMode.tryConfirm();
            }
        }
    }

    /** Captures the orbit pivot/radius exactly once, on the down-transition -- see orbit()'s own doc for why this can't just be re-picked every frame. */
    public static void setMiddleDown(boolean down) {
        if (down && !middleDown) {
            Vec3 dir = viewDirection();
            orbitPivot = (cursorHit != null && cursorHit.getType() != HitResult.Type.MISS)
                    ? cursorHit.getLocation()
                    : position.add(dir.scale(DEFAULT_ORBIT_DISTANCE));
            orbitRadius = Math.max(MIN_ZOOM_DISTANCE, position.distanceTo(orbitPivot));
        }
        middleDown = down;
    }

    /** Rotates yaw/pitch as before, then repositions the camera to stay orbitRadius away from the fixed orbitPivot, always facing it -- an actual orbit instead of a rotate-in-place turn. */
    private static void orbit(double dx, double dy) {
        yaw += (float) dx * ROTATE_DEG_PER_PIXEL;
        pitch = Mth.clamp(pitch + (float) dy * ROTATE_DEG_PER_PIXEL, -90f, 90f);
        position = orbitPivot.subtract(viewDirection().scale(orbitRadius));
    }

    /**
     * Pans the camera relative to its own view: dragging the mouse right moves the camera LEFT and
     * vice versa (grab-and-pull, RTS/map-editor style) -- confirmed live this was backwards for the
     * left/right axis specifically in the first version and flipped here; up/down was not reported
     * as backwards, left as-is.
     */
    private static void pan(double dx, double dy) {
        Vector3f right = rightVector();
        Vector3f up = upVector();
        double scale = PAN_BLOCKS_PER_PIXEL;
        position = position
                .add(right.x * dx * scale, right.y * dx * scale, right.z * dx * scale)
                .add(up.x * dy * scale, up.y * dy * scale, up.z * dy * scale);
    }

    private static void ensureScrollHook() {
        if (scrollHookRegistered) return;
        scrollHookRegistered = true;
        ClientHotbarScrollEvents.ALLOW.register((inventory, oldSlot, newSlot, scrollX, scrollY) -> {
            if (!active) return true;
            boolean altHeld = com.mojang.blaze3d.platform.InputConstants.isKeyDown(com.mojang.blaze3d.platform.InputConstants.KEY_LALT)
                    || com.mojang.blaze3d.platform.InputConstants.isKeyDown(com.mojang.blaze3d.platform.InputConstants.KEY_RALT);
            if (altHeld) {
                zoomToCursor(scrollY);
            } else {
                dolly(scrollY);
            }
            return false; // Sims mode never wants scroll to also change the hotbar slot
        });
    }

    /** Plain scroll: move forward/backward along wherever the camera is currently facing, independent of the cursor. */
    private static void dolly(double scrollY) {
        position = position.add(viewDirection().scale(scrollY * DOLLY_BLOCKS_PER_SCROLL));
    }

    /** Alt+scroll: move toward/away from whatever the cursor is CURRENTLY pointing at (falls back to the view direction if the cursor isn't over anything). Clamped so scrolling in can't push the camera past MIN_ZOOM_DISTANCE from that target. */
    private static void zoomToCursor(double scrollY) {
        Vec3 target = (cursorHit != null && cursorHit.getType() != HitResult.Type.MISS) ? cursorHit.getLocation() : null;
        Vec3 dir = target != null ? target.subtract(position) : viewDirection();
        double distToTarget = target != null ? dir.length() : Double.MAX_VALUE;
        Vec3 unit = target != null ? dir.scale(1.0 / Math.max(dir.length(), 1e-6)) : dir;

        double step = scrollY * ZOOM_BLOCKS_PER_SCROLL;
        if (step > 0) step = Math.min(step, Math.max(0, distToTarget - MIN_ZOOM_DISTANCE));
        position = position.add(unit.scale(step));
    }

    private static Vec3 viewDirection() {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        return new Vec3(-Math.sin(yawRad) * Math.cos(pitchRad), -Math.sin(pitchRad), Math.cos(yawRad) * Math.cos(pitchRad));
    }

    private static Vector3f rightVector() {
        return rotationQuat().transform(new Vector3f(1f, 0f, 0f));
    }

    private static Vector3f upVector() {
        return rotationQuat().transform(new Vector3f(0f, 1f, 0f));
    }

    private static Quaternionf rotationQuat() {
        return new Quaternionf().rotationYXZ((float) Math.toRadians(-yaw), (float) Math.toRadians(pitch), 0f);
    }
}
