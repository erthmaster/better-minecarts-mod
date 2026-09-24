package com.andrii.chainableminecarts.physics;

import com.andrii.chainableminecarts.ModConfig;
import javax.annotation.Nullable;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.common.IMinecartCollisionHandler;

/**
 * Replaces vanilla minecart collisions. Vanilla averages the two carts' speeds and then adds a fixed push apart
 * on every contact tick, which makes carts bounce off each other forever. Here carts ignore each other in the
 * middle of the tick; {@link CartContacts} handles cart-to-cart contact rigidly at the end of it. Entities barely
 * move carts.
 */
public class HeavyCollisionHandler implements IMinecartCollisionHandler
{
    @Override
    public void onEntityCollision(EntityMinecart cart, Entity other)
    {
        // Cart-to-cart contact is left to CartContacts
        if (cart.world.isRemote || cart.noClip || other.noClip || cart.isPassenger(other) || other instanceof EntityMinecart)
        {
            return;
        }

        double dx = other.posX - cart.posX;
        double dz = other.posZ - cart.posZ;
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (dist < 1.0E-4D)
        {
            return;
        }

        double nx = dx / dist;
        double nz = dz / dist;

        double push = SolidCarts.ENTITY_PUSH * Math.min(1.0D, 1.0D / dist);
        other.addVelocity(nx * push * 0.5D, 0.0D, nz * push * 0.5D);
        CartBody.applyImpulse(cart, -nx, -nz, push * ModConfig.carts.entityPushFactor);
    }

    /**
     * What blocks a moving cart. Vanilla includes other carts, but running into one then zeroes the cart's speed
     * before any collision is handled, so a slow cart (one being pushed by a player) stops dead against the next
     * cart instead of pushing it. Carts meet only through {@link CartContacts}; players and mobs still block.
     */
    @Override
    @Nullable
    public AxisAlignedBB getCollisionBox(EntityMinecart cart, Entity other)
    {
        return other.canBePushed() && !(other instanceof EntityMinecart) ? other.getEntityBoundingBox() : null;
    }

    // The box methods below match vanilla behaviour exactly

    @Override
    public AxisAlignedBB getMinecartCollisionBox(EntityMinecart cart)
    {
        return cart.getEntityBoundingBox().grow(0.2D, 0.0D, 0.2D);
    }

    @Override
    @Nullable
    public AxisAlignedBB getBoundingBox(EntityMinecart cart)
    {
        return null;
    }
}
