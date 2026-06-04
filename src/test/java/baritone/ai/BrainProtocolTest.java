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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BrainProtocolTest {

    @Test
    public void parsesTrainedFormatWithThinkBlock() {
        BrainProtocol.Call call = BrainProtocol.extractToolCall(
                "<think>\n\n</think>\n<tool_call>\n{\"name\": \"mine\", \"arguments\": {\"blocks\": [\"minecraft:diamond_ore\"]}}\n</tool_call>");
        assertEquals("mine", call.name);
        assertEquals("minecraft:diamond_ore",
                call.arguments.getAsJsonArray("blocks").get(0).getAsString());
        assertFalse(call.isEscalate());
    }

    @Test
    public void parsesBareJsonWithoutTags() {
        BrainProtocol.Call call = BrainProtocol.extractToolCall(
                "{\"name\": \"follow_player\", \"arguments\": {\"name\": \"keven\"}}");
        assertEquals("follow_player", call.name);
        assertEquals("keven", call.arguments.get("name").getAsString());
    }

    @Test
    public void parsesEscalate() {
        BrainProtocol.Call call = BrainProtocol.extractToolCall(
                "<tool_call>{\"name\": \"escalate\", \"arguments\": {\"reason\": \"complex\"}}</tool_call>");
        assertTrue(call.isEscalate());
    }

    @Test
    public void missingArgumentsBecomesEmptyObject() {
        BrainProtocol.Call call = BrainProtocol.extractToolCall(
                "<tool_call>{\"name\": \"get_state\"}</tool_call>");
        assertEquals("get_state", call.name);
        assertEquals(0, call.arguments.size());
    }

    @Test
    public void garbageReturnsNull() {
        assertNull(BrainProtocol.extractToolCall(null));
        assertNull(BrainProtocol.extractToolCall(""));
        assertNull(BrainProtocol.extractToolCall("sure! I'll mine diamonds for you"));
        assertNull(BrainProtocol.extractToolCall("<tool_call>not json</tool_call>"));
        assertNull(BrainProtocol.extractToolCall("<tool_call>{\"no_name\": true}</tool_call>"));
    }

    @Test
    public void informationalGoalsKeepGetStateAnswers() {
        assertTrue(BrainProtocol.looksInformational("whats in your inventory"));
        assertTrue(BrainProtocol.looksInformational("show me your stuff"));
        assertTrue(BrainProtocol.looksInformational("status report"));
        assertTrue(BrainProtocol.looksInformational("where are you?"));
        assertFalse(BrainProtocol.looksInformational("get wood"));
        assertFalse(BrainProtocol.looksInformational("mine diamonds"));
        assertFalse(BrainProtocol.looksInformational(null));
    }

    @Test
    public void brainModelDetection() {
        assertTrue(BrainProtocol.isBrainModel("baritone-brain"));
        assertTrue(BrainProtocol.isBrainModel("baritone-brain:latest"));
        assertTrue(BrainProtocol.isBrainModel("  Baritone-Brain-v2 "));
        assertFalse(BrainProtocol.isBrainModel("gemma4:e2b"));
        assertFalse(BrainProtocol.isBrainModel(null));
    }
}
