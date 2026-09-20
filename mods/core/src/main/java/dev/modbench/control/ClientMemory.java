// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.control;

import com.google.gson.Gson;
import dev.modbench.api.MemoryAccess;
import dev.modbench.api.WorldMemory;
import dev.modbench.api.WorldMemory.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.util.MovingObjectPosition;

/** Shared memory and protection policy for every ModdedBench input owner. Game-thread only. */
public final class ClientMemory implements MemoryAccess {
    static final ClientMemory INSTANCE=new ClientMemory();
    private static final Minecraft MC=Minecraft.getMinecraft();
    private static String worldId,scope,lastAudit;
    private static boolean blockedThisTick;
    private static WorldMemory memory;
    private static Path file;
    private static String recordingName,recordingScope,recordingError;
    private static boolean recordingReplace;
    private static double recordingRadius;
    private static final List<Pos> recording=new ArrayList<>();
    private ClientMemory() {}
    public void bind(String id) {
        ClientControls.requireGameThread();String valid=UUID.fromString(id).toString();
        if(!Objects.equals(worldId,valid)) {worldId=valid;memory=null;scope=null;}
    }
    public void disconnected() {
        ClientControls.requireGameThread();worldId=null;memory=null;scope=null;lastAudit=null;
        if(recordingName!=null) recordingError="connection_changed";
    }
    public WorldMemory memory() {
        ClientControls.requireGameThread();
        if(worldId==null || MC.theWorld==null || MC.thePlayer==null) throw new IllegalStateException("server world identity unavailable; connect to the ModdedBench server");
        String address=MC.func_147104_D()==null?"integrated":MC.func_147104_D().serverIP.toLowerCase(Locale.ROOT);
        String wanted=address+"|"+worldId+"|"+MC.theWorld.provider.dimensionId;
        try {
            if(memory==null || !wanted.equals(scope)) {
                String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(wanted.getBytes(StandardCharsets.UTF_8)));
                file=MC.mcDataDir.toPath().resolve("modbench/memory").resolve(hash+".json");
                memory=new WorldMemory(file,wanted);scope=wanted;
            }
            return memory;
        } catch(Exception error) {throw new IllegalStateException("world memory unavailable: "+error.getMessage(),error);}
    }
    public Map<String,Object> context() {
        memory();return Map.of("worldId",worldId,"dimension",MC.theWorld.provider.dimensionId,"pos",feet().list());
    }
    public Map<String,Object> status() {
        var data=memory();var state=data.snapshot();Map<String,Object> out=new LinkedHashMap<>();
        out.put("scope",scope);out.put("worldId",worldId);out.put("dimension",MC.theWorld.provider.dimensionId);out.put("revision",state.revision());
        out.put("waypoints",state.waypoints());out.put("regions",state.regions());
        out.put("routes",state.routes().values().stream().sorted(Comparator.comparing(Route::name))
            .map(r->Map.of("name",r.name(),"points",r.points().size(),"radius",r.radius(),"start",r.points().get(0).list(),"end",r.points().get(r.points().size()-1).list())).toList());
        out.put("file",file.toString());out.put("recording",recordingStatus());return out;
    }
    public Pos feet() {return new Pos((int)Math.floor(MC.thePlayer.posX),(int)Math.floor(MC.thePlayer.boundingBox.minY+.001),(int)Math.floor(MC.thePlayer.posZ));}
    public Map<String,Object> record(String action,String name,boolean replace,double radius) throws Exception {
        if(action.equals("start")) {
            WorldMemory.validName(name);memory();
            if(recordingName!=null) throw new IllegalArgumentException("a route recording is already active");
            if(memory.snapshot().routes().containsKey(name)&&!replace) throw new IllegalArgumentException("route exists; replace:true required");
            if(!Double.isFinite(radius)||radius<1||radius>16) throw new IllegalArgumentException("radius must be 1..16");
            recordingName=name;recordingScope=scope;recordingReplace=replace;recordingRadius=radius;recordingError=null;recording.clear();sample();
        } else if(action.equals("stop")) {
            if(recordingName==null) throw new IllegalArgumentException("no active recording");
            memory();if(!scope.equals(recordingScope)) recordingError="world_or_dimension_changed";
            if(recordingError!=null) throw new IllegalArgumentException("recording incomplete: "+recordingError+"; cancel and start a new recording");
            memory.route(new Route(recordingName,WorldMemory.compact(recording),recordingRadius),recordingReplace);recordingName=null;recording.clear();
        } else if(action.equals("cancel")) {recordingName=null;recording.clear();recordingError=null;}
        else if(!action.equals("status")) throw new IllegalArgumentException("record action must be start, stop, cancel or status");
        return recordingStatus();
    }
    public void sample() {
        if(recordingName==null || recordingError!=null) return;
        try {
            memory();if(!scope.equals(recordingScope)) {recordingError="world_or_dimension_changed";return;}
            Pos p=feet();
            if(recording.isEmpty()||!recording.get(recording.size()-1).equals(p)) {
                if(recording.size()>=16384) {recordingError="recording_sample_limit";return;}
                recording.add(p);
            }
        } catch(RuntimeException error) {recordingError=error.getMessage();}
    }
    private static Map<String,Object> recordingStatus() {
        Map<String,Object> out=new LinkedHashMap<>();out.put("active",recordingName!=null);out.put("name",recordingName);out.put("samples",recording.size());out.put("error",recordingError);return out;
    }
    /** Null means permitted. No global override is stored; callers must carry their operation's flag. */
    public String editProblem(int x,int y,int z,boolean override,boolean automated) {
        try {
            List<String> regions=memory().snapshot().protectedAt(new Pos(x,y,z),automated);
            return !override&&!regions.isEmpty()?"protected_region:"+String.join(",",regions):null;
        } catch(RuntimeException error) {return "protection_unavailable:"+error.getMessage();}
    }
    public void endTick() {blockedThisTick=false;}
    /** Called immediately before vanilla block editing; includes raw synthetic attack/use input. */
    public boolean blockAction(int action,int x,int y,int z,int side) {
        if(action==0&&!ClientControls.allowBlockAttack(x,y,z))return false;
        if(blockedThisTick) return false; // Vanilla may fall back from right-click to sendUseItem in this same tick.
        var owner=ClientControls.INSTANCE.arbiter().current();if(!owner.active()) return true;
        try {
            List<Pos> affected=new ArrayList<>();
            if(action==2) {
                // Arbitrary mod items may use either ray, including in-place
                // NBT containers. Item class/use animation cannot prove no edits.
                double reach=MC.playerController.getBlockReachDistance();
                addRay(affected,MC.thePlayer.rayTrace(reach,1));
                var eye=MC.thePlayer.getPosition(1);var look=MC.thePlayer.getLook(1);
                addRay(affected,MC.theWorld.rayTraceBlocks(eye,eye.addVector(look.xCoord*reach,look.yCoord*reach,look.zCoord*reach),true));
                if(affected.isEmpty())affected.add(feet());
            } else addAffected(affected,x,y,z,action==0?-1:side);
            TreeSet<String> regions=new TreeSet<>();for(Pos pos:affected) regions.addAll(memory().snapshot().protectedAt(pos,owner.automatedEdits()));
            if(regions.isEmpty()) return true;
            if(!owner.overrideProtection()) {blockedThisTick=true;ClientControls.revoke("protected_region:"+String.join(",",regions));MC.playerController.resetBlockRemoving();return false;}
            String key=owner.operationId()+"|"+owner.label()+"|"+action+"|"+affected+"|"+regions;
            if(!key.equals(lastAudit)) {
                Map<String,Object> receipt=Map.of("timeMs",System.currentTimeMillis(),"owner",owner.label(),"operationId",owner.operationId(),"action",action,"positions",affected,"regions",regions,"overrideProtection",true);
                Files.writeString(file.resolveSibling(file.getFileName()+".overrides.jsonl"),new Gson().toJson(receipt)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                lastAudit=key;
            }
            return true;
        } catch(Exception error) {blockedThisTick=true;ClientControls.revoke("protection_unavailable:"+error.getMessage());return false;}
    }
    private static void addRay(List<Pos> affected,MovingObjectPosition hit) {
        if(hit!=null&&hit.typeOfHit==MovingObjectPosition.MovingObjectType.BLOCK) addAffected(affected,hit.blockX,hit.blockY,hit.blockZ,hit.sideHit);
    }
    private static void addAffected(List<Pos> affected,int x,int y,int z,int side) {
        if(y>=0&&y<=255)affected.add(new Pos(x,y,z));
        if(side>=0&&side<6) {
            int[][] directions={{0,-1,0},{0,1,0},{0,0,-1},{0,0,1},{-1,0,0},{1,0,0}};
            int[] d=directions[side];if(y+d[1]>=0&&y+d[1]<=255)affected.add(new Pos(x+d[0],y+d[1],z+d[2]));
        }
    }
}
