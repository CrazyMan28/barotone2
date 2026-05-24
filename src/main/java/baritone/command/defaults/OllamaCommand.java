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

package baritone.command.defaults;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.api.utils.SettingsUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class OllamaCommand extends Command {

    private static final List<String> SUBS = Arrays.asList("status", "list", "model", "use", "endpoint", "reset");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private static List<String> lastListedModels = Collections.emptyList();

    public OllamaCommand(IBaritone baritone) {
        super(baritone, "ollama", "olama", "olamam");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        Settings settings = BaritoneAPI.getSettings();
        if (!args.hasAny()) {
            showStatus(settings);
            return;
        }

        String sub = args.getString().toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status":
                showStatus(settings);
                break;
            case "list":
                listModels(settings);
                break;
            case "model":
                args.requireMin(1);
                setModel(settings, args.rawRest().trim(), false);
                break;
            case "use":
                args.requireMin(1);
                setModel(settings, args.rawRest().trim(), true);
                break;
            case "endpoint":
                args.requireMin(1);
                settings.ollamaBaseUrl.value = trimTrailingSlash(args.rawRest().trim());
                SettingsUtil.save(settings);
                logDirect("Ollama endpoint = " + settings.ollamaBaseUrl.value);
                break;
            case "reset":
                settings.ollamaBaseUrl.reset();
                settings.ollamaModel.reset();
                SettingsUtil.save(settings);
                lastListedModels = Collections.emptyList();
                logDirect("Reset Ollama endpoint/model. AI provider is still " + settings.aiProvider.value + ".");
                break;
            default:
                logDirect("Usage: ollama status | list | model <name-or-number> | use <name-or-number> | endpoint <url> | reset",
                        ChatFormatting.RED);
        }
    }

    private void showStatus(Settings settings) {
        logDirect("Ollama configuration:");
        logDirect("  provider = " + settings.aiProvider.value);
        logDirect("  endpoint = " + settings.ollamaBaseUrl.value);
        logDirect("  model    = " + (settings.ollamaModel.value == null || settings.ollamaModel.value.isBlank()
                ? "<not set>" : settings.ollamaModel.value));
        logDirect("  picker   = " + (lastListedModels.isEmpty()
                ? "no cached list; run `ollama list`" : lastListedModels.size() + " model(s) cached"));
    }

    private void listModels(Settings settings) {
        List<String> models;
        try {
            models = fetchModels(settings.ollamaBaseUrl.value);
        } catch (Exception e) {
            logDirect("Could not list Ollama models: " + e.getMessage(), ChatFormatting.RED);
            return;
        }
        lastListedModels = models;
        if (models.isEmpty()) {
            logDirect("Ollama returned no local models. Run `ollama pull <model>` outside Minecraft.", ChatFormatting.YELLOW);
            return;
        }
        logDirect("Ollama local models:");
        for (int i = 0; i < models.size(); i++) {
            String marker = models.get(i).equals(settings.ollamaModel.value) ? " *" : "";
            logDirect("  " + (i + 1) + ". " + models.get(i) + marker);
        }
        logDirect("Use `ollama use <number>` to select and switch AI provider to Ollama.");
    }

    private void setModel(Settings settings, String raw, boolean switchProvider) {
        String model = resolveModel(raw);
        if (model == null || model.isBlank()) {
            logDirect("No model selected. Run `ollama list` first, or pass an exact model name.", ChatFormatting.RED);
            return;
        }
        settings.ollamaModel.value = model;
        if (switchProvider) {
            settings.aiProvider.value = "ollama";
        }
        SettingsUtil.save(settings);
        logDirect((switchProvider ? "Using Ollama model " : "Ollama model = ") + model
                + (switchProvider ? " for #ai." : "."));
    }

    private static String resolveModel(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return "";
        }
        try {
            int index = Integer.parseInt(s);
            if (index >= 1 && index <= lastListedModels.size()) {
                return lastListedModels.get(index - 1);
            }
            return "";
        } catch (NumberFormatException ignored) {
            return s;
        }
    }

    private static List<String> fetchModels(String baseUrl) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(trimTrailingSlash(baseUrl) + "/api/tags"))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 300));
        }
        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        JsonArray arr = root.has("models") && root.get("models").isJsonArray()
                ? root.getAsJsonArray("models") : new JsonArray();
        List<String> out = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject obj = el.getAsJsonObject();
            String name = "";
            if (obj.has("model") && !obj.get("model").isJsonNull()) {
                name = obj.get("model").getAsString();
            } else if (obj.has("name") && !obj.get("name").isJsonNull()) {
                name = obj.get("name").getAsString();
            }
            if (!name.isBlank()) {
                out.add(name);
            }
        }
        return out;
    }

    private static String trimTrailingSlash(String raw) {
        String s = raw == null || raw.isBlank() ? "http://localhost:11434" : raw.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper().append(SUBS.stream()).filterPrefix(args.getString()).stream();
        }
        if (args.hasExactly(2) && ("model".equalsIgnoreCase(args.peekString(0)) || "use".equalsIgnoreCase(args.peekString(0)))) {
            return new TabCompleteHelper().append(lastListedModels.stream()).filterPrefix(args.getString()).stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Configure Ollama local AI provider/model";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Configure Ollama local model support for the `ai` command.",
                "",
                "Usage:",
                "> ollama status                 - Show endpoint/model/provider",
                "> ollama list                   - Fetch local models from /api/tags",
                "> ollama model <name-or-number> - Set Ollama model only",
                "> ollama use <name-or-number>   - Set model and switch aiProvider to ollama",
                "> ollama endpoint <URL>         - Set base URL, default http://localhost:11434",
                "> ollama reset                  - Reset Ollama endpoint/model"
        );
    }
}
