package com.andrii.chainableminecarts.client;

import com.andrii.chainableminecarts.CommonProxy;
import com.andrii.chainableminecarts.link.CartLink;
import com.andrii.chainableminecarts.link.LinkManager;
import com.andrii.chainableminecarts.network.FurnaceStateMessage;
import com.andrii.chainableminecarts.network.SyncLinkMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.item.EntityMinecartMobSpawner;
import net.minecraft.entity.item.EntityMinecartTNT;
import net.minecraftforge.fml.client.registry.RenderingRegistry;

public class ClientProxy extends CommonProxy
{
    @Override
    public void preInit()
    {
        // Replaces all three vanilla minecart renderers; other cart types look theirs up through EntityMinecart
        RenderingRegistry.registerEntityRenderingHandler(EntityMinecart.class, SmoothMinecartRenderer::new);
        RenderingRegistry.registerEntityRenderingHandler(EntityMinecartMobSpawner.class, SmoothMinecartRenderer::new);
        RenderingRegistry.registerEntityRenderingHandler(EntityMinecartTNT.class, SmoothTntMinecartRenderer::new);
    }

    @Override
    public void handleSync(SyncLinkMessage message)
    {
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() ->
        {
            if (mc.world == null)
            {
                return;
            }

            Entity cart = mc.world.getEntityByID(message.cartEntityId);
            CartLink link = cart == null ? null : LinkManager.get(cart);

            if (link != null)
            {
                link.clientHolderIds = message.holderEntityIds;
            }
        });
    }

    @Override
    public void handleFurnaceState(FurnaceStateMessage message)
    {
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() ->
        {
            Entity cart = mc.world == null ? null : mc.world.getEntityByID(message.cartEntityId);

            if (cart != null)
            {
                FurnaceClient.setState(cart, message.lit, message.dirX, message.dirZ);
            }
        });
    }
}
