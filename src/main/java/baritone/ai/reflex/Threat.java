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

package baritone.ai.reflex;

/**
 * One detected danger, scored 0-100. Detectors produce these; the arbiter picks the worst and
 * maps it to a behavior.
 */
public final class Threat {

    public final ThreatType type;
    /** 0-100; 100 = actively dying. The arbiter may scale this by current hp before comparing. */
    public int severity;
    /** The mob behind the threat, if any. */
    public final MobInfo source;

    public Threat(ThreatType type, int severity, MobInfo source) {
        this.type = type;
        this.severity = severity;
        this.source = source;
    }

    public Threat(ThreatType type, int severity) {
        this(type, severity, null);
    }

    @Override
    public String toString() {
        return type + "(" + severity + ")";
    }
}
