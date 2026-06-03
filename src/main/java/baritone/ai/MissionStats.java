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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lightweight per-mission telemetry: counts tool calls, errors, and wall-clock time, then formats a
 * one-line end-of-mission report. Pure data + formatting (no Minecraft or threading concerns), so it
 * is fully unit-testable.
 */
public final class MissionStats {

    private final long startedAtMillis;
    private final Map<String, Counts> perTool = new LinkedHashMap<>();
    private int totalCalls;
    private int totalErrors;

    public MissionStats(long startedAtMillis) {
        this.startedAtMillis = startedAtMillis;
    }

    /** Records a single tool invocation and whether its result was an error. */
    public void record(String toolName, boolean error) {
        String name = toolName == null || toolName.isBlank() ? "unknown" : toolName.trim();
        Counts counts = perTool.computeIfAbsent(name, k -> new Counts());
        counts.calls++;
        if (error) {
            counts.errors++;
            totalErrors++;
        }
        totalCalls++;
    }

    public int totalCalls() {
        return totalCalls;
    }

    public int totalErrors() {
        return totalErrors;
    }

    /** Renders a compact summary, e.g. {@code "12 tool calls, 2 errors, top: mine x4, craft x3, 47s"}. */
    public String report(long nowMillis) {
        long elapsedSeconds = Math.max(0L, (nowMillis - startedAtMillis) / 1000L);
        StringBuilder out = new StringBuilder();
        out.append(totalCalls).append(totalCalls == 1 ? " tool call" : " tool calls");
        if (totalErrors > 0) {
            out.append(", ").append(totalErrors).append(totalErrors == 1 ? " error" : " errors");
        }
        String top = topTools(3);
        if (!top.isEmpty()) {
            out.append(", top: ").append(top);
        }
        out.append(", ").append(elapsedSeconds).append("s");
        return out.toString();
    }

    private String topTools(int limit) {
        List<Map.Entry<String, Counts>> entries = new ArrayList<>(perTool.entrySet());
        // Stable sort by call count descending; LinkedHashMap insertion order breaks ties.
        entries.sort((a, b) -> Integer.compare(b.getValue().calls, a.getValue().calls));
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, Counts> entry : entries) {
            if (shown >= limit) {
                break;
            }
            if (shown > 0) {
                out.append(", ");
            }
            out.append(entry.getKey()).append(" x").append(entry.getValue().calls);
            shown++;
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return report(startedAtMillis).toLowerCase(Locale.ROOT);
    }

    private static final class Counts {
        int calls;
        int errors;
    }
}
