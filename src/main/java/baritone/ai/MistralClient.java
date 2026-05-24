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
 * Thin HTTP client for Mistral's chat completions endpoint. Implements only what the
 * Baritone AI agent needs: posting a chat completion with tool definitions and parsing
 * out the assistant message including any {@code tool_calls}.
 *
 * <p>This is intentionally dependency-free beyond Gson (already on Baritone's
 * classpath) and the JDK's built-in {@link HttpClient}.</p>
 */
public final class MistralClient {

    private static final Gson GSON = new Gson();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMinutes(30))
            .version(HttpClient.Version.HTTP_2)
            .build();

    private final String endpoint;
    private final String apiKey;

    public MistralClient(String endpoint, String apiKey) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
    }

    /**
     * POST a chat completion request and return the assistant's response message
     * (its content + any tool calls). Throws on transport or HTTP error so the caller
     * can surface a useful message to the player.
     *
     * @param model       Mistral model name (e.g. {@code mistral-large-latest}).
     * @param messages    Conversation so far, as a JsonArray of message objects.
     * @param tools       Tool/function schemas, as a JsonArray. May be empty.
     * @param temperature Sampling temperature.
     * @param maxTokens   Max response tokens.
     */
    public AssistantMessage chat(String model,
                                 JsonArray messages,
                                 JsonArray tools,
                                 double temperature,
                                 int maxTokens) throws IOException, InterruptedException {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IOException("Mistral API key is not set. Use `mistral key <KEY>` first.");
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

        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofHours(6))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            String snippet = resp.body() == null ? "" : resp.body();
            if (snippet.length() > 500) snippet = snippet.substring(0, 500) + "...";
            throw new IOException("Mistral API HTTP " + resp.statusCode() + ": " + snippet);
        }

        JsonObject root;
        try {
            root = JsonParser.parseString(resp.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Could not parse Mistral response as JSON: " + e.getMessage(), e);
        }

        if (!root.has("choices") || root.getAsJsonArray("choices").size() == 0) {
            throw new IOException("Mistral response had no choices: " + truncate(resp.body(), 300));
        }
        JsonObject choice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
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

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    /** Container for parsed assistant message. */
    public static final class AssistantMessage {
        /** Raw {@code message} object as returned by the API; safe to append to history. */
        public JsonObject raw;
        /** Plain-text content (may be empty/null). */
        public String content;
        /** Array of tool_call objects (empty if none). */
        public JsonArray toolCalls;
        /** {@code stop}, {@code tool_calls}, {@code length}, etc. */
        public String finishReason;
    }
}
