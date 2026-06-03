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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

public final class MissionQueue {

    public static final int MAX_PENDING = 20;

    private static final Object LOCK = new Object();
    private static final Deque<Mission> pending = new ArrayDeque<>();

    private static int nextId = 1;
    private static Mission active;
    private static Mission lastFinished;
    private static boolean paused;
    private static boolean requeueActiveOnFinish;

    private MissionQueue() {}

    public static Mission create(String goal, boolean planMode, String label) {
        synchronized (LOCK) {
            return createLocked(goal, planMode, label, 0, "created");
        }
    }

    public static Mission enqueue(String goal, boolean planMode, String label) {
        synchronized (LOCK) {
            ensureCapacity();
            Mission mission = createLocked(goal, planMode, label, 0, "queued");
            pending.addLast(mission);
            return mission;
        }
    }

    public static void enqueueFront(Mission mission) {
        if (mission == null) {
            return;
        }
        synchronized (LOCK) {
            if (pending.size() >= MAX_PENDING) {
                pending.removeLast();
            }
            if (active != null && active.id == mission.id) {
                active = null;
                requeueActiveOnFinish = false;
            }
            pending.addFirst(mission.queued("queued"));
        }
    }

    public static Mission markActive(Mission mission) {
        if (mission == null) {
            return null;
        }
        synchronized (LOCK) {
            active = mission.started(System.currentTimeMillis());
            requeueActiveOnFinish = false;
            return active;
        }
    }

    public static Mission pollNext() {
        synchronized (LOCK) {
            if (paused || active != null || pending.isEmpty()) {
                return null;
            }
            active = pending.removeFirst().started(System.currentTimeMillis());
            requeueActiveOnFinish = false;
            return active;
        }
    }

    public static boolean finishActive(int missionId, String status) {
        synchronized (LOCK) {
            if (active == null || active.id != missionId) {
                return false;
            }
            Mission finished = active.finished(status, System.currentTimeMillis());
            active = null;
            boolean requeued = false;
            if (requeueActiveOnFinish) {
                if (pending.size() >= MAX_PENDING) {
                    pending.removeLast();
                }
                pending.addFirst(finished.queued("paused"));
                requeued = true;
            } else {
                lastFinished = finished;
            }
            requeueActiveOnFinish = false;
            return requeued;
        }
    }

    public static Mission pauseAndRequeueActive() {
        synchronized (LOCK) {
            paused = true;
            if (active == null) {
                return null;
            }
            requeueActiveOnFinish = true;
            return active;
        }
    }

    public static boolean pause() {
        synchronized (LOCK) {
            boolean changed = !paused;
            paused = true;
            return changed;
        }
    }

    public static boolean resume() {
        synchronized (LOCK) {
            boolean changed = paused;
            paused = false;
            return changed;
        }
    }

    public static boolean isPaused() {
        synchronized (LOCK) {
            return paused;
        }
    }

    public static boolean hasPending() {
        synchronized (LOCK) {
            return !pending.isEmpty();
        }
    }

    public static Mission retryLast() {
        synchronized (LOCK) {
            if (lastFinished == null) {
                return null;
            }
            if (pending.size() >= MAX_PENDING) {
                pending.removeLast();
            }
            Mission retry = createLocked(lastFinished.goal, lastFinished.planMode,
                    lastFinished.label + " retry", lastFinished.attempts, "queued retry");
            pending.addFirst(retry);
            return retry;
        }
    }

    public static int clearPending() {
        synchronized (LOCK) {
            int size = pending.size();
            pending.clear();
            requeueActiveOnFinish = false;
            return size;
        }
    }

    public static Snapshot snapshot() {
        synchronized (LOCK) {
            return new Snapshot(paused, active, new ArrayList<>(pending), lastFinished);
        }
    }

    public static String describe() {
        Snapshot snapshot = snapshot();
        StringBuilder sb = new StringBuilder();
        sb.append("Mission queue: ").append(snapshot.paused ? "paused" : "running");
        sb.append(", ").append(snapshot.pending.size()).append(" pending.");
        if (snapshot.active != null) {
            sb.append("\nActive #").append(snapshot.active.id)
                    .append(" attempt ").append(snapshot.active.attempts)
                    .append(": ").append(shorten(snapshot.active.goal, 120));
        }
        int index = 1;
        int shown = 0;
        for (Mission mission : snapshot.pending) {
            if (shown >= 5) {
                sb.append("\n... ").append(snapshot.pending.size() - shown).append(" more pending");
                break;
            }
            sb.append("\n").append(index++).append(". #").append(mission.id)
                    .append(" ").append(shorten(mission.goal, 120));
            if (mission.attempts > 0) {
                sb.append(" (retry ").append(mission.attempts + 1).append(")");
            }
            shown++;
        }
        if (snapshot.lastFinished != null) {
            sb.append("\nLast finished #").append(snapshot.lastFinished.id)
                    .append(": ").append(shorten(snapshot.lastFinished.goal, 120));
        }
        if (snapshot.active == null && snapshot.pending.isEmpty() && snapshot.lastFinished == null) {
            sb.append("\nNo missions yet.");
        }
        return sb.toString();
    }

    static void resetForTests() {
        synchronized (LOCK) {
            pending.clear();
            active = null;
            lastFinished = null;
            paused = false;
            requeueActiveOnFinish = false;
            nextId = 1;
        }
    }

    private static Mission createLocked(String goal, boolean planMode, String label, int attempts, String status) {
        String cleanGoal = clean(goal);
        if (cleanGoal.isEmpty()) {
            throw new IllegalArgumentException("Mission goal is empty.");
        }
        return new Mission(nextId++, cleanGoal, planMode, cleanLabel(label), attempts,
                System.currentTimeMillis(), 0L, 0L, status);
    }

    private static void ensureCapacity() {
        if (pending.size() >= MAX_PENDING) {
            throw new IllegalStateException("Mission queue is full (" + MAX_PENDING + " pending).");
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }

    private static String cleanLabel(String label) {
        String clean = clean(label);
        return clean.isEmpty() ? "mission" : clean;
    }

    private static String shorten(String text, int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        return text.substring(0, Math.max(0, max - 3)) + "...";
    }

    public static final class Snapshot {
        public final boolean paused;
        public final Mission active;
        public final List<Mission> pending;
        public final Mission lastFinished;

        private Snapshot(boolean paused, Mission active, List<Mission> pending, Mission lastFinished) {
            this.paused = paused;
            this.active = active;
            this.pending = Collections.unmodifiableList(pending);
            this.lastFinished = lastFinished;
        }
    }

    public static final class Mission {
        public final int id;
        public final String goal;
        public final boolean planMode;
        public final String label;
        public final int attempts;
        public final long createdAt;
        public final long startedAt;
        public final long finishedAt;
        public final String status;

        private Mission(int id, String goal, boolean planMode, String label, int attempts,
                        long createdAt, long startedAt, long finishedAt, String status) {
            this.id = id;
            this.goal = goal;
            this.planMode = planMode;
            this.label = label;
            this.attempts = attempts;
            this.createdAt = createdAt;
            this.startedAt = startedAt;
            this.finishedAt = finishedAt;
            this.status = status;
        }

        private Mission started(long now) {
            return new Mission(id, goal, planMode, label, attempts + 1, createdAt, now, 0L, "active");
        }

        private Mission finished(String status, long now) {
            String cleanStatus = clean(status);
            return new Mission(id, goal, planMode, label, attempts, createdAt, startedAt, now,
                    cleanStatus.isEmpty() ? "finished" : cleanStatus);
        }

        private Mission queued(String status) {
            return new Mission(id, goal, planMode, label, attempts, createdAt, 0L, 0L, status);
        }
    }
}
