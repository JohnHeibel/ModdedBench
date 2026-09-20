// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.server;

import com.google.gson.JsonArray;
import dev.modbench.bridge.BridgeRuntime;
import dev.modbench.bridge.Json;
import dev.modbench.bridge.Request;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

public final class ServerRuntime extends BridgeRuntime {
    private final MinecraftServer server;
    public final ServerClock clock;
    final TileObservations observations=new TileObservations(this);

    /** Registration entry for {@code DevFixtures}; the fixture classes are outside this jar's shipped source set. */
    void fixture(String name,String description,String effect,Handler handler) {register(name,description,effect,handler);}
    public ServerRuntime(MinecraftServer server) {
        super("server"); this.server = server;
        clock=new ServerClock(server,this);
        for(String method:new String[]{"time.status","time.pause","time.resume","time.configure","time.report_failure"})
            register(method,"Dedicated simulation clock; configure {healthDrop,healthBelow,airBelow,foodBelow,burning,actionFailed,pauseOnDisconnect}",
                method.equals("time.status")?"read":"interaction",r->clock.command(r));
        if(Boolean.getBoolean("modbench.devFixtures")) try {
            var fixtures=Class.forName("dev.modbench.server.DevFixtures").getDeclaredMethod("register",ServerRuntime.class,MinecraftServer.class);
            fixtures.setAccessible(true);fixtures.invoke(null,this,server);
        } catch(ClassNotFoundException absent) {throw new IllegalStateException("modbench.devFixtures requested but this server jar was built without -PdevFixtures");}
        catch(ReflectiveOperationException error) {throw new IllegalStateException(error);}
        register("obs.world", "Dedicated server worlds, game time and player count", "read", r -> {
            JsonArray dimensions = new JsonArray();
            for (WorldServer world : server.worldServers) if (world != null) dimensions.add(Json.object(
                "dimension", world.provider.dimensionId, "time", world.getWorldTime(), "totalTime", world.getTotalWorldTime()));
            return Json.object("dedicated", server.isDedicatedServer(), "tickMode", clock.status().get("mode"), "dimensions", dimensions,
                "players", server.getCurrentPlayerCount());
        });
        register("obs.players", "Authoritative positions and health of connected players", "read", r -> {
            JsonArray players = new JsonArray();
            for (Object entry : server.getConfigurationManager().playerEntityList) {
                EntityPlayerMP p = (EntityPlayerMP) entry;
                players.add(Json.object("name", p.getCommandSenderName(), "uuid", p.getUniqueID().toString(),
                    "uuidScope", "server", "entityId", p.getEntityId(),
                    "pos", Json.array(p.posX, p.boundingBox.minY, p.posZ), "positionAnchor", "feet", "dimension", p.dimension,
                    "health", p.getHealth(), "air", p.getAir(), "food", p.getFoodStats().getFoodLevel()));
            }
            return players;
        });
        register("obs.block", "Authoritative loaded block {dimension,x,y,z}; does not load chunks", "read", r -> block(r));
        register("sys.shutdown", "Graceful server shutdown, saving the world", "privileged", r -> {
            server.initiateShutdown(); return Json.object("shuttingDown", true);
        });
    }

    private Object block(Request r) {
        int dimension = Json.integer(r.params, "dimension", 0, -100000, 100000);
        WorldServer world = net.minecraftforge.common.DimensionManager.getWorld(dimension);
        if (world == null) throw new IllegalArgumentException("dimension not loaded");
        int x = Json.integer(r.params, "x", 0, -30000000, 30000000), y = Json.integer(r.params, "y", 0, 0, 255);
        int z = Json.integer(r.params, "z", 0, -30000000, 30000000);
        if (!world.blockExists(x, y, z)) throw new IllegalArgumentException("chunk not loaded");
        var tile = world.getTileEntity(x, y, z);
        return Json.object("pos", Json.array(x, y, z), "dimension", dimension,
            "id", Block.blockRegistry.getNameForObject(world.getBlock(x, y, z)), "meta", world.getBlockMetadata(x, y, z),
            "tileClass", tile == null ? null : tile.getClass().getName());
    }

    @Override protected void controlsChanged(String reason) {}
    @Override protected void maintainControls() {}
    @Override public Object capabilities() {
        var out=(com.google.gson.JsonObject)super.capabilities();out.addProperty("tickControl",true);return out;
    }
}
