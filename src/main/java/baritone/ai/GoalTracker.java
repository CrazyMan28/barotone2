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
import java.util.List;

/**
 * Small shared state object for the AI goal HUD and goal-related tools.
 */
public final class GoalTracker {

    private static final Object LOCK = new Object();
    private static Snapshot current = Snapshot.empty();
    private static String lastGoal = "";

    private GoalTracker() {}

    public static void start(String goal, boolean planMode) {
        synchronized (LOCK) {
            String cleanGoal = clean(goal);
            current = new Snapshot(true, true, cleanGoal, planMode ? "Planning..." : "Starting...", planMode,
                    System.currentTimeMillis(), 0L, Collections.emptyList(), -1);
            if (!cleanGoal.isEmpty()) {
                lastGoal = cleanGoal;
            }
        }
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

    static void resetForTests() {
        synchronized (LOCK) {
            current = Snapshot.empty();
            lastGoal = "";
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
