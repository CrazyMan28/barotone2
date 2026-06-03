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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class MistralAgentCompactionTest {

    private static JsonArray history(String... roles) {
        JsonArray arr = new JsonArray();
        for (int i = 0; i < roles.length; i++) {
            JsonObject m = new JsonObject();
            m.addProperty("role", roles[i]);
            m.addProperty("content", roles[i] + "-" + i);
            arr.add(m);
        }
        return arr;
    }

    private static String roleAt(JsonArray arr, int i) {
        return arr.get(i).getAsJsonObject().get("role").getAsString();
    }

    @Test
    public void returnsSameArrayWhenUnderLimit() {
        JsonArray h = history("system", "user", "assistant", "tool");
        JsonArray result = MistralAgent.compactHistory(h, 40, 16, "checkpoints");
        assertSame(h, result);
    }

    @Test
    public void disabledWhenMaxIsZero() {
        JsonArray h = history("system", "user", "assistant", "tool", "assistant", "tool");
        assertSame(h, MistralAgent.compactHistory(h, 0, 2, "x"));
    }

    @Test
    public void preservesHeaderAndRecentTailWithSummary() {
        JsonArray h = history("system", "user",
                "assistant", "tool", "assistant", "tool", "assistant", "tool", "assistant", "tool", "assistant", "tool");
        // 12 messages, cap 8, keep 4 recent.
        JsonArray result = MistralAgent.compactHistory(h, 8, 4, "last=mine:logs");

        assertTrue(result.size() < h.size());
        assertEquals("system", roleAt(result, 0));
        assertEquals("user", roleAt(result, 1));
        // The collapsed middle becomes one synthetic user message.
        assertEquals("user", roleAt(result, 2));
        assertTrue(result.get(2).getAsJsonObject().get("content").getAsString().contains("summarized"));
        assertTrue(result.get(2).getAsJsonObject().get("content").getAsString().contains("last=mine:logs"));
        // The original goal text is untouched.
        assertNotEquals(roleAt(result, 2), "system");
    }

    @Test
    public void keptBlockNeverStartsWithOrphanedToolResult() {
        // Arrange so that size - keepRecent lands on a tool message; the cut must skip forward past it.
        JsonArray h = history("system", "user",
                "assistant", "tool", "assistant", "tool", "assistant", "tool", "assistant", "tool");
        JsonArray result = MistralAgent.compactHistory(h, 5, 3, "cp");

        // Find the synthetic summary (first user message after the header) and assert what follows it.
        int summaryIndex = 2;
        assertEquals("user", roleAt(result, summaryIndex));
        assertTrue(result.get(summaryIndex).getAsJsonObject().get("content").getAsString().contains("summarized"));
        assertNotEquals("tool", roleAt(result, summaryIndex + 1));
    }
}
