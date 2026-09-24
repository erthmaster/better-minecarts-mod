package com.andrii.chainableminecarts.physics;

import javax.annotation.Nullable;
import net.minecraft.block.BlockRailBase;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

/**
 * Treats a minecart as a body that can only move along its rail. Vanilla {@code moveAlongTrack} keeps the full
 * horizontal speed and just turns it to follow the rail, so a sideways push would become speed along the track.
 * Impulses here only change the speed along the rail, and motion is written back already aligned with it.
 */
public final class CartBody
{
    private static final double INV_SQRT2 = 1.0D / Math.sqrt(2.0D);
    /** How far a cart's wall-testing box is kept in from its sides. */
    private static final double WALL_MARGIN = 0.01D;
    /** How far a cart's wall-testing box is raised off its bottom. */
    private static final double WALL_CLEARANCE = 0.25D;

    private CartBody()
    {
    }

    /** Every cart weighs the same: who or what is sitting in it changes nothing. */
    public static double mass(EntityMinecart cart)
    {
        return 1.0D;
    }

    /** Unit horizontal {x, z} direction of the rail the cart is on, or null if it isn't on one. */
    @Nullable
    public static double[] railAxis(EntityMinecart cart)
    {
        if (!cart.canUseRail())
        {
            return null;
        }

        // Same lookup as EntityMinecart.onUpdate
        BlockPos pos = new BlockPos(MathHelper.floor(cart.posX), MathHelper.floor(cart.posY), MathHelper.floor(cart.posZ));

        if (BlockRailBase.isRailBlock(cart.world, pos.down()))
        {
            pos = pos.down();
        }

        IBlockState state = cart.world.getBlockState(pos);

        if (!BlockRailBase.isRailBlock(state))
        {
            return null;
        }

        switch (((BlockRailBase)state.getBlock()).getRailDirection(cart.world, pos, state, cart))
        {
            case NORTH_SOUTH:
            case ASCENDING_NORTH:
            case ASCENDING_SOUTH:
                return new double[] {0.0D, 1.0D};
            case EAST_WEST:
            case ASCENDING_EAST:
            case ASCENDING_WEST:
                return new double[] {1.0D, 0.0D};
            case SOUTH_EAST:
            case NORTH_WEST:
                return new double[] {INV_SQRT2, -INV_SQRT2};
            default: // SOUTH_WEST, NORTH_EAST
                return new double[] {INV_SQRT2, INV_SQRT2};
        }
    }

    /** How much of the cart's own speed shows up along direction n. */
    private static double coupling(@Nullable double[] axis, double nx, double nz)
    {
        return axis == null ? 1.0D : axis[0] * nx + axis[1] * nz;
    }

    /**
     * The cart's speed in its own frame: signed speed along the rail axis (positive in the axis direction), or
     * velocity along n when off rail.
     */
    static double speed(EntityMinecart cart, @Nullable double[] axis, double nx, double nz)
    {
        if (axis == null)
        {
            return cart.motionX * nx + cart.motionZ * nz;
        }

        double magnitude = Math.sqrt(cart.motionX * cart.motionX + cart.motionZ * cart.motionZ);
        // Vanilla keeps the magnitude and picks the rail direction with a non-negative dot product
        return cart.motionX * axis[0] + cart.motionZ * axis[1] < 0.0D ? -magnitude : magnitude;
    }

    static void setSpeed(EntityMinecart cart, @Nullable double[] axis, double nx, double nz, double oldSpeed, double newSpeed)
    {
        if (axis == null)
        {
            cart.motionX += (newSpeed - oldSpeed) * nx;
            cart.motionZ += (newSpeed - oldSpeed) * nz;
        }
        else
        {
            cart.motionX = newSpeed * axis[0];
            cart.motionZ = newSpeed * axis[1];
        }
    }

    /** Speed at which b moves away from a along unit direction n (a → b). Negative means approaching. */
    public static double relativeSpeed(EntityMinecart a, EntityMinecart b, double nx, double nz)
    {
        double[] axisA = railAxis(a);
        double[] axisB = railAxis(b);
        return coupling(axisB, nx, nz) * speed(b, axisB, nx, nz) - coupling(axisA, nx, nz) * speed(a, axisA, nx, nz);
    }

    /**
     * Applies equal and opposite impulses along n so that {@link #relativeSpeed} becomes {@code target}. Momentum is
     * kept, so the heavier or faster cart wins. Carts whose rails are nearly perpendicular to n (e.g. side by side on
     * parallel tracks) can't push each other and are left alone.
     */
    public static void setRelativeSpeed(EntityMinecart a, EntityMinecart b, double nx, double nz, double target)
    {
        double[] axisA = railAxis(a);
        double[] axisB = railAxis(b);
        double ca = coupling(axisA, nx, nz);
        double cb = coupling(axisB, nx, nz);
        double ma = mass(a);
        double mb = mass(b);
        double effectiveInvMass = ca * ca / ma + cb * cb / mb;

        if (effectiveInvMass < 1.0E-4D)
        {
            return;
        }

        double sa = speed(a, axisA, nx, nz);
        double sb = speed(b, axisB, nx, nz);
        double impulse = (target - (cb * sb - ca * sa)) / effectiveInvMass;
        setSpeed(a, axisA, nx, nz, sa, sa - impulse * ca / ma);
        setSpeed(b, axisB, nx, nz, sb, sb + impulse * cb / mb);
    }

    /**
     * The fastest a cart moves along the rail it's on, in blocks per tick: the rail's own limit or the cart's,
     * whichever is lower (0.4 on vanilla rails). Unlimited off the rails.
     */
    public static double railSpeedCap(EntityMinecart cart)
    {
        // Same lookup as EntityMinecart.onUpdate
        BlockPos pos = new BlockPos(MathHelper.floor(cart.posX), MathHelper.floor(cart.posY), MathHelper.floor(cart.posZ));

        if (BlockRailBase.isRailBlock(cart.world, pos.down()))
        {
            pos = pos.down();
        }

        IBlockState state = cart.world.getBlockState(pos);

        if (!BlockRailBase.isRailBlock(state))
        {
            return Double.POSITIVE_INFINITY;
        }

        return Math.min(((BlockRailBase)state.getBlock()).getRailMaxSpeed(cart.world, cart, pos), cart.getCurrentCartSpeedCapOnRail());
    }

    /**
     * Caps a cart's speed at how fast it can actually move on its rail. Vanilla lets the stored speed run far past
     * that (boosters push it up to 2) and caps the movement instead, one axis at a time, which lets a cart on a
     * turn's diagonal cover about 40% more ground per tick than on a straight. With the speed capped, a cart moves
     * exactly its speed everywhere.
     */
    public static void limitSpeed(EntityMinecart cart)
    {
        double cap = railSpeedCap(cart);
        double speed = Math.sqrt(cart.motionX * cart.motionX + cart.motionZ * cart.motionZ);

        if (speed > cap)
        {
            cart.motionX *= cap / speed;
            cart.motionZ *= cap / speed;
        }
    }

    /**
     * The part of a cart's box that runs into walls: raised off the bottom, so the ground and slopes under the
     * rails don't count, and a hair narrower, so a cart stopped exactly against a wall isn't counted as inside it.
     */
    public static AxisAlignedBB wallBox(AxisAlignedBB box)
    {
        return new AxisAlignedBB(box.minX + WALL_MARGIN, box.minY + WALL_CLEARANCE, box.minZ + WALL_MARGIN,
            box.maxX - WALL_MARGIN, box.maxY, box.maxZ - WALL_MARGIN);
    }

    /**
     * Whether a cart is up against a solid block the given way (a unit direction along its rail): a wall at the end
     * of the track, say, so it can't move that way. A cart already stuck inside a block counts as blocked neither
     * way, so it can still get out.
     */
    public static boolean blockedAlong(EntityMinecart cart, double dirX, double dirZ)
    {
        AxisAlignedBB box = wallBox(cart.getEntityBoundingBox());
        double reach = WALL_MARGIN * 2.0D;
        return !cart.world.collidesWithAnyBlock(box) && cart.world.collidesWithAnyBlock(box.offset(dirX * reach, 0.0D, dirZ * reach));
    }

    /** A cart's speed along a unit direction it travels in on its rail: positive that way, negative the other. */
    public static double speedAlong(EntityMinecart cart, double dirX, double dirZ)
    {
        return speed(cart, new double[] {dirX, dirZ}, dirX, dirZ);
    }

    /** Sets a cart's speed along a unit direction it travels in on its rail. */
    public static void setSpeedAlong(EntityMinecart cart, double dirX, double dirZ, double speed)
    {
        double[] direction = {dirX, dirZ};
        setSpeed(cart, direction, dirX, dirZ, speed(cart, direction, dirX, dirZ), speed);
    }

    /** Pushes one cart along unit direction n with the given impulse. */
    public static void applyImpulse(EntityMinecart cart, double nx, double nz, double impulse)
    {
        double[] axis = railAxis(cart);
        double speed = speed(cart, axis, nx, nz);
        setSpeed(cart, axis, nx, nz, speed, speed + impulse * coupling(axis, nx, nz) / mass(cart));
    }
}
