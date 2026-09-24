package com.andrii.chainableminecarts.link;

import com.andrii.chainableminecarts.ChainableMinecarts;
import com.andrii.chainableminecarts.ModConfig;
import com.andrii.chainableminecarts.network.SyncLinkMessage;
import com.andrii.chainableminecarts.physics.FurnaceEngine;
import com.andrii.chainableminecarts.physics.TrainPhysics;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import javax.annotation.Nullable;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.EntityTravelToDimensionEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Server-side owner of all minecart leads: tying, untying and breaking them, resolving holders after load, syncing
 * to clients, and feeding the active cart-to-cart leads into {@link TrainPhysics} every tick.
 */
@Mod.EventBusSubscriber(modid = ChainableMinecarts.MODID)
public final class LinkManager
{
    private static final ResourceLocation CAPABILITY_KEY = new ResourceLocation(ChainableMinecarts.MODID, "link");
    /** How long a player holder may be missing (e.g. world still loading) before the lead breaks. */
    private static final int PLAYER_RESOLVE_GRACE_TICKS = 60;

    private static final Map<World, Set<EntityMinecart>> LINKED_CARTS = new WeakHashMap<>();

    private LinkManager()
    {
    }

    @Nullable
    public static CartLink get(Entity entity)
    {
        return entity.hasCapability(CartLink.CAPABILITY, null) ? entity.getCapability(CartLink.CAPABILITY, null) : null;
    }

    /** Whether a lead already runs between the two carts, in either direction. Server only. */
    public static boolean areLinked(EntityMinecart a, EntityMinecart b)
    {
        CartLink linkA = get(a);
        CartLink linkB = get(b);
        return (linkA != null && linkA.isHeldBy(b)) || (linkB != null && linkB.isHeldBy(a));
    }

    /** Whether a lead from {@code holder} to {@code cart} would be short enough not to snap straight away. */
    public static boolean inReach(Entity cart, Entity holder)
    {
        return cart.world == holder.world && cart.getDistance(holder) <= ModConfig.trains.breakDistance;
    }

    /** Ties a lead from {@code holder} (a player or another cart) to {@code cart}. Server only. */
    public static boolean addHolder(EntityMinecart cart, Entity holder)
    {
        CartLink link = get(cart);

        if (link == null || holder == cart || link.isHeldBy(holder)
            || (holder instanceof EntityMinecart && areLinked(cart, (EntityMinecart)holder)))
        {
            return false;
        }

        CartLink.Holder entry = new CartLink.Holder(holder.getUniqueID(), holder instanceof EntityPlayer);
        entry.entity = holder;
        link.holders.add(entry);
        track(cart);
        sync(cart, link);
        return true;
    }

    /** Moves the lead that {@code player} holds on {@code cart} over to {@code newHolder}. Server only. */
    public static boolean transferHolder(EntityMinecart cart, EntityPlayer player, EntityMinecart newHolder)
    {
        CartLink link = get(cart);
        CartLink.Holder entry = link == null ? null : link.find(player.getUniqueID());

        if (entry == null || newHolder == cart || areLinked(cart, newHolder) || !inReach(cart, newHolder))
        {
            return false;
        }

        link.holders.remove(entry);
        return addHolder(cart, newHolder);
    }

    /** Unties {@code holder}'s lead from {@code cart}, optionally dropping the lead item. Server only. */
    public static void removeHolder(EntityMinecart cart, Entity holder, boolean dropLead)
    {
        CartLink link = get(cart);
        CartLink.Holder entry = link == null ? null : link.find(holder.getUniqueID());

        if (entry != null)
        {
            link.holders.remove(entry);
            dropLeads(cart, dropLead ? 1 : 0);
            sync(cart, link);
        }
    }

    /** Unties every lead that ties {@code cart} to other carts. Server only. */
    public static void removeCartHolders(EntityMinecart cart, boolean dropLeads)
    {
        CartLink link = get(cart);

        if (link == null)
        {
            return;
        }

        int before = link.holders.size();
        link.holders.removeIf(holder -> !holder.isPlayer);
        dropLeads(cart, dropLeads ? before - link.holders.size() : 0);
        sync(cart, link);
    }

    /** A lead broke on its own (too far, holder gone, cart destroyed). */
    private static void breakLeads(EntityMinecart cart, int count)
    {
        dropLeads(cart, ModConfig.trains.dropLeadOnBreak ? count : 0);
    }

    private static void dropLeads(EntityMinecart cart, int count)
    {
        if (count > 0)
        {
            cart.entityDropItem(new ItemStack(Items.LEAD, count), 0.5F);
        }
    }

    private static void track(EntityMinecart cart)
    {
        LINKED_CARTS.computeIfAbsent(cart.world, w -> new LinkedHashSet<>()).add(cart);
    }

    /** Sends the entity ids of the holders that are currently loaded, if they changed. */
    private static void sync(EntityMinecart cart, CartLink link)
    {
        int[] ids = link.holders.stream()
            .filter(holder -> holder.entity != null && !holder.entity.isDead)
            .mapToInt(holder -> holder.entity.getEntityId())
            .toArray();

        if (!Arrays.equals(ids, link.lastSyncedHolderIds))
        {
            link.lastSyncedHolderIds = ids;
            ChainableMinecarts.network.sendToAllTracking(new SyncLinkMessage(cart.getEntityId(), ids), cart);
        }
    }

    /** Finds the holder entity, or null if it isn't loaded right now. A dead holder is returned as-is. */
    @Nullable
    private static Entity resolve(WorldServer world, CartLink.Holder holder)
    {
        Entity entity = holder.entity;

        if (entity != null)
        {
            if (entity.isDead || entity instanceof EntityPlayer || (entity.isAddedToWorld() && entity.world == world))
            {
                return entity;
            }

            // Holder cart was unloaded with its chunk; look it up again once it's back
            holder.entity = null;
        }

        if (holder.isPlayer)
        {
            entity = world.getPlayerEntityByUUID(holder.id);
        }
        else
        {
            entity = world.getEntityFromUuid(holder.id);

            if (!(entity instanceof EntityMinecart))
            {
                entity = null;
            }
        }

        holder.entity = entity;
        return entity;
    }

    private static void tick(WorldServer world)
    {
        Set<EntityMinecart> carts = LINKED_CARTS.getOrDefault(world, Collections.emptySet());
        List<EntityMinecart[]> cartLinks = new ArrayList<>();

        for (EntityMinecart cart : new ArrayList<>(carts))
        {
            CartLink link = get(cart);

            if (link == null || !link.hasHolders())
            {
                carts.remove(cart);
                continue;
            }

            if (cart.isDead)
            {
                // Destroyed (unloading doesn't kill entities), so its leads fall where the cart was
                breakLeads(cart, link.holders.size());
                link.holders.clear();
                carts.remove(cart);
                continue;
            }

            if (!cart.isAddedToWorld())
            {
                // Unloaded with its chunk; EntityJoinWorldEvent tracks it again on reload
                carts.remove(cart);
                continue;
            }

            int broken = 0;

            for (Iterator<CartLink.Holder> it = link.holders.iterator(); it.hasNext(); )
            {
                CartLink.Holder holder = it.next();
                Entity entity = resolve(world, holder);

                if (entity == null)
                {
                    // A missing cart holder is just unloaded, so wait for it; a missing player has left
                    if (holder.isPlayer && ++holder.unresolvedTicks > PLAYER_RESOLVE_GRACE_TICKS)
                    {
                        it.remove();
                        ++broken;
                    }

                    continue;
                }

                holder.unresolvedTicks = 0;

                if (entity.isDead || !inReach(cart, entity))
                {
                    it.remove();
                    ++broken;
                }
                else if (entity instanceof EntityMinecart)
                {
                    cartLinks.add(new EntityMinecart[] {cart, (EntityMinecart)entity});
                }
            }

            breakLeads(cart, broken);
            sync(cart, link);
        }

        // Fuelled furnace carts drive their train, or themselves if they're not chained to anything
        TrainPhysics.solve(cartLinks, FurnaceEngine.takeDriving(world), FurnaceEngine.takeBraking(world));
    }

    @SubscribeEvent
    public static void onAttachCapabilities(AttachCapabilitiesEvent<Entity> event)
    {
        if (event.getObject() instanceof EntityMinecart)
        {
            event.addCapability(CAPABILITY_KEY, new CartLink.Provider());
        }
    }

    @SubscribeEvent
    public static void onEntityJoinWorld(EntityJoinWorldEvent event)
    {
        if (!event.getWorld().isRemote && event.getEntity() instanceof EntityMinecart)
        {
            CartLink link = get(event.getEntity());

            if (link != null && link.hasHolders())
            {
                link.lastSyncedHolderIds = new int[0];
                track((EntityMinecart)event.getEntity());
            }
        }
    }

    @SubscribeEvent
    public static void onTravelToDimension(EntityTravelToDimensionEvent event)
    {
        if (!event.getEntity().world.isRemote && event.getEntity() instanceof EntityMinecart)
        {
            EntityMinecart cart = (EntityMinecart)event.getEntity();
            CartLink link = get(cart);

            if (link != null && link.hasHolders())
            {
                breakLeads(cart, link.holders.size());
                link.holders.clear();
                sync(cart, link);
            }
        }
    }

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event)
    {
        if (event.getTarget() instanceof EntityMinecart && event.getEntityPlayer() instanceof EntityPlayerMP)
        {
            CartLink link = get(event.getTarget());

            if (link != null && link.lastSyncedHolderIds.length > 0)
            {
                ChainableMinecarts.network.sendTo(new SyncLinkMessage(event.getTarget().getEntityId(), link.lastSyncedHolderIds), (EntityPlayerMP)event.getEntityPlayer());
            }
        }
    }

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent event)
    {
        if (event.phase == TickEvent.Phase.END && event.world instanceof WorldServer)
        {
            tick((WorldServer)event.world);
        }
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event)
    {
        LINKED_CARTS.remove(event.getWorld());
    }
}
