// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;

import baritone.api.schematic.IStaticSchematic;
import java.io.*;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.nbt.*;

/** Native legacy schematic loader; richer formats are also accepted through the harness's canonical cell plans. */
public final class SchematicFiles {
    private SchematicFiles(){}
    public static IStaticSchematic read(File file) throws IOException {
        NBTTagCompound tag;try(InputStream input=new FileInputStream(file)){tag=CompressedStreamTools.readCompressed(input);}
        int width=tag.getShort("Width")&65535,height=tag.getShort("Height")&65535,length=tag.getShort("Length")&65535;
        long size=(long)width*height*length;
        if(width<1||height<1||length<1||size>16777216)throw new IOException("Invalid or excessive schematic dimensions");
        byte[] blocks=tag.getByteArray("Blocks"),data=tag.getByteArray("Data"),add=tag.getByteArray("AddBlocks");
        if(blocks.length!=size||data.length!=size||add.length!=0&&add.length<(size+1)/2)throw new IOException("Invalid schematic block arrays");
        if(tag.getTagList("TileEntities",10).tagCount()>0)throw new IOException("Tile data requires an explicit canonical plan with item selectors and verification");
        IBlockState[] states=new IBlockState[(int)size];
        for(int i=0;i<size;i++){
            int id=(blocks[i]&255)|(add.length==0?0:((add[i/2]>>((i&1)*4))&15)<<8);
            Block block=Block.getBlockById(id);if(block==null||Block.getIdFromBlock(block)!=id)throw new IOException("Unknown native schematic block ID "+id);
            states[i]=IBlockState.of(block,data[i]&15);
        }
        return new IStaticSchematic(){
            public int widthX(){return width;}public int heightY(){return height;}public int lengthZ(){return length;}
            public IBlockState getDirect(int x,int y,int z){return states[(y*length+z)*width+x];}
            public IBlockState desiredState(int x,int y,int z,IBlockState current,List<IBlockState> placeable){return getDirect(x,y,z);}
        };
    }
}
