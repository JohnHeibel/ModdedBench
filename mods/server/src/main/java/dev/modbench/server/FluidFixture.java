// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.server;

import dev.modbench.bridge.Json;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fluids.IFluidBlock;

/** Explicit development fixture. Registered only with -Dmodbench.devFixtures=true. */
final class FluidFixture {
    private static final int FLOOR=175, WIDTH=32, DEPTH=16, TOP=185;
    private final MinecraftServer server;
    private final File journal;
    private NBTTagCompound saved;
    FluidFixture(MinecraftServer server) {
        this.server=server;
        journal=new File(server.worldServerForDimension(0).getSaveHandler().getWorldDirectory(),"modbench-fluid-fixture.dat");
        if(journal.isFile()) try(FileInputStream in=new FileInputStream(journal)) {saved=CompressedStreamTools.readCompressed(in);}
        catch(Exception e) {throw new IllegalStateException("cannot read fluid fixture recovery journal",e);}
    }
    private EntityPlayerMP player() {
        for(Object entry:server.getConfigurationManager().playerEntityList) {
            EntityPlayerMP p=(EntityPlayerMP)entry;
            if(p.getCommandSenderName().equals("ModbenchDev") && p.dimension==0) return p;
        }
        throw new IllegalArgumentException("fixture requires ModbenchDev in overworld");
    }
    private WorldServer world() {return server.worldServerForDimension(0);}
    private int toolMaterial(String name) throws Exception {
        // Use the installed pack's ToolBuilder; vanilla pickaxes are disabled by IguanaTweaks.
        Class<?> registry=Class.forName("tconstruct.library.TConstructRegistry");
        java.util.Map<?,?> materials=(java.util.Map<?,?>)registry.getField("toolMaterials").get(null);
        int material=-1;
        for(var entry:materials.entrySet()) if(name.equalsIgnoreCase((String)entry.getValue().getClass().getField("materialName").get(entry.getValue()))) material=(Integer)entry.getKey();
        if(material<0) throw new IllegalStateException("GTNH "+name+" tool material not found");
        return material;
    }
    private ItemStack buildTool(String headField,int material,String name,boolean bindingRequired) throws Exception {
        Class<?> tools=Class.forName("tconstruct.tools.TinkerTools"),builder=Class.forName("tconstruct.library.crafting.ToolBuilder");
        ItemStack head=new ItemStack((Item)tools.getField(headField).get(null),1,material);
        ItemStack rod=new ItemStack((Item)tools.getField("toolRod").get(null),1,material);
        ItemStack binding=bindingRequired?new ItemStack((Item)tools.getField("binding").get(null),1,material):null;
        return (ItemStack)builder.getMethod("buildTool",ItemStack.class,ItemStack.class,ItemStack.class,String.class)
            .invoke(builder.getField("instance").get(null),head,rod,binding,name);
    }
    private ItemStack miningTool() throws Exception {
        ItemStack result=buildTool("pickaxeHead",toolMaterial("Cobalt"),"Fluid regression pickaxe",true);
        if(result==null || result.getItem().getHarvestLevel(result,"pickaxe")<Blocks.obsidian.getHarvestLevel(0))
            throw new IllegalStateException("GTNH ToolBuilder did not produce an obsidian-capable pickaxe");
        return result;
    }
    private ItemStack optionalTool(String headField,int material,String name) {
        try {return buildTool(headField,material,name,false);}
        catch(Exception ignored) {return null;}
    }
    private void giveWorkLoadout(EntityPlayerMP p) throws Exception {
        // This isolated fixture runs only after the full player NBT was saved.
        // Carrying a natural-world fire timer into a construction test can kill
        // the player during chunk/teleport synchronization. Restore() reinstates
        // the original timer along with health and inventory.
        p.extinguish();
        ItemStack cobalt=miningTool();
        ItemStack broken=miningTool();
        broken.setItemDamage(broken.getMaxDamage());
        broken.getTagCompound().getCompoundTag("InfiTool").setBoolean("Broken",true);
        int steel=toolMaterial("Steel"), cobaltMaterial=toolMaterial("Cobalt");
        ItemStack steelPick=buildTool("pickaxeHead",steel,"Work fixture steel pickaxe",true);
        if(steelPick==null) throw new IllegalStateException("GTNH ToolBuilder did not produce a steel pickaxe");
        // The original inventory is already journaled; resets must remove tools moved by normal swaps.
        for(int slot=0;slot<36;slot++) p.inventory.setInventorySlotContents(slot,null);
        p.inventory.setInventorySlotContents(0,new ItemStack(Items.stick));
        p.inventory.setInventorySlotContents(1,steelPick);
        p.inventory.setInventorySlotContents(2,broken);
        p.inventory.setInventorySlotContents(5,new ItemStack(Blocks.cobblestone,64));
        p.inventory.setInventorySlotContents(12,cobalt);
        ItemStack shovel=optionalTool("shovelHead",cobaltMaterial,"Work fixture shovel");
        ItemStack hatchet=optionalTool("hatchetHead",cobaltMaterial,"Work fixture hatchet");
        if(shovel!=null) p.inventory.setInventorySlotContents(13,shovel);
        if(hatchet!=null) p.inventory.setInventorySlotContents(14,hatchet);
        p.inventory.currentItem=0;
        p.playerNetServerHandler.sendPacket(new net.minecraft.network.play.server.S09PacketHeldItemChange(0));
        p.inventoryContainer.detectAndSendChanges();
    }
    private void set(int x,int y,int z,Block b) {world().setBlock(saved.getInteger("x")+x,y,saved.getInteger("z")+z,b,0,3);}
    private void set(int x,int y,int z,Block b,int meta) {world().setBlock(saved.getInteger("x")+x,y,saved.getInteger("z")+z,b,meta,3);}
    Object create() throws Exception {
        return create("fluid");
    }
    Object createGeometry() throws Exception {return create("geometry");}
    Object createLongRoute() throws Exception {return create("long_route");}
    Object createWork() throws Exception {return create("work");}
    Object createUi() throws Exception {return create("gui");}
    private UiFixture ui() {
        if(saved==null||!saved.getString("kind").equals("gui")) throw new IllegalArgumentException("GUI fixture required");
        return new UiFixture(world(),player(),saved.getInteger("x"),saved.getInteger("z"));
    }
    Object positionUi(String name) {return ui().position(name);}
    Object statusUi() {return ui().status();}
    private Object create(String kind) throws Exception {
        if(saved!=null) throw new IllegalArgumentException("restore existing fluid fixture first");
        EntityPlayerMP p=player();
        if(p.isDead||p.getHealth()<=0) throw new IllegalArgumentException("fixture requires a living player; recover before creating a journal");
        ItemStack tool=kind.equals("fluid")?miningTool():null;
        if(p.theItemInWorldManager.getGameType()!=net.minecraft.world.WorldSettings.GameType.SURVIVAL) throw new IllegalArgumentException("fixture tests require survival player");
        int x=(int)Math.floor(p.posX)-4,z=(int)Math.floor(p.posZ)-4;
        int width=kind.equals("long_route")?432:WIDTH,depth=kind.equals("long_route")?5:DEPTH;
        int floor=kind.equals("long_route")?172:FLOOR,top=kind.equals("long_route")?225:TOP;
        // Explicit fixture setup may load/generate the server chunks needed for its
        // course. The client still receives them normally as the player travels.
        if(kind.equals("long_route")) for(int cx=x>>4;cx<=(x+width-1)>>4;cx++) for(int cz=z>>4;cz<=(z+depth-1)>>4;cz++)
            world().getChunkFromChunkCoords(cx,cz);
        for(int dx=0;dx<width;dx++) for(int dz=0;dz<depth;dz++) for(int y=floor;y<=top;y++)
            if(!world().blockExists(x+dx,y,z+dz)||world().getBlock(x+dx,y,z+dz)!=Blocks.air) throw new IllegalArgumentException("fixture volume must be loaded air");
        saved=new NBTTagCompound();saved.setInteger("x",x);saved.setInteger("z",z);
        saved.setString("kind",kind);
        saved.setInteger("width",width);saved.setInteger("depth",depth);saved.setInteger("floor",floor);saved.setInteger("top",top);
        NBTTagCompound playerData=new NBTTagCompound();p.writeToNBT(playerData);saved.setTag("player",playerData);
        saved.setString("uuid",p.getUniqueID().toString());
        try(FileOutputStream out=new FileOutputStream(journal)) {CompressedStreamTools.writeCompressed(saved,out);}
        if(kind.equals("geometry")) {buildGeometry();return position("shapes_start");}
        if(kind.equals("long_route")) {buildLongRoute();return position("long_start");}
        if(kind.equals("work")) {buildWork();giveWorkLoadout(p);return position("work_start");}
        if(kind.equals("gui")) {ui().create();return ui().position("chest");}
        // A closed stone basin contains all fluids, including subsequent block updates.
        for(int dx=0;dx<WIDTH;dx++) for(int dz=0;dz<DEPTH;dz++) for(int y=FLOOR;y<=178;y++) set(dx,y,dz,y==178?Blocks.glowstone:Blocks.stone);
        for(int dx=0;dx<WIDTH;dx++) for(int dz=0;dz<DEPTH;dz++) if(dx==0||dz==0||dx==WIDTH-1||dz==DEPTH-1)
            for(int y=179;y<=181;y++) set(dx,y,dz,Blocks.stone);
        for(int dx=2;dx<=9;dx++) for(int dz=2;dz<=4;dz++) for(int y=176;y<=178;y++) set(dx,y,dz,Blocks.water);
        for(int dx=1;dx<=10;dx++) for(int dz:new int[]{1,5}) for(int y=179;y<=182;y++) set(dx,y,dz,Blocks.stone);
        for(int dx=15;dx<=22;dx++) for(int dz=2;dz<=4;dz++) set(dx,178,dz,Blocks.air);
        for(int dx=14;dx<=23;dx++) for(int dz:new int[]{1,5}) for(int y=178;y<=182;y++) set(dx,y,dz,Blocks.stone);
        for(int dz=2;dz<=4;dz++) set(15,178,dz,Blocks.flowing_water);
        // Obsidian cap over live lava, with flowing water above and a raised dry bank.
        for(int dx=3;dx<=8;dx++) for(int dz=9;dz<=11;dz++) {
            set(dx,177,dz,Blocks.lava);set(dx,178,dz,Blocks.obsidian);
        }
        for(int dx=2;dx<=9;dx++) for(int dz:new int[]{8,12}) for(int y=179;y<=182;y++) set(dx,y,dz,Blocks.stone);
        for(int dz=9;dz<=11;dz++) {set(2,179,dz,Blocks.stone);set(9,179,dz,Blocks.stone);}
        set(3,179,10,Blocks.flowing_water);
        // Separate, sealed cells for actual pack fluid implementations.
        List<String> modded=new ArrayList<>();
        for(Object key:Block.blockRegistry.getKeys()) {
            Block b=(Block)Block.blockRegistry.getObject(key);
            if(b instanceof IFluidBlock) modded.add(key.toString());
        }
        modded.sort(String::compareTo);
        for(int i=0;i<Math.min(3,modded.size());i++) {
            int bx=17+i*4;
            for(int dx=bx-1;dx<=bx+1;dx++) for(int dz=9;dz<=13;dz++) for(int y=179;y<=181;y++) set(dx,y,dz,Blocks.stone);
            for(int dz=10;dz<=12;dz++) for(int y=179;y<=180;y++) set(bx,y,dz,Blocks.air);
            set(bx,179,10,(Block)Block.blockRegistry.getObject(modded.get(i)));
        }
        saved.setString("modded",String.join(",",modded.subList(0,Math.min(3,modded.size()))));
        try(FileOutputStream out=new FileOutputStream(journal)) {CompressedStreamTools.writeCompressed(saved,out);}
        p.inventory.setInventorySlotContents(p.inventory.currentItem,tool);
        p.inventoryContainer.detectAndSendChanges();
        return position("pool_start");
    }
    /** A compact, deliberately dry course for server-side Baritone development. */
    private void buildWork() {
        // A continuous catch floor keeps every start and target on known, dry footing.
        for(int x=0;x<WIDTH;x++) for(int z=0;z<DEPTH;z++) set(x,175,z,Blocks.glowstone);

        // Direct targets exercise the harvest/tool selector without path hazards.
        set(3,176,2,Blocks.dirt); set(4,176,2,Blocks.stone); set(5,176,2,Blocks.obsidian);
        set(1,176,1,Blocks.torch); set(6,176,3,Blocks.torch);

        // One-wide, roofed dig corridor: the only forward route crosses all three walls.
        for(int x=8;x<=17;x++) {
            for(int y=176;y<=178;y++) {set(x,y,1,Blocks.bedrock);set(x,y,3,Blocks.bedrock);}
            for(int z=1;z<=3;z++) set(x,179,z,Blocks.bedrock);
        }
        for(int y=176;y<=178;y++) set(17,y,2,Blocks.bedrock);
        for(int y=176;y<=177;y++) {set(11,y,2,Blocks.dirt);set(13,y,2,Blocks.stone);set(15,y,2,Blocks.log,0);}
        // The entry has no roof. The raised dry block is a retreat/escape step behind tunnel_start.
        for(int y=176;y<=178;y++) {set(8,y,1,Blocks.bedrock);set(8,y,3,Blocks.bedrock);}
        set(8,176,2,Blocks.stone); set(9,176,2,Blocks.torch);

        // Bridge lane at y=177. The stone catcher at y=175 is two blocks below its surface.
        for(int x=19;x<=30;x++) {
            if(x<22||x>24) set(x,177,7,Blocks.stone);
            for(int y=178;y<=181;y++) {set(x,y,6,Blocks.stone);set(x,y,8,Blocks.stone);}
            for(int z=6;z<=8;z++) set(x,182,z,Blocks.stone);
        }
        for(int y=178;y<=181;y++) {set(18,y,7,Blocks.stone);set(31,y,7,Blocks.stone);}
        set(19,178,7,Blocks.torch); set(30,178,7,Blocks.torch);

        // The liquid source has no side or overhead bypass around the single stone plug.
        for(int x=19;x<=25;x++) {
            for(int y=176;y<=178;y++) {set(x,y,10,Blocks.stone);set(x,y,12,Blocks.stone);}
            for(int z=10;z<=12;z++) set(x,179,z,Blocks.stone);
        }
        for(int y=176;y<=178;y++) set(25,y,11,Blocks.stone);
        set(22,176,11,Blocks.stone); set(23,176,11,Blocks.lava); set(20,176,11,Blocks.torch);

        // Sand then gravel must fall after the plug is mined. Its roof prevents climbing around it.
        for(int x=26;x<=31;x++) {
            for(int y=176;y<=180;y++) {set(x,y,10,Blocks.stone);set(x,y,12,Blocks.stone);}
            for(int z=10;z<=12;z++) set(x,181,z,Blocks.stone);
        }
        for(int y=176;y<=180;y++) {set(26,y,11,Blocks.stone);set(31,y,11,Blocks.stone);}
        set(28,176,11,Blocks.stone); set(28,177,11,Blocks.sand); set(28,178,11,Blocks.gravel); set(27,176,11,Blocks.torch);

        // A chest is a real tile entity, boxed into a lane with a stone target beyond it.
        for(int x=0;x<=7;x++) {
            for(int y=176;y<=178;y++) {set(x,y,10,Blocks.stone);set(x,y,12,Blocks.stone);}
            for(int z=10;z<=12;z++) set(x,179,z,Blocks.stone);
        }
        for(int y=176;y<=178;y++) set(7,y,11,Blocks.stone);
        set(3,176,11,Blocks.chest); set(5,176,11,Blocks.stone); set(1,176,11,Blocks.torch);
    }
    private Object workCases() {
        return Json.array(
            Json.object("name","tool_targets","start","work_start","startRelative",Json.array(2,176,2),"targets",Json.array(
                Json.object("name","dirt","relative",Json.array(3,176,2)),Json.object("name","stone","relative",Json.array(4,176,2)),Json.object("name","obsidian","relative",Json.array(5,176,2)))),
            Json.object("name","tunnel","start","tunnel_start","startRelative",Json.array(9,176,2),"goal","tunnel_goal","goalRelative",Json.array(16,176,2),"escape","tunnel_escape","blockers",Json.array(
                Json.object("block","dirt","relative",Json.array(11,176,2)),Json.object("block","stone","relative",Json.array(13,176,2)),Json.object("block","log","relative",Json.array(15,176,2)))),
            Json.object("name","bridge","start","bridge_start","startRelative",Json.array(19,178,7),"goal","bridge_goal","goalRelative",Json.array(30,178,7),"missingFloor",Json.array(Json.array(22,177,7),Json.array(23,177,7),Json.array(24,177,7)),"catcherY",175),
            Json.object("name","liquid_plug","start","liquid_start","startRelative",Json.array(20,176,11),"target",Json.object("block","stone","relative",Json.array(22,176,11)),"hazard",Json.object("block","lava","relative",Json.array(23,176,11))),
            Json.object("name","gravity_plug","start","gravity_start","startRelative",Json.array(27,176,11),"target",Json.object("block","stone","relative",Json.array(28,176,11)),"above",Json.array(Json.object("block","sand","relative",Json.array(28,177,11)),Json.object("block","gravel","relative",Json.array(28,178,11)))),
            Json.object("name","container_obstacle","start","container_start","startRelative",Json.array(1,176,11),"goal","container_goal","goalRelative",Json.array(4,176,11),"target",Json.object("block","stone","relative",Json.array(5,176,11)),"obstacle",Json.object("block","chest","relative",Json.array(3,176,11),"tileEntity",true)));
    }
    private void buildGeometry() {
        // Illuminate the course through full-cube support blocks without changing movement geometry.
        for(int x=0;x<WIDTH;x++) for(int z=0;z<DEPTH;z++) set(x,FLOOR,z,Blocks.glowstone);
        set(2,176,3,Blocks.stone_slab);
        set(3,176,3,Blocks.stone);
        set(4,176,3,Blocks.stone);set(4,177,3,Blocks.oak_stairs,0);
        for(int x=5;x<=10;x++) for(int y=176;y<=177;y++) set(x,y,3,Blocks.stone);
        set(6,178,3,Blocks.carpet);
        set(7,178,3,Blocks.snow_layer,4);
        set(8,178,3,Blocks.stone_slab,8);
        set(9,178,3,Blocks.stone);set(10,178,3,Blocks.stone);
        set(10,179,3,Blocks.wooden_slab);
        // A real modded slab, if provided by the pack, uses the same collision adapter.
        List<String> candidates=new ArrayList<>();
        for(Object key:Block.blockRegistry.getKeys()) if(key.toString().startsWith("Botania:") && Block.blockRegistry.getObject(key) instanceof net.minecraft.block.BlockSlab) candidates.add(key.toString());
        candidates.sort(String::compareTo);
        for(String id:candidates) {
            Block b=(Block)Block.blockRegistry.getObject(id);set(10,179,3,b);
            var box=b.getCollisionBoundingBoxFromPool(world(),saved.getInteger("x")+10,179,saved.getInteger("z")+3);
            if(box!=null && Math.abs(box.maxY-179.5)<.001) {saved.setString("moddedSlab",id);break;}
            set(10,179,3,Blocks.wooden_slab);
        }
        for(int x=11;x<=14;x++) for(int z=2;z<=4;z++) {
            for(int y=176;y<=177;y++) set(x,y,z,Blocks.stone);
            for(int y=178;y<=179;y++) set(x,y,z,z==3?Blocks.water:Blocks.stone);
        }
        for(int y=176;y<=179;y++) set(15,y,3,Blocks.stone);
        // Four attachment directions. All climb through a six-rung column onto a platform.
        for(int y=176;y<=181;y++) {
            set(21,y,3,Blocks.stone);set(20,y,3,Blocks.ladder,4);
            set(25,y,3,Blocks.stone);set(26,y,3,Blocks.ladder,5);
            set(20,y,9,Blocks.stone);set(20,y,10,Blocks.ladder,3);
            set(26,y,11,Blocks.stone);set(26,y,10,Blocks.ladder,2);
        }
        for(int x=21;x<=25;x++) set(x,181,3,Blocks.stone);
        for(int z=7;z<=9;z++) set(20,181,z,Blocks.stone);
        for(int z=11;z<=13;z++) set(26,181,z,Blocks.stone);
        try(FileOutputStream out=new FileOutputStream(journal)) {CompressedStreamTools.writeCompressed(saved,out);}
        catch(Exception e) {throw new IllegalStateException("cannot update geometry fixture journal",e);}
    }
    private void buildLongRoute() {
        for(int x=0;x<432;x++) {
            int floor=220-Math.min(48,Math.max(0,(x-320)/2));
            for(int z=0;z<5;z++) {
                set(x,floor,z,Blocks.stone);set(x,floor+5,z,Blocks.stone);
                if(z==0||z==4) for(int y=floor+1;y<floor+5;y++) set(x,y,z,Blocks.stone);
            }
            if(x%8==0) set(x,floor+1,1,Blocks.torch);
        }
        // Close the ends. This is a lit navigation corridor, not a progression test.
        for(int x:new int[]{0,431}) {
            int floor=x==0?220:172;
            for(int y=floor+1;y<floor+5;y++) for(int z=1;z<4;z++) set(x,y,z,Blocks.stone);
        }
    }
    Object changeGeometry(String name) {
        if(saved==null||!saved.getString("kind").equals("geometry")) throw new IllegalArgumentException("geometry fixture required");
        if(name.equals("remove_east_rung")) set(20,179,3,Blocks.air);
        else if(name.equals("restore_east_rung")) set(20,179,3,Blocks.ladder,4);
        else if(name.equals("block_step_headroom")) set(2,178,3,Blocks.stone_slab);
        else if(name.equals("restore_step_headroom")) set(2,178,3,Blocks.air);
        else throw new IllegalArgumentException("unknown geometry change");
        return Json.object("changed",name);
    }
    Object changeWork(String name) throws Exception {
        if(saved==null||!saved.getString("kind").equals("work")) throw new IllegalArgumentException("work fixture required");
        if(name.equals("reset_tunnel")) {
            for(int y=176;y<=177;y++) {set(11,y,2,Blocks.dirt);set(13,y,2,Blocks.stone);set(15,y,2,Blocks.log,0);}
        } else if(name.equals("open_tunnel")) {
            for(int x:new int[]{11,13,15}) for(int y=176;y<=177;y++) set(x,y,2,Blocks.air);
        } else if(name.equals("fill_bridge_gaps")) {
            for(int x:new int[]{22,23,24}) set(x,177,7,Blocks.cobblestone);
        } else if(name.equals("reset_bridge_gaps")) {
            for(int x:new int[]{22,23,24}) set(x,177,7,Blocks.air);
        } else if(name.equals("reset_liquid_plug")) {
            for(int x=19;x<=24;x++) for(int y=176;y<=178;y++) set(x,y,11,Blocks.air);
            set(22,176,11,Blocks.stone);set(23,176,11,Blocks.lava);set(20,176,11,Blocks.torch);
        } else if(name.equals("reset_gravity_plug")) {
            set(28,176,11,Blocks.stone);set(28,177,11,Blocks.sand);set(28,178,11,Blocks.gravel);
        } else if(name.equals("remove_tools")) {
            for(int slot=0;slot<36;slot++) {
                ItemStack stack=player().inventory.getStackInSlot(slot);
                if(stack!=null && stack.hasTagCompound() && stack.getTagCompound().hasKey("InfiTool"))
                    player().inventory.setInventorySlotContents(slot,null);
            }
            player().inventoryContainer.detectAndSendChanges();
        } else if(name.equals("remove_building_blocks")) {
            for(int slot=0;slot<36;slot++) {
                ItemStack stack=player().inventory.getStackInSlot(slot);
                if(stack!=null && stack.getItem() instanceof net.minecraft.item.ItemBlock)
                    player().inventory.setInventorySlotContents(slot,null);
            }
            player().inventoryContainer.detectAndSendChanges();
        } else if(name.equals("restore_loadout")) {
            giveWorkLoadout(player());
        } else throw new IllegalArgumentException("unknown work change");
        return Json.object("changed",name,"kind","work","cases",workCases());
    }
    Object positionWork(String name) {
        if(saved==null||!saved.getString("kind").equals("work")) throw new IllegalArgumentException("work fixture required");
        return position(name);
    }
    Object position(String name) {
        if(saved==null) throw new IllegalArgumentException("no fluid fixture");
        EntityPlayerMP p=player();
        if(!p.getUniqueID().toString().equals(saved.getString("uuid"))) throw new IllegalArgumentException("fixture player mismatch");
        int dx,dz,y;
        switch(name) {
            case "pool_start" -> {dx=1;dz=3;y=179;}
            case "submerged" -> {dx=5;dz=3;y=176;}
            case "flow_start" -> {dx=14;dz=3;y=179;}
            case "flow_end" -> {dx=23;dz=3;y=179;}
            case "mining" -> {dx=2;dz=10;y=180;}
            case "shapes_start" -> {dx=1;dz=3;y=176;}
            case "slab_start" -> {dx=2;dz=3;y=176;}
            case "east_start" -> {dx=19;dz=3;y=176;}
            case "west_start" -> {dx=27;dz=3;y=176;}
            case "north_start" -> {dx=20;dz=11;y=176;}
            case "south_start" -> {dx=26;dz=9;y=176;}
            case "long_start" -> {dx=1;dz=2;y=221;}
            case "long_end" -> {dx=417;dz=2;y=173;}
            case "work_start" -> {dx=2;dz=2;y=176;}
            case "tunnel_start" -> {dx=9;dz=2;y=176;}
            case "tunnel_goal" -> {dx=16;dz=2;y=176;}
            case "tunnel_escape" -> {dx=8;dz=2;y=177;}
            case "bridge_start" -> {dx=19;dz=7;y=178;}
            case "bridge_goal" -> {dx=30;dz=7;y=178;}
            case "liquid_start" -> {dx=20;dz=11;y=176;}
            case "gravity_start" -> {dx=27;dz=11;y=176;}
            case "container_start" -> {dx=1;dz=11;y=176;}
            case "container_goal" -> {dx=4;dz=11;y=176;}
            default -> throw new IllegalArgumentException("unknown fixture position");
        }
        p.mountEntity(null);p.motionX=p.motionY=p.motionZ=0;p.fallDistance=0;
        p.playerNetServerHandler.setPlayerLocation(saved.getInteger("x")+dx+.5,y+(name.equals("slab_start")?.5:0),saved.getInteger("z")+dz+.5,0,0);
        return Json.object("origin",Json.array(saved.getInteger("x"),0,saved.getInteger("z")),"kind",saved.getString("kind"),"position",name,
            "cases",saved.getString("kind").equals("work")?workCases():Json.array(),"moddedFluids",saved.getString("modded"),"moddedSlab",saved.getString("moddedSlab"));
    }
    Object restore() throws Exception {
        if(saved==null) return Json.object("restored",false);
        EntityPlayerMP p=player();
        if(!p.getUniqueID().toString().equals(saved.getString("uuid"))) throw new IllegalArgumentException("fixture player mismatch");
        p.closeScreen();p.inventory.setItemStack(null);
        p.readFromNBT(saved.getCompoundTag("player"));p.motionX=p.motionY=p.motionZ=0;
        p.playerNetServerHandler.setPlayerLocation(p.posX,p.posY,p.posZ,p.rotationYaw,p.rotationPitch);
        p.inventoryContainer.detectAndSendChanges();
        int width=saved.hasKey("width")?saved.getInteger("width"):WIDTH,depth=saved.hasKey("depth")?saved.getInteger("depth"):DEPTH;
        p.playerNetServerHandler.sendPacket(new net.minecraft.network.play.server.S09PacketHeldItemChange(p.inventory.currentItem));
        p.sendPlayerAbilities();
        p.playerNetServerHandler.sendPacket(new net.minecraft.network.play.server.S06PacketUpdateHealth(p.getHealth(),p.getFoodStats().getFoodLevel(),p.getFoodStats().getSaturationLevel()));
        int floor=saved.hasKey("floor")?saved.getInteger("floor"):FLOOR,top=saved.hasKey("top")?saved.getInteger("top"):TOP;
        var region=net.minecraft.util.AxisAlignedBB.getBoundingBox(saved.getInteger("x"),floor,saved.getInteger("z"),saved.getInteger("x")+width,top+1,saved.getInteger("z")+depth);
        for(Object entity:world().getEntitiesWithinAABB(net.minecraft.entity.item.EntityItem.class,region)) ((net.minecraft.entity.Entity)entity).setDead();
        // Remove fluids first, without neighbor updates, then remove their containing basin.
        for(int dx=0;dx<width;dx++) for(int dz=0;dz<depth;dz++) {
            if(saved.getString("kind").equals("long_route")) world().getChunkFromChunkCoords((saved.getInteger("x")+dx)>>4,(saved.getInteger("z")+dz)>>4);
            for(int y=top;y>=floor;y--) if(world().getBlock(saved.getInteger("x")+dx,y,saved.getInteger("z")+dz)!=Blocks.air)
                world().setBlock(saved.getInteger("x")+dx,y,saved.getInteger("z")+dz,Blocks.air,0,2);
        }
        // Removing native containers can spawn their contents after the initial
        // sweep. Remove these fixture drops before they can fall out of the volume.
        int removedDrops=0;
        for(Object entity:world().getEntitiesWithinAABB(net.minecraft.entity.item.EntityItem.class,region)) {
            net.minecraft.entity.Entity item=(net.minecraft.entity.Entity)entity;if(!item.isDead) {item.setDead();removedDrops++;}
        }
        if(!journal.delete()) throw new IllegalStateException("could not remove fixture journal");
        saved=null;return Json.object("restored",true,"removedFixtureDrops",removedDrops);
    }
}
