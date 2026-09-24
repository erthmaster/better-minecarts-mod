package com.andrii.chainableminecarts.rail;

import com.andrii.chainableminecarts.ChainableMinecarts;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRailBase;
import net.minecraft.block.BlockRailBase.EnumRailDirection;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Places rails the way the player means them, instead of the way vanilla guesses.
 * <ul>
 * <li>A rail points where the player is looking. Looking along a line and passing a side track lays straight track,
 * rather than bending into the side track.</li>
 * <li>A turn is laid wherever two sides with track meet at a right angle, and the way the player looks picks
 * between the turns on offer. A turn always joins two sides that have track, so it never leads nowhere.</li>
 * <li>Where no turn fits, straight track runs along whichever axis meets more track, so a rail dropped between two
 * ends joins them instead of running past. The player's look decides when neither axis meets more.</li>
 * <li>Only track that could take the connection counts: a rail in the middle of a line has both its ends used,
 * so a new rail beside it runs past instead of pointing at its side.</li>
 * <li>Sneaking always lays straight track, whatever turn would otherwise be made.</li>
 * <li>Rails that are already part of a track are never rearranged by a placement. Vanilla steals a neighbour
 * whenever it thinks it has a free end, which breaks a line that ran into a crossing or a switch. Only a rail with
 * a free end is turned, to meet the new rail.</li>
 * </ul>
 * Forge fires the place event before the block's own placement logic runs, so the rails around the spot are noted
 * first and put back afterwards.
 */
@Mod.EventBusSubscriber(modid = ChainableMinecarts.MODID)
public final class RailPlacement
{
    private static final Map<BlockPos, IBlockState> NEIGHBOURS_BEFORE = new HashMap<>();
    @Nullable
    private static World world;
    @Nullable
    private static BlockPos pos;
    private static EnumFacing look = EnumFacing.NORTH;
    private static boolean straightOnly;
    private static boolean applying;

    private RailPlacement()
    {
    }

    /** Fired before the placed block's own logic runs: note what the rails around the spot look like. */
    @SubscribeEvent
    public static void onPlace(BlockEvent.PlaceEvent event)
    {
        forget();

        if (event.getWorld().isRemote || !(event.getPlacedBlock().getBlock() instanceof BlockRailBase))
        {
            return;
        }

        world = event.getWorld();
        pos = event.getPos().toImmutable();
        look = event.getPlayer().getHorizontalFacing();
        straightOnly = event.getPlayer().isSneaking();

        for (EnumFacing side : EnumFacing.Plane.HORIZONTAL)
        {
            for (int dy = -1; dy <= 1; ++dy)
            {
                BlockPos neighbour = pos.offset(side).up(dy);
                IBlockState state = world.getBlockState(neighbour);

                if (state.getBlock() instanceof BlockRailBase)
                {
                    NEIGHBOURS_BEFORE.put(neighbour, state);
                }
            }
        }
    }

    /**
     * The placed rail's own logic sends these out, both while it runs and once it's done. Fixing up each time keeps
     * the last word, whatever it rearranged in between.
     */
    @SubscribeEvent
    public static void onNeighbourNotify(BlockEvent.NeighborNotifyEvent event)
    {
        if (applying || world == null || event.getWorld() != world || !event.getPos().equals(pos))
        {
            return;
        }

        applying = true;

        try
        {
            apply(world, pos, look);
        }
        finally
        {
            applying = false;
        }
    }

    /** A placement is over by the end of the tick. */
    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent event)
    {
        if (event.phase == TickEvent.Phase.END && event.world == world)
        {
            forget();
        }
    }

    /** Whether this rail is the one a player is placing right now: its shape is theirs to decide. */
    static boolean isBeingPlaced(World inWorld, BlockPos railPos)
    {
        return world == inWorld && railPos.equals(pos);
    }

    private static void forget()
    {
        world = null;
        pos = null;
        NEIGHBOURS_BEFORE.clear();
    }

    private static void apply(World world, BlockPos pos, EnumFacing look)
    {
        IBlockState state = world.getBlockState(pos);
        Block block = state.getBlock();

        if (!(block instanceof BlockRailBase))
        {
            return;
        }

        // Put back any rail the placement rearranged
        for (Map.Entry<BlockPos, IBlockState> before : NEIGHBOURS_BEFORE.entrySet())
        {
            IBlockState now = world.getBlockState(before.getKey());

            if (now != before.getValue() && now.getBlock() == before.getValue().getBlock())
            {
                world.setBlockState(before.getKey(), before.getValue(), 3);
            }
        }

        // The crossing and the switch have their own fixed layout; they attach their neighbours themselves
        if (block instanceof BlockRailIntersection || block instanceof BlockRailDoubleTurn)
        {
            return;
        }

        IProperty<EnumRailDirection> shapeProperty = ((BlockRailBase)block).getShapeProperty();
        EnumRailDirection shape = shapeFor(world, pos, look, (BlockRailBase)block, shapeProperty);

        if (state.getValue(shapeProperty) != shape)
        {
            world.setBlockState(pos, state.withProperty(shapeProperty, shape), 3);
        }

        // Rails at the two ends turn to meet this one, as long as they have an end going spare
        for (EnumFacing side : RailConnections.connectedSides(shape))
        {
            RailConnections.attach(world, pos, side);
        }
    }

    /**
     * A turn wherever one can be made, otherwise the straight that joins the track around it, otherwise straight
     * the way the player looks. Sneaking always lays straight track the way they look.
     */
    private static EnumRailDirection shapeFor(World world, BlockPos pos, EnumFacing look, BlockRailBase rail, IProperty<EnumRailDirection> shapeProperty)
    {
        if (!straightOnly && rail.isFlexibleRail(world, pos))
        {
            EnumRailDirection curve = turnFor(world, pos, look, shapeProperty);

            if (curve != null)
            {
                return curve;
            }
        }

        EnumFacing along = straightOnly ? look : connectingStraight(world, pos, look);

        if (rail.canMakeSlopes(world, pos))
        {
            for (EnumFacing side : new EnumFacing[] {along, along.getOpposite()})
            {
                if (BlockRailBase.isRailBlock(world, pos.offset(side).up()))
                {
                    return ascending(side);
                }
            }
        }

        return RailConnections.straight(along.getAxis());
    }

    /**
     * Which way straight track should run: whichever of the two axes meets more track, and the way the player
     * looks when they meet the same amount. Track either side of the spot is joined rather than run past.
     */
    private static EnumFacing connectingStraight(World world, BlockPos pos, EnumFacing look)
    {
        EnumFacing across = look.rotateY();
        return ends(world, pos, across) > ends(world, pos, look) ? across : look;
    }

    /** How many of the two ends of this axis have track that could take the connection. */
    private static int ends(World world, BlockPos pos, EnumFacing side)
    {
        int count = joinable(world, pos, side) ? 1 : 0;
        return count + (joinable(world, pos, side.getOpposite()) ? 1 : 0);
    }

    /** Whether the track on this side of the spot could join onto a rail placed here, level or a block up or down. */
    private static boolean joinable(World world, BlockPos pos, EnumFacing side)
    {
        BlockPos railPos = pos.offset(side);
        EnumFacing towardsUs = side.getOpposite();
        return RailConnections.canConnect(world, railPos, towardsUs)
            || RailConnections.canConnect(world, railPos.up(), towardsUs)
            || RailConnections.canConnect(world, railPos.down(), towardsUs);
    }

    /**
     * The turn to lay here, or null if no two sides with track meet at a right angle. The way the player looks
     * picks between them: the track they face comes first, then the track behind them, then either side.
     */
    @Nullable
    private static EnumRailDirection turnFor(World world, BlockPos pos, EnumFacing look, IProperty<EnumRailDirection> shapeProperty)
    {
        for (EnumFacing first : new EnumFacing[] {look, look.getOpposite(), look.rotateY(), look.rotateYCCW()})
        {
            if (!joinable(world, pos, first))
            {
                continue;
            }

            EnumFacing second = sideTrack(world, pos, first);

            if (second != null)
            {
                EnumRailDirection curve = RailConnections.curve(first, second);

                if (shapeProperty.getAllowedValues().contains(curve))
                {
                    return curve;
                }
            }
        }

        return null;
    }

    /** Track to either side of the given direction, preferring one that already points this way. */
    @Nullable
    private static EnumFacing sideTrack(World world, BlockPos pos, EnumFacing towards)
    {
        EnumFacing found = null;

        for (EnumFacing side : new EnumFacing[] {towards.rotateY(), towards.rotateYCCW()})
        {
            if (joinable(world, pos, side))
            {
                if (RailConnections.pointsAt(world, pos.offset(side), side.getOpposite()))
                {
                    return side;
                }

                if (found == null)
                {
                    found = side;
                }
            }
        }

        return found;
    }

    private static EnumRailDirection ascending(EnumFacing towards)
    {
        switch (towards)
        {
            case NORTH:
                return EnumRailDirection.ASCENDING_NORTH;
            case SOUTH:
                return EnumRailDirection.ASCENDING_SOUTH;
            case WEST:
                return EnumRailDirection.ASCENDING_WEST;
            default:
                return EnumRailDirection.ASCENDING_EAST;
        }
    }
}
