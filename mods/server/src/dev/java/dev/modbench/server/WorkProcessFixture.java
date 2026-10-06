// SPDX-License-Identifier: MIT
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.modbench.bridge.Json;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.*;
import net.minecraft.network.play.server.S09PacketHeldItemChange;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.WorldServer;

/**
 * Journalled, dev-only mining/building course layered inside FluidFixture's bounded empty-sky work volume.
 * FluidFixture owns the player snapshot, ToolBuilder loadout, selected-slot packet and health restoration.
 * Restore empties that volume; a cell changed outside it (world:true) is journalled here as it stood and put back.
 */
final class WorkProcessFixture {
    private static final int WIDTH=32, DEPTH=16, FLOOR=175, TOP=185;
    private final MinecraftServer server;
    private final FluidFixture work;
    private final File journal;
    private NBTTagCompound saved;
    private int x,z,width=WIDTH,depth=DEPTH,top=TOP;

    WorkProcessFixture(MinecraftServer server) {
        this.server=server;this.work=new FluidFixture(server);
        this.journal=new File(world().getSaveHandler().getWorldDirectory(),"modbench-work-process-fixture.dat");
        if(journal.isFile()) try(FileInputStream in=new FileInputStream(journal)) {
            saved=CompressedStreamTools.readCompressed(in);x=saved.getInteger("x");z=saved.getInteger("z");
            if(saved.hasKey("width")) {width=saved.getInteger("width");depth=saved.getInteger("depth");top=saved.getInteger("top");}
        } catch(Exception e) {throw new IllegalStateException("cannot read work-process fixture recovery journal",e);}
    }

    /** {width?,depth?,top?,bare?}: an arena larger than 32 x 16 under y 185, and bare, without the built-in courses on its floor. */
    Object create(JsonObject params) throws Exception {
        if(saved!=null) throw new IllegalArgumentException("restore existing work-process fixture first");
        int w=Json.integer(params,"width",WIDTH,WIDTH,64),d=Json.integer(params,"depth",DEPTH,DEPTH,64),t=Json.integer(params,"top",TOP,TOP,200);
        boolean bare=Json.bool(params,"bare",false);
        JsonObject origin=(JsonObject)work.createWork(w,d,t);
        x=origin.getAsJsonArray("origin").get(0).getAsInt();z=origin.getAsJsonArray("origin").get(2).getAsInt();width=w;depth=d;top=t;
        saved=new NBTTagCompound();saved.setInteger("x",x);saved.setInteger("z",z);saved.setString("uuid",player().getUniqueID().toString());
        saved.setInteger("width",w);saved.setInteger("depth",d);saved.setInteger("top",t);saved.setBoolean("bare",bare);
        write();
        try {
            clearArena();buildArena();if(!bare) {buildTargets();buildSelectionArea();buildDescendingCase();}
            return position("ore_line");
        } catch(Exception|LinkageError e) {try {restore();} catch(Exception cleanup) {e.addSuppressed(cleanup);} throw e;}
    }

    /** Fixture-only block setter {x,y,z,id,meta,nbt?,world?}; nbt is the tile entity as text, the snbt that inspect_block and region return. */
    Object setBlock(JsonObject params) throws Exception {
        require();int[] at=cell(params,FLOOR);int meta=Json.integer(params,"meta",0,0,15);
        String id=Json.string(params,"id","");Block block=(Block)Block.blockRegistry.getObject(id);
        if(block==null || !id.equals(Block.blockRegistry.getNameForObject(block))) throw new IllegalArgumentException("exact registered block id required");
        NBTTagCompound tag=null;
        if(params.has("nbt")) {if(!(Snbt.read(Json.string(params,"nbt","{}")) instanceof NBTTagCompound read)) throw new IllegalArgumentException("nbt must be a compound");tag=read;}
        remember(at);put(at[0],at[1],at[2],block,meta,tag,3);return status();
    }

    /** {x,y,z,world?} as a world cell: x and z count from the arena's corner and stay inside it; with world:true they are any loaded overworld cell. */
    private int[] cell(JsonObject p,int fallbackY) {
        if(!Json.bool(p,"world",false)) return new int[]{x+Json.integer(p,"x",0,0,width-1),Json.integer(p,"y",fallbackY,FLOOR,top),z+Json.integer(p,"z",0,0,depth-1)};
        int[] c={Json.integer(p,"x",0,-30000000,30000000),Json.integer(p,"y",fallbackY,0,255),Json.integer(p,"z",0,-30000000,30000000)};
        if(!world().blockExists(c[0],c[1],c[2])) throw new IllegalArgumentException("world cell must be in a loaded chunk");
        return c;
    }
    private int[] corner(JsonObject p,String name) {
        if(!p.has(name)||!p.get(name).isJsonArray()||p.getAsJsonArray(name).size()!=3) throw new IllegalArgumentException(name+" must be [x,y,z]");
        JsonArray c=p.getAsJsonArray(name);return cell(Json.object("x",c.get(0),"y",c.get(1),"z",c.get(2),"world",Json.bool(p,"world",false)),FLOOR);
    }

    /** Restore empties the arena, so only a cell outside it needs remembering: as it stood before its first change, written before the change. */
    private void remember(int[] c) throws Exception {
        if(c[0]>=x&&c[0]<x+width&&c[1]>=FLOOR&&c[1]<=top&&c[2]>=z&&c[2]<z+depth) return;
        NBTTagList cells=saved.getTagList("world",10);
        for(int i=0;i<cells.tagCount();i++) {NBTTagCompound old=cells.getCompoundTagAt(i);if(old.getInteger("x")==c[0]&&old.getInteger("y")==c[1]&&old.getInteger("z")==c[2])return;}
        if(cells.tagCount()>=4096) throw new IllegalArgumentException("4096 world cells already changed; restore first");
        Block block=world().getBlock(c[0],c[1],c[2]);int meta=world().getBlockMetadata(c[0],c[1],c[2]);TileEntity tile=block.hasTileEntity(meta)?world().getTileEntity(c[0],c[1],c[2]):null;
        NBTTagCompound cell=new NBTTagCompound();cell.setInteger("x",c[0]);cell.setInteger("y",c[1]);cell.setInteger("z",c[2]);
        cell.setString("id",Block.blockRegistry.getNameForObject(block));cell.setInteger("meta",meta);if(tile!=null)cell.setTag("tile",dump(tile));
        cells.appendTag(cell);saved.setTag("world",cells);write();
    }

    /** One cell as given: the block and, with a tag, its tile entity, created and loaded the way a chunk load does it. */
    private void put(int wx,int y,int wz,Block block,int meta,NBTTagCompound tag,int flags) {
        WorldServer w=world();
        if(tag!=null&&!block.hasTileEntity(meta)) throw new IllegalArgumentException(Block.blockRegistry.getNameForObject(block)+":"+meta+" has no tile entity to take nbt");
        w.removeTileEntity(wx,y,wz);   // gone before its block is: a replaced container must not spill its contents as drops
        w.setBlock(wx,y,wz,block,meta,flags);
        if(tag==null) return;
        tag.setInteger("x",wx);tag.setInteger("y",y);tag.setInteger("z",wz);
        TileEntity tile=tag.hasKey("id")?TileEntity.createAndLoadEntity(tag):null;
        if(tile!=null) w.setTileEntity(wx,y,wz,tile);
        // No id, or one the game could not create and load: the block's own tile entity reads the tag, as /setblock does, and its error is the reply.
        else {tile=w.getTileEntity(wx,y,wz);if(tile==null)throw new IllegalStateException("the block made no tile entity to read the nbt");tile.readFromNBT(tag);}
        tile.markDirty();w.markBlockForUpdate(wx,y,wz);
    }
    private static NBTTagCompound dump(TileEntity tile) {NBTTagCompound tag=new NBTTagCompound();tile.writeToNBT(tag);return tag;}

    /** Fixture-only stack setter, scoped to the journalled fixture player and normal container synchronization. */
    Object setStack(JsonObject params) throws Exception {
        require();int slot=Json.integer(params,"slot",0,0,35),count=Json.integer(params,"count",1,1,64),meta=Json.integer(params,"meta",0,0,32767);
        String id=Json.string(params,"id","");
        Object raw=net.minecraft.item.Item.itemRegistry.getObject(id);
        if(!(raw instanceof net.minecraft.item.Item item) || !id.equals(net.minecraft.item.Item.itemRegistry.getNameForObject(item))) throw new IllegalArgumentException("exact registered item id required; use NEI discovery");
        ItemStack stack=new ItemStack(item,count,meta);
        if(params.has("nbt")) stack.setTagCompound((NBTTagCompound)JsonToNBT.func_150315_a(Json.string(params,"nbt","{}")));
        EntityPlayerMP p=player();p.inventory.setInventorySlotContents(slot,stack);
        // The player and client must agree on the selected hotbar entry before a native tool test begins.
        p.playerNetServerHandler.sendPacket(new S09PacketHeldItemChange(p.inventory.currentItem));
        p.inventoryContainer.detectAndSendChanges();p.sendContainerAndContentsToPlayer(p.inventoryContainer,p.inventoryContainer.getInventory());
        return status();
    }

    /** Read-only native machine evidence {x,y,z,world?}; snbt is the tile entity as set_block's nbt takes it. */
    Object inspectBlock(JsonObject params) {
        require();int[] at=cell(params,176);var tile=world().getTileEntity(at[0],at[1],at[2]);
        JsonObject out=blockAt("inspection",at[0],at[1],at[2]);
        // Pick-block is a client callback: vanilla's fallback refers to a
        // client-only Item accessor stripped from a dedicated server. Use the
        // serialized tile identity here as independent authoritative evidence.
        if(tile!=null) {
            NBTTagCompound tag=dump(tile);out.addProperty("tileClass",tile.getClass().getName());out.add("tile",Json.GSON.toJsonTree(nbt(tag)));out.addProperty("snbt",Snbt.write(tag));
            if(tile instanceof net.minecraft.inventory.IInventory inventory){List<Object> slots=new ArrayList<>();for(int i=0;i<inventory.getSizeInventory();i++)slots.add(stack(inventory.getStackInSlot(i)));out.add("inventory",Json.GSON.toJsonTree(slots));}
            if(tile instanceof net.minecraftforge.fluids.IFluidHandler handler){List<Object> tanks=new ArrayList<>();for(var tank:handler.getTankInfo(net.minecraftforge.common.util.ForgeDirection.UNKNOWN))if(tank!=null)tanks.add(Json.object("capacity",tank.capacity,"fluid",tank.fluid==null?null:Json.object("id",tank.fluid.getFluid().getName(),"amount",tank.fluid.amount)));out.add("tanks",Json.GSON.toJsonTree(tanks));}
        }
        return out;
    }

    /** Every non-air cell of the box {min:[x,y,z],max:[x,y,z],world?} as {pos,id,meta,snbt?}, pos as the corners were given: the picture to compare before and after. */
    Object region(JsonObject params) {
        require();int[] a=corner(params,"min"),b=corner(params,"max");boolean absolute=Json.bool(params,"world",false);WorldServer w=world();
        if(a[0]>b[0]||a[1]>b[1]||a[2]>b[2]) throw new IllegalArgumentException("min must not exceed max");
        if((b[0]-a[0]+1L)*(b[1]-a[1]+1L)*(b[2]-a[2]+1L)>16384) throw new IllegalArgumentException("region holds more than 16384 cells; read it in parts");
        JsonArray cells=new JsonArray();
        for(int wx=a[0];wx<=b[0];wx++) for(int wz=a[2];wz<=b[2];wz++) {
            // An unloaded column would read as air and pass for "unchanged".
            if(!w.blockExists(wx,a[1],wz)) throw new IllegalArgumentException("region must lie in loaded chunks");
            for(int y=a[1];y<=b[1];y++) {
                Block block=w.getBlock(wx,y,wz);if(block==Blocks.air) continue;
                JsonObject cell=Json.object("pos",Json.array(absolute?wx:wx-x,y,absolute?wz:wz-z),"id",Block.blockRegistry.getNameForObject(block),"meta",w.getBlockMetadata(wx,y,wz));
                TileEntity tile=w.getTileEntity(wx,y,wz);if(tile!=null)cell.addProperty("snbt",Snbt.write(dump(tile)));
                cells.add(cell);
            }
        }
        JsonObject out=new JsonObject();out.add("cells",cells);return out;
    }

    /** Energy is a finite fixture input {x,y,z,eu,world?}; never changes faces, connections, modes or recipe inputs. */
    Object supplyEnergy(JsonObject params) throws Exception {
        require();int[] at=cell(params,176);int eu=Json.integer(params,"eu",2048,0,32768);
        var tile=world().getTileEntity(at[0],at[1],at[2]);if(tile==null)throw new IllegalArgumentException("fixture machine required");
        remember(at);
        long capacity=((Number)tile.getClass().getMethod("getEUCapacity").invoke(tile)).longValue();
        tile.getClass().getMethod("setStoredEU",long.class).invoke(tile,Math.min(eu,capacity));tile.markDirty();return inspectBlock(params);
    }
    private static Object stack(ItemStack stack){return stack==null?null:Json.object("id",net.minecraft.item.Item.itemRegistry.getNameForObject(stack.getItem()),"meta",stack.getItemDamage(),"count",stack.stackSize,"nbt",stack.hasTagCompound()?stack.getTagCompound().toString():null);}
    private static Object nbt(NBTBase tag) {
        if(tag instanceof NBTTagCompound compound){java.util.Map<String,Object> out=new java.util.LinkedHashMap<>();for(Object key:compound.func_150296_c())out.put((String)key,nbt(compound.getTag((String)key)));return out;}
        if(tag instanceof NBTTagList list){List<Object> out=new ArrayList<>();NBTTagList copy=(NBTTagList)list.copy();while(copy.tagCount()>0)out.add(nbt(copy.removeTag(0)));return out;}
        if(tag instanceof NBTBase.NBTPrimitive number){if(tag.getId()<=4)return number.func_150291_c();return number.func_150286_g();}
        if(tag instanceof NBTTagString value)return value.func_150285_a_();
        if(tag instanceof NBTTagByteArray value)return value.func_150292_c();
        if(tag instanceof NBTTagIntArray value)return value.func_150302_c();
        return tag.toString();
    }

    Object position(String name) {
        require();int dx,dy,dz;
        switch(name) {
            case "ore_line" -> {dx=1;dy=176;dz=2;}
            case "selection" -> {dx=2;dy=176;dz=8;}
            case "build" -> {dx=8;dy=176;dz=11;}
            case "descend_start" -> {dx=20;dy=179;dz=3;}
            case "descend_step_1" -> {dx=21;dy=178;dz=3;}
            case "descend_step_2" -> {dx=22;dy=177;dz=3;}
            case "descend_goal" -> {dx=23;dy=176;dz=3;}
            default -> throw new IllegalArgumentException("position must be ore_line, selection, build, descend_start, descend_step_1, descend_step_2 or descend_goal");
        }
        EntityPlayerMP p=player();p.closeScreen();p.mountEntity(null);p.motionX=p.motionY=p.motionZ=0;p.fallDistance=0;
        p.playerNetServerHandler.setPlayerLocation(x+dx+.5,dy,z+dz+.5,0,0);
        return status();
    }

    Object status() {
        require();EntityPlayerMP p=player();JsonArray blocks=new JsonArray();boolean bare=saved.getBoolean("bare");
        if(!bare) for(Target target:targets()) blocks.add(blockAt(target.name,x+target.dx,target.y,z+target.dz));
        JsonArray inventory=new JsonArray();
        for(int slot=0;slot<36;slot++) {ItemStack stack=p.inventory.getStackInSlot(slot);if(stack!=null) inventory.add(Json.object("slot",slot,"id",net.minecraft.item.Item.itemRegistry.getNameForObject(stack.getItem()),"meta",stack.getItemDamage(),"count",stack.stackSize,"nbt",stack.hasTagCompound()?stack.getTagCompound().toString():null));}
        return Json.object("active",true,"origin",Json.array(x,FLOOR,z),"bounds",Json.object("min",Json.array(x,FLOOR,z),"maxExclusive",Json.array(x+width,top+1,z+depth)),
            "areas",bare?new JsonObject():Json.object("oreLine",Json.array(x+1,176,z+2,x+8,176,z+2),"selection",Json.array(x+2,176,z+8,x+14,179,z+13),"descending",Json.array(x+20,176,z+3,x+23,179,z+3)),
            "targets",blocks,"selectedSlot",p.inventory.currentItem,"inventory",inventory,"loadout","FluidFixture ToolBuilder Tinkers loadout");
    }

    Object restore() throws Exception {
        if(saved==null) return Json.object("restored",false);
        NBTTagList cells=saved.getTagList("world",10);JsonArray failed=new JsonArray();
        // World cells go back as they stood, the last changed first and without block updates: not as their neighbours would have them now.
        for(int i=cells.tagCount()-1;i>=0;i--) {
            NBTTagCompound c=cells.getCompoundTagAt(i);int wx=c.getInteger("x"),y=c.getInteger("y"),wz=c.getInteger("z");
            world().getChunkFromChunkCoords(wx>>4,wz>>4);   // the player may have left since: load it
            // One cell that will not load must not keep the arena, the player and every other cell from going back.
            try {put(wx,y,wz,(Block)Block.blockRegistry.getObject(c.getString("id")),c.getInteger("meta"),c.hasKey("tile")?c.getCompoundTag("tile"):null,2);}
            catch(RuntimeException e) {failed.add(Json.object("pos",Json.array(wx,y,wz),"id",c.getString("id"),"error",e.toString()));}
        }
        clearDrops();JsonObject restored=(JsonObject)work.restore();
        if(!journal.delete()) throw new IOException("cannot delete work-process fixture journal");
        saved=null;restored.addProperty("worldCells",cells.tagCount());if(failed.size()>0)restored.add("worldCellsFailed",failed);return restored;
    }

    private void clearArena() {for(int dx=0;dx<width;dx++)for(int dz=0;dz<depth;dz++)for(int y=FLOOR;y<=top;y++)set(dx,y,dz,Blocks.air,0);}
    private void buildArena() {
        for(int dx=0;dx<width;dx++)for(int dz=0;dz<depth;dz++) set(dx,FLOOR,dz,Blocks.glowstone,0);
        for(int dx=0;dx<width;dx++)for(int dz=0;dz<depth;dz++) if(dx==0||dz==0||dx==width-1||dz==depth-1) for(int y=176;y<top;y++) set(dx,y,dz,Blocks.glass,0);
        for(int dx=0;dx<width;dx++)for(int dz=0;dz<depth;dz++) set(dx,top-1,dz,Blocks.glass,0);
        for(int dx=2;dx<width-2;dx+=6) for(int dz=2;dz<depth-2;dz+=5) set(dx,top-2,dz,Blocks.glowstone,0);
    }
    private void buildTargets() {
        set(2,176,2,Blocks.coal_ore,0);set(3,176,2,Blocks.iron_ore,0);set(4,176,2,Blocks.gold_ore,0);
        set(5,176,2,Blocks.lapis_ore,0);set(6,176,2,Blocks.redstone_ore,0);set(7,176,2,Blocks.lit_redstone_ore,0);
        // Same registered block with distinct metadata verifies exact ID+meta observation without special cases.
        set(8,176,2,Blocks.stone,1);set(9,176,2,Blocks.stone,3);
    }
    private void buildSelectionArea() {
        // Cobblestone in FluidFixture's existing work loadout funds the empty cells in this bounded partial build.
        for(int dx=2;dx<=14;dx++) for(int dz=8;dz<=13;dz++) if(dx==2||dx==14||dz==8||dz==13) set(dx,175,dz,Blocks.stone,0);
        set(3,176,9,Blocks.cobblestone,0);set(4,176,9,Blocks.wool,14);set(5,176,9,Blocks.wool,11);
        set(3,177,9,Blocks.cobblestone,0);set(5,177,9,Blocks.stone_slab,8);
        set(12,176,12,Blocks.cobblestone,0);set(13,176,12,Blocks.cobblestone,0);
    }
    private void buildDescendingCase() {
        // Three descending, harvestable faces: surface y=179 -> 178 -> 177 -> 176. Each landing is two-wide,
        // with a same-height dry cardinal escape; do not weaken MiningJob's escape-step safety check for a fixture.
        for(int dz=2;dz<=4;dz++) {
            set(19,178,dz,Blocks.stone,0);set(20,178,dz,Blocks.stone,0); // source platform extends backward
            set(21,177,dz,Blocks.stone,0);set(22,176,dz,Blocks.stone,0);set(23,175,dz,Blocks.stone,0);
        }
        set(21,178,3,Blocks.iron_ore,0);set(22,177,3,Blocks.gold_ore,0);set(23,176,3,Blocks.redstone_ore,0);
        for(int dx=20;dx<=23;dx++) {set(dx,180,2,Blocks.glass,0);set(dx,180,4,Blocks.glass,0);} // visible guard, no bypass platform
    }
    private List<Target> targets() {return List.of(new Target("coal",2,176,2),new Target("iron",3,176,2),new Target("gold",4,176,2),new Target("lapis",5,176,2),new Target("redstone",6,176,2),new Target("lit_redstone",7,176,2),new Target("granite_meta",8,176,2),new Target("diorite_meta",9,176,2),new Target("descend_1",21,178,3),new Target("descend_2",22,177,3),new Target("descend_3",23,176,3));}
    private JsonObject blockAt(String name,int wx,int y,int wz) {Block block=world().getBlock(wx,y,wz);return Json.object("name",name,"pos",Json.array(wx,y,wz),"id",Block.blockRegistry.getNameForObject(block),"meta",world().getBlockMetadata(wx,y,wz));}
    private void set(int dx,int y,int dz,Block block,int meta) {if(dx<0||dx>=width||dz<0||dz>=depth||y<FLOOR||y>top) throw new IllegalArgumentException("fixture block outside journalled bounds");world().setBlock(x+dx,y,z+dz,block,meta,3);}
    private void clearDrops() {AxisAlignedBB box=AxisAlignedBB.getBoundingBox(x,FLOOR,z,x+width,top+1,z+depth);for(Object raw:world().getEntitiesWithinAABB(EntityItem.class,box))((EntityItem)raw).setDead();}
    private void require(){if(saved==null)throw new IllegalArgumentException("create work-process fixture first");if(!player().getUniqueID().toString().equals(saved.getString("uuid")))throw new IllegalArgumentException("fixture player mismatch");}
    private WorldServer world(){return server.worldServerForDimension(0);}
    private EntityPlayerMP player(){for(Object raw:server.getConfigurationManager().playerEntityList){EntityPlayerMP p=(EntityPlayerMP)raw;if(p.getCommandSenderName().equals("ModbenchDev")&&p.dimension==0)return p;}throw new IllegalArgumentException("fixture requires ModbenchDev in overworld");}
    private void write() throws Exception {try(FileOutputStream out=new FileOutputStream(journal)){CompressedStreamTools.writeCompressed(saved,out);}}
    private record Target(String name,int dx,int y,int dz) {}
}
