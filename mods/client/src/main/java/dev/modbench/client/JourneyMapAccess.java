// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.client;

import com.google.gson.*;
import dev.modbench.api.ControlRegistry;
import dev.modbench.bridge.Json;
import net.minecraft.client.Minecraft;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;

/** Reads JourneyMap's own files: 512x512 region tiles at one pixel per block, and waypoint JSON. Reflection keeps JourneyMap optional. */
final class JourneyMapAccess {
    private static final int OUT=768;
    private static File worldDir() throws Exception {
        Object dir;
        try {
            // JourneyMap writes tiles on a timer; flush so the picture includes what the player has just seen.
            Object cache=Class.forName("journeymap.client.model.RegionImageCache").getMethod("instance").invoke(null);
            cache.getClass().getMethod("flushToDisk",boolean.class).invoke(cache,false);
            dir=Class.forName("journeymap.client.io.FileHandler").getMethod("getJMWorldDir",Minecraft.class).invoke(null,Minecraft.getMinecraft());
        } catch(ClassNotFoundException e) { throw new IllegalArgumentException("JourneyMap is not installed"); }
        if(dir==null) throw new IllegalArgumentException("JourneyMap has not started mapping this world");
        return (File)dir;
    }
    /** JourneyMap's waypoints for one dimension; deaths are its own, the rest are whatever a player or mod saved. */
    private static JsonArray waypoints(File world,int dimension) throws Exception {
        JsonArray out=new JsonArray();File[] files=new File(world,"waypoints").listFiles((d,n)->n.endsWith(".json"));
        if(files!=null) for(File file:files) {
            if(out.size()>=256||file.length()>65536) continue;
            JsonObject w=Json.GSON.fromJson(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8),JsonObject.class);
            if(w==null||!w.has("x")||!w.has("z")||!Json.bool(w,"enable",true)) continue;
            boolean here=false;if(w.has("dimensions")) for(JsonElement d:w.getAsJsonArray("dimensions")) here|=d.getAsInt()==dimension;
            if(here) out.add(Json.object("name",Json.string(w,"name",""),"type",Json.string(w,"type","Normal"),"source","journeymap",
                "pos",Json.GSON.toJsonTree(new int[]{w.get("x").getAsInt(),w.get("y").getAsInt(),w.get("z").getAsInt()})));
        }
        return out;
    }
    static JsonObject view(JsonObject p) throws Exception {
        Minecraft mc=Minecraft.getMinecraft();if(mc.thePlayer==null) throw new IllegalArgumentException("not in a world");
        int px=(int)Math.floor(mc.thePlayer.posX),py=(int)Math.floor(mc.thePlayer.posY),pz=(int)Math.floor(mc.thePlayer.posZ),dimension=mc.thePlayer.dimension;
        int cx=px,cz=pz;
        if(p.has("center")) {JsonArray c=p.getAsJsonArray("center");if(c.size()!=2) throw new IllegalArgumentException("center is [x,z]");cx=c.get(0).getAsInt();cz=c.get(1).getAsInt();}
        int radius=Json.integer(p,"radius",128,16,2048);
        String layer=Json.string(p,"layer","day");
        if(layer.equals("cave")) layer=Integer.toString(py>>4);
        if(!layer.matches("day|night|topo|\\d{1,2}")) throw new IllegalArgumentException("layer is day, night, topo, cave, or a vertical slice number (y/16)");
        File world=worldDir(),tiles=new File(new File(world,"DIM"+dimension),layer);
        int minX=cx-radius,minZ=cz-radius,span=radius*2;
        BufferedImage blocks=new BufferedImage(span,span,BufferedImage.TYPE_INT_ARGB);Graphics2D g=blocks.createGraphics();
        for(int rx=minX>>9;rx<=(minX+span-1)>>9;rx++) for(int rz=minZ>>9;rz<=(minZ+span-1)>>9;rz++) {
            File file=new File(tiles,rx+","+rz+".png");if(!file.isFile()) continue;
            BufferedImage tile=javax.imageio.ImageIO.read(file);if(tile!=null) g.drawImage(tile,(rx<<9)-minX,(rz<<9)-minZ,null);
        }
        g.dispose();
        long mapped=0;for(int pixel:blocks.getRGB(0,0,span,span,null,0,span)) if((pixel>>>24)!=0&&(pixel&0xFFFFFF)!=0) mapped++;
        BufferedImage out=new BufferedImage(OUT,OUT,BufferedImage.TYPE_INT_RGB);g=out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,span>OUT?RenderingHints.VALUE_INTERPOLATION_BILINEAR:RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(blocks,0,0,OUT,OUT,null);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,12));
        double scale=OUT/(double)span;int grid=16;while(grid*scale<80) grid*=2;
        for(int x=Math.floorDiv(minX,grid)*grid;x<minX+span;x+=grid) {int at=(int)((x-minX)*scale);if(at<0) continue;
            g.setColor(new Color(255,255,255,70));g.drawLine(at,0,at,OUT);label(g,"x "+x,at+3,13);}
        for(int z=Math.floorDiv(minZ,grid)*grid;z<minZ+span;z+=grid) {int at=(int)((z-minZ)*scale);if(at<0) continue;
            g.setColor(new Color(255,255,255,70));g.drawLine(0,at,OUT,at);label(g,"z "+z,3,at+27);}
        JsonArray marks=waypoints(world,dimension);
        ControlRegistry.memory().memory().snapshot().waypoints().forEach((name,pos)->marks.add(Json.object("name",name,"type","Memory","source","mb_memory",
            "pos",Json.GSON.toJsonTree(new int[]{pos.x(),pos.y(),pos.z()}))));
        JsonArray shown=new JsonArray(),outside=new JsonArray();
        for(JsonElement e:marks) {
            JsonObject w=e.getAsJsonObject();JsonArray at=w.getAsJsonArray("pos");int x=at.get(0).getAsInt(),z=at.get(2).getAsInt();
            if(x<minX||x>=minX+span||z<minZ||z>=minZ+span) {outside.add(w);continue;}
            int sx=(int)((x-minX+.5)*scale),sz=(int)((z-minZ+.5)*scale);boolean death=Json.string(w,"type","").equals("Death");
            g.setColor(Color.BLACK);g.fillRect(sx-5,sz-5,10,10);g.setColor(death?Color.RED:Color.CYAN);g.fillRect(sx-3,sz-3,6,6);
            label(g,Json.string(w,"name",""),sx+8,sz+4);shown.add(w);
        }
        if(px>=minX&&px<minX+span&&pz>=minZ&&pz<minZ+span) {
            int sx=(int)((px-minX+.5)*scale),sz=(int)((pz-minZ+.5)*scale);double yaw=Math.toRadians(mc.thePlayer.rotationYaw);
            g.setColor(Color.BLACK);g.fillOval(sx-7,sz-7,14,14);g.setColor(Color.YELLOW);g.fillOval(sx-5,sz-5,10,10);
            g.setStroke(new BasicStroke(3));g.drawLine(sx,sz,sx-(int)(Math.sin(yaw)*16),sz+(int)(Math.cos(yaw)*16));label(g,"you",sx+9,sz-6);
        }
        g.dispose();
        java.io.ByteArrayOutputStream png=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(out,"png",png);
        return Json.object("png",Base64.getEncoder().encodeToString(png.toByteArray()),"width",OUT,"height",OUT,"layer",layer,"dimension",dimension,
            "bounds",Json.object("minX",minX,"minZ",minZ,"maxX",minX+span-1,"maxZ",minZ+span-1),"blocksPerPixel",span/(double)OUT,"gridBlocks",grid,
            "mappedFraction",Math.round(mapped*1000.0/((long)span*span))/1000.0,"player",Json.GSON.toJsonTree(new int[]{px,py,pz}),
            "waypointsShown",shown,"waypointsOutside",outside,"orientation","north is up: x grows to the right, z grows downwards; the yellow dot is you and its line is your facing");
    }
    private static void label(Graphics2D g,String text,int x,int y) {
        if(text.length()>28) text=text.substring(0,28);
        g.setColor(new Color(0,0,0,170));g.fillRect(x-2,y-11,g.getFontMetrics().stringWidth(text)+4,14);g.setColor(Color.WHITE);g.drawString(text,x,y);
    }
}
