package com.andrii.chainableminecarts.physics;

import com.andrii.chainableminecarts.ChainableMinecarts;
import com.andrii.chainableminecarts.ModConfig;
import com.andrii.chainableminecarts.network.FurnaceStateMessage;
import com.andrii.chainableminecarts.rail.RailPath;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.entity.item.EntityMinecartFurnace;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.minecart.MinecartUpdateEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Replaces the furnace cart's engine. Vanilla fuel is time (3600 ticks per coal, burning even while stopped), and
 * its push is a fixed compass direction that doesn't turn with the track: on a curve it ends up sideways or
 * backwards, vanilla drops it, and the cart coasts to a stop while still burning fuel.
 * <p>
 * Here fuel is distance: each coal lasts a fixed number of blocks, used up only by actually moving. Each furnace
 * cart drives one way only: the direction its placer was facing, kept along the rail through curves. Clicking with
 * coal adds fuel and starts the engine; clicking without coal puts its brakes on, slowing it and its train to a
 * stop, after which they come off and the cart is no different from any other. Nothing turns the cart round short
 * of breaking and placing it again. The driving itself happens per train in {@link TrainPhysics}, so
 * pulling power depends on how many furnaces and carts the train has. Vanilla's own fuel field is only used to
 * make the furnace look lit.
 */
@Mod.EventBusSubscriber(modid = ChainableMinecarts.MODID)
public final class FurnaceEngine
{
    private static final String FUEL_KEY = ChainableMinecarts.MODID + ":FuelBlocks";
    private static final String DIR_X_KEY = ChainableMinecarts.MODID + ":DirX";
    private static final String DIR_Z_KEY = ChainableMinecarts.MODID + ":DirZ";
    private static final String STOPPED_KEY = ChainableMinecarts.MODID + ":Stopped";
    private static final String BRAKING_KEY = ChainableMinecarts.MODID + ":Braking";
    /** Vanilla fuel per coal, used to convert furnace carts fuelled before this mod was installed. */
    private static final int VANILLA_TICKS_PER_COAL = 3600;
    /** How often clients are reminded which way a furnace cart faces, in ticks. */
    private static final int RESYNC_TICKS = 20;
    /** Most coal a furnace cart holds, as in vanilla (fuel capped at 32000 ticks). */
    private static final int MAX_COAL = 8;

    /** EntityMinecartFurnace's private fuel counter: its only int field, so it's found by type, not by name. */
    private static final Field VANILLA_FUEL = findVanillaFuelField();

    /** Lit state last sent to clients, per cart. */
    private static final Map<EntityMinecartFurnace, Boolean> SENT_LIT = new WeakHashMap<>();
    /** Fuelled furnace carts on rails this tick, per world, handed to TrainPhysics at the end of the tick. */
    private static final Map<World, List<EntityMinecartFurnace>> DRIVING = new WeakHashMap<>();
    /** Furnace carts braking to a stop, per world; TrainPhysics slows their trains with them. */
    private static final Map<World, List<EntityMinecartFurnace>> BRAKING = new WeakHashMap<>();

    /**
     * A furnace cart being placed right now: the item's use spawns the cart within the same click, so the placer's
     * facing is noted on the click and given to the cart as it joins the world.
     */
    private static World placingWorld;
    private static BlockPos placingPos;
    private static double placingDirX;
    private static double placingDirZ;

    private FurnaceEngine()
    {
    }

    private static Field findVanillaFuelField()
    {
        for (Field field : EntityMinecartFurnace.class.getDeclaredFields())
        {
            if (field.getType() == int.class && !Modifier.isStatic(field.getModifiers()))
            {
                field.setAccessible(true);
                return field;
            }
        }

        throw new IllegalStateException("EntityMinecartFurnace has no fuel field");
    }

    /**
     * Sets vanilla's fuel counter, which vanilla uses to decide whether the furnace is lit (and smokes). Vanilla
     * takes one off before checking, so 2 keeps it lit through the next tick.
     */
    public static void setVanillaLit(EntityMinecartFurnace cart, boolean lit)
    {
        try
        {
            VANILLA_FUEL.setInt(cart, lit ? 2 : 0);
        }
        catch (IllegalAccessException e)
        {
            throw new IllegalStateException(e);
        }
    }

    private static int getVanillaFuel(EntityMinecartFurnace cart)
    {
        try
        {
            return VANILLA_FUEL.getInt(cart);
        }
        catch (IllegalAccessException e)
        {
            throw new IllegalStateException(e);
        }
    }

    /** Fuel left, in blocks. Carts fuelled by vanilla (before this mod) have their fuel converted once. */
    private static double getFuel(EntityMinecartFurnace cart)
    {
        NBTTagCompound data = cart.getEntityData();

        if (!data.hasKey(FUEL_KEY))
        {
            int vanillaFuel = getVanillaFuel(cart);
            data.setDouble(FUEL_KEY, (double)vanillaFuel / VANILLA_TICKS_PER_COAL * ModConfig.furnace.furnaceBlocksPerCoal);

            if (vanillaFuel > 0)
            {
                setDirection(cart, cart.pushX, cart.pushZ);
            }
        }

        return data.getDouble(FUEL_KEY);
    }

    private static void setDirection(EntityMinecartFurnace cart, double x, double z)
    {
        double length = Math.sqrt(x * x + z * z);

        if (length > 1.0E-4D)
        {
            cart.getEntityData().setDouble(DIR_X_KEY, x / length);
            cart.getEntityData().setDouble(DIR_Z_KEY, z / length);
        }
    }

    /** Fired on the server at the end of each cart's move, before the furnace's own fuel handling. */
    @SubscribeEvent
    public static void onMinecartUpdate(MinecartUpdateEvent event)
    {
        if (!ModConfig.furnace.distanceFurnaceFuel || !(event.getMinecart() instanceof EntityMinecartFurnace) || event.getMinecart().world.isRemote)
        {
            return;
        }

        EntityMinecartFurnace cart = (EntityMinecartFurnace)event.getMinecart();
        NBTTagCompound data = cart.getEntityData();
        double fuel = getFuel(cart);

        // Vanilla's push is never used: with it at zero, vanilla neither drives the cart nor drops the push
        cart.pushX = 0.0D;
        cart.pushZ = 0.0D;

        boolean running = fuel > 0.0D && !data.getBoolean(STOPPED_KEY);

        if (running)
        {
            // Burn fuel for the distance actually moved this tick
            double movedX = cart.posX - cart.prevPosX;
            double movedZ = cart.posZ - cart.prevPosZ;
            fuel = Math.max(0.0D, fuel - Math.sqrt(movedX * movedX + movedZ * movedZ));
            data.setDouble(FUEL_KEY, fuel);
            running = fuel > 0.0D;
        }

        double[] axis = CartBody.railAxis(cart);
        RailPath.Point track = axis == null ? null : RailPath.nearest(cart, cart.posX, cart.posY, cart.posZ);

        if (track != null && hasDirection(cart))
        {
            // Keep the travel direction pointing along the track, keeping its sense. The track's own direction
            // runs on smoothly from one rail into the next, even between two turns at right angles, where the
            // straight diagonals of the two turns are square to each other and couldn't tell which way is which.
            // Done even without fuel, so pushing a cold cart around doesn't lose which way it faces.
            double sign = track.dirX * data.getDouble(DIR_X_KEY) + track.dirZ * data.getDouble(DIR_Z_KEY) < 0.0D ? -1.0D : 1.0D;
            setDirection(cart, track.dirX * sign, track.dirZ * sign);

            if (running)
            {
                DRIVING.computeIfAbsent(cart.world, w -> new ArrayList<>()).add(cart);
            }
            else if (data.getBoolean(BRAKING_KEY))
            {
                // Braking to a stop after being switched off. Once stopped the brakes come off, so an idle
                // furnace cart is no different from any other cart in a train.
                if (fuel > 0.0D && cart.motionX * cart.motionX + cart.motionZ * cart.motionZ > 1.0E-6D)
                {
                    BRAKING.computeIfAbsent(cart.world, w -> new ArrayList<>()).add(cart);
                }
                else
                {
                    data.setBoolean(BRAKING_KEY, false);
                }
            }
        }

        boolean lit = running;
        setVanillaLit(cart, lit);

        // Clients follow the track themselves, but every so often they're told again, so they can't drift apart
        if (!Boolean.valueOf(lit).equals(SENT_LIT.get(cart)) || cart.ticksExisted % RESYNC_TICKS == 0)
        {
            SENT_LIT.put(cart, lit);
            sendState(cart, lit);
        }
    }

    private static FurnaceStateMessage stateMessage(EntityMinecartFurnace cart, boolean lit)
    {
        return new FurnaceStateMessage(cart.getEntityId(), lit, hasDirection(cart) ? direction(cart) : null);
    }

    private static void sendState(EntityMinecartFurnace cart, boolean lit)
    {
        // No network outside a running game (the physics tests)
        if (ChainableMinecarts.network == null)
        {
            return;
        }

        ChainableMinecarts.network.sendToAllTracking(stateMessage(cart, lit), cart);
    }

    /** The fuelled furnace carts on rails that updated this tick; the list is cleared for the next tick. */
    public static List<EntityMinecartFurnace> takeDriving(World world)
    {
        List<EntityMinecartFurnace> driving = DRIVING.remove(world);
        return driving == null ? Collections.emptyList() : driving;
    }

    /** The furnace carts switched off this tick; the list is cleared for the next tick. */
    public static List<EntityMinecartFurnace> takeBraking(World world)
    {
        List<EntityMinecartFurnace> braking = BRAKING.remove(world);
        return braking == null ? Collections.emptyList() : braking;
    }

    private static boolean isRunning(EntityMinecartFurnace cart)
    {
        return getFuel(cart) > 0.0D && !cart.getEntityData().getBoolean(STOPPED_KEY);
    }

    private static boolean hasDirection(EntityMinecartFurnace cart)
    {
        return cart.getEntityData().hasKey(DIR_X_KEY);
    }

    /** Horizontal unit direction a furnace cart drives in (along its rail). */
    static double[] direction(EntityMinecartFurnace cart)
    {
        return new double[] {cart.getEntityData().getDouble(DIR_X_KEY), cart.getEntityData().getDouble(DIR_Z_KEY)};
    }

    /** Notes the placer's facing when a furnace cart item is used on a block (it only places on rails). */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event)
    {
        if (!event.getWorld().isRemote && event.getItemStack().getItem() == Items.FURNACE_MINECART)
        {
            EntityPlayer player = event.getEntityPlayer();
            placingWorld = event.getWorld();
            placingPos = event.getPos();
            placingDirX = -Math.sin(player.rotationYaw * 0.017453292D);
            placingDirZ = Math.cos(player.rotationYaw * 0.017453292D);
        }
    }

    /** Gives a furnace cart that's being placed its placer's facing. */
    @SubscribeEvent
    public static void onEntityJoinWorld(EntityJoinWorldEvent event)
    {
        if (event.getWorld().isRemote)
        {
            return;
        }

        if (event.getEntity() instanceof EntityMinecartFurnace && event.getWorld() == placingWorld
            && new BlockPos(event.getEntity()).equals(placingPos))
        {
            setDirection((EntityMinecartFurnace)event.getEntity(), placingDirX, placingDirZ);
        }

        placingWorld = null;
        placingPos = null;
    }

    /**
     * Right-clicking a furnace cart: coal adds fuel and starts the engine. Without coal, a click puts the brakes on
     * a running engine, bringing the cart (and its train) to a stop, or releases them and starts it again. The
     * direction is fixed at placement; only a cart that never got one (placed by a dispenser, or from before this
     * mod) takes it from the first click, facing away from the player. Runs after the lead rules, which cancel the
     * event when they handle the click.
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event)
    {
        if (!ModConfig.furnace.distanceFurnaceFuel || !(event.getTarget() instanceof EntityMinecartFurnace))
        {
            return;
        }

        EntityMinecartFurnace cart = (EntityMinecartFurnace)event.getTarget();
        EntityPlayer player = event.getEntityPlayer();

        if (!event.getWorld().isRemote)
        {
            ItemStack stack = event.getItemStack();
            double fuel = getFuel(cart);

            if (stack.getItem() == Items.COAL)
            {
                if (fuel + ModConfig.furnace.furnaceBlocksPerCoal <= ModConfig.furnace.furnaceBlocksPerCoal * MAX_COAL)
                {
                    cart.getEntityData().setDouble(FUEL_KEY, fuel + ModConfig.furnace.furnaceBlocksPerCoal);
                    cart.getEntityData().setBoolean(STOPPED_KEY, false);
                    cart.getEntityData().setBoolean(BRAKING_KEY, false);

                    if (!player.capabilities.isCreativeMode)
                    {
                        stack.shrink(1);
                    }
                }
            }
            else if (isRunning(cart))
            {
                // The brakes go on; the train slows to a stop rather than stopping dead
                cart.getEntityData().setBoolean(STOPPED_KEY, true);
                cart.getEntityData().setBoolean(BRAKING_KEY, true);
            }
            else
            {
                cart.getEntityData().setBoolean(STOPPED_KEY, false);
                cart.getEntityData().setBoolean(BRAKING_KEY, false);
            }

            if (!hasDirection(cart))
            {
                setDirection(cart, cart.posX - player.posX, cart.posZ - player.posZ);
                sendState(cart, isRunning(cart));
            }
        }

        // Vanilla's handling would add time-based fuel, so it never runs
        event.setCanceled(true);
        event.setCancellationResult(EnumActionResult.SUCCESS);
    }

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event)
    {
        if (ModConfig.furnace.distanceFurnaceFuel && event.getTarget() instanceof EntityMinecartFurnace && event.getEntityPlayer() instanceof EntityPlayerMP)
        {
            EntityMinecartFurnace cart = (EntityMinecartFurnace)event.getTarget();
            ChainableMinecarts.network.sendTo(stateMessage(cart, isRunning(cart)), (EntityPlayerMP)event.getEntityPlayer());
        }
    }
}
