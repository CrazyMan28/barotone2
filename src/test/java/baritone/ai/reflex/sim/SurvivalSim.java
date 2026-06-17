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
import baritone.ai.reflex.BlockPosSpec;
import baritone.ai.reflex.EscapeColumns;
import baritone.ai.reflex.FleeMode;
import baritone.ai.reflex.MobInfo;
import baritone.ai.reflex.ReflexEngine;
import baritone.ai.reflex.ReflexTuning;
import baritone.ai.reflex.WorldSnapshot;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A deterministic, Minecraft-free survival simulator. Each tick it renders the sim world into a
 * {@link WorldSnapshot}, asks the real {@link ReflexEngine} what to do, then applies that decision's
 * <em>physical consequence</em> back into the world (flee → distance opens, pillar → out of reach,
 * shelter → sealed in, combat → mob takes damage, eat → food/regen, escape-lava → out of the lava…)
 * and steps the threats (mobs close in and hit, lava/drown/fire/fall tick down hp).
 *
 * <p>It is intentionally <em>fair, not rigged</em>: doing nothing in any of these scenarios kills the
 * bot (see the control tests), and the damage/speed model is tuned so a <em>correct</em> reflex
 * decision survives while a wrong one dies. That is what makes "≥99% survival across 100+ scenarios"
 * a real measurement of the decision core, not a tautology.
 *
 * <p>The grain is the decision layer (which behavior, against the whole picture) — exactly what the
 * {@link baritone.ai.reflex.SurvivalBrain} rewrite owns. The behaviors' action emission is unit-tested
 * separately ({@code EngineParityTest} et al.); here we model each behavior's outcome.
 */
public final class SurvivalSim {

    // ---- speed / damage model (blocks per tick, hp). Bot outruns every mob; doing nothing dies.
    private static final double BOT_SPEED = 0.32D;
    private static final double PILLAR_RATE = 0.34D;
    private static final double LAVA_DMG = 0.45D;     // per tick while standing in lava
    private static final double FIRE_DMG = 0.12D;     // per tick on fire (out of lava)
    private static final double DROWN_DMG = 0.5D;     // per tick once air is gone
    private static final int CREEPER_FUSE = 30;       // ticks within blast range before it detonates
    private static final double CREEPER_BLAST = 45D;  // point-blank damage (lethal even geared)
    private static final double CREEPER_RANGE = 3.0D; // fuse range
    private static final double CREEPER_STOP = 0.6D;  // a creeper walks right up onto the player
    /** A creeper blast reaches ~4 blocks UP — a 3-tall pillar still eats it (the real-world death). */
    private static final double BLAST_VERTICAL = 4.5D;
    private static final int SHELTER_BUILD_TICKS = 22; // ticks to seal a turtle hole / wall in
    private static final int WALL_BUILD_TICKS = 12;
    // a mob loses the bot once it has been outrun (broken contact) or sealed away long enough — the
    // reflex's contract is "break contact"; crediting that as escape is what makes flee/shelter
    // measurable. Both thresholds are conservative (the bot must really get clear).
    private static final double DEAGGRO_RANGE = 11D;   // ~the flee release distance: contact broken
    private static final int DEAGGRO_TICKS = 12;       // sustained beyond range => lost the target
    private static final int UNREACHABLE_TICKS = 40;   // sealed/pillared away this long => wanders off

    public final ReflexTuning tuning = new ReflexTuning();
    private final ReflexEngine engine = new ReflexEngine();
    private boolean reflexesOff; // control: force NONE to prove the sim is genuinely lethal

    // ---- bot state
    public double x, y, z;
    public double hp = 20, maxHp = 20;
    public int food = 20;
    public int air = 300;
    public boolean onFire;
    public boolean inLava;
    public boolean underWater;
    public boolean poisoned;
    public int slownessLevel;   // can't outrun mobs while slowed
    public boolean weakened;    // melee does far less
    public boolean withered;    // damage-over-time
    public boolean blinded;     // Blindness/Darkness: can't see threats -> kite/flee blind fails
    private int witherTicks;
    public boolean falling;
    public double fallDistance;
    public boolean suffocating;
    public boolean encased;     // head inside ANY solid block (wall/cave-in/bad teleport)
    private boolean headFreed;  // encased: head block broken (~tick 8) -> suffocation damage stops
    public boolean night;
    public int ticksSinceHurt = Integer.MAX_VALUE;
    /** Standing on a contact-damage block (cactus/magma/sweet-berry): ticks damage until we step off. */
    public boolean contactHazard;
    private double contactHazardMoved; // how far we've moved off the hazard cell

    // gear / resources
    public int weaponSlot = -1, weaponTier = -1;
    public int weaponDurabilityPercent = -1; // -1 = unbreakable/none; near 0 = about to snap
    public int armor;
    public boolean shield;
    public int blocks;          // blocks on hand
    public int foodSlot = -1, foodNutrition = -1;
    public int bucketSlot = -1;
    public int bedSlot = -1;

    // terrain
    public boolean[] octantSafe = {true, true, true, true, true, true, true, true};
    public boolean digDownSafe = true; // can always dig a bare-handed turtle hole on solid ground
    public BlockPosSpec lavaEscape;
    /**
     * Lava ocean: no precomputed escape column within scan radius ({@code lavaEscape == null}). The
     * only way out is to swim along a safe octant until reaching the edge {@code lavaOceanEdge} blocks
     * away. Modeled as a moving target so the behavior's safe-octant fallback is fairly exercised.
     */
    private double lavaOceanEdge = -1;
    private double lavaSwum;
    /** Edge distance of the plain lava pool (for the safe-octant swim fallback when the column is blocked). */
    private double lavaEscapeDist = -1;
    /** Faithful EscapeLavaBehavior stall tracking: distance to the chosen column last tick + stall count. */
    private double lavaLastDistToColumn = Double.MAX_VALUE;
    private int lavaStalledTicks;
    public BlockPosSpec nearestWater;
    public BlockPosSpec surfaceEscape;
    public boolean surfaceSealed;
    /**
     * Candidate escape columns the world offers (out of lava / up from drowning). When set, the sim
     * re-picks the actual escape column mob-aware each tick via the real {@link EscapeColumns} — the
     * same selection the adapter does — so "a mob blocks the escape" is a fair test of the real logic.
     */
    private List<BlockPosSpec> lavaCandidates;
    private List<BlockPosSpec> surfaceCandidates;

    // internal episode bookkeeping
    private int attackCooldown;
    private int eatProgress;
    private int shelterProgress;
    private int wallProgress;
    private int surfaceProgress;
    private int digOutProgress;
    private int fireFightProgress;
    private boolean enclosed;     // sealed in a shelter / bunker — mobs can't reach, arrows blocked
    private double pillarBaseY;
    private boolean pillaring;
    private long gameTime;

    private final List<SimMob> mobs = new ArrayList<>();
    private final List<SimMob> walledOff = new ArrayList<>();

    /** Every distinct behavior the engine chose across the run — lets tests assert "never combat a warden". */
    public final java.util.EnumSet<BehaviorId> behaviorsSeen = java.util.EnumSet.noneOf(BehaviorId.class);

    public boolean debug;
    public final List<String> log = new ArrayList<>();

    public static final class SimMob {
        int id;
        String type;
        boolean creeper, skeleton, hostile;
        boolean ranged;      // blaze/ghast/trident-drowned: shoots from range (treated like a skeleton)
        boolean unkillable;  // warden: never winnable — must always flee
        double x, y, z;
        double hp = 20;
        double speed = 0.23D;
        double meleeDmg = 3.0D;
        double range = 1.8D;     // melee reach (skeletons override with shoot range)
        int cooldown;
        int fuse;
        int ticksFar;            // consecutive ticks beyond DEAGGRO_RANGE
        int ticksUnreachable;    // consecutive ticks unable to reach the bot
        double lastDist = Double.MAX_VALUE;
        boolean reached;         // ever got into attack range (for scoring "did it touch us")
        double selfHeal;         // witch: regenerates this much hp/tick (can't out-DPS it in melee)
        boolean poisonOnHit;     // cave spider: applies poison when it lands a hit
        boolean flying;          // phantom: out of ground-melee reach, a charge never connects
    }

    public SurvivalSim disableReflexes() {
        reflexesOff = true;
        return this;
    }

    // ---------------------------------------------------------------- scenario setup helpers

    public SurvivalSim weapon(int tier) {
        this.weaponSlot = 0;
        this.weaponTier = tier;
        return this;
    }

    /** A weapon with this remaining durability percent (e.g. 2 = about to snap, dealing fist damage). */
    public SurvivalSim weaponDurability(int percent) {
        this.weaponDurabilityPercent = percent;
        return this;
    }

    public SurvivalSim armor(int points) {
        this.armor = points;
        return this;
    }

    public SurvivalSim shield() {
        this.shield = true;
        return this;
    }

    public SurvivalSim blocks(int n) {
        this.blocks = n;
        return this;
    }

    public SurvivalSim food(int level, int slot, int nutrition) {
        this.food = level;
        this.foodSlot = slot;
        this.foodNutrition = nutrition;
        return this;
    }

    public SurvivalSim bucket() {
        this.bucketSlot = 3;
        return this;
    }

    public SurvivalSim bed() {
        this.bedSlot = 4;
        return this;
    }

    public SurvivalSim hp(double v) {
        this.hp = v;
        return this;
    }

    public SurvivalSim atNight() {
        this.night = true;
        return this;
    }

    /** Box the bot in: only one direction remains open. */
    public SurvivalSim cornered() {
        for (int i = 0; i < octantSafe.length; i++) {
            octantSafe[i] = false;
        }
        octantSafe[0] = true;
        return this;
    }

    /** No safe direction at all — running is impossible; only pillaring up or bunkering survives. */
    public SurvivalSim fullyBoxed() {
        for (int i = 0; i < octantSafe.length; i++) {
            octantSafe[i] = false;
        }
        return this;
    }

    /**
     * In a lava pool with stand-able non-lava columns at the pool edge {@code escapeDist} blocks away
     * in EVERY direction (a faithful "fell in the middle of a pool" — the real {@code findLavaEscape}
     * ring-scans 8 directions). The candidates are re-picked mob-aware each tick via the real
     * {@link EscapeColumns} (exactly like the adapter), and the sim's escape routes through the same
     * mob-blocked / stalled → safe-octant fallback the real {@link baritone.ai.reflex.behavior.EscapeLavaBehavior}
     * uses — so the plain-lava outcome is not a simplified shortcut but the shipped behavior under test.
     */
    public SurvivalSim inLava(double escapeDist) {
        this.inLava = true;
        this.lavaCandidates = new ArrayList<>();
        for (int dir = 0; dir < 8; dir++) {
            double angle = dir * Math.PI / 4D;
            int cx = (int) Math.round(x + Math.cos(angle) * escapeDist);
            int cz = (int) Math.round(z + Math.sin(angle) * escapeDist);
            this.lavaCandidates.add(new BlockPosSpec(cx, (int) y, cz));
        }
        this.lavaEscapeDist = escapeDist;
        return this;
    }

    /**
     * In a lava ocean with NO clear escape column in scan radius ({@code lavaEscape == null}). The
     * behavior must fall back to swimming along a safe octant; the bot reaches solid ground after
     * {@code edgeDist} blocks of swimming. Without that fallback the bot cooks in place (control).
     */
    public SurvivalSim inLavaOcean(double edgeDist) {
        this.inLava = true;
        this.lavaEscape = null;
        this.lavaOceanEdge = edgeDist;
        this.lavaSwum = 0;
        return this;
    }

    /**
     * In lava with TWO stand-able columns: a near one toward +X (where a mob will be placed) and a
     * farther clear one toward -X. The old "nearest column" pick climbs out onto the mob (and cooks
     * while it eats hits); the mob-aware pick takes the clear column. The sim re-selects each tick
     * via the real {@link EscapeColumns}, so this measures the real selection rule.
     */
    public SurvivalSim inLavaMobBlocked(double nearDist, double farDist) {
        this.inLava = true;
        this.lavaCandidates = new ArrayList<>();
        this.lavaCandidates.add(new BlockPosSpec((int) Math.round(x + nearDist), (int) y, (int) z));
        this.lavaCandidates.add(new BlockPosSpec((int) Math.round(x - farDist), (int) y, (int) z));
        return this;
    }

    public SurvivalSim drowning() {
        this.underWater = true;
        this.air = 40;
        return this;
    }

    /**
     * Drowning sealed directly overhead (a cave ceiling / ice) with no side opening: bobbing up just
     * keeps drowning, so the only way out is to MINE up. Without the surface-escape fix the bot holds
     * JUMP into the ceiling and drowns; with it, it digs the shaft and climbs out.
     */
    public SurvivalSim drowningSealed() {
        this.underWater = true;
        this.air = 40;
        this.surfaceSealed = true;
        return this;
    }

    /**
     * Drowning with the only safe surface to the side ({@code escapeDist} away) — straight up is
     * sealed, so bobbing fails; the bot must swim to the open column. Models a mob potentially
     * waiting at one opening (the column is picked mob-aware).
     */
    public SurvivalSim drowningSideEscape(double escapeDist) {
        this.underWater = true;
        this.air = 40;
        this.surfaceSealed = true;
        this.surfaceCandidates = new ArrayList<>();
        this.surfaceCandidates.add(new BlockPosSpec((int) Math.round(x + escapeDist), (int) y, (int) z));
        return this;
    }

    /** A Warden: unwinnable — must always flee, never combat, regardless of gear. */
    public SurvivalSim warden(double dist) {
        return warden(dist, 0);
    }

    public SurvivalSim warden(double dist, double angleDeg) {
        SurvivalSim s = addMob("warden", dist, angleDeg, false, false, true, 0.21D, 12.0D, 1.8D);
        SimMob m = mobs.get(mobs.size() - 1);
        m.unkillable = true;
        m.hp = 500; // can't be killed in the fight window — fleeing is the only survival
        return s;
    }

    /** A ranged non-skeleton (blaze/ghast/trident-drowned): shoots from afar — answer with cover. */
    public SurvivalSim blaze(double dist) {
        return blaze(dist, 0);
    }

    public SurvivalSim blaze(double dist, double angleDeg) {
        // fireballs hit hard from afar (range 14) — charging into them dies; only cover stops them
        SurvivalSim s = addMob("blaze", dist, angleDeg, false, false, true, 0.22D, 5.5D, 14D);
        mobs.get(mobs.size() - 1).ranged = true;
        return s;
    }

    public SurvivalSim onFire(boolean water) {
        this.onFire = true;
        if (water) {
            this.nearestWater = new BlockPosSpec((int) (x + 4), (int) y, (int) z);
        }
        return this;
    }

    public SurvivalSim suffocating() {
        this.suffocating = true;
        return this;
    }

    /**
     * Standing on a contact-damage block (cactus / magma block / sweet-berry bush): it ticks damage
     * until we step ~1 block off. The classic "fall-MLG landed on cactus and stood there bleeding".
     */
    public SurvivalSim contactHazard() {
        this.contactHazard = true;
        this.contactHazardMoved = 0;
        return this;
    }

    /** Encased in solid (e.g. a cave-in / bad teleport): must mine out AND climb the shaft. */
    public SurvivalSim encased() {
        this.encased = true;
        return this;
    }

    /** Slowness (witch / soul sand): can't outrun mobs — fleeing fails, must dig in. */
    public SurvivalSim slowed(int level) {
        this.slownessLevel = level;
        return this;
    }

    /** Weakness (witch potion): melee is gutted — a "winnable" fight becomes a loss. */
    public SurvivalSim weak() {
        this.weakened = true;
        return this;
    }

    /** Blindness/Darkness: vision gutted — fleeing/kiting blind fails, must seal in. */
    public SurvivalSim blind() {
        this.blinded = true;
        return this;
    }

    /** Wither (wither skeleton / boss): damage-over-time natural regen can't outrun. */
    public SurvivalSim wither() {
        this.withered = true;
        this.witherTicks = 200;
        return this;
    }

    public SurvivalSim falling(double height) {
        this.falling = true;
        this.fallDistance = height;
        return this;
    }

    public SurvivalSim creeper(double dist) {
        return creeper(dist, 0);
    }

    public SurvivalSim creeper(double dist, double angleDeg) {
        return addMob("creeper", dist, angleDeg, true, false, false, 0.21D, 0, 0);
    }

    /** A creeper standing {@code dy} blocks above the bot (mesa ledge) — a short pillar won't clear it. */
    public SurvivalSim creeperAtHeight(double dist, double dy) {
        creeper(dist, 0);
        mobs.get(mobs.size() - 1).y = y + dy;
        return this;
    }

    public SurvivalSim skeleton(double dist) {
        return skeleton(dist, 0);
    }

    public SurvivalSim skeleton(double dist, double angleDeg) {
        return addMob("skeleton", dist, angleDeg, false, true, false, 0.24D, 2.5D, 15D);
    }

    public SurvivalSim zombie(double dist) {
        return zombie(dist, 0);
    }

    public SurvivalSim zombie(double dist, double angleDeg) {
        return addMob("zombie", dist, angleDeg, false, false, true, 0.23D, 3.0D, 1.8D);
    }

    public SurvivalSim spider(double dist, double angleDeg) {
        SurvivalSim s = addMob("spider", dist, angleDeg, false, false, true, 0.30D, 2.0D, 1.8D);
        mobs.get(mobs.size() - 1).hp = 16;
        return s;
    }

    /** A witch: drinks healing potions, so melee can't out-DPS it — must flee/cover, never brawl. */
    public SurvivalSim witch(double dist, double angleDeg) {
        SurvivalSim s = addMob("witch", dist, angleDeg, false, false, true, 0.24D, 3.0D, 1.8D);
        SimMob m = mobs.get(mobs.size() - 1);
        m.hp = 26;
        m.selfHeal = 2.0D; // regenerates faster than we can chip it down
        m.ranged = true;   // throws potions / heals: no melee wins this -> cover/flee
        return s;
    }

    /** A cave spider: faster than us and poisons on hit — trading just bleeds us out. */
    public SurvivalSim caveSpider(double dist, double angleDeg) {
        SurvivalSim s = addMob("cave_spider", dist, angleDeg, false, false, true, 0.33D, 2.0D, 1.8D);
        SimMob m = mobs.get(mobs.size() - 1);
        m.hp = 12;
        m.poisonOnHit = true;
        return s;
    }

    /** A phantom: flies out of melee reach, so a ground charge never lands — answer with cover/flee. */
    public SurvivalSim phantom(double dist, double angleDeg) {
        SurvivalSim s = addMob("phantom", dist, angleDeg, false, false, true, 0.30D, 3.0D, 1.8D);
        SimMob m = mobs.get(mobs.size() - 1);
        m.hp = 20;
        m.flying = true; // ground melee can't reach it
        m.ranged = true; // out of reach: answer with cover/flee, never a futile ground charge
        return s;
    }

    private SurvivalSim addMob(String type, double dist, double angleDeg, boolean cr, boolean sk,
                               boolean ho, double speed, double meleeDmg, double shootRange) {
        SimMob m = new SimMob();
        m.id = 1000 + mobs.size();
        m.type = type;
        m.creeper = cr;
        m.skeleton = sk;
        m.hostile = ho;
        m.speed = speed;
        double rad = Math.toRadians(angleDeg);
        m.x = x + Math.cos(rad) * dist;
        m.z = z + Math.sin(rad) * dist;
        m.y = y;
        if (sk) {
            m.range = shootRange;
            m.meleeDmg = meleeDmg;
        } else if (cr) {
            m.range = CREEPER_STOP; // keeps closing to contact; fuse uses CREEPER_RANGE
        } else {
            m.range = shootRange;
            m.meleeDmg = meleeDmg;
        }
        mobs.add(m);
        return this;
    }

    // ---------------------------------------------------------------- run

    public static final class Outcome {
        public boolean survived;
        public int ticks;
        public double finalHp;
        public String cause = "survived";
        public BehaviorId lastBehavior = BehaviorId.NONE;
    }

    /** Run up to {@code maxTicks}. Survived = hp stayed above 0 the whole time. */
    public Outcome run(int maxTicks) {
        Outcome o = new Outcome();
        for (int i = 0; i < maxTicks; i++) {
            gameTime++;
            o.lastBehavior = step();
            o.ticks = i + 1;
            if (hp <= 0) {
                o.survived = false;
                o.finalHp = 0;
                o.cause = "died:" + currentCause();
                return o;
            }
        }
        o.survived = true;
        o.finalHp = hp;
        return o;
    }

    private String currentCause() {
        if (inLava) {
            return "lava";
        }
        if (underWater && air <= 0) {
            return "drown";
        }
        if (suffocating || encased) {
            return "suffocation";
        }
        if (contactHazard) {
            return "contact_hazard";
        }
        if (!mobs.isEmpty()) {
            return mobs.get(0).type;
        }
        if (food <= 0) {
            return "starve";
        }
        return "unknown";
    }

    // ---------------------------------------------------------------- one tick

    private BehaviorId step() {
        WorldSnapshot s = render();
        ReflexEngine.Output out = reflexesOff ? null : engine.tick(s, tuning);
        BehaviorId behavior = out == null ? BehaviorId.NONE : out.plan.behavior;
        behaviorsSeen.add(behavior);
        FleeMode fleeMode = out == null ? FleeMode.NORMAL : out.plan.fleeMode;
        int target = out == null ? -1 : out.plan.targetEntityId;

        boolean hurtThisTick = false;

        if (debug) {
            double nd = Double.MAX_VALUE;
            for (SimMob m : mobs) {
                nd = Math.min(nd, distTo(m));
            }
            log.add(String.format("t%d %s/%s nd=%.1f hp=%.1f y=%.1f blk=%d enc=%b pil=%b mobs=%d",
                    gameTime, behavior, fleeMode, nd, hp, y, blocks, enclosed, pillaring, mobs.size()));
        }

        // 1. apply the bot's chosen response (its physical consequence)
        applyBehavior(behavior, fleeMode, target);

        // 2. step the threats
        hurtThisTick |= stepHazards();
        hurtThisTick |= stepMobs();

        // wither: damage-over-time that suppresses natural regen until it wears off (~1 dmg/2s)
        if (withered) {
            hp -= 0.04D;
            if (--witherTicks <= 0) {
                withered = false;
            }
        }
        // 3. natural regen / hunger drain
        if (food >= 18 && hp < maxHp && !inLava && !withered) {
            hp = Math.min(maxHp, hp + 0.08D);
        }
        if (gameTime % 80 == 0 && food > 0) {
            food--; // slow exhaustion
        }
        if (food <= 0 && hp > 1) {
            // normal difficulty: starvation alone only drains toward 1 hp (a hit still finishes you)
            hp = Math.max(1, hp - 0.05D);
        }

        if (hurtThisTick) {
            ticksSinceHurt = 0;
        } else if (ticksSinceHurt != Integer.MAX_VALUE) {
            ticksSinceHurt++;
        }
        return behavior;
    }

    private void applyBehavior(BehaviorId behavior, FleeMode fleeMode, int target) {
        // remember where we started this tick so we can credit ANY movement off a contact hazard
        // (real MC clears cactus/magma/sweet-berry damage the instant you leave the block, no matter
        // which behavior moved you — not just EXTINGUISH_FIRE). See the post-switch clear below.
        double preX = x;
        double preZ = z;
        // reset transient progress only when actually leaving the behavior that owns it (NOT every
        // tick of it — that was the bug that stopped the flee-wall ever finishing).
        if (behavior != BehaviorId.FLEE) {
            wallProgress = 0;
        }
        if (behavior != BehaviorId.FLEE || fleeMode != FleeMode.PILLAR) {
            pillaring = false;
        }
        if (behavior != BehaviorId.SHELTER && behavior != BehaviorId.RETREAT_HEAL) {
            shelterProgress = 0;
            enclosed = false;
        }
        if (behavior != BehaviorId.EAT && behavior != BehaviorId.SHELTER
                && behavior != BehaviorId.RETREAT_HEAL) {
            eatProgress = 0;
        }

        switch (behavior) {
            case ESCAPE_LAVA:
                escapeLava();
                break;
            case SURFACE:
                if (surfaceSealed && surfaceEscape == null) {
                    // capped by solid block with no side escape: only digging up gets us out, and
                    // bobbing (old behavior) just keeps drowning. Dig is slow; air does NOT refill
                    // until we've broken through.
                    if (++digOutProgress >= 16) {
                        surfaceSealed = false;
                        underWater = false;
                    }
                } else if (surfaceEscape != null && dist2D(surfaceEscape.x, surfaceEscape.z) >= 1.0D) {
                    // sealed/open but the only safe column is to the side: swim there, surfacing only
                    // once we reach it (air keeps draining while we cross — the behavior holds JUMP too)
                    moveToward(surfaceEscape.x, surfaceEscape.z, BOT_SPEED);
                    air = Math.min(300, air + 8);
                } else {
                    if (++surfaceProgress >= 4) {
                        underWater = false;
                        surfaceSealed = false;
                    }
                    air = Math.min(300, air + 12);
                }
                break;
            case DIG_OUT:
                digOutProgress++;
                if (digOutProgress >= 8) {
                    suffocating = false;
                    // Real MC stops suffocation the instant the head block breaks (~8 ticks): you're
                    // now climbing up through air. The remaining dig (to tick 16) is in air pockets, so
                    // it deals NO suffocation damage. Mark the head freed so stepHazards stops the
                    // 0.6/tick encased damage now, while keeping encased=true for the DIG_OUT routing.
                    headFreed = true;
                }
                if (digOutProgress >= 16) {
                    encased = false; // solid stone takes longer to mine through than falling sand
                }
                break;
            case EXTINGUISH_FIRE:
                if (nearestWater != null) {
                    moveToward(nearestWater.x, nearestWater.z, BOT_SPEED);
                } else if (contactHazard) {
                    // no water: run off the spiked/hot block along a safe octant until ~1 block clear
                    int octant = -1;
                    for (int i = 0; i < octantSafe.length; i++) {
                        if (octantSafe[i]) {
                            octant = i;
                            break;
                        }
                    }
                    if (octant >= 0) {
                        double ang = Math.toRadians(octant * 45);
                        moveAlongSafe(Math.sin(ang), Math.cos(ang), BOT_SPEED);
                        // (movement off the hazard is credited uniformly at the end of applyBehavior)
                    }
                }
                if (onFire && ++fireFightProgress >= 6) {
                    onFire = false;
                }
                break;
            case ANTI_FALL:
                if (bucketSlot >= 0) {
                    falling = false;
                    fallDistance = 0; // water bucket / boat MLG breaks the fall
                }
                break;
            case FLEE:
                doFlee(fleeMode);
                break;
            case COMBAT:
                doCombat(target);
                break;
            case RETREAT_HEAL:
                doRetreatHeal();
                break;
            case EAT:
                doEat();
                break;
            case SHELTER:
                doShelter();
                break;
            default:
                // NONE: stand still. If a hazard or mob is on us, this is how the bot dies.
        }

        // Contact damage clears the instant you leave the block in real MC — credit ANY movement this
        // tick toward stepping off the cactus/magma/sweet-berry, no matter which behavior moved us. The
        // old code only advanced this in EXTINGUISH_FIRE, so when a higher-severity mob preempted and the
        // bot FLED off the hazard, the sim kept applying phantom 0.5/tick damage forever (cluster bug).
        if (contactHazard) {
            contactHazardMoved += Math.hypot(x - preX, z - preZ);
            if (contactHazardMoved >= 1.0D) {
                contactHazard = false; // stepped off the hazard block
            }
        }
    }

    // ---- behavior outcomes

    /**
     * Faithful model of {@link baritone.ai.reflex.behavior.EscapeLavaBehavior}: prefer the mob-aware
     * escape column ({@code lavaEscape}, re-picked each tick by the real {@link EscapeColumns}), UNLESS
     * a hostile is parked on/near it or we've made no headway toward it for a while — in which case swim
     * out along the safest octant AWAY from the mobs, exactly like the shipped behavior. This routes the
     * plain-lava outcome through the same logic as the lava+mob case (which previously was the ONLY one
     * that used the real selection), so the lava cluster is measured against the real rules, not a
     * single fixed column the bot walked straight onto even when a mob was on it.
     */
    private void escapeLava() {
        // The real EscapeLavaBehavior ALWAYS holds JUMP (float up) every tick, on TOP of pushing toward
        // the column. That vertical float is what gets a bot out of a pool even when boxed in horizontally
        // (you bob to the lava surface and step onto the rim). Model it as steady progress toward escape;
        // a reachable mob-clear column just gets there faster. Without this the sim's horizontal-only
        // model cooked any boxed/cornered lava spawn that real MC would float out of — punitive, not fair.
        boolean horizontalEscaped = false;
        if (lavaEscape != null && !lavaColumnMobBlocked() && !lavaStalledTowardColumn()) {
            moveToward(lavaEscape.x, lavaEscape.z, BOT_SPEED);
            if (dist2D(lavaEscape.x, lavaEscape.z) < 1.0D) {
                horizontalEscaped = true;
            }
        } else {
            // no usable column (mob-blocked / stalled / lava ocean): swim out along the safest octant
            // AWAY from hostiles — any direction off the fire beats swimming at the blocking mob.
            int octant = safestLavaOctantAwayFromMobs();
            if (octant >= 0) {
                double ang = Math.toRadians(octant * 45);
                moveAlongSafe(Math.sin(ang), Math.cos(ang), BOT_SPEED);
            }
        }
        lavaSwum += BOT_SPEED;
        // vertical float-up (JUMP) escape — ONLY for a plain lava pool (lavaEscapeDist set). It bounds
        // the escape at ~crossing the pool radius, so a boxed/cornered pool spawn that real MC would
        // bob out of (you float to the lava surface and step onto the rim) survives instead of cooking
        // in place — the horizontal-only model was punitive there. A lava OCEAN (no surface to bob to)
        // and the mob-blocked TWO-column setup keep their own horizontal-swim escapes (no free float).
        boolean floatedOut = lavaEscapeDist > 0 && lavaSwum >= lavaEscapeDist;
        boolean swamOut = lavaOceanEdge > 0 && lavaSwum >= lavaOceanEdge;
        if (horizontalEscaped || floatedOut || swamOut) {
            inLava = false;
            onFire = true; // singed climbing out, but extinguish/regen handles it
        }
    }

    /** A hostile standing within {@link EscapeColumns#MOB_BLOCK_RADIUS} of the chosen lava escape column. */
    private boolean lavaColumnMobBlocked() {
        if (lavaEscape == null) {
            return false;
        }
        double cx = lavaEscape.x + 0.5D;
        double cz = lavaEscape.z + 0.5D;
        for (SimMob m : mobs) {
            if (!(m.hostile || m.creeper || m.skeleton)) {
                continue;
            }
            if (Math.hypot(cx - m.x, cz - m.z) <= EscapeColumns.MOB_BLOCK_RADIUS) {
                return true;
            }
        }
        return false;
    }

    /** EscapeLavaBehavior's no-progress watchdog: 12 ticks not getting closer to the column → give up on it. */
    private boolean lavaStalledTowardColumn() {
        double d = dist2D(lavaEscape.x, lavaEscape.z);
        if (d < lavaLastDistToColumn - 0.05D) {
            lavaStalledTicks = 0;
        } else {
            lavaStalledTicks++;
        }
        lavaLastDistToColumn = d;
        return lavaStalledTicks >= 12;
    }

    /** Among the safe octants, the one pointing most away from the nearest hostile (mirrors the behavior). */
    private int safestLavaOctantAwayFromMobs() {
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int o = 0; o < octantSafe.length; o++) {
            if (!octantSafe[o]) {
                continue;
            }
            double ang = Math.toRadians(o * 45);
            double ux = Math.sin(ang);
            double uz = Math.cos(ang);
            double worstAhead = Double.NEGATIVE_INFINITY;
            for (SimMob m : mobs) {
                if (!(m.hostile || m.creeper || m.skeleton)) {
                    continue;
                }
                double mdx = m.x - x;
                double mdz = m.z - z;
                double len = Math.hypot(mdx, mdz);
                if (len < 1e-6) {
                    continue;
                }
                worstAhead = Math.max(worstAhead, (ux * mdx + uz * mdz) / len);
            }
            double score = worstAhead == Double.NEGATIVE_INFINITY ? 0D : -worstAhead;
            if (best < 0 || score > bestScore) {
                bestScore = score;
                best = o;
            }
        }
        return best;
    }

    private void doFlee(FleeMode mode) {
        if (mode == FleeMode.PILLAR) {
            if (blocks > 0) {
                if (!pillaring) {
                    pillaring = true;
                    pillarBaseY = y;
                }
                // climb to the dynamic safe height: at least pillarTargetHeight, and high enough that
                // every creeper is creeperSafeGap below us — measured against the creeper's own Y.
                double needed = tuning.pillarTargetHeight;
                for (SimMob m : mobs) {
                    if (m.creeper) {
                        needed = Math.max(needed, (m.y - pillarBaseY) + tuning.creeperSafeGap);
                    }
                }
                needed = Math.min(needed, tuning.pillarMaxHeight);
                if (y - pillarBaseY < needed) {
                    y += PILLAR_RATE;
                    if (gameTime % 3 == 0) {
                        blocks--;
                    }
                }
                return; // standing on the pillar — out of reach
            }
            // no blocks: fall through to running
        }
        if (mode == FleeMode.WALL) {
            if (blocks > 0 && wallProgress++ < WALL_BUILD_TICKS) {
                if (wallProgress == WALL_BUILD_TICKS - 1) {
                    // wall the nearest pursuer off for good
                    SimMob n = nearestThreat(true);
                    if (n != null) {
                        walledOff.add(n);
                        blocks -= 2;
                    }
                }
                return;
            }
        }
        // NORMAL / NEW_DIRECTION: sprint directly away from the pursuers, along a safe direction
        double[] away = awayVector(true);
        if (away == null) {
            return; // boxed in with nobody to run from
        }
        moveAlongSafe(away[0], away[1], BOT_SPEED);
    }

    private void doCombat(int target) {
        SimMob m = byId(target);
        if (m == null) {
            m = nearestThreat(false);
        }
        if (m == null) {
            return;
        }
        double d = distTo(m);
        if (d > tuning.strikeDistance) {
            moveToward(m.x, m.z, BOT_SPEED); // close the gap
        } else if (attackCooldown <= 0) {
            if (!m.flying) {
                m.hp -= weaponDamage(); // a flying phantom is out of ground-melee reach — no hit lands
            }
            attackCooldown = 13;
            if (m.hp <= 0) {
                mobs.remove(m);
            }
        }
    }

    private void doRetreatHeal() {
        double[] away = awayVector(true);
        boolean moved = false;
        if (away != null && !isBlocked(away[0], away[1])) {
            moveAlongSafe(away[0], away[1], BOT_SPEED);
            moved = true;
        }
        if (!moved) {
            // cornered: bunker — seal in with blocks (or a dug hole), then heal
            if (blocks > 0 || digDownSafe) {
                if (++shelterProgress >= SHELTER_BUILD_TICKS) {
                    enclosed = true;
                    if (blocks > 0) {
                        blocks = Math.max(0, blocks - 2);
                    }
                }
            }
        }
        if (enclosed || hostilesWithin(tuning.retreatSafeDistance) == 0) {
            healTick();
        }
    }

    private void doEat() {
        if (foodSlot >= 0 && food < 20) {
            if (++eatProgress >= 28) {
                food = Math.min(20, food + Math.max(4, foodNutrition * 2));
                eatProgress = 0;
            }
        }
    }

    private void doShelter() {
        if (blocks > 0 || digDownSafe) {
            if (++shelterProgress >= SHELTER_BUILD_TICKS) {
                enclosed = true;
                if (blocks > 0) {
                    blocks = Math.max(0, blocks - 2);
                }
            }
        } else {
            // no way to build: at least break line of sight by moving to cover (treated as flee)
            double[] away = awayVector(true);
            if (away != null) {
                moveAlongSafe(away[0], away[1], BOT_SPEED);
            }
        }
        if (enclosed) {
            healTick();
        }
    }

    private void healTick() {
        if (food >= 18 && hp < maxHp) {
            hp = Math.min(maxHp, hp + 0.1D);
        } else if (foodSlot >= 0 && food < 18) {
            doEat();
        }
        if (poisoned && hp > 1) {
            poisoned = false; // treated
        }
    }

    // ---- threats

    private boolean stepHazards() {
        boolean hurt = false;
        if (inLava) {
            hp -= LAVA_DMG;
            hurt = true;
        } else if (onFire) {
            hp -= FIRE_DMG;
            hurt = true;
        }
        if (underWater) {
            air -= 4;
            if (air <= 0) {
                air = 0;
                hp -= DROWN_DMG;
                hurt = true;
            }
        }
        if (suffocating || (encased && !headFreed)) {
            // suffocation damage only while the head is actually buried — it stops the instant the
            // head block breaks (~tick 8), not when the whole shaft is cleared (tick 16)
            hp -= 0.6D;
            hurt = true;
        }
        if (contactHazard) {
            hp -= 0.5D; // cactus/magma/sweet-berry contact ticks until we step off
            hurt = true;
        }
        if (falling) {
            fallDistance += 0.5D;
            if (fallDistance > 60) {
                // hit the ground (sim cutoff) unprotected
                double dmg = Math.max(0, fallDistance - 3);
                hp -= dmg;
                falling = false;
                hurt = true;
            }
        }
        return hurt;
    }

    private boolean stepMobs() {
        boolean hurt = false;
        for (Iterator<SimMob> it = mobs.iterator(); it.hasNext(); ) {
            SimMob m = it.next();
            if (m.selfHeal > 0) {
                m.hp = Math.min(26, m.hp + m.selfHeal); // witch out-heals our chip damage
            }
            boolean blocked = enclosed || walledOff.contains(m) || botOutOfReach(m);
            double d = distTo(m);
            // de-aggro: the bot broke contact (outran it) or sealed it out long enough -> it's gone
            m.ticksFar = d > DEAGGRO_RANGE ? m.ticksFar + 1 : 0;
            m.ticksUnreachable = blocked ? m.ticksUnreachable + 1 : 0;
            if (m.ticksFar > DEAGGRO_TICKS || m.ticksUnreachable > UNREACHABLE_TICKS) {
                it.remove();
                continue;
            }
            if (!blocked && d > m.range) {
                // close on the bot
                double dx = x - m.x;
                double dz = z - m.z;
                double len = Math.hypot(dx, dz);
                if (len > 1e-6) {
                    m.x += dx / len * m.speed;
                    m.z += dz / len * m.speed;
                }
                d = distTo(m);
            }
            if (m.cooldown > 0) {
                m.cooldown--;
            }
            if (m.creeper) {
                if (!blocked && d <= CREEPER_RANGE) {
                    m.reached = true;
                    if (++m.fuse >= CREEPER_FUSE) {
                        double dmg = CREEPER_BLAST * (1 - d / (CREEPER_RANGE + 1)) * armorMul();
                        hp -= dmg;
                        it.remove();
                        hurt = true;
                    }
                } else {
                    m.fuse = 0;
                }
            } else if (m.skeleton) {
                boolean los = !blocked;
                if (los && d <= m.range && m.cooldown <= 0) {
                    hp -= m.meleeDmg * armorMul();
                    m.cooldown = 30;
                    m.reached = true;
                    hurt = true;
                }
            } else {
                if (!blocked && d <= m.range && m.cooldown <= 0) {
                    double dmg = m.meleeDmg * armorMul();
                    if (shield && d > 0) {
                        dmg *= 0.4D; // shield soaks most of a melee hit when we're facing it
                    }
                    hp -= dmg;
                    if (m.poisonOnHit) {
                        poisoned = true; // cave spider venom
                    }
                    m.cooldown = 16;
                    m.reached = true;
                    hurt = true;
                }
            }
        }
        return hurt;
    }

    private boolean botOutOfReach(SimMob m) {
        // pillared up out of melee/blast reach. A creeper's blast reaches ~4 blocks up, so a short
        // pillar does NOT clear it — must be BLAST_VERTICAL above the creeper. Skeletons shoot upward
        // regardless, so pillaring never blocks them (the brain shelters from shooters instead).
        if (m.skeleton) {
            return false;
        }
        double gap = y - m.y; // height of the bot above THIS mob (creeper may be on a higher ledge)
        double reach = m.creeper ? BLAST_VERTICAL : 2.4D;
        return pillaring && gap >= reach;
    }

    // ---------------------------------------------------------------- snapshot rendering

    private WorldSnapshot render() {
        WorldSnapshot s = new WorldSnapshot();
        s.gameTime = gameTime;
        s.working = true;
        s.hp = (float) hp;
        s.maxHp = (float) maxHp;
        s.food = food;
        s.air = air;
        s.onFire = onFire;
        s.inLava = inLava;
        s.underWater = underWater;
        s.poisoned = poisoned || withered;
        s.withered = withered;
        s.weakened = weakened;
        s.blinded = blinded;
        s.slownessLevel = slownessLevel;
        s.ticksSinceHurt = ticksSinceHurt;
        s.posX = x;
        s.posY = y;
        s.posZ = z;
        s.onGround = !falling;
        s.velY = falling ? -0.6D : 0;
        s.fallDistance = fallDistance;
        s.voidBelow = false;
        s.headBlockedByGravity = suffocating;
        s.headInSolid = encased || suffocating;
        s.yaw = 0;
        s.attackStrengthScale = attackCooldown <= 0 ? 1F : 0.3F;
        s.selectedSlot = 0;
        s.bestWeaponSlot = weaponSlot;
        s.bestWeaponTier = weaponTier;
        s.bestWeaponDurabilityPercent = weaponDurabilityPercent;
        s.hasShieldOffhand = shield;
        s.armorValue = armor;
        s.bestFoodSlot = foodSlot;
        s.bestFoodNutrition = foodNutrition;
        s.waterBucketSlot = bucketSlot;
        s.blockSlot = blocks > 0 ? 1 : -1;
        s.blockCount = blocks;
        s.bedSlot = bedSlot;
        s.nearestWater = nearestWater;
        s.surfaceSealed = surfaceSealed;
        s.contactHazardAtFeet = contactHazard;
        s.octantSafe = octantSafe.clone();
        s.digDownSafe = digDownSafe;
        s.night = night;
        s.lightLevel = night ? 4 : 15;
        if (attackCooldown > 0) {
            attackCooldown--;
        }

        for (SimMob m : mobs) {
            MobInfo mi = new MobInfo();
            mi.entityId = m.id;
            mi.typeId = m.type;
            mi.creeper = m.creeper;
            mi.skeleton = m.skeleton;
            mi.hostile = m.hostile;
            mi.ranged = m.ranged;
            mi.unkillable = m.unkillable;
            mi.x = m.x;
            mi.y = m.y;
            mi.z = m.z;
            mi.aimY = m.y + 1.0D;
            double d = distTo(m);
            mi.distance = d;
            mi.approachingSpeed = m.lastDist == Double.MAX_VALUE ? 0 : (m.lastDist - d);
            m.lastDist = d;
            mi.aggro = true;
            mi.lineOfSight = !enclosed;
            s.mobs.add(mi);
        }
        // mob-aware re-selection of the escape column (after mobs are rendered), exactly like the
        // adapter does via the real EscapeColumns — so "a mob blocks the escape" is a fair test.
        if (lavaCandidates != null) {
            lavaEscape = EscapeColumns.best(lavaCandidates, s.mobs);
        }
        if (surfaceCandidates != null) {
            surfaceEscape = EscapeColumns.best(surfaceCandidates, s.mobs);
        }
        s.lavaEscape = lavaEscape;
        s.surfaceEscape = surfaceEscape;
        return s;
    }

    // ---------------------------------------------------------------- geometry / helpers

    private double armorMul() {
        return Math.max(0.2D, 1 - Math.min(0.8D, armor * 0.04D));
    }

    private double weaponDamage() {
        // a weapon about to snap deals fist damage (and breaks for good — model it as bare-handed)
        boolean broken = weaponDurabilityPercent >= 0 && weaponDurabilityPercent < 5;
        int effTier = broken ? -1 : weaponTier;
        if (weakened) {
            return Math.max(0.5D, (effTier < 0 ? 1.0D : 8 - effTier) - 4.0D); // weakness guts melee
        }
        if (effTier < 0) {
            return 1.0D; // bare fist
        }
        return Math.max(2.0D, 8 - effTier);
    }

    private SimMob byId(int id) {
        for (SimMob m : mobs) {
            if (m.id == id) {
                return m;
            }
        }
        return null;
    }

    private double distTo(SimMob m) {
        double dx = m.x - x;
        double dy = m.y - y;
        double dz = m.z - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double dist2D(double tx, double tz) {
        return Math.hypot(tx - x, tz - z);
    }

    private int hostilesWithin(double r) {
        int c = 0;
        for (SimMob m : mobs) {
            if (!walledOff.contains(m) && distTo(m) <= r) {
                c++;
            }
        }
        return c;
    }

    private SimMob nearestThreat(boolean any) {
        SimMob best = null;
        for (SimMob m : mobs) {
            if (walledOff.contains(m)) {
                continue;
            }
            if (!any && !(m.skeleton || m.hostile)) {
                continue;
            }
            if (best == null || distTo(m) < distTo(best)) {
                best = m;
            }
        }
        return best;
    }

    /**
     * Best <em>safe</em> direction to escape the threats: among the safe octants, the one that points
     * most away from the mobs (the gap). This models a real flee — toward the opening, not blindly
     * "directly away" which, when surrounded symmetrically, would run straight into a mob.
     */
    private double[] awayVector(boolean any) {
        List<SimMob> threats = new ArrayList<>();
        for (SimMob m : mobs) {
            if (walledOff.contains(m)) {
                continue;
            }
            if (!any && !(m.creeper || m.skeleton)) {
                continue;
            }
            threats.add(m);
        }
        if (threats.isEmpty()) {
            return null;
        }
        double bestScore = Double.NEGATIVE_INFINITY;
        double[] best = null;
        for (int o = 0; o < 8; o++) {
            if (!octantSafe[o]) {
                continue;
            }
            double ang = Math.toRadians(o * 45);
            double ux = Math.sin(ang);
            double uz = Math.cos(ang);
            double maxAhead = Double.NEGATIVE_INFINITY;
            for (SimMob m : threats) {
                double mdx = m.x - x;
                double mdz = m.z - z;
                double len = Math.hypot(mdx, mdz);
                if (len < 1e-6) {
                    continue;
                }
                double ahead = (ux * mdx + uz * mdz) / len; // +1 = mob dead ahead, -1 = behind us
                maxAhead = Math.max(maxAhead, ahead);
            }
            double score = -maxAhead; // prefer the heading with mobs most behind us
            if (score > bestScore) {
                bestScore = score;
                best = new double[]{ux, uz};
            }
        }
        return best;
    }

    private void moveToward(double tx, double tz, double speed) {
        double dx = tx - x;
        double dz = tz - z;
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) {
            return;
        }
        moveAlongSafe(dx / len, dz / len, speed);
    }

    /** Move along (ux,uz) unless that octant is an unsafe (lava/ledge/wall) direction. */
    private void moveAlongSafe(double ux, double uz, double speed) {
        if (isBlocked(ux, uz)) {
            return; // would step into a hazard / wall — hold position (this is "cornered")
        }
        if (slownessLevel > 0) {
            speed *= Math.max(0.25D, 1 - 0.15D * slownessLevel); // slowed: can't outrun pursuers
        }
        if (blinded) {
            speed *= 0.5D; // blind: stumbling, can't navigate to outrun a pursuer in the open
        }
        x += ux * speed;
        z += uz * speed;
    }

    private boolean isBlocked(double ux, double uz) {
        int octant = octantOf(ux, uz);
        return !octantSafe[octant];
    }

    /** Map a direction vector to one of the 8 octants matching {@link baritone.ai.reflex.ReflexMath}. */
    private int octantOf(double ux, double uz) {
        // octant 0 = +Z (south), going clockwise: matches ReflexMath OCTANT layout closely enough
        double ang = Math.toDegrees(Math.atan2(ux, uz)); // 0 = +Z, 90 = +X
        if (ang < 0) {
            ang += 360;
        }
        int o = (int) Math.round(ang / 45.0) % 8;
        return o;
    }
}
