package com.andrii.chainableminecarts.client;

import com.andrii.chainableminecarts.ChainableMinecarts;
import com.andrii.chainableminecarts.ModConfig;
import com.andrii.chainableminecarts.physics.FurnaceEngine;
import java.util.Map;
import java.util.WeakHashMap;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityMinecartFurnace;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;

/**
 * Client-side furnace cart state from the server: whether the engine runs (so the furnace looks lit and smokes; the
 * vanilla client decides that from its own fuel counter every tick, so the counter is kept set here), and which way
 * it drives (so {@link CurveSmoothing} can turn the furnace's front that way).
 */
@Mod.EventBusSubscriber(modid = ChainableMinecarts.MODID, value = Side.CLIENT)
public final class FurnaceClient
{
    private static final Map<Entity, Boolean> LIT = new WeakHashMap<>();
    private static final Map<Entity, double[]> DIRECTION = new WeakHashMap<>();

    private FurnaceClient()
    {
    }

    static void setState(Entity cart, boolean lit, double dirX, double dirZ)
    {
        LIT.put(cart, lit);

        if (dirX != 0.0D || dirZ != 0.0D)
        {
            DIRECTION.put(cart, new double[] {dirX, dirZ});
        }
    }

    /** The cart's driving direction, or null if not known (yet). The array may be updated in place. */
    @Nullable
    static double[] direction(Entity cart)
    {
        return DIRECTION.get(cart);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        Minecraft mc = Minecraft.getMinecraft();

        if (event.phase != TickEvent.Phase.END || mc.world == null || !ModConfig.furnace.distanceFurnaceFuel)
        {
            return;
        }

        for (Entity entity : mc.world.loadedEntityList)
        {
            if (entity instanceof EntityMinecartFurnace)
            {
                FurnaceEngine.setVanillaLit((EntityMinecartFurnace)entity, Boolean.TRUE.equals(LIT.get(entity)));
            }
        }
    }
}
