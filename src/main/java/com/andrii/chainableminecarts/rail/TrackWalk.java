package com.andrii.chainableminecarts.rail;

import javax.annotation.Nullable;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRailBase;
import net.minecraft.block.BlockRailBase.EnumRailDirection;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

/**
 * Follows the track rail by rail, along the same path carts are drawn on: straight rails as lines, turns as
 * quarter circles.
 * <p>
 * Straight lines between carts say nothing useful where the track bends back on itself: round a U two carts sit a
 * block apart side by side, several blocks apart along the rails, and the line between them is square to both.
 * Walking the track gives real distances along it and the direction of travel at every point, neither of which can
 * be ambiguous.
 * <p>
 * The crossing and the switch are taken by the side the walk comes in by, the way a cart coming in there would go,
 * rather than by where some cart elsewhere happens to be.
 */
public final class TrackWalk
{
    /** How many rails a walk goes along before giving up. */
    private static final int MAX_RAILS = 16;
    /** How far along the track to look for another cart. */
    private static final double MAX_DISTANCE = 8.0D;
    private static final double RADIUS = 0.5D;

    private TrackWalk()
    {
    }

    /** Where a walk to another cart got to: how far, and which way the track runs there in the walk's direction. */
    public static final class Step
    {
        public final double distance;
        public final double dirX;
        public final double dirZ;

        Step(double distance, double dirX, double dirZ)
        {
            this.distance = distance;
            this.dirX = dirX;
            this.dirZ = dirZ;
        }
    }

    /** A point travelling along the track: where it is, which way it's going, and which rail it's on. */
    public static final class Cursor
    {
        private final World world;
        /** Whose view of ordinary rails to use; vanilla rails ignore it, some modded ones may not. */
        private final EntityMinecart cart;
        private BlockPos rail;
        private EnumRailDirection shape;
        /** The side of the rail it came in by, so it knows which side it leaves by. */
        private EnumFacing entry;
        public double x;
        public double z;
        public double dirX;
        public double dirZ;

        private Cursor(World world, EntityMinecart cart)
        {
            this.world = world;
            this.cart = cart;
        }

        public BlockPos rail()
        {
            return this.rail;
        }

        public Cursor copy()
        {
            Cursor copy = new Cursor(this.world, this.cart);
            copy.rail = this.rail;
            copy.shape = this.shape;
            copy.entry = this.entry;
            copy.x = this.x;
            copy.z = this.z;
            copy.dirX = this.dirX;
            copy.dirZ = this.dirZ;
            return copy;
        }

        /** Turns round: same place, the other way along the track. */
        public void reverse()
        {
            this.entry = this.exitSide();
            this.dirX = -this.dirX;
            this.dirZ = -this.dirZ;
        }

        /**
         * Moves along the track by a distance, backwards if negative. Returns false if the track ran out first, in
         * which case it stops at the end.
         */
        public boolean advance(double distance)
        {
            if (distance < 0.0D)
            {
                this.reverse();
                boolean whole = this.advance(-distance);
                this.reverse();
                return whole;
            }

            double remaining = distance;

            for (int rails = 0; rails < MAX_RAILS; ++rails)
            {
                EnumFacing exit = this.exitSide();
                double toExit = this.toExit(exit);

                if (remaining <= toExit)
                {
                    this.moveWithin(remaining);
                    return true;
                }

                this.moveWithin(toExit);
                remaining -= toExit;

                if (!this.enterNext(exit))
                {
                    return false;
                }
            }

            return true;
        }

        /**
         * Walks forward to a point on the track and returns how far along the track it is, or NaN if it isn't ahead
         * within {@code maxDistance}. Stops at that point, facing the way the walk was going.
         */
        public double find(double targetX, double targetZ, double maxDistance)
        {
            int blockX = MathHelper.floor(targetX);
            int blockZ = MathHelper.floor(targetZ);
            double travelled = 0.0D;

            for (int rails = 0; rails < MAX_RAILS; ++rails)
            {
                if (this.rail.getX() == blockX && this.rail.getZ() == blockZ)
                {
                    RailPath.Point target = RailPath.project(this.rail, this.shape, targetX, targetZ);
                    double ahead = this.alongTo(target.x, target.z);

                    if (ahead < -1.0E-4D)
                    {
                        return Double.NaN;
                    }

                    ahead = Math.max(0.0D, ahead);
                    this.moveWithin(ahead);
                    return travelled + ahead;
                }

                EnumFacing exit = this.exitSide();
                double toExit = this.toExit(exit);
                travelled += toExit;

                if (travelled > maxDistance)
                {
                    return Double.NaN;
                }

                this.moveWithin(toExit);

                if (!this.enterNext(exit))
                {
                    return Double.NaN;
                }
            }

            return Double.NaN;
        }

        /** How far along the track a point is: positive ahead, negative behind, NaN if not within the distance. */
        public double offsetTo(double targetX, double targetZ, double maxDistance)
        {
            double ahead = this.copy().find(targetX, targetZ, maxDistance);

            if (!Double.isNaN(ahead))
            {
                return ahead;
            }

            Cursor back = this.copy();
            back.reverse();
            double behind = back.find(targetX, targetZ, maxDistance);
            return Double.isNaN(behind) ? Double.NaN : -behind;
        }

        /** The side this rail is left by: the other end from the one it came in by. */
        private EnumFacing exitSide()
        {
            EnumFacing best = null;
            double bestAlong = Double.NEGATIVE_INFINITY;

            for (EnumFacing side : RailConnections.connectedSides(this.shape))
            {
                if (side == this.entry)
                {
                    continue;
                }

                double along = side.getFrontOffsetX() * this.dirX + side.getFrontOffsetZ() * this.dirZ;

                if (along > bestAlong)
                {
                    bestAlong = along;
                    best = side;
                }
            }

            return best;
        }

        /** Distance along the rail's path to where it leaves by the given side. */
        private double toExit(EnumFacing exit)
        {
            double exitX = this.rail.getX() + 0.5D + exit.getFrontOffsetX() * 0.5D;
            double exitZ = this.rail.getZ() + 0.5D + exit.getFrontOffsetZ() * 0.5D;

            if (!isCurve(this.shape))
            {
                return Math.sqrt((exitX - this.x) * (exitX - this.x) + (exitZ - this.z) * (exitZ - this.z));
            }

            double[] centre = this.curveCentre();
            double from = Math.atan2(this.z - centre[1], this.x - centre[0]);
            double to = Math.atan2(exitZ - centre[1], exitX - centre[0]);
            return RADIUS * Math.abs(wrap(to - from));
        }

        /** Distance along this rail's path to a point on it, positive if it lies ahead. */
        private double alongTo(double pointX, double pointZ)
        {
            if (!isCurve(this.shape))
            {
                return (pointX - this.x) * this.dirX + (pointZ - this.z) * this.dirZ;
            }

            double[] centre = this.curveCentre();
            double from = Math.atan2(this.z - centre[1], this.x - centre[0]);
            double to = Math.atan2(pointZ - centre[1], pointX - centre[0]);
            return RADIUS * wrap(to - from) * this.turnSense(from);
        }

        /** Moves along this rail's path by a distance no further than its end. */
        private void moveWithin(double distance)
        {
            if (!isCurve(this.shape))
            {
                this.x += this.dirX * distance;
                this.z += this.dirZ * distance;
                return;
            }

            double[] centre = this.curveCentre();
            double angle = Math.atan2(this.z - centre[1], this.x - centre[0]);
            double sense = this.turnSense(angle);
            angle += sense * distance / RADIUS;
            this.x = centre[0] + RADIUS * Math.cos(angle);
            this.z = centre[1] + RADIUS * Math.sin(angle);
            this.dirX = -Math.sin(angle) * sense;
            this.dirZ = Math.cos(angle) * sense;
        }

        /** Which way round the turn's circle it's travelling: 1 for increasing angle, -1 for decreasing. */
        private double turnSense(double angle)
        {
            return -Math.sin(angle) * this.dirX + Math.cos(angle) * this.dirZ >= 0.0D ? 1.0D : -1.0D;
        }

        /** The corner a turn curves round: between the two edges it joins. */
        private double[] curveCentre()
        {
            EnumFacing[] sides = RailConnections.connectedSides(this.shape);
            return new double[] {
                this.rail.getX() + 0.5D + (sides[0].getFrontOffsetX() + sides[1].getFrontOffsetX()) * 0.5D,
                this.rail.getZ() + 0.5D + (sides[0].getFrontOffsetZ() + sides[1].getFrontOffsetZ()) * 0.5D};
        }

        /** Crosses into the next rail through the given side of this one. */
        private boolean enterNext(EnumFacing exit)
        {
            BlockPos next = railNear(this.world, this.rail.offset(exit));

            if (next == null)
            {
                return false;
            }

            EnumFacing nextEntry = exit.getOpposite();
            EnumRailDirection nextShape = route(this.world, next, nextEntry, this.cart);
            this.rail = next;
            this.shape = nextShape;
            this.entry = nextEntry;
            this.dirX = exit.getFrontOffsetX();
            this.dirZ = exit.getFrontOffsetZ();

            // A rail that doesn't run back the way we came: put the point on its path and carry on along it
            if (!connects(nextShape, nextEntry))
            {
                this.settleOnPath();
            }

            return true;
        }

        /** Puts the point on this rail's path, facing along it the way it was going, and works out its way in. */
        private void settleOnPath()
        {
            RailPath.Point point = RailPath.project(this.rail, this.shape, this.x, this.z);
            double sense = point.dirX * this.dirX + point.dirZ * this.dirZ < 0.0D ? -1.0D : 1.0D;
            this.x = point.x;
            this.z = point.z;
            this.dirX = point.dirX * sense;
            this.dirZ = point.dirZ * sense;
            this.entry = null;
            // The end it's heading away from is the way in
            EnumFacing ahead = this.exitSide();

            for (EnumFacing side : RailConnections.connectedSides(this.shape))
            {
                if (side != ahead)
                {
                    this.entry = side;
                }
            }
        }
    }

    /**
     * A cursor on the track at a position, heading the given way along it (or either way, if the direction is
     * nothing), or null if there's no rail there. The rail is found the way vanilla finds a cart's rail.
     */
    @Nullable
    public static Cursor at(EntityMinecart cart, double x, double y, double z, double dirX, double dirZ)
    {
        BlockPos rail = railUnder(cart.world, x, y, z);

        if (rail == null)
        {
            return null;
        }

        IBlockState state = cart.world.getBlockState(rail);
        Cursor cursor = new Cursor(cart.world, cart);
        cursor.rail = rail;
        cursor.shape = ((BlockRailBase)state.getBlock()).getRailDirection(cart.world, rail, state, cart);
        cursor.x = x;
        cursor.z = z;
        cursor.dirX = dirX;
        cursor.dirZ = dirZ;
        cursor.settleOnPath();
        return cursor;
    }

    /** A cursor at a cart, heading the given way along the track. */
    @Nullable
    public static Cursor at(EntityMinecart cart, double dirX, double dirZ)
    {
        return at(cart, cart.posX, cart.posY, cart.posZ, dirX, dirZ);
    }

    /**
     * Follows the track from {@code from}, setting off the given way, and returns where {@code to} lies along it,
     * or null if the track doesn't lead there.
     */
    @Nullable
    public static Step to(EntityMinecart from, EntityMinecart to, double dirX, double dirZ)
    {
        Cursor cursor = to.world == from.world ? at(from, dirX, dirZ) : null;

        if (cursor == null || railUnder(to.world, to.posX, to.posY, to.posZ) == null)
        {
            return null;
        }

        double distance = cursor.find(to.posX, to.posZ, MAX_DISTANCE);
        return Double.isNaN(distance) ? null : new Step(distance, cursor.dirX, cursor.dirZ);
    }

    /** The unit direction the track runs at a cart (either way along it), or null if it isn't on one. */
    @Nullable
    public static double[] direction(EntityMinecart cart)
    {
        RailPath.Point point = RailPath.nearest(cart, cart.posX, cart.posY, cart.posZ);
        return point == null ? null : new double[] {point.dirX, point.dirZ};
    }

    /** The shape a rail takes for something coming in by the given side. */
    private static EnumRailDirection route(World world, BlockPos rail, EnumFacing entry, EntityMinecart cart)
    {
        IBlockState state = world.getBlockState(rail);
        Block block = state.getBlock();

        if (block instanceof BlockRailIntersection)
        {
            return RailConnections.straight(entry.getAxis());
        }

        if (block instanceof BlockRailDoubleTurn)
        {
            return BlockRailDoubleTurn.routeFrom(state, entry);
        }

        return ((BlockRailBase)block).getRailDirection(world, rail, state, cart);
    }

    private static boolean connects(EnumRailDirection shape, EnumFacing side)
    {
        for (EnumFacing connected : RailConnections.connectedSides(shape))
        {
            if (connected == side)
            {
                return true;
            }
        }

        return false;
    }

    private static boolean isCurve(EnumRailDirection shape)
    {
        return shape == EnumRailDirection.NORTH_EAST || shape == EnumRailDirection.NORTH_WEST
            || shape == EnumRailDirection.SOUTH_EAST || shape == EnumRailDirection.SOUTH_WEST;
    }

    /** An angle brought into -pi..pi. */
    private static double wrap(double angle)
    {
        while (angle > Math.PI)
        {
            angle -= 2.0D * Math.PI;
        }

        while (angle < -Math.PI)
        {
            angle += 2.0D * Math.PI;
        }

        return angle;
    }

    /** The rail at a position, found the way vanilla finds a cart's rail: that block, or the one below. */
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

    /** The next rail along: level, or a block down or up where the track slopes. */
    @Nullable
    private static BlockPos railNear(World world, BlockPos pos)
    {
        if (BlockRailBase.isRailBlock(world, pos))
        {
            return pos;
        }

        if (BlockRailBase.isRailBlock(world, pos.down()))
        {
            return pos.down();
        }

        return BlockRailBase.isRailBlock(world, pos.up()) ? pos.up() : null;
    }
}
