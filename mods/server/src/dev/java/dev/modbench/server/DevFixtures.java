// SPDX-License-Identifier: LGPL-3.0-or-later
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
        WorkProcessFixture processes=new WorkProcessFixture(server);
        runtime.fixture("dev.work_process_fixture.create","Create journalled bounded mining/building course with native ToolBuilder loadout","privileged",r->processes.create());
        runtime.fixture("dev.work_process_fixture.position","Position development player {name:ore_line|selection|build|descend_start|descend_step_1|descend_step_2|descend_goal}","privileged",r->processes.position(Json.string(r.params,"name","ore_line")));
        runtime.fixture("dev.work_process_fixture.status","Authoritative targets, exact metadata, inventory and selected hotbar slot","privileged",r->processes.status());
        runtime.fixture("dev.work_process_fixture.set_block","Fixture-only bounded block setter {x,y,z,id,meta}; exact registry ID required","privileged",r->processes.setBlock(r.params));
        runtime.fixture("dev.work_process_fixture.set_stack","Fixture-only inventory setter {slot,id,meta,count,nbt}; exact registry ID required","privileged",r->processes.setStack(r.params));
        runtime.fixture("dev.work_process_fixture.inspect_block","Independent fixture evidence: authoritative tile NBT, inventory and fluids {x,y,z}","read",r->processes.inspectBlock(r.params));
        runtime.fixture("dev.work_process_fixture.supply_energy","Supply bounded fixture EU input {x,y,z,eu<=32768}; does not configure faces, connections or modes","privileged",r->processes.supplyEnergy(r.params));
        runtime.fixture("dev.work_process_fixture.restore","Restore original player/inventory/health and remove journalled course","privileged",r->processes.restore());
    }
}
