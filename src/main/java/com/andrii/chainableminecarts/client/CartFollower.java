package com.andrii.chainableminecarts.client;

import com.andrii.chainableminecarts.physics.CartBody;
import com.andrii.chainableminecarts.rail.TrackWalk;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;

/**
 * Moves client-side carts along the track.
 * <p>
 * Vanilla sends a cart's position only every third tick and the client then slides it towards each one in a straight
 * line, over five ticks. At full speed those positions are over a block apart, so the straight line cuts right across
 * a turn, and round a U it passes over the other side of the track. Instead, each cart keeps its own point on the
 * track here and moves it along the rails:
 * <ol>
 * <li>forward at the speed the server has been moving it, measured along the track between the positions it sends;</li>
 * <li>then a share of the way towards where the server last had it, moved on by the time since that arrived;</li>
 * <li>straight there if that's more than a few blocks off, or not along the track at all (just spawned, teleported,
 * derailed).</li>
 * </ol>
 * Short of the last, a cart never moves into a wall or the player on this client: it stops there, as on the server.
 * The speed comes from the positions, not from the velocity vanilla sends: that is only sent when it changes by a
 * fair amount, so a cart that has slowed to a crawl or stopped can be left with a speed it no longer has. Positions
 * are sent whenever a cart moves at all, so once they stop coming the cart has stopped, and so does its point here.
 * <p>
 * Whoever is riding the cart is put back in it afterwards, so riders stay in their seat.
 */
final class CartFollower
{
    /** Share of the gap to where the server has the cart that's closed each tick. */
    private static final double PULL = 0.25D;
    /** Further than this from where the server has it, and the cart is simply put there. */
    private static final double MAX_DRIFT = 3.0D;
    /** Vanilla sends a moving cart's position every 3 ticks; this long without one and it has stopped. */
    private static final int STOPPED_AFTER = 5;

    /** Client-side carts only, handled on the client thread. */
    private static final Map<EntityMinecart, State> STATES = new WeakHashMap<>();

    private CartFollower()
    {
    }

    private static final class State
    {
        TrackWalk.Cursor cursor;
        long serverX;
        long serverY;
        long serverZ;
        int ticksSinceUpdate;
        /** Speed along the cursor's direction of travel, measured between the server's positions. */
        double speed;
    }

    /**
     * Moves a cart along the track to where it should be drawn this tick, and returns its point on the track, facing
     * its direction of travel. Returns null for a cart that isn't on a rail, which vanilla keeps moving.
     *
     * @param blocker who the cart can't move into (the player on this client, with solid carts), or null
     */
    @Nullable
    static TrackWalk.Cursor follow(EntityMinecart cart, @Nullable Entity blocker)
    {
        double serverX = cart.serverPosX / 4096.0D;
        double serverY = cart.serverPosY / 4096.0D;
        double serverZ = cart.serverPosZ / 4096.0D;
        State state = STATES.get(cart);

        if (state == null)
        {
            state = new State();
            state.speed = Math.min(Math.sqrt(cart.motionX * cart.motionX + cart.motionZ * cart.motionZ), CartBody.railSpeedCap(cart));

            if (!placeAt(state, cart, serverX, serverY, serverZ, cart.motionX, cart.motionZ))
            {
                return null;
            }

            state.serverX = cart.serverPosX;
            state.serverY = cart.serverPosY;
            state.serverZ = cart.serverPosZ;
            STATES.put(cart, state);
        }
        else if (state.serverX != cart.serverPosX || state.serverY != cart.serverPosY || state.serverZ != cart.serverPosZ)
        {
            measureSpeed(state, cart, serverX, serverY, serverZ);
            state.serverX = cart.serverPosX;
            state.serverY = cart.serverPosY;
            state.serverZ = cart.serverPosZ;
            state.ticksSinceUpdate = 0;
        }
        else if (++state.ticksSinceUpdate >= STOPPED_AFTER)
        {
            // No new position for a while: the server isn't moving it
            state.speed = 0.0D;
        }

        TrackWalk.Cursor cursor = state.cursor;
        TrackWalk.Cursor start = cursor.copy();
        cursor.advance(state.speed);

        // Where the server has the cart now: its last position, moved on by the ticks since it arrived
        double behind = cursor.offsetTo(serverX, serverZ, MAX_DRIFT);
        double drift = Double.isNaN(behind) ? Double.NaN : behind + state.speed * state.ticksSinceUpdate;

        if (Double.isNaN(drift) || Math.abs(drift) > MAX_DRIFT)
        {
            if (!placeAt(state, cart, serverX, serverY, serverZ, cursor.dirX, cursor.dirZ))
            {
                STATES.remove(cart);
                return null;
            }

            cursor = state.cursor;
            cursor.advance(state.speed * state.ticksSinceUpdate);
        }
        else
        {
            cursor.advance(drift * PULL);
            AxisAlignedBB body = blocker != null && blocks(blocker, cart) ? blocker.getEntityBoundingBox() : null;
            cursor = keepClear(cart, start, cursor, serverY, box -> cart.world.collidesWithAnyBlock(CartBody.wallBox(box))
                || body != null && box.intersects(body));
            state.cursor = cursor;
        }

        cart.setPosition(cursor.x, heightAt(cart, cursor, serverY), cursor.z);

        // Riders were put in their seat before the cart moved; put them back in it
        for (Entity rider : cart.getPassengers())
        {
            cart.updatePassenger(rider);
        }

        return cursor;
    }

    /** Rail height, which rises and falls on slopes; the server's height if the rail can't be found. */
    private static double heightAt(EntityMinecart cart, TrackWalk.Cursor point, double serverY)
    {
        Vec3d onRail = cart.getPos(point.x, point.rail().getY() + 0.1D, point.z);
        return onRail != null ? onRail.y : serverY;
    }

    /** Whether an entity is one this cart would run into: not riding it, and solid. */
    private static boolean blocks(Entity entity, EntityMinecart cart)
    {
        return !entity.isRidingSameEntity(cart) && entity.getRidingEntity() != cart && !entity.noClip
            && !(entity instanceof EntityPlayer && ((EntityPlayer)entity).isSpectator());
    }

    /** The cart's box with the cart at a point on the track. */
    private static AxisAlignedBB boxAt(EntityMinecart cart, TrackWalk.Cursor point, double serverY)
    {
        double y = heightAt(cart, point, serverY);
        double half = cart.width / 2.0D;
        return new AxisAlignedBB(point.x - half, y, point.z - half, point.x + half, y + cart.height, point.z + half);
    }

    /**
     * Where the cart can go this tick without running into something: all the way if it's clear, otherwise as far
     * as it can along the track before touching. The server stops a cart at a wall or at the player's body, but a
     * few ticks go by before the client hears; drawn meanwhile rolling on, the cart would sink into the wall, half
     * its length, or have the player inside it. A cart already in something at the start moves freely.
     */
    private static TrackWalk.Cursor keepClear(EntityMinecart cart, TrackWalk.Cursor from, TrackWalk.Cursor to, double serverY, Predicate<AxisAlignedBB> inTheWay)
    {
        if (!inTheWay.test(boxAt(cart, to, serverY)) || inTheWay.test(boxAt(cart, from, serverY)))
        {
            return to;
        }

        double move = from.offsetTo(to.x, to.z, MAX_DRIFT);

        if (Double.isNaN(move))
        {
            return to;
        }

        // The furthest point along the way that's still clear of the blocker
        double clear = 0.0D;
        double blocked = move;

        for (int i = 0; i < 12; ++i)
        {
            double half = (clear + blocked) * 0.5D;
            TrackWalk.Cursor probe = from.copy();
            probe.advance(half);

            if (inTheWay.test(boxAt(cart, probe, serverY)))
            {
                blocked = half;
            }
            else
            {
                clear = half;
            }
        }

        TrackWalk.Cursor stop = from.copy();
        stop.advance(clear);
        return stop;
    }

    /** How fast the cart is travelling along the track, as measured on the client; 0 once it has stopped. */
    static double speedOf(EntityMinecart cart)
    {
        State state = STATES.get(cart);
        return state == null ? 0.0D : state.speed;
    }

    /** Measures the speed from how far along the track the server moved the cart since its previous position. */
    private static void measureSpeed(State state, EntityMinecart cart, double x, double y, double z)
    {
        TrackWalk.Cursor from = TrackWalk.at(cart, state.serverX / 4096.0D, state.serverY / 4096.0D, state.serverZ / 4096.0D,
            state.cursor.dirX, state.cursor.dirZ);
        double moved = from == null ? Double.NaN : from.offsetTo(x, z, MAX_DRIFT);

        if (Double.isNaN(moved))
        {
            return;
        }

        // Moved the other way from the way the point is heading: turn the point round where it stands
        if (moved < 0.0D)
        {
            state.cursor.reverse();
            moved = -moved;
        }

        state.speed = Math.min(moved / (state.ticksSinceUpdate + 1), CartBody.railSpeedCap(cart));
    }

    /** Puts the cart's point on the track where the server has it, heading the given way. */
    private static boolean placeAt(State state, EntityMinecart cart, double x, double y, double z, double dirX, double dirZ)
    {
        if (dirX * dirX + dirZ * dirZ < 1.0E-8D)
        {
            // No way to go by: the way the cart faces
            double yaw = Math.toRadians(cart.rotationYaw);
            dirX = Math.cos(yaw);
            dirZ = Math.sin(yaw);
        }

        TrackWalk.Cursor cursor = TrackWalk.at(cart, x, y, z, dirX, dirZ);

        if (cursor == null)
        {
            return false;
        }

        state.cursor = cursor;
        return true;
    }
}
