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
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MissionMemory {

    public static final int MAX_MEMORIES = 80;
    public static final int MAX_CHECKPOINTS = 120;
    public static final int MAX_GOAL_HISTORY = 20;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "the", "and", "for", "with", "you", "your", "get", "got", "make", "made", "from", "into",
            "then", "this", "that", "near", "some", "any", "all", "out", "use", "using", "back", "come"));

    private static Path testFile;
    private static Path runtimeFile;
    private static Path loadedFile;
    private static State state = new State();
    private static boolean loaded;
    private static String lastError = "";

    private MissionMemory() {}

    public static MemoryRecord remember(String key, String value, String category, String source, Location location) {
        synchronized (LOCK) {
            ensureLoaded();
            String cleanKey = normalizeKey(key);
            if (cleanKey.isEmpty()) {
                throw new IllegalArgumentException("Memory key is empty.");
            }
            String cleanValue = clean(value, 600);
            if (cleanValue.isEmpty() && location == null) {
                throw new IllegalArgumentException("Memory value is empty.");
            }
            long now = System.currentTimeMillis();
            MemoryRecord existing = findMemory(cleanKey);
            if (existing == null) {
                existing = new MemoryRecord();
                existing.key = cleanKey;
                existing.createdAt = now;
                state.memories.add(0, existing);
            }
            existing.value = cleanValue;
            existing.category = defaulted(category, "general", 40);
            existing.source = defaulted(source, "unknown", 40);
            existing.updatedAt = now;
            existing.clearLocation();
            if (location != null) {
                existing.dimension = clean(location.dimension, 80);
                existing.x = location.x;
                existing.y = location.y;
                existing.z = location.z;
                existing.hasPosition = true;
            }
            trimMemories();
            saveLocked();
            return existing.copy();
        }
    }

    public static MemoryRecord rememberLocation(String key, String note, String category,
                                                String dimension, int x, int y, int z, String source) {
        String value = clean(note, 600);
        if (value.isEmpty()) {
            value = key + " at " + x + "," + y + "," + z;
        }
        return remember(key, value, category, source, new Location(dimension, x, y, z));
    }

    public static boolean forget(String key) {
        synchronized (LOCK) {
            ensureLoaded();
            String cleanKey = normalizeKey(key);
            boolean removed = false;
            Iterator<MemoryRecord> it = state.memories.iterator();
            while (it.hasNext()) {
                if (it.next().key.equals(cleanKey)) {
                    it.remove();
                    removed = true;
                }
            }
            if (removed) {
                saveLocked();
            }
            return removed;
        }
    }

    public static int clearCheckpoints() {
        synchronized (LOCK) {
            ensureLoaded();
            int count = state.checkpoints.size();
            state.checkpoints.clear();
            saveLocked();
            return count;
        }
    }

    /** Record a station the agent placed (or refresh its validatedAt if already known). */
    public static void rememberStation(String type, String dimension, int x, int y, int z) {
        String t = defaulted(type, "", 40);
        if (t.isEmpty()) {
            return;
        }
        String dim = clean(dimension, 80);
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            ensureLoaded();
            for (StationRecord st : state.stations) {
                if (st.type.equals(t) && st.dimension.equals(dim) && st.x == x && st.y == y && st.z == z) {
                    st.validatedAt = now;
                    saveLocked();
                    return;
                }
            }
            StationRecord rec = new StationRecord();
            rec.type = t;
            rec.dimension = dim;
            rec.x = x;
            rec.y = y;
            rec.z = z;
            rec.createdAt = now;
            rec.validatedAt = now;
            state.stations.add(rec);
            while (state.stations.size() > 60) {
                state.stations.remove(0);
            }
            saveLocked();
        }
    }

    /** Copies of all registered stations of {@code type} in {@code dimension} (caller sorts by distance). */
    public static List<StationRecord> findStations(String type, String dimension) {
        String t = defaulted(type, "", 40);
        String dim = clean(dimension, 80);
        List<StationRecord> out = new ArrayList<>();
        synchronized (LOCK) {
            ensureLoaded();
            for (StationRecord st : state.stations) {
                if (st.type.equals(t) && st.dimension.equals(dim)) {
                    out.add(st.copy());
                }
            }
        }
        return out;
    }

    public static void validateStation(String type, String dimension, int x, int y, int z) {
        synchronized (LOCK) {
            ensureLoaded();
            for (StationRecord st : state.stations) {
                if (st.type.equals(type) && st.dimension.equals(dimension) && st.x == x && st.y == y && st.z == z) {
                    st.validatedAt = System.currentTimeMillis();
                    saveLocked();
                    return;
                }
            }
        }
    }

    public static boolean forgetStation(String type, String dimension, int x, int y, int z) {
        synchronized (LOCK) {
            ensureLoaded();
            boolean removed = state.stations.removeIf(st ->
                    st.type.equals(type) && st.dimension.equals(dimension) && st.x == x && st.y == y && st.z == z);
            if (removed) {
                saveLocked();
            }
            return removed;
        }
    }

    /** Short "type@x,y,z" list (max 6) for get_state so the agent sees its homestead. */
    public static String stationsForPrompt() {
        StringBuilder sb = new StringBuilder();
        synchronized (LOCK) {
            ensureLoaded();
            int n = 0;
            for (StationRecord st : state.stations) {
                if (n >= 6) {
                    break;
                }
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(st.type).append('@').append(st.x).append(',').append(st.y).append(',').append(st.z);
                n++;
            }
        }
        return sb.toString();
    }

    public static void clearAll() {
        synchronized (LOCK) {
            ensureLoaded();
            state.memories.clear();
            state.checkpoints.clear();
            saveLocked();
        }
    }

    public static Checkpoint recordCheckpoint(String missionGoal, String name, String detail, String status) {
        synchronized (LOCK) {
            ensureLoaded();
            Checkpoint checkpoint = new Checkpoint();
            checkpoint.id = ++state.nextCheckpointId;
            checkpoint.missionGoal = clean(missionGoal, 180);
            checkpoint.name = defaulted(name, "checkpoint", 60);
            checkpoint.detail = clean(detail, 360);
            checkpoint.status = defaulted(status, "ok", 40);
            checkpoint.createdAt = System.currentTimeMillis();
            state.checkpoints.add(0, checkpoint);
            trimCheckpoints();
            saveLocked();
            return checkpoint.copy();
        }
    }

    public static void recordCheckpointQuietly(String missionGoal, String name, String detail, String status) {
        try {
            recordCheckpoint(missionGoal, name, detail, status);
        } catch (RuntimeException ignored) {
        }
    }

    public static Snapshot snapshot() {
        synchronized (LOCK) {
            ensureLoaded();
            List<MemoryRecord> memories = new ArrayList<>();
            for (MemoryRecord memory : state.memories) {
                memories.add(memory.copy());
            }
            List<Checkpoint> checkpoints = new ArrayList<>();
            for (Checkpoint checkpoint : state.checkpoints) {
                checkpoints.add(checkpoint.copy());
            }
            return new Snapshot(memories, checkpoints, lastError);
        }
    }

    public static void useStorageFile(Path file) {
        if (file == null) {
            return;
        }
        synchronized (LOCK) {
            if (testFile != null) {
                return;
            }
            Path normalized = file.toAbsolutePath().normalize();
            if (normalized.equals(runtimeFile)) {
                return;
            }
            runtimeFile = normalized;
            loadedFile = null;
            loaded = false;
            state = new State();
            lastError = "";
        }
    }

    public static String describe() {
        Snapshot snapshot = snapshot();
        StringBuilder out = new StringBuilder("Mission memory: ")
                .append(snapshot.memories.size()).append(" saved, ")
                .append(snapshot.checkpoints.size()).append(" checkpoint(s).");
        if (!snapshot.lastError.isEmpty()) {
            out.append("\nStorage warning: ").append(snapshot.lastError);
        }
        appendMemories(out, snapshot.memories, 10);
        appendCheckpoints(out, snapshot.checkpoints, 5);
        return out.toString();
    }

    public static String describeCheckpoints() {
        Snapshot snapshot = snapshot();
        StringBuilder out = new StringBuilder("Mission checkpoints: ").append(snapshot.checkpoints.size());
        appendCheckpoints(out, snapshot.checkpoints, 12);
        return out.toString();
    }

    public static String recall(String query, String category, boolean includeCheckpoints) {
        Snapshot snapshot = snapshot();
        String q = clean(query, 120).toLowerCase(Locale.ROOT);
        String cat = clean(category, 40).toLowerCase(Locale.ROOT);
        List<MemoryRecord> matching = new ArrayList<>();
        for (MemoryRecord memory : snapshot.memories) {
            if (!cat.isEmpty() && !memory.category.toLowerCase(Locale.ROOT).equals(cat)) {
                continue;
            }
            if (q.isEmpty() || contains(memory.key, q) || contains(memory.value, q)
                    || contains(memory.category, q) || contains(memory.dimension, q)) {
                matching.add(memory);
            }
        }
        StringBuilder out = new StringBuilder();
        out.append(matching.isEmpty() ? "No matching memories." : "Matching memories:");
        appendMemories(out, matching, 12);
        if (includeCheckpoints) {
            List<Checkpoint> checkpointMatches = new ArrayList<>();
            for (Checkpoint checkpoint : snapshot.checkpoints) {
                if (q.isEmpty() || contains(checkpoint.missionGoal, q) || contains(checkpoint.name, q)
                        || contains(checkpoint.detail, q) || contains(checkpoint.status, q)) {
                    checkpointMatches.add(checkpoint);
                }
            }
            appendCheckpoints(out, checkpointMatches, 8);
        }
        return out.toString();
    }

    /**
     * Returns a compact, single-block context for {@code goal}: the memories whose key/value/category
     * overlap the goal's words most, newest checkpoints first as a tiebreak. Empty string when nothing
     * is relevant. Used to seed the agent prompt so it plans with what it already knows.
     */
    public static String contextForGoal(String goal, int maxItems) {
        Snapshot snapshot = snapshot();
        List<String> tokens = tokenize(goal);
        if (snapshot.memories.isEmpty() || maxItems <= 0) {
            return "";
        }
        List<MemoryRecord> scored = new ArrayList<>(snapshot.memories);
        // Score by how many goal tokens appear in the memory; preserve newest-first order on ties.
        scored.sort((a, b) -> Integer.compare(scoreMemory(b, tokens), scoreMemory(a, tokens)));
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (MemoryRecord memory : scored) {
            if (shown >= maxItems) {
                break;
            }
            // When the goal carries real words, only surface memories that actually overlap it.
            if (!tokens.isEmpty() && scoreMemory(memory, tokens) == 0) {
                continue;
            }
            if (shown > 0) {
                out.append(" | ");
            }
            out.append(memory.key).append("=").append(shortLine(memory.value, 80));
            if (memory.hasPosition) {
                out.append(" @ ").append(memory.x).append(",").append(memory.y).append(",").append(memory.z);
            }
            shown++;
        }
        return out.toString();
    }

    /** Loads the persisted recent-goal list (newest first), used to seed the in-memory goal history. */
    public static List<String> loadGoalHistory() {
        synchronized (LOCK) {
            ensureLoaded();
            return new ArrayList<>(state.goalHistory);
        }
    }

    /** Persists the recent-goal list (newest first) so {@code goal history}/{@code retry} survive a restart. */
    public static void saveGoalHistory(List<String> history) {
        synchronized (LOCK) {
            ensureLoaded();
            state.goalHistory.clear();
            if (history != null) {
                for (String goal : history) {
                    String clean = clean(goal, 180);
                    if (!clean.isEmpty() && !state.goalHistory.contains(clean)) {
                        state.goalHistory.add(clean);
                    }
                    if (state.goalHistory.size() >= MAX_GOAL_HISTORY) {
                        break;
                    }
                }
            }
            saveQuietly();
        }
    }

    /**
     * Records the mission currently being attempted so it can be resumed if the game closes mid-mission.
     * Best-effort: a persistence failure must never crash the command that started the mission.
     */
    public static void recordInFlightMission(String goal, boolean planMode) {
        synchronized (LOCK) {
            ensureLoaded();
            String clean = clean(goal, 180);
            if (clean.isEmpty()) {
                return;
            }
            state.inFlightGoal = clean;
            state.inFlightPlanMode = planMode;
            saveQuietly();
        }
    }

    /** Clears any recorded in-flight mission (called when a mission truly finishes). Best-effort. */
    public static void clearInFlightMission() {
        synchronized (LOCK) {
            ensureLoaded();
            if (state.inFlightGoal == null || state.inFlightGoal.isEmpty()) {
                return;
            }
            state.inFlightGoal = "";
            state.inFlightPlanMode = false;
            saveQuietly();
        }
    }

    /** The mission that was running when the game last closed, or {@code null} if none. */
    public static InFlightMission getInFlightMission() {
        synchronized (LOCK) {
            ensureLoaded();
            if (state.inFlightGoal == null || state.inFlightGoal.isEmpty()) {
                return null;
            }
            return new InFlightMission(state.inFlightGoal, state.inFlightPlanMode);
        }
    }

    public static String summaryForPrompt() {
        Snapshot snapshot = snapshot();
        StringBuilder out = new StringBuilder();
        int memoryCount = Math.min(6, snapshot.memories.size());
        for (int i = 0; i < memoryCount; i++) {
            if (i > 0) {
                out.append(" | ");
            }
            MemoryRecord memory = snapshot.memories.get(i);
            out.append(memory.key).append("=").append(shortLine(memory.value, 80));
            if (memory.hasPosition) {
                out.append(" @ ").append(memory.x).append(",").append(memory.y).append(",").append(memory.z);
            }
        }
        if (!snapshot.checkpoints.isEmpty()) {
            if (out.length() > 0) {
                out.append(" | ");
            }
            Checkpoint checkpoint = snapshot.checkpoints.get(0);
            out.append("last_checkpoint=").append(checkpoint.name).append(":")
                    .append(shortLine(checkpoint.detail, 80));
        }
        return out.length() == 0 ? "empty" : out.toString();
    }

    static void setFileForTests(Path file) {
        synchronized (LOCK) {
            testFile = file;
            loadedFile = null;
            loaded = false;
            state = new State();
            lastError = "";
        }
    }

    static void resetForTests() {
        synchronized (LOCK) {
            loadedFile = null;
            loaded = false;
            state = new State();
            lastError = "";
        }
    }

    static void clearFileForTests() {
        synchronized (LOCK) {
            testFile = null;
            runtimeFile = null;
            loadedFile = null;
            loaded = false;
            state = new State();
            lastError = "";
        }
    }

    private static void ensureLoaded() {
        Path file = storageFile();
        if (loaded && file.equals(loadedFile)) {
            return;
        }
        loadedFile = file;
        loaded = true;
        lastError = "";
        state = new State();
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            State loadedState = GSON.fromJson(reader, State.class);
            if (loadedState != null) {
                state = loadedState;
                state.normalize();
            }
        } catch (Exception e) {
            lastError = "Could not load mission-memory.json: " + e.getClass().getSimpleName();
            state = new State();
        }
    }

    /** Save without throwing — for best-effort persistence (goal history, in-flight mission) that must not crash callers. */
    private static void saveQuietly() {
        try {
            saveLocked();
        } catch (RuntimeException ignored) {
        }
    }

    private static void saveLocked() {
        Path file = storageFile();
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(state, writer);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailed) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            lastError = "";
        } catch (IOException e) {
            lastError = "Could not save mission-memory.json: " + e.getClass().getSimpleName();
            throw new IllegalStateException(lastError, e);
        }
    }

    private static Path storageFile() {
        if (testFile != null) {
            return testFile;
        }
        if (runtimeFile != null) {
            return runtimeFile;
        }
        return Minecraft.getInstance().gameDirectory.toPath().resolve("baritone").resolve("mission-memory.json");
    }

    private static MemoryRecord findMemory(String key) {
        for (MemoryRecord memory : state.memories) {
            if (memory.key.equals(key)) {
                return memory;
            }
        }
        return null;
    }

    private static void trimMemories() {
        while (state.memories.size() > MAX_MEMORIES) {
            state.memories.remove(state.memories.size() - 1);
        }
    }

    private static void trimCheckpoints() {
        while (state.checkpoints.size() > MAX_CHECKPOINTS) {
            state.checkpoints.remove(state.checkpoints.size() - 1);
        }
    }

    private static void appendMemories(StringBuilder out, List<MemoryRecord> memories, int limit) {
        int shown = 0;
        for (MemoryRecord memory : memories) {
            if (shown >= limit) {
                out.append("\n... ").append(memories.size() - shown).append(" more memories");
                break;
            }
            out.append("\n- ").append(memory.key).append(" [").append(memory.category).append("]: ")
                    .append(shortLine(memory.value, 160));
            if (memory.hasPosition) {
                out.append(" @ ").append(memory.dimension).append(" ")
                        .append(memory.x).append(",").append(memory.y).append(",").append(memory.z);
            }
            shown++;
        }
    }

    private static void appendCheckpoints(StringBuilder out, List<Checkpoint> checkpoints, int limit) {
        int shown = 0;
        for (Checkpoint checkpoint : checkpoints) {
            if (shown >= limit) {
                out.append("\n... ").append(checkpoints.size() - shown).append(" more checkpoints");
                break;
            }
            out.append("\n#").append(checkpoint.id).append(" ").append(checkpoint.status)
                    .append(" ").append(checkpoint.name);
            if (!checkpoint.missionGoal.isEmpty()) {
                out.append(" | ").append(shortLine(checkpoint.missionGoal, 90));
            }
            if (!checkpoint.detail.isEmpty()) {
                out.append(" | ").append(shortLine(checkpoint.detail, 120));
            }
            shown++;
        }
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private static List<String> tokenize(String raw) {
        List<String> tokens = new ArrayList<>();
        if (raw == null) {
            return tokens;
        }
        for (String part : raw.toLowerCase(Locale.ROOT).split("[^a-z0-9:]+")) {
            // Skip blanks and short stop-words that would match almost everything.
            if (part.length() >= 3 && !STOP_WORDS.contains(part) && !tokens.contains(part)) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    private static int scoreMemory(MemoryRecord memory, List<String> tokens) {
        if (tokens.isEmpty()) {
            return 0;
        }
        String haystack = (memory.key + " " + memory.value + " " + memory.category + " " + memory.dimension)
                .toLowerCase(Locale.ROOT);
        int score = 0;
        for (String token : tokens) {
            if (haystack.contains(token)) {
                score++;
            }
        }
        return score;
    }

    private static String defaulted(String raw, String fallback, int max) {
        String s = clean(raw, max);
        return s.isEmpty() ? fallback : s;
    }

    private static String clean(String raw, int max) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 3)) + "...";
    }

    private static String normalizeKey(String key) {
        return clean(key, 80).toLowerCase(Locale.ROOT).replaceAll("\\s+", "_")
                .replaceAll("[^a-z0-9_.:-]", "");
    }

    private static String shortLine(String raw, int max) {
        return clean(raw, max);
    }

    public static final class Location {
        public final String dimension;
        public final int x;
        public final int y;
        public final int z;

        public Location(String dimension, int x, int y, int z) {
            this.dimension = dimension == null ? "" : dimension;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    public static final class Snapshot {
        public final List<MemoryRecord> memories;
        public final List<Checkpoint> checkpoints;
        public final String lastError;

        private Snapshot(List<MemoryRecord> memories, List<Checkpoint> checkpoints, String lastError) {
            this.memories = Collections.unmodifiableList(memories);
            this.checkpoints = Collections.unmodifiableList(checkpoints);
            this.lastError = lastError == null ? "" : lastError;
        }
    }

    public static final class MemoryRecord {
        public String key = "";
        public String value = "";
        public String category = "general";
        public String source = "unknown";
        public String dimension = "";
        public int x;
        public int y;
        public int z;
        public boolean hasPosition;
        public long createdAt;
        public long updatedAt;

        private void clearLocation() {
            dimension = "";
            x = 0;
            y = 0;
            z = 0;
            hasPosition = false;
        }

        private MemoryRecord copy() {
            MemoryRecord copy = new MemoryRecord();
            copy.key = key;
            copy.value = value;
            copy.category = category;
            copy.source = source;
            copy.dimension = dimension;
            copy.x = x;
            copy.y = y;
            copy.z = z;
            copy.hasPosition = hasPosition;
            copy.createdAt = createdAt;
            copy.updatedAt = updatedAt;
            return copy;
        }
    }

    public static final class Checkpoint {
        public int id;
        public String missionGoal = "";
        public String name = "";
        public String detail = "";
        public String status = "";
        public long createdAt;

        private Checkpoint copy() {
            Checkpoint copy = new Checkpoint();
            copy.id = id;
            copy.missionGoal = missionGoal;
            copy.name = name;
            copy.detail = detail;
            copy.status = status;
            copy.createdAt = createdAt;
            return copy;
        }
    }

    public static final class InFlightMission {
        public final String goal;
        public final boolean planMode;

        private InFlightMission(String goal, boolean planMode) {
            this.goal = goal;
            this.planMode = planMode;
        }
    }

    /** A station the agent built (crafting_table, furnace, ...) or its base_anchor — so it returns to
     *  and re-uses them instead of placing/crafting new ones. Deduped by (type,dimension,x,y,z). */
    public static final class StationRecord {
        public String type = "";
        public String dimension = "";
        public int x;
        public int y;
        public int z;
        public long createdAt;
        public long validatedAt;

        private StationRecord copy() {
            StationRecord c = new StationRecord();
            c.type = type;
            c.dimension = dimension;
            c.x = x;
            c.y = y;
            c.z = z;
            c.createdAt = createdAt;
            c.validatedAt = validatedAt;
            return c;
        }
    }

    private static final class State {
        int version = 1;
        int nextCheckpointId;
        List<MemoryRecord> memories = new ArrayList<>();
        List<Checkpoint> checkpoints = new ArrayList<>();
        List<StationRecord> stations = new ArrayList<>();
        List<String> goalHistory = new ArrayList<>();
        String inFlightGoal = "";
        boolean inFlightPlanMode;

        void normalize() {
            if (memories == null) {
                memories = new ArrayList<>();
            }
            if (checkpoints == null) {
                checkpoints = new ArrayList<>();
            }
            if (stations == null) {
                stations = new ArrayList<>();
            }
            for (StationRecord st : stations) {
                st.type = defaulted(st.type, "", 40);
                st.dimension = clean(st.dimension, 80);
            }
            stations.removeIf(st -> st.type.isEmpty());
            if (goalHistory == null) {
                goalHistory = new ArrayList<>();
            }
            if (inFlightGoal == null) {
                inFlightGoal = "";
            }
            for (MemoryRecord memory : memories) {
                memory.key = normalizeKey(memory.key);
                memory.value = clean(memory.value, 600);
                memory.category = defaulted(memory.category, "general", 40);
                memory.source = defaulted(memory.source, "unknown", 40);
                memory.dimension = clean(memory.dimension, 80);
            }
            memories.removeIf(memory -> memory.key.isEmpty());
            int maxId = nextCheckpointId;
            for (Checkpoint checkpoint : checkpoints) {
                checkpoint.missionGoal = clean(checkpoint.missionGoal, 180);
                checkpoint.name = defaulted(checkpoint.name, "checkpoint", 60);
                checkpoint.detail = clean(checkpoint.detail, 360);
                checkpoint.status = defaulted(checkpoint.status, "ok", 40);
                maxId = Math.max(maxId, checkpoint.id);
            }
            nextCheckpointId = maxId;
            List<String> cleanedHistory = new ArrayList<>();
            for (String goal : goalHistory) {
                String clean = clean(goal, 180);
                if (!clean.isEmpty() && !cleanedHistory.contains(clean) && cleanedHistory.size() < MAX_GOAL_HISTORY) {
                    cleanedHistory.add(clean);
                }
            }
            goalHistory = cleanedHistory;
            inFlightGoal = clean(inFlightGoal, 180);
            trimMemories();
            trimCheckpoints();
        }
    }
}
