// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.server;

import java.util.UUID;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;

/** Stable identity of the saved server world, independent of seed or restart. */
public final class WorldIdentity extends WorldSavedData {
    private static final String KEY="modbench_world_identity";
    private String id;
    private boolean verified;
    public WorldIdentity(String name) {super(name);}
    public static String get(World world) {
        WorldIdentity data=(WorldIdentity)world.loadItemData(WorldIdentity.class,KEY);
        java.io.File file=world.getSaveHandler().getMapFileFromName(KEY);
        if(data==null) {
            // MapStorage catches read failures and returns null; never turn corrupt identity into an unprotected new world.
            if(file.exists()) throw new IllegalStateException("saved Modbench world identity could not be loaded");
            data=new WorldIdentity(KEY);data.id=UUID.randomUUID().toString();world.setItemData(KEY,data);data.markDirty();
            world.mapStorage.saveAllData();
        }
        if(data.id==null) throw new IllegalStateException("saved Modbench world identity is invalid");
        if(!data.verified) {
            try {
                // The pack may defer MapStorage writes, so a new world has no file yet; write the same NBT MapStorage will.
                if(!file.exists()) {
                    NBTTagCompound root=new NBTTagCompound(),body=new NBTTagCompound();data.writeToNBT(body);root.setTag("data",body);
                    file.getParentFile().mkdirs();
                    try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){net.minecraft.nbt.CompressedStreamTools.writeCompressed(root,out);}
                }
            } catch(java.io.IOException error) {throw new IllegalStateException("world identity could not be persisted",error);}
            try(java.io.FileInputStream in=new java.io.FileInputStream(file)) {
                String saved=net.minecraft.nbt.CompressedStreamTools.readCompressed(in).getCompoundTag("data").getString("id");
                if(!data.id.equals(saved)) throw new IllegalStateException("world identity persistence mismatch");
                data.verified=true;
            } catch(Exception error) {throw new IllegalStateException("world identity could not be persisted",error);}
        }
        return data.id;
    }
    @Override public void readFromNBT(NBTTagCompound tag) {id=UUID.fromString(tag.getString("id")).toString();verified=true;}
    @Override public void writeToNBT(NBTTagCompound tag) {tag.setString("id",id);}
}
