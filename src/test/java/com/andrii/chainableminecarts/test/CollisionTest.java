package com.andrii.chainableminecarts.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import net.minecraft.entity.item.EntityMinecart;
import org.junit.Test;

/** Carts that aren't chained still bump into each other like solid bodies, and never pass through. */
public class CollisionTest
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
    public void rollingCartShovesRestingCartAndNeverPassesIt()
    {
        TestWorld world = new TestWorld().track(STRAIGHT);
        EntityMinecart moving = world.cart(5.5D, 0.5D);
        EntityMinecart resting = world.cart(10.5D, 0.5D);
        moving.motionX = 0.4D;

        for (int tick = 0; tick < 200; ++tick)
        {
            world.tick();
            double gap = resting.posX - moving.posX - 0.98D;
            assertTrue(String.format("tick %d: carts overlap by %.3f", tick, -gap), gap > -0.02D);
        }

        assertTrue("the resting cart wasn't shoved", resting.posX > 11.0D);
        assertEquals(0.0D, TestWorld.speed(moving), 0.0D);
        assertEquals(0.0D, TestWorld.speed(resting), 0.0D);
    }

    @Test
    public void headOnCartsStopEachOther()
    {
        TestWorld world = new TestWorld().track(STRAIGHT);
        EntityMinecart left = world.cart(5.5D, 0.5D);
        EntityMinecart right = world.cart(12.5D, 0.5D);
        left.motionX = 0.3D;
        right.motionX = -0.3D;

        for (int tick = 0; tick < 200; ++tick)
        {
            world.tick();
            double gap = right.posX - left.posX - 0.98D;
            assertTrue(String.format("tick %d: carts overlap by %.3f", tick, -gap), gap > -0.02D);
        }

        // Equal and opposite: they meet in the middle and stay there
        assertEquals(9.0D, (left.posX + right.posX) * 0.5D, 0.05D);
        assertEquals(0.0D, TestWorld.speed(left), 0.0D);
        assertEquals(0.0D, TestWorld.speed(right), 0.0D);
    }

    @Test
    public void cartCatchesUpWithCartRoundAUTurn()
    {
        TestWorld world = new TestWorld().track(U_TURN);
        // One cart resting just round the U, one rolling up the other leg into it
        EntityMinecart resting = world.cart(1.5D, 1.5D);
        EntityMinecart moving = world.cart(0.5D, 5.5D);
        moving.motionZ = -0.4D;

        for (int tick = 0; tick < 200; ++tick)
        {
            world.tick();
            double gap = TestWorld.gap(moving, resting);
            assertTrue(String.format("tick %d: carts overlap by %.3f along the track", tick, -gap), Double.isNaN(gap) || gap > -0.02D);
        }

        assertTrue("the resting cart wasn't shoved down the other leg: " + resting.posZ, resting.posZ > 2.0D);
    }

    @Test
    public void cartsOnParallelTracksPassWithoutTouching()
    {
        String[] tracks = {
            "----------------------------------------",
            "----------------------------------------"};
        TestWorld world = new TestWorld().track(tracks);
        EntityMinecart moving = world.cart(3.5D, 0.5D);
        EntityMinecart resting = world.cart(8.5D, 1.5D);
        moving.motionX = 0.4D;
        // The same cart rolling with nothing next to it
        TestWorld alone = new TestWorld().track(tracks);
        EntityMinecart control = alone.cart(3.5D, 0.5D);
        control.motionX = 0.4D;

        for (int tick = 0; tick < 60; ++tick)
        {
            world.tick();
            alone.tick();
            assertEquals("tick " + tick + ": the moving cart was held up", control.posX, moving.posX, 1.0E-9D);
            assertEquals("tick " + tick + ": the resting cart was shoved", 8.5D, resting.posX, 1.0E-9D);
        }

        assertTrue("the moving cart never got past", moving.posX > 10.0D);
    }

    @Test
    public void cartsOnTheTwoLegsOfAUTurnPassWithoutTouching()
    {
        TestWorld world = new TestWorld().track(U_TURN);
        // Side by side across the U, but far apart along the track
        EntityMinecart moving = world.cart(0.5D, 10.5D);
        EntityMinecart resting = world.cart(1.5D, 7.5D);
        moving.motionZ = -0.4D;

        world.tick(12);

        assertTrue("the moving cart was held up", moving.posZ < 7.0D);
        assertEquals("the resting cart was shoved", 7.5D, resting.posZ, 1.0E-6D);
    }
}
