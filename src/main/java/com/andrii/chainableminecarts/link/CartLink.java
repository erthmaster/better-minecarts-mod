package com.andrii.chainableminecarts.link;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.Constants;

/**
 * Per-minecart lead state: the players and carts holding a lead tied to this cart. A lead between two carts is
 * stored on only one of them.
 */
public class CartLink
{
    @CapabilityInject(CartLink.class)
    public static Capability<CartLink> CAPABILITY = null;

    /** One lead tied to this cart. */
    static class Holder
    {
        final UUID id;
        final boolean isPlayer;
        @Nullable
        Entity entity;
        int unresolvedTicks;

        Holder(UUID id, boolean isPlayer)
        {
            this.id = id;
            this.isPlayer = isPlayer;
        }
    }

    // Server state (holder ids are saved)
    final List<Holder> holders = new ArrayList<>();
    int[] lastSyncedHolderIds = new int[0];

    // Client state, filled by SyncLinkMessage
    public int[] clientHolderIds = new int[0];

    public boolean hasHolders()
    {
        return !this.holders.isEmpty();
    }

    public boolean isHeldBy(Entity entity)
    {
        return this.find(entity.getUniqueID()) != null;
    }

    public boolean isHeldByCart()
    {
        for (Holder holder : this.holders)
        {
            if (!holder.isPlayer)
            {
                return true;
            }
        }

        return false;
    }

    @Nullable
    Holder find(UUID id)
    {
        for (Holder holder : this.holders)
        {
            if (holder.id.equals(id))
            {
                return holder;
            }
        }

        return null;
    }

    NBTTagCompound writeNBT()
    {
        NBTTagList list = new NBTTagList();

        for (Holder holder : this.holders)
        {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setUniqueId("Id", holder.id);
            tag.setBoolean("IsPlayer", holder.isPlayer);
            list.appendTag(tag);
        }

        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("Holders", list);
        return tag;
    }

    void readNBT(NBTTagCompound tag)
    {
        this.holders.clear();
        NBTTagList list = tag.getTagList("Holders", Constants.NBT.TAG_COMPOUND);

        for (int i = 0; i < list.tagCount(); ++i)
        {
            NBTTagCompound holderTag = list.getCompoundTagAt(i);

            if (holderTag.hasUniqueId("Id"))
            {
                this.holders.add(new Holder(holderTag.getUniqueId("Id"), holderTag.getBoolean("IsPlayer")));
            }
        }
    }

    public static class Storage implements Capability.IStorage<CartLink>
    {
        @Override
        public NBTBase writeNBT(Capability<CartLink> capability, CartLink instance, EnumFacing side)
        {
            return instance.writeNBT();
        }

        @Override
        public void readNBT(Capability<CartLink> capability, CartLink instance, EnumFacing side, NBTBase nbt)
        {
            instance.readNBT((NBTTagCompound)nbt);
        }
    }

    public static class Provider implements ICapabilitySerializable<NBTTagCompound>
    {
        private final CartLink link = new CartLink();

        @Override
        public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing facing)
        {
            return capability == CAPABILITY;
        }

        @Override
        @Nullable
        public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing facing)
        {
            return capability == CAPABILITY ? CAPABILITY.cast(this.link) : null;
        }

        @Override
        public NBTTagCompound serializeNBT()
        {
            return this.link.writeNBT();
        }

        @Override
        public void deserializeNBT(NBTTagCompound nbt)
        {
            this.link.readNBT(nbt);
        }
    }
}
