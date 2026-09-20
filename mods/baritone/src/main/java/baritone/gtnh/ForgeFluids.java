// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.gtnh.pathing.FluidPolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.fluids.BlockFluidBase;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.IFluidBlock;

/** Read-only Forge fluid sampling. Never drains a block to discover whether it is a source. */
final class ForgeFluids {
    static boolean water(Block b) { return b==Blocks.water || b==Blocks.flowing_water; }
    static boolean fluid(Block b) { return b instanceof IFluidBlock || b.getMaterial().isLiquid(); }

    static Vec3 flow(World world,int x,int y,int z) {
        // Flow implementations inspect neighbors, including the level below and above them.
        for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++)
            if(!ForgeSnapshot.loaded(world,x+dx,Math.max(0,y-1),z+dz) || !ForgeSnapshot.loaded(world,x+dx,Math.min(255,y+1),z+dz)) return null;
        Block b=world.getBlock(x,y,z);
        Vec3 v;
        if(b==Blocks.water || b==Blocks.flowing_water || b==Blocks.lava || b==Blocks.flowing_lava) {
            v=Vec3.createVectorHelper(0,0,0);
            b.velocityToAddToEntity(world,x,y,z,null,v);
        } else if(b instanceof BlockFluidBase base) v=base.getFlowVector(world,x,y,z);
        else return null;
        if(v==null || !Double.isFinite(v.xCoord)||!Double.isFinite(v.yCoord)||!Double.isFinite(v.zCoord)) return null;
        return v;
    }

    static Map<String,Object> inspect(World world,int x,int y,int z) {
        if(!ForgeSnapshot.loaded(world,x,y,z)) throw new IllegalArgumentException("fluid block is not loaded");
        Block b=world.getBlock(x,y,z);
        Map<String,Object> out=new LinkedHashMap<>();
        int meta=world.getBlockMetadata(x,y,z);
        out.put("pos",List.of(x,y,z)); out.put("block",Block.blockRegistry.getNameForObject(b));
        out.put("metadata",meta); out.put("fluid",fluid(b));
        if(!fluid(b)) return out;
        boolean vanilla=water(b)||b==Blocks.lava||b==Blocks.flowing_lava;
        boolean lava=b.getMaterial()==Material.lava;
        Integer temperature=vanilla?(water(b)?300:1300):null;
        out.put("fluidId",vanilla?(water(b)?"water":"lava"):null);
        out.put("canDrain",null); out.put("filledFraction",null);
        try {
            if(b instanceof IFluidBlock fb) {
                Fluid f=fb.getFluid();
                if(f!=null) {
                    out.put("fluidId",f.getName()); temperature=f.getTemperature(world,x,y,z);
                    out.put("density",f.getDensity(world,x,y,z)); out.put("viscosity",f.getViscosity(world,x,y,z));
                    out.put("gaseous",f.isGaseous(world,x,y,z));
                }
                float fill=fb.getFilledPercentage(world,x,y,z);
                out.put("filledFraction",Float.isFinite(fill)?fill:null);
                out.put("fillsFromTop",fill<0);
                out.put("canDrain",fb.canDrain(world,x,y,z));
                // Forge canDrain does not imply vanilla metadata semantics or exactly one bucket.
                out.put("source",null);
            } else if(vanilla) {
                boolean covered=y<255 && ForgeSnapshot.loaded(world,x,y+1,z) && world.getBlock(x,y+1,z).getMaterial()==b.getMaterial();
                out.put("filledFraction",covered?1:1-BlockLiquid.getLiquidHeightPercent(meta));
                out.put("source",meta==0); out.put("canDrain",meta==0); out.put("falling",meta>=8);
            }
            Vec3 flow=flow(world,x,y,z);
            out.put("flow",flow==null?null:List.of(flow.xCoord,flow.yCoord,flow.zCoord));
            out.put("flowUnits","direction contribution, not player velocity");
        } catch(RuntimeException e) {
            out.put("samplingError",e.getClass().getSimpleName());
        }
        String policy=FluidPolicy.reason(water(b),lava,temperature);
        out.put("temperatureK",temperature); out.put("traversal",policy);
        out.put("traversableFluid",policy.equals("verified_water"));
        return out;
    }
}
