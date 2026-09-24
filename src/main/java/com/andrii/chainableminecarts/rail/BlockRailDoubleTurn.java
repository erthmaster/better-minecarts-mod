package com.andrii.chainableminecarts.rail;

import com.andrii.chainableminecarts.ChainableMinecarts;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import javax.annotation.Nullable;
import net.minecraft.block.Block;
import net.minecraft.block.BlockHorizontal;
import net.minecraft.block.BlockRailBase;
import net.minecraft.block.SoundType;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.properties.PropertyDirection;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Mirror;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * A switch where a secondary road joins a straight main road (a T-junction). Carts on the main road always go
 * straight through; they never turn into the secondary road. Carts coming in from the secondary road turn onto the
 * main road, one way unpowered and the other way when powered by redstone (a lever, for example).
 * <p>
 * {@link #FACING} is the side the secondary road joins from. Unpowered, carts from it turn towards the side
 * {@code FACING.rotateYCCW()} (for a secondary road from the south: east, as the texture is drawn); powered, towards
 * {@code FACING.rotateY()} (the texture mirrored).
 */
public class BlockRailDoubleTurn extends BlockRailBase
{
    /** Only here because vanilla's rail-connection code writes to it; the shape really comes from the other two. */
    public static final PropertyEnum<EnumRailDirection> SHAPE = PropertyEnum.create("shape", EnumRailDirection.class,
        shape -> shape == EnumRailDirection.NORTH_SOUTH || shape == EnumRailDirection.EAST_WEST);
    public static final PropertyDirection FACING = BlockHorizontal.FACING;
    public static final PropertyBool POWERED = PropertyBool.create("powered");

    /**
     * The route each cart took on the switch it's on. On the curve a cart moves diagonally, which can't tell a
     * turning cart from a straight one, so the route is picked as the cart comes on and kept while it's on the
     * switch. It is asked for every tick a cart spends there, so an entry from an earlier tick means the cart has
     * since left and the route is picked afresh: otherwise a cart coming back round a loop would take the route it
     * took last time, whatever the switch has been set to since.
     * <p>
     * Client and server both call this with their own cart objects.
     */
    private static final Map<EntityMinecart, Route> ROUTES = Collections.synchronizedMap(new WeakHashMap<>());
    /** How far past the middle of the switch, towards the secondary road, only the curve reaches. */
    private static final double OFF_MAIN_ROAD = 0.05D;

    private static final class Route
    {
        final BlockPos pos;
        final EnumRailDirection shape;
        long tick;

        Route(BlockPos pos, EnumRailDirection shape, long tick)
        {
            this.pos = pos;
            this.shape = shape;
            this.tick = tick;
        }
    }

    public BlockRailDoubleTurn()
    {
        super(true);
        this.setDefaultState(this.blockState.getBaseState()
            .withProperty(SHAPE, EnumRailDirection.EAST_WEST)
            .withProperty(FACING, EnumFacing.SOUTH)
            .withProperty(POWERED, false));
        this.setHardness(0.7F);
        this.setSoundType(SoundType.METAL);
        this.setCreativeTab(CreativeTabs.TRANSPORTATION);
        this.setRegistryName(ChainableMinecarts.MODID, "rail_double_turn");
        this.setUnlocalizedName(ChainableMinecarts.MODID + ".rail_double_turn");
    }

    @Override
    public EnumRailDirection getRailDirection(IBlockAccess world, BlockPos pos, IBlockState state, @Nullable EntityMinecart cart)
    {
        EnumFacing secondary = state.getValue(FACING);

        // Vanilla's connection logic (no cart) sees the main road
        if (cart == null)
        {
            return mainRoad(secondary);
        }

        double offset = towardsSecondary(cart, pos, secondary);
        boolean moving = cart.motionX * cart.motionX + cart.motionZ * cart.motionZ > 1.0E-6D;
        long now = cart.world.getTotalWorldTime();
        Route route = ROUTES.get(cart);

        // Keep the route while the cart is still going through, but let a cart standing still on the main road
        // take up whatever the switch has been set to since
        if (route != null && route.pos.equals(pos) && now - route.tick <= 1L && (moving || offset > OFF_MAIN_ROAD))
        {
            route.tick = now;
            return route.shape;
        }

        EnumRailDirection shape = comesFromSecondary(cart, secondary, offset, moving)
            ? turn(secondary, state.getValue(POWERED)) : mainRoad(secondary);

        // Only remember it for the switch the cart is actually on (other callers look at nearby rails too)
        if (MathHelper.floor(cart.posX) == pos.getX() && MathHelper.floor(cart.posZ) == pos.getZ())
        {
            ROUTES.put(cart, new Route(pos.toImmutable(), shape, now));
        }

        return shape;
    }

    /** How far the cart is past the middle of the switch towards the secondary road; only the curve goes there. */
    private static double towardsSecondary(EntityMinecart cart, BlockPos pos, EnumFacing secondary)
    {
        return (cart.posX - (pos.getX() + 0.5D)) * secondary.getFrontOffsetX()
            + (cart.posZ - (pos.getZ() + 0.5D)) * secondary.getFrontOffsetZ();
    }

    /** Whether the cart is on or coming from the secondary road rather than travelling along the main road. */
    private static boolean comesFromSecondary(EntityMinecart cart, EnumFacing secondary, double offset, boolean moving)
    {
        if (offset > OFF_MAIN_ROAD)
        {
            return true;
        }

        double alongX;
        double alongZ;

        if (moving)
        {
            alongX = Math.abs(cart.motionX);
            alongZ = Math.abs(cart.motionZ);
        }
        else
        {
            // Minecart yaw points along the rail it's on (x = cos, z = sin)
            double yaw = Math.toRadians(cart.rotationYaw);
            alongX = Math.abs(Math.cos(yaw));
            alongZ = Math.abs(Math.sin(yaw));
        }

        return secondary.getAxis() == EnumFacing.Axis.X ? alongX > alongZ : alongZ > alongX;
    }

    /**
     * The way through the switch for something coming in on the given side: from the secondary road it takes the
     * turn the switch is set to, from anywhere else it's the main road. Carts follow this, and so does anything
     * following the track across the switch.
     */
    public static EnumRailDirection routeFrom(IBlockState state, EnumFacing entry)
    {
        EnumFacing secondary = state.getValue(FACING);
        return entry == secondary ? turn(secondary, state.getValue(POWERED)) : mainRoad(secondary);
    }

    private static EnumRailDirection mainRoad(EnumFacing secondary)
    {
        return RailConnections.straight(secondary.rotateY().getAxis());
    }

    /** The curve from the secondary road onto the main road, towards the side the switch is set to. */
    private static EnumRailDirection turn(EnumFacing secondary, boolean powered)
    {
        return RailConnections.curve(secondary, powered ? secondary.rotateY() : secondary.rotateYCCW());
    }

    /**
     * Placed at a T of rails, the secondary road is the rail with no rail opposite it. Otherwise the player is taken
     * to be standing on the secondary road looking at the main road.
     */
    @Override
    public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing, float hitX, float hitY, float hitZ, int meta, EntityLivingBase placer)
    {
        EnumFacing secondary = null;

        for (EnumFacing side : EnumFacing.Plane.HORIZONTAL)
        {
            if (RailConnections.hasRail(world, pos.offset(side)) && !RailConnections.hasRail(world, pos.offset(side.getOpposite()))
                && RailConnections.hasRail(world, pos.offset(side.rotateY())) && RailConnections.hasRail(world, pos.offset(side.rotateYCCW())))
            {
                secondary = side;
            }
        }

        if (secondary == null)
        {
            secondary = placer.getHorizontalFacing().getOpposite();
            EnumFacing mainSide = secondary.rotateY();

            // A straight line of rails through here is the main road: the secondary road must be across it
            if (RailConnections.hasRail(world, pos.offset(secondary)) && RailConnections.hasRail(world, pos.offset(secondary.getOpposite()))
                && !RailConnections.hasRail(world, pos.offset(mainSide)) && !RailConnections.hasRail(world, pos.offset(mainSide.getOpposite())))
            {
                secondary = mainSide;
            }
        }

        return this.getDefaultState()
            .withProperty(FACING, secondary)
            .withProperty(SHAPE, mainRoad(secondary))
            .withProperty(POWERED, world.isBlockPowered(pos));
    }

    /** Redstone switches the turn, and the rails on the switch's three sides stay connected to it. */
    @Override
    protected void updateState(IBlockState state, World world, BlockPos pos, Block neighbour)
    {
        // The state handed to us is the one from when the change was noticed, which may already be out of date
        IBlockState current = world.getBlockState(pos);

        if (current.getBlock() != this)
        {
            return;
        }

        boolean powered = world.isBlockPowered(pos);

        if (powered != current.getValue(POWERED))
        {
            world.setBlockState(pos, current.withProperty(POWERED, powered), 3);
        }

        EnumFacing secondary = current.getValue(FACING);
        RailConnections.connectNeighbours(world, pos, secondary, secondary.rotateY(), secondary.rotateYCCW());
    }

    @Override
    public IProperty<EnumRailDirection> getShapeProperty()
    {
        return SHAPE;
    }

    @Override
    public boolean canMakeSlopes(IBlockAccess world, BlockPos pos)
    {
        return false;
    }

    @Override
    protected BlockStateContainer createBlockState()
    {
        return new BlockStateContainer(this, SHAPE, FACING, POWERED);
    }

    @Override
    public IBlockState getStateFromMeta(int meta)
    {
        EnumFacing secondary = EnumFacing.getHorizontal(meta & 3);
        return this.getDefaultState()
            .withProperty(FACING, secondary)
            .withProperty(SHAPE, mainRoad(secondary))
            .withProperty(POWERED, (meta & 4) != 0);
    }

    @Override
    public int getMetaFromState(IBlockState state)
    {
        return state.getValue(FACING).getHorizontalIndex() | (state.getValue(POWERED) ? 4 : 0);
    }

    @Override
    public IBlockState withRotation(IBlockState state, Rotation rot)
    {
        EnumFacing secondary = rot.rotate(state.getValue(FACING));
        return state.withProperty(FACING, secondary).withProperty(SHAPE, mainRoad(secondary));
    }

    @Override
    public IBlockState withMirror(IBlockState state, Mirror mirror)
    {
        // Mirroring also swaps which way the switch turns, so this is only approximate for a mirrored structure
        return state.withRotation(mirror.toRotation(state.getValue(FACING)));
    }
}
