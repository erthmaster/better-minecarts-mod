package com.andrii.chainableminecarts.physics;

import com.andrii.chainableminecarts.ChainableMinecarts;
import com.andrii.chainableminecarts.ModConfig;
import java.util.Map;
import java.util.WeakHashMap;
import javax.annotation.Nullable;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.event.world.GetCollisionBoxesEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Makes minecarts solid, like a block, for players and mobs: they can't walk through a cart and can stand on it.
 * Carts themselves are unchanged. Vanilla already stops a moving cart at a player or mob, and still seats mobs
 * in a free cart that rolls into them.
 * <p>
 * Vanilla pushes a cart only when an entity overlaps it, which can't happen any more. So walking into a cart's side
 * pushes it instead: mobs are detected here from their server-side movement, and players, whose movement runs on the
 * client, are reported by {@code CartPushInput} through {@code PushCartMessage}.
 */
@Mod.EventBusSubscriber(modid = ChainableMinecarts.MODID)
public final class SolidCarts
{
    /** Vanilla's push strength for a cart touching an entity; scaled by the config's entityPushFactor. */
    static final double ENTITY_PUSH = 0.05D;
    /** Slack for the server's check that a player is touching the cart they push (positions lag a little). */
    private static final double PLAYER_TOUCH_TOLERANCE = 0.5D;
    /** Fastest an entity inside a cart is moved out of it, in blocks per tick: faster than a player can sprint. */
    private static final double MAX_PUSH_OUT = 0.3D;
    /** How far clear of the cart's side an entity is left when pushed out. */
    private static final double PUSH_OUT_MARGIN = 1.0E-3D;

    /** One push per mover per tick; Entity.move asks for collision boxes several times while trying to step up. */
    private static final Map<Entity, Long> LAST_PUSH_TICK = new WeakHashMap<>();

    private SolidCarts()
    {
    }

    @SubscribeEvent
    public static void onGetCollisionBoxes(GetCollisionBoxesEvent event)
    {
        Entity mover = event.getEntity();

        if (!ModConfig.carts.solidCarts || !(mover instanceof EntityLivingBase))
        {
            return;
        }

        AxisAlignedBB sweep = event.getAabb();
        AxisAlignedBB moverBox = mover.getEntityBoundingBox();

        for (EntityMinecart cart : event.getWorld().getEntitiesWithinAABB(EntityMinecart.class, sweep))
        {
            AxisAlignedBB cartBox = cart.getEntityBoundingBox();

            // Riders don't collide with their own cart
            if (mover.isRidingSameEntity(cart))
            {
                continue;
            }

            if (cartBox.intersects(moverBox))
            {
                // Already inside it: free to get out, but not to go any further in
                AxisAlignedBB deeper = deeper(moverBox, cartBox);

                if (deeper != null)
                {
                    event.getCollisionBoxesList().add(deeper);
                }

                continue;
            }

            event.getCollisionBoxesList().add(cartBox);

            if (!event.getWorld().isRemote && !(mover instanceof EntityPlayer))
            {
                // The sweep box is the mover's box stretched by its movement, so the movement is the difference in both edges
                double moveX = (sweep.minX - moverBox.minX) + (sweep.maxX - moverBox.maxX);
                double moveZ = (sweep.minZ - moverBox.minZ) + (sweep.maxZ - moverBox.maxZ);
                push(mover, cart, moveX, moveZ);
            }
        }
    }

    /**
     * Moves an entity out of any solid cart it's inside, the shortest way out, as a block pushes out a player stuck
     * in it. Movement can't carry anyone into a cart, but they can still end up inside one: the server putting a
     * player back where a cart now is, or climbing out of a cart. Inside, the cart only stops them going further in
     * (see {@link #onGetCollisionBoxes}), and nothing in vanilla would push them out. Moved as their own movement,
     * so walls still stop them.
     */
    public static void pushOutOfCarts(Entity entity)
    {
        if (!ModConfig.carts.solidCarts || entity.isRiding() || entity.noClip || entity instanceof EntityPlayer && ((EntityPlayer)entity).isSpectator())
        {
            return;
        }

        AxisAlignedBB body = entity.getEntityBoundingBox();

        for (EntityMinecart cart : entity.world.getEntitiesWithinAABB(EntityMinecart.class, body))
        {
            AxisAlignedBB box = cart.getEntityBoundingBox();

            if (cart.isDead || !box.intersects(body))
            {
                continue;
            }

            double[] out = wayOut(body, box);

            for (int axis = 0; axis < 3; ++axis)
            {
                out[axis] = Math.copySign(Math.min(Math.abs(out[axis]) + (out[axis] != 0.0D ? PUSH_OUT_MARGIN : 0.0D), MAX_PUSH_OUT), out[axis]);
            }

            entity.move(MoverType.SELF, out[0], out[1], out[2]);
            return;
        }
    }

    /**
     * The shortest way out of a cart's box for a body inside it, as {x, y, z}, only one of them moving: out through
     * whichever side it's least far in by, or up out of the top (never down, into the ground).
     */
    private static double[] wayOut(AxisAlignedBB body, AxisAlignedBB box)
    {
        double outX = body.minX + body.maxX >= box.minX + box.maxX ? box.maxX - body.minX : box.minX - body.maxX;
        double outZ = body.minZ + body.maxZ >= box.minZ + box.maxZ ? box.maxZ - body.minZ : box.minZ - body.maxZ;
        double outY = box.maxY - body.minY;

        if (outY <= Math.abs(outX) && outY <= Math.abs(outZ))
        {
            return new double[] {0.0D, outY, 0.0D};
        }

        return Math.abs(outX) <= Math.abs(outZ) ? new double[] {outX, 0.0D, 0.0D} : new double[] {0.0D, 0.0D, outZ};
    }

    /**
     * For a body inside a cart: the part of the cart beyond its leading face on the way further in, which blocks
     * that way while leaving the body free to move out (or sideways). Null if there's nothing further in.
     */
    @Nullable
    private static AxisAlignedBB deeper(AxisAlignedBB body, AxisAlignedBB box)
    {
        double[] out = wayOut(body, box);

        if (out[0] < 0.0D && body.maxX < box.maxX)
        {
            return new AxisAlignedBB(body.maxX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
        }

        if (out[0] > 0.0D && body.minX > box.minX)
        {
            return new AxisAlignedBB(box.minX, box.minY, box.minZ, body.minX, box.maxY, box.maxZ);
        }

        if (out[2] < 0.0D && body.maxZ < box.maxZ)
        {
            return new AxisAlignedBB(box.minX, box.minY, body.maxZ, box.maxX, box.maxY, box.maxZ);
        }

        if (out[2] > 0.0D && body.minZ > box.minZ)
        {
            return new AxisAlignedBB(box.minX, box.minY, box.minZ, box.maxX, box.maxY, body.minZ);
        }

        if (out[1] > 0.0D && body.minY > box.minY)
        {
            return new AxisAlignedBB(box.minX, box.minY, box.minZ, box.maxX, body.minY, box.maxZ);
        }

        return null;
    }

    /** Server side of PushCartMessage: checks the player really is at the cart before pushing it. */
    public static void pushFromPlayer(EntityPlayerMP player, EntityMinecart cart, double dirX, double dirZ)
    {
        if (!ModConfig.carts.solidCarts || !player.isEntityAlive() || player.isSpectator() || player.isRiding() || cart.isDead
            || cart.world != player.world
            || !player.getEntityBoundingBox().grow(PLAYER_TOUCH_TOLERANCE, 0.0D, PLAYER_TOUCH_TOLERANCE).intersects(cart.getEntityBoundingBox()))
        {
            return;
        }

        push(player, cart, dirX, dirZ);
    }

    /** Pushes the cart along the mover's movement direction, if the mover is moving towards it from the side. */
    private static void push(Entity mover, EntityMinecart cart, double moveX, double moveZ)
    {
        double moveLength = Math.sqrt(moveX * moveX + moveZ * moveZ);
        double toCartX = cart.posX - mover.posX;
        double toCartZ = cart.posZ - mover.posZ;
        long tick = mover.world.getTotalWorldTime();

        // Must be moving towards the cart, not standing on top of it, and only once per tick
        if (moveLength < 1.0E-3D || moveX * toCartX + moveZ * toCartZ <= 0.0D
            || mover.getEntityBoundingBox().minY >= cart.getEntityBoundingBox().maxY - 1.0E-3D
            || Long.valueOf(tick).equals(LAST_PUSH_TICK.get(mover)))
        {
            return;
        }

        LAST_PUSH_TICK.put(mover, tick);
        CartBody.applyImpulse(cart, moveX / moveLength, moveZ / moveLength, ENTITY_PUSH * ModConfig.carts.entityPushFactor);
    }
}
