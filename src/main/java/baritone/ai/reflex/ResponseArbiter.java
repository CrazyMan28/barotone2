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
 * Back-compat alias for the survival decision core. The single-threat arbiter was rewritten into the
 * holistic {@link SurvivalBrain}; this thin subclass keeps the original name (and its hard-won
 * regression test-suite, {@code ResponseArbiterTest}) pointed at the new brain, so every proven
 * single-threat semantic stays guarded after the rewrite.
 *
 * @deprecated prefer {@link SurvivalBrain} directly in new code.
 */
@Deprecated
public final class ResponseArbiter extends SurvivalBrain {
}
