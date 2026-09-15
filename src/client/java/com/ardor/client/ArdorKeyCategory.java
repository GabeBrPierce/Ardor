package com.ardor.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/** Shared Controls-screen category ("Ardor") every keybind this mod registers is filed under, instead of vanilla's Miscellaneous. */
public final class ArdorKeyCategory {

    public static final KeyMapping.Category ARDOR =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("ardor", "ardor"));

    private ArdorKeyCategory() {}
}
