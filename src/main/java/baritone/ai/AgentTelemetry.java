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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Machine-readable agent telemetry sink for the Kihi Launcher.
 *
 * <p>Baritone's {@code logDirect} only reaches the in-game chat GUI, never process stdout. The
 * launcher spawns Minecraft and needs structured progress events, so every {@link #emit} writes one
 * line of JSON to <b>both</b> channels:
 * <ul>
 *     <li>{@code System.out.println("[AI:EVT] " + json)} — reaches the launcher over the child
 *         process's stdout.</li>
 *     <li>An append to {@code <gameDir>/baritone/agent_events.jsonl} — a durable fallback channel
 *         that survives stdout buffering or a missing console.</li>
 * </ul>
 *
 * <p>Telemetry is strictly best-effort: a serialization or I/O failure must NEVER crash the game,
 * so every path swallows its exceptions. This class touches no Minecraft classes (the game directory
 * is resolved lazily and is injectable) so it stays unit-testable.
 */
public final class AgentTelemetry {

    /** Stdout prefix the launcher greps for to pick telemetry lines out of the log stream. */
    public static final String PREFIX = "[AI:EVT] ";

    /** Filename under {@code <gameDir>/baritone/} for the fallback JSONL event log. */
    private static final String FILE_NAME = "agent_events.jsonl";

    private static final Gson GSON = new GsonBuilder().create();

    /** Current mission/session id, set by {@code #ai session <id>}; empty when unset. */
    private static volatile String session = "";

    /** Test-injectable override for the events file; {@code null} resolves to the live game dir. */
    private static volatile Path testFile;

    private AgentTelemetry() {}

    /** Sets the session id attached to every subsequent event ({@code null} clears it). */
    public static void setSession(String id) {
        session = id == null ? "" : id.trim();
    }

    /** The current session id (never {@code null}). */
    public static String session() {
        return session;
    }

    /** Emits an event carrying no data payload. */
    public static void emit(String kind) {
        emit(kind, (Map<String, Object>) null);
    }

    /** Emits an event with a single key/value data entry. */
    public static void emit(String kind, String key, Object value) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(key, value);
        emit(kind, data);
    }

    /**
     * Emits one telemetry event: serializes {@code {ts, session, kind, data}} to a single JSON line,
     * prints it to stdout with the {@link #PREFIX}, and appends it to the fallback JSONL file.
     * Best-effort end to end — never throws.
     */
    public static void emit(String kind, Map<String, Object> data) {
        String json;
        try {
            Event event = new Event(System.currentTimeMillis(), session, kind, data);
            json = toJson(event);
        } catch (RuntimeException serializeFailed) {
            // A bad data value must not crash the game; drop the event silently.
            return;
        }
        try {
            System.out.println(PREFIX + json);
        } catch (RuntimeException ignored) {
        }
        appendToFile(json);
    }

    /** Serializes an event to its one-line JSON form. Package-visible so unit tests can assert shape. */
    static String toJson(Event event) {
        return GSON.toJson(event);
    }

    /** Builds the event object without emitting it. Package-visible for unit tests. */
    static Event event(String kind, Map<String, Object> data) {
        return new Event(System.currentTimeMillis(), session, kind, data);
    }

    private static void appendToFile(String json) {
        try {
            Path file = eventsFile();
            if (file == null) {
                return;
            }
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(json);
                writer.write('\n');
            }
        } catch (IOException | RuntimeException ignored) {
            // Telemetry is best-effort; the stdout channel already carried the event.
        }
    }

    /**
     * Resolves the events file the same way {@link MissionMemory} resolves its storage: a
     * test-injected override wins, otherwise {@code <gameDir>/baritone/agent_events.jsonl}. The
     * Minecraft lookup is reflective-free but kept out of the test path via {@link #testFile}.
     */
    private static Path eventsFile() {
        Path override = testFile;
        if (override != null) {
            return override;
        }
        try {
            return net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("baritone").resolve(FILE_NAME);
        } catch (Throwable noMinecraft) {
            // No game directory (e.g. outside the client) — fall back to the stdout channel only.
            return null;
        }
    }

    /** Injects a fixed events file for tests; pass {@code null} to clear. */
    static void setFileForTests(Path file) {
        testFile = file;
    }

    /** Resets static state between tests. */
    static void resetForTests() {
        session = "";
        testFile = null;
    }

    /** Serialized event POJO. Field names (ts/session/kind/data) are part of the launcher contract. */
    static final class Event {
        final long ts;
        final String session;
        final String kind;
        final Map<String, Object> data;

        Event(long ts, String session, String kind, Map<String, Object> data) {
            this.ts = ts;
            this.session = session == null ? "" : session;
            this.kind = kind == null ? "" : kind;
            this.data = data == null ? new LinkedHashMap<>() : data;
        }
    }
}
