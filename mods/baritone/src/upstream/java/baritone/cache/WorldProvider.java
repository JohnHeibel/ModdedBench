/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Modified by the ModdedBench project (2026) for Minecraft 1.7.10 / GT New Horizons.
 * The original file and its SHA-256 are recorded in META-INF/modbench/UPSTREAM_SOURCES.json.
 */

package baritone.cache;

import baritone.Baritone;
import baritone.compat.LoadedChunkIndex;
import baritone.api.cache.IWorldProvider;
import baritone.api.utils.IPlayerContext;


import net.minecraft.client.multiplayer.ServerData;
import baritone.compat.Tuple;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import org.apache.commons.lang3.SystemUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * @author Brady
 * @since 8/4/2018
 */
public class WorldProvider implements IWorldProvider {

    private static final Map<Path, WorldData> worldCache = new HashMap<>();

    private final Baritone baritone;
    private final IPlayerContext ctx;
    private volatile WorldData currentWorld;
    private String scope;

    /**
     * This lets us detect a broken load/unload hook.
     * @see #detectAndHandleBrokenLoading()
     */
    private World mcWorld;
    private final java.util.Map<net.minecraft.world.chunk.Chunk,Integer> captured=new java.util.WeakHashMap<>();
    private java.util.Iterator<net.minecraft.world.chunk.Chunk> pending=java.util.Collections.emptyIterator();
    private int ticks;

    /** Bounded native capture also covers mods that bypass ordinary chunk events. */
    public void tick(){
        WorldData data=getCurrentWorld();
        if(data==null||ctx.player()==null)return;
        data.cache.lastPlayerPosition=ctx.playerFeet();
        if(!Baritone.settings().chunkCaching.value)return;
        if(++ticks%2!=0)return;
        if(!pending.hasNext())pending=LoadedChunkIndex.capture((net.minecraft.client.multiplayer.ChunkProviderClient)ctx.minecraft().theWorld.getChunkProvider()).values().iterator();
        if(!pending.hasNext())return;
        var chunk=pending.next();
        if(!chunk.isChunkLoaded||chunk.worldObj!=ctx.minecraft().theWorld)return;
        if(ticks-captured.getOrDefault(chunk,-1200)<600)return;
        data.cache.queueForPacking(chunk);captured.put(chunk,ticks);
    }

    public void captureUnloading(net.minecraft.world.chunk.Chunk chunk){
        if(!ctx.minecraft().func_152345_ab()||chunk.worldObj!=ctx.minecraft().theWorld)return;
        var data=getCurrentWorld();if(data!=null&&Baritone.settings().chunkCaching.value)data.cache.queueForPacking(chunk);
    }

    public WorldProvider(Baritone baritone) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
    }

    @Override
    public final WorldData getCurrentWorld() {
        if(ctx.minecraft().func_152345_ab())this.detectAndHandleBrokenLoading();
        return this.currentWorld;
    }

    /**
     * Called when a new world is initialized to discover the
     *
     * @param world The new world
     */
    public final void initWorld(World world) {
        this.getSaveDirectories(world).ifPresent(dirs -> {
            final Path worldDir = dirs.getFirst();
            final Path readmeDir = dirs.getSecond();

            try {
                // lol wtf is this baritone folder in my minecraft save?
                // good thing we have a readme
                Files.createDirectories(readmeDir);
                Files.write(
                        readmeDir.resolve("readme.txt"),
                        "https://github.com/cabaletta/baritone\n".getBytes(StandardCharsets.US_ASCII)
                );
            } catch (IOException ignored) {}

            // We will actually store the world data in a subfolder: "DIM<id>"
            final Path worldDataDir = this.getWorldDataDirectory(worldDir, world);
            try {
                Files.createDirectories(worldDataDir);
            } catch (IOException ignored) {}

            System.out.println("Baritone world data dir: " + worldDataDir);
            synchronized (worldCache) {
                final int dimension = world.provider.dimensionId;
                this.currentWorld = worldCache.computeIfAbsent(worldDataDir, d -> new WorldData(d, dimension));
            }
            this.mcWorld = ctx.minecraft().theWorld;
        });
    }

    public final void closeWorld() {
        WorldData world = this.currentWorld;
        this.currentWorld = null;
        this.mcWorld = null;
        this.scope = null;
        captured.clear();pending=java.util.Collections.emptyIterator();
        if (world == null) {
            return;
        }
        world.onClose();
    }

    private Path getWorldDataDirectory(Path parent, World world) {
        return parent.resolve("DIM" + world.provider.dimensionId);
    }

    /**
     * @param world The world
     * @return An {@link Optional} containing the world's baritone dir and readme dir, or {@link Optional#empty()} if
     *         the world isn't valid for caching.
     */
    private Optional<Tuple<Path, Path>> getSaveDirectories(World world) {
        String identity;
        try { identity=dev.modbench.api.ControlRegistry.memory().memory().scope(); }
        catch (IllegalStateException notReady) { return Optional.empty(); }
        this.scope=identity;
        try {
            String key=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
            Path root=ctx.minecraft().mcDataDir.toPath().resolve("baritone/worlds-gtnh-v1");
            return Optional.of(new Tuple<>(root.resolve(key),root));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /**
     * Why does this exist instead of fixing the event? Some mods break the event. Lol.
     */
    private void detectAndHandleBrokenLoading() {
        World nativeWorld=ctx.minecraft().theWorld;
        String identity=null;
        if(nativeWorld!=null&&ctx.player()!=null){try {identity=dev.modbench.api.ControlRegistry.memory().memory().scope();}catch(IllegalStateException notReady){}}
        if(this.mcWorld!=nativeWorld || !java.util.Objects.equals(scope,identity))closeWorld();
        if(currentWorld==null&&identity!=null)initWorld(nativeWorld);
    }
}
