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
 * The pure decision policy for spinning up — and standing down — the cooperative LLM "survival
 * agent" when the rule-based reflex is "having a bad time" ({@link SurvivalBrain#inDistress()}).
 *
 * <p>The survival agent NEVER fights the reflex for control: the reflex (a priority-10 temporary
 * Baritone process) keeps tick-level command of the body the whole time and the survival agent works
 * through the same {@code wait_until_idle} / pathing layer every other agent does. This class is only
 * the <em>policy</em> — when may we start one, and when has the danger resolved — kept Minecraft-free
 * so the conditions (debounce, cooldown, already-running, no-provider, disabled) are unit-tested
 * without a live LLM or a running world. The adapter ({@code ReflexProcess}) supplies the live inputs
 * and performs the off-game-thread start.
 */
public final class SurvivalEscalation {

    private SurvivalEscalation() {}

    /**
     * Should we START a survival escalation right now? ALL conditions must hold:
     * <ul>
     *   <li>{@code distress} — the reflex is currently exhausted + still endangered;</li>
     *   <li>{@code sustainedTicks >= requiredSustainTicks} — distress has held continuously for a
     *       debounce window, so a one-tick blip can't trigger an LLM round-trip;</li>
     *   <li>{@code !alreadyRunning} — never stack a second survival agent on a running one;</li>
     *   <li>{@code providerConfigured} — an LLM provider (Mistral key or Ollama model) is set;</li>
     *   <li>{@code enabled} — the {@code aiSurvivalEscalation} setting is on;</li>
     *   <li>{@code cooldownOver} — enough time has elapsed since the last escalation.</li>
     * </ul>
     */
    public static boolean shouldEscalate(boolean distress, int sustainedTicks, int requiredSustainTicks,
                                         boolean alreadyRunning, boolean providerConfigured,
                                         boolean enabled, boolean cooldownOver) {
        return distress
                && sustainedTicks >= Math.max(1, requiredSustainTicks)
                && !alreadyRunning
                && providerConfigured
                && enabled
                && cooldownOver;
    }

    /**
     * Has the survival situation RESOLVED enough to resume the original goal? The danger is over when
     * the reflex is no longer in distress AND no hostile has been within perception for a sustained
     * window (so we don't hand control straight back into a mob that's about to re-engage). The
     * survival agent calling {@code done} is a separate, equally-valid resolve path the adapter also
     * honors — this is the autonomous "it got quiet" path.
     *
     * @param distress         the reflex is still exhausted/endangered
     * @param hostilesNear     a hostile is within perception this tick
     * @param clearTicks       consecutive ticks with no distress AND no hostile near
     * @param requiredClearTicks how long that calm must hold before we call it resolved
     */
    public static boolean isResolved(boolean distress, boolean hostilesNear,
                                     int clearTicks, int requiredClearTicks) {
        if (distress || hostilesNear) {
            return false;
        }
        return clearTicks >= Math.max(1, requiredClearTicks);
    }
}
