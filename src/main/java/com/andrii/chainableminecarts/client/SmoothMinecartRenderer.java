package com.andrii.chainableminecarts.client;

import com.andrii.chainableminecarts.ModConfig;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RenderMinecart;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Vanilla RenderMinecart, except on rails. Vanilla ignores the cart's position and yaw there and recalculates both
 * from the rail block, so a cart on a curve sits on the block's diagonal and snaps between 0, 45 and 90 degrees at
 * block edges. This draws the cart where {@link CurveSmoothing} put it (on the curve), facing its smoothed yaw.
 * Heights and slope tilt still come from the rail, as in vanilla.
 */
public class SmoothMinecartRenderer<T extends EntityMinecart> extends RenderMinecart<T>
{
    public SmoothMinecartRenderer(RenderManager renderManager)
    {
        super(renderManager);
    }

    @Override
    public void doRender(T entity, double x, double y, double z, float entityYaw, float partialTicks)
    {
        if (!ModConfig.curves.smoothCurves)
        {
            super.doRender(entity, x, y, z, entityYaw, partialTicks);
            return;
        }

        GlStateManager.pushMatrix();
        this.bindEntityTexture(entity);
        // Vanilla's tiny per-cart offset that stops carts in the same spot from z-fighting
        long seed = (long)entity.getEntityId() * 493286711L;
        seed = seed * seed * 4392167121L + seed * 98761L;
        float jitterX = (((float)(seed >> 16 & 7L) + 0.5F) / 8.0F - 0.5F) * 0.004F;
        float jitterY = (((float)(seed >> 20 & 7L) + 0.5F) / 8.0F - 0.5F) * 0.004F;
        float jitterZ = (((float)(seed >> 24 & 7L) + 0.5F) / 8.0F - 0.5F) * 0.004F;
        GlStateManager.translate(jitterX, jitterY, jitterZ);

        double posX = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks;
        double posY = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks;
        double posZ = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks;
        float pitch = entity.prevRotationPitch + (entity.rotationPitch - entity.prevRotationPitch) * partialTicks;
        Vec3d onRail = entity.getPos(posX, posY, posZ);

        if (onRail != null)
        {
            Vec3d ahead = entity.getPosOffset(posX, posY, posZ, 0.30000001192092896D);
            Vec3d behind = entity.getPosOffset(posX, posY, posZ, -0.30000001192092896D);

            if (ahead == null)
            {
                ahead = onRail;
            }

            if (behind == null)
            {
                behind = onRail;
            }

            // Height from the rail as vanilla does; x and z stay where the cart is
            y += (ahead.y + behind.y) / 2.0D - posY;
            Vec3d slope = behind.subtract(ahead);

            if (slope.lengthVector() != 0.0D)
            {
                slope = slope.normalize();
                pitch = (float)(Math.atan(slope.y) * 73.0D);
                double yawRadians = entityYaw * 0.017453292D;

                // Vanilla's pitch goes with its own facing along the rail; tilt the other way if we face the other way
                if (slope.x * Math.cos(yawRadians) + slope.z * Math.sin(yawRadians) < 0.0D)
                {
                    pitch = -pitch;
                }
            }
        }

        GlStateManager.translate((float)x, (float)y + 0.375F, (float)z);
        GlStateManager.rotate(180.0F - entityYaw, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(-pitch, 0.0F, 0.0F, 1.0F);
        float roll = (float)entity.getRollingAmplitude() - partialTicks;
        float damage = Math.max(entity.getDamage() - partialTicks, 0.0F);

        if (roll > 0.0F)
        {
            GlStateManager.rotate(MathHelper.sin(roll) * roll * damage / 10.0F * (float)entity.getRollingDirection(), 1.0F, 0.0F, 0.0F);
        }

        int tileOffset = entity.getDisplayTileOffset();

        if (this.renderOutlines)
        {
            GlStateManager.enableColorMaterial();
            GlStateManager.enableOutlineMode(this.getTeamColor(entity));
        }

        IBlockState tile = entity.getDisplayTile();

        if (tile.getRenderType() != EnumBlockRenderType.INVISIBLE)
        {
            GlStateManager.pushMatrix();
            this.bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
            GlStateManager.scale(0.75F, 0.75F, 0.75F);
            GlStateManager.translate(-0.5F, (float)(tileOffset - 8) / 16.0F, 0.5F);
            this.renderCartContents(entity, partialTicks, tile);
            GlStateManager.popMatrix();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            this.bindEntityTexture(entity);
        }

        GlStateManager.scale(-1.0F, -1.0F, 1.0F);
        this.modelMinecart.render(entity, 0.0F, 0.0F, -0.1F, 0.0F, 0.0F, 0.0625F);
        GlStateManager.popMatrix();

        if (this.renderOutlines)
        {
            GlStateManager.disableOutlineMode();
            GlStateManager.disableColorMaterial();
        }

        // What Render.doRender does (RenderMinecart's super call)
        if (!this.renderOutlines)
        {
            this.renderName(entity, x, y, z);
        }
    }
}
