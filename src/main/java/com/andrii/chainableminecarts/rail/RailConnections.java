package com.andrii.chainableminecarts.rail;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRailBase;
import net.minecraft.block.BlockRailBase.EnumRailDirection;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Turning rails to meet each other, used by {@link RailPlacement} and by the crossing and the switch.
 * <p>
 * A rail is only ever turned when it has an end going spare: one of its two ends isn't joined to anything. An end
 * only counts as joined when the rail there points back at it, so a rail whose loose end merely faces a line
 * running past can still swing round. A rail joined at both ends is part of a track and is left alone, which is
 * what stops a new rail from breaking a line that runs into a crossing or a switch (vanilla steals those).
 */
public final class RailConnections
{
    private RailConnections()
    {
    }

    /** Turns the rails on the given sides of {@code anchor} to meet it, where they have an end going spare. */
    public static void connectNeighbours(World world, BlockPos anchor, EnumFacing... sides)
    {
        for (EnumFacing side : sides)
        {
            attach(world, anchor, side);
        }
    }

    /**
     * Turns the rail on one side of {@code anchor} to meet it, as a turn where its other end is joined to a rail.
     * Does nothing if it already meets the anchor, or if both its ends are joined.
     */
    public static void attach(World world, BlockPos anchor, EnumFacing side)
    {
        BlockPos railPos = anchor.offset(side);
        IBlockState state = world.getBlockState(railPos);
        Block block = state.getBlock();

        // The crossing and the switch have a fixed layout of their own, and a rail being placed keeps the shape
        // the player gave it
        if (!(block instanceof BlockRailBase) || block instanceof BlockRailIntersection || block instanceof BlockRailDoubleTurn
            || RailPlacement.isBeingPlaced(world, railPos))
        {
            return;
        }

        IProperty<EnumRailDirection> shapeProperty = ((BlockRailBase)block).getShapeProperty();
        EnumRailDirection shape = state.getValue(shapeProperty);
        EnumFacing towardsAnchor = side.getOpposite();

        // Already met, or a slope, which meets us at its lower end anyway
        if (shape.isAscending() || connects(shape, towardsAnchor))
        {
            return;
        }

        EnumFacing keep = null;

        for (EnumFacing end : connectedSides(shape))
        {
            if (joined(world, railPos, end))
            {
                if (keep != null)
                {
                    // Joined at both ends: this rail is part of a track
                    return;
                }

                keep = end;
            }
        }

        EnumRailDirection wanted = RailConnections.straight(towardsAnchor.getAxis());

        if (keep != null && keep.getAxis() != towardsAnchor.getAxis())
        {
            EnumRailDirection curve = curve(towardsAnchor, keep);
            wanted = shapeProperty.getAllowedValues().contains(curve) ? curve : wanted;
        }

        if (wanted != shape)
        {
            world.setBlockState(railPos, state.withProperty(shapeProperty, wanted), 3);
        }
    }

    /** Whether the rail on this side is joined to this one: it points back, or it's a crossing or switch. */
    private static boolean joined(World world, BlockPos railPos, EnumFacing side)
    {
        BlockPos other = railPos.offset(side);
        return pointsBack(world, other, side.getOpposite())
            || pointsBack(world, other.up(), side.getOpposite())
            || pointsBack(world, other.down(), side.getOpposite());
    }

    private static boolean pointsBack(World world, BlockPos railPos, EnumFacing towardsUs)
    {
        IBlockState state = world.getBlockState(railPos);
        Block block = state.getBlock();

        if (!(block instanceof BlockRailBase))
        {
            return false;
        }

        // The crossing and the switch carry track through on every side they use
        if (block instanceof BlockRailIntersection)
        {
            return true;
        }

        if (block instanceof BlockRailDoubleTurn)
        {
            return towardsUs != state.getValue(BlockRailDoubleTurn.FACING).getOpposite();
        }

        return connects(state.getValue(((BlockRailBase)block).getShapeProperty()), towardsUs);
    }

    /**
     * Whether the rail at this spot could carry track on to the given side: it already points that way, or it has
     * an end going spare. A rail in the middle of a line has both ends used, so nothing can join onto its side.
     */
    public static boolean canConnect(World world, BlockPos railPos, EnumFacing towardsUs)
    {
        IBlockState state = world.getBlockState(railPos);
        Block block = state.getBlock();

        if (!(block instanceof BlockRailBase))
        {
            return false;
        }

        // A crossing takes track from any side; a switch from every side but the one behind its secondary road
        if (block instanceof BlockRailIntersection)
        {
            return true;
        }

        if (block instanceof BlockRailDoubleTurn)
        {
            return towardsUs != state.getValue(BlockRailDoubleTurn.FACING).getOpposite();
        }

        EnumRailDirection shape = state.getValue(((BlockRailBase)block).getShapeProperty());

        if (connects(shape, towardsUs))
        {
            return true;
        }

        // A slope's ends are where they are; a flat rail can swing a spare end round to meet us
        if (shape.isAscending())
        {
            return false;
        }

        for (EnumFacing end : connectedSides(shape))
        {
            if (!hasRail(world, railPos.offset(end)))
            {
                return true;
            }
        }

        return false;
    }

    /** Whether the rail at this spot has an end facing the given way. */
    public static boolean pointsAt(World world, BlockPos railPos, EnumFacing side)
    {
        IBlockState state = world.getBlockState(railPos);
        return state.getBlock() instanceof BlockRailBase
            && connects(((BlockRailBase)state.getBlock()).getRailDirection(world, railPos, state, null), side);
    }

    private static boolean connects(EnumRailDirection shape, EnumFacing side)
    {
        for (EnumFacing connected : connectedSides(shape))
        {
            if (connected == side)
            {
                return true;
            }
        }

        return false;
    }

    /** The two sides a rail shape connects. */
    public static EnumFacing[] connectedSides(EnumRailDirection shape)
    {
        switch (shape)
        {
            case EAST_WEST:
            case ASCENDING_EAST:
            case ASCENDING_WEST:
                return new EnumFacing[] {EnumFacing.WEST, EnumFacing.EAST};
            case SOUTH_EAST:
                return new EnumFacing[] {EnumFacing.SOUTH, EnumFacing.EAST};
            case SOUTH_WEST:
                return new EnumFacing[] {EnumFacing.SOUTH, EnumFacing.WEST};
            case NORTH_WEST:
                return new EnumFacing[] {EnumFacing.NORTH, EnumFacing.WEST};
            case NORTH_EAST:
                return new EnumFacing[] {EnumFacing.NORTH, EnumFacing.EAST};
            default:
                return new EnumFacing[] {EnumFacing.NORTH, EnumFacing.SOUTH};
        }
    }

    /** A rail beside this spot, level or one block up or down, as vanilla looks for neighbours. */
    public static boolean hasRail(World world, BlockPos pos)
    {
        return BlockRailBase.isRailBlock(world, pos) || BlockRailBase.isRailBlock(world, pos.up()) || BlockRailBase.isRailBlock(world, pos.down());
    }

    public static EnumRailDirection straight(EnumFacing.Axis axis)
    {
        return axis == EnumFacing.Axis.X ? EnumRailDirection.EAST_WEST : EnumRailDirection.NORTH_SOUTH;
    }

    /** The curve joining two sides at right angles to each other. */
    public static EnumRailDirection curve(EnumFacing a, EnumFacing b)
    {
        boolean north = a == EnumFacing.NORTH || b == EnumFacing.NORTH;
        boolean east = a == EnumFacing.EAST || b == EnumFacing.EAST;

        if (north)
        {
            return east ? EnumRailDirection.NORTH_EAST : EnumRailDirection.NORTH_WEST;
        }

        return east ? EnumRailDirection.SOUTH_EAST : EnumRailDirection.SOUTH_WEST;
    }
}
