// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.Baritone;
import baritone.compat.BlockPos;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.block.IGrowable;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.common.IPlantable;
import net.minecraftforge.common.util.ForgeDirection;
import static baritone.gtnh.pathing.WorkSpec.*;

/** What a farm job harvests, plants, fertilizes and picks up: the caller's selectors, else defaults shown in the receipt.
 *  Readiness and seeds are the game's answers (IGrowable, IPlantable, canSustainPlant), not a list of crops. */
public final class FarmPlan {
    // Defaults only: vanilla's crops and soils, each replaceable by the caller's own list.
    static final List<Map<String,Object>> CROPS=List.of(Map.of("id","minecraft:wheat"),Map.of("id","minecraft:carrots"),Map.of("id","minecraft:potatoes"),
        Map.of("id","minecraft:cocoa"),Map.of("id","minecraft:nether_wart","meta",3),Map.of("id","minecraft:pumpkin"),Map.of("id","minecraft:melon_block"),
        Map.of("id","minecraft:reeds"),Map.of("id","minecraft:cactus"));
    static final List<Map<String,Object>> SOILS=List.of(Map.of("id","minecraft:farmland"),Map.of("id","minecraft:soul_sand"));
    static final List<Map<String,Object>> FERTILIZERS=List.of(Map.of("id","minecraft:dye","meta",15));
    private final List<Map<String,Object>> crops,soils,fertilizers,seeds,collect;
    private final MiningObservation scan;
    /** What the last farm tick saw, for the receipt. */
    public volatile Map<String,Object> seen=Map.of();
    FarmPlan(World world,BlockPos center,int radius,Map<String,Object> params){
        crops=params.containsKey("crops")?WorkAccess.selectors(params.get("crops")):CROPS;
        soils=params.containsKey("soils")?WorkAccess.selectors(params.get("soils")):SOILS;
        seeds=params.containsKey("seeds")?WorkAccess.itemSelectors(params.get("seeds")):null;
        fertilizers=params.containsKey("fertilizers")?WorkAccess.itemSelectors(params.get("fertilizers")):FERTILIZERS;
        collect=params.containsKey("collect")?WorkAccess.itemSelectors(params.get("collect")):null;
        List<Map<String,Object>> both=new ArrayList<>(crops);both.addAll(soils);
        scan=new MiningObservation(world,bounds(Map.of("min",List.of(center.getX()-radius,Math.max(1,center.getY()-16),center.getZ()-radius),
            "max",List.of(center.getX()+radius,Math.min(254,center.getY()+16),center.getZ()+radius))),List.copyOf(both),List.of());
    }
    /** Scan a slice; true once a whole pass has been seen. */
    public boolean tick(){scan.tick();return scan.passes>0;}
    public List<BlockPos> locations(){return scan.observedLocations();}
    public boolean inBounds(BlockPos p){return scan.acceptsDrop(p);}
    /** The index of the crop selector this block matches, or -1. */
    public int crop(World w,BlockPos p){for(int i=0;i<crops.size();i++)if(WorkAccess.block(w,p,crops.get(i)))return i;return -1;}
    public int crops(){return crops.size();}
    /** A selector with a meta names the ripe state itself; otherwise ask the block: an IGrowable is ripe once it cannot grow,
     *  an IPlantable that is not (reeds, cactus) is cut above one of its own kind, and anything else is ripe as it stands. */
    public boolean ready(World w,BlockPos p,int crop){
        if(crops.get(crop).containsKey("meta"))return true;
        Block b=w.getBlock(p.getX(),p.getY(),p.getZ());
        if(b instanceof IGrowable g)return !g.func_149851_a(w,p.getX(),p.getY(),p.getZ(),true);
        if(b instanceof IPlantable)return !Baritone.settings().replantCrops.value||w.getBlock(p.getX(),p.getY()-1,p.getZ())==b;
        return true;
    }
    /** Soil the caller named with air above it. */
    public boolean openSoil(World w,BlockPos p){
        return w.isAirBlock(p.getX(),p.getY()+1,p.getZ())&&soils.stream().anyMatch(s->WorkAccess.block(w,p,s));
    }
    /** A seed for this soil: one the caller named, or by default any IPlantable the soil says it sustains. */
    public boolean seed(ItemStack stack,World w,BlockPos p){
        if(stack==null||stack.stackSize<=0)return false;
        if(seeds!=null)return seeds.stream().anyMatch(s->WorkAccess.item(stack,s));
        return stack.getItem() instanceof IPlantable plant&&w.getBlock(p.getX(),p.getY(),p.getZ()).canSustainPlant(w,p.getX(),p.getY(),p.getZ(),ForgeDirection.UP,plant);
    }
    public boolean fertilizer(ItemStack stack){return stack!=null&&stack.stackSize>0&&fertilizers.stream().anyMatch(s->WorkAccess.item(stack,s));}
    public boolean collect(ItemStack stack){return stack!=null&&stack.stackSize>0&&(collect==null||collect.stream().anyMatch(s->WorkAccess.item(stack,s)));}
    /** The rules in force, defaults included. */
    public Map<String,Object> rules(){
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("crops",crops);out.put("soils",soils);
        out.put("seeds",seeds!=null?seeds:"any IPlantable item the soil's canSustainPlant accepts");
        out.put("fertilizers",fertilizers);out.put("collect",collect!=null?collect:"any item on the ground within the scan bounds (3 blocks beyond)");
        out.put("ripe","a crop selector with meta: that state; else IGrowable that cannot grow; else IPlantable above its own kind (replantCrops); else as it stands");
        out.put("scanPasses",scan.passes);
        return out;
    }
}
