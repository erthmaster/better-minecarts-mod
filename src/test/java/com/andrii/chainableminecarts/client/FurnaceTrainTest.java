package com.andrii.chainableminecarts.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.andrii.chainableminecarts.test.TestWorld;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.item.EntityMinecartFurnace;
import org.junit.Test;

/** Trains driven by fuelled furnace carts: they run, and they stop cleanly, on the server and as the client shows it. */
public class FurnaceTrainTest
{
    private static final String LONG_LINE = "----------------------------------------------------------------------------------------------------";
    private static final String BOOSTERS = "--------PPPP------pp--------PPPP------pp--------------------------------------------------------------";

    /** A furnace cart heading east with fuel for the given distance, pulling a chest cart and an empty cart behind. */
    private static EntityMinecart[] train(Game game, double fuel)
    {
        EntityMinecartFurnace furnace = game.server.furnace(6.5D, 0.5D, 1.0D, 0.0D, fuel);
        EntityMinecart[] train = {
            furnace,
            game.server.cart(EntityMinecart.Type.CHEST, 4.92D, 0.5D),
            game.server.cart(EntityMinecart.Type.RIDEABLE, 3.34D, 0.5D),
        };
        game.server.chain(train);
        game.track(train);
        return train;
    }

    private static Game game(String line)
    {
        return new Game(new TestWorld().line(line), new TestWorld(true).line(line));
    }

    private static void assertStopped(EntityMinecart... train)
    {
        for (EntityMinecart cart : train)
        {
            assertEquals("server cart still moving", 0.0D, TestWorld.speed(cart), 0.0D);
        }
    }

    private static void assertSpaced(String when, EntityMinecart... train)
    {
        for (int i = 0; i + 1 < train.length; ++i)
        {
            double gap = TestWorld.gap(train[i], train[i + 1]);
            assertTrue(String.format("%s: gap %d-%d is %.3f", when, i, i + 1, gap), gap > 0.45D && gap < 0.75D);
        }
    }

    @Test
    public void trainRunsOutOfFuelAndStops()
    {
        Game game = game(LONG_LINE);
        EntityMinecart[] train = train(game, 30.0D);
        double worst = 0.0D;

        for (int tick = 0; tick < 300; ++tick)
        {
            game.tick();
            worst = Math.max(worst, game.worstError());
            assertSpaced("tick " + tick, train);
        }

        assertTrue("didn't run on its fuel: " + train[0].posX, train[0].posX > 36.0D);
        assertTrue(String.format("client carts strayed %.3f from the server's", worst), worst < 0.5D);
        assertStopped(train);
        game.assertClientSettled();
    }

    @Test
    public void trainBrakesToAStop()
    {
        Game game = game(LONG_LINE);
        EntityMinecart[] train = train(game, 500.0D);
        game.tick(60);
        assertTrue("not up to speed", TestWorld.speed(train[0]) > 0.3D);

        // As clicking the running furnace cart without coal does
        train[0].getEntityData().setBoolean("better_minecarts:Stopped", true);
        train[0].getEntityData().setBoolean("better_minecarts:Braking", true);
        double worst = 0.0D;

        for (int tick = 0; tick < 200; ++tick)
        {
            game.tick();
            worst = Math.max(worst, game.worstError());
            assertSpaced("tick " + tick, train);
        }

        assertTrue(String.format("client carts strayed %.3f from the server's", worst), worst < 0.5D);
        assertStopped(train);
        game.assertClientSettled();
    }

    @Test
    public void trainRunsOverBoostersAndBrakeRailsThenStops()
    {
        Game game = game(BOOSTERS);
        EntityMinecart[] train = train(game, 40.0D);
        double worst = 0.0D;

        for (int tick = 0; tick < 400; ++tick)
        {
            game.tick();
            worst = Math.max(worst, game.worstError());

            // An unpowered booster halves a cart's speed as it moves, before the train evens it out, so the train
            // bunches up a little each time one runs onto it; it must never overlap or come close to snapping
            for (int i = 0; i + 1 < train.length; ++i)
            {
                double gap = TestWorld.gap(train[i], train[i + 1]);
                assertTrue(String.format("tick %d: gap %d-%d is %.3f", tick, i, i + 1, gap), gap > -0.005D && gap < 1.0D);
            }
        }

        assertTrue("didn't get past the boosters: " + train[2].posX, train[2].posX > 40.0D);
        assertSpaced("stopped", train);
        assertTrue(String.format("client carts strayed %.3f from the server's", worst), worst < 0.5D);
        assertStopped(train);
        game.assertClientSettled();
    }

    /** A furnace train driving round a U, into it from each leg: it never stalls at the join, and looks right. */
    @Test
    public void trainDrivesRoundAUTurnEitherWay()
    {
        for (double x : new double[] {0.5D, 1.5D})
        {
            Game game = new Game(new TestWorld().track(UTurnTest.U_NORTH), new TestWorld(true).track(UTurnTest.U_NORTH));
            EntityMinecart[] train = {
                game.server.furnace(x, 6.5D, 0.0D, -1.0D, 500.0D),
                game.server.cart(EntityMinecart.Type.CHEST, x, 8.08D),
                game.server.cart(EntityMinecart.Type.RIDEABLE, x, 9.66D),
            };
            game.server.chain(train);
            game.track(train);
            UTurnProbe server = new UTurnProbe("server", train[0], 0.0D, -1.0D);
            UTurnProbe client = new UTurnProbe("client", game.carts.get(0).client, 0.0D, -1.0D);

            // Round the U and down the other leg, not quite to its end
            for (int tick = 0; tick < 38; ++tick)
            {
                game.tick();
                server.sample(tick);
                client.sample(tick);
                assertSpaced("leg " + x + " tick " + tick, train);
            }

            String where = String.format("%n   %s%n   %s", server, client);
            // Round the U and heading down the other leg
            assertTrue("stalled" + where, Math.abs(train[0].posX - (2.0D - x)) < 0.01D && train[0].posZ > 1.5D && train[0].motionZ > 0.3D);
            assertTrue("went back" + where, server.backStep < 1.0E-3D && client.backStep < 0.02D);
            assertTrue("drawn off the rails" + where, client.offPath < 0.01D);
            assertTrue("drawn facing off the track" + where, client.misaligned < 5.0D);
            // A turn has a radius of half a block: at the top speed of 0.4 a tick, that's 46 degrees a tick
            assertTrue("drawn turning sharply" + where, client.yawTurn < UTurnProbe.MAX_TURN);
            assertTrue("drawn away from the server's" + where, game.worstError() < 0.35D);
        }
    }
}
