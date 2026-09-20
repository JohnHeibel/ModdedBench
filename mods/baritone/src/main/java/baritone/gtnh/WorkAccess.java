// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.gtnh.pathing.*;
import static baritone.gtnh.pathing.WorkSpec.*;
import dev.modbench.control.ClientMemory;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.item.*;
import net.minecraft.nbt.*;
import net.minecraft.util.*;
import net.minecraft.world.World;
import net.minecraftforge.oredict.OreDictionary;

/** Native registry, ore-dictionary and NBT selectors shared by bulk work. */
final class WorkAccess {
    static final Minecraft MC=Minecraft.getMinecraft();
    static final int[][] SIDES={{0,-1,0},{0,1,0},{0,0,-1},{0,0,1},{-1,0,0},{1,0,0}};
    static BlockPos feet(){return new BlockPos((int)Math.floor(MC.thePlayer.posX),(int)Math.floor(MC.thePlayer.boundingBox.minY+.001),(int)Math.floor(MC.thePlayer.posZ));}
    static void player(){if(MC.theWorld==null||MC.thePlayer==null||MC.thePlayer.getHealth()<=0||MC.currentScreen!=null)throw new IllegalArgumentException("living player with closed GUI required");}
    static boolean item(ItemStack stack,Map<String,Object> selector) {
        if(stack==null||stack.stackSize<=0)return false;
        if(selector.containsKey("id")&&!selector.get("id").equals(Item.itemRegistry.getNameForObject(stack.getItem())))return false;
        if(selector.containsKey("meta")&&integer(selector,"meta",0,0,32767)!=stack.getItemDamage())return false;
        if(selector.containsKey("nbt"))try {
            NBTBase expected=JsonToNBT.func_150315_a(string(selector,"nbt","{}"));
            if(!Objects.equals(expected,stack.hasTagCompound()?stack.getTagCompound():new NBTTagCompound()))return false;
        }catch(Exception error){throw new IllegalArgumentException("invalid selector NBT",error);}
        if(selector.containsKey("ore")) {
            String name=string(selector,"ore","");boolean found=false;
            for(int id:OreDictionary.getOreIDs(stack))if(name.equals(OreDictionary.getOreName(id)))found=true;
            if(!found)return false;
        }
        return true;
    }
    static List<Map<String,Object>> selectors(Object value) {
        var out=selectorObjects(value);for(var s:out)validateBlockSelector(s);return out;
    }
    static List<Map<String,Object>> selectorObjects(Object value) {
        List<?> entries=list(value);if(entries.isEmpty()||entries.size()>64)throw new IllegalArgumentException("1..64 selectors required");
        List<Map<String,Object>> out=new ArrayList<>();for(Object e:entries) {
            Map<String,Object> s=object(e);if(!s.containsKey("id")&&!s.containsKey("ore")&&!s.containsKey("item"))throw new IllegalArgumentException("selector needs id, ore or item");
            if(s.containsKey("meta"))integer(s,"meta",0,0,32767);if(s.containsKey("nbt"))try{JsonToNBT.func_150315_a(string(s,"nbt","{}"));}catch(Exception error){throw new IllegalArgumentException("invalid selector NBT",error);}
            out.add(s);
        }return List.copyOf(out);
    }
    static List<Map<String,Object>> itemSelectors(Object value) {
        var out=selectorObjects(value);
        for(var s:out)validateItemSelector(s);
        return out;
    }
    static void validateItemSelector(Map<String,Object> s) {
        if(!s.containsKey("id")&&!s.containsKey("ore"))throw new IllegalArgumentException("item selector needs id or ore");
        if(!Set.of("id","meta","nbt","ore").containsAll(s.keySet()))throw new IllegalArgumentException("unknown item selector field");
        if(s.containsKey("meta"))integer(s,"meta",0,0,32767);
        for(String key:List.of("id","ore"))if(s.containsKey(key)&&string(s,key,"").isBlank())throw new IllegalArgumentException("empty item selector "+key);
        if(s.containsKey("nbt"))try{JsonToNBT.func_150315_a(string(s,"nbt","{}"));}catch(Exception error){throw new IllegalArgumentException("invalid selector NBT",error);}
    }
    static void validateBlockSelector(Map<String,Object> s) {
        if(!s.containsKey("id")&&!s.containsKey("ore")&&!s.containsKey("item"))throw new IllegalArgumentException("block selector needs id, ore or item");
        if(!Set.of("id","meta","ore","item").containsAll(s.keySet()))throw new IllegalArgumentException("unknown block selector field; use item.nbt for picked stack NBT");
        if(s.containsKey("meta"))integer(s,"meta",0,0,15);
        for(String key:List.of("id","ore"))if(s.containsKey(key)&&string(s,key,"").isBlank())throw new IllegalArgumentException("empty block selector "+key);
        if(s.containsKey("item"))validateItemSelector(child(s,"item"));
    }
    static ItemStack picked(World world,BlockPos p) {
        Block block=world.getBlock(p.x(),p.y(),p.z());
        return block.getPickBlock(new MovingObjectPosition(p.x(),p.y(),p.z(),1,Vec3.createVectorHelper(p.x()+.5,p.y()+.5,p.z()+.5)),world,p.x(),p.y(),p.z(),MC.thePlayer);
    }
    static boolean block(World world,BlockPos p,Map<String,Object> s) {
        if(!ForgeSnapshot.loaded(world,p.x(),p.y(),p.z()))return false;
        Block block=world.getBlock(p.x(),p.y(),p.z());
        if(s.containsKey("id")&&!s.get("id").equals(Block.blockRegistry.getNameForObject(block)))return false;
        if(s.containsKey("meta")&&integer(s,"meta",0,0,15)!=world.getBlockMetadata(p.x(),p.y(),p.z()))return false;
        if(s.containsKey("item")&&!item(picked(world,p),child(s,"item")))return false;
        if(s.containsKey("ore")&&!item(picked(world,p),Map.of("ore",s.get("ore"))))return false;
        return true;
    }
    static boolean block(World world,BlockPos p,List<Map<String,Object>> selectors){for(var s:selectors)if(block(world,p,s))return true;return false;}
    static int count(List<Map<String,Object>> selectors) {
        int count=0;for(ItemStack stack:MC.thePlayer.inventory.mainInventory)if(stack!=null)for(var s:selectors)if(item(stack,s)){count+=stack.stackSize;break;}return count;
    }
    static boolean room(List<Map<String,Object>> selectors) {
        // Packs may append equipment/offhand storage to mainInventory. Native
        // pickup decides which empty slots can actually receive drops.
        if(MC.thePlayer.inventory.getFirstEmptyStack()>=0)return true;
        for(ItemStack stack:MC.thePlayer.inventory.mainInventory) {
            if(stack==null)continue;
            if(stack.stackSize<Math.min(stack.getMaxStackSize(),MC.thePlayer.inventory.getInventoryStackLimit()))for(var s:selectors)if(item(stack,s))return true;
        }return false;
    }
    static double distance(BlockPos p){return Math.hypot(p.x()+.5-MC.thePlayer.posX,p.z()+.5-MC.thePlayer.posZ)+Math.abs(p.y()-MC.thePlayer.boundingBox.minY);}
    static Vec3 eyeAt(BlockPos feet){return Vec3.createVectorHelper(feet.x()+.5,feet.y()+MC.thePlayer.getPosition(1).yCoord-MC.thePlayer.boundingBox.minY,feet.z()+.5);}
    record Pose(BlockPos feet,double standingY) {}
    static Vec3 eyeAt(Pose pose){return Vec3.createVectorHelper(pose.feet().x()+.5,pose.standingY()+MC.thePlayer.getPosition(1).yCoord-MC.thePlayer.boundingBox.minY,pose.feet().z()+.5);}
    static List<Pose> buildingApproaches(World world,BlockPos target) {
        double reach=MC.playerController.getBlockReachDistance();
        int radius=Math.min(8,(int)Math.ceil(reach));
        BlockPos min=new BlockPos(target.x()-radius,Math.max(1,target.y()-radius-2),target.z()-radius);
        BlockPos max=new BlockPos(target.x()+radius,Math.min(254,target.y()+radius),target.z()+radius);
        // Use exactly the navigation graph's collision-derived footing. A
        // full-block-only pose list loses every stance as a slab roof closes.
        // Include the lower poses permitted by the player's native eye/reach.
        TerrainGrid grid=ForgeSnapshot.local(world,min,max);List<Pose> out=new ArrayList<>();
        for(int x=min.x();x<=max.x();x++)for(int z=min.z();z<=max.z();z++)for(int y=min.y();y<=max.y();y++) {
            BlockPos feet=new BlockPos(x,y,z);double height=grid.standingY(feet);
            if(!Double.isFinite(height))continue;
            Pose pose=new Pose(feet,height);Vec3 eye=eyeAt(pose);
            double dx=Math.max(0,Math.max(target.x()-eye.xCoord,eye.xCoord-target.x()-1));
            double dy=Math.max(0,Math.max(target.y()-eye.yCoord,eye.yCoord-target.y()-1));
            double dz=Math.max(0,Math.max(target.z()-eye.zCoord,eye.zCoord-target.z()-1));
            if(dx*dx+dy*dy+dz*dz<=reach*reach&&ForgeSnapshot.liveClear(world,x+.5,height,z+.5,height+1.8))out.add(pose);
        }
        out.sort(Comparator.comparingDouble(p->distance(p.feet())));return out;
    }
    static Map<String,Object> observed(World world,BlockPos p) {
        if(!ForgeSnapshot.loaded(world,p.x(),p.y(),p.z()))return Map.of("pos",point(p),"loaded",false);
        Map<String,Object> out=new LinkedHashMap<>();out.put("pos",point(p));out.put("loaded",true);out.put("id",Block.blockRegistry.getNameForObject(world.getBlock(p.x(),p.y(),p.z())));out.put("meta",world.getBlockMetadata(p.x(),p.y(),p.z()));
        var tile=world.getTileEntity(p.x(),p.y(),p.z());out.put("tileClass",tile==null?null:tile.getClass().getName());return out;
    }
    static List<BlockPos> approaches(World world,BlockPos target,boolean allowBreak) {
        List<BlockPos> out=new ArrayList<>();BlockPos here=feet();
        if(ForgeSnapshot.liveStandable(world,here)&&!target.equals(new BlockPos(here.x(),here.y()-1,here.z())))out.add(here);
        for(int y=Math.max(1,target.y()-2);y<=Math.min(254,target.y()+1);y++)for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++) {
            if(Math.abs(dx)+Math.abs(dz)==0||Math.abs(dx)+Math.abs(dz)>3)continue;
            BlockPos p=new BlockPos(target.x()+dx,y,target.z()+dz);
            if(!ForgeSnapshot.loaded(world,p.x(),p.y(),p.z())||!ForgeSnapshot.loaded(world,p.x(),p.y()-1,p.z()))continue;
            if(ForgeSnapshot.liveStandable(world,p)||allowBreak&&ForgeSnapshot.classify(world,p.x(),p.y()-1,p.z())==TerrainGrid.SUPPORT&&MiningTools.automaticBlock(world,p)&&MiningTools.automaticBlock(world,new BlockPos(p.x(),p.y()+1,p.z())))out.add(p);
        }
        out.sort(Comparator.comparingDouble(WorkAccess::distance));return out.stream().distinct().limit(40).toList();
    }
    static String protection(BlockPos p,boolean override){return ClientMemory.editProblem(p.x(),p.y(),p.z(),override,true);}
}
