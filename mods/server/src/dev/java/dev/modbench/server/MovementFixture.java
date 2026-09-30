// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.modbench.bridge.Json;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityFallingBlock;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S09PacketHeldItemChange;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.fluids.IFluidBlock;

/**
 * Deterministic movement and flowing-liquid course, high in the sky on a glowstone catch floor.
 * Sixteen 16x16 plots; the origin sits 8 blocks west/north of a chunk corner, so every plot crosses a chunk border
 * at u=8 and v=8. position{name} rebuilds that case's plot from scratch, resets the player (health, fire, air,
 * food, loadout) and places them at the case start, so every trial starts from the same world.
 * Partial and thin-topped blocks are chosen by measuring collision boxes of every registered block, never by class.
 * Public because FML's generated event handlers (item-fate tracking) must be able to see the class.
 */
public final class MovementFixture {
    /** Plots run west to east four per row; plot 16 opens a fifth row, so the volume is 64 x 80. */
    static final int PLOTS=18, SIZE=64, SIZE_Z=16*((PLOTS+3)/4), FLOOR=199, TOP=218, B=200;
    private int sizeZ=SIZE_Z;
    /** Every item that entered the course volume since the last position, with how it left (picked, burned, gone). */
    private final List<Tracked> items=new ArrayList<>();
    private static final class Tracked {EntityItem e;String id;int count;boolean pickupAttempt;boolean lava;int fire;double[] pos;}
    private final MinecraftServer server;
    private final File journal;
    private NBTTagCompound saved;
    private int x0,z0;
    private final Map<String,Case> cases=new LinkedHashMap<>();
    private List<Shape> shapes;

    MovementFixture(MinecraftServer server) {
        this.server=server;
        journal=new File(world().getSaveHandler().getWorldDirectory(),"modbench-movement-fixture.dat");
        if(journal.isFile()) try(FileInputStream in=new FileInputStream(journal)) {
            saved=CompressedStreamTools.readCompressed(in);x0=saved.getInteger("x");z0=saved.getInteger("z");
            sizeZ=saved.hasKey("sizeZ")?saved.getInteger("sizeZ"):SIZE;
        } catch(Exception e) {throw new IllegalStateException("cannot read movement fixture recovery journal",e);}
        defineCases();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
        cpw.mods.fml.common.FMLCommonHandler.instance().bus().register(this);
    }
    private boolean inside(double x,double z) {return saved!=null&&x>=x0&&x<x0+SIZE&&z>=z0&&z<z0+sizeZ;}
    @cpw.mods.fml.common.eventhandler.SubscribeEvent public void joined(net.minecraftforge.event.entity.EntityJoinWorldEvent event) {
        if(event.world.isRemote||!(event.entity instanceof EntityItem item)||!inside(item.posX,item.posZ)||item.getEntityItem()==null) return;
        Tracked t=new Tracked();t.e=item;t.id=Item.itemRegistry.getNameForObject(item.getEntityItem().getItem());t.count=item.getEntityItem().stackSize;
        synchronized(items) {if(items.size()<512) items.add(t);}
    }
    /** Blocks the player broke and what they dropped, per id, since the last position: the authoritative 'mined'. */
    private final Map<String,Integer> broken=new LinkedHashMap<>(),dropped=new LinkedHashMap<>();
    @cpw.mods.fml.common.eventhandler.SubscribeEvent public void broke(net.minecraftforge.event.world.BlockEvent.BreakEvent event) {
        if(event.world.isRemote||!inside(event.x,event.z)) return;
        synchronized(items) {broken.merge(Block.blockRegistry.getNameForObject(event.block),1,Integer::sum);}
    }
    @cpw.mods.fml.common.eventhandler.SubscribeEvent public void drops(net.minecraftforge.event.world.BlockEvent.HarvestDropsEvent event) {
        if(event.world.isRemote||!inside(event.x,event.z)||event.harvester==null) return;
        synchronized(items) {for(ItemStack d:event.drops) if(d!=null&&d.getItem()!=null) dropped.merge(Item.itemRegistry.getNameForObject(d.getItem()),d.stackSize,Integer::sum);}
    }
    @cpw.mods.fml.common.eventhandler.SubscribeEvent public void pickup(net.minecraftforge.event.entity.player.EntityItemPickupEvent event) {
        synchronized(items) {for(Tracked t:items) if(t.e==event.item) t.pickupAttempt=true;}
    }
    /** Samples live tracked items each server tick: lava contact or fire before they vanish decides 'burned'. */
    @cpw.mods.fml.common.eventhandler.SubscribeEvent public void tick(cpw.mods.fml.common.gameevent.TickEvent.ServerTickEvent event) {
        if(event.phase!=cpw.mods.fml.common.gameevent.TickEvent.Phase.END) return;
        synchronized(items) {for(Tracked t:items) if(!t.e.isDead) {
            t.lava|=t.e.handleLavaMovement();t.fire=Math.max(t.fire,fireTicks(t.e));t.pos=new double[]{t.e.posX,t.e.posY,t.e.posZ};
            if(t.e.getEntityItem()!=null) t.count=t.e.getEntityItem().stackSize;}}
    }
    private Object itemFates() {
        JsonArray a=new JsonArray();
        synchronized(items) {for(Tracked t:items) {
            // gone: dead without a pickup or lava/fire contact; in practice merged into a neighbouring stack.
            String fate=!t.e.isDead?"alive":t.pickupAttempt?"picked":(t.lava||t.fire>0)?"burned":"gone";
            JsonObject j=(JsonObject)Json.object("id",t.id,"count",t.count,"fate",fate,"touchedLava",t.lava);
            if(t.pos!=null) j.add("pos",Json.GSON.toJsonTree(Json.array(Shape.r(t.pos[0]),Shape.r(t.pos[1]),Shape.r(t.pos[2]))));
            a.add(j);
        }}
        return a;
    }

    // ---------------------------------------------------------------- world helpers
    private WorldServer world() {return server.worldServerForDimension(0);}
    private EntityPlayerMP player() {
        // After a death the list can briefly hold a stale entity as well; the live one has an open connection
        // and is the latest added.
        EntityPlayerMP found=null;
        for(Object o:server.getConfigurationManager().playerEntityList) {
            EntityPlayerMP p=(EntityPlayerMP)o;
            if(!p.getCommandSenderName().equals("ModbenchDev")||p.dimension!=0) continue;
            boolean open=p.playerNetServerHandler!=null&&p.playerNetServerHandler.netManager.isChannelOpen();
            if(open&&!p.isDead||found==null) found=p;
        }
        if(found==null) throw new IllegalArgumentException("fixture requires ModbenchDev in overworld");
        return found;
    }
    /** A player at zero health (a death outside the safety net, saved and loaded again) is revived in place:
     *  respawnPlayer leaves the client driving a different entity from the one the server tracks and saves. */
    private EntityPlayerMP livePlayer() {
        EntityPlayerMP p=player();
        if(p.isDead) throw new IllegalArgumentException("player entity is dead; rejoin the client (it is revived on login)");
        if(p.getHealth()<=0) revive(p);
        return p;
    }
    private static void revive(EntityPlayerMP p) {
        p.setHealth(p.getMaxHealth());p.deathTime=0;p.extinguish();
        p.playerNetServerHandler.sendPacket(new S06PacketUpdateHealth(p.getHealth(),p.getFoodStats().getFoodLevel(),p.getFoodStats().getSaturationLevel()));
    }
    @cpw.mods.fml.common.eventhandler.SubscribeEvent public void login(cpw.mods.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent event) {
        if(saved!=null&&event.player instanceof EntityPlayerMP p&&p.getCommandSenderName().equals("ModbenchDev")&&p.getHealth()<=0) revive(p);
    }
    /** Deaths inside the course are recorded, not suffered: a trial that would kill the player leaves them at one
     *  health and counts a fatal event. Respawning mid-run desynchronises the client from the server's player. */
    private int fatal;private String fatalCause="";
    @cpw.mods.fml.common.eventhandler.SubscribeEvent public void death(net.minecraftforge.event.entity.living.LivingDeathEvent event) {
        if(!(event.entityLiving instanceof EntityPlayerMP p)||p.worldObj.isRemote||!inside(p.posX,p.posZ)||!p.getCommandSenderName().equals("ModbenchDev")) return;
        event.setCanceled(true);p.setHealth(1f);fatal++;fatalCause=event.source==null?"":event.source.getDamageType();
    }
    private void require() {if(saved==null) throw new IllegalArgumentException("create movement fixture first");}
    private void write() throws Exception {try(FileOutputStream out=new FileOutputStream(journal)) {CompressedStreamTools.writeCompressed(saved,out);}}
    /** Plot-relative set; flags 2 sends to clients without neighbour updates, 3 also notifies neighbours (liquids flow). */
    private void set(int plot,int u,int y,int v,Block b,int meta,int flags) {
        int px=plot%4,pz=plot/4;world().setBlock(x0+px*16+u,y,z0+pz*16+v,b,meta,flags);
    }
    private void set(int plot,int u,int y,int v,Block b) {set(plot,u,y,v,b,0,2);}
    private void box(int plot,int u0,int y0,int v0,int u1,int y1,int v1,Block b) {
        for(int u=u0;u<=u1;u++) for(int y=y0;y<=y1;y++) for(int v=v0;v<=v1;v++) set(plot,u,y,v,b);
    }
    /** Sets a block without onBlockAdded (no scheduled fall tick): gravel stays over lava, as worldgen leaves it. */
    private void raw(int plot,int u,int y,int v,Block b) {
        int wx=x0+(plot%4)*16+u,wz=z0+(plot/4)*16+v;
        Chunk c=world().getChunkFromBlockCoords(wx,wz);
        ExtendedBlockStorage[] storage=c.getBlockStorageArray();
        if(storage[y>>4]==null) storage[y>>4]=new ExtendedBlockStorage(y>>4<<4,!world().provider.hasNoSky);
        storage[y>>4].func_150818_a(wx&15,y&15,wz&15,b);storage[y>>4].setExtBlockMetadata(wx&15,y&15,wz&15,0);
        c.setChunkModified();world().func_147451_t(wx,y,wz);world().markBlockForUpdate(wx,y,wz);
    }
    private double[] abs(int plot,double u,double y,double v) {return new double[]{x0+(plot%4)*16+u,y,z0+(plot/4)*16+v};}
    private int[] absBlock(int plot,int u,int y,int v) {return new int[]{x0+(plot%4)*16+u,y,z0+(plot/4)*16+v};}

    // ---------------------------------------------------------------- collision measurement
    /** One registered block's measured collision boxes at meta 0, relative to its cell. */
    static final class Shape {
        String id;int meta;List<double[]> boxes=new ArrayList<>();double[] union;double top;double[] topUnion;
        boolean hurts,tile;
        boolean partial() {return !boxes.isEmpty()&&!(boxes.size()==1&&full(boxes.get(0)));}
        static boolean full(double[] b) {return b[0]<=.001&&b[1]<=.001&&b[2]<=.001&&b[3]>=.999&&b[4]>=.999&&b[5]>=.999&&b[4]<=1.001;}
        /** Baritone's current standable rule (P1-1): a top box touching the centre 0.2..0.8 square, top >= 0.8. */
        boolean looksStandable() {
            if(boxes.isEmpty()||top<.8) return false;
            for(double[] b:boxes) if(b[4]>=top-.001&&b[3]>.2&&b[0]<.8&&b[5]>.2&&b[2]<.8) return true;
            return false;
        }
        /** Neighbouring copies leave a gap wider than the 0.6 player footprint somewhere on the top. */
        boolean thinTop() {return looksStandable()&&(1-(topUnion[3]-topUnion[0])>.6||1-(topUnion[5]-topUnion[2])>.6);}
        /** A top in 0.8..0.875: standable to the old rule, but feet cell floor(minY+0.1251) reads one cell low. */
        boolean lowTop() {return looksStandable()&&top>=.8&&top<.8749;}
        Object json() {
            JsonArray b=new JsonArray();for(double[] x:boxes) b.add(Json.array(r(x[0]),r(x[1]),r(x[2]),r(x[3]),r(x[4]),r(x[5])));
            return Json.object("id",id,"meta",meta,"top",r(top),"boxes",b,"standableToOldRule",looksStandable(),"thinTop",thinTop(),"lowTop",lowTop(),"hurtsOnContact",hurts,"tileEntity",tile);
        }
        static double r(double v) {return Math.round(v*10000)/10000.0;}
    }
    private static boolean overrides(Block b,String name,Class<?>... args) {
        try {return b.getClass().getMethod(name,args).getDeclaringClass()!=Block.class;} catch(Exception e) {return false;}
    }
    private Shape measureAt(Block b,int meta,int wx,int wy,int wz) {
        world().setBlock(wx,wy,wz,b,meta,0);
        Shape s=new Shape();s.id=Block.blockRegistry.getNameForObject(b);s.meta=meta;
        List<AxisAlignedBB> list=new ArrayList<>();
        AxisAlignedBB mask=AxisAlignedBB.getBoundingBox(wx-1,wy-1,wz-1,wx+2,wy+3,wz+2);
        b.addCollisionBoxesToList(world(),wx,wy,wz,mask,list,player());
        double[] u={9,9,9,-9,-9,-9};s.top=-9;
        for(AxisAlignedBB a:list) {
            double[] r={a.minX-wx,a.minY-wy,a.minZ-wz,a.maxX-wx,a.maxY-wy,a.maxZ-wz};s.boxes.add(r);
            for(int i=0;i<3;i++){u[i]=Math.min(u[i],r[i]);u[i+3]=Math.max(u[i+3],r[i+3]);}
            s.top=Math.max(s.top,r[4]);
        }
        s.union=u;double[] t={9,9,9,-9,-9,-9};
        for(double[] r:s.boxes) if(r[4]>=s.top-.001) for(int i=0;i<3;i++){t[i]=Math.min(t[i],r[i]);t[i+3]=Math.max(t[i+3],r[i+3]);}
        s.topUnion=t;
        s.hurts=overrides(b,"onEntityCollidedWithBlock",net.minecraft.world.World.class,int.class,int.class,int.class,Entity.class)
            ||overrides(b,"onEntityWalking",net.minecraft.world.World.class,int.class,int.class,int.class,Entity.class);
        s.tile=b.hasTileEntity(meta);
        return s;
    }
    /** Measures every registered block (meta 0) in a scratch cell far above an unwatched chunk; flag 0 sends nothing. */
    private List<Shape> scan() {
        if(shapes!=null) return shapes;
        int wx=x0-400,wy=250,wz=z0;world().getChunkFromBlockCoords(wx,wz);
        world().setBlock(wx,wy-1,wz,Blocks.stone,0,0);
        List<String> ids=new ArrayList<>();for(Object k:Block.blockRegistry.getKeys()) ids.add(k.toString());
        ids.sort(String::compareTo);
        List<Shape> out=new ArrayList<>();int failed=0,skipped=0;
        for(String id:ids) {
            Block b=(Block)Block.blockRegistry.getObject(id);
            if(b==null||b==Blocks.air||b instanceof BlockLiquid||b instanceof IFluidBlock) continue;
            // A tile entity placed and removed within one call can still reach the tick list and crash the
            // server (Thaumic Exploration's soul brazier did). Tile-entity blocks are counted, not measured.
            try {if(b.hasTileEntity(0)) {skipped++;continue;}} catch(Throwable e) {skipped++;continue;}
            try {Shape s=measureAt(b,0,wx,wy,wz);if(s.partial()) out.add(s);}
            catch(Throwable e) {failed++;}
            finally {try {world().setBlock(wx,wy,wz,Blocks.air,0,0);} catch(Throwable ignored) {}}
        }
        world().setBlock(wx,wy-1,wz,Blocks.air,0,0);
        for(Object e:world().getEntitiesWithinAABB(Entity.class,AxisAlignedBB.getBoundingBox(wx-4,wy-8,wz-4,wx+5,wy+4,wz+5)))
            if(!(e instanceof EntityPlayerMP)) ((Entity)e).setDead();
        saved.setInteger("scanFailed",failed);saved.setInteger("scanSkippedTileEntities",skipped);saved.setInteger("scanned",ids.size());
        shapes=out;return out;
    }
    /** Measured picks; deterministic registry order. Vanilla blocks the owner named are placed on their own lanes. */
    private void pick() throws Exception {
        List<Shape> all=scan();
        List<String> vanillaNamed=List.of("minecraft:fence","minecraft:cobblestone_wall","minecraft:glass_pane","minecraft:stone_slab","minecraft:trapdoor");
        List<Shape> usable=new ArrayList<>();
        for(Shape s:all) if(!s.hurts&&!vanillaNamed.contains(s.id)&&s.union[1]>=-.001&&s.union[4]<=1.6&&!s.id.startsWith("minecraft:")) usable.add(s);
        // Up to four pack partials with distinct measured features, then thin and low tops.
        NBTTagList partials=new NBTTagList(),thin=new NBTTagList(),low=new NBTTagList();
        String[] features={"lowTop","tall","narrow","offset","low"};
        List<String> taken=new ArrayList<>();
        for(String f:features) for(Shape s:usable) {
            if(taken.contains(s.id)||partials.tagCount()>=4) continue;
            double w=Math.min(s.union[3]-s.union[0],s.union[5]-s.union[2]);
            boolean match=switch(f) {
                case "lowTop" -> s.lowTop();
                case "tall" -> s.top>1.001;
                case "narrow" -> w<.5&&s.top>.5;
                case "offset" -> Math.abs((s.union[0]+s.union[3])/2-.5)>.2||Math.abs((s.union[2]+s.union[5])/2-.5)>.2;
                default -> s.top<=.5&&s.top>.05;
            };
            if(match) {NBTTagCompound t=new NBTTagCompound();t.setString("id",s.id);t.setString("feature",f);partials.appendTag(t);taken.add(s.id);break;}
        }
        // Thin tops level with a full block first (a walker sees one flat floor), one per distinct measured top outline.
        List<Shape> thinC=new ArrayList<>();for(Shape s:all) if(!s.hurts&&s.thinTop()) thinC.add(s);
        thinC.sort(java.util.Comparator.comparing((Shape s)->Math.abs(s.top-1)>.01));
        List<String> outlines=new ArrayList<>();
        for(Shape s:thinC) {
            String outline=Math.round((s.topUnion[3]-s.topUnion[0])*1000)+"x"+Math.round((s.topUnion[5]-s.topUnion[2])*1000)+"@"+Math.round(s.top*1000);
            if(thin.tagCount()<2&&!outlines.contains(outline)) {outlines.add(outline);NBTTagCompound t=new NBTTagCompound();t.setString("id",s.id);thin.appendTag(t);}
        }
        // The widest low top: a full floor that still reads one cell low.
        Shape lowest=null;
        for(Shape s:all) if(!s.hurts&&s.lowTop()&&!s.thinTop()&&(lowest==null||Math.min(s.union[3]-s.union[0],s.union[5]-s.union[2])>Math.min(lowest.union[3]-lowest.union[0],lowest.union[5]-lowest.union[2])+1e-6)) lowest=s;
        if(lowest!=null) {NBTTagCompound t=new NBTTagCompound();t.setString("id",lowest.id);low.appendTag(t);}
        saved.setTag("packPartials",partials);saved.setTag("thinTops",thin);saved.setTag("lowTops",low);write();
    }
    private String picked(String key,int i) {NBTTagList l=saved.getTagList(key,10);return i<l.tagCount()?l.getCompoundTagAt(i).getString("id"):null;}
    private static Block block(String id) {return id==null?null:(Block)Block.blockRegistry.getObject(id);}

    // ---------------------------------------------------------------- cases
    /** A case: plot, start (plot-relative feet position), goal (plot-relative block), builder. */
    final class Case {
        final String name;final int plot;double su,sy,sv;float yaw;int gu,gy,gv;final Runnable build;
        double[][] route;double minY=Double.NaN;int[][] bounds;String note="";String mineId;
        Case(String name,int plot,Runnable build) {this.name=name;this.plot=plot;this.build=build;}
        Case start(double u,double y,double v,float yaw) {su=u;sy=y;sv=v;this.yaw=yaw;return this;}
        Case goal(int u,int y,int v) {gu=u;gy=y;gv=v;return this;}
        Case route(double... uv) {route=new double[uv.length/2][];for(int i=0;i<uv.length;i+=2) route[i/2]=new double[]{uv[i],uv[i+1]};return this;}
        Case minY(double y) {minY=y;return this;}
        Case mine(String id,int u0,int y0,int v0,int u1,int y1,int v1) {mineId=id;bounds=new int[][]{{u0,y0,v0},{u1,y1,v1}};return this;}
        Case note(String n) {note=n;return this;}
        Object json() {
            JsonArray r=new JsonArray();if(route!=null) for(double[] p:route){double[] a=abs(plot,p[0],0,p[1]);r.add(Json.array(a[0],a[2]));}
            double[] s=abs(plot,su,sy,sv);int[] g=absBlock(plot,gu,gy,gv);
            JsonObject o=(JsonObject)Json.object("name",name,"plot",plot,"start",Json.array(s[0],s[1],s[2]),"yaw",yaw,"goal",Json.array(g[0],g[1],g[2]),"route",r,
                "plotMin",Json.array(x0+(plot%4)*16,FLOOR,z0+(plot/4)*16),"plotMax",Json.array(x0+(plot%4)*16+15,TOP,z0+(plot/4)*16+15),"note",note);
            if(!Double.isNaN(minY)) o.addProperty("minY",minY);
            if(bounds!=null) {int[] a=absBlock(plot,bounds[0][0],bounds[0][1],bounds[0][2]),b=absBlock(plot,bounds[1][0],bounds[1][1],bounds[1][2]);
                o.add("mine",(JsonObject)Json.object("id",mineId,"bounds",Json.object("min",Json.array(a[0],a[1],a[2]),"max",Json.array(b[0],b[1],b[2]))));}
            return o;
        }
    }
    private Case add(Case c) {cases.put(c.name,c);return c;}

    private void defineCases() {
        // Plot 0: 1-wide, 2-high L corridor: 6 east then 6 north, lit roof.
        add(new Case("corner_l",0,()->{
            box(0,0,B,1,8,B+1,9,Blocks.stone);box(0,0,B+2,1,8,B+2,9,Blocks.glowstone);
            box(0,1,B,8,7,B+1,8,Blocks.air);box(0,7,B,2,7,B+1,8,Blocks.air);
        })).start(1.5,B,8.5,-90).goal(7,B,2).route(1.5,8.5,7.5,8.5,7.5,2.5).note("sprint L-turn; chunk border at u=8/v=8");
        // Plot 1: diagonal lanes, partial block in one side cell of each diagonal step.
        String[] diag={"fence","wall","pane","slab","trapdoor","pack0","pack1","pack2","pack3"};
        for(int i=0;i<diag.length;i++) {
            int lu=(i%3)*5+1,lv=(i/3)*5+1;String kind=diag[i];
            add(new Case("diag_"+kind,1,()->{
                Block p=partialFor(kind);if(p==null) return;
                for(int k=0;k<3;k++) set(1,lu+k+1,B,lv+3-k,p,metaFor(kind),2);
            })).start(lu+.5,B,lv+3.5,-135).goal(lu+3,B,lv).route(lu+.5,lv+3.5,lu+3.5,lv+.5).note(kind);
        }
        // Plot 2: 1-wide L corridors whose inside-corner wall cell is a partial block.
        String[] inside={"fence","wall","pane","pack0"};
        for(int i=0;i<inside.length;i++) {
            int lu=(i%2)*8+1,lv=(i/2)*8+1;String kind=inside[i];
            add(new Case("inside_"+kind,2,()->{
                box(2,lu-1,B,lv-1,lu+4,B+1,lv+5,Blocks.stone);box(2,lu-1,B+2,lv-1,lu+4,B+2,lv+5,Blocks.glowstone);
                box(2,lu,B,lv+4,lu+3,B+1,lv+4,Blocks.air);box(2,lu+3,B,lv,lu+3,B+1,lv+4,Blocks.air);
                Block p=partialFor(kind);if(p!=null) set(2,lu+2,B,lv+3,p,metaFor(kind),2);
            })).start(lu+.5,B,lv+4.5,-90).goal(lu+3,B,lv).route(lu+.5,lv+4.5,lu+3.5,lv+4.5,lu+3.5,lv+.5).note(kind+" at the inside corner");
        }
        // Plot 3: strips floored with measured thin-topped / low-topped blocks, with a solid detour beside them.
        String[] strips={"thin0","thin1","low0"};
        for(int i=0;i<strips.length;i++) {
            int lv=i*5;String kind=strips[i];
            add(new Case("strip_"+kind,3,()->{
                Block t=kind.startsWith("thin")?block(picked("thinTops",kind.charAt(4)-'0')):block(picked("lowTops",0));
                box(3,0,B+2,lv,1,B+2,lv+3,Blocks.glowstone);box(3,8,B+2,lv,9,B+2,lv+3,Blocks.glowstone);
                box(3,2,B+2,lv+3,7,B+2,lv+3,Blocks.glowstone);
                if(t!=null) box(3,2,B+2,lv,7,B+2,lv+1,t);
            })).start(.5,B+3,lv+.5,-90).goal(9,B+3,lv).route(.5,lv+.5,9.5,lv+.5).minY(B+2.5).note(kind+" strip; solid detour at v+3");
        }
        // Plot 4: catalogue #1. 1-wide tunnel (v=6), start alcove at (14,7) beside a 2-block, 4-deep floor hole at (12..13,7).
        Runnable pit=()->{
            box(4,0,B+3,4,15,B+6,8,Blocks.stone);box(4,0,B+6,4,15,B+6,8,Blocks.glowstone);
            box(4,1,B+4,6,14,B+5,6,Blocks.air);box(4,12,B+4,7,14,B+5,7,Blocks.air);
            box(4,11,B,6,14,B+3,8,Blocks.stone);box(4,12,B,7,13,B+3,7,Blocks.air);
        };
        add(new Case("pit_loop",4,pit)).start(14.5,B+4,7.5,90).goal(2,B+4,6).route(14.5,7.5,14.5,6.5,2.5,6.5).minY(B+3.5).note("catalogue #1; no break/place");
        add(new Case("pit_trapped",4,pit)).start(12.5,B,7.5,90).goal(2,B+4,6).note("inside the 4-deep hole: must refuse fast");
        // Plot 5: catalogue #2. Standing at x.300001 against a west wall, overhanging a hole: floor(pos) has no floor.
        Runnable edge=()->{
            box(5,2,B+2,2,10,B+2,10,Blocks.glowstone);set(5,5,B+2,6,Blocks.air);
            box(5,4,B+3,5,4,B+4,6,Blocks.stone);
        };
        add(new Case("edge_start",5,edge)).start(5.300001,B+3,6.01,0).goal(8,B+3,3).route(5.3,6.01,8.5,3.5).minY(B+2.5).note("catalogue #2; start cell (5,6) is air");
        add(new Case("edge_start_across",5,edge)).start(5.300001,B+3,6.01,0).goal(5,B+3,9).route(5.3,6.01,5.5,9.5).minY(B+2.5).note("catalogue #2; goal past the hole");
        // Plot 6: catalogue #3. 1x1 shaft 7 deep, player jammed at x.698 z.699999; goal (+1,+7,+2); break and place.
        add(new Case("shaft_jam",6,()->{
            box(6,4,B,4,10,B+6,10,Blocks.stone);box(6,7,B,7,7,B+6,7,Blocks.air);
        })).start(7.698,B,7.699999,0).goal(8,B+7,9).note("catalogue #3; allow break+place");
        // Plot 7: stairs, 3 up east, landing, turn, 2 up north; approached from the side.
        Runnable stairs=()->{
            set(7,4,B,6,Blocks.stone_stairs,0,2);
            set(7,5,B,6,Blocks.stone);set(7,5,B+1,6,Blocks.stone_stairs,0,2);
            box(7,6,B,6,6,B+1,6,Blocks.stone);set(7,6,B+2,6,Blocks.stone_stairs,0,2);
            box(7,7,B,6,7,B+2,6,Blocks.stone);
            box(7,7,B,5,7,B+2,5,Blocks.stone);set(7,7,B+3,5,Blocks.stone_stairs,3,2);
            box(7,7,B,4,7,B+3,4,Blocks.stone);set(7,7,B+4,4,Blocks.stone_stairs,3,2);
            box(7,6,B,2,8,B+4,3,Blocks.stone);
        };
        add(new Case("stairs_up",7,stairs)).start(4.5,B,8.5,180).goal(7,B+5,2).route(4.5,8.5,4.5,6.5,7.5,6.5,7.5,2.5).note("side approach, landing turn");
        add(new Case("stairs_down",7,stairs)).start(7.5,B+5,2.5,0).goal(4,B,8).route(7.5,2.5,7.5,6.5,4.5,6.5,4.5,8.5).note("descend the same flight");
        // Plot 8: two closed rooms joined by a doorway: wooden door, iron door (no redstone), fence gate.
        String[] doors={"door_wood","door_iron","gate"};
        for(int i=0;i<doors.length;i++) {
            int lv=i*5+1;String kind=doors[i];
            add(new Case(kind,8,()->{
                box(8,0,B,lv-1,8,B+2,lv+3,Blocks.stone);box(8,0,B+3,lv-1,8,B+3,lv+3,Blocks.glowstone);
                box(8,1,B,lv,3,B+2,lv+2,Blocks.air);box(8,5,B,lv,7,B+2,lv+2,Blocks.air);
                if(kind.equals("gate")) {set(8,4,B,lv+1,Blocks.fence_gate,1,2);set(8,4,B+1,lv+1,Blocks.air);}
                else {Block d=kind.equals("door_wood")?Blocks.wooden_door:Blocks.iron_door;set(8,4,B,lv+1,d,0,2);set(8,4,B+1,lv+1,d,8,2);}
            })).start(2.5,B,lv+1.5,-90).goal(6,B,lv+1).route(2.5,lv+1.5,6.5,lv+1.5).note(kind.equals("door_iron")?"no redstone: must refuse fast":"closed");
        }
        // Plot 9: gravel floor left standing over 2-deep lava, a feet-level stone blocks the tunnel above it; break allowed.
        add(new Case("gravel_lava",9,()->{
            box(9,0,B,5,14,B+5,7,Blocks.stone);box(9,0,B+5,5,14,B+5,7,Blocks.glowstone);
            box(9,1,B+3,6,13,B+4,6,Blocks.air);
            box(9,6,B,6,8,B+1,6,Blocks.lava);for(int u=6;u<=8;u++) raw(9,u,B+2,6,Blocks.gravel);
            set(9,7,B+3,6,Blocks.stone);
        })).start(2.5,B+3,6.5,-90).goal(12,B+3,6).route(2.5,6.5,12.5,6.5).minY(B+2.5).note("breaking the blocker updates gravel over lava");
        // Plot 10: catalogue #5. 3-deep pool, player pinned in the NW corner at the bottom; exit onto the east bank.
        add(new Case("underwater_corner",10,()->{
            box(10,3,B,3,8,B+2,8,Blocks.stone);box(10,8,B+2,3,12,B+2,10,Blocks.glowstone);
            box(10,4,B,4,7,B+2,7,Blocks.water);
        })).start(4.300001,B,4.300001,135).goal(10,B+3,6).note("catalogue #5; no break/place");
        // Plot 11: 5-wide corridor; a lava source behind a retaining block at (10,3) opens on change/mid-walk.
        Runnable lava=()->{
            box(11,0,B,2,15,B+1,10,Blocks.stone);box(11,1,B,4,14,B+1,8,Blocks.air);
            set(11,10,B,3,Blocks.lava);set(11,10,B,4,Blocks.stone);
        };
        add(new Case("lava_approach",11,lava)).start(1.5,B,6.5,-90).goal(14,B,6).route(1.5,6.5,14.5,6.5).note("change lava_approach_open at start");
        add(new Case("lava_approach_mid",11,lava)).start(1.5,B,6.5,-90).goal(14,B,6).route(1.5,6.5,14.5,6.5).note("change lava_approach_open when the player reaches u>=8");
        // Plot 12: mine jobs. Beside: lava face-adjacent to the ore. Pocket: gravel above the ore, lava above the gravel.
        add(new Case("mine_lava_beside",12,()->{
            box(12,6,B,1,10,B+3,5,Blocks.stone);set(12,6,B+1,3,Blocks.iron_ore);set(12,7,B+1,3,Blocks.lava);
        })).start(2.5,B,3.5,-90).goal(6,B+1,3).mine("minecraft:iron_ore",0,B,0,15,B+3,6).note("lava behind the ore");
        add(new Case("mine_lava_pocket",12,()->{
            box(12,6,B,9,10,B+4,14,Blocks.stone);set(12,6,B,11,Blocks.iron_ore);set(12,6,B+1,11,Blocks.gravel);set(12,6,B+2,11,Blocks.lava);
        })).start(2.5,B,11.5,-90).goal(6,B,11).mine("minecraft:iron_ore",0,B,8,15,B+4,15).note("gravel over the ore, lava over the gravel");
        // Plot 13: a water current crossing the route; sources at (7..8,2), drain at v=11.
        add(new Case("water_current",13,()->{
            box(13,0,B,0,15,B,15,Blocks.glowstone);box(13,0,B+1,0,15,B+2,15,Blocks.air);
            box(13,6,B+1,1,9,B+2,1,Blocks.stone);box(13,6,B+1,2,6,B+2,4,Blocks.stone);box(13,9,B+1,2,9,B+2,4,Blocks.stone);
            box(13,4,B,11,11,B,11,Blocks.air);
            set(13,7,B+1,2,Blocks.water,0,3);set(13,8,B+1,2,Blocks.water,0,3);
        })).start(1.5,B+1,7.5,-90).goal(14,B+1,7).route(1.5,7.5,14.5,7.5).note("flowing water pushes +v");
        // Plot 14: walk down a flowing-water stream in a 1-wide stepped channel.
        add(new Case("water_stream_down",14,()->{
            box(14,0,B,6,15,B+6,8,Blocks.stone);
            int[] floor={0,0,4,4,4,3,3,2,2,1,1,0,-1,-1,-1};
            for(int u=1;u<=14;u++) {int f=B+floor[u];box(14,u,f+1,7,u,B+6,7,Blocks.air);if(f>=B) set(14,u,f,7,Blocks.stone);}
            box(14,1,B+5,7,1,B+6,7,Blocks.air);set(14,1,B+4,7,Blocks.stone);set(14,1,B+5,7,Blocks.water,0,3);
        })).start(2.5,B+5,7.5,-90).goal(14,B,7).route(2.5,7.5,14.5,7.5).note("source at the top step");
        // Plot 15: 3x3 lava pool, two deep; the bank is level with the air layer above it. Water bucket + pickaxe.
        add(new Case("obsidian",15,()->{
            box(15,2,B,2,12,B+2,12,Blocks.stone);box(15,6,B,6,8,B+1,8,Blocks.lava);box(15,6,B+2,6,8,B+2,8,Blocks.air);
        })).start(5.5,B+3,7.5,-90).goal(6,B+1,7).note("pour, recover, mine; scripted with raw primitives");
        // Plot 16: an enclosed cave room. 5x5 lava pool three deep whose top layer is a patchy obsidian/cobblestone crust
        // flush with the ledge; a roof water source at (5,B+7,4) falls onto the north-west ledge and spreads over the crust.
        Runnable natural=this::naturalRoom;
        add(new Case("obsidian_natural",16,natural)).start(4.5,B+3,8.5,-90).goal(8,B+2,8)
            .mine("minecraft:obsidian",6,B,6,10,B+2,10).minY(B+2.9).note("flow running from the build; collect 4 obsidian");
        add(new Case("obsidian_natural_mid",16,natural)).start(4.5,B+3,8.5,-90).goal(8,B+2,8)
            .mine("minecraft:obsidian",6,B,6,10,B+2,10).minY(B+2.9).note("change natural_flow_on a few seconds in");
        // Plot 17: the hole a mined crust block leaves. A stone shelf at B, a one-deep hole at (5,B,7) holding a water
        // source; wet: a sheet of water sources lies over the shelf around it (the obsidian lake), dry: the shelf is bare.
        add(new Case("water_hole_climb_wet",17,()->waterHole(true))).start(5.5,B,7.5,-90).goal(9,B+1,7).note("Sol round 2: MovementAscend stalled 99 ticks");
        add(new Case("water_hole_climb_dry",17,()->waterHole(false))).start(5.5,B,7.5,-90).goal(9,B+1,7).note("a player in a flooded 1-deep hole");
    }
    private void waterHole(boolean wet) {
        box(17,1,B,3,13,B,11,Blocks.stone);box(17,1,B+1,3,13,B+3,11,Blocks.air);
        if(wet) box(17,2,B+1,4,7,B+1,10,Blocks.water);
        set(17,5,B,7,Blocks.water,0,3);
    }
    /** Plot 16's roof water source cell (plot-relative u,v at B+7). */
    static final int NU=5,NV=4;
    /** Plot 16 crust at B+2 over two layers of lava, u 6..10 by v 6..10: O obsidian, C cobblestone, L open lava. */
    static final String[] CRUST={"OOLOO","OCOLO","OOOOL","LOCOO","OOLLO"};
    private void naturalRoom() {
        box(16,1,B,1,14,B+8,14,Blocks.stone);box(16,2,B+3,2,13,B+7,13,Blocks.air);box(16,2,B+8,2,13,B+8,13,Blocks.glowstone);
        box(16,6,B,6,10,B+2,10,Blocks.lava);
        for(int i=0;i<5;i++) for(int j=0;j<5;j++) {char k=CRUST[j].charAt(i);
            if(k!='L') set(16,6+i,B+2,6+j,k=='O'?Blocks.obsidian:Blocks.cobblestone);}
    }
    private Block partialFor(String kind) {
        return switch(kind) {
            case "fence" -> Blocks.fence; case "wall" -> Blocks.cobblestone_wall; case "pane" -> Blocks.glass_pane;
            case "slab" -> Blocks.stone_slab; case "trapdoor" -> Blocks.trapdoor;
            default -> kind.startsWith("pack")?block(picked("packPartials",kind.charAt(4)-'0')):null;
        };
    }
    private int metaFor(String kind) {return 0;}

    // ---------------------------------------------------------------- lifecycle
    Object create() throws Exception {
        if(saved!=null) throw new IllegalArgumentException("restore existing movement fixture first");
        EntityPlayerMP p=livePlayer();
        if(p.theItemInWorldManager.getGameType()!=net.minecraft.world.WorldSettings.GameType.SURVIVAL) throw new IllegalArgumentException("fixture tests require survival player");
        int x=(((int)Math.floor(p.posX))>>4<<4)-8,z=(((int)Math.floor(p.posZ))>>4<<4)-8;
        for(int cx=x>>4;cx<=(x+SIZE-1)>>4;cx++) for(int cz=z>>4;cz<=(z+SIZE_Z-1)>>4;cz++) world().getChunkFromChunkCoords(cx,cz);
        for(int dx=0;dx<SIZE;dx++) for(int dz=0;dz<SIZE_Z;dz++) for(int y=FLOOR;y<=TOP;y++)
            if(world().getBlock(x+dx,y,z+dz)!=Blocks.air) throw new IllegalArgumentException("fixture volume must be air: "+(x+dx)+","+y+","+(z+dz));
        x0=x;z0=z;
        saved=new NBTTagCompound();saved.setInteger("x",x);saved.setInteger("z",z);saved.setInteger("sizeZ",SIZE_Z);sizeZ=SIZE_Z;saved.setString("uuid",p.getUniqueID().toString());
        NBTTagCompound playerData=new NBTTagCompound();p.writeToNBT(playerData);saved.setTag("player",playerData);
        var rules=world().getGameRules();
        saved.setString("doMobSpawning",rules.getGameRuleStringValue("doMobSpawning"));saved.setString("doFireTick",rules.getGameRuleStringValue("doFireTick"));
        write();
        try {
            rules.setOrCreateGameRule("doMobSpawning","false");rules.setOrCreateGameRule("doFireTick","false");
            for(int dx=0;dx<SIZE;dx++) for(int dz=0;dz<SIZE_Z;dz++) world().setBlock(x+dx,FLOOR,z+dz,Blocks.glowstone,0,2);
            pick();
            for(int plot=0;plot<PLOTS;plot++) rebuild(plot);
            return Json.object("origin",Json.array(x0,FLOOR,z0),"cases",caseList(),"picks",picks());
        } catch(Exception|LinkageError e) {try {restore();} catch(Exception cleanup) {e.addSuppressed(cleanup);} throw e;}
    }
    private Object caseList() {JsonArray a=new JsonArray();for(Case c:cases.values()) a.add(Json.GSON.toJsonTree(c.json()));return a;}
    private Object picks() {
        return Json.object("packPartials",Json.GSON.toJsonTree(nbtIds("packPartials")),"thinTops",Json.GSON.toJsonTree(nbtIds("thinTops")),"lowTops",Json.GSON.toJsonTree(nbtIds("lowTops")),
            "scanned",saved.getInteger("scanned"),"scanFailed",saved.getInteger("scanFailed"),"scanSkippedTileEntities",saved.getInteger("scanSkippedTileEntities"));
    }
    private List<String> nbtIds(String key) {List<String> out=new ArrayList<>();NBTTagList l=saved.getTagList(key,10);for(int i=0;i<l.tagCount();i++) out.add(l.getCompoundTagAt(i).getString("id")+(l.getCompoundTagAt(i).hasKey("feature")?" ("+l.getCompoundTagAt(i).getString("feature")+")":""));return out;}
    /** Clears one plot (fluids first, no neighbour updates), relays the catch floor and builds every case on it. */
    private void rebuild(int plot) {
        clearEntities(plot);
        for(int y=TOP;y>FLOOR;y--) for(int u=0;u<16;u++) for(int v=0;v<16;v++) set(plot,u,y,v,Blocks.air);
        for(int u=0;u<16;u++) for(int v=0;v<16;v++) set(plot,u,FLOOR,v,Blocks.glowstone);
        List<Runnable> done=new ArrayList<>();
        for(Case c:cases.values()) if(c.plot==plot&&!done.contains(c.build)) {c.build.run();done.add(c.build);}
        clearEntities(plot);
    }
    private AxisAlignedBB plotBox(int plot) {
        int wx=x0+(plot%4)*16,wz=z0+(plot/4)*16;return AxisAlignedBB.getBoundingBox(wx,FLOOR-2,wz,wx+16,TOP+2,wz+16);
    }
    private int clearEntities(int plot) {
        int n=0;
        for(Object o:world().getEntitiesWithinAABB(Entity.class,plotBox(plot))) {
            Entity e=(Entity)o;if(e instanceof EntityItem||e instanceof EntityFallingBlock||e instanceof EntityXPOrb) {e.setDead();n++;}
        }
        return n;
    }
    private ItemStack pickaxe() throws Exception {
        ItemStack tool=FluidFixture.buildTool("pickaxeHead",FluidFixture.toolMaterial("Cobalt"),"Movement course pickaxe",true);
        if(tool==null||tool.getItem().getHarvestLevel(tool,"pickaxe")<Blocks.obsidian.getHarvestLevel(0)) throw new IllegalStateException("GTNH ToolBuilder did not produce an obsidian-capable pickaxe");
        return tool;
    }
    private void resetPlayer(EntityPlayerMP p,String name) throws Exception {
        p.closeScreen();p.inventory.setItemStack(null);p.mountEntity(null);p.extinguish();p.clearActivePotions();
        p.setHealth(p.getMaxHealth());p.setAir(300);p.fallDistance=0;p.motionX=p.motionY=p.motionZ=0;
        NBTTagCompound food=new NBTTagCompound();p.getFoodStats().writeNBT(food);food.setInteger("foodLevel",20);food.setFloat("foodSaturationLevel",5);food.setFloat("foodExhaustionLevel",0);p.getFoodStats().readNBT(food);
        for(int i=0;i<p.inventory.mainInventory.length;i++) p.inventory.mainInventory[i]=null;
        p.inventory.mainInventory[0]=pickaxe();
        p.inventory.mainInventory[1]=new ItemStack(Blocks.cobblestone,64);
        if(name.startsWith("obsidian")) p.inventory.mainInventory[2]=new ItemStack(Items.water_bucket);
        if(name.startsWith("obsidian_natural")) p.inventory.mainInventory[3]=new ItemStack(Items.bucket);
        p.inventory.currentItem=0;
        p.playerNetServerHandler.sendPacket(new S09PacketHeldItemChange(0));
        p.inventoryContainer.detectAndSendChanges();p.sendContainerAndContentsToPlayer(p.inventoryContainer,p.inventoryContainer.getInventory());
        p.playerNetServerHandler.sendPacket(new S06PacketUpdateHealth(p.getHealth(),p.getFoodStats().getFoodLevel(),p.getFoodStats().getSaturationLevel()));
    }
    /** Rebuilds the case's plot, resets the player and places them at the start. yaw (degrees) overrides the default. */
    Object position(String name,JsonObject params) throws Exception {
        require();Case c=cases.get(name);if(c==null) throw new IllegalArgumentException("unknown movement case; see status.cases");
        EntityPlayerMP p=livePlayer();
        if(!p.getUniqueID().toString().equals(saved.getString("uuid"))) throw new IllegalArgumentException("fixture player mismatch");
        fatal=0;fatalCause="";
        rebuild(c.plot);if(name.equals("obsidian_natural")) change("natural_flow_on");
        resetPlayer(p,name);synchronized(items) {items.clear();broken.clear();dropped.clear();}
        float yaw=params.has("yaw")?(float)Json.number(params,"yaw",c.yaw,-360,360):c.yaw;
        double[] s=abs(c.plot,c.su,c.sy,c.sv);
        p.playerNetServerHandler.setPlayerLocation(s[0],s[1],s[2],yaw,params.has("pitch")?(float)Json.number(params,"pitch",0,-90,90):0);
        JsonObject out=(JsonObject)c.json();out.addProperty("yaw",yaw);
        String kind=name.substring(name.indexOf('_')+1);
        if((name.startsWith("diag_")||name.startsWith("inside_"))&&partialFor(kind)==null
            ||name.startsWith("strip_")&&(kind.startsWith("thin")?picked("thinTops",kind.charAt(4)-'0'):picked("lowTops",0))==null)
            out.addProperty("missing","no measured block for this lane in the pack");
        if(name.startsWith("strip_")||name.startsWith("diag_")||name.startsWith("inside_")) out.add("measured",Json.GSON.toJsonTree(measuredCells(c)));
        return out;
    }
    /** The partial/thin blocks actually placed for a case, measured in place (connections included). */
    private List<Object> measuredCells(Case c) {
        List<Object> out=new ArrayList<>();
        for(int u=0;u<16;u++) for(int v=0;v<16;v++) for(int y=B;y<=B+2;y++) {
            int[] a=absBlock(c.plot,u,y,v);Block b=world().getBlock(a[0],a[1],a[2]);
            if(b==Blocks.air||b==Blocks.stone||b==Blocks.glowstone) continue;
            List<AxisAlignedBB> list=new ArrayList<>();
            b.addCollisionBoxesToList(world(),a[0],a[1],a[2],AxisAlignedBB.getBoundingBox(a[0]-1,a[1]-1,a[2]-1,a[0]+2,a[1]+3,a[2]+2),list,player());
            JsonArray boxes=new JsonArray();
            for(AxisAlignedBB x:list) boxes.add(Json.array(Shape.r(x.minX-a[0]),Shape.r(x.minY-a[1]),Shape.r(x.minZ-a[2]),Shape.r(x.maxX-a[0]),Shape.r(x.maxY-a[1]),Shape.r(x.maxZ-a[2])));
            if(out.size()<24) out.add(Json.object("pos",Json.array(a[0],a[1],a[2]),"id",Block.blockRegistry.getNameForObject(b),"meta",world().getBlockMetadata(a[0],a[1],a[2]),"boxes",boxes));
        }
        return out;
    }
    Object change(String name) throws Exception {
        require();
        switch(name) {
            case "lava_approach_open" -> set(11,10,B,4,Blocks.air,0,3);
            case "lava_approach_close" -> set(11,10,B,4,Blocks.stone,0,3);
            case "natural_flow_on" -> set(16,NU,B+7,NV,Blocks.water,0,3);
            case "natural_flow_off" -> set(16,NU,B+7,NV,Blocks.glowstone,0,3);
            default -> throw new IllegalArgumentException("unknown movement change: lava_approach_open|lava_approach_close|natural_flow_on|natural_flow_off");
        }
        return Json.object("changed",name);
    }
    /** Authoritative player state, plus items/falling blocks and liquids in one case's plot when {name} is given. */
    Object status(JsonObject params) {
        require();EntityPlayerMP p=player();
        JsonArray inv=new JsonArray();
        for(int i=0;i<p.inventory.mainInventory.length;i++) {ItemStack s=p.inventory.mainInventory[i];
            if(s!=null) inv.add(Json.GSON.toJsonTree(Json.object("slot",i,"id",Item.itemRegistry.getNameForObject(s.getItem()),"meta",s.getItemDamage(),"count",s.stackSize)));}
        JsonObject out=(JsonObject)Json.object("origin",Json.array(x0,FLOOR,z0),"pos",Json.array(p.posX,p.posY,p.posZ),"health",p.getHealth(),"burning",p.isBurning(),
            "fireTicks",fireTicks(p),"air",p.getAir(),"inWater",p.isInWater(),"inLava",p.handleLavaMovement(),"onGround",p.onGround,"dead",p.isDead||p.getHealth()<=0,"fatal",fatal,"fatalCause",fatalCause,"inventory",inv,
            "serverTick",server.getTickCounter());
        String name=Json.string(params,"name","");
        if(name.isEmpty()) {out.add("cases",Json.GSON.toJsonTree(caseList()));out.add("picks",Json.GSON.toJsonTree(picks()));return out;}
        Case c=cases.get(name);if(c==null) throw new IllegalArgumentException("unknown movement case");
        JsonArray entities=new JsonArray();
        for(Object o:world().getEntitiesWithinAABB(Entity.class,plotBox(c.plot))) {
            Entity e=(Entity)o;if(e instanceof EntityPlayerMP) continue;
            JsonObject j=(JsonObject)Json.object("type",net.minecraft.entity.EntityList.getEntityString(e),"pos",Json.array(e.posX,e.posY,e.posZ),"burning",e.isBurning(),"dead",e.isDead,"age",e.ticksExisted);
            if(e instanceof EntityItem item&&item.getEntityItem()!=null) {j.addProperty("item",Item.itemRegistry.getNameForObject(item.getEntityItem().getItem()));j.addProperty("count",item.getEntityItem().stackSize);}
            entities.add(j);
        }
        JsonArray fluids=new JsonArray();
        for(int u=0;u<16;u++) for(int v=0;v<16;v++) for(int y=FLOOR+1;y<=B+8;y++) {
            int[] a=absBlock(c.plot,u,y,v);Block b=world().getBlock(a[0],a[1],a[2]);
            if(b instanceof BlockLiquid||b==Blocks.obsidian||(b==Blocks.cobblestone||b==Blocks.stone&&c.plot==16&&y<=B+2&&u>=6&&u<=10&&v>=6&&v<=10)&&c.plot>=15||b==Blocks.gravel)
                fluids.add(Json.array(a[0],a[1],a[2],Block.blockRegistry.getNameForObject(b),world().getBlockMetadata(a[0],a[1],a[2])));
        }
        out.add("entities",entities);out.add("blocks",fluids);out.add("items",Json.GSON.toJsonTree(itemFates()));
        synchronized(items) {out.add("broken",Json.GSON.toJsonTree(new LinkedHashMap<>(broken)));out.add("dropped",Json.GSON.toJsonTree(new LinkedHashMap<>(dropped)));}
        return out;
    }
    private static int fireTicks(Entity e) {
        try {java.lang.reflect.Field f=Entity.class.getDeclaredField("field_70151_c");f.setAccessible(true);return f.getInt(e);}
        catch(Exception ignored) {try {java.lang.reflect.Field f=Entity.class.getDeclaredField("fire");f.setAccessible(true);return f.getInt(e);} catch(Exception none) {return -1;}}
    }
    /** Every measured partial block (for the report); the scan runs once per fixture. */
    Object shapes(JsonObject params) {
        require();String filter=Json.string(params,"filter","picked");
        JsonArray a=new JsonArray();
        for(Shape s:scan()) {
            boolean keep=switch(filter) {case "thin" -> s.thinTop(); case "low" -> s.lowTop(); case "all" -> true; default -> nbtIds("packPartials").stream().anyMatch(i->i.startsWith(s.id+" "))||nbtIds("thinTops").contains(s.id)||nbtIds("lowTops").contains(s.id);};
            if(keep&&a.size()<Json.integer(params,"limit",200,1,5000)) a.add(Json.GSON.toJsonTree(s.json()));
        }
        return Json.object("count",scan().size(),"shapes",a,"picks",picks());
    }
    Object restore() throws Exception {
        if(saved==null) return Json.object("restored",false);
        EntityPlayerMP p=livePlayer();
        if(!p.getUniqueID().toString().equals(saved.getString("uuid"))) throw new IllegalArgumentException("fixture player mismatch");
        p.closeScreen();p.inventory.setItemStack(null);
        p.readFromNBT(saved.getCompoundTag("player"));p.motionX=p.motionY=p.motionZ=0;
        p.playerNetServerHandler.setPlayerLocation(p.posX,p.posY,p.posZ,p.rotationYaw,p.rotationPitch);
        p.inventoryContainer.detectAndSendChanges();
        p.playerNetServerHandler.sendPacket(new S09PacketHeldItemChange(p.inventory.currentItem));
        p.sendPlayerAbilities();
        p.playerNetServerHandler.sendPacket(new S06PacketUpdateHealth(p.getHealth(),p.getFoodStats().getFoodLevel(),p.getFoodStats().getSaturationLevel()));
        for(int plot=0;plot<PLOTS;plot++) clearEntities(plot);
        for(int dx=0;dx<SIZE;dx++) for(int dz=0;dz<sizeZ;dz++) for(int y=TOP;y>=FLOOR;y--)
            if(world().getBlock(x0+dx,y,z0+dz)!=Blocks.air) world().setBlock(x0+dx,y,z0+dz,Blocks.air,0,2);
        for(int plot=0;plot<PLOTS;plot++) clearEntities(plot);
        var rules=world().getGameRules();
        if(saved.hasKey("doMobSpawning")) rules.setOrCreateGameRule("doMobSpawning",saved.getString("doMobSpawning"));
        if(saved.hasKey("doFireTick")) rules.setOrCreateGameRule("doFireTick",saved.getString("doFireTick"));
        if(!journal.delete()) throw new IllegalStateException("could not remove movement fixture journal");
        synchronized(items) {items.clear();broken.clear();dropped.clear();}
        saved=null;shapes=null;return Json.object("restored",true);
    }
}
