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

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.util.Locale;
import java.util.Map;

/**
 * Captures the agent's Minecraft window to a PNG so the launcher/phone can "check in" on the bot.
 *
 * <p>Captures the ALREADY-RENDERED main framebuffer — it never opens a Screen, so it doesn't intrude
 * on or interrupt Baritone. The grab must run on the render thread, so we schedule it via
 * {@code Minecraft.execute}. The file lands in {@code <gameDir>/screenshots/agent_<reason>_<ts>.png}
 * (the standard screenshots dir the launcher already polls). A {@code status} telemetry breadcrumb is
 * emitted so the launcher knows a fresh file landed (reusing the existing [AI:EVT] 'status' kind — no
 * new event kind, so the launcher/phone parsers don't need changes; and no new gson POJO, so no
 * ProGuard keep-rule is needed). Screenshot failure NEVER throws into the caller — it must not be able
 * to fail a mission.
 */
public final class ScreenshotHelper {

    private ScreenshotHelper() {
    }

    /**
     * @param reason short tag for the filename/telemetry, e.g. "manual", "remote", "mission_done"
     * @return the screenshot filename (under {@code screenshots/}), or null if not in a live game
     */
    public static String capture(String reason) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getMainRenderTarget() == null || mc.gameDirectory == null) {
                return null;
            }
            String safe = sanitizeReason(reason);
            String filename = "agent_" + safe + "_" + System.currentTimeMillis() + ".png";
            mc.execute(() -> {
                try {
                    // 1.21.11 mojmap: grab(File gameDir, String name, RenderTarget fb, int downScale, Consumer<Component>)
                    // writes to <gameDir>/screenshots/<name>. downScale=1 -> full resolution. Reads the
                    // current main render target -> no GUI opened, Baritone keeps running.
                    Screenshot.grab(mc.gameDirectory, filename, mc.getMainRenderTarget(), 1, msg -> { });
                } catch (RuntimeException | LinkageError e) {
                    // a screenshot must never break a mission — swallow render-side failures
                }
            });
            AgentTelemetry.emit("status", Map.of("event", "screenshot", "file", filename, "reason", safe));
            return filename;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Lowercase, filename-safe, length-capped reason tag (defaults to "manual"). */
    static String sanitizeReason(String reason) {
        if (reason == null || reason.isEmpty()) {
            return "manual";
        }
        String s = reason.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        return s.length() > 24 ? s.substring(0, 24) : s;
    }
}
