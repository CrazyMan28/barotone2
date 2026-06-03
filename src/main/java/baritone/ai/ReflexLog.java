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
 * Small static ring buffer of the most recent survival-reflex events. The AI sees these in
 * {@code get_state} ("recent_reflexes") so it stays aware of what the guardian did on its behalf
 * without the reflexes ever waiting on the model.
 */
public final class ReflexLog {

    private static final int LIMIT = 8;
    private static final Object LOCK = new Object();
    private static final List<Entry> entries = new ArrayList<>();

    private ReflexLog() {
    }

    public static void record(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        synchronized (LOCK) {
            entries.add(0, new Entry(message.trim(), System.currentTimeMillis()));
            while (entries.size() > LIMIT) {
                entries.remove(entries.size() - 1);
            }
        }
    }

    /** @return newest-first, each formatted as "<message> (<age>s ago)" */
    public static List<String> recent(int max) {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < entries.size() && i < Math.max(0, max); i++) {
                Entry e = entries.get(i);
                out.add(e.message + " (" + Math.max(0, (now - e.atMillis) / 1000) + "s ago)");
            }
            return Collections.unmodifiableList(out);
        }
    }

    static void resetForTests() {
        synchronized (LOCK) {
            entries.clear();
        }
    }

    private static final class Entry {
        final String message;
        final long atMillis;

        Entry(String message, long atMillis) {
            this.message = message;
            this.atMillis = atMillis;
        }
    }
}
