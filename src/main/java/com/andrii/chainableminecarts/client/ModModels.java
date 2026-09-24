package com.andrii.chainableminecarts.client;

import com.andrii.chainableminecarts.ChainableMinecarts;
import com.andrii.chainableminecarts.ModBlocks;
import com.andrii.chainableminecarts.rail.BlockRailDoubleTurn;
import net.minecraft.block.Block;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.client.renderer.block.statemap.StateMap;
import net.minecraft.item.Item;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

@Mod.EventBusSubscriber(modid = ChainableMinecarts.MODID, value = Side.CLIENT)
public final class ModModels
{
    private ModModels()
    {
    }

    @SubscribeEvent
    public static void registerModels(ModelRegistryEvent event)
    {
        for (Block block : new Block[] {ModBlocks.railIntersection, ModBlocks.railDoubleTurn})
        {
            Item item = Item.getItemFromBlock(block);
            ModelLoader.setCustomModelResourceLocation(item, 0, new ModelResourceLocation(item.getRegistryName(), "inventory"));
        }

        // The switch's shape property is internal (vanilla rail code writes to it); its look depends on the others
        ModelLoader.setCustomStateMapper(ModBlocks.railDoubleTurn, new StateMap.Builder().ignore(BlockRailDoubleTurn.SHAPE).build());
    }
}
