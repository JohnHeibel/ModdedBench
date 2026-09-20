// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
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

    public ServerRuntime(MinecraftServer server) {
        super("server"); this.server = server;
        clock=new ServerClock(server,this);
        for(String method:new String[]{"time.status","time.pause","time.resume","time.configure","time.report_failure"})
            register(method,"Dedicated simulation clock; configure {healthDrop,healthBelow,airBelow,foodBelow,burning,actionFailed,pauseOnDisconnect}",
                method.equals("time.status")?"read":"interaction",r->clock.command(r));
        if(Boolean.getBoolean("modbench.devFixtures")) {
            InteractionFixture interactions=new InteractionFixture(server);
            register("dev.interaction_fixture.create","Journalled survival interaction/combat arena","privileged",r->interactions.create());
            register("dev.interaction_fixture.position","Position/load targets {target:chest|fluid|entity|combat|occluded}","privileged",r->interactions.position(Json.string(r.params,"target","chest")));
            register("dev.interaction_fixture.status","Authoritative interaction fixture state","privileged",r->interactions.status());
            register("dev.interaction_fixture.hurt","Deterministic interrupt stimulus {amount:1..19}","privileged",r->interactions.hurt(Json.integer(r.params,"amount",1,1,19)));
            register("dev.interaction_fixture.stack","Journalled fixture-only loadout {slot,id,meta,count,nbt}; select identities through NEI","privileged",r->interactions.stack(r.params));
            register("dev.interaction_fixture.restore","Restore original player and remove journalled arena/entities","privileged",r->interactions.restore());
            TimeFixture time=new TimeFixture(server);
            register("dev.time_fixture.create","Create journaled furnace, fluid and entity clock fixture","privileged",r->time.create());
            register("dev.time_fixture.run","Run a development workload for {ticks:1..2000} server ticks, then pause","privileged",r->{clock.runForFixture(Json.integer(r.params,"ticks",200,1,2000));return clock.status();});
            register("dev.time_fixture.status","Observe fixture world time, furnace, fluid and entity state","privileged",r->time.status());
            register("dev.time_fixture.hurt","Development-only deterministic damage {amount:1..19}","privileged",r->time.hurt(Json.integer(r.params,"amount",1,1,19)));
            register("dev.time_fixture.restore","Restore clock fixture and original player","privileged",r->time.restore());
            FluidFixture fixture=new FluidFixture(server);
            register("dev.gui_fixture.create","Create journaled native vanilla, Tinkers, GT, AE2 and Forestry UI fixture","privileged",r->fixture.createUi());
            register("dev.gui_fixture.position","Close container and position at named GUI fixture {name}","privileged",r->fixture.positionUi(Json.string(r.params,"name","chest")));
            register("dev.gui_fixture.status","Authoritative fixture container slots and cursor","privileged",r->fixture.statusUi());
            register("dev.fluid_fixture.create","Create isolated fluid test basin and journal player state", "privileged",r->fixture.create());
            register("dev.fluid_fixture.position","Position development player in a named test case {name}","privileged",r->fixture.position(Json.string(r.params,"name","pool_start")));
            register("dev.fluid_fixture.restore","Remove test basin and restore journaled player state","privileged",r->fixture.restore());
            register("dev.geometry_fixture.create","Create journaled collision/ladder test terrain","privileged",r->fixture.createGeometry());
            register("dev.long_route_fixture.create","Create journaled 416-block route and 48-block descent; loads server chunks","privileged",r->fixture.createLongRoute());
            register("dev.geometry_fixture.change","Named terrain change during a geometry regression {name}","privileged",r->fixture.changeGeometry(Json.string(r.params,"name","")));
            register("dev.work_fixture.create","Create journaled bounded mining, bridging, hazard, and inventory fixture","privileged",r->fixture.createWork());
            register("dev.work_fixture.position","Position development player in a named work-fixture case {name}","privileged",r->fixture.positionWork(Json.string(r.params,"name","work_start")));
            register("dev.work_fixture.change","Named obstacle or loadout change during a work regression {name}","privileged",r->fixture.changeWork(Json.string(r.params,"name","")));
            WorkProcessFixture processes=new WorkProcessFixture(server);
            register("dev.work_process_fixture.create","Create journalled bounded mining/building course with native ToolBuilder loadout","privileged",r->processes.create());
            register("dev.work_process_fixture.position","Position development player {name:ore_line|selection|build|descend_start|descend_step_1|descend_step_2|descend_goal}","privileged",r->processes.position(Json.string(r.params,"name","ore_line")));
            register("dev.work_process_fixture.status","Authoritative targets, exact metadata, inventory and selected hotbar slot","privileged",r->processes.status());
            register("dev.work_process_fixture.set_block","Fixture-only bounded block setter {x,y,z,id,meta}; exact registry ID required","privileged",r->processes.setBlock(r.params));
            register("dev.work_process_fixture.set_stack","Fixture-only inventory setter {slot,id,meta,count,nbt}; exact registry ID required","privileged",r->processes.setStack(r.params));
            register("dev.work_process_fixture.inspect_block","Independent fixture evidence: authoritative tile NBT, inventory and fluids {x,y,z}","read",r->processes.inspectBlock(r.params));
            register("dev.work_process_fixture.supply_energy","Supply bounded fixture EU input {x,y,z,eu<=32768}; does not configure faces, connections or modes","privileged",r->processes.supplyEnergy(r.params));
            register("dev.work_process_fixture.restore","Restore original player/inventory/health and remove journalled course","privileged",r->processes.restore());
        }
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
