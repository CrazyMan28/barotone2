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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Locale;

/**
 * Protocol helpers for the fine-tuned "baritone-brain" command model. The brain answers a short
 * prompt (no tool schemas - they are baked into its weights during training) with a single
 * Qwen-style inline tool call:
 *
 * <pre>&lt;tool_call&gt;{"name": "mine", "arguments": {"blocks": ["minecraft:diamond_ore"]}}&lt;/tool_call&gt;</pre>
 *
 * Pure and static so it is unit-testable without Minecraft.
 */
public final class BrainProtocol {

    /** Ollama model-name prefix that activates the fast path. */
    public static final String MODEL_PREFIX = "baritone-brain";

    /** The trained pseudo-tool meaning "this is beyond me, send it to the big model". */
    public static final String ESCALATE = "escalate";

    /** The exact system prompt the model was trained with (see training/train.py). */
    public static final String SYSTEM_PROMPT =
            "You are baritone-brain, the command brain of a Minecraft Baritone bot. "
                    + "Convert the player's message into exactly one tool call. "
                    + "If the request is creative, multi-step, or beyond your tools, call escalate.";

    private BrainProtocol() {
    }

    public static boolean isBrainModel(String model) {
        return model != null && model.trim().toLowerCase(Locale.ROOT).startsWith(MODEL_PREFIX);
    }

    /**
     * True when the goal reads like a status QUESTION, where answering with get_state genuinely
     * completes the mission ("whats in your inventory"). For action goals ("get wood"), a one-shot
     * get_state answer means the brain was confused - callers should escalate instead of finishing
     * the mission with a pointless pocket-check (observed in-game with "get wood").
     */
    public static boolean looksInformational(String goal) {
        if (goal == null) {
            return false;
        }
        String g = goal.toLowerCase(Locale.ROOT);
        return g.contains("?") || g.contains("what") || g.contains("how") || g.contains("where")
                || g.contains("status") || g.contains("inventory") || g.contains("show me")
                || g.contains("do you have") || g.contains("you got") || g.contains("report");
    }

    /** A tool call extracted from the brain's reply. */
    public static final class Call {
        public final String name;
        public final JsonObject arguments;

        Call(String name, JsonObject arguments) {
            this.name = name;
            this.arguments = arguments;
        }

        public boolean isEscalate() {
            return ESCALATE.equalsIgnoreCase(name);
        }
    }

    /**
     * Extract the single tool call from the brain's raw reply text. Tolerates the leading empty
     * think block, surrounding chatter, and a bare JSON object without the tags.
     *
     * @return the call, or {@code null} when no valid tool call is present (callers escalate then)
     */
    public static Call extractToolCall(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String text = content;
        int thinkEnd = text.lastIndexOf("</think>");
        if (thinkEnd >= 0) {
            text = text.substring(thinkEnd + "</think>".length());
        }
        String json = null;
        int open = text.indexOf("<tool_call>");
        if (open >= 0) {
            int close = text.indexOf("</tool_call>", open);
            json = (close > open)
                    ? text.substring(open + "<tool_call>".length(), close)
                    : text.substring(open + "<tool_call>".length());
        } else {
            int brace = text.indexOf('{');
            if (brace >= 0) {
                json = text.substring(brace);
            }
        }
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonElement el = JsonParser.parseString(json.trim());
            if (!el.isJsonObject()) {
                return null;
            }
            JsonObject obj = el.getAsJsonObject();
            if (!obj.has("name") || obj.get("name").isJsonNull()) {
                return null;
            }
            String name = obj.get("name").getAsString().trim();
            if (name.isEmpty()) {
                return null;
            }
            JsonObject args = obj.has("arguments") && obj.get("arguments").isJsonObject()
                    ? obj.getAsJsonObject("arguments")
                    : new JsonObject();
            return new Call(name, args);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
