package com.andrii.chainableminecarts.network;

import com.andrii.chainableminecarts.physics.SolidCarts;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Client → server: the player is walking into a cart in the given direction. Player movement is simulated on the
 * client, which stops the player at the solid cart, so the server can't see the push from the player's movement.
 */
public class PushCartMessage implements IMessage
{
    public int cartEntityId;
    public float dirX;
    public float dirZ;

    public PushCartMessage()
    {
    }

    public PushCartMessage(int cartEntityId, float dirX, float dirZ)
    {
        this.cartEntityId = cartEntityId;
        this.dirX = dirX;
        this.dirZ = dirZ;
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        this.cartEntityId = buf.readInt();
        this.dirX = buf.readFloat();
        this.dirZ = buf.readFloat();
    }

    @Override
    public void toBytes(ByteBuf buf)
    {
        buf.writeInt(this.cartEntityId);
        buf.writeFloat(this.dirX);
        buf.writeFloat(this.dirZ);
    }

    public static class Handler implements IMessageHandler<PushCartMessage, IMessage>
    {
        @Override
        public IMessage onMessage(PushCartMessage message, MessageContext ctx)
        {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() ->
            {
                Entity cart = player.world.getEntityByID(message.cartEntityId);

                if (cart instanceof EntityMinecart)
                {
                    SolidCarts.pushFromPlayer(player, (EntityMinecart)cart, message.dirX, message.dirZ);
                }
            });
            return null;
        }
    }
}
