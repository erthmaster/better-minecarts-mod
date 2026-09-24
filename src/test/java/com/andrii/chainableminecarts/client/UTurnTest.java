package com.andrii.chainableminecarts.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.andrii.chainableminecarts.test.TestWorld;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.item.EntityMinecart;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Every kind of cart round U-turns, every way round, watched on the server and on the client. */
@RunWith(Parameterized.class)
public class UTurnTest
{
    /** A U with its turn at the top (north): legs run south from it. */
    static final String[] U_NORTH = {
        "r7", "||", "||", "||", "||", "||", "||", "||", "||", "||", "||", "||", "||", "||",
    };

    /** A U with its turn at the bottom (south). */
    static final String[] U_SOUTH = {
        "||", "||", "||", "||", "||", "||", "||", "||", "||", "||", "||", "||", "||", "LJ",
    };

    /** A U with its turn on the east, legs running west. */
    static final String[] U_EAST = {
        "-------------7",
        "-------------J",
    };

    /** A U with its turn on the west, legs running east. */
    static final String[] U_WEST = {
        "r-------------",
        "L-------------",
    };

    /** A U with a straight between its two turns. */
    static final String[] U_WIDE = {
        "r-7", "| |", "| |", "| |", "| |", "| |", "| |", "| |", "| |", "| |", "| |", "| |", "| |", "| |",
    };

    @Parameterized.Parameter
    public EntityMinecart.Type type;

    @Parameterized.Parameters(name = "{0}")
    public static List<Object[]> types()
    {
        List<Object[]> types = new ArrayList<>();

        for (EntityMinecart.Type type : EntityMinecart.Type.values())
        {
            types.add(new Object[] {type});
        }

        return types;
    }

    /**
     * One run: a cart (or train) started on a leg heading into the turn, server and client watched throughout.
     * Returns how far the first cart travelled along the track.
     */
    private double run(String label, String[] track, double x, double z, double dirX, double dirZ, double speed, boolean ridden, int length)
    {
        Game game = new Game(new TestWorld().track(track), new TestWorld(true).track(track));
        EntityMinecart[] train = new EntityMinecart[length];

        for (int i = 0; i < length; ++i)
        {
            EntityMinecart.Type type = length == 1 || i == 1 ? this.type : EntityMinecart.Type.RIDEABLE;
            train[i] = game.server.cart(type, x - dirX * 1.58D * i, z - dirZ * 1.58D * i);
            train[i].motionX = dirX * speed;
            train[i].motionZ = dirZ * speed;
        }

        if (ridden)
        {
            EntityArmorStand rider = new EntityArmorStand(game.server, x, 1.5D, z);
            game.server.spawnEntity(rider);
            rider.startRiding(train[0], true);
        }

        game.server.chain(train);
        game.track(train);
        List<UTurnProbe> probes = new ArrayList<>();

        for (int i = 0; i < length; ++i)
        {
            probes.add(new UTurnProbe("server " + i, train[i], dirX, dirZ));
            probes.add(new UTurnProbe("client " + i, game.carts.get(i).client, dirX, dirZ));
        }

        double error = 0.0D;

        for (int tick = 0; tick < 120; ++tick)
        {
            game.tick();

            if (tick >= UTurnProbe.SETTLE)
            {
                error = Math.max(error, game.worstError());
            }

            for (UTurnProbe probe : probes)
            {
                probe.sample(tick);
            }
        }

        StringBuilder report = new StringBuilder(String.format("%n== %s %s: client error %.3f%n", this.type, label, error));

        for (UTurnProbe probe : probes)
        {
            report.append("   ").append(probe).append('\n');
        }

        String where = report.toString();

        for (UTurnProbe probe : probes)
        {
            // Never backwards along the track, never jumping more than a cart moves in a tick
            assertTrue("went back" + where, probe.backStep < (probe.name.startsWith("server") ? 1.0E-3D : 0.02D));
            assertTrue("jumped" + where, probe.biggestStep < 0.42D);

            if (probe.name.startsWith("client"))
            {
                // What's drawn: on the rails, facing along them, turning smoothly
                assertTrue("off the rails" + where, probe.offPath < 0.01D);
                assertTrue("facing off the track" + where, probe.misaligned < 5.0D);
                // A turn has a radius of half a block: at the top speed of 0.4 a tick, that's 46 degrees a tick
                assertTrue("turned sharply" + where, probe.yawTurn < UTurnProbe.MAX_TURN);
            }
        }

        assertTrue("client strayed from the server" + where, error < 0.35D);
        return probes.get(0).travelled;
    }

    /**
     * The same run every way round: into the turn from either leg, and with the U facing each way. Mirror images
     * of each other, they must all go the same way: a cart held up at the join between the two turns one way round
     * travels less.
     */
    @Test
    public void everyWayRoundAUTurnGoesTheSame()
    {
        double[] travelled = {
            this.run("north U, up the left leg", U_NORTH, 0.5D, 6.5D, 0.0D, -1.0D, 0.4D, false, 1),
            this.run("north U, up the right leg", U_NORTH, 1.5D, 6.5D, 0.0D, -1.0D, 0.4D, false, 1),
            this.run("south U, down the left leg", U_SOUTH, 0.5D, 7.5D, 0.0D, 1.0D, 0.4D, false, 1),
            this.run("south U, down the right leg", U_SOUTH, 1.5D, 7.5D, 0.0D, 1.0D, 0.4D, false, 1),
            this.run("east U, along the top leg", U_EAST, 7.5D, 0.5D, 1.0D, 0.0D, 0.4D, false, 1),
            this.run("east U, along the bottom leg", U_EAST, 7.5D, 1.5D, 1.0D, 0.0D, 0.4D, false, 1),
            this.run("west U, along the top leg", U_WEST, 6.5D, 0.5D, -1.0D, 0.0D, 0.4D, false, 1),
            this.run("west U, along the bottom leg", U_WEST, 6.5D, 1.5D, -1.0D, 0.0D, 0.4D, false, 1),
        };

        for (double distance : travelled)
        {
            assertEquals("distance round the U", travelled[0], distance, 0.02D);
        }
    }

    @Test
    public void roundAWideU()
    {
        this.run("wide U, up the left leg", U_WIDE, 0.5D, 6.5D, 0.0D, -1.0D, 0.4D, false, 1);
        this.run("wide U, up the right leg", U_WIDE, 2.5D, 6.5D, 0.0D, -1.0D, 0.4D, false, 1);
    }

    @Test
    public void roundAUTurnSlowly()
    {
        this.run("north U, slow, left leg", U_NORTH, 0.5D, 2.5D, 0.0D, -1.0D, 0.12D, false, 1);
        this.run("north U, slow, right leg", U_NORTH, 1.5D, 2.5D, 0.0D, -1.0D, 0.12D, false, 1);
    }

    @Test
    public void inATrainRoundAUTurn()
    {
        this.run("north U, train up the left leg", U_NORTH, 0.5D, 5.5D, 0.0D, -1.0D, 0.4D, false, 3);
        this.run("north U, train up the right leg", U_NORTH, 1.5D, 5.5D, 0.0D, -1.0D, 0.4D, false, 3);
    }

    @Test
    public void riddenRoundAUTurn()
    {
        this.run("north U, ridden up the left leg", U_NORTH, 0.5D, 6.5D, 0.0D, -1.0D, 0.4D, true, 1);
        this.run("north U, ridden up the right leg", U_NORTH, 1.5D, 6.5D, 0.0D, -1.0D, 0.4D, true, 1);
    }
}
