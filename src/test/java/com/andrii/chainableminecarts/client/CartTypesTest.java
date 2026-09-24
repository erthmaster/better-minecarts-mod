package com.andrii.chainableminecarts.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.andrii.chainableminecarts.test.TestWorld;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.item.EntityMinecart;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Every kind of cart moves exactly like an empty one, on the server and as the client shows it: alone, in trains,
 * and over powered and unpowered booster rails. (Fuelled furnace carts drive themselves; see {@link FurnaceTrainTest}.)
 */
@RunWith(Parameterized.class)
public class CartTypesTest
{
    /** Plain rails, a run of powered rails, plain again, then two unpowered ones (which brake), then plain. */
    private static final String BOOSTERS = "--------PPPP------pp--------------------------------------";

    @Parameterized.Parameter
    public EntityMinecart.Type type;

    @Parameterized.Parameters(name = "{0}")
    public static List<Object[]> types()
    {
        List<Object[]> types = new ArrayList<>();

        for (EntityMinecart.Type type : EntityMinecart.Type.values())
        {
            types.add(new Object[] {type});
        }

        return types;
    }

    private static Game game(String line)
    {
        return new Game(new TestWorld().line(line), new TestWorld(true).line(line));
    }

    /** The cart of this type and an empty control cart, each rolled from the same start in its own world. */
    private void assertRollsLikeAnEmptyCart(String line, double startX, double speed)
    {
        TestWorld world = new TestWorld().line(line);
        EntityMinecart cart = world.cart(this.type, startX, 0.5D);
        TestWorld control = new TestWorld().line(line);
        EntityMinecart empty = control.cart(EntityMinecart.Type.RIDEABLE, startX, 0.5D);
        cart.motionX = speed;
        empty.motionX = speed;

        for (int tick = 0; tick < 300; ++tick)
        {
            world.tick();
            control.tick();
            assertEquals("tick " + tick + ": position", empty.posX, cart.posX, 1.0E-6D);
            assertEquals("tick " + tick + ": speed", empty.motionX, cart.motionX, 1.0E-6D);
        }

        assertEquals("still moving", 0.0D, TestWorld.speed(cart), 0.0D);
    }

    @Test
    public void rollsLikeAnEmptyCartOnPlainRails()
    {
        this.assertRollsLikeAnEmptyCart("--------------------------------------------------", 2.5D, 0.4D);
    }

    @Test
    public void rollsLikeAnEmptyCartOverBoosters()
    {
        this.assertRollsLikeAnEmptyCart(BOOSTERS, 2.5D, 0.1D);
    }

    @Test
    public void pulledTogetherWithAnEmptyCartBothStopOnServerAndClient()
    {
        Game game = game("----------------------------------------");
        EntityMinecart empty = game.server.cart(EntityMinecart.Type.RIDEABLE, 10.5D, 0.5D);
        EntityMinecart cart = game.server.cart(this.type, 14.5D, 0.5D);
        game.server.chain(empty, cart);
        game.track(empty, cart);

        game.tick(100);

        double gap = TestWorld.gap(empty, cart);
        assertEquals("gap", 0.6D, gap, 0.03D);
        assertEquals("server cart still moving", 0.0D, TestWorld.speed(cart), 0.0D);
        assertEquals("server empty cart still moving", 0.0D, TestWorld.speed(empty), 0.0D);
        game.assertClientSettled();
    }

    @Test
    public void trainOverBoostersStopsOnServerAndClient()
    {
        Game game = game(BOOSTERS);
        EntityMinecart[] train = {
            game.server.cart(EntityMinecart.Type.RIDEABLE, 5.5D, 0.5D),
            game.server.cart(this.type, 4.0D, 0.5D),
            game.server.cart(EntityMinecart.Type.RIDEABLE, 2.4D, 0.5D),
        };
        game.server.chain(train);
        game.track(train);

        for (EntityMinecart cart : train)
        {
            cart.motionX = 0.2D;
        }

        double worst = 0.0D;

        for (int tick = 0; tick < 400; ++tick)
        {
            game.tick();
            worst = Math.max(worst, game.worstError());
        }

        assertTrue("the train didn't cross the boosters: " + train[2].posX, train[2].posX > 12.0D);
        assertTrue(String.format("client carts strayed %.3f from the server's", worst), worst < 0.5D);

        for (EntityMinecart cart : train)
        {
            assertEquals("server cart still moving", 0.0D, TestWorld.speed(cart), 0.0D);
        }

        game.assertClientSettled();
    }
}
