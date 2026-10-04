// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.compat.Registry;
import dev.modbench.api.ControlRegistry;
import baritone.compat.BlockPos;
import baritone.gtnh.pathing.*;
import static baritone.gtnh.pathing.WorkSpec.*;
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
    static BlockPos feet(){return new BlockPos((int)Math.floor(MC.thePlayer.posX),(int)Math.floor(MC.thePlayer.boundingBox.minY+.001),(int)Math.floor(MC.thePlayer.posZ));}
    static void player(){if(MC.theWorld==null||MC.thePlayer==null||MC.thePlayer.getHealth()<=0||MC.currentScreen!=null)throw new IllegalArgumentException("living player with closed GUI required");}
    static boolean item(ItemStack stack,Map<String,Object> selector) {
        if(stack==null||stack.stackSize<=0)return false;
        if(selector.containsKey("id")&&!selector.get("id").equals(Registry.name(stack.getItem())))return false;
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
    static ItemStack picked(World world,BlockPos p){return picked(world.getBlock(p.getX(),p.getY(),p.getZ()),world,p,MC.thePlayer);}
    /** The stack the block's own pick hook names. A mod's hook that throws (a tile it expected is not there) names none. */
    static ItemStack picked(Block block,World world,BlockPos p,net.minecraft.entity.player.EntityPlayer player) {
        try{return block.getPickBlock(new MovingObjectPosition(p.getX(),p.getY(),p.getZ(),1,Vec3.createVectorHelper(p.getX()+.5,p.getY()+.5,p.getZ()+.5)),world,p.getX(),p.getY(),p.getZ(),player);}
        catch(RuntimeException|LinkageError failed){return null;}
    }
    static boolean block(World world,BlockPos p,Map<String,Object> s) {
        if(!ForgeSnapshot.loaded(world,p.getX(),p.getY(),p.getZ()))return false;
        Block block=world.getBlock(p.getX(),p.getY(),p.getZ());
        if(s.containsKey("id")&&!s.get("id").equals(Registry.name(block)))return false;
        if(s.containsKey("meta")&&integer(s,"meta",0,0,15)!=world.getBlockMetadata(p.getX(),p.getY(),p.getZ()))return false;
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
    static double distance(BlockPos p){return Math.hypot(p.getX()+.5-MC.thePlayer.posX,p.getZ()+.5-MC.thePlayer.posZ)+Math.abs(p.getY()-MC.thePlayer.boundingBox.minY);}
    static Vec3 eyeAt(BlockPos feet){return Vec3.createVectorHelper(feet.getX()+.5,feet.getY()+MC.thePlayer.getPosition(1).yCoord-MC.thePlayer.boundingBox.minY,feet.getZ()+.5);}
    record Pose(BlockPos feet,double standingY) {}
    static Vec3 eyeAt(Pose pose){return Vec3.createVectorHelper(pose.feet().getX()+.5,pose.standingY()+MC.thePlayer.getPosition(1).yCoord-MC.thePlayer.boundingBox.minY,pose.feet().getZ()+.5);}
    /** Where the player could stand to work on `target`, nearest first: Stands, taken for this one cell. */
    static List<Pose> buildingApproaches(World world,BlockPos target) {return new Stands(world,target,Stands.radius()).around(target);}
    /**
     * Every pose the player could stand in within `half` of a centre, captured once and asked for many targets: a
     * capture per target is most of what a large plan costs the game thread. It uses exactly the navigation graph's
     * collision-derived footing (a full-block-only list loses every stance as a slab roof closes) and includes the
     * lower poses the player's own eye and reach permit.
     */
    static final class Stands {
        private final World world;private final BlockPos centre;private final int half;private final List<Pose> poses=new ArrayList<>();
        static int radius(){return Math.min(8,(int)Math.ceil(MC.playerController.getBlockReachDistance()));}
        Stands(World world,BlockPos centre,int half){
            this.world=world;this.centre=centre;this.half=half;
            BlockPos min=new BlockPos(centre.getX()-half,Math.max(1,centre.getY()-half-2),centre.getZ()-half);
            BlockPos max=new BlockPos(centre.getX()+half,Math.min(254,centre.getY()+half),centre.getZ()+half);
            TerrainGrid grid=ForgeSnapshot.local(world,min,max);
            for(int x=min.getX();x<=max.getX();x++)for(int z=min.getZ();z<=max.getZ();z++)for(int y=min.getY();y<=max.getY();y++){
                BlockPos feet=new BlockPos(x,y,z);double height=grid.standingY(feet);
                if(Double.isFinite(height))poses.add(new Pose(feet,height));
            }
        }
        /** Whether every pose within reach of `target` is in here. */
        boolean covers(BlockPos target){return within(target,half-radius());}
        boolean within(BlockPos p,int distance){
            return Math.abs(p.getX()-centre.getX())<=distance&&Math.abs(p.getY()-centre.getY())<=distance&&Math.abs(p.getZ()-centre.getZ())<=distance;
        }
        List<Pose> around(BlockPos target){
            double reach=MC.playerController.getBlockReachDistance();int radius=radius();List<Pose> out=new ArrayList<>();
            for(Pose pose:poses){
                BlockPos feet=pose.feet();
                if(Math.abs(feet.getX()-target.getX())>radius||Math.abs(feet.getZ()-target.getZ())>radius||feet.getY()<target.getY()-radius-2||feet.getY()>target.getY()+radius)continue;
                Vec3 eye=eyeAt(pose);
                double dx=Math.max(0,Math.max(target.getX()-eye.xCoord,eye.xCoord-target.getX()-1));
                double dy=Math.max(0,Math.max(target.getY()-eye.yCoord,eye.yCoord-target.getY()-1));
                double dz=Math.max(0,Math.max(target.getZ()-eye.zCoord,eye.zCoord-target.getZ()-1));
                if(dx*dx+dy*dy+dz*dz<=reach*reach&&ForgeSnapshot.liveClear(world,feet.getX()+.5,pose.standingY(),feet.getZ()+.5,pose.standingY()+1.8))out.add(pose);
            }
            out.sort(Comparator.comparingDouble(p->distance(p.feet())));return out;
        }
    }
    static Map<String,Object> observed(World world,BlockPos p) {
        if(!ForgeSnapshot.loaded(world,p.getX(),p.getY(),p.getZ()))return Map.of("pos",point(p),"loaded",false);
        Map<String,Object> out=new LinkedHashMap<>();out.put("pos",point(p));out.put("loaded",true);out.put("id",Registry.name(world.getBlock(p.getX(),p.getY(),p.getZ())));out.put("meta",world.getBlockMetadata(p.getX(),p.getY(),p.getZ()));
        var tile=world.getTileEntity(p.getX(),p.getY(),p.getZ());out.put("tileClass",tile==null?null:tile.getClass().getName());return out;
    }
    /** The shared stall watchdog: the job's stallTicks param, else the stallTicks setting. */
    static Stall stall(Map<String,Object> params){return new Stall(integer(params,"stallTicks",baritone.Baritone.settings().stallTicks.value,0,72000));}
    /** For the stall watchdog: the ticks a path search may take in all (its longest timeout) while one is in flight, else 0. */
    static int searchBudget(baritone.Baritone engine){
        if(engine.getPathingBehavior().getInProgress().isEmpty())return 0;
        var s=baritone.Baritone.settings();return (int)Math.min(72000,(Math.max(s.failureTimeoutMS.value,s.planAheadFailureTimeoutMS.value)+49)/50);
    }
    /** A dead player, or the one that died and was replaced by a respawn. */
    static boolean died(Object player){return player instanceof net.minecraft.entity.EntityLivingBase p&&(p.isDead||p.getHealth()<=0);}
    static String protection(BlockPos p,boolean override){return ControlRegistry.memory().editProblem(p.getX(),p.getY(),p.getZ(),override,true);}
}
