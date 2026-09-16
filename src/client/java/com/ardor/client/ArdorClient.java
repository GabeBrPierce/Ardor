package com.ardor.client;

import com.ardor.agent.AgentControlChannel;
import com.ardor.bridge.BaritoneNav;
import com.ardor.bridge.BridgeServer;
import com.ardor.bridge.PeerServer;
import com.ardor.container.AutoSourceRecorder;
import com.ardor.event.EventHookDispatcher;
import com.ardor.event.ScriptEventBindings;
import com.ardor.game.ArrowDodgeController;
import com.ardor.game.AutoEatController;
import com.ardor.game.AutoFleeController;
import com.ardor.game.BaritoneAutoToolController;
import com.ardor.game.BaritoneFacingController;
import com.ardor.game.BaritoneRegionGate;
import com.ardor.game.DrowningSafetyController;
import com.ardor.game.MovementVarianceController;
import com.ardor.game.ParryController;
import com.ardor.game.RegionCombatController;
import com.ardor.game.SleepController;
import com.ardor.game.SocialGreetingController;
import com.ardor.llm.LlmServerManager;
import com.ardor.region.RegionManager;
import com.ardor.struct.PlacementRecorder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint. This mod is client-only (see fabric.mod.json) -- there is
 * no server-side component, since hotkey capture, STT/TTS, and pathfinding
 * are all inherently client concerns.
 */
public class ArdorClient implements ClientModInitializer {
    public static final String MOD_ID = "ardor";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("[{}] client init", MOD_ID);
        PushToTalk.register();
        ChatListener.register();
        AgentControlChannel.register();
        RegionCommands.register();
        EventHookDispatcher.register();
        ScriptEventBindings.register();
        PickWheelKey.register();
        SingleSelectionOverlay.register();
        LlmServerManager.register();
        MacroCommands.register();
        BridgeServer.register();
        PeerServer.register();
        RegionRenderer.register();
        SocialGreetingController.register();
        AutoEatController.register();
        AutoFleeController.register();
        ArrowDodgeController.register();
        SleepController.register();
        RegionCombatController.register();
        ParryController.register();
        DrowningSafetyController.register();
        BaritoneFacingController.register();
        BaritoneRegionGate.register();
        BaritoneAutoToolController.register();
        AutoSourceRecorder.register();
        FetchItemsKey.register();
        MovementVarianceController.register();
        StructCommands.register();
        ScriptCommands.register();
        HomeCommands.register();
        ScriptWheelCommands.register();
        ScriptWheelKey.register();
        ScriptKeybindCommands.register();
        ScriptKeybinds.register();
        MacroKeybinds.register();
        MacroStopRecordingKey.register();
        KeybindControl.register();
        ActivityTracker.register();
        TaskPlannerKey.register();
        PanicStopKey.register();
        PanicStopCommand.register();
        PauseToggleKey.register();
        ArdorMasterToggleKey.register();
        QuestTrackerOverlay.register();
        HudManager.register();
        PlacementRecorder.register();

        // Xaero's World Map integration (com.ardor.xaero.*) needs no explicit call here --
        // RegionChunkHighlighter gets registered automatically by RegisterHighlighterMixin, which
        // only applies at all if Xaero's World Map is actually installed (see
        // ardor-xaero.mixins.json's "required": false). A tick-polling registration call used
        // to live here; removed once that approach was confirmed to always be too late (see
        // TODO.md) -- Xaero freezes its HighlighterRegistry synchronously during its own session
        // init, before any of our post-hoc code could ever observe a live session.

        // The bridge/companion drives this game unattended for long stretches with no real mouse/
        // keyboard activity -- the OS eventually gives focus to something else, and vanilla's
        // auto-pause-on-focus-loss (Options.pauseOnLostFocus, on by default) opens the pause menu,
        // which halts the ENTIRE client tick loop (see BridgeServer's javadoc) and silently breaks
        // every tick-driven bridge command until a human physically clicks the window again -- hit
        // repeatedly this session. Forced off in memory only, every launch, never written to
        // options.txt: this is a "the bot is running" behavior, not a permanent user preference, so
        // removing the mod restores stock behavior with zero leftover trace.
        ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
            mc.options.pauseOnLostFocus = false;
            BaritoneNav.configure();
        });
    }
}
