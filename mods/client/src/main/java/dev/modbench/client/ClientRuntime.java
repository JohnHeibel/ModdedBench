// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.client;

import dev.modbench.api.ControlRegistry;
import java.util.Map;
import java.util.LinkedHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cpw.mods.fml.client.FMLClientHandler;
import dev.modbench.bridge.BridgeRuntime;
import dev.modbench.bridge.Json;
import dev.modbench.bridge.Request;
import dev.modbench.api.InputArbiter;
import dev.modbench.api.Navigation;
import dev.modbench.api.NavigationRegistry;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ScreenShotHelper;
import org.lwjgl.input.Keyboard;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Direct Minecraft/Forge implementation. Baritone is intentionally absent from the classpath. */
public final class ClientRuntime extends BridgeRuntime {
    private final NeiAccess nei=new NeiAccess();
    final GuiOperations ui=new GuiOperations();
    public final ClientClock clock=new ClientClock(this);
    final InteractionOperations interactions=new InteractionOperations();
    private final ClientInterrupts interrupts=new ClientInterrupts(this);
    private final ClientObservations observations=new ClientObservations(this,interrupts::context,this::observeBatch);
    private final QuestAccess quests=new QuestAccess();
    private final Minecraft mc = Minecraft.getMinecraft();
    private Request control;
    private InputArbiter.Lease inputLease;
    private Request navigationRequest;
    private Navigation.Job navigationJob;
    private int remaining;
    private long refusalMark; // clicks the harness had refused when the current job or hold began
    private Object lastWorld, lastPlayer, identity;

    public Object identity() {
        if (lastWorld != mc.theWorld || lastPlayer != mc.thePlayer) {
            lastWorld = mc.theWorld;
            lastPlayer = mc.thePlayer;
            identity = new Object();
        }
        return identity;
    }

    public ClientRuntime() {
        super("client");
        register("quest.status","Native Better Questing availability and catalogue counts","read",r->quests.status());
        register("quest.sync","Native Better Questing full quest/progress and chapter synchronization query; receipt is pending server processing","read",r->quests.sync());
        register("quest.search","Search localized quest titles/descriptions {query,offset,limit:1..100}","read",r->{requirePlayer();return quests.search(mc.thePlayer,Json.string(r.params,"query",""),Json.integer(r.params,"offset",0,0,100000),Json.integer(r.params,"limit",20,1,100));});
        register("quest.lines","Native quest-line order, layout and player state totals {query,offset,limit:1..100}","read",r->{requirePlayer();return quests.lines(mc.thePlayer,Json.string(r.params,"query",""),Json.integer(r.params,"offset",0,0,100000),Json.integer(r.params,"limit",10,1,100));});
        register("quest.observe","Observe quest UUID {questId}: prerequisites, tasks/config/progress, rewards and choice options","read",r->{requirePlayer();return quests.observe(mc.thePlayer,Json.string(r.params,"questId",""));});
        register("quest.detect","Normal quest-wide detection {questId,taskIds:[int]}, clicking unfinished checkbox tasks first; receipt pending synchronization","interaction",r->{requirePlayer();return quests.detect(mc.thePlayer,Json.string(r.params,"questId",""),questIds(r.params,"taskIds"));});
        register("quest.select_choice","Normal explicit reward selection {questId,rewardId,choiceIndex}; receipt pending synchronization","interaction",r->{requirePlayer();return quests.selectChoice(mc.thePlayer,Json.string(r.params,"questId",""),Json.integer(r.params,"rewardId",0,0,Integer.MAX_VALUE),Json.integer(r.params,"choiceIndex",0,0,100000));});
        register("quest.claim","Normal quest-wide claim {questId,rewardIds:[int],choices:{rewardId:choiceIndex}}; no forced completion","interaction",r->{requirePlayer();Map<Integer,Integer> choices=new LinkedHashMap<>();if(r.params.has("choices"))for(var e:r.params.getAsJsonObject("choices").entrySet()){int id=Integer.parseInt(e.getKey());if(id<0)throw new IllegalArgumentException("invalid reward ID");choices.put(id,Json.integer(r.params.getAsJsonObject("choices"),e.getKey(),0,0,100000));}return quests.claim(mc.thePlayer,Json.string(r.params,"questId",""),questIds(r.params,"rewardIds"),choices);});
        register("obs.batch","Batched watchable reads {queries:{alias:{method,params}}}, max 16; server reads share serverTick, client reads share tick; sides not atomic; per-alias errors/context","read",observations::batch);
        for(String method:ClientObservations.METHODS)register(method,method.equals("obs.nbt")?
            "Immutable server NBT snapshot {handle,path:string|[exact keys/indices],offset,limit,budget:256..65536,depth:0..32}; expires after 10 minutes or cache eviction; re-read tile for live state":
            "Authoritative nearby loaded block {pos:[x,y,z]|x,y,z|crosshair,detail:summary|full,hwyla:true,inventoryOffset,inventoryLimit,budget,depth}; native NBT/config, inventory, per-side fluid and energy, native Waila provider text; no chunk loads", "read",observations::call);
        watchableRemote(ClientObservations.METHODS.toArray(new String[0]));
        register("interrupt.status","Interrupt latch, control operation and bridge/world context; receipts opt-in {eventId} or {limit:0..32}","read",r->interrupts.status(r.params));
        register("interrupt.fire","Urgent guarded reaction {eventId,reason,effects:[notify,cancel,pause],expectedContext,expectedOperationId?,latch,payload}; idempotent per client JVM","interaction",r->interrupts.fire(r));
        register("interrupt.ack","Clear an interrupt admission latch {eventId}; does not resume time or retry actions","interaction",r->interrupts.ack(r));
        for(String method:InteractionOperations.METHODS) register("act."+method,
            "Native bounded "+method+"; inspect sys.methods/docs for target, exact held guards, face/local hit, sneak, fluid and combat options; receipts distinguish native acceptance from observed effects","interaction",r->{
                controlsChanged("superseded");return interactions.start(r);
            });
        register("act.status","Current/last targeted interaction or combat receipt, including partial effects","read",r->interactions.status());
        for(String method:MemoryMethods.METHODS)
            register("memory."+method,MemoryMethods.description(method),Set.of("context","status","get").contains(method)?"read":"interaction",r->{
                if(method.equals("protect") || method.equals("remove") && Json.string(r.params,"kind","").equals("region")) controlsChanged("protection_changed");
                return MemoryMethods.call(method,r.params);
            });
        for(String method:new String[]{"time.status","time.pause","time.resume","time.configure","time.report_failure"})
            register(method,"Server time control; configure {healthDrop,healthBelow,airBelow,foodBelow,burning,threatWithin,actionFailed,pauseOnDisconnect}; threatWithin N (-1 off, at most 32) pauses with reason threat when a mob takes you as its target within N blocks (2N in line of sight) or a creeper starts to swell, and status.threats lists them",
                method.equals("time.status")?"read":"interaction",r->clock.command(r));
        register("nei.status","NEI catalogue readiness and available search/recipe operations","read",r->nei.status());
        register("nei.handlers","List all registered NEI categories and machine catalysts {query,offset,limit}","read",r->nei.handlers(r.params));
        register("nei.view","Open exact native recipe UI {id,meta,nbt} or {fluid}; {mode,handlerKey,index}; no crafting","interaction",r->{controlsChanged("gui_changed");return nei.view(r.params);});
        register("nei.inspect","Inspect native recipe tooltips and scroll {x,y,scroll:-1|0|1}; GUI coordinates","interaction",r->{controlsChanged("superseded");clock.presentation.refresh();return nei.inspect(r.params);});
        register("gui.hover","Move native GUI pointer {x,y} for custom tooltips; coordinates use GUI dimensions","interaction",r->{
            controlsChanged("superseded");ui.view.require(r.params);
            if(mc.currentScreen==null) throw new IllegalArgumentException("open a GUI first");
            int x=Json.integer(r.params,"x",0,0,mc.currentScreen.width-1),y=Json.integer(r.params,"y",0,0,mc.currentScreen.height-1);
            GuiPointer.move(mc.currentScreen,x,y);
            clock.presentation.refresh();
            return Json.object("x",x,"y",y,"guiWidth",mc.currentScreen.width,"guiHeight",mc.currentScreen.height);
        });
        register("nei.search","Native NEI query {query,id,meta,mod,ore,offset,limit}; exact item identities and ore names","read",r->nei.search(r));
        register("nei.fluids","Search canonical fluid IDs and physical properties {query,offset,limit}","read",r->nei.fluids(r.params));
        register("nei.item","Inspect exact item {id,meta,nbt}; metadata and NBT must come from observations/search","read",r->nei.item(r.params));
        register("nei.recipes","Native NEI recipes or uses {id,meta,nbt} or {fluid,amount}; {mode:recipes|uses,handler,offset,limit,alternativesOffset,alternativesLimit}","read",r->nei.recipes(r.params));
        register("obs.player", "Player state from the client", "read", r -> player());
        register("obs.entities", "Loaded entities {radius,limit}, client-instance handles, health, visibility, hostility and item stacks; obs.entity resolves durable server UUID", "read", r -> interactions.entities(r.params));
        register("obs.entity", "Resolve nearby loaded entity {entityId} or durable {uuid}; server UUID, last observed position; absence does not prove death", "read", r -> {
            requirePlayer();return clock.entity(r);
        });
        register("obs.world", "Client world and connection state", "read", r -> world());
        register("obs.inventory", "Player slots 0..35, armor, cursor and exact identities {detail:full|compact|counts}; totals keep NBT variants separate", "read", r -> ui.view.inventory(Json.string(r.params,"detail","full")));
        register("obs.block", "One loaded block {x,y,z | pos:[x,y,z]}: id, meta, hardness, harvest tool/level, tile, and picked (the pick-block item: its identity as the game names it)", "read", r -> block(r));
        register("obs.container", "Screen/container epoch, slots, geometry and widgets {detail:summary|full|compact,probeSlot?:observed source index}; probe reports native slot acceptance/capacity without picking up items", "read", r -> {ui.view.require(r.params);return ui.view.container(Json.string(r.params,"detail","summary"),r.params.has("probeSlot")?Json.integer(r.params,"probeSlot",0,0,4095):-1);});
        register("obs.gui", "Current screen class and dimensions", "read", r -> gui());
        register("obs.keys", "Registered key bindings", "read", r -> bindings());
        register("act.press_key", "Press registered binding {name,ticks:1..200,overrideProtection:false}", "interaction", r -> press(r));
        register("act.input", "Hold vanilla controls {keys:[forward,back,left,right,jump,sneak,sprint,attack,use],ticks:1..200,overrideProtection:false,allowRetarget:false}; attack locks the initial block and ends on change", "interaction", r -> input(r));
        register("act.look", "Set player view {yaw,pitch}", "interaction", r -> look(r));
        register("act.stop", "Release controls and cancel active or pending navigation, including Java API processes", "interaction", r -> {
            controlsChanged("cancelled");cancelNavigation("cancelled");return Json.object("stopped", true);
        });
        register("nav.status", "Standalone Baritone navigation state and supported movement", "read", r ->
            NavigationRegistry.get() == null ? java.util.Map.of("available",false) : NavigationRegistry.get().status());
        register("obs.light","Where mobs can spawn near you, from the light values F3 shows: {radius:8 (<=16),height:4,limit:32}. A spot is a solid top with two free cells above it and block light 7 or less; sky is its sky light (15 = open sky: dark only at night). Nearest first, plus the count of all of them; a torch gives 14 and loses 1 per block.","read",r->{
            requirePlayer();var world=mc.theWorld;var me=mc.thePlayer;
            int radius=Json.integer(r.params,"radius",8,1,16),height=Json.integer(r.params,"height",4,1,8),limit=Json.integer(r.params,"limit",32,1,256);
            int px=(int)Math.floor(me.posX),py=(int)Math.floor(me.boundingBox.minY),pz=(int)Math.floor(me.posZ);
            List<int[]> dark=new ArrayList<>();
            for(int x=px-radius;x<=px+radius;x++)for(int z=pz-radius;z<=pz+radius;z++)for(int y=Math.max(1,py-height);y<=Math.min(253,py+height);y++) {
                if(!world.blockExists(x,y,z)||!net.minecraft.world.World.doesBlockHaveSolidTopSurface(world,x,y-1,z))continue;
                boolean free=true;for(int up=0;up<2;up++){var material=world.getBlock(x,y+up,z).getMaterial();free&=!material.blocksMovement()&&!material.isLiquid();}
                int block=world.getSavedLightValue(net.minecraft.world.EnumSkyBlock.Block,x,y,z);
                if(free&&block<=7)dark.add(new int[]{x,y,z,block,world.getSavedLightValue(net.minecraft.world.EnumSkyBlock.Sky,x,y,z)});
            }
            dark.sort(java.util.Comparator.comparingDouble(s->me.getDistanceSq(s[0]+.5,s[1],s[2]+.5)));
            JsonArray spots=new JsonArray();
            for(int[] s:dark.subList(0,Math.min(limit,dark.size())))spots.add(Json.object("pos",Json.array(s[0],s[1],s[2]),"blockLight",s[3],"sky",s[4]));
            return Json.object("center",Json.array(px,py,pz),"radius",radius,"height",height,"spawnable",dark.size(),"underRoof",dark.stream().filter(s->s[4]<15).count(),"spots",spots);
        });
        register("obs.terrain", "Loaded feet cell {x,y,z}: collision boxes, standing height and ladder attachment", "read", r -> {
            requirePlayer();
            if(NavigationRegistry.get()==null) throw new IllegalArgumentException("Baritone mod is not installed");
            if(!r.params.has("x")||!r.params.has("y")||!r.params.has("z")) throw new IllegalArgumentException("x,y,z required");
            return NavigationRegistry.get().inspectTerrain(Json.integer(r.params,"x",0,-30000000,30000000),Json.integer(r.params,"y",0,1,254),Json.integer(r.params,"z",0,-30000000,30000000));
        });
        register("obs.fluid", "Inspect loaded fluid {x,y,z}: identity, source/drainability, signed fill, flow and traversal policy", "read", r -> {
            requirePlayer();
            Navigation provider=NavigationRegistry.get();
            if(provider==null) throw new IllegalArgumentException("Baritone mod is not installed");
            return provider.inspectFluid(Json.integer(r.params,"x",0,-30000000,30000000),Json.integer(r.params,"y",0,0,255),Json.integer(r.params,"z",0,-30000000,30000000));
        });
        register("obs.tools", "Every inventory slot against the block at {x,y,z}: the game's strength and harvest answer, eligibility and reason, toolsToAvoid and measured-ineffective flags, estimated ticks, and the one tool choice (bestSlot) every job uses; does not select or mine", "read", r -> {
            requirePlayer();Navigation provider=NavigationRegistry.get();
            if(provider==null) throw new IllegalArgumentException("Baritone mod is not installed");
            if(!r.params.has("x")||!r.params.has("y")||!r.params.has("z")) throw new IllegalArgumentException("x,y,z required");
            return provider.inspectTools(Json.integer(r.params,"x",0,-30000000,30000000),Json.integer(r.params,"y",0,1,254),Json.integer(r.params,"z",0,-30000000,30000000));
        });
        register("nav.place_block", "Place one block at loaded air {x,y,z,timeoutTicks:1..6000,items:[{id,meta,nbt,ore}]} with normal right-click input; items names what may be spent (any item: the game decides whether it places), default the acceptableThrowawayItems setting. Succeeds when the target is no longer air; the receipt's placed is {id,meta} of what is there and materialRule the rule used. overrideProtection:false by default", "interaction", r -> {
            requirePlayer();Navigation provider=NavigationRegistry.get();
            if(provider==null) throw new IllegalArgumentException("Baritone mod is not installed");
            if(!r.params.has("x")||!r.params.has("y")||!r.params.has("z")) throw new IllegalArgumentException("x,y,z required");
            controlsChanged("superseded");
            navigationJob=provider.placeBlock(Json.integer(r.params,"x",0,-30000000,30000000),Json.integer(r.params,"y",0,1,254),Json.integer(r.params,"z",0,-30000000,30000000),Json.integer(r.params,"timeoutTicks",1200,1,6000),Json.bool(r.params,"overrideProtection",false),r.params.has("items")?Json.GSON.fromJson(r.params.get("items"),List.class):null);
            navigationRequest=r;return null;
        });
        register("nav.mine_block", "Mine one reachable block {x,y,z,autoTool:true,timeoutTicks:1..6000}; protected regions refuse unless overrideProtection:true", "interaction", r -> {
            requirePlayer();
            Navigation provider=NavigationRegistry.get();
            if(provider==null) throw new IllegalArgumentException("Baritone mod is not installed");
            if(!r.params.has("x")||!r.params.has("y")||!r.params.has("z")) throw new IllegalArgumentException("x,y,z required");
            controlsChanged("superseded");
            navigationJob=provider.mineBlock(Json.integer(r.params,"x",0,-30000000,30000000),Json.integer(r.params,"y",0,1,254),Json.integer(r.params,"z",0,-30000000,30000000),Json.integer(r.params,"timeoutTicks",1200,1,6000),Json.bool(r.params,"autoTool",true),Json.bool(r.params,"overrideProtection",false));
            navigationRequest=r;
            return null;
        });
        register("nav.goto", "Navigate within 4096 blocks {x,y,z,allowBreak:false,allowPlace:false,overrideProtection:false,timeoutTicks:1..72000}; optional flat excavation and inventory-funded bridging; set caller timeout for long routes", "interaction", r -> {
            requirePlayer();
            Navigation provider = NavigationRegistry.get();
            if (provider == null) throw new IllegalArgumentException("Baritone mod is not installed");
            if (!r.params.has("x") || !r.params.has("y") || !r.params.has("z")) throw new IllegalArgumentException("x,y,z required");
            int x=Json.integer(r.params,"x",0,-30000000,30000000), y=Json.integer(r.params,"y",0,1,254), z=Json.integer(r.params,"z",0,-30000000,30000000);
            int timeout=Json.integer(r.params,"timeoutTicks",1200,1,72000);
            controlsChanged("superseded");
            ControlRegistry.controls().focusForInput();
            navigationJob=provider.goTo(x,y,z,timeout,Json.bool(r.params,"allowBreak",false),Json.bool(r.params,"allowPlace",false),Json.bool(r.params,"overrideProtection",false));
            navigationRequest=r;
            return null;
        });
        register("nav.route", "Follow saved route {name,reverse:false,startIndex:0,allowBreak:false,allowPlace:false,overrideProtection:false,timeoutTicks:1..72000}; approach first anchor, then stay inside each corridor", "interaction", r -> {
            requirePlayer();Navigation provider=NavigationRegistry.get();
            if(provider==null) throw new IllegalArgumentException("Baritone mod is not installed");
            controlsChanged("superseded");ControlRegistry.controls().focusForInput();
            navigationJob=provider.route(Json.string(r.params,"name",""),Json.bool(r.params,"reverse",false),Json.integer(r.params,"startIndex",0,0,4095),
                Json.integer(r.params,"timeoutTicks",1200,1,72000),Json.bool(r.params,"allowBreak",false),Json.bool(r.params,"allowPlace",false),Json.bool(r.params,"overrideProtection",false));
            navigationRequest=r;return null;
        });
        register("gui.open_inventory", "Open the player's inventory", "interaction", r -> {
            requirePlayer(); controlsChanged("gui_changed"); mc.displayGuiScreen(new GuiInventory(mc.thePlayer)); return gui();
        });
        register("gui.close", "Close the current player screen", "interaction", r -> {
            requirePlayer(); if(mc.thePlayer.inventory.getItemStack()!=null&&!Json.bool(r.params,"allowCursorDrop",false)) throw new IllegalArgumentException("cursor_occupied: return cursor before closing"); controlsChanged("gui_changed"); mc.thePlayer.closeScreen(); return gui();
        });
        for(String method:GuiOperations.METHODS) register("gui."+method,GuiOperations.description(method),"interaction",r->{
            if(clock.isPaused()) throw new IllegalArgumentException("time_paused: resume before executing native GUI actions");
            controlsChanged("superseded");return ui.start(r);
        });
        for(String method:List.of("mine","build","resume")) register("nav."+method,"Owned, checkpointed "+method+" process; timeoutTicks<=72000. Mine: blocks/items selectors, quantity, bounds/radius, toolSlot (forces the tool in that slot). Build: cells, selection or planId; mode blueprint/builder, origin, size, settings, replaceExisting, allowBreak/allowPlace. Resume: jobId. Explicit overrideProtection required each attempt.","interaction",r->{
            requirePlayer();Navigation provider=navigation();Map<String,Object> params=Json.GSON.fromJson(r.params,Map.class);params.remove("_timeout_ms");controlsChanged("superseded");ControlRegistry.controls().focusForInput();
            navigationJob=method.equals("mine")?provider.mine(params):method.equals("build")?provider.build(params):provider.resume(Json.string(r.params,"jobId",""),params);navigationRequest=r;return null;
        });
        register("nav.build_preview","Fresh read-only build diff and material allocation for {cells|selection|planId,mode,settings,origin,size,replaceExisting,overrideProtection}; no chunk loading","read",r->navigation().previewBuild(Json.GSON.fromJson(r.params,Map.class)));
        register("nav.follow","Source FollowProcess: {target:{entityId|uuid|type|name},durationTicks:1..72000,radius,offsetDistance,offsetDirection,allowBreak:false,allowPlace:false,overrideProtection:false}. Follows loaded matches until duration/cancellation; fails when none remain loaded.","interaction",r->{
            requirePlayer();Map<String,Object> params=Json.GSON.fromJson(r.params,Map.class);params.remove("_timeout_ms");controlsChanged("superseded");ControlRegistry.controls().focusForInput();
            navigationJob=navigation().follow(params);navigationRequest=r;return null;
        });
        register("nav.fight","One fight as a job: {entityId (from obs.entities or time.status threats) or target:{entityId|uuid|type|name|class} (any entity, nearest match in sight), hostile:[selectors] (default [{class:net.minecraft.entity.monster.IMob}]; used for hold, maxAttackers and the report, never to refuse a target), allowBreak:false, allowPlace:false, hold:false, durationTicks:600 (<=6000), leash:16 blocks from where you stood, bailHealth:8, maxAttackers:2, weaponSlot:0..8, crit:true, block:true, intervalTicks:10, ranged:{drawTicks,reloadTicks,minRange:6,maxRange:20,clickAfterLoad,speed,gravity,drag}}. ranged fights with the launcher or throwable in hand: usage is found by trying (hold and release, else click), ballistics are measured from its own shots and kept per weapon name; fails no_projectile_fired after three tries. Paths to the mob without breaking or placing, then in reach blocks with a sword and swings while falling for critical hits; backs away from its target creeper while it swells. hold:true never moves: it hits the named target, or with none the nearest match of the hostile rule in sight, when one comes into reach, and succeeds once none is in sight. Fails, which the actionFailed guard turns into a pause, on health_at_bail_line, outnumbered, creeper_swelling (another one), target_beyond_leash, target_lost, cannot_reach_target, duration_elapsed. Receipt: target {entityId,type,class,health (null when not living),hostile,distance}, hostileRule, jobSettings (the settings in force for this job, restored after), and for ranged adjustments (changes made to the given numbers, from->to) and shotEntities (classes seen as the shot).","interaction",r->{
            requirePlayer();Map<String,Object> params=Json.GSON.fromJson(r.params,Map.class);params.remove("_timeout_ms");controlsChanged("superseded");ControlRegistry.controls().focusForInput();
            navigationJob=navigation().fight(params);navigationRequest=r;
            if(params.get("entityId") instanceof Number id)clock.expectThreat(id.intValue()); // the threat guard warns of mobs the model has not answered yet
            return null;
        });
        register("nav.settings","Source settings {operation:get|set|reset,query,values,save}; typed values or source syntax, atomic edits while idle. Optional declarations do not promise runtime support.","interaction",r->navigation().settings(Json.GSON.fromJson(r.params,Map.class)));
        register("nav.build_pause","Pause active construction and release controls; retains jobId for nav.resume. Server time continues.","interaction",r->navigation().pauseBuild());
        register("nav.build_materials","Approximate placeable native inventory states; final state depends on native placement callbacks","read",r->navigation().buildMaterials());
        register("nav.cache","Source terrain cache {operation:status|block(pos)|repack(range)|save|reload|locations(block,meta?,limit,regionDistanceSquared)|result(id)}. Disk operations return an id to poll. Cached states are approximate and need native verification.","interaction",r->navigation().cache(Json.GSON.fromJson(r.params,Map.class)));
        register("nav.process","Source {process:goal|explore|get_to_block|farm,durationTicks:1..72000,goal:{type,...},center:[x,y,z],radius,block:{id,meta} or {item:{id,meta,nbt}|ore} (picked identity, scanned within radius),crops:[block selectors],soils:[block selectors],seeds:[item selectors],fertilizers:[item selectors],collect:[item selectors],allowBreak:false,allowPlace:false,exploreForBlocks:true,openOnArrival:false,enterPortal:false}. Goal types: block,near,adjacent,two_blocks,xz,y,axis,inverted,composite,run_away. Farm/explore run for a bounded duration. Farm defaults (shown in farmRules): vanilla crops and farmland/soul_sand; seeds = any IPlantable the soil accepts; fertilizer bone meal; collect any item on the ground in bounds. Ripe: a crop selector with meta is that state, else IGrowable that cannot grow, else IPlantable above its own kind, else as it stands. farmSeen counts ready, growing, fertilizable, openSoil, openSoilWithoutSeed, soilsWithoutSeed, drops, cropSelectorsMatchingNothing (indexes).","interaction",r->{
            requirePlayer();Map<String,Object> params=Json.GSON.fromJson(r.params,Map.class);params.remove("_timeout_ms");controlsChanged("superseded");ControlRegistry.controls().focusForInput();
            navigationJob=navigation().sourceProcess(params);navigationRequest=r;return null;
        });
        register("nav.build_stage","Stage a large immutable plan: operation begin(spec), append(stageId,offset,cells), finish(stageId). Finish returns planId for build/preview.","interaction",r->{Map<String,Object> params=Json.GSON.fromJson(r.params,Map.class);params.remove("_timeout_ms");return navigation().stageBuild(params);});
        register("obs.scan","Paged native block/metadata/ore-dictionary/item selectors {blocks:[selector],bounds:{min,max},cursor,limit,budget}; reports unloaded cells","read",r->navigation().scan(Json.GSON.fromJson(r.params,Map.class)));
        register("nav.schematic_import","Read <gameDir>/schematics/{path} (MCEdit .schematic or a canonical JSON plan) into {plan:{cells,origin,size},size:[w,h,l],count,skipped:{air,unknown}}; plan is accepted by nav.build_preview/nav.build. Params {path,origin?:[x,y,z],includeAir?}; at most 1048576 cells","read",r->navigation().importSchematic(Json.GSON.fromJson(r.params,Map.class)));
        register("nav.copy","Copy loaded blocks in {bounds:{min,max}} into {plan:{cells,origin,size},size,count,skipped:{air,unknown,unloaded},tileEntities}; positions are relative so that bounds.min maps to origin (default [0,0,0]). Tile entities are copied as plain blocks (no NBT). Params {bounds,origin?,includeAir?}; at most 1048576 cells","read",r->navigation().copy(Json.GSON.fromJson(r.params,Map.class)));
        register("nav.work_status","Durable process intent and last receipt {jobId}; active status remains nav.status","read",r->navigation().workStatus(Json.string(r.params,"jobId","")));
        register("gui.status","Last/current GUI action receipt, including partial effects and native transaction acknowledgements","read",r->ui.status());
        register("gui.hit_test","Inspect native slots, buttons, text fields and custom widget geometry under {x,y}","read",r->{
            if(mc.currentScreen==null) throw new IllegalArgumentException("open GUI first");
            return UiWidgets.hitTest(mc.currentScreen,Json.integer(r.params,"x",0,0,mc.currentScreen.width-1),Json.integer(r.params,"y",0,0,mc.currentScreen.height-1));
        });
        register("obs.tooltip","Native tooltip for {slot:containerIndex} or {inv:playerIndex} or {cursor:true}; defaults held stack; {advanced:true}","read",r->{
            requirePlayer();ItemStack stack;
            if(r.params.has("slot")) stack=InventoryView.slot(ui.view.require(r.params),Json.integer(r.params,"slot",0,0,4095)).getStack();
            else if(r.params.has("inv")) stack=mc.thePlayer.inventory.getStackInSlot(Json.integer(r.params,"inv",0,0,35));
            else stack=Json.bool(r.params,"cursor",false)?mc.thePlayer.inventory.getItemStack():mc.thePlayer.getHeldItem();
            return Stacks.tooltip(stack,Json.bool(r.params,"advanced",true));
        });
        register("obs.find","Find observed inventory/container stacks {selector:{id,meta?,nbt_hash?,nbt?},scope:player|container}","read",r->{
            requirePlayer();if(!r.params.has("selector")||!r.params.get("selector").isJsonObject()) throw new IllegalArgumentException("selector required");
            JsonObject selector=r.params.getAsJsonObject("selector");JsonArray matches=new JsonArray();
            String scope=Json.string(r.params,"scope","player");
            if(scope.equals("player")) {for(int i=0;i<36;i++) if(Stacks.matches(mc.thePlayer.inventory.mainInventory[i],selector)) matches.add(Json.object("slot",i,"stack",Stacks.json(mc.thePlayer.inventory.mainInventory[i])));}
            else if(scope.equals("container")) {var container=ui.view.require(r.params);for(int i=0;i<container.inventorySlots.size();i++) {var slot=InventoryView.slot(container,i);if(Stacks.matches(slot.getStack(),selector)) matches.add(Json.object("slot",i,"stack",Stacks.json(slot.getStack()),"virtual",slot.slotNumber!=i));}}
            else throw new IllegalArgumentException("scope must be player or container");
            return Json.object("matches",matches,"scope",scope,"epoch",ui.view.epoch());
        });
        register("sys.screenshot", "Capture client framebuffer as base64 PNG", "read", r -> screenshot());
        register("map.view", "JourneyMap overhead picture {center:[x,z]=player,radius:16..2048=128,layer:day|night|topo|cave|slice}: gridded PNG, bounds, mapped fraction, JourneyMap and memory waypoints", "read", r -> JourneyMapAccess.view(r.params));
        register("sys.connect", "Join a server {host,port}; completion means connection initiated", "interaction", r -> {
            if (mc.theWorld != null) throw new IllegalArgumentException("disconnect before connecting");
            // Two launchers once asked at the same moment; two concurrent FML handshakes both remap the registries and the join crashes.
            if (mc.currentScreen instanceof net.minecraft.client.multiplayer.GuiConnecting) throw new IllegalArgumentException("already connecting");
            String host = Json.string(r.params, "host", "127.0.0.1");
            int port = Json.integer(r.params, "port", 25575, 1, 65535);
            if (host.isBlank()) throw new IllegalArgumentException("host must not be blank");
            if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
            mc.gameSettings.pauseOnLostFocus = false;
            // Forge owns handshake initialization; raw GuiConnecting leaves its latch unset.
            FMLClientHandler.instance().setupServerList();
            FMLClientHandler.instance().connectToServer(new GuiMainMenu(), new ServerData("ModdedBench", host + ":" + port));
            return Json.object("connecting", true);
        });
        register("sys.disconnect", "Leave the current server", "interaction", r -> {
            controlsChanged("disconnected");
            if (mc.theWorld != null) mc.theWorld.sendQuittingDisconnectingPacket();
            mc.loadWorld(null); mc.displayGuiScreen(new GuiMainMenu()); return Json.object("disconnected", true);
        });
        register("sys.shutdown", "Gracefully close this Minecraft client", "privileged", r -> {
            controlsChanged("shutdown"); mc.shutdown(); return Json.object("shuttingDown", true);
        });
        watchable("obs.player","obs.world","obs.entities","obs.inventory","obs.block","obs.container","obs.gui","obs.tooltip",
            "obs.find","gui.status","act.status","interrupt.status","nav.status","obs.terrain","obs.fluid","obs.tools",
            "memory.context","memory.status","memory.get","time.status","obs.keys","quest.status","quest.observe");
    }
    private Navigation navigation(){requirePlayer();Navigation p=NavigationRegistry.get();if(p==null)throw new IllegalArgumentException("Baritone mod is not installed");return p;}
    private List<Integer> questIds(JsonObject params,String key){if(!params.has(key)||!params.get(key).isJsonArray()||params.getAsJsonArray(key).size()>256)throw new IllegalArgumentException(key+" array required (max 256)");List<Integer> out=new ArrayList<>();for(JsonElement e:params.getAsJsonArray(key)){JsonObject one=Json.object("id",e);out.add(Json.integer(one,"id",0,0,Integer.MAX_VALUE));}return out;}

    @Override public Object capabilities() {
        JsonObject out=(JsonObject) super.capabilities();
        out.addProperty("baritone",NavigationRegistry.get()!=null);
        out.addProperty("inputOwnership",true);
        out.addProperty("generalGui",true);
        out.addProperty("exactTransfers",true);
        out.addProperty("worldMemory",true);
        out.addProperty("regionProtection",true);
        out.addProperty("tickControl",true);
        out.addProperty("nei",cpw.mods.fml.common.Loader.isModLoaded("NotEnoughItems"));
        return out;
    }

    private void requirePlayer() {
        if (mc.thePlayer == null || mc.theWorld == null) throw new IllegalArgumentException("player is not in a world");
    }

    private JsonObject player() {
        requirePlayer();
        var p = mc.thePlayer;
        int x = (int) Math.floor(p.posX), z = (int) Math.floor(p.posZ);
        return Json.object("name", p.getCommandSenderName(), "uuid", p.getUniqueID().toString(),
            "uuidScope", "client_profile", "entityId", p.getEntityId(),
            // In 1.7.10 the local player's posY includes the stance/eye offset.
            "pos", Json.array(p.posX, p.boundingBox.minY, p.posZ), "positionAnchor", "feet",
            "velocity", Json.array(p.motionX,p.motionY,p.motionZ), "burning",p.isBurning(),
            "yaw", p.rotationYaw, "pitch", p.rotationPitch,
            "health", p.getHealth(), "food", p.getFoodStats().getFoodLevel(), "air", p.getAir(),
            "onGround", p.onGround, "inWater", p.isInWater(), "onLadder",p.isOnLadder(), "stepHeight",p.stepHeight, "dimension", p.dimension,
            "selectedSlot", p.inventory.currentItem, "held", stack(p.getHeldItem()),
            "gui", mc.currentScreen == null ? null : mc.currentScreen.getClass().getName(),
            "sneaking", p.isSneaking(), "controlActive", ControlRegistry.controls().arbiter().current().active(),
            "controlOwner", ControlRegistry.controls().arbiter().current().label(),
            "effects", effects(p), "blocks", Json.object("feet", cell(x, (int) Math.floor(p.boundingBox.minY + .001), z),
                "head", cell(x, (int) Math.floor(p.posY), z), "under", cell(x, (int) Math.floor(p.boundingBox.minY - .01), z)));
    }

    /** The potion effects on the player, as its inventory screen shows them. */
    private static com.google.gson.JsonArray effects(net.minecraft.entity.player.EntityPlayer p) {
        var out = new com.google.gson.JsonArray();
        for (Object o : p.getActivePotionEffects()) if (o instanceof net.minecraft.potion.PotionEffect e) {
            var potion = e.getPotionID() < net.minecraft.potion.Potion.potionTypes.length ? net.minecraft.potion.Potion.potionTypes[e.getPotionID()] : null;
            out.add(Json.object("id", e.getPotionID(), "name", potion == null ? null : potion.getName(), "amplifier", e.getAmplifier(), "durationTicks", e.getDuration()));
        }
        return out;
    }

    private JsonObject cell(int x, int y, int z) {
        if (y < 0 || y > 255 || !mc.theWorld.blockExists(x, y, z) || mc.theWorld.getChunkFromChunkCoords(x >> 4, z >> 4).isEmpty()) return null;
        return Json.object("id", Block.blockRegistry.getNameForObject(mc.theWorld.getBlock(x, y, z)), "meta", mc.theWorld.getBlockMetadata(x, y, z));
    }

    private JsonObject world() {
        return Json.object("inWorld", mc.theWorld != null, "integratedServer", mc.isIntegratedServerRunning(),
            "dimension", mc.theWorld == null ? null : mc.theWorld.provider.dimensionId,
            "time", mc.theWorld == null ? null : mc.theWorld.getWorldTime(),
            "tickMode",clock.status().getAsJsonObject("state").get("mode"),"clock", clock.status());
    }

    private JsonObject stack(ItemStack s) {
        return Stacks.json(s);
    }

    private JsonObject block(Request r) {
        requirePlayer();
        JsonObject p = r.params;
        if (p.has("pos")) {  // the same {pos:[x,y,z]} the tile and waila reads take
            JsonArray a = p.getAsJsonArray("pos");
            if (a.size() != 3) throw new IllegalArgumentException("pos must be [x,y,z]");
            p = Json.object("x", a.get(0), "y", a.get(1), "z", a.get(2));
        }
        if (!p.has("x") || !p.has("y") || !p.has("z")) throw new IllegalArgumentException("give x,y,z or pos:[x,y,z]");
        int x = Json.integer(p, "x", 0, -30000000, 30000000);
        int y = Json.integer(p, "y", 0, 0, 255);
        int z = Json.integer(p, "z", 0, -30000000, 30000000);
        // Client chunkExists is unconditional in 1.7.10; reject its missing-chunk placeholder.
        if (!mc.theWorld.blockExists(x, y, z) || mc.theWorld.getChunkFromChunkCoords(x>>4,z>>4).isEmpty()) throw new IllegalArgumentException("block is not loaded");
        Block b = mc.theWorld.getBlock(x, y, z);
        int meta = mc.theWorld.getBlockMetadata(x, y, z);
        return Json.object("pos", Json.array(x, y, z), "id", Block.blockRegistry.getNameForObject(b),
            "meta", meta, "hardness", b.getBlockHardness(mc.theWorld, x, y, z),
            "harvestTool", b.getHarvestTool(meta), "harvestLevel", b.getHarvestLevel(meta),
            "hasTile", mc.theWorld.getTileEntity(x, y, z) != null, "picked", picked(b, x, y, z));
    }
    /** What pick-block (middle click) gives here: a block's identity as the game names it, which for GregTech ores and
     *  machines lives in the tile entity rather than the meta. Null when the block picks nothing. */
    private JsonObject picked(Block b, int x, int y, int z) {
        try {
            return stack(b.getPickBlock(new net.minecraft.util.MovingObjectPosition(x, y, z, 1, net.minecraft.util.Vec3.createVectorHelper(x + .5, y + .5, z + .5)), mc.theWorld, x, y, z, mc.thePlayer));
        } catch (RuntimeException | LinkageError failed) {return null;}
    }

    private JsonObject gui() {
        GuiScreen s = mc.currentScreen;
        return Json.object("class", s == null ? null : s.getClass().getName(),
            "width", s == null ? mc.displayWidth : s.width, "height", s == null ? mc.displayHeight : s.height);
    }

    private JsonArray bindings() {
        JsonArray out = new JsonArray();
        for (KeyBinding key : mc.gameSettings.keyBindings) out.add(Json.object("name", key.getKeyDescription(),
            "code", key.getKeyCode(), "category", key.getKeyCategory(), "pressed", key.getIsKeyPressed()));
        return out;
    }

    private Object press(Request r) {
        requirePlayer();
        String name = Json.string(r.params, "name", "");
        List<KeyBinding> found = new ArrayList<>();
        for (KeyBinding key : mc.gameSettings.keyBindings) if (name.equals(key.getKeyDescription())) found.add(key);
        if (found.size() != 1 || found.get(0).getKeyCode() == 0) throw new IllegalArgumentException("binding missing, ambiguous or unbound");
        int code = found.get(0).getKeyCode();
        for (KeyBinding key : mc.gameSettings.keyBindings) {
            if (key != found.get(0) && key.getKeyCode() == code) throw new IllegalArgumentException("binding conflicts with " + key.getKeyDescription());
        }
        return hold(r, Set.of(code));
    }

    private Object input(Request r) {
        requirePlayer();
        if (mc.currentScreen != null) throw new IllegalArgumentException("close GUI before movement input");
        if (!r.params.has("keys") || !r.params.get("keys").isJsonArray()) throw new IllegalArgumentException("keys must be an array");
        Set<Integer> codes = new LinkedHashSet<>();
        for (JsonElement e : r.params.getAsJsonArray("keys")) {
            String key = e.getAsString();
            KeyBinding binding = switch (key) {
                case "forward" -> mc.gameSettings.keyBindForward;
                case "back" -> mc.gameSettings.keyBindBack;
                case "left" -> mc.gameSettings.keyBindLeft;
                case "right" -> mc.gameSettings.keyBindRight;
                case "jump" -> mc.gameSettings.keyBindJump;
                case "sneak" -> mc.gameSettings.keyBindSneak;
                case "sprint" -> mc.gameSettings.keyBindSprint;
                case "attack" -> mc.gameSettings.keyBindAttack;
                case "use" -> mc.gameSettings.keyBindUseItem;
                default -> throw new IllegalArgumentException("unknown input " + key);
            };
            codes.add(binding.getKeyCode());
        }
        if (codes.isEmpty()) throw new IllegalArgumentException("keys must not be empty");
        ControlRegistry.controls().focusForInput();
        return hold(r, codes);
    }

    private Object hold(Request r, Set<Integer> codes) {
        int ticks = Json.integer(r.params, "ticks", 1, 1, 200);
        controlsChanged("superseded");
        control = r;
        remaining = ticks;
        ControlRegistry.controls().focusForInput();
        inputLease=ControlRegistry.controls().arbiter().acquire("direct",reason -> {
            if (control == r) { release(); r.fail("cancelled", "input released: "+reason); }
        },Json.bool(r.params,"overrideProtection",false));
        if(codes.contains(mc.gameSettings.keyBindAttack.getKeyCode())&&!Json.bool(r.params,"allowRetarget",false))
            ControlRegistry.controls().guardBlockAttack(inputLease);
        inputLease.setKeys(codes);
        return null; // Completed at END after exactly the requested client ticks, or interrupted.
    }

    @Override protected void maintainControls() {
        nei.pump(clock.isPaused());
        ui.maintain();
        interactions.maintain();
        if (navigationRequest != null && (navigationRequest.isDone() || !navigationRequest.session.connected || navigationRequest.expired())) {
            navigationJob.cancel("cancelled");
            navigationRequest.fail("cancelled", "navigation owner disconnected or cancelled");
            navigationRequest=null; navigationJob=null;
        }
        if (navigationRequest != null && clock.guardPause()) { // no tick will end this job: hand back what it did, now, with why
            navigationJob.cancel("world_paused: "+clock.pauseReason());
            navigationRequest.fail("cancelled","world paused by a guard ("+clock.pauseReason()+"): read mb_time status, decide, resume",refused(navigationJob.status()));
            navigationRequest=null; navigationJob=null;
        }
        if (control == null) return;
        if (control.isDone() || !control.session.connected || control.expired()) controlsChanged("cancelled");
    }

    public void endTick() {
        ui.tick();
        interactions.tick();
        dev.modbench.api.ControlRegistry.memory().sample();
        if (navigationRequest != null && navigationJob.done()) {
            Request r=navigationRequest; Navigation.Job job=navigationJob;
            navigationRequest=null; navigationJob=null;
            Map<String,Object> receipt=refused(job.status());
            if (job.succeeded() || "paused".equals(receipt.get("state"))) r.reply(receipt);
            else {
                boolean cancelled=receipt.get("state").equals("cancelled");
                r.fail(cancelled ? "cancelled" : "path_failed", String.valueOf(receipt.get("reason")),receipt);
                if(!cancelled) clock.actionFailed();
            }
        }
        if (control == null) return;
        if (mc.theWorld != lastWorld || mc.thePlayer != lastPlayer || mc.thePlayer == null) {
            controlsChanged("world_changed"); return;
        }
        if (control.isDone() || !control.session.connected || control.expired()) { controlsChanged("cancelled"); return; }
        boolean targetChanged=ControlRegistry.controls().blockAttackChanged(inputLease);
        if (--remaining <= 0 || targetChanged) {
            Request finished = control;
            release();
            JsonObject receipt=Json.object("completed", true, "outcome",targetChanged?"attack_target_changed":"duration_elapsed", "player", player(), "serverAcknowledged", false);
            ControlRegistry.memory().refusedSince(refusalMark).forEach((k,v)->receipt.add(k,Json.GSON.toJsonTree(v)));
            finished.reply(receipt);
        }
    }

    private void release() {
        ControlRegistry.controls().releaseBlockAttack(inputLease);
        if (inputLease != null) inputLease.close();
        inputLease=null; control=null; remaining=0;
    }

    @Override protected void controlsChanged(String reason) {
        interactions.cancel(reason);
        ui.cancel(reason);
        Request previous = control;
        release();
        if (previous != null) previous.fail(reason, "input released: " + reason);
        if (navigationJob != null) navigationJob.cancel(reason);
        if (navigationRequest != null) navigationRequest.fail(reason, "navigation released: " + reason);
        navigationRequest=null; navigationJob=null;
        ControlRegistry.controls().arbiter().revoke(reason);
        refusalMark=ControlRegistry.memory().refusals();
    }
    /** A job's receipt with the clicks the harness refused it (protected regions, a held attack locked to its block). */
    private Map<String,Object> refused(Map<String,Object> status) {
        var refusals=ControlRegistry.memory().refusedSince(refusalMark);if(refusals.isEmpty()) return status;
        Map<String,Object> out=new LinkedHashMap<>(status);out.putAll(refusals);return out;
    }

    private void cancelNavigation(String reason){var navigation=NavigationRegistry.get();if(navigation!=null)navigation.cancel(reason);}
    void interruptControls() {cancelQueuedInteractions();controlsChanged("interrupted");cancelNavigation("interrupted");}
    @Override protected void admit(Request r) {
        interrupts.admit(r);
        if(readOnly(r.method))return;
        String m=r.method;
        if(clock.isPaused()&&(m.startsWith("act.")&&!Set.of("act.stop","act.look").contains(m)
            ||m.startsWith("nav.")&&!Set.of("nav.settings","nav.cache").contains(m)||m.startsWith("quest.")))
            throw new IllegalArgumentException("time_paused: resume before starting simulation actions");
    }

    private JsonObject look(Request r) {
        requirePlayer();
        float yaw = (float) Json.number(r.params, "yaw", mc.thePlayer.rotationYaw, -360000, 360000);
        float pitch = (float) Json.number(r.params, "pitch", mc.thePlayer.rotationPitch, -90, 90);
        controlsChanged("superseded");
        try (InputArbiter.Lease lease=ControlRegistry.controls().arbiter().acquire("direct-look",reason -> {})) { lease.look(yaw,pitch); }
        return player();
    }

    private JsonObject screenshot() throws Exception {
        String name = "modbench-" + java.util.UUID.randomUUID() + ".png";
        ScreenShotHelper.saveScreenshot(mc.mcDataDir, name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        Path file = mc.mcDataDir.toPath().resolve("screenshots").resolve(name);
        byte[] png = Files.readAllBytes(file);
        Files.delete(file);
        return Json.object("png", Base64.getEncoder().encodeToString(png), "width", mc.displayWidth, "height", mc.displayHeight);
    }
}
