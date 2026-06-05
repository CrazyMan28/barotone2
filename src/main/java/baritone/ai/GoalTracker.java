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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small shared state object for the AI goal HUD and goal-related tools.
 */
public final class GoalTracker {

    private static final int HISTORY_LIMIT = 5;
    private static final Object LOCK = new Object();
    private static Snapshot current = Snapshot.empty();
    private static String lastGoal = "";
    private static final List<String> history = new ArrayList<>();
    private static HistoryStore historyStore;

    private GoalTracker() {}

    /**
     * Optional persistence backend for the recent-goal list so {@code goal history} / {@code goal retry}
     * survive a restart. Left {@code null} in unit tests, which keeps {@link GoalTracker} file-free.
     */
    public interface HistoryStore {
        void save(List<String> history);

        List<String> load();
    }

    /** Installs a persistence backend and seeds the in-memory history from it (newest first). */
    public static void setHistoryStore(HistoryStore store) {
        synchronized (LOCK) {
            historyStore = store;
            if (store == null) {
                return;
            }
            try {
                List<String> loaded = store.load();
                if (loaded == null) {
                    return;
                }
                history.clear();
                for (String goal : loaded) {
                    String clean = clean(goal);
                    if (!clean.isEmpty() && !history.contains(clean) && history.size() < HISTORY_LIMIT) {
                        history.add(clean);
                    }
                }
                if (!history.isEmpty()) {
                    lastGoal = history.get(0);
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    public static void start(String goal, boolean planMode) {
        synchronized (LOCK) {
            String cleanGoal = clean(goal);
            current = new Snapshot(true, true, cleanGoal, planMode ? "Planning..." : "Starting...", planMode,
                    System.currentTimeMillis(), 0L, Collections.emptyList(), -1);
            if (!cleanGoal.isEmpty()) {
                lastGoal = cleanGoal;
                remember(cleanGoal);
            }
        }
        AgentTelemetry.emit("mission_start", "goal", clean(goal));
    }

    public static void setStatus(String status) {
        synchronized (LOCK) {
            if (!current.visible) {
                return;
            }
            current = current.withStatus(clean(status));
        }
    }

    public static void setPlan(List<String> rawSteps) {
        synchronized (LOCK) {
            if (!current.visible) {
                return;
            }
            List<Step> steps = new ArrayList<>();
            int max = Math.min(10, rawSteps == null ? 0 : rawSteps.size());
            for (int i = 0; i < max; i++) {
                String step = clean(rawSteps.get(i));
                if (!step.isEmpty()) {
                    steps.add(new Step(step, false));
                }
            }
            current = current.withSteps(steps).withStatus(steps.isEmpty() ? "Running" : "Plan ready");
            List<String> stepTexts = new ArrayList<>(steps.size());
            for (Step step : steps) {
                stepTexts.add(step.text);
            }
            AgentTelemetry.emit("plan", "steps", stepTexts);
        }
    }

    public static void completeStep(int oneBasedIndex, String status) {
        synchronized (LOCK) {
            if (!current.visible || current.steps.isEmpty()) {
                if (status != null && !status.isBlank()) {
                    current = current.withStatus(clean(status));
                }
                return;
            }
            int idx = Math.max(0, Math.min(current.steps.size() - 1, oneBasedIndex - 1));
            List<Step> steps = new ArrayList<>(current.steps);
            Step old = steps.get(idx);
            steps.set(idx, new Step(old.text, true));
            current = current.withSteps(steps).withStatus(status == null || status.isBlank()
                    ? "Completed step " + (idx + 1)
                    : clean(status));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("index", idx + 1);
            data.put("status", status == null || status.isBlank() ? "done" : clean(status));
            data.put("total", steps.size());
            AgentTelemetry.emit("step_complete", data);
        }
    }

    public static void finish(String summary) {
        synchronized (LOCK) {
            if (!current.visible) {
                return;
            }
            current = current.withActive(false)
                    .withFinishedAt(System.currentTimeMillis())
                    .withStatus(prefixStatus("Done", summary));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", clean(summary));
        data.put("success", true);
        AgentTelemetry.emit("mission_done", data);
    }

    public static void fail(String summary) {
        synchronized (LOCK) {
            if (!current.visible) {
                return;
            }
            current = current.withActive(false)
                    .withFinishedAt(System.currentTimeMillis())
                    .withStatus(prefixStatus("Stopped", summary));
        }
        AgentTelemetry.emit("mission_fail", "summary", clean(summary));
    }

    public static void hide() {
        synchronized (LOCK) {
            current = Snapshot.empty();
        }
    }

    public static boolean toggleVisible() {
        synchronized (LOCK) {
            if (current.isEmpty()) {
                current = Snapshot.idle(true);
                return true;
            }
            current = current.withVisible(!current.visible);
            return current.visible;
        }
    }

    public static void showIdle() {
        synchronized (LOCK) {
            if (current.isEmpty()) {
                current = Snapshot.idle(true);
            } else {
                current = current.withVisible(true);
            }
        }
    }

    public static Snapshot snapshot() {
        synchronized (LOCK) {
            return current;
        }
    }

    public static String lastGoal() {
        synchronized (LOCK) {
            return lastGoal;
        }
    }

    public static List<String> history() {
        synchronized (LOCK) {
            return Collections.unmodifiableList(new ArrayList<>(history));
        }
    }

    public static String historyGoal(int oneBasedIndex) {
        synchronized (LOCK) {
            int idx = oneBasedIndex - 1;
            if (idx < 0 || idx >= history.size()) {
                return "";
            }
            return history.get(idx);
        }
    }

    public static String describeHistory() {
        List<String> recent = history();
        if (recent.isEmpty()) {
            return "No previous AI goals.";
        }
        StringBuilder out = new StringBuilder("Recent AI goals:");
        for (int i = 0; i < recent.size(); i++) {
            out.append("\n").append(i + 1).append(". ").append(recent.get(i));
        }
        out.append("\nRun `goal retry` to rerun the latest goal, or `goal retry <number>` for a listed goal.");
        return out.toString();
    }

    public static String describe() {
        Snapshot s = snapshot();
        if (!s.visible || s.isEmpty()) {
            return "No visible AI goal.";
        }
        if (s.goal.isEmpty()) {
            return "No active AI goal.\nStatus: " + (s.status.isEmpty() ? "Idle" : s.status);
        }
        StringBuilder out = new StringBuilder();
        out.append(s.active ? "Active" : "Last").append(" goal: ").append(s.goal).append("\n");
        out.append("Status: ").append(s.status);
        for (int i = 0; i < s.steps.size(); i++) {
            Step step = s.steps.get(i);
            out.append("\n").append(i + 1).append(". ")
                    .append(step.done ? "[x] " : "[ ] ")
                    .append(step.text);
        }
        return out.toString();
    }

    private static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() <= 180 ? s : s.substring(0, 177) + "...";
    }

    private static String prefixStatus(String prefix, String raw) {
        String s = clean(raw);
        return s.isEmpty() ? prefix : prefix + ": " + s;
    }

    private static void remember(String goal) {
        history.remove(goal);
        history.add(0, goal);
        while (history.size() > HISTORY_LIMIT) {
            history.remove(history.size() - 1);
        }
        if (historyStore != null) {
            try {
                historyStore.save(new ArrayList<>(history));
            } catch (RuntimeException ignored) {
            }
        }
    }

    static void resetForTests() {
        synchronized (LOCK) {
            current = Snapshot.empty();
            lastGoal = "";
            history.clear();
            historyStore = null;
        }
    }

    public static final class Snapshot {
        public final boolean visible;
        public final boolean active;
        public final String goal;
        public final String status;
        public final boolean planMode;
        public final long startedAt;
        public final long finishedAt;
        public final List<Step> steps;
        public final int currentStep;

        private Snapshot(
                boolean visible,
                boolean active,
                String goal,
                String status,
                boolean planMode,
                long startedAt,
                long finishedAt,
                List<Step> steps,
                int currentStep) {
            this.visible = visible;
            this.active = active;
            this.goal = goal == null ? "" : goal;
            this.status = status == null ? "" : status;
            this.planMode = planMode;
            this.startedAt = startedAt;
            this.finishedAt = finishedAt;
            this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
            this.currentStep = currentStep;
        }

        private static Snapshot empty() {
            return new Snapshot(false, false, "", "", false, 0L, 0L, Collections.emptyList(), -1);
        }

        private static Snapshot idle(boolean visible) {
            return new Snapshot(visible, false, "", "No AI goal yet", false, 0L, 0L, Collections.emptyList(), -1);
        }

        public boolean shouldAutoHide(long nowMillis, long maxAgeMillis) {
            return !active
                    && finishedAt > 0L
                    && nowMillis - finishedAt > maxAgeMillis
                    && isDoneStatus(status);
        }

        private boolean isEmpty() {
            return goal.isEmpty() && status.isEmpty() && steps.isEmpty() && startedAt == 0L && finishedAt == 0L;
        }

        private Snapshot withStatus(String status) {
            return new Snapshot(visible, active, goal, status, planMode, startedAt, finishedAt, steps, currentStep);
        }

        private Snapshot withSteps(List<Step> steps) {
            return new Snapshot(visible, active, goal, status, planMode, startedAt, finishedAt, steps, currentStep);
        }

        private Snapshot withActive(boolean active) {
            return new Snapshot(visible, active, goal, status, planMode, startedAt, finishedAt, steps, currentStep);
        }

        private Snapshot withVisible(boolean visible) {
            return new Snapshot(visible, active, goal, status, planMode, startedAt, finishedAt, steps, currentStep);
        }

        private Snapshot withFinishedAt(long finishedAt) {
            return new Snapshot(visible, active, goal, status, planMode, startedAt, finishedAt, steps, currentStep);
        }

        private static boolean isDoneStatus(String status) {
            return "Done".equals(status) || status.startsWith("Done:");
        }
    }

    public static final class Step {
        public final String text;
        public final boolean done;

        private Step(String text, boolean done) {
            this.text = text;
            this.done = done;
        }
    }
}
