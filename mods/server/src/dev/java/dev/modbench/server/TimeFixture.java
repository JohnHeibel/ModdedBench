// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.server;

import dev.modbench.bridge.Json;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.UUID;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.world.WorldServer;

/**
 * Small time-control fixture built inside FluidFixture's already-journalled work volume.
 * Its pig is removed by its exact UUID before FluidFixture clears the containing blocks.
 */
final class TimeFixture {
    private static final int FURNACE_X = 3, FURNACE_Y = 176, FURNACE_Z = 4;
    private static final int WATER_X = 10, WATER_Y = 177, WATER_Z = 6;
    private static final int PIG_X = 3, PIG_Y = 176, PIG_Z = 8;

    private final MinecraftServer server;
    private final FluidFixture work;
    private final File journal;
    private boolean created;
    private int originX, originZ;
    private UUID pigId;
    private ProgressionFixture progression;

    TimeFixture(MinecraftServer server) {
        this.server = server;
        this.work = new FluidFixture(server);
        this.journal = new File(world().getSaveHandler().getWorldDirectory(), "modbench-time-fixture.dat");
        loadJournal();
    }

    Object create() throws Exception {
        if (created) throw new IllegalArgumentException("restore existing time fixture first");
        try {
            Object positioned = work.createWork();
            com.google.gson.JsonObject start = (com.google.gson.JsonObject) positioned;
            originX = start.getAsJsonArray("origin").get(0).getAsInt();
            originZ = start.getAsJsonArray("origin").get(2).getAsInt();
            created = true;
            buildFurnace();
            buildWaterCourse();
            spawnPig();
            progression=new ProgressionFixture(world(),originX,originZ);progression.create();
            return status();
        } catch (Exception failure) {
            if (created) {
                removePig();
                created = false;
                try {
                    work.restore();
                    if (journal.isFile() && !journal.delete()) {
                        failure.addSuppressed(new IllegalStateException("could not remove time fixture recovery journal"));
                    }
                } catch (Exception cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    Object status() throws Exception {
        requireCreated();
        WorldServer world = world();
        TileEntity tile = world.getTileEntity(x(FURNACE_X), FURNACE_Y, z(FURNACE_Z));
        TileEntityFurnace furnace = tile instanceof TileEntityFurnace ? (TileEntityFurnace) tile : null;
        EntityPig pig = findPig();
        return Json.object(
            "progression",progression==null?null:progression.status(),
            "origin", Json.array(originX, 0, originZ),
            "worldTime", world.getWorldTime(), "totalWorldTime", world.getTotalWorldTime(),
            "furnace", Json.object(
                "pos", Json.array(x(FURNACE_X), FURNACE_Y, z(FURNACE_Z)),
                "present", furnace != null,
                "burnTime", furnace == null ? null : furnace.furnaceBurnTime,
                "cookTime", furnace == null ? null : furnace.furnaceCookTime,
                "input", furnace == null ? null : stackName(furnace.getStackInSlot(0)),
                "fuel", furnace == null ? null : stackName(furnace.getStackInSlot(1)),
                "output", furnace == null ? null : stackName(furnace.getStackInSlot(2))),
            "water", Json.object(
                "source", blockState(WATER_X, WATER_Y, WATER_Z),
                "below", blockState(WATER_X, WATER_Y - 1, WATER_Z),
                "outlet", blockState(WATER_X + 2, WATER_Y - 1, WATER_Z)),
            "pig", Json.object(
                "uuid", pigId == null ? null : pigId.toString(), "present", pig != null,
                "age", pig == null ? null : pig.getGrowingAge(), "ticksExisted",pig==null?null:pig.ticksExisted,
                "pos", pig == null ? null : Json.array(pig.posX, pig.posY, pig.posZ)),
            "playerHealth", player().getHealth());
    }

    Object hurt(int amount) {
        requireCreated();
        if (amount < 1 || amount > 19) throw new IllegalArgumentException("amount must be in 1..19");
        EntityPlayerMP player = player();
        float before = player.getHealth();
        float after = Math.max(1.0F, before - amount);
        if (after == before) throw new IllegalStateException("fixture player has no reducible health");
        // This is a deterministic development trigger. FluidFixture's journal restores the original health.
        player.setHealth(after);
        return Json.object("healthBefore", before, "healthAfter", after, "amount", amount);
    }

    Object restore() throws Exception {
        if (!created) return Json.object("restored", false);
        removePig();
        Object restored = work.restore();
        if (!journal.delete()) throw new IllegalStateException("could not remove time fixture recovery journal");
        created = false;
        pigId = null;
        return restored;
    }

    private void buildFurnace() {
        WorldServer world = world();
        world.setBlock(x(FURNACE_X), FURNACE_Y, z(FURNACE_Z), Blocks.furnace, 0, 3);
        TileEntity tile = world.getTileEntity(x(FURNACE_X), FURNACE_Y, z(FURNACE_Z));
        if (!(tile instanceof TileEntityFurnace)) throw new IllegalStateException("time fixture furnace tile was not created");
        TileEntityFurnace furnace = (TileEntityFurnace) tile;
        furnace.setInventorySlotContents(0, new ItemStack(Blocks.iron_ore));
        furnace.setInventorySlotContents(1, new ItemStack(Items.coal, 2));
        furnace.markDirty();
    }

    private void buildWaterCourse() {
        // A sealed, stepped channel makes both source and propagated water observable without leaving the work volume.
        for (int dx = 7; dx <= 13; dx++) for (int dz = 4; dz <= 8; dz++) set(dx, 175, dz, Blocks.stone);
        for (int dx = 7; dx <= 13; dx++) for (int y = 176; y <= 178; y++) {
            set(dx, y, 4, Blocks.stone);
            set(dx, y, 8, Blocks.stone);
        }
        for (int dz = 4; dz <= 8; dz++) for (int y = 176; y <= 178; y++) {
            set(7, y, dz, Blocks.stone);
            set(13, y, dz, Blocks.stone);
        }
        // Keep the floor sealed: flowing below y=175 would escape the recovery volume.
        set(WATER_X, WATER_Y, WATER_Z, Blocks.flowing_water, 0);
    }

    private void spawnPig() throws Exception {
        // Fence walls bound normal AI wandering while the negative age makes server-tick advancement measurable.
        for (int dx = 1; dx <= 5; dx++) for (int dz = 6; dz <= 10; dz++) set(dx, 175, dz, Blocks.grass);
        for (int dx = 1; dx <= 5; dx++) for (int y = 176; y <= 178; y++) {
            set(dx, y, 6, Blocks.fence);
            set(dx, y, 10, Blocks.fence);
        }
        for (int dz = 6; dz <= 10; dz++) for (int y = 176; y <= 178; y++) {
            set(1, y, dz, Blocks.fence);
            set(5, y, dz, Blocks.fence);
        }
        EntityPig pig = new EntityPig(world());
        pig.setGrowingAge(-1200);
        pig.setLocationAndAngles(x(PIG_X) + 0.5D, PIG_Y, z(PIG_Z) + 0.5D, 0.0F, 0.0F);
        pigId = pig.getUniqueID();
        writeJournal(); // A crash before spawn is also recoverable: cleanup finds no matching UUID.
        if (!world().spawnEntityInWorld(pig)) throw new IllegalStateException("time fixture pig could not spawn");
    }

    private EntityPig findPig() {
        if (pigId == null) return null;
        // On restart the work-volume chunk may not be resident yet; loading this exact pig chunk attaches its entities.
        world().getChunkFromChunkCoords(x(PIG_X) >> 4, z(PIG_Z) >> 4);
        for (Object entry : world().loadedEntityList) {
            if (entry instanceof EntityPig && pigId.equals(((Entity) entry).getUniqueID())) return (EntityPig) entry;
        }
        return null;
    }

    private void removePig() {
        EntityPig pig = findPig();
        if (pig != null) world().removeEntity(pig);
    }

    private void loadJournal() {
        if (!journal.isFile()) return;
        try (FileInputStream in = new FileInputStream(journal)) {
            NBTTagCompound saved = CompressedStreamTools.readCompressed(in);
            if (saved == null || saved.getInteger("version") != 1 || !saved.hasKey("pigUuid")) {
                throw new IllegalStateException("invalid time fixture recovery journal");
            }
            originX = saved.getInteger("originX");
            originZ = saved.getInteger("originZ");
            pigId = UUID.fromString(saved.getString("pigUuid"));
            created = true;
        } catch (Exception e) {
            throw new IllegalStateException("cannot read time fixture recovery journal", e);
        }
    }

    private void writeJournal() throws Exception {
        NBTTagCompound saved = new NBTTagCompound();
        saved.setInteger("version", 1);
        saved.setInteger("originX", originX);
        saved.setInteger("originZ", originZ);
        saved.setString("pigUuid", pigId.toString());
        try (FileOutputStream out = new FileOutputStream(journal)) {
            CompressedStreamTools.writeCompressed(saved, out);
        }
    }

    private EntityPlayerMP player() {
        for (Object entry : server.getConfigurationManager().playerEntityList) {
            EntityPlayerMP player = (EntityPlayerMP) entry;
            if (player.getCommandSenderName().equals("ModbenchDev") && player.dimension == 0) return player;
        }
        throw new IllegalArgumentException("time fixture requires ModbenchDev in overworld");
    }

    private WorldServer world() { return server.worldServerForDimension(0); }
    private int x(int relative) { return originX + relative; }
    private int z(int relative) { return originZ + relative; }
    private void set(int relativeX, int y, int relativeZ, Block block) { set(relativeX, y, relativeZ, block, 0); }
    private void set(int relativeX, int y, int relativeZ, Block block, int meta) {
        world().setBlock(x(relativeX), y, z(relativeZ), block, meta, 3);
    }
    private JsonObject blockState(int relativeX, int y, int relativeZ) {
        Block block = world().getBlock(x(relativeX), y, z(relativeZ));
        return Json.object("pos", Json.array(x(relativeX), y, z(relativeZ)),
            "id", Block.blockRegistry.getNameForObject(block), "meta", world().getBlockMetadata(x(relativeX), y, z(relativeZ)));
    }
    private static String stackName(ItemStack stack) {
        return stack == null ? null : String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem()));
    }
    private void requireCreated() {
        if (!created) throw new IllegalArgumentException("time fixture required");
    }
}
