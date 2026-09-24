package com.andrii.chainableminecarts.client;

import com.andrii.chainableminecarts.ChainableMinecarts;
import com.andrii.chainableminecarts.ModConfig;
import com.andrii.chainableminecarts.network.PushCartMessage;
import com.andrii.chainableminecarts.physics.SolidCarts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;

/**
 * Notices the local player walking into the side of a solid cart and asks the server to push it, and moves them out
 * of any cart they've ended up inside.
 */
@Mod.EventBusSubscriber(modid = ChainableMinecarts.MODID, value = Side.CLIENT)
public final class CartPushInput
{
    /** How far ahead of the player to look for a cart being walked into. */
    private static final double REACH = 0.15D;

    private CartPushInput()
    {
    }

    /** After the carts have been moved for this tick (CurveSmoothing), so the player is checked against where they are. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onClientTickLate(TickEvent.ClientTickEvent event)
    {
        EntityPlayerSP player = Minecraft.getMinecraft().player;

        if (event.phase == TickEvent.Phase.END && player != null && !Minecraft.getMinecraft().isGamePaused())
        {
            SolidCarts.pushOutOfCarts(player);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        EntityPlayerSP player = Minecraft.getMinecraft().player;

        if (event.phase != TickEvent.Phase.END || player == null || !ModConfig.carts.solidCarts || player.isRiding() || player.isSpectator())
        {
            return;
        }

        float forward = player.movementInput.moveForward;
        float strafe = player.movementInput.moveStrafe;

        if (forward == 0.0F && strafe == 0.0F)
        {
            return;
        }

        // Same direction maths as Entity.moveRelative
        float sin = MathHelper.sin(player.rotationYaw * 0.017453292F);
        float cos = MathHelper.cos(player.rotationYaw * 0.017453292F);
        double dirX = strafe * cos - forward * sin;
        double dirZ = forward * cos + strafe * sin;
        double length = Math.sqrt(dirX * dirX + dirZ * dirZ);
        dirX /= length;
        dirZ /= length;

        AxisAlignedBB box = player.getEntityBoundingBox();
        AxisAlignedBB ahead = box.offset(dirX * REACH, 0.0D, dirZ * REACH);

        for (EntityMinecart cart : player.world.getEntitiesWithinAABB(EntityMinecart.class, ahead))
        {
            AxisAlignedBB cartBox = cart.getEntityBoundingBox();

            // Already overlapping carts get the vanilla overlap push; standing on top doesn't push
            if (cartBox.intersects(box) || box.minY >= cartBox.maxY - 1.0E-3D)
            {
                continue;
            }

            ChainableMinecarts.network.sendToServer(new PushCartMessage(cart.getEntityId(), (float)dirX, (float)dirZ));
        }
    }
}
