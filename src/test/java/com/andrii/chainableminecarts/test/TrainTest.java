package com.andrii.chainableminecarts.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.andrii.chainableminecarts.ModConfig;
import com.andrii.chainableminecarts.rail.TrackWalk;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.item.EntityMinecart;
import org.junit.Test;

/** Chained carts moving as trains, on straights and round bends. */
public class TrainTest
{
    /** How far the spacing may stray from the lead length while a train is moving. */
    private static final double SPACING_SLACK = 0.15D;

    private static final String[] STRAIGHT = {"----------------------------------------"};

    /** A U-turn: two long legs one block apart, joined at the top by two turns. */
    private static final String[] U_TURN = {
        "r7",
        "||",
        "||",
        "||",
        "||",
        "||",
        "||",
        "||",
        "||",
        "||",
        "||",
        "||",
        "||",
    };

    /** A loop round a 10 by 6 block rectangle. */
    private static final String[] LOOP = {
        "r--------7",
        "|        |",
        "|        |",
        "|        |",
        "|        |",
        "L--------J",
    };

    /** Where, along a straight east-west track, to put carts {@code gap} apart edge to edge. */
    private static double[] row(double firstX, int count, double gap)
    {
        double[] xs = new double[count];

        for (int i = 0; i < count; ++i)
        {
            xs[i] = firstX + i * (0.98D + gap);
        }

        return xs;
    }

    private static EntityMinecart[] carts(TestWorld world, double[] xs, double z)
    {
        EntityMinecart[] carts = new EntityMinecart[xs.length];

        for (int i = 0; i < xs.length; ++i)
        {
            carts[i] = world.cart(xs[i], z);
        }

        return carts;
    }

    private static void assertSettled(TestWorld world, EntityMinecart... carts)
    {
        double[] before = new double[carts.length * 2];

        for (int i = 0; i < carts.length; ++i)
        {
            assertEquals("cart " + i + " is still moving along x", 0.0D, carts[i].motionX, 0.0D);
            assertEquals("cart " + i + " is still moving along z", 0.0D, carts[i].motionZ, 0.0D);
            before[i * 2] = carts[i].posX;
            before[i * 2 + 1] = carts[i].posZ;
        }

        // Left alone, nothing moves any more: not even a creep
        world.tick(200);

        for (int i = 0; i < carts.length; ++i)
        {
            assertEquals("cart " + i + " crept along x", before[i * 2], carts[i].posX, 1.0E-9D);
            assertEquals("cart " + i + " crept along z", before[i * 2 + 1], carts[i].posZ, 1.0E-9D);
            assertEquals(0.0D, TestWorld.speed(carts[i]), 0.0D);
        }
    }

    private static void assertSpacing(String when, EntityMinecart[] carts, double slack)
    {
        for (int i = 0; i + 1 < carts.length; ++i)
        {
            double gap = TestWorld.gap(carts[i], carts[i + 1]);

            if (Double.isNaN(gap) || gap < ModConfig.trains.minRopeGap - slack || gap > ModConfig.trains.maxRopeGap + slack)
            {
                fail(String.format("%s: gap between carts %d and %d is %.3f, lead allows %.2f to %.2f",
                    when, i, i + 1, gap, ModConfig.trains.minRopeGap, ModConfig.trains.maxRopeGap));
            }
        }
    }

    @Test
    public void pairTooFarApartPullsTogetherAndStops()
    {
        TestWorld world = new TestWorld().track(STRAIGHT);
        EntityMinecart[] carts = carts(world, new double[] {10.5D, 13.5D}, 0.5D);
        world.chain(carts);

        world.tick(100);

        assertSpacing("after settling", carts, 0.03D);
        assertSettled(world, carts);
    }

    @Test
    public void pairTooCloseSpreadsApartAndStops()
    {
        TestWorld world = new TestWorld().track(STRAIGHT);
        EntityMinecart[] carts = carts(world, row(10.5D, 2, 0.1D), 0.5D);
        world.chain(carts);

        world.tick(100);

        assertSpacing("after settling", carts, 0.03D);
        assertSettled(world, carts);
    }

    @Test
    public void longTrainPulledTightSettlesEvenly()
    {
        TestWorld world = new TestWorld().track(STRAIGHT);
        // Every other gap too long, the rest too short
        EntityMinecart[] carts = carts(world, new double[] {5.5D, 8.0D, 9.2D, 11.5D, 12.7D, 15.0D}, 0.5D);
        world.chain(carts);

        world.tick(200);

        assertSpacing("after settling", carts, 0.03D);
        assertSettled(world, carts);
    }

    @Test
    public void pushedTrainMovesTogetherAndStops()
    {
        TestWorld world = new TestWorld().track(STRAIGHT);
        EntityMinecart[] carts = carts(world, row(3.5D, 4, 0.6D), 0.5D);
        world.chain(carts);
        // A hard shove on the last cart only: it moves off on its own for a tick before the train takes it up
        carts[3].motionX = 0.4D;

        double startX = carts[0].posX;

        for (int tick = 0; tick < 300; ++tick)
        {
            world.tick();

            if (tick >= 10)
            {
                assertSpacing("tick " + tick, carts, SPACING_SLACK);
            }
        }

        assertTrue("the train didn't move", carts[0].posX - startX > 1.0D);
        assertSettled(world, carts);
    }

    @Test
    public void trainRoundUTurnKeepsGoingAndKeepsSpacing()
    {
        TestWorld world = new TestWorld().track(U_TURN);
        // Up the left leg, head first
        EntityMinecart[] carts = new EntityMinecart[4];

        for (int i = 0; i < carts.length; ++i)
        {
            carts[i] = world.cart(0.5D, 4.5D + i * 1.58D);
            carts[i].motionZ = -0.4D;
        }

        world.chain(carts);
        TrackWalk.Cursor[] progress = startProgress(carts, 0.0D, -1.0D);

        for (int tick = 0; tick < 400; ++tick)
        {
            world.tick();
            assertSpacing("tick " + tick, carts, SPACING_SLACK);
            assertForward("tick " + tick, carts, progress);
        }

        // The head made it round and down the other leg
        assertTrue("head didn't get round the U: " + carts[0].posX + ", " + carts[0].posZ, carts[0].posX > 1.0D && carts[0].posZ > 2.0D);
        assertSettled(world, carts);
    }

    @Test
    public void trainRoundLoopKeepsGoingAndKeepsSpacing()
    {
        TestWorld world = new TestWorld().track(LOOP);
        EntityMinecart[] carts = carts(world, new double[] {7.5D, 5.92D, 4.34D, 2.76D}, 0.5D);

        for (EntityMinecart cart : carts)
        {
            cart.motionX = 0.4D;
        }

        world.chain(carts);
        TrackWalk.Cursor[] progress = startProgress(carts, 1.0D, 0.0D);

        for (int tick = 0; tick < 400; ++tick)
        {
            world.tick();
            assertSpacing("tick " + tick, carts, SPACING_SLACK);
            assertForward("tick " + tick, carts, progress);
        }

        assertSettled(world, carts);
    }

    @Test
    public void riddenCartRollsExactlyLikeAnEmptyOne()
    {
        TestWorld world = new TestWorld().track(STRAIGHT);
        EntityMinecart empty = world.cart(3.5D, 0.5D);
        TestWorld other = new TestWorld().track(STRAIGHT);
        EntityMinecart ridden = other.cart(3.5D, 0.5D);
        EntityArmorStand rider = new EntityArmorStand(other, 3.5D, 1.5D, 0.5D);
        other.spawnEntity(rider);
        rider.startRiding(ridden, true);
        assertTrue(ridden.isBeingRidden());
        empty.motionX = 0.4D;
        ridden.motionX = 0.4D;

        for (int tick = 0; tick < 100; ++tick)
        {
            world.tick();
            other.tick();
            assertEquals("tick " + tick, empty.posX, ridden.posX, 1.0E-6D);
        }
    }

    @Test
    public void riddenCartInATrainKeepsUp()
    {
        TestWorld world = new TestWorld().track(STRAIGHT);
        EntityMinecart[] carts = carts(world, row(3.5D, 3, 0.6D), 0.5D);
        EntityArmorStand rider = new EntityArmorStand(world, carts[1].posX, 1.5D, 0.5D);
        world.spawnEntity(rider);
        rider.startRiding(carts[1], true);
        world.chain(carts);

        for (EntityMinecart cart : carts)
        {
            cart.motionX = 0.4D;
        }

        for (int tick = 0; tick < 200; ++tick)
        {
            world.tick();
            // Much tighter than a train is otherwise held to: a ridden cart lagging would show straight away
            assertSpacing("tick " + tick, carts, 0.03D);
        }

        assertSettled(world, carts);
    }

    /** A point on the track for each cart, heading the way the train is going, to measure how far each moves. */
    private static TrackWalk.Cursor[] startProgress(EntityMinecart[] carts, double dirX, double dirZ)
    {
        TrackWalk.Cursor[] cursors = new TrackWalk.Cursor[carts.length];

        for (int i = 0; i < carts.length; ++i)
        {
            cursors[i] = TrackWalk.at(carts[i], dirX, dirZ);
            assertTrue("cart " + i + " isn't on the track", cursors[i] != null);
        }

        return cursors;
    }

    /** Every cart moved forward along the track, or stayed put: none went back. */
    private static void assertForward(String when, EntityMinecart[] carts, TrackWalk.Cursor[] cursors)
    {
        for (int i = 0; i < carts.length; ++i)
        {
            double moved = cursors[i].offsetTo(carts[i].posX, carts[i].posZ, 2.0D);
            assertFalse(when + ": cart " + i + " left the track", Double.isNaN(moved));
            assertTrue(String.format("%s: cart %d went back %.4f", when, i, -moved), moved > -1.0E-3D);
            cursors[i].advance(moved);
        }
    }
}
