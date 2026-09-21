// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone;

import baritone.api.*;
import baritone.api.utils.*;
import baritone.behavior.*;
import baritone.compat.*;
import baritone.process.CustomGoalProcess;
import baritone.utils.*;
import net.minecraft.client.Minecraft;
import java.util.concurrent.*;

/** Client-owned composition root for the source-ported Baritone engine. */
public final class Baritone implements IBaritone {
    private static final Settings SETTINGS=new Settings();
    private static final ExecutorService EXECUTOR=Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"baritone-reference-search");t.setDaemon(true);return t;});
    private final Minecraft mc=Minecraft.getMinecraft();
    private final GameEventHandler events=new GameEventHandler();
    private final IPlayerContext ctx=new IPlayerContext(){
        private final LegacyPlayerController controller=new LegacyPlayerController(Baritone.this);
        public Minecraft minecraft(){return mc;}
        public net.minecraft.client.entity.EntityPlayerSP player(){return mc.thePlayer;}
        public LegacyPlayerController playerController(){return controller;}
        public World world(){return new World(mc.theWorld);}
        public RayTraceResult objectMouseOver(){return RayTraceResult.fromNative(mc.objectMouseOver);}
        public baritone.api.cache.IWorldData worldData(){return worlds.getCurrentWorld();}
    };
    private final LookBehavior look=new LookBehavior(this);
    private final PathingBehavior pathing=new PathingBehavior(this);
    private final InventoryBehavior inventory=new InventoryBehavior(this);
    private final InputOverrideHandler input=new InputOverrideHandler(this);
    private final PathingControlManager processes;
    private final CustomGoalProcess customGoal;
    private final baritone.process.MineProcess mine;
    private final baritone.process.InventoryPauserProcess inventoryPauser;
    private final baritone.process.BuilderProcess builder;
    private final baritone.process.FollowProcess follow;
    private final baritone.process.ExploreProcess explore;
    private final baritone.process.GetToBlockProcess getToBlock;
    private final baritone.process.FarmProcess farm;
    private final baritone.process.BackfillProcess backfill;
    private final baritone.selection.SelectionManager selections=new baritone.selection.SelectionManager(this);
    private final baritone.cache.WorldProvider worlds=new baritone.cache.WorldProvider(this);
    public BlockStateInterface bsi;
    public boolean overrideProtection;
    /** A mining job may accept water (never lava, never a fluid it cannot swim in) beside what it breaks, as a player does. */
    public static volatile boolean besideWater;
    public java.util.function.Predicate<BlockPos> positionAllowed=p->true;
    public java.util.function.Supplier<java.util.function.Predicate<IBlockState>> explicitMiningTargets=()->s->false;
    /** The single native inventory swap in flight; acknowledged through the core packet hook. */
    public LegacyInventorySwap pendingSwap;
    private boolean tickStarted;
    public Baritone(){
        BaritoneAPI.getProvider().attach(this);
        events.registerEventListener(look);events.registerEventListener(inventory);events.registerEventListener(pathing);
        processes=new PathingControlManager(this);
        customGoal=new CustomGoalProcess(this);processes.registerProcess(customGoal);
        mine=new baritone.process.MineProcess(this);processes.registerProcess(mine);
        inventoryPauser=new baritone.process.InventoryPauserProcess(this);processes.registerProcess(inventoryPauser);
        builder=new baritone.process.BuilderProcess(this);processes.registerProcess(builder);
        follow=new baritone.process.FollowProcess(this);processes.registerProcess(follow);
        explore=new baritone.process.ExploreProcess(this);processes.registerProcess(explore);
        getToBlock=new baritone.process.GetToBlockProcess(this);processes.registerProcess(getToBlock);
        farm=new baritone.process.FarmProcess(this);processes.registerProcess(farm);
        backfill=new baritone.process.BackfillProcess(this);processes.registerProcess(backfill);
        inventory.placementTarget=(x,y,z)->builder.placeAt(x,y,z,bsi.get0(x,y,z));
        // Keep visible aiming as the GTNH default; packet/movement hooks also support freeLook.
        SETTINGS.freeLook.value=false;
        baritone.api.utils.SettingsUtil.readAndApply(SETTINGS,baritone.api.utils.SettingsUtil.SETTINGS_DEFAULT_NAME);
        SETTINGS.allowWaterBucketFall.value=false;
        // Native items have a pickup delay; allow the final drop/packet handoff
        // without adding any wait between blocks while targets remain.
        SETTINGS.mineDropLoiterDurationMSThanksLouca.value=1000L;
    }
    public static Settings settings(){return SETTINGS;}
    public static ExecutorService getExecutor(){return EXECUTOR;}
    public IPlayerContext getPlayerContext(){return ctx;}
    /** The vanilla attack loop and native clicks are owned while a source process holds a lease. */
    public boolean ownsNativeActions(){return input.hasActiveLease();}
    public LookBehavior getLookBehavior(){return look;}
    public PathingBehavior getPathingBehavior(){return pathing;}
    public InputOverrideHandler getInputOverrideHandler(){return input;}
    public InventoryBehavior getInventoryBehavior(){return inventory;}
    public GameEventHandler getGameEventHandler(){return events;}
    public PathingControlManager getPathingControlManager(){return processes;}
    public CustomGoalProcess getCustomGoalProcess(){return customGoal;}
    public baritone.process.MineProcess getMineProcess(){return mine;}
    public baritone.process.InventoryPauserProcess getInventoryPauserProcess(){return inventoryPauser;}
    public baritone.process.BuilderProcess getBuilderProcess(){return builder;}
    public baritone.process.FollowProcess getFollowProcess(){return follow;}
    public baritone.process.ExploreProcess getExploreProcess(){return explore;}
    public baritone.process.GetToBlockProcess getGetToBlockProcess(){return getToBlock;}
    public baritone.process.FarmProcess getFarmProcess(){return farm;}
    public baritone.process.BackfillProcess getBackfillProcess(){return backfill;}
    public baritone.cache.WorldProvider getWorldProvider(){return worlds;}
    public baritone.selection.SelectionManager getSelectionManager(){return selections;}
    public void tickStart(){
        bsi=new BlockStateInterface(ctx);
        // The 1.7 render crosshair is interpolated and mods may refresh it with
        // their own partial tick. Source processes need the current native pose
        // for their pre-aim checks, as well as the new ray after applying aim.
        dev.modbench.api.ControlRegistry.targeting().refresh();
        var event=new baritone.api.event.events.TickEvent(baritone.api.event.events.type.EventState.PRE,baritone.api.event.events.TickEvent.Type.IN,mc.thePlayer.ticksExisted);
        events.onTick(event);
        events.onPlayerUpdate(new baritone.api.event.events.PlayerUpdateEvent(baritone.api.event.events.type.EventState.PRE));
        dev.modbench.api.ControlRegistry.targeting().refresh();
        input.flush();
        // PathExecutor consumes SPRINT to compute continuity across movements.
        if(pathing.getCurrent()!=null)mc.thePlayer.setSprinting(pathing.getCurrent().isSprinting());
        tickStarted=true;
    }
    public void tickEnd(){
        if(!tickStarted)return;tickStarted=false;
        if(mc.thePlayer!=null){
            events.onPlayerUpdate(new baritone.api.event.events.PlayerUpdateEvent(baritone.api.event.events.type.EventState.POST));
            events.onPostTick(new baritone.api.event.events.TickEvent(baritone.api.event.events.type.EventState.POST,baritone.api.event.events.TickEvent.Type.IN,mc.thePlayer.ticksExisted));
        }
    }
}
