package com.andrii.chainableminecarts.rail;

import com.andrii.chainableminecarts.ChainableMinecarts;
import javax.annotation.Nullable;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRailBase;
import net.minecraft.block.SoundType;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.util.Mirror;
import net.minecraft.util.Rotation;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * A rail crossing: north-south and east-west track through the same block. Each cart goes straight through along
 * the axis it's travelling on, so crossing traffic never turns. Forge asks the rail for its shape per cart, so the
 * rest of the mod (rail physics, curve smoothing, the renderer) follows the same straight line automatically.
 * <p>
 * Vanilla's rail-connection logic only understands two connected sides, so the rails around the crossing are
 * pointed back at it by {@link RailConnections} whenever something nearby changes.
 */
public class BlockRailIntersection extends BlockRailBase
{
    public static final PropertyEnum<EnumRailDirection> SHAPE = PropertyEnum.create("shape", EnumRailDirection.class,
        shape -> shape == EnumRailDirection.NORTH_SOUTH || shape == EnumRailDirection.EAST_WEST);

    public BlockRailIntersection()
    {
        // Like powered rails: no curves
        super(true);
        this.setDefaultState(this.blockState.getBaseState().withProperty(SHAPE, EnumRailDirection.NORTH_SOUTH));
        this.setHardness(0.7F);
        this.setSoundType(SoundType.METAL);
        this.setCreativeTab(CreativeTabs.TRANSPORTATION);
        this.setRegistryName(ChainableMinecarts.MODID, "rail_intersection");
        this.setUnlocalizedName(ChainableMinecarts.MODID + ".rail_intersection");
    }

    /** Straight along whichever axis the cart is travelling on; its facing decides if it's standing still. */
    @Override
    public EnumRailDirection getRailDirection(IBlockAccess world, BlockPos pos, IBlockState state, @Nullable EntityMinecart cart)
    {
        if (cart == null)
        {
            return state.getValue(SHAPE);
        }

        double alongX;
        double alongZ;

        if (cart.motionX * cart.motionX + cart.motionZ * cart.motionZ > 1.0E-6D)
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

        return alongX > alongZ ? EnumRailDirection.EAST_WEST : EnumRailDirection.NORTH_SOUTH;
    }

    /** Called when a neighbour changes: keep the rails around the crossing connected to it. */
    @Override
    protected void updateState(IBlockState state, World world, BlockPos pos, Block neighbour)
    {
        RailConnections.connectNeighbours(world, pos, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST);
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
        return new BlockStateContainer(this, SHAPE);
    }

    @Override
    public IBlockState getStateFromMeta(int meta)
    {
        return this.getDefaultState().withProperty(SHAPE, meta == 1 ? EnumRailDirection.EAST_WEST : EnumRailDirection.NORTH_SOUTH);
    }

    @Override
    public int getMetaFromState(IBlockState state)
    {
        return state.getValue(SHAPE) == EnumRailDirection.EAST_WEST ? 1 : 0;
    }

    @Override
    public IBlockState withRotation(IBlockState state, Rotation rot)
    {
        if (rot == Rotation.CLOCKWISE_90 || rot == Rotation.COUNTERCLOCKWISE_90)
        {
            return state.withProperty(SHAPE, state.getValue(SHAPE) == EnumRailDirection.NORTH_SOUTH ? EnumRailDirection.EAST_WEST : EnumRailDirection.NORTH_SOUTH);
        }

        return state;
    }

    @Override
    public IBlockState withMirror(IBlockState state, Mirror mirror)
    {
        return state;
    }
}
