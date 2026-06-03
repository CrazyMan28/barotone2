/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Minimal dependency-free client for OpenAI-compatible chat completions APIs.
 * Mistral and Ollama both accept the message/tools shape this agent uses.
 */
public final class OpenAiChatClient {

    private static final Gson GSON = new Gson();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private static final long MAX_BACKOFF_MILLIS = 30_000L;

    private final String endpoint;
    private final String apiKey;
    private final String providerName;
    private final int maxRetries;
    private final long retryBackoffMillis;
    private final Duration requestTimeout;

    public OpenAiChatClient(String endpoint, String apiKey, String providerName) {
        this(endpoint, apiKey, providerName, 3, 1500L, 120);
    }

    public OpenAiChatClient(String endpoint, String apiKey, String providerName,
                            int maxRetries, long retryBackoffMillis, int requestTimeoutSeconds) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.providerName = providerName == null || providerName.isBlank() ? "AI" : providerName;
        this.maxRetries = Math.max(0, maxRetries);
        this.retryBackoffMillis = Math.max(0L, retryBackoffMillis);
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds <= 0 ? 120 : requestTimeoutSeconds);
    }

    /** Retry transient transport failures (HTTP 429 rate limit and any 5xx server error). */
    static boolean shouldRetry(int statusCode) {
        return statusCode == 429 || (statusCode >= 500 && statusCode <= 599);
    }

    /** Exponential backoff (base, 2x, 4x, ...) capped at {@link #MAX_BACKOFF_MILLIS}. */
    static long backoffMillis(int attempt, long base) {
        if (base <= 0L || attempt < 0) {
            return Math.max(0L, base);
        }
        long shift = Math.min(attempt, 20); // guard against overflow on absurd attempt counts
        long delay = base << shift;
        if (delay <= 0L || delay > MAX_BACKOFF_MILLIS) {
            return MAX_BACKOFF_MILLIS;
        }
        return delay;
    }

    public AssistantMessage chat(String model,
                                 JsonArray messages,
                                 JsonArray tools,
                                 double temperature,
                                 int maxTokens) throws IOException, InterruptedException {
        if (model == null || model.isBlank()) {
            throw new IOException(providerName + " model is not set.");
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", temperature);
        body.addProperty("max_tokens", maxTokens);
        body.add("messages", messages);
        if (tools != null && tools.size() > 0) {
            body.add("tools", tools);
            body.addProperty("tool_choice", "auto");
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));
        if (apiKey != null && !apiKey.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }
        HttpRequest request = requestBuilder.build();

        HttpResponse<String> resp = sendWithRetries(request);

        JsonObject root;
        try {
            root = JsonParser.parseString(resp.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Could not parse " + providerName + " response as JSON: " + e.getMessage(), e);
        }

        if (!root.has("choices") || !root.get("choices").isJsonArray() || root.getAsJsonArray("choices").size() == 0) {
            throw new IOException(providerName + " response had no choices: " + truncate(resp.body(), 300));
        }
        JsonObject choice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
        if (!choice.has("message") || !choice.get("message").isJsonObject()) {
            throw new IOException(providerName + " response choice had no message: " + truncate(resp.body(), 300));
        }
        JsonObject msg = choice.getAsJsonObject("message");

        AssistantMessage out = new AssistantMessage();
        out.raw = msg;
        if (msg.has("content") && !msg.get("content").isJsonNull()) {
            JsonElement c = msg.get("content");
            out.content = c.isJsonPrimitive() ? c.getAsString() : c.toString();
        }
        if (msg.has("tool_calls") && msg.get("tool_calls").isJsonArray()) {
            out.toolCalls = msg.getAsJsonArray("tool_calls");
        } else {
            out.toolCalls = new JsonArray();
        }
        if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) {
            out.finishReason = choice.get("finish_reason").getAsString();
        }
        return out;
    }

    private HttpResponse<String> sendWithRetries(HttpRequest request) throws IOException, InterruptedException {
        IOException lastNetworkError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException(providerName + " request cancelled");
            }
            try {
                HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = resp.statusCode();
                if (status / 100 == 2) {
                    return resp;
                }
                if (attempt < maxRetries && shouldRetry(status)) {
                    sleepBeforeRetry(retryAfterMillis(resp).orElse(backoffMillis(attempt, retryBackoffMillis)));
                    continue;
                }
                throw new IOException(providerName + " API HTTP " + status + ": " + truncate(resp.body(), 500));
            } catch (IOException e) {
                lastNetworkError = e;
                if (attempt < maxRetries) {
                    sleepBeforeRetry(backoffMillis(attempt, retryBackoffMillis));
                    continue;
                }
                throw e;
            }
        }
        // Unreachable in practice; the loop always returns or throws on its last attempt.
        throw lastNetworkError != null ? lastNetworkError
                : new IOException(providerName + " request failed with no response");
    }

    private void sleepBeforeRetry(long millis) throws InterruptedException {
        if (millis > 0L) {
            Thread.sleep(millis);
        }
    }

    private static java.util.Optional<Long> retryAfterMillis(HttpResponse<String> resp) {
        return resp.headers().firstValue("Retry-After").map(value -> {
            try {
                // Only the delta-seconds form is honored; HTTP-date form falls back to exponential backoff.
                long seconds = Long.parseLong(value.trim());
                return seconds < 0L ? null : Math.min(MAX_BACKOFF_MILLIS, seconds * 1000L);
            } catch (NumberFormatException e) {
                return null;
            }
        });
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    public static final class AssistantMessage {
        public JsonObject raw;
        public String content;
        public JsonArray toolCalls;
        public String finishReason;
    }
}
