package com.andrii.chainableminecarts.physics;

import com.andrii.chainableminecarts.ChainableMinecarts;
import com.andrii.chainableminecarts.ModConfig;
import com.andrii.chainableminecarts.rail.TrackWalk;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.block.BlockRailBase;
import net.minecraft.block.BlockRailPowered;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Rigid contact between carts, linked or not. Runs once per server tick after everything else has set the carts'
 * speeds (vanilla movement, pushes, trains, furnaces), so nothing can undo it before the carts move again:
 * <ul>
 * <li>two carts may approach each other at most by the gap between them, so after their next move they touch at
 * most, never overlap;</li>
 * <li>carts already touching lose their approach speed, bouncing apart only by {@code restitution};</li>
 * <li>carts that overlap anyway (placed inside each other, or pushed in by something else) separate within a
 * tick.</li>
 * </ul>
 * Carts on the same track are measured along it. Round a U or a tight loop, two carts can sit less than a cart's
 * width apart across the bend while being well apart along the rails; measured in a straight line, they'd be
 * shoved apart for nothing. Carts on different tracks (side by side, or crossing) meet as boxes instead.
 * <p>
 * Each correction is an equal and opposite push, so momentum passes from cart to cart: a cart rolling into a row of
 * carts shoves the row. A few passes over all contacts keep rows of carts pressed together rigid.
 * <p>
 * Walls (any solid block a cart's rail runs into) take pushes without moving: a cart up against one can't move into
 * it, and in a contact it can't be pushed that way either, so a cart running into a row of carts at a wall stops.
 */
@Mod.EventBusSubscriber(modid = ChainableMinecarts.MODID)
public final class CartContacts
{
    private static final int PASSES = 4;
    /** Fastest speed used to push apart carts that overlap. */
    private static final double MAX_SEPARATION_SPEED = 0.2D;

    private CartContacts()
    {
    }

    /** Two carts that could touch this tick, and how they're laid out relative to each other. */
    private static final class Contact
    {
        final EntityMinecart a;
        final EntityMinecart b;
        /** Along the track: a's direction towards b, b's direction away from a. Across: both the line from a to b. */
        final double aDirX;
        final double aDirZ;
        final double bDirX;
        final double bDirZ;
        /** Gap between the carts' edges: along the track, or in a straight line. */
        final double gap;
        final boolean alongTrack;
        /** Up against a wall on the way away from the other cart, so it can't be pushed off that way. */
        boolean aStuck;
        boolean bStuck;

        Contact(EntityMinecart a, EntityMinecart b, double aDirX, double aDirZ, double bDirX, double bDirZ, double gap, boolean alongTrack)
        {
            this.a = a;
            this.b = b;
            this.aDirX = aDirX;
            this.aDirZ = aDirZ;
            this.bDirX = bDirX;
            this.bDirZ = bDirZ;
            this.gap = gap;
            this.alongTrack = alongTrack;
        }
    }

    /** Low priority so this runs after the trains are moved (LinkManager's world tick). */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onWorldTick(TickEvent.WorldTickEvent event)
    {
        if (event.phase == TickEvent.Phase.END && event.world instanceof WorldServer && ModConfig.carts.heavyPhysics)
        {
            solveAll(event.world);
        }
    }

    /** A cart up against a wall, the given way along its rail. */
    private static final class Wall
    {
        final EntityMinecart cart;
        final double dirX;
        final double dirZ;

        Wall(EntityMinecart cart, double dirX, double dirZ)
        {
            this.cart = cart;
            this.dirX = dirX;
            this.dirZ = dirZ;
        }
    }

    /** Resolves every contact between carts, and between carts and walls, in a world. */
    public static void solveAll(World world)
    {
        List<Contact> contacts = findContacts(world);
        List<Wall> walls = findWalls(world);

        for (int pass = 0; pass < PASSES && !(contacts.isEmpty() && walls.isEmpty()); ++pass)
        {
            for (Contact contact : contacts)
            {
                solve(contact);
            }

            for (Wall wall : walls)
            {
                if (CartBody.speedAlong(wall.cart, wall.dirX, wall.dirZ) > 0.0D)
                {
                    CartBody.setSpeedAlong(wall.cart, wall.dirX, wall.dirZ, 0.0D);
                }
            }
        }
    }

    /**
     * How much of its speed a cart will move next tick: vanilla halves it as the cart moves over an unpowered booster
     * rail, and stops it there when slow. Planning with the full speed would let the carts behind close in on it by
     * more than the gap, and overlap.
     */
    private static double moveFactor(EntityMinecart cart)
    {
        BlockPos pos = new BlockPos(MathHelper.floor(cart.posX), MathHelper.floor(cart.posY), MathHelper.floor(cart.posZ));

        if (BlockRailBase.isRailBlock(cart.world, pos.down()))
        {
            pos = pos.down();
        }

        IBlockState rail = cart.world.getBlockState(pos);

        if (rail.getBlock() != Blocks.GOLDEN_RAIL || rail.getValue(BlockRailPowered.POWERED))
        {
            return 1.0D;
        }

        return Math.sqrt(cart.motionX * cart.motionX + cart.motionZ * cart.motionZ) < 0.03D ? 0.0D : 0.5D;
    }

    /** Carts up against a wall, and which way. */
    private static List<Wall> findWalls(World world)
    {
        List<Wall> walls = new ArrayList<>();

        for (Entity entity : world.loadedEntityList)
        {
            if (entity instanceof EntityMinecart && !entity.isDead && !entity.noClip)
            {
                EntityMinecart cart = (EntityMinecart)entity;
                double[] along = TrackWalk.direction(cart);

                if (along != null)
                {
                    for (double sense = -1.0D; sense <= 1.0D; sense += 2.0D)
                    {
                        if (CartBody.blockedAlong(cart, along[0] * sense, along[1] * sense))
                        {
                            walls.add(new Wall(cart, along[0] * sense, along[1] * sense));
                        }
                    }
                }
            }
        }

        return walls;
    }

    /** Carts close enough to touch within a tick, each pair once. */
    private static List<Contact> findContacts(World world)
    {
        List<Contact> contacts = new ArrayList<>();

        for (Entity entity : world.loadedEntityList)
        {
            if (!(entity instanceof EntityMinecart) || entity.isDead || entity.noClip)
            {
                continue;
            }

            EntityMinecart cart = (EntityMinecart)entity;
            // Room for both carts' moves this tick (their speed is capped well under this)
            double reach = 1.0D;

            for (EntityMinecart other : world.getEntitiesWithinAABB(EntityMinecart.class, cart.getEntityBoundingBox().grow(reach, 0.0D, reach)))
            {
                if (other.getEntityId() > cart.getEntityId() && !other.isDead && !other.noClip)
                {
                    Contact contact = contact(cart, other);

                    if (contact != null)
                    {
                        if (contact.alongTrack)
                        {
                            contact.aStuck = CartBody.blockedAlong(cart, -contact.aDirX, -contact.aDirZ);
                            contact.bStuck = CartBody.blockedAlong(other, contact.bDirX, contact.bDirZ);
                        }

                        contacts.add(contact);
                    }
                }
            }
        }

        return contacts;
    }

    /** How two carts meet: along the track if one leads to the other, otherwise in a straight line. */
    private static Contact contact(EntityMinecart a, EntityMinecart b)
    {
        double halfWidths = (a.width + b.width) * 0.5D;
        double[] along = TrackWalk.direction(a);

        if (along != null)
        {
            double sense = 1.0D;
            TrackWalk.Step step = TrackWalk.to(a, b, along[0], along[1]);

            if (step == null)
            {
                sense = -1.0D;
                step = TrackWalk.to(a, b, -along[0], -along[1]);
            }

            if (step != null)
            {
                return new Contact(a, b, along[0] * sense, along[1] * sense, step.dirX, step.dirZ, step.distance - halfWidths, true);
            }
        }

        return boxContact(a, b, halfWidths);
    }

    /**
     * Carts on different tracks meet as what they are, square boxes. Measured as circles, carts passing on parallel
     * tracks a block apart (0.02 between their sides) would clip each other's corners on the way past, holding up
     * one and shoving the other along its rail. Two boxes only touch where they overlap along both x and z, so
     * there's only a contact if that would happen this tick: then it's along whichever axis they'd meet on last,
     * the side that actually hits. Boxes overlapping already are pushed apart along the shallower overlap.
     */
    @Nullable
    private static Contact boxContact(EntityMinecart a, EntityMinecart b, double halfWidths)
    {
        double dx = b.posX - a.posX;
        double dz = b.posZ - a.posZ;
        double signX = dx < 0.0D ? -1.0D : 1.0D;
        double signZ = dz < 0.0D ? -1.0D : 1.0D;
        double gapX = Math.abs(dx) - halfWidths;
        double gapZ = Math.abs(dz) - halfWidths;
        boolean alongX;

        if (gapX < 0.0D && gapZ < 0.0D)
        {
            alongX = gapX > gapZ;
        }
        else
        {
            // How much each gap shrinks this tick
            double closingX = -(b.motionX - a.motionX) * signX;
            double closingZ = -(b.motionZ - a.motionZ) * signZ;

            if (gapX >= closingX || gapZ >= closingZ)
            {
                // Still apart along one axis after this tick's move: no contact
                return null;
            }

            // Meeting along an axis they already overlap on can't happen; otherwise the later of the two meetings
            // is when they touch
            alongX = gapZ < 0.0D || gapX >= 0.0D && gapX * closingZ > gapZ * closingX;
        }

        double nx = alongX ? signX : 0.0D;
        double nz = alongX ? 0.0D : signZ;
        return new Contact(a, b, nx, nz, nx, nz, alongX ? gapX : gapZ, false);
    }

    private static void solve(Contact contact)
    {
        EntityMinecart a = contact.a;
        EntityMinecart b = contact.b;
        // How much of its speed each cart will actually move next tick
        double moveA = contact.alongTrack ? moveFactor(a) : 1.0D;
        double moveB = contact.alongTrack ? moveFactor(b) : 1.0D;
        // How fast b moves away from a; negative means closing in
        double relativeSpeed = contact.alongTrack
            ? moveB * CartBody.speedAlong(b, contact.bDirX, contact.bDirZ) - moveA * CartBody.speedAlong(a, contact.aDirX, contact.aDirZ)
            : CartBody.relativeSpeed(a, b, contact.aDirX, contact.aDirZ);
        double target;

        if (contact.gap > 0.0D)
        {
            // Close in at most by the gap: next tick they touch at most
            target = -contact.gap;
        }
        else
        {
            // Touching or overlapping: stop closing in (with a little bounce), and move out of any overlap
            target = Math.max(Math.min(-contact.gap, MAX_SEPARATION_SPEED), -ModConfig.carts.restitution * Math.min(relativeSpeed, 0.0D));
        }

        if (relativeSpeed >= target)
        {
            return;
        }

        if (!contact.alongTrack)
        {
            CartBody.setRelativeSpeed(a, b, contact.aDirX, contact.aDirZ, target);
            return;
        }

        // Equal and opposite along the track, each cart pushed along its own rail; a cart at a wall doesn't give
        double inverseA = contact.aStuck ? 0.0D : 1.0D / CartBody.mass(a);
        double inverseB = contact.bStuck ? 0.0D : 1.0D / CartBody.mass(b);
        double give = moveA * inverseA + moveB * inverseB;

        if (give <= 0.0D)
        {
            return;
        }

        double impulse = (target - relativeSpeed) / give;
        double speedA = CartBody.speedAlong(a, contact.aDirX, contact.aDirZ);
        double speedB = CartBody.speedAlong(b, contact.bDirX, contact.bDirZ);
        CartBody.setSpeedAlong(a, contact.aDirX, contact.aDirZ, speedA - impulse * inverseA);
        CartBody.setSpeedAlong(b, contact.bDirX, contact.bDirZ, speedB + impulse * inverseB);
    }
}
