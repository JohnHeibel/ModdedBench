// SPDX-License-Identifier: MIT
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.client;

import com.google.gson.JsonArray;
import dev.modbench.bridge.Json;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

/**
 * The drops beside the player that would hurt, as one sees them standing there: on each of eight headings, the nearest
 * place within 3 blocks (a plain hit throws you about 2, a harder one further) where the floor you stand on ends and the fall beyond is 4 blocks or
 * more, or ends in what burns, or in nothing within 64. A wall two high closes a heading; a low one does not, since a
 * throw lifts you over it.
 */
final class Edges {
    private Edges(){}
    private static final String[] TOWARD={"south","southwest","west","northwest","north","northeast","east","southeast"};
    static JsonArray near(World w,EntityPlayer me){
        JsonArray out=new JsonArray();
        int y=(int)Math.floor(me.boundingBox.minY+.01);
        for(int h=0;h<8;h++){
            double r=Math.toRadians(h*45),ux=-Math.sin(r),uz=Math.cos(r);boolean overWall=false;
            for(double d=.5;d<=3;d+=.5){
                int x=(int)Math.floor(me.posX+ux*d),z=(int)Math.floor(me.posZ+uz*d);
                if(w.getBlock(x,y,z).getMaterial().blocksMovement()){if(w.getBlock(x,y+1,z).getMaterial().blocksMovement())break;overWall=true;continue;}
                if(w.getBlock(x,y-1,z).getMaterial().blocksMovement())continue;
                int at=y-1;String onto=null;boolean burns=false;
                for(;at>=Math.max(0,y-64);at--){
                    var block=w.getBlock(x,at,z);var m=block.getMaterial();
                    burns=m==Material.fire||m==Material.lava||block.isBurning(w,x,at,z);
                    if(burns||m.isLiquid()||m.blocksMovement()){onto=net.minecraft.block.Block.blockRegistry.getNameForObject(block);break;}
                }
                int drop=onto==null?64:y-at-1;
                boolean liquid=onto!=null&&!burns&&w.getBlock(x,at,z).getMaterial().isLiquid();
                if(burns||onto==null||!liquid&&drop>=4)
                    out.add(Json.object("toward",TOWARD[h],"blocks",d,"drop",drop,"onto",onto==null?"nothing within 64":onto,"burns",burns,"overLowWall",overWall));
                break;
            }
        }
        return out;
    }
}
