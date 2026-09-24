package com.andrii.chainableminecarts.event;

import com.andrii.chainableminecarts.ChainableMinecarts;
import com.andrii.chainableminecarts.link.CartLink;
import com.andrii.chainableminecarts.link.LinkManager;
import java.util.List;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumActionResult;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Right-click rules for leads on minecarts. Runs on both sides so the client cancels exactly when the server does
 * (no riding or opening the cart's GUI while handling leads). Only the server changes state, and it quietly does
 * nothing when a lead would be too long.
 */
@Mod.EventBusSubscriber(modid = ChainableMinecarts.MODID)
public final class InteractionHandler
{
    /** Same reach as tying held mobs to a fence. */
    private static final double TRANSFER_RANGE = 7.0D;

    private InteractionHandler()
    {
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event)
    {
        if (!(event.getTarget() instanceof EntityMinecart))
        {
            return;
        }

        EntityMinecart target = (EntityMinecart)event.getTarget();
        EntityPlayer player = event.getEntityPlayer();
        CartLink link = LinkManager.get(target);

        if (link == null)
        {
            return;
        }

        boolean server = !event.getWorld().isRemote;
        ItemStack stack = event.getItemStack();
        List<EntityMinecart> heldCarts = findCartsHeldBy(player, target);

        // 1. Clicking a cart you hold lets go of it
        if (isHeldBy(target, link, player))
        {
            if (server)
            {
                LinkManager.removeHolder(target, player, !player.capabilities.isCreativeMode);
            }
        }
        // 2. Holding other carts: tie them to this one, which becomes their leader
        else if (!heldCarts.isEmpty())
        {
            if (server)
            {
                for (EntityMinecart follower : heldCarts)
                {
                    if (follower.getDistance(target) <= TRANSFER_RANGE)
                    {
                        LinkManager.transferHolder(follower, player, target);
                    }
                }
            }
        }
        // 3. Put a lead on the cart, even if it's already chained to others
        else if (stack.getItem() == Items.LEAD)
        {
            if (server && LinkManager.inReach(target, player) && LinkManager.addHolder(target, player) && !player.capabilities.isCreativeMode)
            {
                stack.shrink(1);
            }
        }
        // 4. Sneak + empty hand un-chains a cart from the carts it's tied to
        else if (player.isSneaking() && stack.isEmpty() && isHeldByCart(target, link))
        {
            if (server)
            {
                LinkManager.removeCartHolders(target, !player.capabilities.isCreativeMode);
            }
        }
        else
        {
            return;
        }

        event.setCanceled(true);
        event.setCancellationResult(EnumActionResult.SUCCESS);
    }

    /** Carts near {@code target} with a lead held by this player. */
    private static List<EntityMinecart> findCartsHeldBy(EntityPlayer player, EntityMinecart target)
    {
        return player.world.getEntitiesWithinAABB(EntityMinecart.class, target.getEntityBoundingBox().grow(TRANSFER_RANGE), cart ->
        {
            CartLink link = cart == null || cart == target ? null : LinkManager.get(cart);
            return link != null && isHeldBy(cart, link, player);
        });
    }

    private static boolean isHeldBy(EntityMinecart cart, CartLink link, Entity holder)
    {
        if (!cart.world.isRemote)
        {
            return link.isHeldBy(holder);
        }

        for (int id : link.clientHolderIds)
        {
            if (id == holder.getEntityId())
            {
                return true;
            }
        }

        return false;
    }

    private static boolean isHeldByCart(EntityMinecart cart, CartLink link)
    {
        if (!cart.world.isRemote)
        {
            return link.isHeldByCart();
        }

        for (int id : link.clientHolderIds)
        {
            if (cart.world.getEntityByID(id) instanceof EntityMinecart)
            {
                return true;
            }
        }

        return false;
    }
}
