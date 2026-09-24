package com.andrii.chainableminecarts;

import com.andrii.chainableminecarts.rail.BlockRailDoubleTurn;
import com.andrii.chainableminecarts.rail.BlockRailIntersection;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod.EventBusSubscriber(modid = ChainableMinecarts.MODID)
public final class ModBlocks
{
    public static Block railIntersection;
    public static Block railDoubleTurn;

    private ModBlocks()
    {
    }

    @SubscribeEvent
    public static void registerBlocks(RegistryEvent.Register<Block> event)
    {
        railIntersection = new BlockRailIntersection();
        railDoubleTurn = new BlockRailDoubleTurn();
        event.getRegistry().registerAll(railIntersection, railDoubleTurn);
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event)
    {
        for (Block block : new Block[] {railIntersection, railDoubleTurn})
        {
            event.getRegistry().register(new ItemBlock(block).setRegistryName(block.getRegistryName()));
        }
    }
}
