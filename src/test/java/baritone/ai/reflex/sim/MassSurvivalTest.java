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

package baritone.ai.reflex.sim;

import org.junit.Test;

import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertTrue;

/**
 * Mass randomized survival — runs the REAL reflex engine across a huge space of randomized scenarios
 * with gear weighted toward BARE-HANDED (a real spawn, no advantage), varied mobs/terrain/time/hp/
 * hazards/effects. The point is honesty at scale: not a curated win, but "given a random situation,
 * how often does the rule engine keep the bot alive?" — with the failures clustered so the winnable
 * ones can be fixed.
 *
 * <p>Count is configurable: {@code -DmassN=200000}. Default is modest so the normal suite stays fast.
 * A detailed report (overall rate + per-condition survival + failure-cause histogram) is written to
 * {@code /tmp/mass_survival_report.txt}.
 *
 * <p>100% is NOT claimed or asserted: some random spawns are unwinnable for ANYONE (a creeper
 * detonating point-blank with no blocks, spawned mid-lava with no shore). The assertion floor catches
 * regressions; the report carries the real number and what kills the rest.
 */
public class MassSurvivalTest {

    private static final int TICKS = 300;

    @Test
    public void survivesTheRandomizedWorldAtScale() {
        int n = Integer.getInteger("massN", 25_000);
        long baseSeed = Long.getLong("massSeed", 1234567L);

        int survived = 0;
        // survival by single condition tag, and a histogram of death causes
        Map<String, int[]> byTag = new TreeMap<>(); // tag -> [survived, total]
        Map<String, AtomicInteger> deathCause = new TreeMap<>();

        for (int i = 0; i < n; i++) {
            Random r = new Random(baseSeed + i * 2654435761L);
            Scenario sc = randomScenario(r);
            SurvivalSim.Outcome o = sc.sim.run(TICKS);
            if (o.survived) {
                survived++;
            } else {
                deathCause.computeIfAbsent(o.cause, k -> new AtomicInteger()).incrementAndGet();
            }
            for (String tag : sc.tags) {
                int[] sv = byTag.computeIfAbsent(tag, k -> new int[2]);
                if (o.survived) {
                    sv[0]++;
                }
                sv[1]++;
            }
        }

        double rate = survived / (double) n;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("MASS SURVIVAL: %d/%d = %.2f%% over %d random scenarios (seed %d)%n",
                survived, n, rate * 100, n, baseSeed));
        sb.append("\n-- survival by condition (survivors/total) --\n");
        byTag.forEach((tag, sv) ->
                sb.append(String.format("  %-22s %6.2f%%  (%d/%d)%n",
                        tag, 100.0 * sv[0] / sv[1], sv[0], sv[1])));
        sb.append("\n-- death causes --\n");
        deathCause.forEach((c, cnt) -> sb.append(String.format("  %-14s %d%n", c, cnt.get())));
        String report = sb.toString();
        System.out.println(report);
        try {
            java.nio.file.Files.write(java.nio.file.Paths.get("/tmp/mass_survival_report.txt"), report.getBytes());
        } catch (Exception ignored) {
        }

        // Regression floor only (NOT a 100% claim): the random space includes genuinely unwinnable
        // spawns (5-mob swarms, point-blank creepers with no blocks, multi-debuff spawns) AND now
        // realistic terrain (walls/boxed lanes). The honest measured rate is ~89.9%; the floor sits a
        // couple points under it to catch a real survival-rule regression without flapping on RNG.
        assertTrue("mass survival regressed below floor: " + report, rate >= 0.88D);
    }

    // ---------------------------------------------------------------- random scenario

    private static final class Scenario {
        final SurvivalSim sim;
        final java.util.List<String> tags = new java.util.ArrayList<>();

        Scenario(SurvivalSim sim) {
            this.sim = sim;
        }
    }

    /** A fair random spawn: gear skewed POOR, random threats/terrain/effects. */
    private static Scenario randomScenario(Random r) {
        SurvivalSim s = new SurvivalSim();
        Scenario sc = new Scenario(s);

        // --- gear (weighted toward nothing — most random spawns are poor)
        double g = r.nextDouble();
        if (g < 0.45) {
            sc.tags.add("gear:none");                 // bare hands, no weapon
        } else if (g < 0.68) {
            s.weapon(5);
            sc.tags.add("gear:wood");
        } else if (g < 0.84) {
            s.weapon(3);
            sc.tags.add("gear:stone");
        } else if (g < 0.95) {
            s.weapon(2);
            sc.tags.add("gear:iron");
        } else {
            s.weapon(1);
            sc.tags.add("gear:diamond");
        }
        // armor: mostly none
        double a = r.nextDouble();
        s.armor(a < 0.5 ? 0 : a < 0.8 ? 4 + r.nextInt(5) : 8 + r.nextInt(13));
        // blocks: mostly few — bare spawns can't pillar
        double b = r.nextDouble();
        int blocks = b < 0.45 ? 0 : b < 0.75 ? r.nextInt(8) : 8 + r.nextInt(57);
        if (blocks > 0) {
            s.blocks(blocks);
        }
        sc.tags.add(blocks == 0 ? "blocks:none" : "blocks:some");
        if (r.nextDouble() < 0.5) {
            s.food(20, 2, 6);
            sc.tags.add("food:yes");
        } else {
            sc.tags.add("food:no");
        }
        if (r.nextDouble() < 0.2) {
            s.bucket();
        }
        double hp = 4 + r.nextInt(17);
        s.hp(hp);
        if (hp <= 8) {
            sc.tags.add("hp:low");
        }
        boolean night = r.nextDouble() < 0.45;
        if (night) {
            s.atNight();
            sc.tags.add("time:night");
        }

        // --- terrain posture
        double terr = r.nextDouble();
        if (terr < 0.10) {
            s.cornered();
            sc.tags.add("posture:cornered");
        } else if (terr < 0.15) {
            s.fullyBoxed();
            sc.tags.add("posture:boxed");
        }

        // --- terrain obstacles (walls): real worlds are NOT flat. A direction can be hazard-free yet
        // unwalkable for a raw sprint (a wall just past the look-ahead, a diagonal pinch, water). This is
        // exactly what wedged the old raw-input flee and what the pathfinding-first get-away must route
        // around. Exercising it at scale is what makes the survival number honest about real terrain.
        double wls = r.nextDouble();
        if (wls < 0.20) {
            int nWalls = 1 + r.nextInt(3);
            for (int w = 0; w < nWalls; w++) {
                s.wall(r.nextInt(8));
            }
            sc.tags.add("terrain:walls");
        } else if (wls < 0.27) {
            s.wallsExcept(r.nextInt(8)); // boxed in by terrain with a single lane out (cave/ravine)
            sc.tags.add("terrain:boxed");
        }

        // --- a terrain/effect hazard sometimes
        double hz = r.nextDouble();
        if (hz < 0.04) {
            s.inLava(2 + r.nextInt(3));
            sc.tags.add("hazard:lava");
        } else if (hz < 0.06) {
            s.drowning();
            sc.tags.add("hazard:drown");
        } else if (hz < 0.08) {
            s.onFire(r.nextBoolean());
            sc.tags.add("hazard:fire");
        } else if (hz < 0.10) {
            s.suffocating();
            sc.tags.add("hazard:suffocate");
        } else if (hz < 0.115) {
            s.encased();
            sc.tags.add("hazard:encased");
        } else if (hz < 0.13) {
            s.contactHazard();
            sc.tags.add("hazard:contact");
        }
        if (r.nextDouble() < 0.04) {
            s.slowed(1 + r.nextInt(2));
            sc.tags.add("effect:slow");
        }
        if (r.nextDouble() < 0.03) {
            s.weak();
            sc.tags.add("effect:weak");
        }
        if (r.nextDouble() < 0.03) {
            s.wither();
            sc.tags.add("effect:wither");
        }
        if (r.nextDouble() < 0.03) {
            s.blind();
            sc.tags.add("effect:blind");
        }

        // --- mobs: 0..5, random kinds/distances/angles
        int nMobs = r.nextInt(6);
        sc.tags.add("mobs:" + nMobs);
        int creepers = 0;
        for (int i = 0; i < nMobs; i++) {
            double dist = 3 + r.nextDouble() * 11; // 3..14
            double ang = r.nextDouble() * 360;
            int kind = r.nextInt(9);
            switch (kind) {
                case 0:
                case 1:
                    s.zombie(dist, ang);
                    break;
                case 2:
                    s.skeleton(dist, ang);
                    break;
                case 3:
                    s.creeper(dist, ang);
                    creepers++;
                    break;
                case 4:
                    s.spider(dist, ang);
                    break;
                case 5:
                    s.witch(dist, ang);
                    break;
                case 6:
                    s.caveSpider(dist, ang);
                    break;
                case 7:
                    s.phantom(dist, ang);
                    break;
                default:
                    s.zombie(dist, ang);
            }
        }
        if (creepers > 0) {
            sc.tags.add("has:creeper");
        }
        return sc;
    }
}
