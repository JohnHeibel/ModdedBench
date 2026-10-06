// SPDX-License-Identifier: MIT
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.server;

import dev.modbench.bridge.Json;
import net.minecraft.server.MinecraftServer;

/** Development-only dev.* RPCs. Compiled always, shipped only with -PdevFixtures, discovered reflectively. */
final class DevFixtures {
    private DevFixtures() {}
    static void register(ServerRuntime runtime,MinecraftServer server) {
        InteractionFixture interactions=new InteractionFixture(server);
        runtime.fixture("dev.interaction_fixture.create","Journalled survival interaction/combat arena","privileged",r->interactions.create());
        runtime.fixture("dev.interaction_fixture.position","Position/load targets {target:chest|fluid|entity|combat|occluded}","privileged",r->interactions.position(Json.string(r.params,"target","chest")));
        runtime.fixture("dev.interaction_fixture.status","Authoritative interaction fixture state","privileged",r->interactions.status());
        runtime.fixture("dev.interaction_fixture.hurt","Deterministic interrupt stimulus {amount:1..19}","privileged",r->interactions.hurt(Json.integer(r.params,"amount",1,1,19)));
        runtime.fixture("dev.interaction_fixture.stack","Journalled fixture-only loadout {slot,id,meta,count,nbt}; select identities through NEI","privileged",r->interactions.stack(r.params));
        runtime.fixture("dev.interaction_fixture.restore","Restore original player and remove journalled arena/entities","privileged",r->interactions.restore());
        TimeFixture time=new TimeFixture(server);
        runtime.fixture("dev.time_fixture.create","Create journaled furnace, fluid and entity clock fixture","privileged",r->time.create());
        runtime.fixture("dev.time_fixture.run","Run a development workload for {ticks:1..2000} server ticks, then pause","privileged",r->{runtime.clock.runForFixture(Json.integer(r.params,"ticks",200,1,2000));return runtime.clock.status();});
        runtime.fixture("dev.time_fixture.status","Observe fixture world time, furnace, fluid and entity state","privileged",r->time.status());
        runtime.fixture("dev.time_fixture.hurt","Development-only deterministic damage {amount:1..19}","privileged",r->time.hurt(Json.integer(r.params,"amount",1,1,19)));
        runtime.fixture("dev.time_fixture.restore","Restore clock fixture and original player","privileged",r->time.restore());
        FluidFixture fixture=new FluidFixture(server);
        runtime.fixture("dev.gui_fixture.create","Create journaled native vanilla, Tinkers, GT, AE2 and Forestry UI fixture","privileged",r->fixture.createUi());
        runtime.fixture("dev.gui_fixture.position","Close container and position at named GUI fixture {name}","privileged",r->fixture.positionUi(Json.string(r.params,"name","chest")));
        runtime.fixture("dev.gui_fixture.status","Authoritative fixture container slots and cursor","privileged",r->fixture.statusUi());
        runtime.fixture("dev.fluid_fixture.create","Create isolated fluid test basin and journal player state", "privileged",r->fixture.create());
        runtime.fixture("dev.fluid_fixture.position","Position development player in a named test case {name}","privileged",r->fixture.position(Json.string(r.params,"name","pool_start")));
        runtime.fixture("dev.fluid_fixture.restore","Remove test basin and restore journaled player state","privileged",r->fixture.restore());
        runtime.fixture("dev.geometry_fixture.create","Create journaled collision/ladder test terrain","privileged",r->fixture.createGeometry());
        runtime.fixture("dev.long_route_fixture.create","Create journaled 416-block route and 48-block descent; loads server chunks","privileged",r->fixture.createLongRoute());
        runtime.fixture("dev.geometry_fixture.change","Named terrain change during a geometry regression {name}","privileged",r->fixture.changeGeometry(Json.string(r.params,"name","")));
        runtime.fixture("dev.work_fixture.create","Create journaled bounded mining, bridging, hazard, and inventory fixture","privileged",r->fixture.createWork());
        runtime.fixture("dev.work_fixture.position","Position development player in a named work-fixture case {name}","privileged",r->fixture.positionWork(Json.string(r.params,"name","work_start")));
        runtime.fixture("dev.work_fixture.change","Named obstacle or loadout change during a work regression {name}","privileged",r->fixture.changeWork(Json.string(r.params,"name","")));
        MovementFixture movement=new MovementFixture(server);
        runtime.fixture("dev.movement_fixture.create","Journalled sky movement/flowing-liquid course; measures every registered block's collision boxes once","privileged",r->movement.create());
        runtime.fixture("dev.movement_fixture.position","Rebuild a case's plot, reset the player and place them at its start {name,yaw?,pitch?}","privileged",r->movement.position(Json.string(r.params,"name","corner_l"),r.params));
        runtime.fixture("dev.movement_fixture.status","Authoritative player state; with {name} the plot's items, falling blocks, fluids and obsidian","privileged",r->movement.status(r.params));
        runtime.fixture("dev.movement_fixture.change","Named mid-run world change {name:lava_approach_open|lava_approach_close|natural_flow_on|natural_flow_off|bridge_drop_fall|raised_edge_lift}","privileged",r->movement.change(Json.string(r.params,"name","")));
        runtime.fixture("dev.movement_fixture.shapes","Measured partial collision shapes {filter:picked|thin|low|all,limit}","privileged",r->movement.shapes(r.params));
        runtime.fixture("dev.movement_fixture.restore","Restore original player, game rules, and remove the course","privileged",r->movement.restore());
        ReplayFixture replay=new ReplayFixture(server);
        runtime.fixture("dev.replay.place","Teleport the only connected player to {x,y,z,yaw?,pitch?} in a cloned world; changes no blocks","privileged",r->replay.place(r.params));
        runtime.fixture("dev.replay.sustain","Fill the only connected player's health and food and, with {time:0..23999}, set the overworld's time of day; returns what they were","privileged",r->replay.sustain(r.params));
        runtime.fixture("dev.replay.status","The player as the server sees it, and block id/meta at {cells:[[x,y,z],...]} (<=64)","read",r->replay.status(r.params));
        WorkProcessFixture processes=new WorkProcessFixture(server);
        runtime.fixture("dev.work_process_fixture.create","Create journalled bounded mining/building course with native ToolBuilder loadout {width?:32..64,depth?:16..64,top?:185..200,bare?}; bare leaves the floor free of the built-in courses","privileged",r->processes.create(r.params));
        runtime.fixture("dev.work_process_fixture.position","Position development player {name:ore_line|selection|build|descend_start|descend_step_1|descend_step_2|descend_goal}","privileged",r->processes.position(Json.string(r.params,"name","ore_line")));
        runtime.fixture("dev.work_process_fixture.status","Authoritative targets, exact metadata, inventory and selected hotbar slot","privileged",r->processes.status());
        runtime.fixture("dev.work_process_fixture.set_block","Fixture-only bounded block setter {x,y,z,id,meta,nbt?,world?}; exact registry ID required; nbt is the tile entity as text (the snbt of inspect_block and region); world:true takes world coordinates in loaded chunks, put back by restore","privileged",r->processes.setBlock(r.params));
        runtime.fixture("dev.work_process_fixture.set_stack","Fixture-only inventory setter {slot,id,meta,count,nbt}; exact registry ID required","privileged",r->processes.setStack(r.params));
        runtime.fixture("dev.work_process_fixture.inspect_block","Independent fixture evidence: authoritative tile NBT (tile as JSON, snbt as text), inventory and fluids {x,y,z,world?}","read",r->processes.inspectBlock(r.params));
        runtime.fixture("dev.work_process_fixture.region","Every non-air cell of {min:[x,y,z],max:[x,y,z],world?} (<=16384 cells) as {pos,id,meta,snbt?}","read",r->processes.region(r.params));
        runtime.fixture("dev.work_process_fixture.supply_energy","Supply bounded fixture EU input {x,y,z,eu<=32768,world?}; does not configure faces, connections or modes","privileged",r->processes.supplyEnergy(r.params));
        runtime.fixture("dev.work_process_fixture.restore","Restore original player/inventory/health, remove journalled course and put back world cells changed outside it","privileged",r->processes.restore());
    }
}
