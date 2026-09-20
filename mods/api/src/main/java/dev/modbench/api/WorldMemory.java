// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.api;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** World/dimension-scoped route knowledge and edit policy; no Minecraft dependency. */
public final class WorldMemory {
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    public record Pos(int x,int y,int z) {
        public Pos {
            if(Math.abs((long)x)>30000000 || Math.abs((long)z)>30000000 || y<0 || y>255)
                throw new IllegalArgumentException("position outside world bounds");
        }
        public List<Integer> list() {return List.of(x,y,z);}
    }
    public record Region(String name,Pos min,Pos max,String mode) {
        public Region(String name,Pos min,Pos max) {this(name,min,max,"automation");}
        public Region {
            validName(name);Objects.requireNonNull(min);Objects.requireNonNull(max);
            if(!Set.of("automation","all_edits").contains(mode)) throw new IllegalArgumentException("region mode must be automation or all_edits");
            if(min.x>max.x || min.y>max.y || min.z>max.z) throw new IllegalArgumentException("region min must not exceed max");
        }
        public boolean contains(Pos p) {return p.x>=min.x&&p.x<=max.x&&p.y>=min.y&&p.y<=max.y&&p.z>=min.z&&p.z<=max.z;}
    }
    public record Route(String name,List<Pos> points,double radius) {
        public Route {
            validName(name);points=List.copyOf(points);
            if(points.size()<2 || points.size()>4096 || !Double.isFinite(radius) || radius<1 || radius>16)
                throw new IllegalArgumentException("route requires 2..4096 points and corridor radius 1..16");
        }
    }
    public record Snapshot(long revision,Map<String,Pos> waypoints,Map<String,Route> routes,Map<String,Region> regions) {
        public Snapshot {waypoints=Map.copyOf(waypoints);routes=Map.copyOf(routes);regions=Map.copyOf(regions);}
        public List<String> protectedAt(Pos pos) {return protectedAt(pos,true);}
        public List<String> protectedAt(Pos pos,boolean automated) {
            return regions.values().stream().filter(r->r.contains(pos)&&(automated||r.mode.equals("all_edits"))).map(Region::name).sorted().toList();
        }
    }
    private final Path file;
    private final String scope;
    private Snapshot state=new Snapshot(0,Map.of(),Map.of(),Map.of());
    public WorldMemory(Path file,String scope) throws IOException {
        this.file=file;this.scope=Objects.requireNonNull(scope);
        if(!Files.notExists(file)) load(); // Invalid policy never silently becomes an empty policy.
    }
    public Snapshot snapshot() {return state;}
    public String scope() {return scope;}
    public void waypoint(String name,Pos pos,boolean replace) throws IOException {
        validName(name);Objects.requireNonNull(pos);
        Map<String,Pos> values=new TreeMap<>(state.waypoints);
        if(values.containsKey(name)&&!replace) throw new IllegalArgumentException("waypoint exists; replace:true required");
        values.put(name,pos);commit(values,state.routes,state.regions);
    }
    public void route(Route route,boolean replace) throws IOException {
        Map<String,Route> values=new TreeMap<>(state.routes);
        if(values.containsKey(route.name)&&!replace) throw new IllegalArgumentException("route exists; replace:true required");
        values.put(route.name,route);commit(state.waypoints,values,state.regions);
    }
    public void protect(Region region,boolean overrideProtection) throws IOException {
        Map<String,Region> values=new TreeMap<>(state.regions);
        if(values.containsKey(region.name)&&!overrideProtection)
            throw new IllegalArgumentException("changing a protected region requires overrideProtection:true");
        values.put(region.name,region);commit(state.waypoints,state.routes,values);
    }
    public void remove(String kind,String name,boolean overrideProtection) throws IOException {
        validName(name);
        Map<String,Pos> waypoints=new TreeMap<>(state.waypoints);Map<String,Route> routes=new TreeMap<>(state.routes);Map<String,Region> regions=new TreeMap<>(state.regions);
        Object removed=switch(kind) {
            case "waypoint" -> waypoints.remove(name);
            case "route" -> routes.remove(name);
            case "region" -> {
                if(!overrideProtection) throw new IllegalArgumentException("removing protection requires overrideProtection:true");
                yield regions.remove(name);
            }
            default -> throw new IllegalArgumentException("kind must be waypoint, route or region");
        };
        if(removed==null) throw new IllegalArgumentException("named "+kind+" does not exist");
        commit(waypoints,routes,regions);
    }
    private void commit(Map<String,Pos> waypoints,Map<String,Route> routes,Map<String,Region> regions) throws IOException {
        if(waypoints.size()>1024||routes.size()>128||regions.size()>256) throw new IllegalArgumentException("world memory capacity exceeded");
        Snapshot next=new Snapshot(Math.addExact(state.revision,1),waypoints,routes,regions);
        JsonObject data=new JsonObject();data.addProperty("format",1);data.addProperty("scope",scope);data.add("state",JSON.toJsonTree(next));
        byte[] bytes=JSON.toJson(data).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>16*1024*1024) throw new IllegalArgumentException("world memory exceeds 16 MiB");
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp=Files.createTempFile(file.toAbsolutePath().getParent(),"modbench-memory-",".tmp");
        try {
            Files.write(temp,bytes);
            try {Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException unavailable) {Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
            state=next;
        } finally {Files.deleteIfExists(temp);}
    }
    private void load() throws IOException {
        if(Files.size(file)>16*1024*1024) throw new IOException("world memory exceeds 16 MiB");
        try {
            JsonObject data=new JsonParser().parse(Files.readString(file,StandardCharsets.UTF_8)).getAsJsonObject();
            if(data.get("format").getAsInt()!=1 || !scope.equals(data.get("scope").getAsString())) throw new IllegalArgumentException("world memory format/scope mismatch");
            JsonObject saved=data.getAsJsonObject("state");long revision=saved.get("revision").getAsLong();
            if(revision<0) throw new IllegalArgumentException("negative revision");
            Map<String,Pos> waypoints=new TreeMap<>();Map<String,Route> routes=new TreeMap<>();Map<String,Region> regions=new TreeMap<>();
            for(var e:saved.getAsJsonObject("waypoints").entrySet()) {validName(e.getKey());waypoints.put(e.getKey(),pos(e.getValue()));}
            for(var e:saved.getAsJsonObject("routes").entrySet()) {
                JsonObject r=e.getValue().getAsJsonObject();if(!e.getKey().equals(r.get("name").getAsString())) throw new IllegalArgumentException("route key mismatch");
                List<Pos> points=new ArrayList<>();for(JsonElement p:r.getAsJsonArray("points")) points.add(pos(p));
                routes.put(e.getKey(),new Route(e.getKey(),points,r.get("radius").getAsDouble()));
            }
            for(var e:saved.getAsJsonObject("regions").entrySet()) {
                JsonObject r=e.getValue().getAsJsonObject();if(!e.getKey().equals(r.get("name").getAsString())) throw new IllegalArgumentException("region key mismatch");
                regions.put(e.getKey(),new Region(e.getKey(),pos(r.get("min")),pos(r.get("max")),r.has("mode")?r.get("mode").getAsString():"all_edits"));
            }
            if(waypoints.size()>1024||routes.size()>128||regions.size()>256) throw new IllegalArgumentException("world memory capacity exceeded");
            state=new Snapshot(revision,waypoints,routes,regions);
        } catch(RuntimeException error) {throw new IOException("world memory invalid; repair the file before editing terrain",error);}
    }
    private static Pos pos(JsonElement element) {
        JsonObject p=element.getAsJsonObject();return new Pos(exactInt(p.get("x")),exactInt(p.get("y")),exactInt(p.get("z")));
    }
    private static int exactInt(JsonElement value) {return value.getAsBigDecimal().intValueExact();}
    public static void validName(String name) {
        if(name==null || name.isBlank() || name.length()>96 || name.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("name must be 1..96 printable characters");
    }
    /** Remove only exactly collinear middle points; keep turns, elevation transitions and reversals. */
    public static List<Pos> compact(List<Pos> samples) {
        List<Pos> out=new ArrayList<>();
        for(Pos p:samples) {
            if(!out.isEmpty()&&out.get(out.size()-1).equals(p)) continue;
            if(out.size()>=2) {
                Pos a=out.get(out.size()-2),b=out.get(out.size()-1);
                long ax=(long)b.x-a.x,ay=(long)b.y-a.y,az=(long)b.z-a.z,bx=(long)p.x-b.x,by=(long)p.y-b.y,bz=(long)p.z-b.z;
                if(ax*by==ay*bx && ax*bz==az*bx && ay*bz==az*by && ax*bx+ay*by+az*bz>0) out.remove(out.size()-1);
            }
            out.add(p);
        }
        return List.copyOf(out);
    }
}
