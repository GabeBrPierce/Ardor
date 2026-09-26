package com.ardor.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Detached spectator-style camera: while active, the render camera (see mixin/CameraFreecamMixin)
 * flies independently of the real player entity, which holds completely still -- neither its
 * position nor its rotation ever changes while freecam is on.
 *
 * Position: FreecamController owns its own Vec3, updated once per CLIENT TICK (tick()) from held
 * movement keys, then interpolated for RENDER frames (renderPosition(partialTick), read every frame
 * by CameraFreecamMixin) the same way vanilla interpolates entity movement -- ticks and frames run at
 * different rates, and setting the camera straight to the tick position with no interpolation is
 * what made flight look "choppy, jumping block to block" before this was added.
 *
 * Rotation: mouse-look normally reaches the player entity through MouseHandler.turnPlayer ->
 * Entity.turn(double, double). mixin/FreecamTurnMixin cancels that call for the real player while
 * freecam is active and forwards the exact same (already-sensitivity-scaled) delta to onTurn() below
 * instead -- so the player's rotation is never touched at all (true stillness, no reset-every-frame
 * hack needed), and freecam's own yaw/pitch accumulate exactly like the player's would have. An
 * earlier version of this instead let the player keep rotating and reset it back to a snapshot every
 * frame -- that broke mouse-look almost entirely (confirmed live: "can't change where I'm looking"),
 * since resetting yRot/xRot before the next frame's delta arrived meant every turn was computed
 * relative to the frozen snapshot instead of accumulating. See FreecamTurnMixin's own doc for where
 * the technique (and the 0.15f sensitivity constant below, confirmed via javap against the real
 * Entity.turn bytecode) came from.
 */
public final class FreecamController {

    private static final double SPEED_PER_TICK = 0.6;
    private static final double SPRINT_MULTIPLIER = 2.5;
    private static final float TURN_SCALE = 0.15f;

    private static volatile boolean active;
    private static volatile Vec3 tickPos = Vec3.ZERO;
    private static volatile Vec3 prevTickPos = Vec3.ZERO;
    private static volatile float yaw;
    private static volatile float pitch;

    private static CameraType previousCameraType;

    private FreecamController() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (active) tick(client);
        });
    }

    public static boolean isActive() {
        return active;
    }

    public static float yaw() {
        return yaw;
    }

    public static float pitch() {
        return pitch;
    }

    /** Interpolated between the previous and current tick's position -- call once per render frame, never per tick. */
    public static Vec3 renderPosition(float partialTick) {
        return prevTickPos.lerp(tickPos, partialTick);
    }

    /** Called by FreecamMovementSuppressMixin's turn-redirect (see FreecamTurnMixin) with the same raw, not-yet-scaled delta Entity.turn itself would have applied to the player. */
    public static void onTurn(double yRotDelta, double xRotDelta) {
        yaw += (float) yRotDelta * TURN_SCALE;
        pitch = Mth.clamp(pitch + (float) xRotDelta * TURN_SCALE, -90.0f, 90.0f);
    }

    /** Called only by CameraModeController when switching INTO FLY mode. */
    public static void activate() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        tickPos = player.getEyePosition();
        prevTickPos = tickPos;
        yaw = player.getYRot();
        pitch = player.getXRot();

        // "I want to see the player" -- freecam is meant to be watched from outside, and vanilla
        // never renders your own model in first person.
        previousCameraType = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);

        active = true;
    }

    /** Called by CameraModeController when switching OUT of FLY mode, and internally if the player/level disappears mid-flight. */
    public static void deactivate() {
        active = false;
        if (previousCameraType != null) {
            Minecraft.getInstance().options.setCameraType(previousCameraType);
        }
    }

    private static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            deactivate();
            return;
        }

        var options = client.options;
        double speed = SPEED_PER_TICK * (options.keySprint.isDown() ? SPRINT_MULTIPLIER : 1.0);

        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);

        // Full look-direction forward vector (pitch included, so looking down + forward dives) --
        // matches vanilla spectator-mode flight feel.
        double forwardX = -Math.sin(yawRad) * Math.cos(pitchRad);
        double forwardY = -Math.sin(pitchRad);
        double forwardZ = Math.cos(yawRad) * Math.cos(pitchRad);
        // Right-hand strafe vector, horizontal only regardless of pitch. Derived (not guessed) from
        // vanilla's own yaw convention (0=south/+Z, 90=west/-X, confirmed via the forward vector
        // above): turning +90 deg from facing south is a RIGHT turn and points west, so "right" =
        // forward rotated +90 deg = (-cos(yaw), -sin(yaw)).
        double rightX = -Math.cos(yawRad);
        double rightZ = -Math.sin(yawRad);

        double dx = 0, dy = 0, dz = 0;
        if (options.keyUp.isDown()) { dx += forwardX; dy += forwardY; dz += forwardZ; }
        if (options.keyDown.isDown()) { dx -= forwardX; dy -= forwardY; dz -= forwardZ; }
        if (options.keyRight.isDown()) { dx += rightX; dz += rightZ; }
        if (options.keyLeft.isDown()) { dx -= rightX; dz -= rightZ; }
        if (options.keyJump.isDown()) dy += 1.0;
        if (options.keyShift.isDown()) dy -= 1.0;

        prevTickPos = tickPos;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len > 1e-6) {
            tickPos = tickPos.add(dx / len * speed, dy / len * speed, dz / len * speed);
        }
    }
}
