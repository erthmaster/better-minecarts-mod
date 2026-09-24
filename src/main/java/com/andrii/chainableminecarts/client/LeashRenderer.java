package com.andrii.chainableminecarts.client;

import com.andrii.chainableminecarts.ChainableMinecarts;
import com.andrii.chainableminecarts.link.CartLink;
import com.andrii.chainableminecarts.link.LinkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.lwjgl.opengl.GL11;

/** Draws minecart leads. Vanilla only draws leads for EntityLiving, so carts need their own rope. */
@Mod.EventBusSubscriber(modid = ChainableMinecarts.MODID, value = Side.CLIENT)
public final class LeashRenderer
{
    private static final int SEGMENTS = 24;
    private static final double THICKNESS = 0.025D;
    /** Height of the rope's attachment point above the cart's base. */
    private static final double CART_ATTACH_HEIGHT = 0.5D;

    private LeashRenderer()
    {
    }

    @SubscribeEvent
    public static void onRenderWorldLast(RenderWorldLastEvent event)
    {
        Minecraft mc = Minecraft.getMinecraft();
        Entity viewer = mc.getRenderViewEntity();

        if (mc.world == null || viewer == null)
        {
            return;
        }

        float partialTicks = event.getPartialTicks();
        Vec3d camera = interpolatedPos(viewer, partialTicks);
        boolean glSetUp = false;

        for (Entity entity : mc.world.loadedEntityList)
        {
            if (!(entity instanceof EntityMinecart))
            {
                continue;
            }

            CartLink link = LinkManager.get(entity);

            if (link == null)
            {
                continue;
            }

            for (int holderId : link.clientHolderIds)
            {
                Entity holder = mc.world.getEntityByID(holderId);

                if (holder == null)
                {
                    continue;
                }

                if (!glSetUp)
                {
                    GlStateManager.disableTexture2D();
                    GlStateManager.disableLighting();
                    GlStateManager.disableCull();
                    glSetUp = true;
                }

                drawLead(entity, holder, partialTicks, camera);
            }
        }

        if (glSetUp)
        {
            GlStateManager.enableCull();
            GlStateManager.enableLighting();
            GlStateManager.enableTexture2D();
        }
    }

    private static void drawLead(Entity cart, Entity holder, float partialTicks, Vec3d camera)
    {
        Vec3d cartPos = interpolatedPos(cart, partialTicks).addVector(0.0D, CART_ATTACH_HEIGHT, 0.0D);
        Vec3d holderPos;

        if (holder instanceof EntityMinecart)
        {
            holderPos = interpolatedPos(holder, partialTicks).addVector(0.0D, CART_ATTACH_HEIGHT, 0.0D);
            // Attach at the facing ends of the two carts instead of their centres
            Vec3d between = new Vec3d(holderPos.x - cartPos.x, 0.0D, holderPos.z - cartPos.z);

            if (between.lengthSquared() > 1.0E-6D)
            {
                between = between.normalize();
                cartPos = cartPos.add(between.scale(cart.width * 0.45D));
                holderPos = holderPos.subtract(between.scale(holder.width * 0.45D));
            }
        }
        else
        {
            holderPos = handPos(holder, partialTicks);
        }

        drawRope(cartPos.subtract(camera), holderPos.subtract(camera));
    }

    private static Vec3d interpolatedPos(Entity entity, float partialTicks)
    {
        return new Vec3d(
            entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks,
            entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks,
            entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks);
    }

    /** Where the lead leaves a holding player's hand; same maths as vanilla RenderLiving.renderLeash. */
    private static Vec3d handPos(Entity holder, float partialTicks)
    {
        double yaw = lerp(holder.prevRotationYaw, holder.rotationYaw, partialTicks * 0.5F) * 0.017453292D;
        double pitch = lerp(holder.prevRotationPitch, holder.rotationPitch, partialTicks * 0.5F) * 0.017453292D;
        double cosYaw = Math.cos(yaw);
        double sinYaw = Math.sin(yaw);
        double sinPitch = Math.sin(pitch);
        double cosPitch = Math.cos(pitch);
        double eyeHeight = holder instanceof EntityPlayer ? holder.getEyeHeight() : holder.height;
        Vec3d pos = interpolatedPos(holder, partialTicks);

        return new Vec3d(
            pos.x - cosYaw * 0.7D - sinYaw * 0.5D * cosPitch,
            pos.y + eyeHeight * 0.7D - sinPitch * 0.5D - 0.25D,
            pos.z - sinYaw * 0.7D + cosYaw * 0.5D * cosPitch);
    }

    private static double lerp(double start, double end, double pct)
    {
        return start + (end - start) * pct;
    }

    /** Two crossed strips with a slight sag, coloured like the vanilla lead. */
    private static void drawRope(Vec3d from, Vec3d to)
    {
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double sag = Math.min(0.6D, 0.06D * Math.sqrt(dx * dx + dy * dy + dz * dz));

        for (int strip = 0; strip < 2; ++strip)
        {
            buffer.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);

            for (int i = 0; i <= SEGMENTS; ++i)
            {
                float shade = i % 2 == 0 ? 0.7F : 1.0F;
                float r = 0.5F * shade;
                float g = 0.4F * shade;
                float b = 0.3F * shade;
                double t = (double)i / SEGMENTS;
                double x = from.x + dx * t;
                double y = from.y + dy * t - sag * 4.0D * t * (1.0D - t);
                double z = from.z + dz * t;

                if (strip == 0)
                {
                    buffer.pos(x, y, z).color(r, g, b, 1.0F).endVertex();
                    buffer.pos(x + THICKNESS, y + THICKNESS, z).color(r, g, b, 1.0F).endVertex();
                }
                else
                {
                    buffer.pos(x, y + THICKNESS, z).color(r, g, b, 1.0F).endVertex();
                    buffer.pos(x + THICKNESS, y, z + THICKNESS).color(r, g, b, 1.0F).endVertex();
                }
            }

            tessellator.draw();
        }
    }
}
