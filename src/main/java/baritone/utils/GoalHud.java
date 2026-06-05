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

package baritone.utils;

import baritone.ai.AgentTelemetry;
import baritone.ai.GoalTracker;
import baritone.ai.MissionQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GoalHud {

    /** Throttle so the position telemetry stream stays at most one event per second. */
    private static volatile long lastPositionEmit = 0L;

    private GoalHud() {}

    public static void render(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) {
            return;
        }
        GoalTracker.Snapshot snap = GoalTracker.snapshot();
        if (!snap.visible) {
            return;
        }
        // Position telemetry rides the HUD tick (player is guaranteed present here) but only while an
        // AI goal is actually active, throttled to once per second so the launcher's event stream stays light.
        if (snap.active) {
            emitPositionThrottled(mc);
        }
        if (snap.shouldAutoHide(System.currentTimeMillis(), 15_000L)) {
            GoalTracker.hide();
            return;
        }

        Font font = mc.font;
        int maxWidth = Math.min(290, Math.max(180, graphics.guiWidth() / 3));
        int x = Math.max(6, graphics.guiWidth() - maxWidth - 72);
        int y = Math.max(22, graphics.guiHeight() / 12);

        List<String> lines = new ArrayList<>();
        boolean idle = !snap.active && snap.goal.isEmpty();
        MissionQueue.Snapshot queue = MissionQueue.snapshot();
        String title = (idle ? "AI GOAL IDLE" : (snap.active ? "AI GOAL" : "AI GOAL DONE"))
                + (snap.planMode ? " PLAN" : "");
        if (queue.paused) {
            title += " PAUSED";
        }
        if (!queue.pending.isEmpty()) {
            title += " Q:" + queue.pending.size();
        }
        lines.add(title);
        if (!snap.status.isEmpty()) {
            lines.add(shorten(snap.status, font, maxWidth - 12));
        }
        if (queue.paused) {
            lines.add("Queue paused");
        }
        if (!queue.pending.isEmpty()) {
            MissionQueue.Mission next = queue.pending.get(0);
            lines.add(shorten("Next #" + next.id + ": " + next.goal, font, maxWidth - 12));
        }
        if (!snap.goal.isEmpty()) {
            lines.add(shorten(snap.goal, font, maxWidth - 12));
        }
        int shown = Math.min(8, snap.steps.size());
        for (int i = 0; i < shown; i++) {
            GoalTracker.Step step = snap.steps.get(i);
            lines.add((i + 1) + ". " + (step.done ? "[x] " : "[ ] ")
                    + shorten(step.text, font, maxWidth - 26));
        }
        if (snap.steps.size() > shown) {
            lines.add("... " + (snap.steps.size() - shown) + " more");
        }

        int lineH = 11;
        int height = lines.size() * lineH + 9;
        graphics.nextStratum();
        graphics.fill(x - 5, y - 5, x + maxWidth + 5, y + height, 0xD8080A0D);
        graphics.fill(x - 5, y - 5, x + maxWidth + 5, y - 2, snap.active ? 0xEE36CCDC : 0xEE66AA66);
        for (int i = 0; i < lines.size(); i++) {
            // Text colors MUST carry an explicit alpha channel: since the 1.21.6 GUI rewrite,
            // Minecraft no longer promotes zero-alpha colors to opaque, so 0x36CCDC would render
            // as fully transparent (invisible) text while the alpha'd fills still show.
            int color;
            if (i == 0) {
                color = snap.active ? 0xFF36CCDC : 0xFFA8E6A3;
            } else if (lines.get(i).contains("[x]")) {
                color = 0xFF88D08A;
            } else {
                color = 0xFFE6E6E6;
            }
            graphics.drawString(font, lines.get(i), x, y + i * lineH, color, true);
        }
    }

    private static void emitPositionThrottled(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (now - lastPositionEmit < 1000L) {
            return;
        }
        lastPositionEmit = now;
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("x", mc.player.getBlockX());
            data.put("y", mc.player.getBlockY());
            data.put("z", mc.player.getBlockZ());
            String dim = "";
            try {
                dim = mc.player.level().dimension().identifier().toString();
            } catch (RuntimeException ignored) {
            }
            data.put("dim", dim);
            AgentTelemetry.emit("position", data);
        } catch (RuntimeException ignored) {
            // Telemetry must never disrupt rendering.
        }
    }

    private static String shorten(String text, Font font, int width) {
        if (text == null) {
            return "";
        }
        String s = text.trim();
        if (font.width(s) <= width) {
            return s;
        }
        while (s.length() > 4 && font.width(s + "...") > width) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "...";
    }
}
