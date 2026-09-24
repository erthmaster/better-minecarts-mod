package com.andrii.chainableminecarts;

import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * The mod's settings, in groups. Forge writes each group's settings in alphabetical order, whatever their order
 * here, so the groups are what keeps related settings together in the file and the in-game config screen.
 */
@Config(modid = ChainableMinecarts.MODID)
@Mod.EventBusSubscriber(modid = ChainableMinecarts.MODID)
public class ModConfig
{
    @Config.Comment("How minecarts move and collide: weight, friction, and being solid for players and mobs.")
    public static final Carts carts = new Carts();

    @Config.Comment("Carts on curved rails, and whoever rides them.")
    public static final Curves curves = new Curves();

    @Config.Comment("Furnace carts: fuel, and how they drive their trains.")
    public static final Furnace furnace = new Furnace();

    @Config.Comment("Minecarts chained together with leads.")
    public static final Trains trains = new Trains();

    public static class Carts
    {
        @Config.Comment({"Replace vanilla minecart collisions with heavy, non-bouncy ones and add rolling resistance.",
            "Turn off if another mod (e.g. Railcraft) installs its own minecart collision handler."})
        @Config.RequiresMcRestart
        public boolean heavyPhysics = true;

        @Config.Comment("Constant slowdown per tick for carts on unpowered rails, in blocks/tick.")
        @Config.RangeDouble(min = 0.0D, max = 0.1D)
        public double rollingResistance = 0.002D;

        @Config.Comment("Carts slower than this (blocks/tick) come to a full stop.")
        @Config.RangeDouble(min = 0.0D, max = 0.1D)
        public double restSpeed = 0.003D;

        @Config.Comment("How much two carts bounce apart when they collide (0 = not at all, 1 = fully elastic).")
        @Config.RangeDouble(min = 0.0D, max = 1.0D)
        public double restitution = 0.0D;

        @Config.Comment("Minecarts are solid for players and mobs, like a block: they can't walk through carts and can stand on them.")
        public boolean solidCarts = true;

        @Config.Comment("How much of a vanilla entity push a cart receives when a player or mob walks into it (vanilla = 1).")
        @Config.RangeDouble(min = 0.0D, max = 1.0D)
        public double entityPushFactor = 0.4D;
    }

    public static class Curves
    {
        @Config.Comment("Carts follow the curve of curved rails and turn smoothly, instead of cutting diagonally and snapping between angles.")
        public boolean smoothCurves = true;

        @Config.Comment("Whoever rides a cart turns with it through curves, the way a boat turns its passengers.")
        public boolean riderTurnsWithCart = true;
    }

    public static class Furnace
    {
        @Config.Comment({"Furnace carts run a fixed distance per coal and push along the track through curves.",
            "Off: vanilla furnace carts (fuel burns by time, and they often stall on curves)."})
        public boolean distanceFurnaceFuel = true;

        @Config.Comment("How many blocks a furnace cart travels on one coal.")
        @Config.RangeDouble(min = 1.0D, max = 10000.0D)
        public double furnaceBlocksPerCoal = 720.0D;

        @Config.Comment({"Top speed of a train driven by furnace carts, in blocks/tick (vanilla furnace carts: 0.2).",
            "No cart goes faster than its rail allows, 0.4 on vanilla rails, so anything above that acts as 0.4."})
        @Config.RangeDouble(min = 0.01D, max = 1.0D)
        public double furnaceSpeed = 0.5D;

        @Config.Comment("How much speed a furnace-driven train gains per tick until it reaches its top speed.")
        @Config.RangeDouble(min = 0.001D, max = 0.5D)
        public double furnacePower = 0.02D;

        @Config.Comment({"How much speed a furnace cart's brakes take off its train each tick, in blocks/tick.",
            "Lower means the train carries further after the brakes go on; 0 means it just coasts."})
        @Config.RangeDouble(min = 0.0D, max = 1.0D)
        public double furnaceBrakePower = 0.007D;

        @Config.Comment("How many other carts each fuelled furnace cart pulls at full speed.")
        @Config.RangeInt(min = 0, max = 64)
        public int cartsPerFurnace = 19;

        @Config.Comment({"How much a train's top speed drops for each cart beyond what its furnaces pull at full speed, in blocks/tick.",
            "When the top speed reaches 0 the furnaces can't move the train."})
        @Config.RangeDouble(min = 0.0D, max = 1.0D)
        public double furnaceSlowdownPerCart = 0.1D;
    }

    public static class Trains
    {
        @Config.Comment("Largest gap between the edges of two chained minecarts, in blocks. Farther apart, they are pulled together.")
        @Config.RangeDouble(min = 0.0D, max = 5.0D)
        public double maxRopeGap = 0.6D;

        @Config.Comment({"Smallest gap between the edges of two chained minecarts, in blocks. Closer, they are pushed apart.",
            "Between this and maxRopeGap the lead is slack and the spacing isn't corrected. Capped at maxRopeGap."})
        @Config.RangeDouble(min = 0.0D, max = 5.0D)
        public double minRopeGap = 0.4D;

        @Config.Comment("How quickly chained carts fix their spacing to the lead length (0 = never, 1 = within one tick).")
        @Config.RangeDouble(min = 0.0D, max = 1.0D)
        public double stiffness = 0.2D;

        @Config.Comment("A lead snaps when its two ends get farther apart than this, in blocks.")
        @Config.RangeDouble(min = 2.0D, max = 32.0D)
        public double breakDistance = 3.0D;

        @Config.Comment("Drop the lead item when a lead breaks on its own (stretched too far, holder gone, cart destroyed).")
        public boolean dropLeadOnBreak = true;
    }

    @SubscribeEvent
    public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event)
    {
        if (event.getModID().equals(ChainableMinecarts.MODID))
        {
            ConfigManager.sync(ChainableMinecarts.MODID, Config.Type.INSTANCE);
        }
    }
}
