package com.andrii.chainableminecarts.client;

import static org.junit.Assert.assertTrue;

import com.andrii.chainableminecarts.physics.CartBody;
import com.andrii.chainableminecarts.physics.SolidCarts;
import com.andrii.chainableminecarts.test.TestWorld;
import java.util.function.IntFunction;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.Test;

/**
 * A player walking into a solid cart pushes it along, and never ends up inside it, as their own client sees it.
 * <p>
 * The player is stood in for by an armour stand on the client, walking as a player does: its movement collides with
 * carts there, and walking into one sends the server a push, as CartPushInput and PushCartMessage do. The server
 * has a copy of it a tick behind, as it has of a player, which carts moving there run into.
 */
public class PushThroughTest
{
    private static final String LINE = "------------------------------------------------------------";
    /** Vanilla's push for a player walking into a cart (SolidCarts). */
    private static final double PUSH = 0.05D;
    /** How far ahead CartPushInput looks for a cart being walked into. */
    private static final double REACH = 0.15D;

    /** A stand-in for a player: a living body that carts collide with, like a player. */
    private static EntityLivingBase body(World world, double x, double z)
    {
        EntityLivingBase body = new EntityArmorStand(world, x, TestWorld.RAIL_Y, z)
        {
            @Override
            public boolean canBePushed()
            {
                return true;
            }
        };
        world.spawnEntity(body);
        return body;
    }

    /** How deep a box is inside another, horizontally, if they overlap at all; 0 if they don't. */
    private static double depth(AxisAlignedBB a, AxisAlignedBB b)
    {
        if (!a.intersects(b))
        {
            return 0.0D;
        }

        return Math.min(Math.min(a.maxX - b.minX, b.maxX - a.minX), Math.min(a.maxZ - b.minZ, b.maxZ - a.minZ));
    }

    /**
     * Runs a scene: the player starts at a spot and walks by {@code walk} (x, z per tick, given the tick) while the
     * server and client tick. Returns how deep the player got inside any cart as drawn on their client, at worst,
     * whether or not they were then pushed out.
     */
    private static double play(Game game, double x, double z, IntFunction<double[]> walk, int ticks)
    {
        return play(game, body(game.client, x, z), walk, ticks);
    }

    private static double play(Game game, EntityLivingBase player, IntFunction<double[]> walk, int ticks)
    {
        double x = player.posX;
        double z = player.posZ;
        EntityLivingBase serverPlayer = body(game.server, x, z);
        EntityMinecart pushed = null;
        double pushX = 0.0D;
        double pushZ = 0.0D;
        double deepest = 0.0D;

        for (int tick = 0; tick < ticks; ++tick)
        {
            // The server hears about last tick's push, and where the player was, as its tick starts
            if (pushed != null)
            {
                CartBody.applyImpulse(pushed, pushX, pushZ, PUSH);
                pushed = null;
            }

            serverPlayer.setPosition(player.posX, player.posY, player.posZ);
            game.server.tick();

            // Client tick: the player moves, then the carts are drawn where they go, and last the player is moved
            // out of any cart they're in (CurveSmoothing, then CartPushInput)
            double[] step = walk.apply(tick);
            player.move(MoverType.SELF, step[0], -0.08D, step[1]);

            for (Game.Tracked tracked : game.carts)
            {
                game.client.updateEntity(tracked.client);
                CurveSmoothing.followTrack(tracked.client, player);
            }

            // Inside a cart here would mean the player gets shoved back out: a jolt, even if they end up outside
            for (Game.Tracked tracked : game.carts)
            {
                deepest = Math.max(deepest, depth(player.getEntityBoundingBox(), tracked.client.getEntityBoundingBox()));
            }

            SolidCarts.pushOutOfCarts(player);

            double length = Math.sqrt(step[0] * step[0] + step[1] * step[1]);
            AxisAlignedBB box = player.getEntityBoundingBox();

            for (Game.Tracked tracked : game.carts)
            {
                AxisAlignedBB cartBox = tracked.client.getEntityBoundingBox();
                deepest = Math.max(deepest, depth(box, cartBox));

                // Walking into a cart's side pushes it (CartPushInput): touching it within reach, not inside it
                if (length > 0.0D && box.offset(step[0] / length * REACH, 0.0D, step[1] / length * REACH).intersects(cartBox)
                    && !box.intersects(cartBox))
                {
                    pushed = tracked.server;
                    pushX = step[0] / length;
                    pushZ = step[1] / length;
                }
            }
        }

        return deepest;
    }

    private static Game game()
    {
        return new Game(new TestWorld().line(LINE), new TestWorld(true).line(LINE));
    }

    private static void assertOutside(String scene, double deepest)
    {
        System.out.printf("%s: deepest inside a cart %.4f%n", scene, deepest);
        assertTrue(String.format("%s: the player got %.3f inside a cart", scene, deepest), deepest < 0.01D);
    }

    @Test
    public void pushingACartAlong()
    {
        for (double speed : new double[] {0.1D, 0.216D, 0.28D})
        {
            Game game = game();
            EntityMinecart cart = game.server.cart(EntityMinecart.Type.RIDEABLE, 5.5D, 0.5D);
            game.track(cart);
            double deepest = play(game, 4.0D, 0.5D, tick -> new double[] {tick < 120 ? speed : 0.0D, 0.0D}, 180);
            assertTrue("the cart wasn't pushed: " + cart.posX, cart.posX > 8.0D);
            assertOutside("pushing at " + speed, deepest);
        }
    }

    @Test
    public void pushingACartIntoAParkedOne()
    {
        Game game = game();
        EntityMinecart cart = game.server.cart(EntityMinecart.Type.RIDEABLE, 5.5D, 0.5D);
        EntityMinecart parked = game.server.cart(EntityMinecart.Type.RIDEABLE, 9.5D, 0.5D);
        game.track(cart, parked);
        assertOutside("pushing into a parked cart", play(game, 4.0D, 0.5D, tick -> new double[] {tick < 120 ? 0.28D : 0.0D, 0.0D}, 180));
    }

    @Test
    public void pushingATrain()
    {
        Game game = game();
        EntityMinecart[] train = {
            game.server.cart(EntityMinecart.Type.RIDEABLE, 5.5D, 0.5D),
            game.server.cart(EntityMinecart.Type.CHEST, 7.08D, 0.5D),
            game.server.cart(EntityMinecart.Type.RIDEABLE, 8.66D, 0.5D),
        };
        game.server.chain(train);
        game.track(train);
        assertOutside("pushing a train", play(game, 4.0D, 0.5D, tick -> new double[] {tick < 120 ? 0.28D : 0.0D, 0.0D}, 180));
    }

    @Test
    public void cartRollingIntoAStandingPlayer()
    {
        for (double speed : new double[] {0.1D, 0.25D, 0.4D})
        {
            Game game = game();
            EntityMinecart cart = game.server.cart(EntityMinecart.Type.RIDEABLE, 3.5D, 0.5D);
            cart.motionX = speed;
            game.track(cart);
            assertOutside("a cart rolling in at " + speed, play(game, 8.0D, 0.5D, tick -> new double[] {0.0D, 0.0D}, 120));
        }
    }

    @Test
    public void walkingIntoACartAtAnAngle()
    {
        Game game = game();
        EntityMinecart cart = game.server.cart(EntityMinecart.Type.RIDEABLE, 5.5D, 0.5D);
        game.track(cart);
        // From behind and to one side, heading across the track's end of the cart
        double step = 0.216D / Math.sqrt(2.0D);
        assertOutside("walking in at an angle", play(game, 3.5D, -1.0D, tick -> new double[] {tick < 120 ? step : 0.0D, tick < 120 ? step : 0.0D}, 180));
    }

    @Test
    public void pushingACartIntoAWall()
    {
        String line = "--------------------";
        Game game = new Game(new TestWorld().line(line), new TestWorld(true).line(line));

        for (TestWorld world : new TestWorld[] {game.server, game.client})
        {
            world.setBlockState(new BlockPos(line.length(), TestWorld.RAIL_Y, 0), Blocks.STONE.getDefaultState());
        }

        EntityMinecart cart = game.server.cart(EntityMinecart.Type.RIDEABLE, 5.5D, 0.5D);
        game.track(cart);
        assertOutside("pushing into a wall", play(game, 4.0D, 0.5D, tick -> new double[] {tick < 150 ? 0.28D : 0.0D, 0.0D}, 200));
        assertTrue("the cart didn't reach the wall: " + cart.posX, cart.posX > 18.0D);
    }

    @Test
    public void playerInsideACartIsPushedOutAndCantWalkThrough()
    {
        // Half inside a cart from behind, then holding forward into it
        Game game = game();
        EntityMinecart cart = game.server.cart(EntityMinecart.Type.RIDEABLE, 5.5D, 0.5D);
        game.track(cart);
        game.tick(3);
        EntityLivingBase player = body(game.client, 5.0D, 0.5D);
        play(game, player, tick -> new double[] {0.28D, 0.0D}, 3);

        AxisAlignedBB drawn = game.carts.get(0).client.getEntityBoundingBox();
        assertTrue("still inside the cart", !player.getEntityBoundingBox().intersects(drawn));
        assertTrue("pushed out the far side, through the cart: " + player.posX, player.posX < cart.posX);

        // And from then on, walking on into it only pushes it along
        assertOutside("walking on into it", play(game, player, tick -> new double[] {0.28D, 0.0D}, 100));
    }
}
