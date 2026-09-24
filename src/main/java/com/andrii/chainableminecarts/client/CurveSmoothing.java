package com.andrii.chainableminecarts.client;

import com.andrii.chainableminecarts.ChainableMinecarts;
import com.andrii.chainableminecarts.ModConfig;
import com.andrii.chainableminecarts.rail.TrackWalk;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityMinecartFurnace;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;

/**
 * Keeps client-side carts on the track and turning smoothly. {@link CartFollower} moves each cart along the rails to
 * where it should be drawn; this then points it along the track, always turning the short way so it never snaps or
 * flips. Furnace carts instead point the way they drive, which is where the furnace's front faces. Whoever is riding
 * a cart turns with it, the way a boat turns its passengers. {@link SmoothMinecartRenderer} then draws the cart
 * exactly there.
 */
@Mod.EventBusSubscriber(modid = ChainableMinecarts.MODID, value = Side.CLIENT)
public final class CurveSmoothing
{
    /** Speed along the track, in blocks per tick, above which a cart is clearly travelling that way. */
    private static final double CLEARLY_MOVING = 0.01D;

    private CurveSmoothing()
    {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        Minecraft mc = Minecraft.getMinecraft();

        if (event.phase != TickEvent.Phase.END || mc.world == null || mc.isGamePaused() || !ModConfig.curves.smoothCurves)
        {
            return;
        }

        for (Entity entity : mc.world.loadedEntityList)
        {
            if (entity instanceof EntityMinecart)
            {
                EntityMinecart cart = (EntityMinecart)entity;
                followTrack(cart, ModConfig.carts.solidCarts ? mc.player : null);
                turnRiders(cart);
            }
        }
    }

    /**
     * Turns whoever is riding the cart by as much as the cart turned this tick, so their view follows the track
     * through a curve instead of the cart turning underneath them. Their body faces along the cart as well.
     */
    private static void turnRiders(EntityMinecart cart)
    {
        // A cart turning round on the spot is the same cart facing the other way: the rider doesn't spin with it
        float turn = MathHelper.wrapDegrees(cart.rotationYaw - cart.prevRotationYaw);

        if (turn > 90.0F)
        {
            turn -= 180.0F;
        }
        else if (turn < -90.0F)
        {
            turn += 180.0F;
        }

        if (!ModConfig.curves.riderTurnsWithCart || cart.getPassengers().isEmpty())
        {
            return;
        }

        // The cart's yaw is measured from east, entity yaw from south
        float cartFacing = cart.rotationYaw - 90.0F;

        for (Entity rider : cart.getPassengers())
        {
            rider.rotationYaw += turn;
            rider.setRotationYawHead(rider.getRotationYawHead() + turn);

            if (rider instanceof EntityLivingBase)
            {
                ((EntityLivingBase)rider).renderYawOffset = facingNearest(cartFacing, rider.rotationYaw);
            }
        }
    }

    /** The cart's facing, or its opposite, whichever the rider is turned closer to: a cart sits either way round. */
    private static float facingNearest(float cartFacing, float yaw)
    {
        return Math.abs(MathHelper.wrapDegrees(yaw - cartFacing)) <= 90.0F ? cartFacing : cartFacing + 180.0F;
    }

    /** Moves a cart along the track to where it's drawn this tick, and turns it to face along the track there. */
    static void followTrack(EntityMinecart cart, @Nullable Entity blocker)
    {
        TrackWalk.Cursor point = CartFollower.follow(cart, blocker);

        if (point == null)
        {
            return;
        }

        // Same angle convention as vanilla's minecart yaw. The track direction works either way round
        float trackYaw = (float)(MathHelper.atan2(point.dirZ, point.dirX) * (180.0D / Math.PI));
        double[] drive = cart instanceof EntityMinecartFurnace ? FurnaceClient.direction(cart) : null;
        float turn;

        if (drive != null)
        {
            // Keep the driving direction along the track (as the server does) and face it: the display block's
            // front points along the cart's yaw
            if (point.dirX * drive[0] + point.dirZ * drive[1] < 0.0D)
            {
                trackYaw += 180.0F;
                drive[0] = -point.dirX;
                drive[1] = -point.dirZ;
            }
            else
            {
                drive[0] = point.dirX;
                drive[1] = point.dirZ;
            }

            turn = MathHelper.wrapDegrees(trackYaw - cart.prevRotationYaw);
        }
        else
        {
            // Point the way the cart travels: the follower's point heads that way along the track. Standing still,
            // it stays the way round it was.
            double facing = Math.cos(Math.toRadians(cart.prevRotationYaw)) * point.dirX + Math.sin(Math.toRadians(cart.prevRotationYaw)) * point.dirZ;
            boolean backwards = CartFollower.speedOf(cart) > CLEARLY_MOVING ? false : facing < 0.0D;

            if (backwards)
            {
                trackYaw += 180.0F;
            }

            turn = MathHelper.wrapDegrees(trackYaw - cart.prevRotationYaw);
        }

        cart.rotationYaw = cart.prevRotationYaw + turn;

        // A big jump (just placed, or the direction just arrived) happens at once instead of as a quick spin
        if (Math.abs(turn) > 90.0F)
        {
            cart.prevRotationYaw = cart.rotationYaw;
        }

        // Keep the numbers small without breaking the interpolation between the two values
        if (Math.abs(cart.rotationYaw) > 720.0F)
        {
            float shift = 360.0F * Math.round(cart.rotationYaw / 360.0F);
            cart.rotationYaw -= shift;
            cart.prevRotationYaw -= shift;
        }
    }
}
