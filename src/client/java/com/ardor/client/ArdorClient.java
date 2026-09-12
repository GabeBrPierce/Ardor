package com.ardor.client;

import com.ardor.agent.AgentControlChannel;
import com.ardor.bridge.BaritoneNav;
import com.ardor.bridge.BridgeServer;
import com.ardor.container.AutoSourceRecorder;
import com.ardor.event.EventHookDispatcher;
import com.ardor.game.ArrowDodgeController;
import com.ardor.game.AutoEatController;
import com.ardor.game.AutoFleeController;
import com.ardor.game.BaritoneFacingController;
import com.ardor.game.DrowningSafetyController;
import com.ardor.game.ParryController;
import com.ardor.game.ProactiveCombatController;
import com.ardor.game.SleepController;
import com.ardor.game.SocialGreetingController;
import com.ardor.llm.LlmServerManager;
import com.ardor.region.RegionManager;
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
        RegionManager.get().ensureDefaultDefendBinding();
        PickWheelKey.register();
        SingleSelectionOverlay.register();
        LlmServerManager.register();
        MacroCommands.register();
        BridgeServer.register();
        RegionRenderer.register();
        SocialGreetingController.register();
        AutoEatController.register();
        AutoFleeController.register();
        ArrowDodgeController.register();
        SleepController.register();
        ProactiveCombatController.register();
        ParryController.register();
        DrowningSafetyController.register();
        BaritoneFacingController.register();
        AutoSourceRecorder.register();
        FetchItemsKey.register();

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
