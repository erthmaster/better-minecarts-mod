package com.andrii.chainableminecarts.client;

import com.andrii.chainableminecarts.rail.RailPath;
import com.andrii.chainableminecarts.rail.TrackWalk;
import net.minecraft.block.BlockRailBase;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

/**
 * Watches one cart, on the server or the client, tick by tick: how far it is off the rails' path, whether it ever
 * steps back along the track, its biggest step, how sharply its yaw turns, and how far it faces off the track.
 */
final class UTurnProbe
{
    final String name;
    final EntityMinecart cart;
    private TrackWalk.Cursor progress;
    private float lastYaw;
    double offPath;
    double backStep;
    double biggestStep;
    double yawTurn;
    double misaligned;
    double travelled;
    String worstAt = "";
    String stepAt = "";
    String backAt = "";
    String yawAt = "";
    /** Ticks at the start not judged: the client copy is still settling onto where the server has it. */
    static final int SETTLE = 5;
    /** Most a cart's yaw may turn in a tick: a turn's radius is half a block, 0.4 / 0.5 radians at top speed. */
    static final double MAX_TURN = Math.toDegrees(0.4D / 0.5D) + 4.0D;

    UTurnProbe(String name, EntityMinecart cart, double dirX, double dirZ)
    {
        this.name = name;
        this.cart = cart;
        this.progress = TrackWalk.at(cart, dirX, dirZ);
        this.lastYaw = cart.rotationYaw;
    }

    void sample(int tick)
    {
        EntityMinecart cart = this.cart;
        RailPath.Point onPath = pathPoint(cart);
        double off = onPath == null ? 9.0D : Math.hypot(onPath.x - cart.posX, onPath.z - cart.posZ);

        if (off > this.offPath)
        {
            this.offPath = off;
            this.worstAt = String.format("tick %d at %.3f, %.3f", tick, cart.posX, cart.posZ);
        }

        double moved = this.progress == null ? Double.NaN : this.progress.offsetTo(cart.posX, cart.posZ, 2.0D);

        if (Double.isNaN(moved))
        {
            // Lost it: start again from here
            this.progress = TrackWalk.at(cart, this.progress == null ? 1.0D : this.progress.dirX, this.progress == null ? 0.0D : this.progress.dirZ);
            this.backStep = Math.max(this.backStep, 9.0D);
        }
        else
        {
            if (tick >= SETTLE && -moved > this.backStep)
            {
                this.backStep = -moved;
                this.backAt = String.format("back@%d %.3f,%.3f", tick, cart.posX, cart.posZ);
            }

            if (tick >= SETTLE && Math.abs(moved) > this.biggestStep)
            {
                this.biggestStep = Math.abs(moved);
                this.stepAt = String.format("step@%d %.3f,%.3f", tick, cart.posX, cart.posZ);
            }

            this.travelled += moved;
            this.progress.advance(moved);
        }

        float turn = Math.abs(MathHelper.wrapDegrees(cart.rotationYaw - this.lastYaw));

        if (tick >= SETTLE && turn > this.yawTurn)
        {
            this.yawTurn = turn;
            this.yawAt = String.format("yaw@%d %.3f,%.3f", tick, cart.posX, cart.posZ);
        }

        this.lastYaw = cart.rotationYaw;

        if (onPath != null && tick >= SETTLE)
        {
            double yaw = Math.toRadians(cart.rotationYaw);
            double along = Math.abs(Math.cos(yaw) * onPath.dirX + Math.sin(yaw) * onPath.dirZ);
            this.misaligned = Math.max(this.misaligned, Math.toDegrees(Math.acos(Math.min(1.0D, along))));
        }
    }

    /** The nearest point of the rail the cart is on, found as vanilla finds it; null off the rails. */
    static RailPath.Point pathPoint(EntityMinecart cart)
    {
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

        return RailPath.project(pos, ((BlockRailBase)state.getBlock()).getRailDirection(cart.world, pos, state, cart), cart.posX, cart.posZ);
    }

    @Override
    public String toString()
    {
        return String.format("%-8s off %.3f  back %.3f  step %.3f  yaw %5.1f  misaligned %5.1f  travelled %6.2f  (%s | %s | %s | %s)",
            this.name, this.offPath, this.backStep, this.biggestStep, this.yawTurn, this.misaligned, this.travelled,
            this.worstAt, this.backAt, this.stepAt, this.yawAt);
    }
}
