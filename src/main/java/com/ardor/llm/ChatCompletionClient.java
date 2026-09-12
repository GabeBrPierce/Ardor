package com.ardor.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal OpenAI-compatible chat completions client (pure JDK HttpClient +
 * Gson) -- works against Groq or any local OpenAI-compatible server
 * (llama.cpp server, Ollama, llama-swap), since they all share this request
 * shape. Renamed from the original GroqClient once it became clear this same
 * client would need to target a future local fine-tuned-model server too.
 *
 * baseUrl is a BASE (e.g. "https://api.groq.com/openai/v1" or
 * "http://127.0.0.1:8081/v1") with "/chat/completions" appended here, not a
 * complete endpoint URL -- matches training/README.md's documented
 * convention for pointing at the local fine-tuned model's llama-server.
 *
 * reasoningEffort exists because Groq's current default model line
 * (openai/gpt-oss-*) are reasoning models: verified live that without an
 * explicit low effort, a tight max_tokens budget can get entirely consumed
 * by hidden reasoning before any real content is emitted, returning an empty
 * string -- a real bug risk for a voice assistant expecting a short SAY:/DO:
 * or single-command reply, not just a latency concern. Pass null/blank to
 * omit the field for backends that don't support it.
 */
public final class ChatCompletionClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String reasoningEffort;

    public ChatCompletionClient(String baseUrl, String apiKey, String model, String reasoningEffort) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.reasoningEffort = reasoningEffort;
    }

    public CompletableFuture<String> complete(String systemPrompt, String userMessage) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            body.addProperty("reasoning_effort", reasoningEffort);
        }
        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userMessage));
        body.add("messages", messages);

        String url = (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl) + "/chat/completions";
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (apiKey != null && !apiKey.isBlank()) request.header("Authorization", "Bearer " + apiKey);

        return HTTP.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(ChatCompletionClient::extractContent);
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    private static String extractContent(HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            throw new RuntimeException("Chat completion API error " + response.statusCode() + ": " + response.body());
        }
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        return json.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString();
    }
}
