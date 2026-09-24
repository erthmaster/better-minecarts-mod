package com.andrii.chainableminecarts.client;

import static org.junit.Assert.assertTrue;

import net.minecraft.entity.item.EntityMinecart;
import org.junit.Test;

/** What a player sees: carts on a client, drawn by {@link CartFollower} from what the server sends ({@link Game}). */
public class CartFollowerTest
{
    private static final String[] STRAIGHT = {"----------------------------------------"};

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
    };

    @Test
    public void chainedPairPulledTogetherStopsOnTheClientToo()
    {
        Game game = new Game(STRAIGHT);
        EntityMinecart[] carts = game.carts(10.5D, 0.5D, 14.5D, 0.5D);
        game.server.chain(carts);
        game.track(carts);
        double worst = 0.0D;

        for (int tick = 0; tick < 100; ++tick)
        {
            game.tick();
            worst = Math.max(worst, game.worstError());
        }

        assertTrue(String.format("client carts strayed %.3f from the server's while pulling together", worst), worst < 0.5D);
        game.assertClientSettled();
    }

    @Test
    public void pushedTrainStopsOnTheClientToo()
    {
        Game game = new Game(STRAIGHT);
        EntityMinecart[] carts = game.carts(3.5D, 0.5D, 5.08D, 0.5D, 6.66D, 0.5D);
        game.server.chain(carts);
        game.track(carts);
        carts[0].motionX = -0.05D;
        carts[2].motionX = 0.4D;
        double worst = 0.0D;

        for (int tick = 0; tick < 200; ++tick)
        {
            game.tick();
            worst = Math.max(worst, game.worstError());
        }

        assertTrue(String.format("client carts strayed %.3f from the server's", worst), worst < 0.5D);
        game.assertClientSettled();
    }

    @Test
    public void trainRoundAUTurnStaysOnItsLegOnTheClient()
    {
        Game game = new Game(U_TURN);
        EntityMinecart[] carts = game.carts(0.5D, 5.5D, 0.5D, 7.08D, 0.5D, 8.66D);

        for (EntityMinecart cart : carts)
        {
            cart.motionZ = -0.4D;
        }

        game.server.chain(carts);
        game.track(carts);
        double worst = 0.0D;

        for (int tick = 0; tick < 150; ++tick)
        {
            game.tick();
            worst = Math.max(worst, game.worstError());
        }

        // The legs are a block apart: straying half that far would put a cart over the other leg
        assertTrue(String.format("client carts strayed %.3f from the server's", worst), worst < 0.5D);
        assertTrue("the train didn't get round the U", carts[0].posX > 1.0D);
        game.assertClientSettled();
    }
}
