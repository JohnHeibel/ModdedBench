// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.client;

import java.util.Map;
import java.util.LinkedHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cpw.mods.fml.client.FMLClientHandler;
import dev.modbench.bridge.BridgeRuntime;
import dev.modbench.bridge.Json;
import dev.modbench.bridge.Request;
import dev.modbench.control.ClientControls;
import dev.modbench.control.api.InputArbiter;
import dev.modbench.control.api.Navigation;
import dev.modbench.control.api.NavigationRegistry;
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
        register("quest.detect","Normal quest-wide detection {questId,taskIds:[int]}; receipt pending synchronization","interaction",r->{requirePlayer();return quests.detect(mc.thePlayer,Json.string(r.params,"questId",""),questIds(r.params,"taskIds"));});
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
            register(method,"Server time control; configure {healthDrop,healthBelow,airBelow,foodBelow,burning,actionFailed,pauseOnDisconnect}",
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
        register("obs.block", "One loaded block {x,y,z}", "read", r -> block(r));
        register("obs.container", "Screen/container epoch, slots, geometry and widgets {detail:summary|full|compact,probeSlot?:observed source index}; probe reports native slot acceptance/capacity without picking up items", "read", r -> {ui.view.require(r.params);return ui.view.container(Json.string(r.params,"detail","summary"),r.params.has("probeSlot")?Json.integer(r.params,"probeSlot",0,0,4095):-1);});
        register("obs.gui", "Current screen class and dimensions", "read", r -> gui());
        register("keys.list", "Registered key bindings", "read", r -> bindings());
        register("keys.press", "Press registered binding {name,ticks:1..200,overrideProtection:false}", "interaction", r -> press(r));
        register("act.input", "Hold vanilla controls {keys:[forward,back,left,right,jump,sneak,sprint,attack,use],ticks:1..200,overrideProtection:false,allowRetarget:false}; attack locks the initial block and ends on change", "interaction", r -> input(r));
        register("act.look", "Set player view {yaw,pitch}", "interaction", r -> look(r));
        register("act.stop", "Release controls and cancel active or pending navigation, including Java API processes", "interaction", r -> {
            controlsChanged("cancelled");cancelNavigation("cancelled");return Json.object("stopped", true);
        });
        register("baritone.status", "Standalone Baritone navigation state and supported movement", "read", r ->
            NavigationRegistry.get() == null ? java.util.Map.of("available",false) : NavigationRegistry.get().status());
        register("baritone.terrain", "Loaded feet cell {x,y,z}: collision boxes, standing height and ladder attachment", "read", r -> {
            requirePlayer();
            if(NavigationRegistry.get()==null) throw new IllegalArgumentException("Baritone mod is not installed");
            if(!r.params.has("x")||!r.params.has("y")||!r.params.has("z")) throw new IllegalArgumentException("x,y,z required");
            return NavigationRegistry.get().inspectTerrain(Json.integer(r.params,"x",0,-30000000,30000000),Json.integer(r.params,"y",0,1,254),Json.integer(r.params,"z",0,-30000000,30000000));
        });
        register("baritone.fluid", "Inspect loaded fluid {x,y,z}: identity, source/drainability, signed fill, flow and traversal policy", "read", r -> {
            requirePlayer();
            Navigation provider=NavigationRegistry.get();
            if(provider==null) throw new IllegalArgumentException("Baritone mod is not installed");
            return provider.inspectFluid(Json.integer(r.params,"x",0,-30000000,30000000),Json.integer(r.params,"y",0,0,255),Json.integer(r.params,"z",0,-30000000,30000000));
        });
        register("baritone.tools", "Inspect inventory harvest eligibility, tool NBT and estimated break ticks for {x,y,z}; does not select or mine", "read", r -> {
            requirePlayer();Navigation provider=NavigationRegistry.get();
            if(provider==null) throw new IllegalArgumentException("Baritone mod is not installed");
            if(!r.params.has("x")||!r.params.has("y")||!r.params.has("z")) throw new IllegalArgumentException("x,y,z required");
            return provider.inspectTools(Json.integer(r.params,"x",0,-30000000,30000000),Json.integer(r.params,"y",0,1,254),Json.integer(r.params,"z",0,-30000000,30000000));
        });
        register("baritone.place_block", "Place one common support cube at loaded air {x,y,z,timeoutTicks:1..6000}, using inventory and normal input; overrideProtection:false by default", "interaction", r -> {
            requirePlayer();Navigation provider=NavigationRegistry.get();
            if(provider==null) throw new IllegalArgumentException("Baritone mod is not installed");
            if(!r.params.has("x")||!r.params.has("y")||!r.params.has("z")) throw new IllegalArgumentException("x,y,z required");
            controlsChanged("superseded");
            navigationJob=provider.placeBlock(Json.integer(r.params,"x",0,-30000000,30000000),Json.integer(r.params,"y",0,1,254),Json.integer(r.params,"z",0,-30000000,30000000),Json.integer(r.params,"timeoutTicks",1200,1,6000),Json.bool(r.params,"overrideProtection",false));
            navigationRequest=r;return null;
        });
        register("baritone.mine_block", "Mine one reachable block {x,y,z,autoTool:true,timeoutTicks:1..6000}; requires stable footing and dry escape step; overrideProtection:false by default", "interaction", r -> {
            requirePlayer();
            Navigation provider=NavigationRegistry.get();
            if(provider==null) throw new IllegalArgumentException("Baritone mod is not installed");
            if(!r.params.has("x")||!r.params.has("y")||!r.params.has("z")) throw new IllegalArgumentException("x,y,z required");
            controlsChanged("superseded");
            navigationJob=provider.mineBlock(Json.integer(r.params,"x",0,-30000000,30000000),Json.integer(r.params,"y",0,1,254),Json.integer(r.params,"z",0,-30000000,30000000),Json.integer(r.params,"timeoutTicks",1200,1,6000),Json.bool(r.params,"autoTool",true),Json.bool(r.params,"overrideProtection",false));
            navigationRequest=r;
            return null;
        });
        register("baritone.goto", "Navigate within 4096 blocks {x,y,z,allowBreak:false,allowPlace:false,overrideProtection:false,timeoutTicks:1..72000}; optional flat excavation and inventory-funded bridging; set caller timeout for long routes", "interaction", r -> {
            requirePlayer();
            Navigation provider = NavigationRegistry.get();
            if (provider == null) throw new IllegalArgumentException("Baritone mod is not installed");
            if (!r.params.has("x") || !r.params.has("y") || !r.params.has("z")) throw new IllegalArgumentException("x,y,z required");
            int x=Json.integer(r.params,"x",0,-30000000,30000000), y=Json.integer(r.params,"y",0,1,254), z=Json.integer(r.params,"z",0,-30000000,30000000);
            int timeout=Json.integer(r.params,"timeoutTicks",1200,1,72000);
            controlsChanged("superseded");
            ClientControls.focusForInput();
            navigationJob=provider.goTo(x,y,z,timeout,Json.bool(r.params,"allowBreak",false),Json.bool(r.params,"allowPlace",false),Json.bool(r.params,"overrideProtection",false));
            navigationRequest=r;
            return null;
        });
        register("baritone.route", "Follow saved route {name,reverse:false,startIndex:0,allowBreak:false,allowPlace:false,overrideProtection:false,timeoutTicks:1..72000}; approach first anchor, then stay inside each corridor", "interaction", r -> {
            requirePlayer();Navigation provider=NavigationRegistry.get();
            if(provider==null) throw new IllegalArgumentException("Baritone mod is not installed");
            controlsChanged("superseded");ClientControls.focusForInput();
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
        for(String method:List.of("mine","build","resume")) register("baritone."+method,"Owned, checkpointed "+method+" process; timeoutTicks<=72000. Mine: blocks/items selectors, quantity, bounds/radius. Build: cells, selection or planId; mode blueprint/builder, origin, size, settings, replaceExisting, allowBreak/allowPlace. Resume: jobId. Explicit overrideProtection required each attempt.","interaction",r->{
            requirePlayer();Navigation provider=navigation();Map<String,Object> params=Json.GSON.fromJson(r.params,Map.class);params.remove("_timeout_ms");controlsChanged("superseded");ClientControls.focusForInput();
            navigationJob=method.equals("mine")?provider.mine(params):method.equals("build")?provider.build(params):provider.resume(Json.string(r.params,"jobId",""),params);navigationRequest=r;return null;
        });
        register("baritone.build_preview","Fresh read-only build diff and material allocation for {cells|selection|planId,mode,settings,origin,size,replaceExisting,overrideProtection}; no chunk loading","read",r->navigation().previewBuild(Json.GSON.fromJson(r.params,Map.class)));
        register("baritone.follow","Source FollowProcess: {target:{entityId|uuid|type|name},durationTicks:1..72000,radius,offsetDistance,offsetDirection,allowBreak:false,allowPlace:false,overrideProtection:false}. Follows loaded matches until duration/cancellation; fails when none remain loaded.","interaction",r->{
            requirePlayer();Map<String,Object> params=Json.GSON.fromJson(r.params,Map.class);params.remove("_timeout_ms");controlsChanged("superseded");ClientControls.focusForInput();
            navigationJob=navigation().follow(params);navigationRequest=r;return null;
        });
        register("baritone.settings","Source settings {operation:get|set|reset,query,values,save}; typed values or source syntax, atomic edits while idle. Optional declarations do not promise runtime support.","interaction",r->navigation().settings(Json.GSON.fromJson(r.params,Map.class)));
        register("baritone.build_pause","Pause active construction and release controls; retains jobId for baritone.resume. Server time continues.","interaction",r->navigation().pauseBuild());
        register("baritone.build_materials","Approximate placeable native inventory states; final state depends on native placement callbacks","read",r->navigation().buildMaterials());
        register("baritone.cache","Source terrain cache {operation:status|block(pos)|repack(range)|save|reload|locations(block,meta?,limit,regionDistanceSquared)|result(id)}. Disk operations return an id to poll. Cached states are approximate and need native verification.","interaction",r->navigation().cache(Json.GSON.fromJson(r.params,Map.class)));
        register("baritone.process","Source {process:goal|explore|get_to_block|farm,durationTicks:1..72000,goal:{type,...},center:[x,y,z],radius,block:{id,meta},allowBreak:false,allowPlace:false,exploreForBlocks:true,openOnArrival:false,enterPortal:false}. Goal types: block,near,adjacent,two_blocks,xz,y,axis,inverted,composite,run_away. Farm/explore run for a bounded duration; native source behavior and explicit mod crop adapters apply.","interaction",r->{
            requirePlayer();Map<String,Object> params=Json.GSON.fromJson(r.params,Map.class);params.remove("_timeout_ms");controlsChanged("superseded");ClientControls.focusForInput();
            navigationJob=navigation().sourceProcess(params);navigationRequest=r;return null;
        });
        register("baritone.build_stage","Stage a large immutable plan: operation begin(spec), append(stageId,offset,cells), finish(stageId). Finish returns planId for build/preview.","interaction",r->{Map<String,Object> params=Json.GSON.fromJson(r.params,Map.class);params.remove("_timeout_ms");return navigation().stageBuild(params);});
        register("baritone.scan","Paged native block/metadata/ore-dictionary/item selectors {blocks:[selector],bounds:{min,max},cursor,limit,budget}; reports unloaded cells","read",r->navigation().scan(Json.GSON.fromJson(r.params,Map.class)));
        register("baritone.work_status","Durable process intent and last receipt {jobId}; active status remains baritone.status","read",r->navigation().workStatus(Json.string(r.params,"jobId","")));
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
        register("inv.find","Find observed inventory/container stacks {selector:{id,meta?,nbt_hash?,nbt?},scope:player|container}","read",r->{
            requirePlayer();if(!r.params.has("selector")||!r.params.get("selector").isJsonObject()) throw new IllegalArgumentException("selector required");
            JsonObject selector=r.params.getAsJsonObject("selector");JsonArray matches=new JsonArray();
            String scope=Json.string(r.params,"scope","player");
            if(scope.equals("player")) {for(int i=0;i<36;i++) if(Stacks.matches(mc.thePlayer.inventory.mainInventory[i],selector)) matches.add(Json.object("slot",i,"stack",Stacks.json(mc.thePlayer.inventory.mainInventory[i])));}
            else if(scope.equals("container")) {var container=ui.view.require(r.params);for(int i=0;i<container.inventorySlots.size();i++) {var slot=InventoryView.slot(container,i);if(Stacks.matches(slot.getStack(),selector)) matches.add(Json.object("slot",i,"stack",Stacks.json(slot.getStack()),"virtual",slot.slotNumber!=i));}}
            else throw new IllegalArgumentException("scope must be player or container");
            return Json.object("matches",matches,"scope",scope,"epoch",ui.view.epoch());
        });
        register("sys.screenshot", "Capture client framebuffer as base64 PNG", "read", r -> screenshot());
        register("sys.connect", "Join a server {host,port}; completion means connection initiated", "interaction", r -> {
            if (mc.theWorld != null) throw new IllegalArgumentException("disconnect before connecting");
            String host = Json.string(r.params, "host", "127.0.0.1");
            int port = Json.integer(r.params, "port", 25575, 1, 65535);
            if (host.isBlank()) throw new IllegalArgumentException("host must not be blank");
            if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
            mc.gameSettings.pauseOnLostFocus = false;
            // Forge owns handshake initialization; raw GuiConnecting leaves its latch unset.
            FMLClientHandler.instance().setupServerList();
            FMLClientHandler.instance().connectToServer(new GuiMainMenu(), new ServerData("Modbench", host + ":" + port));
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
            "inv.find","gui.status","act.status","interrupt.status","baritone.status","baritone.terrain","baritone.fluid","baritone.tools",
            "memory.context","memory.status","memory.get","time.status","keys.list","quest.status","quest.observe");
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
            "sneaking", p.isSneaking(), "controlActive", ClientControls.arbiter().current().active(),
            "controlOwner", ClientControls.arbiter().current().label());
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
        int x = Json.integer(r.params, "x", 0, -30000000, 30000000);
        int y = Json.integer(r.params, "y", 0, 0, 255);
        int z = Json.integer(r.params, "z", 0, -30000000, 30000000);
        // Client chunkExists is unconditional in 1.7.10; reject its missing-chunk placeholder.
        if (!mc.theWorld.blockExists(x, y, z) || mc.theWorld.getChunkFromChunkCoords(x>>4,z>>4).isEmpty()) throw new IllegalArgumentException("block is not loaded");
        Block b = mc.theWorld.getBlock(x, y, z);
        int meta = mc.theWorld.getBlockMetadata(x, y, z);
        return Json.object("pos", Json.array(x, y, z), "id", Block.blockRegistry.getNameForObject(b),
            "meta", meta, "hardness", b.getBlockHardness(mc.theWorld, x, y, z),
            "harvestTool", b.getHarvestTool(meta), "harvestLevel", b.getHarvestLevel(meta),
            "hasTile", mc.theWorld.getTileEntity(x, y, z) != null);
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
        ClientControls.focusForInput();
        return hold(r, codes);
    }

    private Object hold(Request r, Set<Integer> codes) {
        int ticks = Json.integer(r.params, "ticks", 1, 1, 200);
        controlsChanged("superseded");
        control = r;
        remaining = ticks;
        ClientControls.focusForInput();
        inputLease=ClientControls.arbiter().acquire("direct",reason -> {
            if (control == r) { release(); r.fail("cancelled", "input released: "+reason); }
        },Json.bool(r.params,"overrideProtection",false));
        if(codes.contains(mc.gameSettings.keyBindAttack.getKeyCode())&&!Json.bool(r.params,"allowRetarget",false))
            ClientControls.guardBlockAttack(inputLease);
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
        if (control == null) return;
        if (control.isDone() || !control.session.connected || control.expired()) controlsChanged("cancelled");
    }

    public void endTick() {
        ui.tick();
        interactions.tick();
        dev.modbench.control.ClientMemory.sample();
        if (navigationRequest != null && navigationJob.done()) {
            Request r=navigationRequest; Navigation.Job job=navigationJob;
            navigationRequest=null; navigationJob=null;
            if (job.succeeded() || "paused".equals(job.status().get("state"))) r.reply(job.status());
            else {
                boolean cancelled=job.status().get("state").equals("cancelled");
                r.fail(cancelled ? "cancelled" : "path_failed", String.valueOf(job.status().get("reason")),job.status());
                if(!cancelled) clock.actionFailed();
            }
        }
        if (control == null) return;
        if (mc.theWorld != lastWorld || mc.thePlayer != lastPlayer || mc.thePlayer == null) {
            controlsChanged("world_changed"); return;
        }
        if (control.isDone() || !control.session.connected || control.expired()) { controlsChanged("cancelled"); return; }
        boolean targetChanged=ClientControls.blockAttackChanged(inputLease);
        if (--remaining <= 0 || targetChanged) {
            Request finished = control;
            release();
            finished.reply(Json.object("completed", true, "outcome",targetChanged?"attack_target_changed":"duration_elapsed", "player", player(), "serverAcknowledged", false));
        }
    }

    private void release() {
        ClientControls.releaseBlockAttack(inputLease);
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
        ClientControls.arbiter().revoke(reason);
    }

    private void cancelNavigation(String reason){var navigation=NavigationRegistry.get();if(navigation!=null)navigation.cancel(reason);}
    void interruptControls() {cancelQueuedInteractions();controlsChanged("interrupted");cancelNavigation("interrupted");}
    @Override protected void admit(Request r) {
        interrupts.admit(r);
        if(readOnly(r.method))return;
        String m=r.method;
        if(clock.isPaused()&&(m.startsWith("act.")&&!Set.of("act.stop","act.look").contains(m)||m.equals("keys.press")
            ||m.startsWith("baritone.")&&!Set.of("baritone.settings","baritone.cache").contains(m)||m.startsWith("quest.")))
            throw new IllegalArgumentException("time_paused: resume before starting simulation actions");
    }

    private JsonObject look(Request r) {
        requirePlayer();
        float yaw = (float) Json.number(r.params, "yaw", mc.thePlayer.rotationYaw, -360000, 360000);
        float pitch = (float) Json.number(r.params, "pitch", mc.thePlayer.rotationPitch, -90, 90);
        controlsChanged("superseded");
        try (InputArbiter.Lease lease=ClientControls.arbiter().acquire("direct-look",reason -> {})) { lease.look(yaw,pitch); }
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
