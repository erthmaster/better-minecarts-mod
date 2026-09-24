package com.andrii.chainableminecarts;

import com.andrii.chainableminecarts.link.CartLink;
import com.andrii.chainableminecarts.network.FurnaceStateMessage;
import com.andrii.chainableminecarts.network.PushCartMessage;
import com.andrii.chainableminecarts.network.SyncLinkMessage;
import com.andrii.chainableminecarts.physics.HeavyCollisionHandler;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

@Mod(modid = ChainableMinecarts.MODID, name = ChainableMinecarts.NAME, version = ChainableMinecarts.VERSION, acceptedMinecraftVersions = "[1.12.2]")
public class ChainableMinecarts
{
    public static final String MODID = "better_minecarts";
    public static final String NAME = "Better Minecarts";
    public static final String VERSION = "0.1";

    @SidedProxy(clientSide = "com.andrii.chainableminecarts.client.ClientProxy", serverSide = "com.andrii.chainableminecarts.CommonProxy")
    public static CommonProxy proxy;

    public static SimpleNetworkWrapper network;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event)
    {
        CapabilityManager.INSTANCE.register(CartLink.class, new CartLink.Storage(), CartLink::new);

        network = NetworkRegistry.INSTANCE.newSimpleChannel(MODID);
        network.registerMessage(SyncLinkMessage.Handler.class, SyncLinkMessage.class, 0, Side.CLIENT);
        network.registerMessage(PushCartMessage.Handler.class, PushCartMessage.class, 1, Side.SERVER);
        network.registerMessage(FurnaceStateMessage.Handler.class, FurnaceStateMessage.class, 2, Side.CLIENT);
        proxy.preInit();
    }

    @EventHandler
    public void init(FMLInitializationEvent event)
    {
        if (ModConfig.carts.heavyPhysics)
        {
            EntityMinecart.setCollisionHandler(new HeavyCollisionHandler());
        }
    }
}
