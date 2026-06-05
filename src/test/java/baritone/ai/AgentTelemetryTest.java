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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentTelemetryTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @After
    public void tearDown() {
        AgentTelemetry.resetForTests();
    }

    @Test
    public void eventJsonHasExpectedTopLevelKeys() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("goal", "mine diamonds");
        String json = AgentTelemetry.toJson(AgentTelemetry.event("mission_start", data));

        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertTrue("ts key present", obj.has("ts"));
        assertTrue("session key present", obj.has("session"));
        assertTrue("kind key present", obj.has("kind"));
        assertTrue("data key present", obj.has("data"));

        assertTrue("ts is epoch millis", obj.get("ts").getAsLong() > 0L);
        assertEquals("mission_start", obj.get("kind").getAsString());
        assertEquals("mine diamonds", obj.getAsJsonObject("data").get("goal").getAsString());
    }

    @Test
    public void setSessionIsReflectedInEvents() {
        AgentTelemetry.setSession("s_abc123");
        assertEquals("s_abc123", AgentTelemetry.session());

        String json = AgentTelemetry.toJson(AgentTelemetry.event("plan", null));
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("s_abc123", obj.get("session").getAsString());
    }

    @Test
    public void emptySessionSerializesAsEmptyString() {
        AgentTelemetry.setSession(null);
        String json = AgentTelemetry.toJson(AgentTelemetry.event("status", null));
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("", obj.get("session").getAsString());
    }

    @Test
    public void nullDataSerializesAsEmptyObject() {
        String json = AgentTelemetry.toJson(AgentTelemetry.event("position", null));
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(obj.get("data").isJsonObject());
        assertEquals(0, obj.getAsJsonObject("data").size());
    }

    @Test
    public void emitAppendsOneJsonLinePerEventToFile() throws Exception {
        Path file = temporaryFolder.getRoot().toPath().resolve("baritone").resolve("agent_events.jsonl");
        AgentTelemetry.setFileForTests(file);
        AgentTelemetry.setSession("s_file");

        AgentTelemetry.emit("mission_start", "goal", "get wood");
        AgentTelemetry.emit("step_complete", "index", 1);

        assertTrue("events file created", Files.exists(file));
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());

        JsonObject first = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        assertEquals("mission_start", first.get("kind").getAsString());
        assertEquals("s_file", first.get("session").getAsString());
        assertEquals("get wood", first.getAsJsonObject("data").get("goal").getAsString());

        JsonObject second = JsonParser.parseString(lines.get(1)).getAsJsonObject();
        assertEquals("step_complete", second.get("kind").getAsString());
        assertEquals(1, second.getAsJsonObject("data").get("index").getAsInt());
    }

    @Test
    public void emitCreatesParentDirectories() {
        Path nested = temporaryFolder.getRoot().toPath()
                .resolve("does").resolve("not").resolve("exist").resolve("agent_events.jsonl");
        AgentTelemetry.setFileForTests(nested);
        AgentTelemetry.emit("status", "phase", "boot");
        assertTrue(Files.exists(nested));
    }

    @Test
    public void emitWithUnwritablePathDoesNotThrow() {
        // A directory path cannot be opened as a file for append; telemetry must swallow the failure.
        AgentTelemetry.setFileForTests(temporaryFolder.getRoot().toPath());
        AgentTelemetry.emit("status", "phase", "boot");
        // Reaching here without an exception is the assertion.
        assertFalse(Files.isRegularFile(temporaryFolder.getRoot().toPath()));
    }
}
