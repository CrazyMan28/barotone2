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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Severity math for the threats the old enum ladder never saw (the bot died to all of these). */
public class DetectorsTest {

    private final ReflexTuning t = new ReflexTuning();

    private static WorldSnapshot calm() {
        WorldSnapshot s = new WorldSnapshot();
        s.working = true;
        return s;
    }

    // ---------------------------------------------------------------- fire

    @Test
    public void burningScalesWithMissingHealth() {
        WorldSnapshot healthy = calm();
        healthy.onFire = true;
        healthy.hp = 18;
        Threat a = Detectors.fire(healthy, t);
        assertNotNull(a);
        assertEquals(ThreatType.FIRE, a.type);

        WorldSnapshot dying = calm();
        dying.onFire = true;
        dying.hp = 4;
        Threat b = Detectors.fire(dying, t);
        assertNotNull(b);
        assertTrue("burning while nearly dead must score higher", b.severity > a.severity);
        assertTrue(a.severity >= 70 && b.severity <= 90);
    }

    @Test
    public void contactHazardFiresOnTheFeetBlockButDefersToLavaAndWater() {
        WorldSnapshot s = calm();
        s.contactHazardAtFeet = true;
        Threat th = Detectors.contactHazard(s, t);
        assertNotNull("standing on cactus/magma must produce a contact-hazard threat", th);
        assertEquals(ThreatType.CONTACT_HAZARD, th.type);

        WorldSnapshot inLava = calm();
        inLava.contactHazardAtFeet = true;
        inLava.inLava = true;
        assertNull("lava owns its own case", Detectors.contactHazard(inLava, t));

        WorldSnapshot swimming = calm();
        swimming.contactHazardAtFeet = true;
        swimming.underWater = true;
        assertNull("underwater is the drown handler's", Detectors.contactHazard(swimming, t));

        assertNull("no hazard, no threat", Detectors.contactHazard(calm(), t));
    }

    @Test
    public void fireDefersToLavaAndWater() {
        WorldSnapshot inLava = calm();
        inLava.onFire = true;
        inLava.inLava = true;
        assertNull("lava owns the burning-in-lava case", Detectors.fire(inLava, t));

        WorldSnapshot swimming = calm();
        swimming.onFire = true;
        swimming.underWater = true;
        assertNull("water is already putting us out", Detectors.fire(swimming, t));
    }

    // ---------------------------------------------------------------- fall

    @Test
    public void fallNeedsABucketAndARealDrop() {
        WorldSnapshot falling = calm();
        falling.onGround = false;
        falling.velY = -0.6;
        falling.fallDistance = 5;
        falling.gapBelow = 8;
        falling.waterBucketSlot = 3;
        Threat th = Detectors.fall(falling, t);
        assertNotNull(th);
        assertEquals(ThreatType.FALL, th.type);
        assertEquals(90, th.severity);

        falling.waterBucketSlot = -1;
        assertNull("no bucket -> nothing the reflex can do", Detectors.fall(falling, t));

        falling.waterBucketSlot = 3;
        falling.fallDistance = 1;
        assertNull("normal pathing hops must never trigger", Detectors.fall(falling, t));

        falling.fallDistance = 5;
        falling.onGround = true;
        assertNull(Detectors.fall(falling, t));
    }

    @Test
    public void voidDropIsMaxSeverity() {
        WorldSnapshot s = calm();
        s.onGround = false;
        s.voidBelow = true;
        s.gapBelow = 24;
        Threat th = Detectors.voidDrop(s, t);
        assertNotNull(th);
        assertEquals(ThreatType.VOID, th.type);
        assertEquals(100, th.severity);
        assertNull("not falling -> no void threat", Detectors.voidDrop(calm(), t));
    }

    // ---------------------------------------------------------------- suffocation

    @Test
    public void gravityBlockOnHeadIsDetected() {
        WorldSnapshot s = calm();
        s.headBlockedByGravity = true;
        Threat th = Detectors.suffocation(s, t);
        assertNotNull(th);
        assertEquals(ThreatType.SUFFOCATION, th.type);
        assertTrue("suffocation outranks drowning (kills faster)", th.severity > Detectors.SEV_DROWN);
    }

    // ---------------------------------------------------------------- mobs

    @Test
    public void ignitedCreeperOutranksACalmOne() {
        WorldSnapshot calmCreeper = calm();
        MobInfo c1 = new MobInfo();
        c1.entityId = 1;
        c1.creeper = true;
        c1.distance = 5;
        calmCreeper.mobs.add(c1);
        Threat normal = Detectors.fleeMob(calmCreeper, t);
        assertNotNull(normal);

        WorldSnapshot hissing = calm();
        MobInfo c2 = new MobInfo();
        c2.entityId = 2;
        c2.creeper = true;
        c2.ignited = true;
        c2.distance = 5;
        hissing.mobs.add(c2);
        Threat ignited = Detectors.fleeMob(hissing, t);
        assertNotNull(ignited);
        assertTrue("a hissing creeper is an emergency", ignited.severity > normal.severity);
    }

    @Test
    public void wardenIsAlwaysFledNeverFoughtEvenGeared() {
        WorldSnapshot s = calm();
        // fully geared: would normally stand and fight a melee mob
        s.bestWeaponSlot = 0;
        s.bestWeaponTier = 0;
        s.armorValue = 20;
        s.hp = 20;
        MobInfo warden = new MobInfo();
        warden.entityId = 7;
        warden.hostile = true;
        warden.unkillable = true;
        warden.distance = 6;
        s.mobs.add(warden);
        Threat flee = Detectors.fleeMob(s, t);
        assertNotNull("a warden must always produce a flee threat", flee);
        assertEquals(ThreatType.WARDEN, flee.type);
        assertEquals(Detectors.SEV_WARDEN, flee.severity);
        assertNull("a warden is never a melee fight, no matter the gear", Detectors.meleeFight(s, t));
        assertTrue("a warden requires fleeing", Detectors.fleeRequiredWithin(s, t, 8));
    }

    @Test
    public void rangedMobShelteredNotCharged() {
        WorldSnapshot s = calm();
        // geared enough to brawl a melee mob — but a blaze must still be answered with cover (RANGED),
        // never a melee charge (which a plain hostile of the same gear would get)
        s.bestWeaponSlot = 0;
        s.bestWeaponTier = 0;
        s.armorValue = 20;
        s.hp = 20;
        MobInfo blaze = new MobInfo();
        blaze.entityId = 8;
        blaze.hostile = true;
        blaze.ranged = true;
        blaze.distance = 6;
        s.mobs.add(blaze);
        assertTrue("a blaze is a shooter", Detectors.isShooter(blaze));
        Threat flee = Detectors.fleeMob(s, t);
        assertNotNull("a ranged mob must produce a cover (RANGED) threat", flee);
        assertEquals(ThreatType.RANGED, flee.type);
        assertNull("a ranged mob must never be melee-charged", Detectors.meleeFight(s, t));
    }

    @Test
    public void swarmDetectedFleeNotBrawl() {
        WorldSnapshot s = calm();
        s.ticksSinceHurt = 5;
        for (int i = 0; i < 3; i++) {
            MobInfo z = new MobInfo();
            z.entityId = 10 + i;
            z.hostile = true;
            z.distance = 4;
            z.x = 4;
            s.mobs.add(z);
        }
        Threat th = Detectors.swarm(s, t);
        assertNotNull(th);
        assertEquals(ThreatType.SWARM, th.type);
        assertTrue("a swarm outranks a single melee fight", th.severity > Detectors.SEV_MELEE);

        WorldSnapshot pair = calm();
        pair.mobs.add(s.mobs.get(0));
        pair.mobs.add(s.mobs.get(1));
        assertNull("two mobs are not a swarm", Detectors.swarm(pair, t));
    }

    // ---------------------------------------------------------------- poison

    @Test
    public void poisonAtLowHpWithFoodTriggersRetreat() {
        WorldSnapshot s = calm();
        s.poisoned = true;
        s.hp = 10;
        s.bestFoodSlot = 2;
        Threat th = Detectors.poison(s, t);
        assertNotNull(th);
        assertEquals(ThreatType.POISON, th.type);

        s.bestFoodSlot = -1;
        assertNull("no food -> nothing the retreat can do", Detectors.poison(s, t));

        s.bestFoodSlot = 2;
        s.hp = 18;
        assertNull("healthy enough to ride it out", Detectors.poison(s, t));
    }
}
