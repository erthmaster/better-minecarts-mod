package com.andrii.chainableminecarts.physics;

import com.andrii.chainableminecarts.ChainableMinecarts;
import com.andrii.chainableminecarts.ModConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Last thing each server tick: every cart's speed is capped at how fast it can move on its rail
 * ({@link CartBody#limitSpeed}). After this, a cart covers exactly its speed each tick on straights and turns alike,
 * so trains keep their spacing round bends and clients can tell exactly where a cart will be.
 * <p>
 * A cart left creeping slower than {@code restSpeed} is stopped outright. Trains and collisions can leave a stopped
 * cart with a sliver of speed each tick; vanilla only tells clients about a new speed once it has changed by a fair
 * amount, or has become exactly nothing, so a creep like that would never reach them and they would carry on showing
 * the cart's old speed.
 */
@Mod.EventBusSubscriber(modid = ChainableMinecarts.MODID)
public final class SpeedLimit
{
    private SpeedLimit()
    {
    }

    /** Lowest priority, so it runs after trains, furnaces and collisions have all set their speeds. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onWorldTick(TickEvent.WorldTickEvent event)
    {
        if (event.phase == TickEvent.Phase.END && event.world instanceof WorldServer)
        {
            limitAll(event.world);
        }
    }

    /** Caps every cart's speed in a world, and stops the ones barely moving. */
    public static void limitAll(World world)
    {
        for (Entity entity : world.loadedEntityList)
        {
            if (entity instanceof EntityMinecart && !entity.isDead)
            {
                EntityMinecart cart = (EntityMinecart)entity;
                CartBody.limitSpeed(cart);

                if (cart.motionX * cart.motionX + cart.motionZ * cart.motionZ < ModConfig.carts.restSpeed * ModConfig.carts.restSpeed)
                {
                    cart.motionX = 0.0D;
                    cart.motionZ = 0.0D;
                }
            }
        }
    }
}
