package com.andrii.chainableminecarts.rail;

import javax.annotation.Nullable;
import net.minecraft.block.BlockRailBase;
import net.minecraft.block.BlockRailBase.EnumRailDirection;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

/**
 * The path a cart follows along a rail. Straight rails are the line between the midpoints of two block edges, as
 * in vanilla. Curves are a quarter circle (radius 0.5) around the block corner between their two ends, matching the
 * curved rail texture, instead of vanilla's straight diagonal between the ends.
 */
public final class RailPath
{
    /** Same as EntityMinecart.MATRIX: the two block edges each rail shape connects, indexed by its metadata. */
    private static final int[][][] MATRIX = new int[][][] {
        {{0, 0, -1}, {0, 0, 1}}, {{-1, 0, 0}, {1, 0, 0}}, {{-1, -1, 0}, {1, 0, 0}}, {{-1, 0, 0}, {1, -1, 0}},
        {{0, 0, -1}, {0, -1, 1}}, {{0, -1, -1}, {0, 0, 1}}, {{0, 0, 1}, {1, 0, 0}}, {{0, 0, 1}, {-1, 0, 0}},
        {{0, 0, -1}, {-1, 0, 0}}, {{0, 0, -1}, {1, 0, 0}}};
    /** Metadata of the first curved shape; everything below is straight (flat or ascending). */
    private static final int FIRST_CURVE = 6;
    private static final double RADIUS = 0.5D;
    /** How far inside its block's far edges a cart is kept when put on a curve. */
    private static final double EDGE = 1.0E-6D;

    private RailPath()
    {
    }

    /** A point on the track and the unit direction of the track there (either way along it). */
    public static final class Point
    {
        public final double x;
        public final double z;
        public final double dirX;
        public final double dirZ;

        Point(double x, double z, double dirX, double dirZ)
        {
            this.x = x;
            this.z = z;
            this.dirX = dirX;
            this.dirZ = dirZ;
        }
    }

    /**
     * The track point nearest to a position on the rail under it (found the same way vanilla does), or null if
     * there's no rail there.
     */
    @Nullable
    public static Point nearest(EntityMinecart cart, double x, double y, double z)
    {
        BlockPos rail = railAt(cart.world, MathHelper.floor(x), MathHelper.floor(y), MathHelper.floor(z));
        return rail == null ? null : pointOn(rail, shape(cart, rail), x, z);
    }

    /**
     * Server side, after vanilla moved the cart: vanilla keeps carts on the straight diagonal of a curve. This moves
     * the cart sideways onto the curve. It stays at the same place along the diagonal, so vanilla's projection
     * onto the diagonal next tick finds exactly where it left off.
     */
    public static void snapOntoCurve(EntityMinecart cart, BlockPos railPos)
    {
        if (MathHelper.floor(cart.posX) != railPos.getX() || MathHelper.floor(cart.posZ) != railPos.getZ()
            || !BlockRailBase.isRailBlock(cart.world.getBlockState(railPos)))
        {
            return;
        }

        int shape = shape(cart, railPos);

        if (shape < FIRST_CURVE)
        {
            return;
        }

        int[][] ends = MATRIX[shape];
        double ax = railPos.getX() + 0.5D + ends[0][0] * 0.5D;
        double az = railPos.getZ() + 0.5D + ends[0][2] * 0.5D;
        double bx = railPos.getX() + 0.5D + ends[1][0] * 0.5D;
        double bz = railPos.getZ() + 0.5D + ends[1][2] * 0.5D;
        double cx = ax + bx - (railPos.getX() + 0.5D);
        double cz = az + bz - (railPos.getZ() + 0.5D);

        // Where along the diagonal the cart is (0 at one end, 1 at the other)
        double chordX = bx - ax;
        double chordZ = bz - az;
        double t = ((cart.posX - ax) * chordX + (cart.posZ - az) * chordZ) / (chordX * chordX + chordZ * chordZ);

        if (t < 0.0D || t > 1.0D)
        {
            return;
        }

        // Move away from the corner, at right angles to the diagonal, until reaching the curve
        double px = ax + chordX * t;
        double pz = az + chordZ * t;
        double outX = (ax + bx) * 0.5D - cx;
        double outZ = (az + bz) * 0.5D - cz;
        double outLength = Math.sqrt(outX * outX + outZ * outZ);
        outX /= outLength;
        outZ /= outLength;
        double qx = px - cx;
        double qz = pz - cz;
        double along = qx * outX + qz * outZ;
        double discriminant = along * along - (qx * qx + qz * qz) + RADIUS * RADIUS;

        if (discriminant >= 0.0D)
        {
            double shift = Math.sqrt(discriminant) - along;
            // Stay inside this rail's block. The curve's ends lie on the block's edges, and a cart put exactly on
            // its west or north edge counts as being in the block beyond, the rail it came from. Where two turns
            // meet (a U), vanilla then moves it along that rail's diagonal, square to this one's, and this puts it
            // straight back on the same spot: the cart sits at the join, tick after tick, until it has lost all
            // its speed.
            double x = MathHelper.clamp(px + outX * shift, railPos.getX(), railPos.getX() + 1.0D - EDGE);
            double z = MathHelper.clamp(pz + outZ * shift, railPos.getZ(), railPos.getZ() + 1.0D - EDGE);
            cart.setPosition(x, cart.posY, z);
        }
    }

    /** The rail block for a position, looked up like EntityMinecart.onUpdate does (the block, or the one below). */
    @Nullable
    private static BlockPos railAt(World world, int x, int y, int z)
    {
        BlockPos pos = new BlockPos(x, y, z);

        if (BlockRailBase.isRailBlock(world, pos.down()))
        {
            return pos.down();
        }

        return BlockRailBase.isRailBlock(world, pos) ? pos : null;
    }

    /**
     * Where vanilla puts a cart on this rail before moving it: square onto the line between the rail's two ends
     * (for a turn, the diagonal across the block), not limited to between them. EntityMinecart.moveAlongTrack.
     */
    public static double[] vanillaStart(BlockPos railPos, EnumRailDirection shape, double x, double z)
    {
        int[][] ends = MATRIX[shape.getMetadata()];
        double ax = railPos.getX() + 0.5D + ends[0][0] * 0.5D;
        double az = railPos.getZ() + 0.5D + ends[0][2] * 0.5D;
        double dx = (ends[1][0] - ends[0][0]) * 0.5D;
        double dz = (ends[1][2] - ends[0][2]) * 0.5D;
        double t = ((x - ax) * dx + (z - az) * dz) / (dx * dx + dz * dz);
        return new double[] {ax + dx * t, az + dz * t};
    }

    /** Whether a rail shape is a turn. */
    public static boolean isCurve(EnumRailDirection shape)
    {
        return shape.getMetadata() >= FIRST_CURVE;
    }

    /** The point on this rail's path (a line, or a quarter circle for a turn) nearest to a position. */
    public static Point project(BlockPos railPos, EnumRailDirection shape, double x, double z)
    {
        return pointOn(railPos, shape.getMetadata(), x, z);
    }

    private static int shape(EntityMinecart cart, BlockPos railPos)
    {
        IBlockState state = cart.world.getBlockState(railPos);
        return ((BlockRailBase)state.getBlock()).getRailDirection(cart.world, railPos, state, cart).getMetadata();
    }

    private static Point pointOn(BlockPos railPos, int shape, double x, double z)
    {
        int[][] ends = MATRIX[shape];
        double ax = railPos.getX() + 0.5D + ends[0][0] * 0.5D;
        double az = railPos.getZ() + 0.5D + ends[0][2] * 0.5D;
        double bx = railPos.getX() + 0.5D + ends[1][0] * 0.5D;
        double bz = railPos.getZ() + 0.5D + ends[1][2] * 0.5D;

        if (shape < FIRST_CURVE)
        {
            double dx = bx - ax;
            double dz = bz - az;
            double lengthSq = dx * dx + dz * dz;
            double t = MathHelper.clamp(((x - ax) * dx + (z - az) * dz) / lengthSq, 0.0D, 1.0D);
            double length = Math.sqrt(lengthSq);
            return new Point(ax + dx * t, az + dz * t, dx / length, dz / length);
        }

        // Curve: circle around the corner between the two ends
        double cx = ax + bx - (railPos.getX() + 0.5D);
        double cz = az + bz - (railPos.getZ() + 0.5D);
        double vx = x - cx;
        double vz = z - cz;
        boolean withinCurve = vx * (ax - cx) + vz * (az - cz) >= 0.0D && vx * (bx - cx) + vz * (bz - cz) >= 0.0D;
        double length = Math.sqrt(vx * vx + vz * vz);

        if (!withinCurve || length < 1.0E-6D)
        {
            // Beyond either end of the quarter circle: use the nearer end
            boolean nearA = (x - ax) * (x - ax) + (z - az) * (z - az) <= (x - bx) * (x - bx) + (z - bz) * (z - bz);
            vx = nearA ? ax - cx : bx - cx;
            vz = nearA ? az - cz : bz - cz;
            length = RADIUS;
        }

        double rx = vx / length;
        double rz = vz / length;
        return new Point(cx + rx * RADIUS, cz + rz * RADIUS, -rz, rx);
    }
}
