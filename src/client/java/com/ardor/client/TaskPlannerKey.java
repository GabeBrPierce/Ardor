package com.ardor.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/** Opens TaskPlannerScreen -- "the B key has stopped working recently, let's add that back." Deleted during the Cloth Config/Mod Menu migration (still reachable via ArdorConfigScreen's "Task Planner" button), restored as its own key for fast direct access, same reasoning FetchItemsKey/ScriptWheelKey already kept theirs for. Bound to B by default. */
public final class TaskPlannerKey {

    private static final KeyMapping KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.ardor.taskplanner", InputConstants.KEY_B, ArdorKeyCategory.ARDOR));

    private TaskPlannerKey() {}

    public static void register() {
        KeybindTicker.add(KEY, TaskPlannerKey::open);
    }

    private static void open() {
        Minecraft client = Minecraft.getInstance();
        if (client.screen == null) {
            client.setScreen(new TaskPlannerScreen());
        }
    }
}
