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

package baritone.ai.planner;

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

/**
 * Persists the {@link PlanDocument} to &lt;gameDir&gt;/baritone/active-plan.json (same
 * atomic-write + test-hook idiom as {@link baritone.ai.MissionMemory}) so `ai recover`
 * resumes mid-plan after a crash or relaunch. Best-effort: persistence failures never
 * crash the mission.
 */
public final class PlannerStore {

    private PlannerStore() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();

    private static Path testFile;
    private static Path runtimeFile;

    /** Scope the plan file (e.g. per-world), mirroring MissionMemory.useStorageFile. */
    public static void useStorageFile(Path file) {
        if (file == null) {
            return;
        }
        synchronized (LOCK) {
            if (testFile != null) {
                return;
            }
            runtimeFile = file.toAbsolutePath().normalize();
        }
    }

    public static void save(PlanDocument plan) {
        if (plan == null) {
            return;
        }
        synchronized (LOCK) {
            Path file = storageFile();
            try {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                    GSON.toJson(plan, writer);
                }
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicMoveFailed) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | RuntimeException ignored) {
                // best-effort: a failed save must never kill the mission
            }
        }
    }

    /** The persisted plan, or null when there is none (or it is unreadable). */
    public static PlanDocument load() {
        synchronized (LOCK) {
            Path file = storageFile();
            if (!Files.exists(file)) {
                return null;
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                return GSON.fromJson(reader, PlanDocument.class);
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            try {
                Files.deleteIfExists(storageFile());
            } catch (IOException | RuntimeException ignored) {
            }
        }
    }

    private static Path storageFile() {
        if (testFile != null) {
            return testFile;
        }
        if (runtimeFile != null) {
            return runtimeFile;
        }
        return Minecraft.getInstance().gameDirectory.toPath().resolve("baritone").resolve("active-plan.json");
    }

    // ---------------------------------------------------------------- test hooks

    static void setFileForTests(Path file) {
        synchronized (LOCK) {
            testFile = file;
        }
    }

    static void clearFileForTests() {
        synchronized (LOCK) {
            testFile = null;
            runtimeFile = null;
        }
    }
}
