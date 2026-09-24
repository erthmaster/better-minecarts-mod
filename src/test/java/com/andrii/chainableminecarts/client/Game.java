package com.andrii.chainableminecarts.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.andrii.chainableminecarts.test.TestWorld;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.item.EntityMinecartFurnace;
import net.minecraft.util.math.MathHelper;

/**
 * A server test world and a client world with the same track, joined the way vanilla joins them: each server cart
 * has a copy on the client, drawn by {@link CartFollower} from exactly what vanilla's entity tracker would send for
 * a minecart. That's the position every third tick if the cart has moved (in 1/4096ths of a block), and the velocity
 * only when it has changed by more than 0.02 or has become exactly nothing.
 */
final class Game
{
    final TestWorld server;
    final TestWorld client;
    final List<Tracked> carts = new ArrayList<>();

    Game(TestWorld server, TestWorld client)
    {
        this.server = server;
        this.client = client;
        this.server.afterEntities = world -> this.carts.forEach(Tracked::send);
    }

    Game(String... track)
    {
        this(new TestWorld().track(track), new TestWorld(true).track(track));
    }

    EntityMinecart[] carts(double... xz)
    {
        EntityMinecart[] carts = new EntityMinecart[xz.length / 2];

        for (int i = 0; i < carts.length; ++i)
        {
            carts[i] = this.server.cart(xz[i * 2], xz[i * 2 + 1]);
        }

        return carts;
    }

    /** Starts sending the carts to the client, as when a player comes in range. */
    void track(EntityMinecart... carts)
    {
        for (EntityMinecart cart : carts)
        {
            EntityMinecart copy = EntityMinecart.create(this.client, cart.posX, cart.posY, cart.posZ, cart.getType());
            copy.rotationYaw = cart.rotationYaw;
            copy.prevRotationYaw = cart.rotationYaw;
            this.client.spawnEntity(copy);
            this.carts.add(new Tracked(cart, copy));

            if (cart instanceof EntityMinecartFurnace && cart.getEntityData().hasKey("better_minecarts:DirX"))
            {
                // What FurnaceStateMessage tells the client
                FurnaceClient.setState(copy, true, cart.getEntityData().getDouble("better_minecarts:DirX"),
                    cart.getEntityData().getDouble("better_minecarts:DirZ"));
            }
        }
    }

    /**
     * One server tick, then one client tick: vanilla's own client update for each cart (which slides it towards the
     * last position received), then this mod's, as at the end of a client tick.
     */
    void tick()
    {
        this.server.tick();

        for (Tracked tracked : this.carts)
        {
            this.client.updateEntity(tracked.client);
            CurveSmoothing.followTrack(tracked.client, null);
        }
    }

    void tick(int ticks)
    {
        for (int i = 0; i < ticks; ++i)
        {
            this.tick();
        }
    }

    /** Furthest any client cart is from where the server has it. */
    double worstError()
    {
        double worst = 0.0D;

        for (Tracked tracked : this.carts)
        {
            worst = Math.max(worst, tracked.error());
        }

        return worst;
    }

    /** Once the server has stopped the carts, the client shows them stopped where they are, and they stay there. */
    void assertClientSettled()
    {
        for (int tick = 0; tick < 20; ++tick)
        {
            this.tick();
        }

        assertTrue(String.format("client carts are %.3f from where the server stopped them", this.worstError()), this.worstError() < 0.01D);
        double[] where = new double[this.carts.size() * 2];

        for (int i = 0; i < this.carts.size(); ++i)
        {
            where[i * 2] = this.carts.get(i).client.posX;
            where[i * 2 + 1] = this.carts.get(i).client.posZ;
        }

        for (int tick = 0; tick < 300; ++tick)
        {
            this.tick();
        }

        for (int i = 0; i < this.carts.size(); ++i)
        {
            assertEquals("client cart " + i + " drifted along x", where[i * 2], this.carts.get(i).client.posX, 1.0E-9D);
            assertEquals("client cart " + i + " drifted along z", where[i * 2 + 1], this.carts.get(i).client.posZ, 1.0E-9D);
        }
    }

    /** One cart as vanilla's EntityTrackerEntry sends it: range 80, every 3 ticks, with velocity. */
    static final class Tracked
    {
        final EntityMinecart server;
        final EntityMinecart client;
        long sentX;
        long sentY;
        long sentZ;
        double sentMotionX;
        double sentMotionY;
        double sentMotionZ;
        int updateCounter;

        Tracked(EntityMinecart server, EntityMinecart client)
        {
            this.server = server;
            this.client = client;
            // The spawn packet: position and velocity
            this.sentX = encode(server.posX);
            this.sentY = encode(server.posY);
            this.sentZ = encode(server.posZ);
            client.serverPosX = this.sentX;
            client.serverPosY = this.sentY;
            client.serverPosZ = this.sentZ;
            this.sentMotionX = server.motionX;
            this.sentMotionY = server.motionY;
            this.sentMotionZ = server.motionZ;
            client.setVelocity(receive(server.motionX), receive(server.motionY), receive(server.motionZ));
        }

        private static float angle(float degrees)
        {
            return (byte)MathHelper.floor(degrees * 256.0F / 360.0F) * 360 / 256.0F;
        }

        private static long encode(double position)
        {
            return MathHelper.lfloor(position * 4096.0D);
        }

        /** Velocity as it arrives: sent in 1/8000ths, capped at 3.9. */
        private static double receive(double motion)
        {
            return (int)(MathHelper.clamp(motion, -3.9D, 3.9D) * 8000.0D) / 8000.0D;
        }

        void send()
        {
            if (this.updateCounter % 3 == 0)
            {
                long x = encode(this.server.posX);
                long y = encode(this.server.posY);
                long z = encode(this.server.posZ);
                long dx = x - this.sentX;
                long dy = y - this.sentY;
                long dz = z - this.sentZ;

                if (dx * dx + dy * dy + dz * dz >= 128L || this.updateCounter % 60 == 0)
                {
                    this.sentX = x;
                    this.sentY = y;
                    this.sentZ = z;
                    this.client.serverPosX = x;
                    this.client.serverPosY = y;
                    this.client.serverPosZ = z;
                    // As NetHandlerPlayClient.handleEntityMovement passes it on; angles travel as 1/256ths of a turn
                    this.client.setPositionAndRotationDirect(x / 4096.0D, y / 4096.0D, z / 4096.0D,
                        angle(this.server.rotationYaw), angle(this.server.rotationPitch), 3, false);
                }

                if (this.updateCounter > 0)
                {
                    double mx = this.server.motionX - this.sentMotionX;
                    double my = this.server.motionY - this.sentMotionY;
                    double mz = this.server.motionZ - this.sentMotionZ;
                    double change = mx * mx + my * my + mz * mz;

                    if (change > 4.0E-4D || change > 0.0D && this.server.motionX == 0.0D && this.server.motionY == 0.0D && this.server.motionZ == 0.0D)
                    {
                        this.sentMotionX = this.server.motionX;
                        this.sentMotionY = this.server.motionY;
                        this.sentMotionZ = this.server.motionZ;
                        this.client.setVelocity(receive(this.sentMotionX), receive(this.sentMotionY), receive(this.sentMotionZ));
                    }
                }
            }

            ++this.updateCounter;
        }

        double error()
        {
            double dx = this.client.posX - this.server.posX;
            double dz = this.client.posZ - this.server.posZ;
            return Math.sqrt(dx * dx + dz * dz);
        }
    }
}
