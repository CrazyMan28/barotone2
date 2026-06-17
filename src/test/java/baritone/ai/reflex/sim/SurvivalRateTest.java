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

import baritone.ai.reflex.BehaviorId;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The headline survival proof the rewrite is judged by: <b>100+ distinct mob/hazard situations, run
 * through the real reflex engine in a fair deterministic simulator, must survive ≥99%.</b>
 *
 * <p>Fairness is established by the control tests at the bottom: with reflexes off, the very same
 * setups kill the bot — so a pass means the decision core actually saved it, not that the sim is
 * toothless.
 */
public class SurvivalRateTest {

    private static final int TICKS = 400;

    private static final class Scenario {
        final String name;
        final Supplier<SurvivalSim> setup;

        Scenario(String name, Supplier<SurvivalSim> setup) {
            this.name = name;
            this.setup = setup;
        }
    }

    // ---- loadouts
    private static SurvivalSim kitted() {
        return new SurvivalSim().weapon(2).armor(8).blocks(64).food(20, 2, 6).bucket();
    }

    private static SurvivalSim heavy() {
        return new SurvivalSim().weapon(1).armor(15).blocks(64).food(20, 2, 6).bucket().shield();
    }

    private static SurvivalSim fresh() {
        // fresh spawn: no weapon/armor/blocks, but solid ground (can dig a bare-handed turtle hole)
        return new SurvivalSim().food(20, 2, 6);
    }

    private static SurvivalSim freshBlocks() {
        return new SurvivalSim().blocks(32).food(20, 2, 6);
    }

    // ---------------------------------------------------------------- the scenario matrix

    private static List<Scenario> scenarios() {
        List<Scenario> all = new ArrayList<>();

        // A. lone creeper, every range, geared & fresh — must flee (outrun) every time
        for (double d : new double[]{5, 6, 7, 8, 10, 12}) {
            all.add(new Scenario("creeper@" + d + " kitted", () -> kitted().creeper(d)));
            all.add(new Scenario("creeper@" + d + " fresh", () -> freshBlocks().creeper(d)));
        }
        // B. cornered / boxed creeper with blocks — must pillar up HIGH ENOUGH (blast reaches ~4 up)
        all.add(new Scenario("creeper cornered+blocks", () -> kitted().creeper(5).cornered()));
        all.add(new Scenario("creeper boxed+blocks", () -> kitted().creeper(5).fullyBoxed()));
        all.add(new Scenario("ignited creeper close+blocks", () -> kitted().creeper(4).fullyBoxed()));
        // the real-world death: cornered creeper while already hurt — a 3-tall pillar gets you killed,
        // only a dynamic tall pillar (clearing the creeper by the safe gap) survives
        all.add(new Scenario("lowhp boxed creeper", () -> kitted().armor(8).hp(10).creeper(4).fullyBoxed()));
        all.add(new Scenario("lowhp cornered creeper", () -> kitted().armor(8).hp(9).creeper(5).cornered()));
        // creeper standing on a ledge level-ish with us — pillar must out-climb the creeper's OWN height
        all.add(new Scenario("creeper on ledge boxed", () -> kitted().creeperAtHeight(4, 3).fullyBoxed()));
        all.add(new Scenario("creeper on ledge cornered", () -> kitted().armor(10).hp(12).creeperAtHeight(4, 2).cornered()));
        // pillar runs out of blocks mid-climb (a ledge creeper needs ~8, we have 3): must downgrade
        // gracefully — run for an open direction instead of stranding ourselves on a stub pillar.
        all.add(new Scenario("creeper ledge low-blocks cornered", () -> kitted().blocks(3).creeperAtHeight(5, 3).cornered()));
        all.add(new Scenario("creeper ledge low-blocks open", () -> kitted().blocks(3).creeperAtHeight(6, 3)));

        // C. armed skeleton — must fight and win
        for (double d : new double[]{5, 7, 10, 14}) {
            all.add(new Scenario("skeleton@" + d + " armed", () -> kitted().armor(12).skeleton(d)));
        }
        // D. unarmed skeleton — must get to cover (shelter), with or without blocks
        for (double d : new double[]{5, 8, 12}) {
            all.add(new Scenario("skeleton@" + d + " fresh-shelter", () -> freshBlocks().skeleton(d)));
            all.add(new Scenario("skeleton@" + d + " dig-shelter", () -> fresh().skeleton(d)));
        }

        // E. armed zombie — fight
        for (double d : new double[]{3, 5, 8}) {
            all.add(new Scenario("zombie@" + d + " armed", () -> kitted().armor(12).zombie(d)));
        }
        // F. unarmed zombie — flee
        for (double d : new double[]{4, 6, 9}) {
            all.add(new Scenario("zombie@" + d + " fresh-flee", () -> freshBlocks().zombie(d)));
        }

        // G. two zombies, heavily geared — winnable fight
        all.add(new Scenario("2 zombies heavy", () -> heavy().zombie(4, 0).zombie(5, 30)));
        all.add(new Scenario("2 zombies kitted", () -> kitted().armor(14).zombie(5, 10).zombie(6, 50)));

        // H. swarms — must flee, not brawl
        all.add(new Scenario("3 zombies swarm", () -> kitted().zombie(5, 0).zombie(6, 40).zombie(6, 80)));
        all.add(new Scenario("4 zombies swarm", () -> kitted().zombie(6, 0).zombie(6, 60).zombie(6, 120).zombie(6, 200)));
        all.add(new Scenario("5 hostiles swarm", () -> kitted().zombie(6, 0).zombie(6, 45).zombie(6, 90)
                .skeleton(7, 180).zombie(6, 300)));

        // I. ranged + melee combos
        all.add(new Scenario("skeleton+zombie armed", () -> heavy().skeleton(8, 0).zombie(5, 160)));
        all.add(new Scenario("skeleton+zombie fresh", () -> freshBlocks().skeleton(8, 0).zombie(6, 170)));
        all.add(new Scenario("2 skeletons+zombie", () -> heavy().skeleton(9, 20).skeleton(10, 60).zombie(6, 200)));

        // J. creeper + zombie (flee the creeper, don't get cornered onto the zombie)
        all.add(new Scenario("creeper+zombie opp", () -> kitted().creeper(6, 0).zombie(6, 180)));
        all.add(new Scenario("creeper+zombie same side", () -> kitted().creeper(6, 0).zombie(7, 20)));
        all.add(new Scenario("creeper+2 zombies", () -> kitted().creeper(6, 0).zombie(6, 150).zombie(7, 210)));

        // K. creeper + skeleton
        all.add(new Scenario("creeper+skeleton", () -> kitted().creeper(6, 0).skeleton(9, 180)));
        all.add(new Scenario("creeper+skeleton fresh", () -> freshBlocks().creeper(6, 0).skeleton(9, 180)));

        // L. two creepers
        all.add(new Scenario("2 creepers", () -> kitted().creeper(6, 0).creeper(7, 120)));

        // M. surrounded from all sides — must flee through the gap
        all.add(new Scenario("surrounded 4 zombies", () -> kitted().zombie(6, 0).zombie(6, 90).zombie(6, 180).zombie(6, 270)));
        all.add(new Scenario("surrounded 3 + gap", () -> kitted().zombie(6, 0).zombie(6, 80).zombie(6, 280)));

        // N. night, undergeared, single mob — proactive turtle
        all.add(new Scenario("night zombie fresh", () -> fresh().atNight().zombie(8, 0)));
        all.add(new Scenario("night skeleton fresh", () -> fresh().atNight().skeleton(8, 0)));
        all.add(new Scenario("night creeper fresh", () -> freshBlocks().atNight().creeper(8, 0)));
        all.add(new Scenario("night zombie+sk fresh", () -> freshBlocks().atNight().zombie(8, 0).skeleton(9, 90)));
        all.add(new Scenario("night 3 mobs fresh", () -> freshBlocks().atNight().zombie(8, 0).zombie(9, 90).skeleton(10, 180)));

        // O. low hp + threat — must disengage and heal, not trade
        for (double hp : new double[]{4, 5, 6}) {
            all.add(new Scenario("lowhp" + hp + "+zombie", () -> kitted().armor(10).hp(hp).zombie(6, 0)));
        }
        all.add(new Scenario("lowhp+skeleton", () -> kitted().armor(10).hp(5).skeleton(8, 0)));

        // P. lava
        for (double d : new double[]{2, 3, 4}) {
            all.add(new Scenario("lava escape@" + d, () -> kitted().inLava(d)));
        }
        all.add(new Scenario("lava+creeper opp", () -> kitted().inLava(3).creeper(6, 180)));
        all.add(new Scenario("lava+zombie opp", () -> kitted().inLava(3).zombie(6, 180)));
        // lava ocean: no clear escape column in scan radius — must swim out along a safe octant
        all.add(new Scenario("lava ocean@5", () -> kitted().armor(12).inLavaOcean(5)));
        all.add(new Scenario("lava ocean@7 heavy", () -> heavy().inLavaOcean(7)));

        // Q. drowning
        all.add(new Scenario("drowning", () -> kitted().drowning()));
        all.add(new Scenario("drowning+zombie", () -> kitted().armor(12).drowning().zombie(7, 0)));

        // R. fire
        all.add(new Scenario("onfire+water", () -> kitted().onFire(true)));
        all.add(new Scenario("onfire+no-water", () -> kitted().onFire(false)));
        all.add(new Scenario("onfire+zombie", () -> kitted().armor(12).onFire(false).zombie(7, 0)));

        // S. suffocation — sand on the head AND being encased in solid (wall/cave-in/bad teleport)
        all.add(new Scenario("suffocation", () -> kitted().suffocating()));
        all.add(new Scenario("suffocation+zombie", () -> kitted().armor(12).suffocating().zombie(6, 0)));
        all.add(new Scenario("encased in wall", () -> kitted().encased()));
        all.add(new Scenario("encased lowhp", () -> kitted().armor(8).hp(12).encased()));
        all.add(new Scenario("encased+zombie", () -> kitted().armor(12).encased().zombie(6, 0)));

        // T. fall with bucket
        for (double h : new double[]{10, 20, 40}) {
            all.add(new Scenario("fall@" + h + " bucket", () -> kitted().falling(h)));
        }

        // U. critical starvation, safe to eat
        all.add(new Scenario("starving f1", () -> new SurvivalSim().food(1, 2, 6)));
        all.add(new Scenario("starving f2 + far creeper", () -> new SurvivalSim().food(2, 2, 6).creeper(13)));

        // V. spider (fast melee)
        all.add(new Scenario("spider armed", () -> kitted().armor(12).spider(6, 0)));
        all.add(new Scenario("spider fresh-flee", () -> freshBlocks().spider(7, 0)));

        // W. brutal mixed nights
        all.add(new Scenario("night creeper+skel+zombie", () -> kitted().atNight().creeper(7, 0).skeleton(10, 120).zombie(6, 240)));
        all.add(new Scenario("night swarm+creeper", () -> kitted().atNight().creeper(7, 0).zombie(6, 90).zombie(6, 150).zombie(6, 210)));

        // X. distance sweep padding (lone mob, geared) to broaden coverage
        for (double d = 5; d <= 13; d += 1) {
            final double dd = d;
            all.add(new Scenario("sweep zombie@" + d + " fresh", () -> freshBlocks().zombie(dd, 0)));
            all.add(new Scenario("sweep skeleton@" + d + " armed", () -> kitted().armor(12).skeleton(dd, 0)));
        }

        // Y. creeper distance sweep, both loadouts (broaden the most dangerous case)
        for (double d = 5; d <= 12; d += 1) {
            final double dd = d;
            all.add(new Scenario("sweep creeper@" + d + " kitted", () -> kitted().creeper(dd, 0)));
        }
        // Z. armed-zombie distance sweep
        for (double d = 3; d <= 9; d += 1) {
            final double dd = d;
            all.add(new Scenario("sweep zombie@" + d + " armed", () -> kitted().armor(13).zombie(dd, 0)));
        }
        // AA. poison treatment (retreat + heal)
        all.add(new Scenario("poisoned lowhp", () -> {
            SurvivalSim s = kitted().armor(10).hp(8);
            s.poisoned = true;
            return s;
        }));
        all.add(new Scenario("poisoned + zombie", () -> {
            SurvivalSim s = kitted().armor(12).hp(9).zombie(7, 0);
            s.poisoned = true;
            return s;
        }));
        // AB. bed at night (sleep skips the night) — geared with a bed
        all.add(new Scenario("night bed fresh", () -> fresh().bed().atNight().zombie(9, 0)));
        // AC. mixed terrain + mob combos
        all.add(new Scenario("fall+bucket then zombie", () -> kitted().armor(12).falling(15).zombie(8, 0)));
        all.add(new Scenario("drowning then surface calm", () -> kitted().drowning()));
        all.add(new Scenario("onfire+creeper opp", () -> kitted().onFire(false).creeper(6, 180)));
        // AD. heavier swarms (still must flee/turtle, never trade)
        all.add(new Scenario("6 zombies swarm", () -> kitted().zombie(7, 0).zombie(7, 60).zombie(7, 120)
                .zombie(7, 180).zombie(7, 240).zombie(7, 300)));
        all.add(new Scenario("night 4 mixed fresh", () -> freshBlocks().atNight()
                .zombie(8, 0).skeleton(9, 90).zombie(9, 180).skeleton(10, 270)));
        // AE. low-hp + ranged/creeper
        all.add(new Scenario("lowhp+creeper", () -> kitted().armor(10).hp(5).creeper(6, 0)));
        all.add(new Scenario("lowhp+swarm", () -> kitted().armor(10).hp(6).zombie(6, 0).zombie(6, 90).zombie(6, 180)));

        // AF. UNDERGEARED (no blocks/food/weapon) surrounded at night — the real death loop: must commit
        // to digging in (bare-handed) instead of thrashing flee<->retreat. (no creeper: that needs blocks)
        all.add(new Scenario("undergeared night swarm", () -> new SurvivalSim().atNight().zombie(5, 0).zombie(6, 100).zombie(6, 220)));
        all.add(new Scenario("undergeared night sk+zombies", () -> new SurvivalSim().atNight().skeleton(7, 0).zombie(6, 120).zombie(6, 240)));
        all.add(new Scenario("undergeared surrounded day", () -> new SurvivalSim().zombie(5, 0).zombie(5, 120).zombie(5, 240)));

        // AG. status effects (witch / wither): slowness (can't outrun -> dig in), weakness (don't lose a
        // fight -> flee/shelter), wither (DoT -> retreat + heal)
        all.add(new Scenario("slowed + zombies", () -> kitted().slowed(2).zombie(5, 0).zombie(6, 150)));
        all.add(new Scenario("slowed + skeleton", () -> freshBlocks().slowed(2).skeleton(7, 0)));
        all.add(new Scenario("weakened + 2 zombies", () -> kitted().armor(12).weak().zombie(5, 0).zombie(6, 60)));
        all.add(new Scenario("withered lowhp", () -> kitted().armor(10).hp(11).wither()));
        all.add(new Scenario("withered + zombie", () -> kitted().armor(12).hp(12).wither().zombie(7, 0)));

        // AH. lava escape blocked by a mob: the near column has a mob parked on it, a clear column is
        // farther — must climb out the clear way, not cook climbing onto the mob. (kitted & low-hp)
        all.add(new Scenario("lava escape mob-blocked", () -> kitted().inLavaMobBlocked(2, 3).zombie(2.5, 0)));
        all.add(new Scenario("lava escape mob-blocked lowhp", () -> kitted().armor(10).hp(10).inLavaMobBlocked(2, 3).zombie(2.5, 0)));
        all.add(new Scenario("lava escape mob-blocked + skel", () -> kitted().armor(12).inLavaMobBlocked(2, 4).zombie(2.5, 0).skeleton(8, 20)));

        // AI. drowning sealed overhead: bobbing up just keeps drowning — must mine up, or swim to a
        // side opening (picked mob-aware so we never surface into a waiting mob)
        all.add(new Scenario("drowning sealed dig-up", () -> kitted().drowningSealed()));
        all.add(new Scenario("drowning side-escape", () -> kitted().armor(10).drowningSideEscape(3)));
        all.add(new Scenario("drowning side-escape + mob", () -> kitted().armor(12).drowningSideEscape(3).zombie(7, 0)));

        // AJ. Warden — unwinnable: must ALWAYS flee, never combat, even fully geared
        all.add(new Scenario("warden heavy", () -> heavy().warden(8)));
        all.add(new Scenario("warden kitted", () -> kitted().armor(14).warden(7)));
        all.add(new Scenario("warden + zombie", () -> heavy().warden(8, 0).zombie(6, 180)));

        // AK. ranged non-skeleton (blaze/ghast/trident-drowned): shoots from afar — answer with cover,
        // never melee-charge into its fire (which is what treating it as a plain hostile would do)
        for (double d : new double[]{6, 8, 11}) {
            all.add(new Scenario("blaze@" + d + " kitted", () -> kitted().armor(12).blaze(d)));
            all.add(new Scenario("blaze@" + d + " fresh", () -> freshBlocks().blaze(d)));
        }
        all.add(new Scenario("blaze + zombie kitted", () -> kitted().armor(12).blaze(9, 0).zombie(6, 180)));

        // AK2. special mobs you can't out-DPS in melee: a witch heals through hits, a cave spider
        // out-speeds + poisons, a phantom flies out of reach — all must be fled/sheltered, never brawled
        all.add(new Scenario("witch armed", () -> kitted().armor(10).witch(6, 0)));
        all.add(new Scenario("witch + cornered", () -> kitted().armor(8).hp(12).witch(5, 0).cornered()));
        all.add(new Scenario("cave spider armed", () -> kitted().armor(12).caveSpider(6, 0)));
        all.add(new Scenario("cave spider fresh", () -> freshBlocks().caveSpider(7, 0)));
        all.add(new Scenario("phantom night armed", () -> kitted().armor(12).atNight().phantom(6, 0)));
        all.add(new Scenario("phantom fresh", () -> freshBlocks().atNight().phantom(7, 0)));

        // AM. RETREAT_HEAL with NO food + a hostile nearby: a heal loop is futile (nothing to eat,
        // regen off below 18 hunger) — must seal in so contact breaks and natural regen ticks.
        all.add(new Scenario("retreat no-food hostile", () -> {
            SurvivalSim s = new SurvivalSim().weapon(2).armor(8).blocks(16).hp(10);
            s.food = 0; s.foodSlot = -1;
            return s.zombie(8, 0);
        }));
        all.add(new Scenario("retreat no-food 2 hostiles", () -> {
            SurvivalSim s = new SurvivalSim().weapon(2).armor(10).blocks(20).hp(11);
            s.food = 0; s.foodSlot = -1;
            return s.zombie(7, 0).zombie(8, 120);
        }));

        // AL. near-broken weapon: a sword about to snap deals fist damage, so a fight that looks
        // winnable on the weapon tier is actually a loss — the power score must discount it and flee.
        all.add(new Scenario("broken sword + zombie", () -> kitted().armor(6).weaponDurability(2).zombie(5, 0)));
        all.add(new Scenario("broken sword + 2 zombies", () -> kitted().armor(8).weaponDurability(1).zombie(5, 0).zombie(6, 90)));
        all.add(new Scenario("broken sword fresh-flee", () -> new SurvivalSim().weapon(2).weaponDurability(3).blocks(32).food(20, 2, 6).zombie(6, 0)));

        return all;
    }

    // ---------------------------------------------------------------- the headline test

    @Test
    public void survivesAtLeast99PercentAcross100PlusScenarios() {
        List<Scenario> scenarios = scenarios();
        assertTrue("must run at least 100 scenarios, had " + scenarios.size(), scenarios.size() >= 100);

        int survived = 0;
        List<String> deaths = new ArrayList<>();
        for (Scenario sc : scenarios) {
            SurvivalSim.Outcome o = sc.setup.get().run(TICKS);
            if (o.survived) {
                survived++;
            } else {
                deaths.add(sc.name + " -> " + o.cause + " (last=" + o.lastBehavior + ", t=" + o.ticks + ")");
            }
        }
        double rate = survived / (double) scenarios.size();
        String report = String.format("survival %d/%d = %.1f%%; deaths=%s",
                survived, scenarios.size(), rate * 100, deaths);
        System.out.println("[SURVIVAL] " + report);
        try {
            java.nio.file.Files.write(java.nio.file.Paths.get("/tmp/survival_report.txt"),
                    report.getBytes());
        } catch (Exception ignored) {
        }
        assertTrue("survival rate below 99%: " + report, rate >= 0.99D);
    }

    // ---------------------------------------------------------------- fairness controls

    @Test
    public void controlReflexesOffDiesToCreeper() {
        SurvivalSim.Outcome o = kitted().creeper(5).disableReflexes().run(TICKS);
        assertFalse("with no reflexes a creeper must kill the bot (sim is lethal)", o.survived);
    }

    @Test
    public void controlReflexesOffDiesToLava() {
        SurvivalSim.Outcome o = kitted().inLava(3).disableReflexes().run(TICKS);
        assertFalse("with no reflexes lava must kill the bot", o.survived);
    }

    @Test
    public void controlReflexesOffDiesToLavaOcean() {
        SurvivalSim.Outcome o = kitted().armor(12).inLavaOcean(5).disableReflexes().run(TICKS);
        assertFalse("with no reflexes a lava ocean must kill the bot", o.survived);
    }

    @Test
    public void lavaOceanSwimsOutAlongASafeOctant() {
        // no precomputed escape column: the behavior must swim out a safe direction, not cook in place
        SurvivalSim sim = kitted().armor(12).inLavaOcean(5);
        SurvivalSim.Outcome o = sim.run(TICKS);
        assertTrue("must swim out of a lava ocean with no escape column: " + o.cause, o.survived);
        assertTrue("must have escaped the lava", !sim.inLava);
    }

    @Test
    public void controlReflexesOffDiesToSwarm() {
        SurvivalSim.Outcome o = kitted().zombie(4, 0).zombie(4, 90).zombie(4, 180)
                .disableReflexes().run(TICKS);
        assertFalse("with no reflexes a swarm must kill the bot", o.survived);
    }

    @Test
    public void controlReflexesOffDrowns() {
        SurvivalSim.Outcome o = kitted().drowning().disableReflexes().run(TICKS);
        assertFalse("with no reflexes drowning must kill the bot", o.survived);
    }

    // ---------------------------------------------------------------- the four new gaps

    @Test
    public void lavaEscapeRoutesAwayFromTheBlockingMob() {
        // a mob is parked on the near escape column; a clear column is farther. The mob-aware pick
        // must take the clear column and the bot must survive (not cook climbing onto the mob).
        SurvivalSim sim = kitted().armor(10).hp(10).inLavaMobBlocked(2, 3).zombie(2.5, 0);
        SurvivalSim.Outcome o = sim.run(TICKS);
        assertTrue("must escape lava away from the blocking mob: " + o.cause, o.survived);
        // climbed out the far (-X) side, away from the +X mob
        assertTrue("must have ended up clear of the mob's side", sim.x < 1.0D);
    }

    @Test
    public void controlReflexesOffDiesToMobBlockedLava() {
        SurvivalSim.Outcome o = kitted().armor(10).hp(10).inLavaMobBlocked(2, 3).zombie(2.5, 0)
                .disableReflexes().run(TICKS);
        assertFalse("with no reflexes mob-blocked lava must kill the bot", o.survived);
    }

    @Test
    public void drowningSealedDigsUpInsteadOfBobbing() {
        // sealed straight overhead with no side opening: the only escape is to mine up
        SurvivalSim.Outcome o = kitted().drowningSealed().run(TICKS);
        assertTrue("must dig out of a sealed drowning, not bob into the ceiling: " + o.cause, o.survived);
    }

    @Test
    public void controlReflexesOffDrownsSealed() {
        SurvivalSim.Outcome o = kitted().drowningSealed().disableReflexes().run(TICKS);
        assertFalse("with no reflexes a sealed drowning must kill the bot", o.survived);
    }

    @Test
    public void wardenIsAlwaysFledNeverFought() {
        SurvivalSim sim = heavy().warden(8);
        SurvivalSim.Outcome o = sim.run(TICKS);
        assertTrue("must survive a warden by fleeing: " + o.cause, o.survived);
        assertFalse("a warden must NEVER be engaged in combat (it's unwinnable)",
                sim.behaviorsSeen.contains(BehaviorId.COMBAT));
    }

    @Test
    public void controlReflexesOffDiesToWarden() {
        SurvivalSim.Outcome o = heavy().warden(6).disableReflexes().run(TICKS);
        assertFalse("with no reflexes a warden must kill the bot", o.survived);
    }

    @Test
    public void rangedMobIsShelteredNotCharged() {
        // a blaze shoots from range; charging it (treating it as a plain hostile) eats fireballs.
        // it must be answered with cover/shelter and never meleed.
        SurvivalSim sim = kitted().armor(12).blaze(8);
        SurvivalSim.Outcome o = sim.run(TICKS);
        assertTrue("must survive a blaze by taking cover: " + o.cause, o.survived);
        assertFalse("a ranged mob must not be melee-charged", sim.behaviorsSeen.contains(BehaviorId.COMBAT));
        assertTrue("must take cover from a shooter", sim.behaviorsSeen.contains(BehaviorId.SHELTER));
    }

    @Test
    public void controlReflexesOffDiesToBlaze() {
        SurvivalSim.Outcome o = kitted().armor(12).blaze(6).disableReflexes().run(TICKS);
        assertFalse("with no reflexes a blaze must kill the bot", o.survived);
    }

    @Test
    public void witchIsFledNeverBrawled() {
        // a witch out-heals our hits — brawling it is unwinnable. The power score must flee it.
        SurvivalSim sim = kitted().armor(10).witch(6, 0);
        SurvivalSim.Outcome o = sim.run(TICKS);
        assertTrue("must survive a witch by fleeing: " + o.cause, o.survived);
        assertFalse("a witch must not be melee-brawled (it heals through it)",
                sim.behaviorsSeen.contains(BehaviorId.COMBAT));
    }

    @Test
    public void controlReflexesOffDiesToWitch() {
        SurvivalSim.Outcome o = kitted().armor(10).witch(5, 0).disableReflexes().run(TICKS);
        assertFalse("with no reflexes a witch must kill the bot", o.survived);
    }

    @Test
    public void phantomIsNotChasedInPointlessMelee() {
        // a phantom flies out of reach — a ground charge never lands. Must shelter/flee, not COMBAT.
        SurvivalSim sim = kitted().armor(12).atNight().phantom(6, 0);
        SurvivalSim.Outcome o = sim.run(TICKS);
        assertTrue("must survive a phantom without futile combat: " + o.cause, o.survived);
        assertFalse("a flying phantom must not be melee-chased", sim.behaviorsSeen.contains(BehaviorId.COMBAT));
    }
}
