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

import baritone.ai.MistralAgent;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.utils.Helper;
import baritone.ai.GoalTracker;
import net.minecraft.ChatFormatting;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Natural-language goal command. Spawns a Mistral-backed agent on a background
 * thread that will plan and execute Baritone primitives until the goal is met, you run {@code ai stop},
 * or the model calls {@code done}. By default there is no round limit ({@code mistralMaxIterations} 0).
 *
 * <p>Run {@code #ai stop} to cancel a running agent.</p>
 */
public class AiCommand extends Command {

    public AiCommand(IBaritone baritone) {
        super(baritone, "ai");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMin(1);
        String first = args.peekString().toLowerCase(Locale.ROOT);
        if (first.equals("stop") || first.equals("cancel")) {
            args.getString();
            MistralAgent active = MistralAgent.ACTIVE.get();
            if (active == null) {
                logDirect("No AI agent is currently running.", ChatFormatting.YELLOW);
                return;
            }
            active.cancel();
            GoalTracker.fail("Cancellation requested");
            logDirect("Cancellation requested. The agent will stop between rounds.", ChatFormatting.YELLOW);
            return;
        }

        String goal = args.rawRest().trim();
        if (goal.isEmpty()) {
            logDirect("Usage: ai <natural language goal>", ChatFormatting.RED);
            return;
        }

        startAgent(baritone, goal, false, this, "ai");
    }

    public static void startAgent(IBaritone baritone, String goal, boolean planMode, Helper logger, String label) {
        String cleanGoal = goal == null ? "" : goal.trim();
        if (cleanGoal.isEmpty()) {
            logger.logDirect("Usage: " + label + " <natural language goal>", ChatFormatting.RED);
            return;
        }

        if (MistralAgent.ACTIVE.get() != null) {
            logger.logDirect("Another AI agent is already running. Run `ai stop` first.",
                    ChatFormatting.YELLOW);
            return;
        }

        GoalTracker.start(cleanGoal, planMode);
        GoalTracker.setStatus("Checking provider");

        Settings settings = BaritoneAPI.getSettings();
        String provider = settings.aiProvider.value == null ? "mistral" : settings.aiProvider.value.trim().toLowerCase(Locale.ROOT);
        if ("ollama".equals(provider)) {
            String model = settings.ollamaModel.value;
            if (model == null || model.trim().isEmpty()) {
                GoalTracker.fail("Ollama model is not set");
                logger.logDirect("Ollama model is not set. Run: "
                                + settings.prefix.value + "ollama list, then " + settings.prefix.value + "ollama use <number-or-name>",
                        ChatFormatting.RED);
                return;
            }
        } else {
            String key = settings.mistralApiKey.value;
            if (key == null || key.isEmpty()) {
                GoalTracker.fail("Mistral API key is not set");
                logger.logDirect("Mistral API key is not set. Run: "
                                + settings.prefix.value + "mistral key <YOUR_KEY>, or use "
                                + settings.prefix.value + "ollama use <model>",
                        ChatFormatting.RED);
                return;
            }
        }

        MistralAgent agent = new MistralAgent(baritone, planMode);
        if (!MistralAgent.ACTIVE.compareAndSet(null, agent)) {
            GoalTracker.fail("Another AI agent just started");
            logger.logDirect("Another AI agent just started; aborting this one.", ChatFormatting.YELLOW);
            return;
        }

        Thread t = new Thread(() -> {
            try {
                agent.runGoal(cleanGoal);
            } catch (Throwable th) {
                logger.logDirect("AI agent crashed: " + th.getClass().getSimpleName()
                        + ": " + th.getMessage(), ChatFormatting.RED);
                GoalTracker.fail("Agent crashed: " + th.getClass().getSimpleName());
            } finally {
                MistralAgent.ACTIVE.compareAndSet(agent, null);
            }
        }, "baritone-ai-agent");
        t.setDaemon(true);
        t.start();
        logger.logDirect("[AI] queued " + label + ": " + cleanGoal, ChatFormatting.AQUA);
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Run a natural-language goal via Mistral AI";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Hands a natural-language goal to a Mistral-backed planning agent, which then drives",
                "Baritone primitives (goto, mine, follow, build, etc.) to accomplish it.",
                "",
                "Requires a Mistral API key. Set it with: mistral key <YOUR_KEY>",
                "",
                "Examples:",
                "> ai go mine 16 diamonds and come back",
                "> ai follow Player1 until they stop",
                "> ai build a small dirt hut next to me",
                "> ai stop                  - cancel the current agent (also stops unlimited waits)",
                "",
                "Tip: `undercover on` before long sessions for gentler movement; set allowInventory true for crafting.",
                "The agent runs in the background; you'll see [AI:call] and [AI:result] log lines as",
                "it works (toggle with the mistralVerbose setting)."
        );
    }
}
