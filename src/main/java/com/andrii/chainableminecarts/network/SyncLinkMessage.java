package com.andrii.chainableminecarts.network;

import com.andrii.chainableminecarts.ChainableMinecarts;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** Server → client: the entity ids of everything holding a lead tied to a minecart. */
public class SyncLinkMessage implements IMessage
{
    public int cartEntityId;
    public int[] holderEntityIds;

    public SyncLinkMessage()
    {
    }

    public SyncLinkMessage(int cartEntityId, int[] holderEntityIds)
    {
        this.cartEntityId = cartEntityId;
        this.holderEntityIds = holderEntityIds;
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        this.cartEntityId = buf.readInt();
        this.holderEntityIds = new int[buf.readUnsignedByte()];

        for (int i = 0; i < this.holderEntityIds.length; ++i)
        {
            this.holderEntityIds[i] = buf.readInt();
        }
    }

    @Override
    public void toBytes(ByteBuf buf)
    {
        int count = Math.min(this.holderEntityIds.length, 255);
        buf.writeInt(this.cartEntityId);
        buf.writeByte(count);

        for (int i = 0; i < count; ++i)
        {
            buf.writeInt(this.holderEntityIds[i]);
        }
    }

    public static class Handler implements IMessageHandler<SyncLinkMessage, IMessage>
    {
        @Override
        public IMessage onMessage(SyncLinkMessage message, MessageContext ctx)
        {
            ChainableMinecarts.proxy.handleSync(message);
            return null;
        }
    }
}
