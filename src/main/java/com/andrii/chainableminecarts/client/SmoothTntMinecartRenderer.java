package com.andrii.chainableminecarts.client;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.item.EntityMinecartTNT;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.MathHelper;

/** {@link SmoothMinecartRenderer} with vanilla RenderTntMinecart's swelling, flashing TNT. */
public class SmoothTntMinecartRenderer extends SmoothMinecartRenderer<EntityMinecartTNT>
{
    public SmoothTntMinecartRenderer(RenderManager renderManager)
    {
        super(renderManager);
    }

    @Override
    protected void renderCartContents(EntityMinecartTNT cart, float partialTicks, IBlockState tile)
    {
        int fuse = cart.getFuseTicks();

        if (fuse > -1 && (float)fuse - partialTicks + 1.0F < 10.0F)
        {
            float swell = MathHelper.clamp(1.0F - ((float)fuse - partialTicks + 1.0F) / 10.0F, 0.0F, 1.0F);
            swell = swell * swell;
            swell = swell * swell;
            float scale = 1.0F + swell * 0.3F;
            GlStateManager.scale(scale, scale, scale);
        }

        super.renderCartContents(cart, partialTicks, tile);

        if (fuse > -1 && fuse / 5 % 2 == 0)
        {
            GlStateManager.disableTexture2D();
            GlStateManager.disableLighting();
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.DST_ALPHA);
            GlStateManager.color(1.0F, 1.0F, 1.0F, (1.0F - ((float)fuse - partialTicks + 1.0F) / 100.0F) * 0.8F);
            GlStateManager.pushMatrix();
            Minecraft.getMinecraft().getBlockRendererDispatcher().renderBlockBrightness(Blocks.TNT.getDefaultState(), 1.0F);
            GlStateManager.popMatrix();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.disableBlend();
            GlStateManager.enableLighting();
            GlStateManager.enableTexture2D();
        }
    }
}
