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

/** The arbiter's verdict for one tick: which behavior runs, against what, and how. */
public final class ResponsePlan {

    public static final ResponsePlan NONE = new ResponsePlan(BehaviorId.NONE, null, FleeMode.NORMAL, -1);

    public final BehaviorId behavior;
    /** The threat that engaged the behavior (null for NONE). */
    public final Threat cause;
    public final FleeMode fleeMode;
    /** Combat target entity id (-1 = none). */
    public final int targetEntityId;

    public ResponsePlan(BehaviorId behavior, Threat cause, FleeMode fleeMode, int targetEntityId) {
        this.behavior = behavior;
        this.cause = cause;
        this.fleeMode = fleeMode == null ? FleeMode.NORMAL : fleeMode;
        this.targetEntityId = targetEntityId;
    }

    public ResponsePlan(BehaviorId behavior, Threat cause) {
        this(behavior, cause, FleeMode.NORMAL, cause != null && cause.source != null ? cause.source.entityId : -1);
    }
}
