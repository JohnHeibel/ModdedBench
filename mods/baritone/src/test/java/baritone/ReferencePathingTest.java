// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone;

import baritone.api.IBaritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.*;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.utils.*;
import baritone.behavior.*;
import baritone.compat.*;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.movement.*;
import baritone.pathing.movement.movements.*;
import baritone.utils.*;
import baritone.utils.pathing.Favoring;
import dev.modbench.api.WorldMemory;
import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.util.ForgeDirection;
import org.junit.*;
import java.util.*;
import static org.junit.Assert.*;

/** Executes the imported A*, movement cost graph, path assembly and splice implementation. */
@org.junit.runner.RunWith(ForgePlanningTestRunner.class)
public class ReferencePathingTest {
    private static final IBaritone PLANNING_ONLY=new IBaritone(){
        public IPlayerContext getPlayerContext(){return null;}
        public LookBehavior getLookBehavior(){throw new AssertionError("planning must not aim");}
        public PathingBehavior getPathingBehavior(){throw new AssertionError("planning must not control the player");}
        public InputOverrideHandler getInputOverrideHandler(){throw new AssertionError("planning must not press keys");}
    };
    @BeforeClass public static void bootstrap(){
        cpw.mods.fml.common.Loader.injectData("7","99","40","1614","1.7.10","9.05",new java.io.File("."),List.of());
        net.minecraft.init.Bootstrap.func_151354_b();
    }
    @Before public void reset(){Baritone.settings().allSettings.forEach(s->s.reset());Baritone.settings().allowPlace.value=false;}
    private static final class Terrain implements IBlockAccess {
        final Map<BlockPos,IBlockState> cells=new HashMap<>();
        boolean floor=true;
        void set(int x,int y,int z,Block block,int meta){cells.put(new BlockPos(x,y,z),new IBlockState(block,meta,this,x,y,z));}
        public Block getBlock(int x,int y,int z){var s=cells.get(new BlockPos(x,y,z));return s!=null?s.getBlock():floor&&y<64?Blocks.STONE:Blocks.AIR;}
        public int getBlockMetadata(int x,int y,int z){var s=cells.get(new BlockPos(x,y,z));return s==null?0:s.meta;}
        public TileEntity getTileEntity(int x,int y,int z){return null;}
        public int getLightBrightnessForSkyBlocks(int x,int y,int z,int min){return 15<<20|15<<4;}
        public int isBlockProvidingPowerTo(int x,int y,int z,int side){return 0;}
        public boolean isAirBlock(int x,int y,int z){return getBlock(x,y,z)==Blocks.AIR;}
        public BiomeGenBase getBiomeGenForCoords(int x,int z){return BiomeGenBase.plains;}
        public int getHeight(){return 256;}
        public boolean extendedLevelsInChunkCache(){return false;}
        public boolean isSideSolid(int x,int y,int z,ForgeDirection side,boolean fallback){return getBlock(x,y,z).isNormalCube();}
    }
    /** Stands in for the game's tool answer: vanilla dig speed and harvest callbacks, no Forge events. */
    static final ToolSet.Answers VANILLA=(stacks,state)->{
        var out=new baritone.gtnh.ReferenceToolPolicy.Answer[stacks.length];
        float hardness=state.getBlock().getBlockHardness(null,state.x,state.y,state.z);
        for(int i=0;i<stacks.length;i++){ItemStack s=stacks[i];
            boolean harvest=state.getMaterial().isToolNotRequired()||s!=null&&s.getItem().canHarvestBlock(state.getBlock(),s);
            float speed=s==null?1:s.getItem().getDigSpeed(s,state.getBlock(),state.meta);
            out[i]=new baritone.gtnh.ReferenceToolPolicy.Answer(hardness<0?-1:speed/hardness/(harvest?30:100),harvest);}
        return out;
    };
    private CalculationContext context(Terrain terrain,boolean sprint){
        return context(terrain,sprint,new WorldMemory.Snapshot(0,Map.of(),Map.of(),Map.of()));
    }
    private CalculationContext context(Terrain terrain,boolean sprint,WorldMemory.Snapshot protection){
        ItemStack[] hotbar=new ItemStack[9];hotbar[0]=new ItemStack(net.minecraft.init.Items.iron_pickaxe);
        return new CalculationContext(PLANNING_ONLY,true,new CalculationInputs(null,new BlockStateInterface(terrain,(x,z)->Math.abs(x)<64&&Math.abs(z)<64),new ToolSet(hotbar,0,1,VANILLA),false,false,sprint,0,0,protection,false,p->true));
    }
    private IPath path(CalculationContext context,BetterBlockPos start,Goal goal){
        var result=new AStarPathFinder(start.x,start.y,start.z,goal,new Favoring(null,context),context).calculate(2000,4000);
        assertEquals(result.toString(),PathCalculationResult.Type.SUCCESS_TO_GOAL,result.getType());return result.getPath().orElseThrow();
    }
    @Test public void diagonalTravelUsesActualDiagonalMovements(){
        var p=path(context(new Terrain(),true),new BetterBlockPos(0,64,0),new GoalBlock(12,64,12));
        assertEquals(13,p.length());assertTrue(p.movements().stream().allMatch(m->m instanceof MovementDiagonal));
        assertEquals(p.positions().size()-1,p.movements().size());
    }
    @Test public void waterAboveMiningTargetKeepsNativeBreakSafety(){
        Terrain terrain=new Terrain();
        terrain.set(1,64,0,net.minecraft.init.Blocks.obsidian,0);
        terrain.set(1,65,0,net.minecraft.init.Blocks.flowing_water,1);
        Baritone.settings().strictLiquidCheck.value=false;
        var c=context(terrain,false);
        assertTrue(MovementHelper.avoidBreaking(c.bsi,1,64,0,c.get(1,64,0)));
        terrain.set(1,65,0,Blocks.AIR,0);
        c=context(terrain,false);
        assertFalse(MovementHelper.avoidBreaking(c.bsi,1,64,0,c.get(1,64,0)));
    }
    @Test public void failedSearchRetainsItsDiagnosticWithoutProducingAPath(){
        var c=context(new Terrain(),false);
        Goal invalid=new Goal(){
            public boolean isInGoal(int x,int y,int z){throw new IllegalStateException("native state unavailable");}
            public double heuristic(int x,int y,int z){return 0;}
        };
        var search=new AStarPathFinder(0,64,0,invalid,new Favoring(null,c),c);
        var result=search.calculate(2000,4000);
        assertEquals(PathCalculationResult.Type.EXCEPTION,result.getType());
        assertFalse(result.getPath().isPresent());
        assertTrue(search.failureDescription().contains("native state unavailable"));
    }
    @Test public void stepUpAndStepDownProduceOriginalMovementClasses(){
        Terrain t=new Terrain();t.set(1,64,0,Blocks.STONE,0);var c=context(t,false);
        assertTrue(path(c,new BetterBlockPos(0,64,0),new GoalBlock(1,65,0)).movements().get(0) instanceof MovementAscend);
        assertTrue(path(c,new BetterBlockPos(1,65,0),new GoalBlock(2,64,0)).movements().get(0) instanceof MovementDescend);
    }
    @Test public void directDownwardDiggingIsARealMovement(){
        var p=path(context(new Terrain(),false),new BetterBlockPos(0,64,0),new GoalBlock(0,63,0));
        assertEquals(1,p.movements().size());assertTrue(p.movements().get(0) instanceof MovementDownward);
    }
    @Test public void parkourCrossesAnActualGap(){
        Terrain t=new Terrain();t.floor=false;t.set(0,63,0,Blocks.STONE,0);t.set(3,63,0,Blocks.STONE,0);
        Baritone.settings().allowParkour.value=true;
        var p=path(context(t,true),new BetterBlockPos(0,64,0),new GoalBlock(3,64,0));
        assertTrue(p.movements().get(0) instanceof MovementParkour);
    }
    @Test public void compositeGoalSelectsReachableGroundInsteadOfElevatedTarget(){
        Terrain t=new Terrain();Baritone.settings().allowBreak.value=false;
        var p=path(context(t,false),new BetterBlockPos(0,64,0),new GoalComposite(new GoalBlock(2,70,0),new GoalBlock(9,64,0)));
        assertEquals(new BetterBlockPos(9,64,0),p.getDest());
    }
    @Test public void soulSandIsTraversableWithItsOriginalPenalty(){
        Terrain t=new Terrain();var normal=context(t,false);double base=MovementTraverse.cost(normal,0,64,0,1,0);
        t.set(1,63,0,Blocks.SOUL_SAND,0);double slow=MovementTraverse.cost(context(t,false),0,64,0,1,0);
        assertTrue(slow>base);assertTrue(slow<ActionCosts.COST_INF);
    }
    @Test public void metadataControlsSlabAndDoorSemantics(){
        Terrain t=new Terrain();Block slab=net.minecraft.init.Blocks.stone_slab;
        t.set(0,64,0,slab,0);t.set(1,64,0,slab,8);var c=context(t,false);
        assertTrue(MovementHelper.isBottomSlab(c.get(0,64,0)));assertFalse(MovementHelper.isBottomSlab(c.get(1,64,0)));
        t.set(2,64,0,net.minecraft.init.Blocks.wooden_door,4);t.set(2,65,0,net.minecraft.init.Blocks.wooden_door,8);
        assertTrue(c.get(2,65,0).getValue(LegacyProperties.OPEN));
    }
    @Test public void cancellationBeforeWorkerStartsIsNotLost(){
        var c=context(new Terrain(),true);var search=new AStarPathFinder(0,64,0,new GoalBlock(20,64,20),new Favoring(null,c),c);
        search.cancel();assertEquals(PathCalculationResult.Type.CANCELLATION,search.calculate(1000,2000).getType());
    }
    @Test public void sourcePathSplicesAContinuationAndRejectsAMismatchedStart(){
        var c=context(new Terrain(),false);
        Goal goal=new GoalBlock(15,64,0);
        var first=new baritone.pathing.path.CutoffPath(path(c,new BetterBlockPos(0,64,0),goal),8);
        assertFalse(baritone.pathing.path.SplicedPath.trySplice(first,path(c,new BetterBlockPos(6,64,0),goal),true).isPresent());
        var second=path(c,new BetterBlockPos(8,64,0),goal);
        var combined=baritone.pathing.path.SplicedPath.trySplice(first,second,true).orElseThrow();
        assertEquals(first.getSrc(),combined.getSrc());assertEquals(second.getDest(),combined.getDest());
        assertEquals(combined.length()-1,combined.movements().size());
    }
    @Test public void negativeAndBoundaryCoordinatesRoundTrip(){
        for(int x:new int[]{-29999999,-4096,-1,0,1,29999999})for(int y:new int[]{0,64,255}){
            var p=new BlockPos(x,y,-x);assertEquals(p,BlockPos.fromLong(p.toLong()));
            assertEquals(new BetterBlockPos(p).hashCode(),new BetterBlockPos(x,y,-x).hashCode());
        }
    }
    @Test public void protectedRegionsForbidExcavationButStillAllowWalking(){
        Terrain t=new Terrain();t.set(1,64,0,Blocks.STONE,0);
        var region=new WorldMemory.Region("base",new WorldMemory.Pos(0,63,0),new WorldMemory.Pos(2,66,0));
        var c=context(t,false,new WorldMemory.Snapshot(1,Map.of(),Map.of(),Map.of("base",region)));
        assertTrue(MovementTraverse.cost(c,0,64,0,1,0)>=ActionCosts.COST_INF);
        t.set(1,64,0,Blocks.AIR,0);
        assertTrue(MovementTraverse.cost(context(t,false,capturedProtection(region)),0,64,0,1,0)<ActionCosts.COST_INF);
    }
    private static WorldMemory.Snapshot capturedProtection(WorldMemory.Region region){return new WorldMemory.Snapshot(1,Map.of(),Map.of(),Map.of(region.name(),region));}
    @Test public void unverifiedForgeFluidsAreNotAirAndCannotBeExcavatedThrough(){
        Terrain t=new Terrain();var definition=new net.minecraftforge.fluids.Fluid("reference-test-fluid");
        net.minecraftforge.fluids.FluidRegistry.registerFluid(definition);
        Block fluid=new net.minecraftforge.fluids.BlockFluidClassic(definition,net.minecraft.block.material.Material.water);
        t.set(1,64,0,fluid,0);var c=context(t,false);
        assertFalse(MovementHelper.canWalkThrough(c,1,64,0));
        assertFalse(MovementHelper.fullyPassable(c,1,64,0));
        assertFalse(MovementHelper.canWalkOn(c,1,64,0));
        assertTrue(MovementHelper.getMiningDurationTicks(c,1,64,0,false)>=ActionCosts.COST_INF);
        assertTrue(MovementHelper.avoidBreaking(c.bsi,0,64,0,c.get(0,64,0)));
    }
    @Test public void unknownRegistryStatesDoNotAliasInThePassabilityCache(){
        Terrain t=new Terrain();Block one=new net.minecraft.block.Block(net.minecraft.block.material.Material.rock){},two=new net.minecraft.block.Block(net.minecraft.block.material.Material.plants){};
        t.set(0,64,0,one,0);t.set(1,64,0,two,0);var c=context(t,false);
        assertFalse(MovementHelper.canWalkThrough(c,0,64,0));assertTrue(MovementHelper.canWalkThrough(c,1,64,0));
    }
    @Test public void toolSetSwingsTheStackTheAnswerPicksAnywhereInTheInventory(){
        ItemStack[] slots=new ItemStack[36];slots[20]=new ItemStack(net.minecraft.init.Items.iron_pickaxe);
        var set=new ToolSet(slots,0,1,VANILLA);var state=new IBlockState(Blocks.STONE,0,new Terrain(),0,63,0);
        assertTrue(set.canHarvest(state));assertEquals(20,set.getBestSlot(state));
        assertEquals(6.0/1.5/30,set.getStrVsBlock(state),.00001);
    }
    @Test public void blocksToAvoidNamesABlockOrOneOfItsMetas(){
        var grass=net.minecraft.init.Blocks.tallgrass;
        Baritone.settings().blocksToAvoid.value=List.of("minecraft:tallgrass:1");
        assertEquals(baritone.pathing.precompute.Ternary.NO,MovementHelper.canWalkThroughBlockState(IBlockState.of(grass,1)));
        assertNotEquals(baritone.pathing.precompute.Ternary.NO,MovementHelper.canWalkThroughBlockState(IBlockState.of(grass,2)));
        Baritone.settings().blocksToAvoid.value=List.of("minecraft:tallgrass");
        assertEquals(baritone.pathing.precompute.Ternary.NO,MovementHelper.canWalkThroughBlockState(IBlockState.of(grass,2)));
        assertThrows(IllegalArgumentException.class,()->baritone.gtnh.BlockIdentity.entries(List.of("nosuchmod:thing")));
        assertEquals(1,baritone.gtnh.BlockIdentity.entries(List.of("item=minecraft:stone:3")).size());
    }
    @Test public void nonOpaqueFullCubesSupportTheActualMovementGraph(){
        Terrain t=new Terrain();t.floor=false;
        for(int x=0;x<=8;x++)t.set(x,63,0,net.minecraft.init.Blocks.glowstone,0);
        var p=path(context(t,false),new BetterBlockPos(0,64,0),new GoalBlock(8,64,0));
        assertEquals(9,p.length());assertTrue(p.movements().stream().allMatch(m->m instanceof MovementTraverse));
    }
    @Test public void requestedMiningAuthorizesOnlyItsExactTargetWhilePathing(){
        Terrain t=new Terrain();t.set(1,64,0,Blocks.STONE,1);t.set(2,64,0,Blocks.STONE,0);
        ItemStack[] slots=new ItemStack[9];slots[0]=new ItemStack(net.minecraft.init.Items.iron_pickaxe);
        Baritone.settings().allowBreak.value=false;
        var c=new CalculationContext(PLANNING_ONLY,true,new CalculationInputs(null,new BlockStateInterface(t,(x,z)->Math.abs(x)<64&&Math.abs(z)<64),new ToolSet(slots,0,1,VANILLA),false,false,false,0,0,capturedProtection(new WorldMemory.Region("remote",new WorldMemory.Pos(20,60,20),new WorldMemory.Pos(21,70,21))),false,p->true,s->s.x==1&&s.y==64&&s.z==0&&s.getBlock()==Blocks.STONE&&s.meta==1));
        assertTrue(MovementTraverse.cost(c,0,64,0,1,0)<ActionCosts.COST_INF);
        assertTrue(MovementTraverse.cost(c,1,64,0,2,0)>=ActionCosts.COST_INF);
        var p=path(c,new BetterBlockPos(0,64,0),new GoalBlock(1,64,0));assertEquals(2,p.length());
    }
}
