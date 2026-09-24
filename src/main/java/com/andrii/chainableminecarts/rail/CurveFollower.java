package com.andrii.chainableminecarts.rail;

import com.andrii.chainableminecarts.ChainableMinecarts;
import com.andrii.chainableminecarts.ModConfig;
import com.andrii.chainableminecarts.physics.CartBody;
import javax.annotation.Nullable;
import net.minecraft.block.BlockRailBase;
import net.minecraft.block.BlockRailBase.EnumRailDirection;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.minecart.MinecartUpdateEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Server side: carts on turns travel along the curve, not along vanilla's straight diagonal across the block.
 * <p>
 * Vanilla moves a cart on a turn in a straight line along the diagonal, carrying on in that line past the end of
 * it. Where two turns meet, as at the top of a U, the next turn's diagonal is square to that line, so the part of
 * the move that crossed the join is wasted: the cart stalls there for a tick, while the carts behind it keep coming.
 * And a cart one way round a U never gets past at all (see {@link RailPath#snapOntoCurve}).
 * <p>
 * So wherever a cart starts or ends its move on a turn, it is moved again, from where it started: the same distance
 * vanilla moved it, along the track itself, arcs and all. A cart then covers exactly its speed each tick on turns
 * as on straights, which is what trains keep their spacing by and what clients predict. Straights are left to
 * vanilla, which is exact there, and so is anything out of the ordinary (slopes, the end of the track, a cart
 * leaving the rails), where the cart is just put onto the curve as before.
 */
@Mod.EventBusSubscriber(modid = ChainableMinecarts.MODID)
public final class CurveFollower
{
    private CurveFollower()
    {
    }

    /** Fired on the server at the end of each cart's update, after it has moved. */
    @SubscribeEvent
    public static void onMinecartUpdate(MinecartUpdateEvent event)
    {
        EntityMinecart cart = event.getMinecart();

        if (ModConfig.curves.smoothCurves && !cart.world.isRemote && !moveAlongTrack(cart))
        {
            RailPath.snapOntoCurve(cart, event.getPos());
        }
    }

    /** Redoes this tick's move along the track; false where it's left to vanilla. */
    private static boolean moveAlongTrack(EntityMinecart cart)
    {
        World world = cart.world;
        BlockPos from = railUnder(world, cart.prevPosX, cart.prevPosY, cart.prevPosZ);
        BlockPos to = railUnder(world, cart.posX, cart.posY, cart.posZ);

        if (from == null || to == null || from.getY() != to.getY())
        {
            return false;
        }

        EnumRailDirection startShape = shape(cart, from);
        EnumRailDirection endShape = shape(cart, to);

        if (startShape.isAscending() || endShape.isAscending() || !RailPath.isCurve(startShape) && !RailPath.isCurve(endShape))
        {
            return false;
        }

        // How far vanilla moved it: from where it put the cart on its rail before moving, to where it ended up
        double[] start = RailPath.vanillaStart(from, startShape, cart.prevPosX, cart.prevPosZ);
        double movedX = cart.posX - start[0];
        double movedZ = cart.posZ - start[1];
        double distance = Math.sqrt(movedX * movedX + movedZ * movedZ);

        // The same distance from where it started, setting off the way it went
        TrackWalk.Cursor cursor = TrackWalk.at(cart, cart.prevPosX, cart.prevPosY, cart.prevPosZ, movedX, movedZ);

        if (cursor == null || !cursor.advance(distance) || cursor.rail().getY() != from.getY())
        {
            return false;
        }

        // Not into a wall: vanilla's move stopped at it, but the same distance along a curve can reach further
        AxisAlignedBB box = cart.getEntityBoundingBox();
        AxisAlignedBB moved = box.offset(cursor.x - cart.posX, 0.0D, cursor.z - cart.posZ);

        if (world.collidesWithAnyBlock(CartBody.wallBox(moved)) && !world.collidesWithAnyBlock(CartBody.wallBox(box)))
        {
            return false;
        }

        cart.setPosition(cursor.x, cart.posY, cursor.z);

        // Heading along the track where it now is. Vanilla picks which way to go along a rail by the cart's heading,
        // and the diagonal it came in on can be square to the next rail's, which tells it nothing.
        double speed = Math.sqrt(cart.motionX * cart.motionX + cart.motionZ * cart.motionZ);
        cart.motionX = speed * cursor.dirX;
        cart.motionZ = speed * cursor.dirZ;
        return true;
    }

    /** The rail a cart at this position is on, found as vanilla finds it: the block, or the one below. */
    @Nullable
    private static BlockPos railUnder(World world, double x, double y, double z)
    {
        BlockPos pos = new BlockPos(MathHelper.floor(x), MathHelper.floor(y), MathHelper.floor(z));

        if (BlockRailBase.isRailBlock(world, pos.down()))
        {
            return pos.down();
        }

        return BlockRailBase.isRailBlock(world, pos) ? pos : null;
    }

    private static EnumRailDirection shape(EntityMinecart cart, BlockPos rail)
    {
        IBlockState state = cart.world.getBlockState(rail);
        return ((BlockRailBase)state.getBlock()).getRailDirection(cart.world, rail, state, cart);
    }
}
