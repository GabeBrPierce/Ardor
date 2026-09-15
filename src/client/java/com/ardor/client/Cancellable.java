package com.ardor.client;

/** Something ArdorMasterToggle can shut off when the bot is disabled -- see ArdorMasterToggle.register(Cancellable). */
public interface Cancellable {
    void cancel();
}
