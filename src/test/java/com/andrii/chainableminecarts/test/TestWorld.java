package com.andrii.chainableminecarts.test;

import com.andrii.chainableminecarts.physics.CartContacts;
import com.andrii.chainableminecarts.physics.CartFriction;
import com.andrii.chainableminecarts.physics.FurnaceEngine;
import com.andrii.chainableminecarts.physics.HeavyCollisionHandler;
import com.andrii.chainableminecarts.physics.SolidCarts;
import com.andrii.chainableminecarts.physics.SpeedLimit;
import com.andrii.chainableminecarts.physics.TrainPhysics;
import com.andrii.chainableminecarts.rail.CurveFollower;
import com.andrii.chainableminecarts.rail.TrackWalk;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.block.BlockRail;
import net.minecraft.block.BlockRailPowered;
import net.minecraft.block.state.IBlockState;
import net.minecraft.block.BlockRailBase.EnumRailDirection;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.item.EntityMinecartEmpty;
import net.minecraft.entity.item.EntityMinecartFurnace;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.SaveHandlerMP;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.GetCollisionBoxesEvent;
import net.minecraftforge.event.entity.minecart.MinecartUpdateEvent;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventBus;
import net.minecraftforge.fml.common.eventhandler.EventPriority;

/**
 * A real Minecraft world held in memory, for testing cart physics without starting the game: vanilla rails, vanilla
 * carts running their own update, and this mod's physics hooked in exactly as on a server.
 * <p>
 * Each {@link #tick} runs the carts' vanilla updates (with this mod's per-cart handlers on the event bus), then what
 * a server does at the end of a world tick, in the same order: trains, then contacts between carts, then the speed
 * limit. Leads are given directly as pairs in {@link #links} instead of through the capability, which needs the full
 * mod loader.
 */
public class TestWorld extends World
{
    /** Height of the rails; the ground is one below. */
    public static final int RAIL_Y = 1;

    private static boolean gameReady;

    private final Map<Long, Chunk> chunks = new HashMap<>();
    /** Chained pairs of carts, as the lead manager would hand them to the train physics. */
    public final List<EntityMinecart[]> links = new ArrayList<>();
    /** Called each tick after the carts' own updates, where a server sends positions and speeds to clients. */
    @Nullable
    public Consumer<TestWorld> afterEntities;

    public TestWorld()
    {
        this(false);
    }

    public TestWorld(boolean client)
    {
        super(new SaveHandlerMP(), new WorldInfo(new WorldSettings(0L, GameType.CREATIVE, false, false, WorldType.FLAT), "test"),
            new WorldProviderSurface(), new Profiler(), client);
        setUpGame();
        this.provider.setWorld(this);
        this.chunkProvider = this.createChunkProvider();
    }

    /** Vanilla's blocks and items, and this mod's cart handlers, once per test run. */
    private static synchronized void setUpGame()
    {
        if (gameReady)
        {
            return;
        }

        Bootstrap.register();
        EntityMinecart.setCollisionHandler(new HeavyCollisionHandler());

        // Forge's event bus relies on a class transformer that only runs inside the game, so the mod's handlers
        // can't be registered the normal way here. Without it every event shares the base event's listener list,
        // so one listener there sees every event posted, and hands the ones the physics needs to their handlers:
        // cart updates (in priority order), and solid carts' collision boxes.
        try
        {
            Field busId = EventBus.class.getDeclaredField("busID");
            busId.setAccessible(true);
            new Event().getListenerList().register(busId.getInt(MinecraftForge.EVENT_BUS), EventPriority.NORMAL, event ->
            {
                if (event instanceof GetCollisionBoxesEvent)
                {
                    SolidCarts.onGetCollisionBoxes((GetCollisionBoxesEvent)event);
                }
                else if (event instanceof MinecartUpdateEvent)
                {
                    MinecartUpdateEvent update = (MinecartUpdateEvent)event;
                    CartFriction.onMinecartUpdate(update);
                    CurveFollower.onMinecartUpdate(update);
                    FurnaceEngine.onMinecartUpdate(update);
                }
            });
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalStateException(e);
        }

        gameReady = true;
    }

    @Override
    protected IChunkProvider createChunkProvider()
    {
        return new IChunkProvider()
        {
            @Override
            public Chunk getLoadedChunk(int x, int z)
            {
                return this.provideChunk(x, z);
            }

            @Override
            public Chunk provideChunk(int x, int z)
            {
                return TestWorld.this.chunks.computeIfAbsent(ChunkPos.asLong(x, z), key -> new Chunk(TestWorld.this, x, z));
            }

            @Override
            public boolean tick()
            {
                return false;
            }

            @Override
            public String makeString()
            {
                return "TestWorld";
            }

            @Override
            public boolean isChunkGeneratedAt(int x, int z)
            {
                return true;
            }
        };
    }

    @Override
    protected boolean isChunkLoaded(int x, int z, boolean allowEmpty)
    {
        return true;
    }

    /**
     * Lays rails from a map, one character per block: x runs along each row, z down the rows.
     * <pre>
     *   - east-west          | north-south
     *   r south-east (top-left corner)      7 south-west (top-right corner)
     *   L north-east (bottom-left corner)   J north-west (bottom-right corner)
     * </pre>
     * Anything else is left empty.
     */
    public TestWorld track(String... rows)
    {
        for (int z = 0; z < rows.length; ++z)
        {
            for (int x = 0; x < rows[z].length(); ++x)
            {
                EnumRailDirection shape = shapeOf(rows[z].charAt(x));

                if (shape != null)
                {
                    this.rail(x, z, shape);
                }
            }
        }

        return this;
    }

    @Nullable
    private static EnumRailDirection shapeOf(char c)
    {
        switch (c)
        {
            case '-': return EnumRailDirection.EAST_WEST;
            case '|': return EnumRailDirection.NORTH_SOUTH;
            case 'r': return EnumRailDirection.SOUTH_EAST;
            case '7': return EnumRailDirection.SOUTH_WEST;
            case 'L': return EnumRailDirection.NORTH_EAST;
            case 'J': return EnumRailDirection.NORTH_WEST;
            default: return null;
        }
    }

    /** Lays a straight east-west line along z = 0 at x = 0, 1, 2...: '-' rail, 'P' powered rail, 'p' unpowered one. */
    public TestWorld line(String rails)
    {
        for (int x = 0; x < rails.length(); ++x)
        {
            char c = rails.charAt(x);

            if (c == 'P' || c == 'p')
            {
                this.place(new BlockPos(x, RAIL_Y, 0), Blocks.GOLDEN_RAIL.getDefaultState()
                    .withProperty(BlockRailPowered.SHAPE, EnumRailDirection.EAST_WEST)
                    .withProperty(BlockRailPowered.POWERED, c == 'P'));
            }
            else if (c == '-')
            {
                this.rail(x, 0, EnumRailDirection.EAST_WEST);
            }
        }

        return this;
    }

    /** Places one rail on stone, exactly as given: nothing reshapes it or its neighbours. */
    public void rail(int x, int z, EnumRailDirection shape)
    {
        this.place(new BlockPos(x, RAIL_Y, z), Blocks.RAIL.getDefaultState().withProperty(BlockRail.SHAPE, shape));
    }

    /** Places a rail block on stone, exactly as given: nothing reshapes or powers it, or touches its neighbours. */
    public void place(BlockPos pos, IBlockState rail)
    {
        // Captured placement skips onBlockAdded and neighbour updates, which would reshape the rails
        this.captureBlockSnapshots = true;
        this.setBlockState(pos.down(), Blocks.STONE.getDefaultState(), 2);
        this.setBlockState(pos, rail, 2);
        this.captureBlockSnapshots = false;
        this.capturedBlockSnapshots.clear();
    }

    /** An empty cart on the rail at the given block coordinates (x.5 and z.5 is the middle of a block). */
    public EntityMinecartEmpty cart(double x, double z)
    {
        return this.add(new EntityMinecartEmpty(this, x, RAIL_Y + 0.0625D, z));
    }

    /** A cart of any type, as the cart items place them (a furnace cart without fuel). */
    public EntityMinecart cart(EntityMinecart.Type type, double x, double z)
    {
        return this.add(EntityMinecart.create(this, x, RAIL_Y + 0.0625D, z, type));
    }

    /** A furnace cart facing the given way, with fuel for the given distance and its engine running. */
    public EntityMinecartFurnace furnace(double x, double z, double dirX, double dirZ, double fuelBlocks)
    {
        EntityMinecartFurnace furnace = new EntityMinecartFurnace(this, x, RAIL_Y + 0.0625D, z);
        furnace.getEntityData().setDouble("better_minecarts:FuelBlocks", fuelBlocks);
        furnace.getEntityData().setDouble("better_minecarts:DirX", dirX);
        furnace.getEntityData().setDouble("better_minecarts:DirZ", dirZ);
        return this.add(furnace);
    }

    private <T extends EntityMinecart> T add(T cart)
    {
        this.spawnEntity(cart);
        return cart;
    }

    /** Chains carts one after another into a train. */
    public void chain(EntityMinecart... carts)
    {
        for (int i = 0; i + 1 < carts.length; ++i)
        {
            this.links.add(new EntityMinecart[] {carts[i], carts[i + 1]});
        }
    }

    /** One server tick. */
    public void tick()
    {
        this.worldInfo.setWorldTotalTime(this.worldInfo.getWorldTotalTime() + 1L);

        for (Entity entity : new ArrayList<>(this.loadedEntityList))
        {
            if (!entity.isDead)
            {
                this.updateEntity(entity);
            }
        }

        if (this.afterEntities != null)
        {
            this.afterEntities.accept(this);
        }

        TrainPhysics.solve(this.links, FurnaceEngine.takeDriving(this), FurnaceEngine.takeBraking(this));
        CartContacts.solveAll(this);
        SpeedLimit.limitAll(this);
    }

    public void tick(int ticks)
    {
        for (int i = 0; i < ticks; ++i)
        {
            this.tick();
        }
    }

    /** Horizontal speed, in blocks per tick. */
    public static double speed(EntityMinecart cart)
    {
        return Math.sqrt(cart.motionX * cart.motionX + cart.motionZ * cart.motionZ);
    }

    /**
     * Distance between two carts' centres along the track, or NaN if the track doesn't lead from one to the other
     * within a few blocks.
     */
    public static double alongTrack(EntityMinecart a, EntityMinecart b)
    {
        double[] direction = TrackWalk.direction(a);

        if (direction == null)
        {
            return Double.NaN;
        }

        TrackWalk.Step step = TrackWalk.to(a, b, direction[0], direction[1]);

        if (step == null)
        {
            step = TrackWalk.to(a, b, -direction[0], -direction[1]);
        }

        return step == null ? Double.NaN : step.distance;
    }

    /** Gap between the edges of two carts along the track. */
    public static double gap(EntityMinecart a, EntityMinecart b)
    {
        return alongTrack(a, b) - (a.width + b.width) * 0.5D;
    }
}
