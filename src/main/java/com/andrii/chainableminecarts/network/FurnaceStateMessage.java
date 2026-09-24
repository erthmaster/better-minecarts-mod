package com.andrii.chainableminecarts.network;

import com.andrii.chainableminecarts.ChainableMinecarts;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Server → client: whether a furnace cart's engine is running, and which way it drives. Vanilla clients work out
 * "lit" from their own copy of the fuel, which no longer exists; the direction lets the client draw the furnace
 * facing the way it drives. The client keeps the direction along the track itself afterwards.
 */
public class FurnaceStateMessage implements IMessage
{
    public int cartEntityId;
    public boolean lit;
    /** Horizontal unit driving direction, or 0, 0 if the cart has none yet. */
    public float dirX;
    public float dirZ;

    public FurnaceStateMessage()
    {
    }

    public FurnaceStateMessage(int cartEntityId, boolean lit, double[] direction)
    {
        this.cartEntityId = cartEntityId;
        this.lit = lit;
        this.dirX = direction == null ? 0.0F : (float)direction[0];
        this.dirZ = direction == null ? 0.0F : (float)direction[1];
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        this.cartEntityId = buf.readInt();
        this.lit = buf.readBoolean();
        this.dirX = buf.readFloat();
        this.dirZ = buf.readFloat();
    }

    @Override
    public void toBytes(ByteBuf buf)
    {
        buf.writeInt(this.cartEntityId);
        buf.writeBoolean(this.lit);
        buf.writeFloat(this.dirX);
        buf.writeFloat(this.dirZ);
    }

    public static class Handler implements IMessageHandler<FurnaceStateMessage, IMessage>
    {
        @Override
        public IMessage onMessage(FurnaceStateMessage message, MessageContext ctx)
        {
            ChainableMinecarts.proxy.handleFurnaceState(message);
            return null;
        }
    }
}
