package com.andrii.chainableminecarts.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.andrii.chainableminecarts.rail.TrackWalk;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.item.EntityMinecartFurnace;
import org.junit.Test;

/** The speed limit, and furnace carts driving themselves and their trains. */
public class SpeedAndFurnaceTest
{
    /** Vanilla's fastest speed on ordinary rails (a float, so a hair over 0.4 as a double). */
    private static final double RAIL_CAP = 0.4D;

    private static final String[] LOOP = {
        "r--------7",
        "|        |",
        "|        |",
        "|        |",
        "|        |",
        "L--------J",
    };

    /** Up a leg, round a U and down the other leg. */
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
        "||",
        "||",
        "||",
        "||",
        "||",
        "||",
        "||",
    };

    @Test
    public void noCartMovesFasterThanItsRailAllowsEvenOnTurns()
    {
        TestWorld world = new TestWorld().track(LOOP);
        EntityMinecart cart = world.cart(2.5D, 0.5D);
        // Far past the cap, as after a line of boosters
        cart.motionX = 2.0D;

        for (int tick = 0; tick < 80; ++tick)
        {
            double x = cart.posX;
            double z = cart.posZ;
            world.tick();
            double moved = Math.sqrt((cart.posX - x) * (cart.posX - x) + (cart.posZ - z) * (cart.posZ - z));
            assertTrue(String.format("tick %d: moved %.4f", tick, moved), moved <= RAIL_CAP + 1.0E-6D);
            assertTrue(String.format("tick %d: speed %.4f", tick, TestWorld.speed(cart)), TestWorld.speed(cart) <= RAIL_CAP + 1.0E-6D);
        }
    }

    @Test
    public void furnaceCartDrivesRoundAUTurnWithoutStalling()
    {
        TestWorld world = new TestWorld().track(U_TURN);
        // Heading north up the left leg
        EntityMinecartFurnace furnace = world.furnace(0.5D, 12.5D, 0.0D, -1.0D, 100.0D);
        TrackWalk.Cursor progress = TrackWalk.at(furnace, 0.0D, -1.0D);

        for (int tick = 0; tick < 70; ++tick)
        {
            world.tick();
            double moved = progress.offsetTo(furnace.posX, furnace.posZ, 2.0D);
            assertFalse("tick " + tick + ": the furnace cart left the track", Double.isNaN(moved));
            assertTrue(String.format("tick %d: the furnace cart went back %.4f", tick, -moved), moved > -1.0E-3D);
            progress.advance(moved);
        }

        // Round the U and well down the other leg, heading south
        assertTrue("stuck at " + furnace.posX + ", " + furnace.posZ, furnace.posX > 1.0D && furnace.posZ > 5.0D);
        assertTrue("not heading down the other leg", furnace.motionZ > 0.1D);
    }

    @Test
    public void furnaceTrainLapsALoopKeepingItsSpacing()
    {
        TestWorld world = new TestWorld().track(LOOP);
        EntityMinecart[] train = new EntityMinecart[4];
        train[0] = world.furnace(7.5D, 0.5D, 1.0D, 0.0D, 1000.0D);

        for (int i = 1; i < train.length; ++i)
        {
            train[i] = world.cart(7.5D - i * 1.58D, 0.5D);
        }

        world.chain(train);
        TrackWalk.Cursor progress = TrackWalk.at(train[0], 1.0D, 0.0D);
        double travelled = 0.0D;

        for (int tick = 0; tick < 400; ++tick)
        {
            world.tick();
            double moved = progress.offsetTo(train[0].posX, train[0].posZ, 2.0D);
            assertFalse("tick " + tick + ": the furnace cart left the track", Double.isNaN(moved));
            assertTrue(String.format("tick %d: the furnace cart went back %.4f", tick, -moved), moved > -1.0E-3D);
            progress.advance(moved);
            travelled += moved;

            for (int i = 0; i + 1 < train.length; ++i)
            {
                double gap = TestWorld.gap(train[i], train[i + 1]);
                assertTrue(String.format("tick %d: gap %d-%d is %.3f", tick, i, i + 1, gap), gap > 0.4D && gap < 0.8D);
            }
        }

        // The loop is about 25 blocks round; at full speed that's several laps
        assertTrue("only travelled " + travelled, travelled > 100.0D);
    }

    @Test
    public void furnaceFuelLastsItsDistance()
    {
        TestWorld world = new TestWorld().track(LOOP);
        EntityMinecartFurnace furnace = world.furnace(4.5D, 0.5D, 1.0D, 0.0D, 10.0D);
        TrackWalk.Cursor progress = TrackWalk.at(furnace, 1.0D, 0.0D);
        double travelled = 0.0D;

        for (int tick = 0; tick < 300; ++tick)
        {
            world.tick();
            double moved = progress.offsetTo(furnace.posX, furnace.posZ, 2.0D);
            progress.advance(moved);
            travelled += moved;
        }

        // Ten blocks under power, then it coasts to a stop
        assertTrue("travelled " + travelled, travelled > 10.0D && travelled < 20.0D);
        assertEquals(0.0D, TestWorld.speed(furnace), 0.0D);
    }
}
