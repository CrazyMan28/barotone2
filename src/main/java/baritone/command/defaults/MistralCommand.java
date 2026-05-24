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

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Configures Baritone's Mistral AI integration without leaking the API key into
 * the chat scrollback. Provides simple subcommands for key/model/endpoint and
 * a status readout.
 */
public class MistralCommand extends Command {

    private static final List<String> SUBS = Arrays.asList("key", "status", "list", "model", "use", "sue", "endpoint", "reset");
    private static final List<String> MODELS = Arrays.asList(
            "mistral-large-latest",
            "mistral-medium-latest",
            "mistral-small-latest",
            "codestral-latest",
            "ministral-8b-latest",
            "ministral-3b-latest"
    );

    public MistralCommand(IBaritone baritone) {
        super(baritone, "mistral");
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
            case "key":
                args.requireMin(1);
                String key = args.rawRest().trim();
                settings.mistralApiKey.value = key;
                SettingsUtil.save(settings);
                if (key.isEmpty()) {
                    logDirect("Cleared Mistral API key.");
                } else {
                    logDirect("Mistral API key set (length=" + key.length() + "). Stored in baritone settings file.");
                }
                break;
            case "list":
                listModels(settings);
                break;
            case "model":
                args.requireMin(1);
                setModel(settings, args.rawRest().trim(), false);
                break;
            case "use":
            case "sue":
                args.requireMin(1);
                setModel(settings, args.rawRest().trim(), true);
                break;
            case "endpoint":
                args.requireMin(1);
                settings.mistralEndpoint.value = args.rawRest().trim();
                SettingsUtil.save(settings);
                logDirect("Mistral endpoint = " + settings.mistralEndpoint.value);
                break;
            case "reset":
                settings.mistralModel.reset();
                settings.mistralEndpoint.reset();
                settings.mistralMaxIterations.reset();
                settings.mistralTemperature.reset();
                settings.mistralMaxTokens.reset();
                settings.mistralVerbose.reset();
                SettingsUtil.save(settings);
                logDirect("Reset Mistral settings (api key preserved).");
                break;
            case "status":
            default:
                showStatus(settings);
        }
    }

    private void showStatus(Settings settings) {
        String key = settings.mistralApiKey.value;
        logDirect("Mistral configuration:");
        logDirect("  aiProvider = " + settings.aiProvider.value);
        logDirect("  key      = " + (key == null || key.isEmpty()
                ? "<not set>"
                : maskKey(key)));
        logDirect("  model    = " + settings.mistralModel.value);
        logDirect("  endpoint = " + settings.mistralEndpoint.value);
        logDirect("  temp     = " + settings.mistralTemperature.value
                + ", maxTokens = " + settings.mistralMaxTokens.value
                + ", maxRounds = " + (settings.mistralMaxIterations.value <= 0
                        ? "0 (unlimited)" : String.valueOf(settings.mistralMaxIterations.value))
                + ", verbose = " + settings.mistralVerbose.value);
        logDirect("  picker   = run `mistral list`, then `mistral use <number>` to switch #ai to Mistral.");
    }

    private void listModels(Settings settings) {
        logDirect("Mistral model picker:");
        for (int i = 0; i < MODELS.size(); i++) {
            String marker = MODELS.get(i).equals(settings.mistralModel.value) ? " *" : "";
            logDirect("  " + (i + 1) + ". " + MODELS.get(i) + marker);
        }
        logDirect("Use `mistral use <number>` to select and switch AI provider to Mistral.");
    }

    private void setModel(Settings settings, String raw, boolean switchProvider) {
        String model = resolveModel(raw);
        if (model.isBlank()) {
            logDirect("No Mistral model selected. Run `mistral list`, or pass an exact model name.");
            return;
        }
        settings.mistralModel.value = model;
        if (switchProvider) {
            settings.aiProvider.value = "mistral";
        }
        SettingsUtil.save(settings);
        logDirect((switchProvider ? "Using Mistral model " : "Mistral model = ") + model
                + (switchProvider ? " for #ai." : "."));
    }

    private static String resolveModel(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            return "";
        }
        try {
            int index = Integer.parseInt(s);
            if (index >= 1 && index <= MODELS.size()) {
                return MODELS.get(index - 1);
            }
            return "";
        } catch (NumberFormatException ignored) {
            return s;
        }
    }

    private static String maskKey(String key) {
        if (key.length() <= 8) return "********";
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4) + " (len=" + key.length() + ")";
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper().append(SUBS.stream()).filterPrefix(args.getString()).stream();
        }
        if (args.hasExactly(2) && ("model".equalsIgnoreCase(args.peekString(0))
                || "use".equalsIgnoreCase(args.peekString(0))
                || "sue".equalsIgnoreCase(args.peekString(0)))) {
            return new TabCompleteHelper().append(MODELS.stream()).filterPrefix(args.getString()).stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Configure Mistral AI key/model used by the `ai` command";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Configure the Mistral AI integration used by the `ai` command.",
                "Your API key is stored in your Baritone settings file (not transmitted anywhere except to Mistral).",
                "",
                "Usage:",
                "> mistral                       - Show current Mistral configuration",
                "> mistral status                - Same as above",
                "> mistral list                  - Show common Mistral model picker",
                "> mistral key <YOUR_API_KEY>    - Set the Mistral API key",
                "> mistral model <name-or-number> - Set the model only",
                "> mistral use <name-or-number>  - Set model and switch aiProvider to mistral",
                "> mistral endpoint <URL>        - Override the chat-completions endpoint",
                "> mistral reset                 - Reset all Mistral settings except the API key",
                "",
                "Other Mistral knobs live as normal Baritone settings:",
                "  mistralTemperature, mistralMaxTokens, mistralMaxIterations (0 = unlimited),",
                "  mistralVerbose"
        );
    }
}
