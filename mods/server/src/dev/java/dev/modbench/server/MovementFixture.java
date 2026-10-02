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
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.monster.EntityZombie;
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
    static final int PLOTS=40, SIZE=64, SIZE_Z=16*((PLOTS+3)/4), FLOOR=199, TOP=218, B=200;
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
        Case c=armed;
        if(c!=null) try {
            EntityPlayerMP p=player();int[] t=c.mobTrigger;
            double u=p.posX-(x0+(c.plot%4)*16),v=p.posZ-(z0+(c.plot/4)*16);
            if(u>=t[0]&&u<t[2]+1&&v>=t[1]&&v<t[3]+1&&p.posY>=t[4]) {armed=null;mobReleased=server.getTickCounter();releaseMob(c);}
        } catch(Exception ignored) {}
    }
    /** Every hit the player took in the course since the last position: cause, amount, where. */
    private final List<JsonObject> hurts=new ArrayList<>();
    @cpw.mods.fml.common.eventhandler.SubscribeEvent public void hurt(net.minecraftforge.event.entity.living.LivingHurtEvent event) {
        if(!(event.entityLiving instanceof EntityPlayerMP p)||p.worldObj.isRemote||!inside(p.posX,p.posZ)||!p.getCommandSenderName().equals("ModbenchDev")) return;
        synchronized(items) {if(hurts.size()<200) hurts.add((JsonObject)Json.object("type",event.source==null?"":event.source.getDamageType(),"amount",event.ammount,
            "tick",server.getTickCounter(),"health",p.getHealth(),"pos",Json.array(Shape.r(p.posX),Shape.r(p.posY),Shape.r(p.posZ))));}
    }
    /** A case with a mob runs at normal difficulty (peaceful removes hostiles and zeroes their damage); every other
     *  case puts back what the world had. Set on the world object only, so a restart is peaceful again. */
    private net.minecraft.world.EnumDifficulty difficultyBefore;
    private volatile Case armed;private int mobReleased=-1;
    private void hostile(boolean on) {
        WorldServer w=world();
        if(on) {if(difficultyBefore==null) difficultyBefore=w.difficultySetting;w.difficultySetting=net.minecraft.world.EnumDifficulty.NORMAL;}
        else if(difficultyBefore!=null) {w.difficultySetting=difficultyBefore;difficultyBefore=null;}
    }
    /** A plain adult zombie, added the way spawnEntityInWorld adds one minus EntityJoinWorldEvent: Infernal Mobs and
     *  Special Mobs re-roll mobs there (modifiers, subtype), which would make every trial a different fight. */
    private void releaseMob(Case c) throws Exception {
        double[] a=abs(c.plot,c.mob[0],c.mob[1],c.mob[2]);
        EntityZombie z=new EntityZombie(world());z.setPosition(a[0],a[1],a[2]);z.func_110163_bv();
        z.rotationYaw=z.rotationYawHead=90;
        world().getChunkFromBlockCoords((int)Math.floor(a[0]),(int)Math.floor(a[2])).addEntity(z);
        world().loadedEntityList.add(z);
        java.lang.reflect.Method added=null;
        for(String n:new String[]{"func_72923_a","onEntityAdded"}) try {added=net.minecraft.world.World.class.getDeclaredMethod(n,Entity.class);break;} catch(NoSuchMethodException ignored) {}
        if(added==null) throw new IllegalStateException("World.onEntityAdded not found");
        added.setAccessible(true);added.invoke(world(),z);
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
        /** Plots the case covers eastward from its own (u runs 0..16*span-1); a mob it releases and the cells that release it. */
        int span=1;double[] mob;int[] mobTrigger;
        Case(String name,int plot,Runnable build) {this.name=name;this.plot=plot;this.build=build;}
        Case span(int n) {span=n;return this;}
        /** A zombie at (u,y,v), released when the player stands in u0..u1 x v0..v1 at or above y0 (plot-relative). */
        Case mob(double u,double y,double v,int u0,int v0,int u1,int v1,int y0) {mob=new double[]{u,y,v};mobTrigger=new int[]{u0,v0,u1,v1,y0};return this;}
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
                "plotMin",Json.array(x0+(plot%4)*16,FLOOR,z0+(plot/4)*16),"plotMax",Json.array(x0+(plot%4)*16+16*span-1,TOP,z0+(plot/4)*16+15),"note",note);
            if(!Double.isNaN(minY)) o.addProperty("minY",minY);
            if(bounds!=null) {int[] a=absBlock(plot,bounds[0][0],bounds[0][1],bounds[0][2]),b=absBlock(plot,bounds[1][0],bounds[1][1],bounds[1][2]);
                JsonArray drops=new JsonArray();Block mined=block(mineId);  // what the game says the block drops, for counting
                if(mined!=null) for(ItemStack d:mined.getDrops(world(),a[0],a[1],a[2],0,0)) if(d!=null) drops.add(new com.google.gson.JsonPrimitive(Item.itemRegistry.getNameForObject(d.getItem())));
                o.add("mine",(JsonObject)Json.object("id",mineId,"drops",drops,"bounds",Json.object("min",Json.array(a[0],a[1],a[2]),"max",Json.array(b[0],b[1],b[2]))));}
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
        // Plot 18: the agent's snags of 2026-10-01. A stone ridge one high whose u=8 cell is a double tall grass on grass:
        // the goal is the plant's top cell, so the ridge must be bridged into the plant's bottom cell, and the plant hides
        // the face to place against. Lane v=13: the player starts overhanging that cell from the ridge (feet in the goal,
        // held up by the cell beside it). Lane v=9: the player stands inside a tall plant (eyes in its top half) with
        // leaves at head height in the next cell of a walled lane.
        add(new Case("bridge_plant",18,()->plantRidge(3))).start(2.5,B+1,3.5,-90).goal(8,B+1,3).route(2.5,3.5,8.5,3.5).note("s_trav_49_65_64 / 63_66_19");
        add(new Case("overhang_plant",18,()->plantRidge(13))).start(8.2,B+1,13.5,-90).goal(8,B+1,13).note("feet in the goal, pathStart beside it");
        add(new Case("head_plant_leaves",18,()->{
            box(18,2,B,8,8,B+2,8,Blocks.stone);box(18,2,B,10,8,B+2,10,Blocks.stone);box(18,1,B,9,1,B+2,9,Blocks.stone);
            set(18,3,B-1,9,Blocks.grass);set(18,3,B,9,Blocks.double_plant,2,2);set(18,3,B+1,9,Blocks.double_plant,8,2);
            set(18,4,B+1,9,Blocks.leaves,4,2);
        })).start(3.7,B,9.5,-90).goal(7,B,9).route(3.7,9.5,7.5,9.5).note("s_trav_49_64_62: pressed against the leaves, eyes inside the plant top");
        // Plot 19: a stone ridge two high with a two-deep gap at u=8, bridged on the way to u=12. The change drops the player
        // into the gap, too deep to jump out of, while a traverse along the ridge is running (s_trav_64_65_55).
        add(new Case("bridge_drop",19,()->{
            box(19,0,B,7,15,B+1,7,Blocks.stone);box(19,8,B,7,8,B+1,7,Blocks.air);
        })).start(2.5,B+2,7.5,-90).goal(12,B+2,7).route(2.5,7.5,12.5,7.5).note("change bridge_drop_fall at u>=6");
        // Plots 20-21: gaps across a 1-wide stone ridge three high (top at B+2). A fall into a gap lands three below with no
        // way back up without placing, so with break and place off a jump is the only way across.
        add(new Case("gap1",20,()->gapRidge(20,1,7,7,0))).start(1.5,B+3,1.5,-90).goal(14,B+3,1).route(1.5,1.5,14.5,1.5).note("1-wide gap");
        add(new Case("gap2",20,()->gapRidge(20,4,7,8,0))).start(1.5,B+3,4.5,-90).goal(14,B+3,4).route(1.5,4.5,14.5,4.5).note("2-wide gap");
        add(new Case("gap3",20,()->gapRidge(20,7,7,9,0))).start(1.5,B+3,7.5,-90).goal(14,B+3,7).route(1.5,7.5,14.5,7.5).note("3-wide gap: a sprint jump");
        add(new Case("gap1_up",20,()->gapRidge(20,10,7,7,1))).start(1.5,B+3,10.5,-90).goal(14,B+4,10).route(1.5,10.5,14.5,10.5).note("1-wide gap to a ridge one higher");
        add(new Case("gap2_place",20,()->gapRidge(20,13,7,8,0))).start(1.5,B+3,13.5,-90).goal(14,B+3,13).route(1.5,13.5,14.5,13.5).note("2-wide gap, placing allowed: jump or bridge");
        add(new Case("gap2_lava",21,()->{
            gapRidge(21,3,7,8,0);box(21,7,B,2,8,B,4,Blocks.stone);for(int u=7;u<=8;u++) set(21,u,B,3,Blocks.lava);
        })).start(1.5,B+3,3.5,-90).goal(14,B+3,3).route(1.5,3.5,14.5,3.5).note("2-wide gap over still lava two below the takeoff");
        liquidCases();
        add(new Case("gap2_turn",21,()->{
            box(21,0,B,9,8,B+2,9,Blocks.stone);box(21,8,B,9,8,B+2,15,Blocks.stone);box(21,8,B,11,8,B+2,12,Blocks.air);
        })).start(1.5,B+3,9.5,-90).goal(8,B+3,14).route(1.5,9.5,8.5,9.5,8.5,14.5).note("a corner then a 2-wide gap");
    }
    private void liquidCases() {
        // Plots 22-23: dive for clay. A still 3x3 water pit h deep in a stone mass (bank top at B+h); its floor centre is
        // clay. The job mines it, then swims back to the bank (the case goal): a drowning shows as lost health.
        add(new Case("dive_clay5",22,()->clayPit(22,5))).start(3.5,B+6,7.5,-90).goal(3,B+6,7).mine("minecraft:clay",6,B,6,8,B+5,8).note("5-deep pit");
        add(new Case("dive_clay10",23,()->clayPit(23,10))).start(3.5,B+11,7.5,-90).goal(3,B+11,7).mine("minecraft:clay",6,B,6,8,B+10,8).note("10-deep pit");
        // Plot 24: a flooded U. Stone mass top at B+5 over the whole plot, a wall on top at u=7..8; the only way across is
        // down a 1x1 water shaft at u=3, along a 2-high flooded tunnel, and up the shaft at u=12.
        add(new Case("swim_u",24,()->{
            box(24,0,B,0,15,B+5,15,Blocks.stone);box(24,7,B+6,0,8,B+8,15,Blocks.stone);
            box(24,3,B+1,7,3,B+5,7,Blocks.water);box(24,3,B+1,7,12,B+2,7,Blocks.water);box(24,12,B+1,7,12,B+5,7,Blocks.water);
        })).start(1.5,B+6,7.5,-90).goal(14,B+6,7).note("down a water shaft, a 9-long flooded tunnel, up a shaft");
        // Plot 25: an L of roofed corridors (4 wide east, 5 wide north) with three lava sources against the inside wall of
        // the north leg, flowed out before the start: the dry way is a 1-2 wide strip around the lava's stepped edge.
        add(new Case("lava_corner",25,()->{
            roofed(25);box(25,1,B,1,12,B+1,4,Blocks.air);box(25,8,B,1,12,B+1,14,Blocks.air);
            for(int v=5;v<=9;v+=2) set(25,8,B,v,Blocks.flowing_lava,0,3); // still lava would not spread until a neighbour changed
        })).start(1.5,B,2.5,-90).goal(11,B,13).note("settled flowing lava; dry strip at v=1 past u=8, then u=11..12");
        // Plot 26: a roofed room with three lava sources on the anti-diagonal, flowed out into one stepped wall; the dry
        // ways round are 1-wide strips along the room's walls.
        add(new Case("lava_wall",26,()->{
            roofed(26);box(26,1,B,1,14,B+1,14,Blocks.air);
            set(26,7,B,7,Blocks.flowing_lava,0,3);set(26,10,B,5,Blocks.flowing_lava,0,3);set(26,5,B,10,Blocks.flowing_lava,0,3);
        })).start(2.5,B,2.5,-135).goal(12,B,12).note("settled flowing lava wall across the diagonal");
        hardLiquidCases();
        Runnable gauntlet=this::gauntlet;
        add(new Case("liquid_gauntlet",GP,gauntlet)).span(3).start(1.5,B+5,3.5,-90).goal(3,B+13,10)
            .mob(31.5,B+12,11.5,30,8,41,10,B+11)
            .note("plots 37-39: lava ledge + gap, current into a lava curtain, 28-block flooded tunnel with one air pocket, lava-moat jump,"
                +" waterfall climb, 2-high wet corridor with a pothole, zombie hall round a lava pool, gravel-into-water dam, five jumps over a pool;"
                +" human route in the gauntlet() comment");
        // Stage starts on the same build, for probing one stage at a time (not in the course's case table).
        double[][] at={{12.5,B+5,3.5,-90},{28.5,B+1,4.5,-90},{45.5,B+6,3.5,0},{45.5,B+6,7.5,0},{43.5,B+12,10.5,90},{28.5,B+12,13.5,90},{16.5,B+12,13.5,90}};
        String[] st={"s2","s3","s4","s5","s6","s8","s9"};
        for(int i=0;i<st.length;i++) add(new Case("liquid_gauntlet@"+st[i],GP,gauntlet)).span(3).start(at[i][0],at[i][1],at[i][2],(float)at[i][3])
            .goal(3,B+13,10).mob(31.5,B+12,11.5,30,8,41,10,B+11).note("stage start of liquid_gauntlet");
    }
    /** Plots 27-36: deliberately adversarial water and lava, each a different attack a real world makes. Every one is
     *  passable by a careful human with only the case's kit; the notes say how. Flowing sources go last (flags 3). */
    private void hardLiquidCases() {
        // Plot 27: the 6-deep clay pit with a walled channel (v=6..8, walls one high at v=5,9 and u=2) across its mouth at
        // B+7, fed by three sources at u=3: the mouth carries an eastward current (levels 3..5) off the pit.
        add(new Case("dive_clay_current",27,()->{
            clayPit(27,6);box(27,2,B+7,5,13,B+7,5,Blocks.stone);box(27,2,B+7,9,13,B+7,9,Blocks.stone);box(27,2,B+7,6,2,B+7,8,Blocks.stone);
            for(int v=6;v<=8;v++) set(27,3,B+7,v,Blocks.flowing_water,0,3);
        })).start(4.5,B+7,11.5,180).goal(4,B+7,11).mine("minecraft:clay",6,B,6,8,B+7,8)
            .note("current across a 6-deep pit mouth; human: hop the wall upstream, dive, dig, surface at the west edge, climb out against the flow");
        // Plot 28: a 1x1 hole 4 deep in a stone bank (top B+4), clay at its floor, and a source collared in stone under a
        // beam at B+7 pouring a waterfall into it: the hole is falling water that pushes down and drowns a head.
        add(new Case("dive_clay_falls",28,()->{
            box(28,2,B,2,13,B+4,13,Blocks.stone);box(28,7,B+1,7,7,B+4,7,Blocks.air);set(28,7,B,7,Blocks.clay);
            box(28,7,B+8,3,7,B+8,7,Blocks.stone);box(28,6,B+7,6,8,B+7,8,Blocks.stone);
            set(28,7,B+7,7,Blocks.flowing_water,0,3);
        })).start(3.5,B+5,7.5,-90).goal(3,B+5,7).mine("minecraft:clay",6,B,6,8,B+7,8)
            .note("clay under a waterfall in a 1x1 hole; human: drop in, dig the clay underfoot, hold jump up the falls, step out");
        // Plot 29: the 6-deep clay pit with the clay buried under a 3-high gravel column (each dig drops the next through
        // the water) and a gravel lid over the mouth's east row, set raw so it hangs over water until something disturbs it.
        add(new Case("dive_clay_gravel",29,()->{
            clayPit(29,6);box(29,7,B+1,7,7,B+3,7,Blocks.gravel);
            for(int v=6;v<=8;v++) raw(29,8,B+6,v,Blocks.gravel);
        })).start(3.5,B+7,7.5,-90).goal(3,B+7,7).mine("minecraft:clay",6,B,6,8,B+7,8)
            .note("clay under 3 gravel in a 6-deep pit, hanging gravel lid; human: dive at the west row, dig the gravel top-down, dig clay");
        // Plot 30: a stone mass (top B+6) with a still pond (u 6..8, v 5..7, B+4..B+6) in it. The clay at (7,B+3,6) is the
        // pond's floor and the ceiling of a dry 3-high sump (B..B+2), reached by a 1-wide stair down from v=13 and a
        // 2-high stub at v=7. Mining the clay opens the pond: a waterfall fills the sump where the drops land.
        add(new Case("dive_clay_flooding",30,()->{
            box(30,0,B,0,15,B+6,15,Blocks.stone);box(30,6,B+4,5,8,B+6,7,Blocks.water);set(30,7,B+3,6,Blocks.clay);
            box(30,7,B,6,7,B+2,6,Blocks.air);box(30,7,B+1,7,7,B+2,7,Blocks.air);
            for(int k=0;k<=5;k++) box(30,7,B+1+k,8+k,7,B+3+k,8+k,Blocks.air);  // stair: feet at B+1 (v=8) .. B+6 (v=13)
        })).start(7.5,B+7,14.5,180).goal(7,B+7,14).mine("minecraft:clay",6,B,5,8,B+3,8)
            .note("clay is a pond's floor over a dry sump; human: dig it from the v=7 stub (stay out of the falls), duck into the sump for the drops, step out, climb the stair");
        // Plot 31: a walled floor (feet B+1). Lane C1 (v=4..5, curbs one high at v=3,6) runs a current east from two
        // sources at u=1, wet to u=8. Wall W (v=9, 4 high) has a dry door at u=2 (3 high: a 2-high one leaves no headroom to jump the curb behind it) and a waterfall curtain door at u=13
        // (source in a collared lintel at B+3, a drain under the door). Lane C2 (v=11..12, curbs v=10,13) runs west from
        // sources at u=14, wet to u=7. Dry route: v=1 east to u>=9, over C1, v=7 west, door u=2, over C2's dry end, v=14 east.
        add(new Case("water_maze",31,()->{
            box(31,0,B,0,15,B,15,Blocks.stone);
            box(31,0,B+1,0,15,B+3,0,Blocks.stone);box(31,0,B+1,15,15,B+3,15,Blocks.stone);
            box(31,0,B+1,0,0,B+3,15,Blocks.stone);box(31,15,B+1,0,15,B+3,15,Blocks.stone);
            for(int v:new int[]{3,6,10,13}) box(31,1,B+1,v,14,B+1,v,Blocks.stone);
            box(31,1,B+1,9,14,B+4,9,Blocks.stone);box(31,2,B+1,9,2,B+3,9,Blocks.air);
            box(31,13,B,9,13,B+2,9,Blocks.air);set(31,13,B+3,8,Blocks.stone);set(31,13,B+3,10,Blocks.stone);
            for(int v=4;v<=5;v++) set(31,1,B+1,v,Blocks.flowing_water,0,3);
            for(int v=11;v<=12;v++) set(31,14,B+1,v,Blocks.flowing_water,0,3);
            set(31,13,B+3,9,Blocks.flowing_water,0,3);
        })).start(1.5,B+1,1.5,-90).goal(14,B+1,14)
            .note("currents and a curtain across a walled floor; human: take the dry weave (C1 at u>=9, door u=2, C2 at u<=6) or wade the currents");
        // Plot 32: stone mass top B+5; the goal sits in a walled courtyard (u>=10, v>=9, walls 3 high). The only way in is
        // down a water shaft at (2,2), a 2-high flooded serpentine (v=2, u=13, v=7, u=2, v=12: ~45 cells) and up a shaft
        // at (13,12): ~53 blocks underwater, lethal in one breath. Three single-cell air pockets in the tunnel ceiling
        // (B+3) split it into legs of 13-14 blocks.
        add(new Case("swim_pocket",32,()->{
            box(32,0,B,0,15,B+5,15,Blocks.stone);
            box(32,10,B+6,9,15,B+8,9,Blocks.stone);box(32,10,B+6,9,10,B+8,15,Blocks.stone);
            box(32,2,B+1,2,2,B+5,2,Blocks.water);box(32,13,B+1,12,13,B+5,12,Blocks.water);
            box(32,2,B+1,2,13,B+2,2,Blocks.water);box(32,13,B+1,2,13,B+2,7,Blocks.water);box(32,2,B+1,7,13,B+2,7,Blocks.water);
            box(32,2,B+1,7,2,B+2,12,Blocks.water);box(32,2,B+1,12,13,B+2,12,Blocks.water);
            set(32,10,B+3,2,Blocks.air);set(32,8,B+3,7,Blocks.air);set(32,4,B+3,12,Blocks.air);
        })).start(1.5,B+6,2.5,-90).goal(13,B+6,14)
            .note("53-block flooded serpentine with ceiling air pockets at (10,2),(8,7),(4,12); human: swim leg to leg, rise into each pocket for a breath");
        // Plot 33: stone mass top B+7 under a parapet; on top, 1-wide lanes at odd v joined by gaps at alternate ends of
        // the walls between them: a ~97-block dry walk. Underneath, a pocketless flooded shortcut: a 7-deep shaft at (14,1),
        // a 2-high tunnel west along v=1 and south along u=1, a 7-deep shaft up at (1,13): ~39 blocks underwater, past one
        // breath. By path cost the swim is the cheaper route; minY marks any dive.
        add(new Case("swim_long_dry_detour",33,()->{
            box(33,0,B,0,15,B+7,15,Blocks.stone);
            box(33,0,B+8,0,15,B+10,0,Blocks.stone);box(33,0,B+8,15,15,B+10,15,Blocks.stone);
            box(33,0,B+8,0,0,B+10,15,Blocks.stone);box(33,15,B+8,0,15,B+10,15,Blocks.stone);
            for(int k=1;k<=6;k++) {box(33,1,B+8,2*k,14,B+10,2*k,Blocks.stone);int g=k%2==1?1:14;box(33,g,B+8,2*k,g,B+10,2*k,Blocks.air);}
            box(33,14,B+1,1,14,B+7,1,Blocks.water);box(33,1,B+1,13,1,B+7,13,Blocks.water);
            box(33,1,B+1,1,14,B+2,1,Blocks.water);box(33,1,B+1,1,1,B+2,13,Blocks.water);
        })).start(13.5,B+8,1.5,90).goal(2,B+8,13).minY(B+7.5)
            .note("39-block pocketless flooded shortcut beside a 97-block dry lane walk; human: walk the lanes");
        // Plot 34: a roofed corridor (u 1..14, v 4..8) with lava sources at (3,8),(7,8),(10,8) and a water source at (14,8)
        // behind a divider (u 12..14, v=6). Water reaches (10,8) first (obsidian), meets (7,8)'s flow (cobble seams) and
        // leaves weak lava beside it; lava reaches v=5 at u=3 and u=7. Settled, v=4 is a dry strip past two lava pockets,
        // a shallow wet stretch at u=10..11, then dry behind the divider.
        add(new Case("lava_water_mix",34,()->{
            roofed(34);box(34,1,B,4,14,B+1,8,Blocks.air);box(34,12,B,6,14,B+1,6,Blocks.stone);
            for(int u:new int[]{3,7,10}) set(34,u,B,8,Blocks.flowing_lava,0,3);
            set(34,14,B,8,Blocks.flowing_water,0,3);
        })).start(1.5,B,4.5,-90).goal(14,B,4)
            .note("settled lava/water mix (obsidian, cobble, weak lava beside water); human: hug the north wall (v=4), past the lava edges at u=3,7, through the shallow water");
        // Plot 35: a 6-high cliff (v>=8, top B+5) across the plot. The only way up: a 2-high doorway at (7,v=8) into a
        // 1x1 still water column (7,B..B+5,9). On top, a channel (walls one high at u=6,8, end wall v=14) carries a
        // current north from a source at (7,13) over the column's mouth and off the lip as a waterfall in front of the
        // doorway, which spreads over the low ground.
        add(new Case("waterfall_climb",35,()->{
            box(35,0,B,8,15,B+5,15,Blocks.stone);box(35,7,B,8,7,B+1,8,Blocks.air);box(35,7,B,9,7,B+5,9,Blocks.water);
            box(35,6,B+6,8,6,B+6,13,Blocks.stone);box(35,8,B+6,8,8,B+6,13,Blocks.stone);box(35,6,B+6,14,8,B+6,14,Blocks.stone);
            set(35,7,B+6,13,Blocks.flowing_water,0,3);
        })).start(1.5,B,0.5,0).goal(11,B+6,12)
            .note("swim up a 6-high 1x1 water column behind a waterfall, exit into a current that pushes back off the lip; human: hold jump, then forward (south) and over the channel wall");
        // Plot 36: a stepped stone slope (v 2..13) falling east one block every two u, top B+6 at u=0..1 to B at u>=12,
        // with a flat bank (v 11..13, top B+6). A channel (v 6..8, walls one high at v=5,9, end walls u=0,15) carries a
        // stream down it from three sources at u=1. The clay is the streambed at (8,B+2,7): its drops wash downstream.
        add(new Case("dive_clay_stream",36,()->{
            for(int u=0;u<16;u++) {int t=Math.max(0,6-u/2);box(36,u,B,2,u,B+t,13,Blocks.stone);set(36,u,B+t+1,5,Blocks.stone);set(36,u,B+t+1,9,Blocks.stone);}
            box(36,0,B,11,15,B+6,13,Blocks.stone);box(36,0,B+7,6,0,B+7,8,Blocks.stone);box(36,15,B+1,6,15,B+1,8,Blocks.stone);
            set(36,8,B+2,7,Blocks.clay);
            for(int v=6;v<=8;v++) set(36,1,B+7,v,Blocks.flowing_water,0,3);
        })).start(1.5,B+7,12.5,0).goal(1,B+7,12).mine("minecraft:clay",7,B+1,6,9,B+3,8)
            .note("clay as the bed of a stepped stream; human: walk down the v=10 steps, hop in, dig, chase the drops to the u=14 end, climb the steps back");
    }
    /** The liquid gauntlet's first plot; it spans plots 37..39, so u runs 0..47 (east) and v 0..15 (south). */
    static final int GP=37;
    private void g(int u,int y,int v,Block b,int meta,int flags) {set(GP,u,y,v,b,meta,flags);}
    private void gbox(int u0,int y0,int v0,int u1,int y1,int v1,Block b) {box(GP,u0,y0,v0,u1,y1,v1,b);}
    private void gfall(int u,int y0,int y1,int v,Block flowing) {for(int y=y0;y<=y1;y++) g(u,y,v,flowing,8,2);}  // a pre-filled fall
    /**
     * One long adversarial course in a bedrock block (u 0..47, v 0..15, FLOOR..B+17). Bedrock wherever digging must not
     * route round a hazard; the only breakable blocks are the dam (stone + gravel) and a few glowstone lamps in walls
     * that open onto nothing. Kit: pickaxe and cobblestone; the case allows break, not place. Y below is B+k, feet levels.
     * Lane N runs east (v 1..6), lane S runs west (v 8..14).
     *  S1 lava ledge, feet Y5: a 1-wide ledge (v=3, u 3..12) between a lava trench fed by two lava falls (v=2, falls at
     *     u=6,11, 0.5 from the walking line) and a lava pit (v=4, lava 3 below). A 2-gap (u 8..9) opens into the pit.
     *     Human: walk the centre line, walk-jump 7 -> 10 (4 high, so a full jump).
     *  S2 current into lava, feet Y1 after three 1-block steps down (u 13..15): a 12x3 room of shallow water flowing
     *     south from sources along v=2 into a solid lava curtain (falling lava, v=5); the way out is a 1-wide door at
     *     (28, v=4), beside the curtain. The push is 0.014/tick against 0.02/tick of swim thrust. Human: hold the north
     *     wall with forward+left, cut into the door from the north-west.
     *  S3 flooded tunnel, 2 high, from the door: 8 blocks (v=4 to u=34, south 2) to a one-cell ceiling air pocket at
     *     (34,Y3,6), then 20 more (east along v=6 to u=40, north to v=1, east to u=44, up the 1x1 shaft at u=45 to Y5):
     *     ~205-230 ticks at 0.1 b/t against 300 ticks of air. Human: rise into the pocket, then swim without stopping.
     *  S4 lava moat: out of the shaft onto a 1-wide runway (u=45, v 2..4, feet Y6), a 2-gap (v 5..6) over lava with a
     *     lava fall at (46, v=5) 0.5 from the jump line, landing on a 1x2 ledge (45, v 7..8; v=8 is the overshoot cell).
     *  S5 waterfall: step into a 1x1 falling-water column (45, v=9), swim up 7 to the source at Y12; the exit south is a
     *     2-high corridor (feet Y12, ceiling Y14), so the feet must be in Y12.0..12.2 to slide out (the ceiling caps it).
     *  S6 2-high wet corridor (v=10, u 45 -> 39): the source's current runs west into a water-filled pothole at u=42;
     *     dropping in means climbing out under the 2-high ceiling (the hole's own column is 3 high, so it can be done).
     *  S7 zombie hall (u 30..38, v 8..14, feet Y12, 3 high): a 3x3 lava pool in the floor; a plain zombie appears at
     *     (31.5, 11.5) beside the exit door (29, v=13) when the player is in the corridor west of u=42. Normal
     *     difficulty: 3 per hit. Human: draw it round the pool and outpace it to the door (zombie ~2.3 b/s, player 4.3),
     *     or kill it with the pickaxe.
     *  S8 dam (u=26, v=13): a stone at head height over a 2-deep still water hole, two gravel above the stone. Breaking
     *     the stone drops both gravel through the water: one fills the hole's bottom, one stands at feet level, a 1-step
     *     under a 2-high ceiling, so it must be dug too (gravel with a pickaxe: 18 ticks dry). The zombie, if left
     *     alive, catches up here. Then a 2-high corridor to u=16.
     *  S9 pool room, open sky: from a 1x1 notch (16, v=13, feet Y12) five jumps over a 2-deep pool on 1x1 pillars:
     *     2-gap W to (13,13), 2-gap N to (13,10), 1-gap W and 1 up to (11,10), 2-gap W to (8,10), 2-gap W onto the
     *     1-wide goal strip (2..5, v=10, feet Y13). A miss is not death: the pool's walls are 2 above the water, so swim
     *     through (16, v=12) to the 1x1 shaft at (17, v=12), climb back into the corridor and try again.
     */
    private void gauntlet() {
        final int Y=B;
        gbox(0,FLOOR,0,47,Y+17,15,Blocks.bedrock);
        // S1
        gbox(1,Y+5,2,2,Y+8,4,Blocks.air);gbox(3,Y+5,3,12,Y+8,3,Blocks.air);
        gbox(3,Y+4,2,12,Y+8,2,Blocks.air);gbox(3,Y+2,4,12,Y+8,4,Blocks.air);gbox(3,Y+1,4,12,Y+1,4,Blocks.lava);
        gbox(8,Y+2,3,9,Y+4,3,Blocks.air);gbox(8,Y+1,3,9,Y+1,3,Blocks.lava);
        gbox(13,Y+4,3,13,Y+7,3,Blocks.air);gbox(14,Y+3,3,14,Y+6,3,Blocks.air);gbox(15,Y+2,3,15,Y+5,3,Blocks.air);
        // S2 room and S3 tunnel (still water: nothing moves until something beside it changes)
        gbox(16,Y+1,2,27,Y+4,4,Blocks.air);gbox(28,Y+1,4,28,Y+2,4,Blocks.air);
        gbox(29,Y+1,4,34,Y+2,4,Blocks.water);gbox(34,Y+1,5,34,Y+2,6,Blocks.water);g(34,Y+3,6,Blocks.air,0,2);
        gbox(35,Y+1,6,40,Y+2,6,Blocks.water);gbox(40,Y+1,1,40,Y+2,5,Blocks.water);gbox(41,Y+1,1,44,Y+2,1,Blocks.water);
        gbox(45,Y+1,1,45,Y+5,1,Blocks.water);
        // S4 runway, moat, landing; S5 column top
        gbox(45,Y+6,1,45,Y+9,4,Blocks.air);gbox(45,Y+3,5,46,Y+9,6,Blocks.air);gbox(45,Y+2,5,46,Y+2,6,Blocks.lava);
        gbox(45,Y+6,7,45,Y+9,8,Blocks.air);g(45,Y+13,9,Blocks.air,0,2);
        // S6 corridor and pothole, S7 hall and its pool
        gbox(39,Y+12,10,45,Y+13,10,Blocks.air);g(42,Y+11,10,Blocks.air,0,2);
        gbox(30,Y+12,8,38,Y+14,14,Blocks.air);gbox(33,Y+11,10,35,Y+11,12,Blocks.lava);
        // S8 dam and the corridor beyond
        gbox(27,Y+12,13,29,Y+13,13,Blocks.air);gbox(26,Y+11,13,26,Y+12,13,Blocks.water);g(26,Y+13,13,Blocks.stone,0,2);
        gbox(26,Y+14,13,26,Y+15,13,Blocks.gravel);gbox(17,Y+12,13,25,Y+13,13,Blocks.air);
        // S9 notch, pool room, pillars, way back
        gbox(16,Y+12,13,16,Y+17,13,Blocks.air);
        gbox(2,Y+10,9,15,Y+17,14,Blocks.air);gbox(2,Y+8,9,15,Y+9,14,Blocks.water);
        gbox(13,Y+8,13,13,Y+11,13,Blocks.bedrock);gbox(13,Y+8,10,13,Y+11,10,Blocks.bedrock);
        gbox(11,Y+8,10,11,Y+12,10,Blocks.bedrock);gbox(8,Y+8,10,8,Y+12,10,Blocks.bedrock);gbox(2,Y+8,10,5,Y+12,10,Blocks.bedrock);
        gbox(16,Y+8,12,16,Y+9,12,Blocks.water);gbox(17,Y+8,12,17,Y+11,12,Blocks.water);gbox(17,Y+12,12,17,Y+13,12,Blocks.air);
        // lamps in walls that open onto nothing
        for(int[] l:new int[][]{{32,Y+2,5},{38,Y+2,5},{42,Y+2,2},{46,Y+8,3},{44,Y+9,8},{44,Y+13,11},{21,Y+13,14}}) g(l[0],l[1],l[2],Blocks.glowstone,0,2);
        // moving liquids last: falls pre-filled (falling meta 8) so nothing else can take their cells first
        for(int u:new int[]{6,11}) {gfall(u,Y+4,Y+8,2,Blocks.flowing_lava);g(u,Y+9,2,Blocks.flowing_lava,0,3);}
        for(int u=16;u<=27;u++) {gfall(u,Y,Y+4,5,Blocks.flowing_lava);g(u,Y+5,5,Blocks.flowing_lava,0,3);}
        gfall(46,Y+3,Y+9,5,Blocks.flowing_lava);g(46,Y+10,5,Blocks.flowing_lava,0,3);
        gfall(45,Y+5,Y+11,9,Blocks.flowing_water);g(45,Y+12,9,Blocks.flowing_water,0,3);
        for(int u=16;u<=27;u++) g(u,Y+1,2,Blocks.flowing_water,0,3);
    }
    private void clayPit(int p,int h) {
        box(p,2,B,2,13,B+h,13,Blocks.stone);box(p,6,B+1,6,8,B+h,8,Blocks.water);set(p,7,B,7,Blocks.clay);
    }
    /** Plot p: solid stone two high under a glowstone roof; cases carve their rooms out of it. */
    private void roofed(int p) {
        box(p,0,B,0,15,B+1,15,Blocks.stone);box(p,0,B+2,0,15,B+2,15,Blocks.glowstone);
    }
    /** Plot p, lane v: a ridge u=0..15 (top at B+2, or B+2+up past the gap) with air at u=g0..g1. */
    private void gapRidge(int p,int v,int g0,int g1,int up) {
        box(p,0,B,v,g0-1,B+2,v,Blocks.stone);box(p,g1+1,B,v,15,B+2+up,v,Blocks.stone);
    }
    private void plantRidge(int v) {
        box(18,0,B,v,15,B,v,Blocks.stone);
        set(18,8,B-1,v,Blocks.grass);set(18,8,B,v,Blocks.double_plant,2,2);set(18,8,B+1,v,Blocks.double_plant,8,2);
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
        for(Case c:cases.values()) if(c.plot<plot&&plot<c.plot+c.span) return;  // built (and cleared) by the case spanning it
        int span=1;for(Case c:cases.values()) if(c.plot==plot) span=Math.max(span,c.span);
        for(int p=plot;p<plot+span;p++) {
            clearEntities(p);
            for(int y=TOP;y>FLOOR;y--) for(int u=0;u<16;u++) for(int v=0;v<16;v++) set(p,u,y,v,Blocks.air);
            for(int u=0;u<16;u++) for(int v=0;v<16;v++) set(p,u,FLOOR,v,Blocks.glowstone);
        }
        List<Runnable> done=new ArrayList<>();
        for(Case c:cases.values()) if(c.plot==plot&&!done.contains(c.build)) {c.build.run();done.add(c.build);}
        for(int p=plot;p<plot+span;p++) clearEntities(p);
    }
    private AxisAlignedBB plotBox(int plot) {return plotBox(plot,1);}
    private AxisAlignedBB plotBox(int plot,int span) {
        int wx=x0+(plot%4)*16,wz=z0+(plot/4)*16;return AxisAlignedBB.getBoundingBox(wx,FLOOR-2,wz,wx+16*span,TOP+2,wz+16);
    }
    /** Items, falling blocks, xp and any mob a case released. */
    private int clearEntities(int plot) {
        int n=0;
        for(Object o:world().getEntitiesWithinAABB(Entity.class,plotBox(plot))) {
            Entity e=(Entity)o;
            if(e instanceof EntityItem||e instanceof EntityFallingBlock||e instanceof EntityXPOrb||e instanceof EntityLiving) {e.setDead();n++;}
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
        if(name.startsWith("dive_")) p.inventory.mainInventory[2]=FluidFixture.buildTool("shovelHead",FluidFixture.toolMaterial("Cobalt"),"Movement course shovel",false);
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
        fatal=0;fatalCause="";armed=null;mobReleased=-1;
        rebuild(c.plot);if(name.equals("obsidian_natural")) change("natural_flow_on");
        hostile(c.mob!=null);
        resetPlayer(p,name);synchronized(items) {items.clear();broken.clear();dropped.clear();hurts.clear();}
        armed=c.mob!=null?c:null;
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
            case "bridge_drop_fall" -> {EntityPlayerMP p=livePlayer();double[] a=abs(19,8.5,B,7.5);
                p.playerNetServerHandler.setPlayerLocation(a[0],a[1],a[2],p.rotationYaw,p.rotationPitch);}
            default -> throw new IllegalArgumentException("unknown movement change: lava_approach_open|lava_approach_close|natural_flow_on|natural_flow_off|bridge_drop_fall");
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
            "serverTick",server.getTickCounter(),"difficulty",world().difficultySetting.name(),"mobReleasedTick",mobReleased);
        synchronized(items) {JsonArray h=new JsonArray();for(JsonObject j:hurts) h.add(j);out.add("hurts",h);}
        String name=Json.string(params,"name","");
        if(name.isEmpty()) {out.add("cases",Json.GSON.toJsonTree(caseList()));out.add("picks",Json.GSON.toJsonTree(picks()));return out;}
        Case c=cases.get(name);if(c==null) throw new IllegalArgumentException("unknown movement case");
        JsonArray entities=new JsonArray();
        for(Object o:world().getEntitiesWithinAABB(Entity.class,plotBox(c.plot,c.span))) {
            Entity e=(Entity)o;if(e instanceof EntityPlayerMP) continue;
            JsonObject j=(JsonObject)Json.object("type",net.minecraft.entity.EntityList.getEntityString(e),"pos",Json.array(e.posX,e.posY,e.posZ),"burning",e.isBurning(),"dead",e.isDead,"age",e.ticksExisted);
            if(e instanceof EntityItem item&&item.getEntityItem()!=null) {j.addProperty("item",Item.itemRegistry.getNameForObject(item.getEntityItem().getItem()));j.addProperty("count",item.getEntityItem().stackSize);}
            entities.add(j);
        }
        JsonArray fluids=new JsonArray();
        for(int u=0;u<16*c.span;u++) for(int v=0;v<16;v++) for(int y=FLOOR+1;y<=(c.span>1?TOP:B+8);y++) {
            int[] a=absBlock(c.plot,u,y,v);Block b=world().getBlock(a[0],a[1],a[2]);
            if(b instanceof BlockLiquid||b==Blocks.obsidian||(b==Blocks.cobblestone||b==Blocks.stone&&c.plot==16&&y<=B+2&&u>=6&&u<=10&&v>=6&&v<=10)&&c.plot>=15||b==Blocks.gravel
                ||c.span>1&&b==Blocks.stone)
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
        armed=null;hostile(false);
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
