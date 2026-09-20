// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package baritone.gtnh;

import baritone.gtnh.pathing.BlockPos;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.*;
import net.minecraft.world.World;
import static baritone.gtnh.pathing.WorkSpec.*;

/** Plans in the shape baritone.build accepts, read from schematics on disk or from the loaded world. */
final class PlanImport {
    static final int LIMIT=1048576;
    private PlanImport(){}
    private static BlockPos origin(Map<String,Object> params){return params.containsKey("origin")?pos(params.get("origin")):new BlockPos(0,0,0);}
    private static List<Integer> at(BlockPos origin,int x,int y,int z){return List.of(origin.x()+x,origin.y()+y,origin.z()+z);}
    private static Map<String,Object> result(List<Map<String,Object>> cells,int w,int h,int l,int air,int unknown){
        Map<String,Object> plan=new LinkedHashMap<>();plan.put("cells",cells);plan.put("origin",List.of(0,0,0));plan.put("size",List.of(w,h,l));
        Map<String,Object> out=new LinkedHashMap<>();out.put("plan",plan);out.put("size",List.of(w,h,l));out.put("count",cells.size());
        Map<String,Object> skipped=new LinkedHashMap<>();skipped.put("air",air);skipped.put("unknown",unknown);out.put("skipped",skipped);return out;
    }
    /** {path,origin?,includeAir?}: MCEdit .schematic or a canonical JSON plan under gameDir/schematics. */
    static Map<String,Object> schematic(Map<String,Object> params) throws java.io.IOException {
        Path root=Minecraft.getMinecraft().mcDataDir.toPath().resolve("schematics").toAbsolutePath().normalize();
        String name=string(params,"path","");Path file=root.resolve(name).toAbsolutePath().normalize();
        if(name.isBlank()||!file.startsWith(root)||file.equals(root)||!Files.isRegularFile(file))throw new IllegalArgumentException("path must name a file inside "+root);
        BlockPos origin=origin(params);boolean includeAir=bool(params,"includeAir",false);
        if(name.endsWith(".json")){
            if(Files.size(file)>64L*1024*1024)throw new IllegalArgumentException("plan file too large");
            Map<String,Object> spec=object(WorkJournal.JSON.fromJson(Files.readString(file,StandardCharsets.UTF_8),Map.class));
            List<?> entries=list(spec.get("cells"));if(entries.size()>LIMIT)throw new IllegalArgumentException("plan exceeds "+LIMIT+" cells");
            BlockPos base=spec.containsKey("origin")?pos(spec.get("origin")):new BlockPos(0,0,0);
            List<Map<String,Object>> cells=new ArrayList<>();int air=0;int[] min={Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE},max={Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};
            for(Object entry:entries){
                Map<String,Object> cell=new LinkedHashMap<>(object(entry));BlockPos local=pos(cell.get("pos"));
                boolean clear=bool(cell,"clear",false);
                if(!clear&&string(cell,"id","").equals("minecraft:air")){if(!includeAir){air++;continue;}cell.remove("id");cell.remove("meta");cell.put("clear",true);}
                for(int i=0;i<3;i++){int v=List.of(local.x(),local.y(),local.z()).get(i);min[i]=Math.min(min[i],v);max[i]=Math.max(max[i],v);}
                cell.put("pos",at(origin,base.x()+local.x(),base.y()+local.y(),base.z()+local.z()));cells.add(cell);
            }
            boolean any=!cells.isEmpty();
            return result(cells,any?max[0]-min[0]+1:0,any?max[1]-min[1]+1:0,any?max[2]-min[2]+1:0,air,0);
        }
        NBTTagCompound tag;try(var input=Files.newInputStream(file)){tag=CompressedStreamTools.readCompressed(input);}
        int w=tag.getShort("Width")&65535,h=tag.getShort("Height")&65535,l=tag.getShort("Length")&65535;long size=(long)w*h*l;
        if(w<1||h<1||l<1)throw new IllegalArgumentException("invalid schematic dimensions");
        if(size>LIMIT)throw new IllegalArgumentException("schematic exceeds "+LIMIT+" cells");
        byte[] blocks=tag.getByteArray("Blocks"),data=tag.getByteArray("Data"),add=tag.getByteArray("AddBlocks");
        if(blocks.length!=size||data.length!=size||add.length!=0&&add.length<(size+1)/2)throw new IllegalArgumentException("invalid schematic block arrays");
        List<Map<String,Object>> cells=new ArrayList<>();int air=0,unknown=0;
        for(int i=0;i<size;i++){
            int id=(blocks[i]&255)|(add.length==0?0:((add[i/2]>>((i&1)*4))&15)<<8);int x=i%w,z=(i/w)%l,y=i/(w*l);
            Block block=Block.getBlockById(id);
            if(id==0){if(!includeAir){air++;continue;}cells.add(new LinkedHashMap<>(Map.of("pos",at(origin,x,y,z),"clear",true)));continue;}
            if(block==null||Block.getIdFromBlock(block)!=id){unknown++;continue;}
            Map<String,Object> cell=new LinkedHashMap<>();cell.put("pos",at(origin,x,y,z));cell.put("id",Block.blockRegistry.getNameForObject(block));cell.put("meta",data[i]&15);cells.add(cell);
        }
        Map<String,Object> out=result(cells,w,h,l,air,unknown);out.put("tileEntities",tag.getTagList("TileEntities",10).tagCount());return out;
    }
    /** {bounds:{min,max},origin?,includeAir?}: loaded blocks through the same view baritone.scan uses. */
    static Map<String,Object> copy(World world,Map<String,Object> params) {
        Bounds bounds=bounds(child(params,"bounds"));if(bounds.volume()>LIMIT)throw new IllegalArgumentException("copy volume exceeds "+LIMIT);
        BlockPos origin=origin(params);boolean includeAir=bool(params,"includeAir",false);
        List<Map<String,Object>> cells=new ArrayList<>();int air=0,unknown=0,unloaded=0,tiles=0;
        for(long i=0;i<bounds.volume();i++){
            BlockPos p=bounds.at(i);int x=p.x()-bounds.min().x(),y=p.y()-bounds.min().y(),z=p.z()-bounds.min().z();
            if(!ForgeSnapshot.loaded(world,p.x(),p.y(),p.z())){unloaded++;continue;}
            if(world.isAirBlock(p.x(),p.y(),p.z())){if(!includeAir){air++;continue;}cells.add(new LinkedHashMap<>(Map.of("pos",at(origin,x,y,z),"clear",true)));continue;}
            Block block=world.getBlock(p.x(),p.y(),p.z());String id=Block.blockRegistry.getNameForObject(block);
            if(id==null){unknown++;continue;}
            if(world.getTileEntity(p.x(),p.y(),p.z())!=null)tiles++;
            Map<String,Object> cell=new LinkedHashMap<>();cell.put("pos",at(origin,x,y,z));cell.put("id",id);cell.put("meta",world.getBlockMetadata(p.x(),p.y(),p.z()));cells.add(cell);
        }
        Map<String,Object> out=result(cells,bounds.max().x()-bounds.min().x()+1,bounds.max().y()-bounds.min().y()+1,bounds.max().z()-bounds.min().z()+1,air,unknown);
        ((Map<String,Object>)out.get("skipped")).put("unloaded",unloaded);out.put("tileEntities",tiles);return out;
    }
}
