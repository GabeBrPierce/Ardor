package com.ardor.llm;

/**
 * Presets for ChatCompletionClient's baseUrl/model, covering the OpenAI-compatible chat/completions
 * shape (Groq, OpenAI itself, OpenRouter, Together AI, and a local llama.cpp/llama-server all speak
 * it). Anthropic's native API is a different request/response shape and isn't covered here -- see
 * TODO.md.
 *
 * ArdorConfig.llmBaseUrl/llmModel take precedence when non-blank; a provider only supplies the
 * default for whichever of those two the user has left blank (ArdorConfig.effectiveBaseUrl()/
 * effectiveModel()).
 */
public enum LlmProvider {
    LOCAL("Local (llama-server)", "http://127.0.0.1:8081/v1", "qwen2.5-3b-instruct", false),
    GROQ("Groq", "https://api.groq.com/openai/v1", "openai/gpt-oss-20b", true),
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", true),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini", true),
    TOGETHER("Together AI", "https://api.together.xyz/v1", "meta-llama/Llama-3.3-70B-Instruct-Turbo", true),
    CUSTOM("Custom", "", "", true);

    public final String displayName;
    public final String defaultBaseUrl;
    public final String defaultModel;
    public final boolean needsApiKey;

    LlmProvider(String displayName, String defaultBaseUrl, String defaultModel, boolean needsApiKey) {
        this.displayName = displayName;
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultModel = defaultModel;
        this.needsApiKey = needsApiKey;
    }

    public static LlmProvider fromConfigValue(String value) {
        for (LlmProvider p : values()) {
            if (p.name().equalsIgnoreCase(value)) return p;
        }
        return LOCAL;
    }
}
