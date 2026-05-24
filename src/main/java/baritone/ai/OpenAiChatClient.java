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

    private final String endpoint;
    private final String apiKey;
    private final String providerName;

    public OpenAiChatClient(String endpoint, String apiKey, String providerName) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.providerName = providerName == null || providerName.isBlank() ? "AI" : providerName;
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

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofHours(6))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));
        if (apiKey != null && !apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> resp = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new IOException(providerName + " API HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 500));
        }

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
