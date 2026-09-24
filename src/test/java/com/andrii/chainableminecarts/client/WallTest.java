package com.andrii.chainableminecarts.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.andrii.chainableminecarts.ModConfig;
import com.andrii.chainableminecarts.physics.CartBody;
import com.andrii.chainableminecarts.test.TestWorld;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.Test;

/**
 * Carts running into a wall stop at it, touching it: never inside it, on the server or as the client draws them.
 * Trains stop there whole, without bouncing back off it or creeping away afterwards.
 */
public class WallTest
{
    /** A straight east-west track 20 rails long, ending at a wall at x = 20 (and one at x = -1, behind). */
    private static Game walledLine()
    {
        String line = "--------------------";
        Game game = new Game(new TestWorld().line(line), new TestWorld(true).line(line));

        for (TestWorld world : new TestWorld[] {game.server, game.client})
        {
            world.setBlockState(new BlockPos(line.length(), TestWorld.RAIL_Y, 0), Blocks.STONE.getDefaultState());
            world.setBlockState(new BlockPos(-1, TestWorld.RAIL_Y, 0), Blocks.STONE.getDefaultState());
        }

        return game;
    }

    /** Where a cart's centre is when its front touches the wall at x = 20. */
    private static final double AT_EAST_WALL = 20.0D - 0.49D;
    private static final double AT_WEST_WALL = 0.49D;

    /** Runs the scene, checking every tick that no cart is in a wall, on the server or drawn on the client. */
    private static void run(Game game, int ticks)
    {
        for (int tick = 0; tick < ticks; ++tick)
        {
            game.tick();

            for (Game.Tracked tracked : game.carts)
            {
                for (EntityMinecart cart : new EntityMinecart[] {tracked.server, tracked.client})
                {
                    boolean inWall = cart.world.collidesWithAnyBlock(CartBody.wallBox(cart.getEntityBoundingBox()));
                    assertTrue(String.format("tick %d: %s cart in the wall at %.3f, %.3f", tick, cart.world.isRemote ? "drawn" : "server",
                        cart.posX, cart.posZ), !inWall);
                }
            }
        }
    }

    /** Everything has stopped, stays exactly where it is, and is drawn right there. */
    private static void assertSettled(Game game)
    {
        double[] where = new double[game.carts.size()];

        for (int i = 0; i < where.length; ++i)
        {
            assertEquals("cart " + i + " still moving", 0.0D, TestWorld.speed(game.carts.get(i).server), 0.0D);
            where[i] = game.carts.get(i).server.posX;
        }

        run(game, 100);

        for (int i = 0; i < where.length; ++i)
        {
            assertEquals("cart " + i + " moved after stopping", where[i], game.carts.get(i).server.posX, 1.0E-9D);
            assertEquals("cart " + i + " drawn away from the server's", where[i], game.carts.get(i).client.posX, 0.01D);
        }
    }

    private static void assertSpaced(EntityMinecart... train)
    {
        for (int i = 0; i + 1 < train.length; ++i)
        {
            double gap = TestWorld.gap(train[i], train[i + 1]);
            assertTrue(String.format("gap %d-%d is %.3f", i, i + 1, gap), gap > ModConfig.trains.minRopeGap - 0.03D && gap < ModConfig.trains.maxRopeGap + 0.03D);
        }
    }

    private static EntityMinecart[] train(Game game, double headX, double direction, int length)
    {
        EntityMinecart[] train = new EntityMinecart[length];

        for (int i = 0; i < length; ++i)
        {
            train[i] = game.server.cart(i == 1 ? EntityMinecart.Type.CHEST : EntityMinecart.Type.RIDEABLE, headX - direction * 1.58D * i, 0.5D);
            train[i].motionX = direction * 0.4D;
        }

        game.server.chain(train);
        game.track(train);
        return train;
    }

    @Test
    public void cartStopsAtAWall()
    {
        Game game = walledLine();
        EntityMinecart cart = train(game, 17.0D, 1.0D, 1)[0];
        run(game, 40);
        assertEquals("not stopped touching the wall", AT_EAST_WALL, cart.posX, 0.005D);
        assertSettled(game);
    }

    @Test
    public void trainStopsAtAWallWithoutBouncingBack()
    {
        Game game = walledLine();
        EntityMinecart[] train = train(game, 17.0D, 1.0D, 4);
        run(game, 60);
        assertEquals("the head isn't at the wall", AT_EAST_WALL, train[0].posX, 0.005D);
        assertSpaced(train);
        assertSettled(game);
    }

    @Test
    public void trainBackingIntoAWallStopsThere()
    {
        // Heading west, the chain running the other way along the track
        Game game = walledLine();
        EntityMinecart[] train = train(game, 3.0D, -1.0D, 4);
        run(game, 60);
        assertEquals("the head isn't at the wall", AT_WEST_WALL, train[0].posX, 0.005D);
        assertSpaced(train);
        assertSettled(game);
    }

    @Test
    public void furnaceDrivingATrainIntoAWallHoldsItThere()
    {
        for (boolean furnaceInFront : new boolean[] {true, false})
        {
            Game game = walledLine();
            EntityMinecart[] train = new EntityMinecart[3];

            for (int i = 0; i < train.length; ++i)
            {
                double x = 14.0D - 1.58D * i;
                boolean furnace = furnaceInFront ? i == 0 : i == train.length - 1;
                train[i] = furnace ? game.server.furnace(x, 0.5D, 1.0D, 0.0D, 1000.0D) : game.server.cart(EntityMinecart.Type.RIDEABLE, x, 0.5D);
            }

            game.server.chain(train);
            game.track(train);
            run(game, 80);
            assertEquals("the head isn't at the wall", AT_EAST_WALL, train[0].posX, 0.005D);
            assertSpaced(train);
            // Still fuelled and driving, but it can't go anywhere
            assertSettled(game);
        }
    }

    @Test
    public void cartRollingIntoCartsParkedAtAWall()
    {
        Game game = walledLine();
        EntityMinecart parked1 = game.server.cart(EntityMinecart.Type.RIDEABLE, AT_EAST_WALL, 0.5D);
        EntityMinecart parked2 = game.server.cart(EntityMinecart.Type.RIDEABLE, AT_EAST_WALL - 0.98D, 0.5D);
        EntityMinecart moving = game.server.cart(EntityMinecart.Type.RIDEABLE, 12.5D, 0.5D);
        moving.motionX = 0.4D;
        game.track(parked1, parked2, moving);
        run(game, 60);
        assertEquals("the parked carts were pushed into the wall", AT_EAST_WALL, parked1.posX, 0.005D);
        assertTrue("carts overlap", TestWorld.gap(parked2, parked1) > -0.02D && TestWorld.gap(moving, parked2) > -0.02D);
        // It stops against them, barely bouncing off
        assertTrue("bounced back off them: " + moving.posX, moving.posX > parked2.posX - 0.98D - 0.3D);
        assertSettled(game);
    }

    @Test
    public void cartStopsAtAWallJustPastACurve()
    {
        // Round a turn and into a wall one block along from it
        String[] track = {
            "r-",
            "| ",
            "| ",
            "| ",
            "| ",
            "| ",
        };
        Game game = new Game(new TestWorld().track(track), new TestWorld(true).track(track));

        for (TestWorld world : new TestWorld[] {game.server, game.client})
        {
            world.setBlockState(new BlockPos(2, TestWorld.RAIL_Y, 0), Blocks.STONE.getDefaultState());
        }

        EntityMinecart cart = game.server.cart(EntityMinecart.Type.RIDEABLE, 0.5D, 4.5D);
        cart.motionZ = -0.4D;
        game.track(cart);
        run(game, 60);
        assertEquals("not stopped touching the wall", 2.0D - 0.49D, cart.posX, 0.005D);
        assertSettled(game);
    }
}
