package com.ardor.client;

import com.ardor.config.ArdorConfig;
import com.ardor.history.ChatHistory;
import com.ardor.llm.ChatCompletionClient;
import com.ardor.voice.ResponseHandler;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;

import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Deterministic trigger gate for chat-based interaction (as opposed to
 * push-to-talk): responds only when a message mentions the wake word AND
 * the cooldown has elapsed. This is exactly "Layer 1" from the original
 * design -- ships with zero training data, and alone prevents the bot from
 * jumping into every conversation. Every message is still logged via
 * ChatHistory regardless of whether it triggers, building labeled data
 * (triggered vs not) toward a future learned "Layer 2" classifier.
 */
public final class ChatListener {

    private static final Duration COOLDOWN = Duration.ofSeconds(3);
    private static Instant lastTrigger = Instant.EPOCH;

    private ChatListener() {}

    public static void register() {
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, boundChatType, timestamp) -> {
            String text = message.getString();
            if (sender == null) return; // disguised/profileless chat has no backing GameProfile
            String senderName = sender.name();

            var player = Minecraft.getInstance().player;
            if (player != null && senderName.equals(player.getGameProfile().name())) return; // ignore our own messages

            boolean mentioned = text.toLowerCase().contains(ArdorConfig.get().wakeWord.toLowerCase());
            boolean cooledDown = Duration.between(lastTrigger, Instant.now()).compareTo(COOLDOWN) > 0;
            boolean trigger = mentioned && cooledDown;

            ChatHistory.logChat(senderName, text, trigger);

            if (trigger) {
                lastTrigger = Instant.now();
                respond(senderName, text);
            }
        });
    }

    private static void respond(String senderName, String text) {
        ArdorConfig config = ArdorConfig.get();
        if (config.needsApiKey() && config.llmApiKey.isBlank()) return; // not configured -- stay silent
        StatusIndicator.show("Thinking...");
        // single_command's fine-tuned model only ever saw clean commands during training
        // (see training/build_sft_dataset.py) -- no "name says:" framing, no wake word in
        // the text. say_do's frozen general model has no such training and benefits from
        // the extra context instead.
        String userMessage = "single_command".equals(config.llmMode)
                ? stripWakeWord(text, config.wakeWord)
                : senderName + " says: " + text;
        new ChatCompletionClient(config.effectiveBaseUrl(), config.llmApiKey, config.effectiveModel(), config.llmReasoningEffort)
                .complete(ResponseHandler.systemPromptFor(config.llmMode), userMessage)
                .thenAccept(response -> ResponseHandler.handle(response, config.llmMode))
                .exceptionally(err -> {
                    System.err.println("[ardor] chat response failed: " + err.getMessage());
                    Minecraft.getInstance().execute(() -> StatusIndicator.show("Error: " + err.getCause().getMessage()));
                    return null;
                });
    }

    private static String stripWakeWord(String text, String wakeWord) {
        return text.replaceAll("(?i)" + Pattern.quote(wakeWord), "").trim();
    }
}
