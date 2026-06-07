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

import java.util.Locale;

/** The reflex behaviors a threat can be answered with. */
public enum BehaviorId {
    NONE("idle"),
    ESCAPE_LAVA("escaping lava"),
    SURFACE("surfacing for air"),
    DIG_OUT("digging out"),
    EXTINGUISH_FIRE("extinguishing fire"),
    ANTI_FALL("breaking a fall"),
    FLEE("fleeing danger"),
    COMBAT("fighting back"),
    RETREAT_HEAL("retreating to heal"),
    EAT("eating");

    /** Human description, used by ReflexLog / chat / the process display name. */
    public final String describe;

    BehaviorId(String describe) {
        this.describe = describe;
    }

    public String lower() {
        return describe.toLowerCase(Locale.ROOT);
    }
}
