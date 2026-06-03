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
import java.util.List;
import java.util.Locale;

/**
 * Maps a player's plain-English tuning request (forwarded verbatim by the AI's {@code tune} tool)
 * to deterministic setting-cluster changes. Pure and static so it is unit-testable without
 * Minecraft; the actual setting application lives in {@link BaritoneTools}.
 */
public final class TuneIntents {

    /**
     * Recognized tuning intents, in the canonical order they are applied when several match.
     */
    public enum Intent {
        /** Restore the look/aim cluster to working defaults and allow breaking ("head not turning"). */
        FIX_AIM,
        /** Apply the undercover stealth profile (visible, human-looking interactions). */
        STEALTH,
        /** Light look smoothing that never blocks breaking. */
        SMOOTH,
        /** Instant, jitter-free aiming. */
        SNAPPY,
        /** Minimum delay between block breaks. */
        BREAK_FASTER,
        /** Slow, casual block-break pacing. */
        BREAK_SLOWER,
        /** allowBreak = true. */
        ALLOW_BREAK,
        /** allowBreak = false. */
        NO_BREAK,
        /** reflexesEnabled = true (survival guardian on). */
        REFLEX_ON,
        /** reflexesEnabled = false. */
        REFLEX_OFF
    }

    // Phrase tables, all lower-case. Multi-word phrases are matched as substrings, so they also
    // catch variants ("it just won't break any blocks" matches "won't break").
    private static final String[] NO_BREAK_PHRASES = {
            "dont break", "don't break", "do not break", "stop breaking", "no breaking",
            "disable break", "never break", "without breaking"
    };
    private static final String[] ALLOW_BREAK_PHRASES = {
            "allow break", "let it break", "enable break", "can break again", "breaking allowed"
    };
    private static final String[] BREAK_FASTER_PHRASES = {
            "break faster", "break quicker", "break fast", "faster break", "mine faster",
            "dig faster", "faster mining", "speed up break", "speed up mining"
    };
    private static final String[] BREAK_SLOWER_PHRASES = {
            "break slower", "break slow", "slower break", "mine slower", "slow down break",
            "slower mining"
    };
    private static final String[] FIX_AIM_PHRASES = {
            "head", "not turning", "wont turn", "won't turn", "cant turn", "can't turn",
            "aim", "stuck", "cant break", "can't break", "wont break", "won't break",
            "cannot break", "not breaking", "fix", "reset look", "default look", "normal look",
            "back to normal", "vanilla"
    };
    private static final String[] STEALTH_PHRASES = {
            "stealth", "undercover", "sneak", "legit", "human", "anticheat", "anti-cheat",
            "anti cheat", "suspicious", "ban", "hide", "low profile", "look real"
    };
    private static final String[] SMOOTH_PHRASES = {
            "smooth", "gentle", "jitter", "jerky", "twitchy", "less robotic"
    };
    private static final String[] SNAPPY_PHRASES = {
            "snappy", "instant", "fast turn", "quick turn", "fast look", "responsive", "react faster"
    };
    private static final String[] REFLEX_OFF_PHRASES = {
            "reflex off", "reflexes off", "ignore mobs", "dont defend", "don't defend", "do not defend",
            "stop defending", "no reflexes", "disable reflex", "stop eating", "dont eat", "don't eat"
    };
    private static final String[] REFLEX_ON_PHRASES = {
            "reflex on", "reflexes on", "be careful", "stay safe", "stay alive", "protect yourself",
            "defend yourself", "survival mode", "keep yourself alive", "watch out"
    };

    private TuneIntents() {
    }

    /**
     * @param request the player's words, any case
     * @return matched intents in canonical apply order; empty if nothing matched
     */
    public static List<Intent> match(String request) {
        List<Intent> out = new ArrayList<>();
        if (request == null) {
            return out;
        }
        String r = request.toLowerCase(Locale.ROOT);
        // Specific break on/off/pacing requests are checked before the generic FIX_AIM complaint
        // phrases so "stop breaking" never reads as a "won't break" complaint.
        boolean noBreak = containsAny(r, NO_BREAK_PHRASES);
        boolean allowBreak = !noBreak && containsAny(r, ALLOW_BREAK_PHRASES);
        boolean breakFaster = containsAny(r, BREAK_FASTER_PHRASES);
        boolean breakSlower = !breakFaster && containsAny(r, BREAK_SLOWER_PHRASES);
        boolean fixAim = !noBreak && containsAny(r, FIX_AIM_PHRASES);
        boolean stealth = containsAny(r, STEALTH_PHRASES);
        boolean smooth = containsAny(r, SMOOTH_PHRASES);
        boolean snappy = !smooth && containsAny(r, SNAPPY_PHRASES);
        boolean reflexOff = containsAny(r, REFLEX_OFF_PHRASES);
        boolean reflexOn = !reflexOff && containsAny(r, REFLEX_ON_PHRASES);

        if (fixAim) {
            out.add(Intent.FIX_AIM);
        }
        if (stealth) {
            out.add(Intent.STEALTH);
        }
        if (smooth) {
            out.add(Intent.SMOOTH);
        }
        if (snappy) {
            out.add(Intent.SNAPPY);
        }
        if (breakFaster) {
            out.add(Intent.BREAK_FASTER);
        }
        if (breakSlower) {
            out.add(Intent.BREAK_SLOWER);
        }
        if (allowBreak) {
            out.add(Intent.ALLOW_BREAK);
        }
        if (noBreak) {
            out.add(Intent.NO_BREAK);
        }
        if (reflexOn) {
            out.add(Intent.REFLEX_ON);
        }
        if (reflexOff) {
            out.add(Intent.REFLEX_OFF);
        }
        return out;
    }

    /**
     * One-line help describing what {@code tune} understands; returned when nothing matches.
     */
    public static String help() {
        return "No tuning intent recognized. I understand requests like: 'head not turning / won't break blocks / fix aim' "
                + "(restores working look+break settings), 'be stealthy/undercover/legit', 'smoother look', 'snappy/instant aim', "
                + "'break faster' / 'break slower', 'allow breaking' / 'don't break blocks', "
                + "'be careful / protect yourself' (reflexes on) / 'ignore mobs / reflexes off'. "
                + "For anything else use list_settings (searches names AND docs) + set_setting.";
    }

    private static boolean containsAny(String haystack, String[] phrases) {
        for (String p : phrases) {
            if (haystack.contains(p)) {
                return true;
            }
        }
        return false;
    }
}
