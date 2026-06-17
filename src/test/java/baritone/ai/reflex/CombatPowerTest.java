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

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Gear-aware fight-or-flee: 59% of the analyzed deaths were zombies beating an unarmed,
 * unarmored bot that chose to brawl. Power = weapon + armor + health (+ shield + full belly);
 * the bot only stands and fights when its power strictly beats the local threat.
 */
public class CombatPowerTest {

    private final ReflexTuning t = new ReflexTuning();

    /** Rank indexes in the adapter's melee table: 0=netherite sword ... 3=stone sword, 5=wooden. */
    private static final int IRON_SWORD = 2;
    private static final int STONE_SWORD = 3;
    private static final int WOODEN_SWORD = 5;

    private static WorldSnapshot bot(int weaponTier, int armor) {
        WorldSnapshot s = new WorldSnapshot(); // full hp + food by default
        if (weaponTier >= 0) {
            s.bestWeaponSlot = 0;
        }
        s.bestWeaponTier = weaponTier;
        s.armorValue = armor;
        return s;
    }

    private static MobInfo mob(int id, String type, double dist) {
        MobInfo m = new MobInfo();
        m.entityId = id;
        m.typeId = type;
        m.hostile = true;
        m.skeleton = "skeleton".equals(type);
        m.creeper = "creeper".equals(type);
        m.distance = dist;
        return m;
    }

    @Test
    public void unarmedUnarmoredDoesNotTakeOnEvenASingleZombie() {
        WorldSnapshot s = bot(-1, 0);
        s.mobs.add(mob(1, "zombie", 3));
        assertFalse(CombatPower.fightFavorable(s, t));
    }

    @Test
    public void stoneSwordAndArmorBeatsASingleZombie() {
        WorldSnapshot s = bot(STONE_SWORD, 8);
        s.mobs.add(mob(1, "zombie", 3));
        assertTrue(CombatPower.fightFavorable(s, t));
    }

    @Test
    public void twoNightSkeletonsOutmatchEvenAGearedBot() {
        WorldSnapshot s = bot(STONE_SWORD, 8);
        s.night = true;
        s.mobs.add(mob(1, "skeleton", 5));
        s.mobs.add(mob(2, "skeleton", 6));
        assertFalse(CombatPower.fightFavorable(s, t));
    }

    @Test
    public void ironSwordStillTakesTwoDaytimeZombies() {
        WorldSnapshot s = bot(IRON_SWORD, 0);
        s.mobs.add(mob(1, "zombie", 2));
        s.mobs.add(mob(2, "zombie", 3));
        assertTrue(CombatPower.fightFavorable(s, t));
    }

    @Test
    public void aShieldTipsAMarginalNightFight() {
        WorldSnapshot s = bot(WOODEN_SWORD, 0);
        s.night = true;
        s.mobs.add(mob(1, "skeleton", 5));
        assertFalse(CombatPower.fightFavorable(s, t));
        s.hasShieldOffhand = true;
        assertTrue(CombatPower.fightFavorable(s, t));
    }

    @Test
    public void creepersAreNeverWorthFighting() {
        // creepers don't add to threat power (the CREEPER threat handles them: always flee)
        WorldSnapshot s = bot(STONE_SWORD, 8);
        s.mobs.add(mob(1, "creeper", 5));
        assertTrue(CombatPower.fightFavorable(s, t));
    }

    @Test
    public void mobsOutsideEngageRangeDoNotScareTheBot() {
        WorldSnapshot s = bot(-1, 0);
        s.mobs.add(mob(1, "zombie", 14)); // beyond flee-engage radius, not approaching
        assertTrue(CombatPower.fightFavorable(s, t));
    }

    @Test
    public void moreArmorNeverLowersPower() {
        for (int armor = 1; armor <= 20; armor++) {
            assertTrue(CombatPower.playerPower(bot(-1, armor))
                    >= CombatPower.playerPower(bot(-1, armor - 1)));
        }
    }

    @Test
    public void lowHealthDrainsPower() {
        WorldSnapshot healthy = bot(STONE_SWORD, 0);
        WorldSnapshot hurt = bot(STONE_SWORD, 0);
        hurt.hp = 6;
        assertTrue(CombatPower.playerPower(hurt) < CombatPower.playerPower(healthy));
    }

    @Test
    public void aNearBrokenSwordCountsAsBareHanded() {
        // a stone sword + armor normally beats a zombie, but at 2% durability it snaps mid-fight
        // and then deals fist damage — the power score must discount it so the bot flees instead.
        WorldSnapshot fullDurability = bot(STONE_SWORD, 6);
        fullDurability.bestWeaponDurabilityPercent = 100;
        fullDurability.mobs.add(mob(1, "zombie", 3));
        assertTrue("a healthy sword wins the fight", CombatPower.fightFavorable(fullDurability, t));

        WorldSnapshot aboutToSnap = bot(STONE_SWORD, 6);
        aboutToSnap.bestWeaponDurabilityPercent = 2;
        aboutToSnap.mobs.add(mob(1, "zombie", 3));
        assertTrue("a near-broken sword scores like a fist",
                CombatPower.playerPower(aboutToSnap) < CombatPower.playerPower(fullDurability));
    }

    @Test
    public void unbreakableWeaponIsNotPenalised() {
        // -1 durability means unbreakable / not damageable — full weapon points
        WorldSnapshot s = bot(STONE_SWORD, 6);
        s.bestWeaponDurabilityPercent = -1;
        WorldSnapshot full = bot(STONE_SWORD, 6);
        full.bestWeaponDurabilityPercent = 100;
        assertTrue(CombatPower.playerPower(s) == CombatPower.playerPower(full));
    }

    @Test
    public void aCaveSpiderOutweighsAPlainZombie() {
        WorldSnapshot s = bot(STONE_SWORD, 8);
        s.mobs.add(mob(1, "cave_spider", 4));
        // a stone sword + armor that beats a zombie should NOT confidently take a cave spider
        // (faster + poison) — it scores higher than a plain hostile
        WorldSnapshot zombieCase = bot(STONE_SWORD, 8);
        zombieCase.mobs.add(mob(2, "zombie", 4));
        assertTrue(CombatPower.threatPower(s, t) > CombatPower.threatPower(zombieCase, t));
    }

    @Test
    public void legacyModeTrustsTheOldJudgment() {
        t.gearAwareCombat = false;
        WorldSnapshot s = bot(-1, 0);
        s.mobs.add(mob(1, "zombie", 3));
        assertTrue(CombatPower.fightFavorable(s, t));
    }
}
