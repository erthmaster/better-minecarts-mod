package com.andrii.chainableminecarts.physics;

import com.andrii.chainableminecarts.ChainableMinecarts;
import com.andrii.chainableminecarts.ModConfig;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.minecraft.block.BlockRailBase;
import net.minecraft.block.BlockRailPowered;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.item.EntityMinecartContainer;
import net.minecraft.entity.item.EntityMinecartFurnace;
import net.minecraft.inventory.Container;
import net.minecraft.util.ResourceLocation;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.event.entity.minecart.MinecartUpdateEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Rolling resistance for carts on rails, and the same drag for every cart.
 * <p>
 * Vanilla drag is a percentage of the speed, so carts slow down forever without ever stopping; a constant
 * deceleration plus a rest threshold brings them to a stop.
 * <p>
 * Vanilla also moves carts differently depending on what they are and who's in them, and all of it is taken back
 * out here, so every cart rolls exactly like an empty one:
 * <ul>
 * <li>a cart with someone in it is dragged far less (0.3% per tick against 4%), and moved only three quarters of
 * the distance its speed says, which makes a ridden cart fall behind the rest of its train until the carts behind
 * bump it along;</li>
 * <li>chest and hopper carts are dragged less too, 0.5% to 2% per tick depending on how full they are, so they roll
 * several times as far;</li>
 * <li>a furnace cart that isn't driving is dragged an extra 2% per tick.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = ChainableMinecarts.MODID)
public final class CartFriction
{
    /** The extra drag per tick vanilla puts on a furnace cart that isn't driving. */
    private static final double FURNACE_DRAG = 0.9800000190734863D;
    /** Vanilla's drag per tick for a cart with a passenger, and for an empty one. */
    private static final double RIDDEN_DRAG = 0.996999979019165D;
    private static final double EMPTY_DRAG = 0.9599999785423279D;
    /** How much of its speed vanilla actually moves a cart with a passenger. */
    private static final double RIDDEN_MOVE = 0.75D;
    /** A chest or hopper cart's loot table, still to be rolled: its only ResourceLocation field. */
    private static final Field LOOT_TABLE = findLootTableField();

    private CartFriction()
    {
    }

    private static Field findLootTableField()
    {
        for (Field field : EntityMinecartContainer.class.getDeclaredFields())
        {
            if (field.getType() == ResourceLocation.class && !Modifier.isStatic(field.getModifiers()))
            {
                field.setAccessible(true);
                return field;
            }
        }

        throw new IllegalStateException("EntityMinecartContainer has no loot table field");
    }

    /** The drag vanilla put on this cart this tick, apart from a furnace cart's extra. */
    private static double vanillaDrag(EntityMinecart cart)
    {
        if (cart instanceof EntityMinecartContainer)
        {
            // As EntityMinecartContainer.applyDrag works it out, float arithmetic and all
            float drag = 0.98F;

            if (!hasLootTable((EntityMinecartContainer)cart))
            {
                drag += (float)(15 - Container.calcRedstoneFromInventory((EntityMinecartContainer)cart)) * 0.001F;
            }

            return drag;
        }

        return cart.isBeingRidden() ? RIDDEN_DRAG : EMPTY_DRAG;
    }

    private static boolean hasLootTable(EntityMinecartContainer cart)
    {
        try
        {
            return LOOT_TABLE.get(cart) != null;
        }
        catch (IllegalAccessException e)
        {
            throw new IllegalStateException(e);
        }
    }

    /** Fired on the server at the end of each cart's update, after it has moved. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onMinecartUpdate(MinecartUpdateEvent event)
    {
        EntityMinecart cart = event.getMinecart();

        if (!ModConfig.carts.heavyPhysics || cart.world.isRemote)
        {
            return;
        }

        // Running furnace carts are engines; leave their speed alone
        if (cart instanceof EntityMinecartFurnace)
        {
            EntityMinecartFurnace furnace = (EntityMinecartFurnace)cart;

            if (furnace.pushX * furnace.pushX + furnace.pushZ * furnace.pushZ > 0.0D)
            {
                return;
            }
        }

        IBlockState rail = cart.world.getBlockState(event.getPos());

        if (!BlockRailBase.isRailBlock(rail))
        {
            return;
        }

        // An idle furnace cart is dragged harder than any other cart; take that back out so it rolls the same
        if (cart instanceof EntityMinecartFurnace)
        {
            cart.motionX /= FURNACE_DRAG;
            cart.motionZ /= FURNACE_DRAG;
        }

        double drag = vanillaDrag(cart);

        if (cart.isBeingRidden())
        {
            // Vanilla moved this cart at three quarters of its speed; make up the rest of the distance. Drag has
            // been applied since the move, so the speed it moved at is taken back out of the current one.
            int fromX = MathHelper.floor(cart.posX);
            int fromZ = MathHelper.floor(cart.posZ);
            double cap = CartBody.railSpeedCap(cart);
            cart.move(MoverType.SELF, missedMove(cart.motionX / drag, cap), 0.0D, missedMove(cart.motionZ / drag, cap));
            faceNewBlock(cart, fromX, fromZ);
        }

        // Swap whatever drag vanilla used for an empty cart's
        cart.motionX *= EMPTY_DRAG / drag;
        cart.motionZ *= EMPTY_DRAG / drag;

        if (rail.getBlock() == Blocks.GOLDEN_RAIL && rail.getValue(BlockRailPowered.POWERED))
        {
            return;
        }

        double speed = Math.sqrt(cart.motionX * cart.motionX + cart.motionZ * cart.motionZ);

        if (speed <= 0.0D)
        {
            return;
        }

        double newSpeed = speed - ModConfig.carts.rollingResistance;
        double scale = newSpeed < ModConfig.carts.restSpeed ? 0.0D : newSpeed / speed;
        cart.motionX *= scale;
        cart.motionZ *= scale;
    }

    /**
     * Points the cart at the block it has just moved into, as vanilla does whenever a cart crosses into one. Vanilla
     * did that before this extra move, so a cart carried over the edge by it would arrive still pointing along the
     * rail it came from. Where the next rail is a turn at right angles to that, the rail has no way to tell which
     * way round the cart is going and can send it back the way it came.
     */
    private static void faceNewBlock(EntityMinecart cart, int fromX, int fromZ)
    {
        int toX = MathHelper.floor(cart.posX);
        int toZ = MathHelper.floor(cart.posZ);

        if (toX != fromX || toZ != fromZ)
        {
            double speed = Math.sqrt(cart.motionX * cart.motionX + cart.motionZ * cart.motionZ);
            cart.motionX = speed * (toX - fromX);
            cart.motionZ = speed * (toZ - fromZ);
        }
    }

    /** The distance along one axis that vanilla left out, both its move and the full one being capped as it caps them. */
    private static double missedMove(double speed, double cap)
    {
        return MathHelper.clamp(speed, -cap, cap) - MathHelper.clamp(speed * RIDDEN_MOVE, -cap, cap);
    }
}
