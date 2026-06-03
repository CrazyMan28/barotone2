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

import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MistralAgentTest {

    @Test
    public void parseToolCallAcceptsJsonStringArguments() {
        MistralAgent.ParsedToolCall call = MistralAgent.parseToolCall(JsonParser.parseString(
                "{\"id\":\"call_1\",\"function\":{\"name\":\"say\",\"arguments\":\"{\\\"message\\\":\\\"hello\\\"}\"}}"));

        assertTrue(call.canRespondAsTool);
        assertNull(call.error);
        assertEquals("call_1", call.callId);
        assertEquals("say", call.functionName);
        assertEquals("hello", call.arguments.get("message").getAsString());
    }

    @Test
    public void parseToolCallReturnsToolErrorForMissingFunctionName() {
        MistralAgent.ParsedToolCall call = MistralAgent.parseToolCall(JsonParser.parseString(
                "{\"id\":\"call_2\",\"function\":{\"arguments\":{}}}"));

        assertTrue(call.canRespondAsTool);
        assertEquals("call_2", call.callId);
        assertEquals("invalid_tool_call", call.functionName);
        assertTrue(call.error.contains("missing name"));
    }

    @Test
    public void parseToolCallRejectsMissingIdAsUnanswerable() {
        MistralAgent.ParsedToolCall call = MistralAgent.parseToolCall(JsonParser.parseString(
                "{\"function\":{\"name\":\"get_state\",\"arguments\":{}}}"));

        assertFalse(call.canRespondAsTool);
        assertTrue(call.error.contains("missing id"));
    }

    @Test
    public void parseToolCallRejectsMalformedStringArguments() {
        MistralAgent.ParsedToolCall call = MistralAgent.parseToolCall(JsonParser.parseString(
                "{\"id\":\"call_3\",\"function\":{\"name\":\"mine\",\"arguments\":\"not json at all\"}}"));

        assertTrue(call.canRespondAsTool);
        assertEquals("call_3", call.callId);
        assertEquals("mine", call.functionName);
        assertNotNull(call.error);
        assertTrue(call.error.contains("valid JSON object"));
    }

    @Test
    public void parseToolCallTreatsEmptyStringArgumentsAsNoArgs() {
        MistralAgent.ParsedToolCall call = MistralAgent.parseToolCall(JsonParser.parseString(
                "{\"id\":\"call_4\",\"function\":{\"name\":\"get_state\",\"arguments\":\"\"}}"));

        assertTrue(call.canRespondAsTool);
        assertNull(call.error);
        assertEquals(0, call.arguments.size());
    }
}
