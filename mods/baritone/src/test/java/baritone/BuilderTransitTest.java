// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.*;
import baritone.api.schematic.ISchematic;
import baritone.api.utils.*;
import baritone.behavior.*;
import baritone.compat.*;
import baritone.gtnh.pathing.PlanBreaks;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.movements.MovementFall;
import baritone.process.BuilderProcess;
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
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Predicate;
import static org.junit.Assert.*;

/**
 * The live place-and-break loop of 2026-10-03 (jobs 543eb980, d0961b96), as a path search: the imported A*, the builder's
 * own cost context (BuilderCalculationContext, its fields filled in by reflection because its constructor reads a live
 * player) and its own GoalAdjacent goals, over the shell as the journals left it. What is not from the game: the ground
 * outside the shell (taken as level with the floor) and the row of access blocks the player stood on (only the one under
 * its feet is recorded; the others are assumed).
 */
@org.junit.runner.RunWith(ForgePlanningTestRunner.class)
public class BuilderTransitTest {
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
    @Before public void reset(){Baritone.settings().allSettings.forEach(s->s.reset());}
    // Not a constant: the block registry is filled by bootstrap(), after this class initialises.
    private static Block cobble(){return net.minecraft.init.Blocks.cobblestone;}
    static final class Terrain implements IBlockAccess {
        final Map<BlockPos,Block> cells=new HashMap<>();
        int ground=74;
        public Block getBlock(int x,int y,int z){var b=cells.get(new BlockPos(x,y,z));return b!=null?b:y<=ground?Blocks.STONE:Blocks.AIR;}
        public int getBlockMetadata(int x,int y,int z){return 0;}
        public TileEntity getTileEntity(int x,int y,int z){return null;}
        public int getLightBrightnessForSkyBlocks(int x,int y,int z,int min){return 15<<20|15<<4;}
        public int isBlockProvidingPowerTo(int x,int y,int z,int side){return 0;}
        public boolean isAirBlock(int x,int y,int z){return getBlock(x,y,z)==Blocks.AIR;}
        public BiomeGenBase getBiomeGenForCoords(int x,int z){return BiomeGenBase.plains;}
        public int getHeight(){return 256;}
        public boolean extendedLevelsInChunkCache(){return false;}
        public boolean isSideSolid(int x,int y,int z,ForgeDirection side,boolean fallback){return getBlock(x,y,z).isNormalCube();}
    }
    /** The shell plan: floor y75, walls y76..80, roof y81 over x 256..280, z -286..-262, all cobblestone. */
    private static Set<BlockPos> shell(){
        Set<BlockPos> plan=new HashSet<>();
        for(int x=256;x<=280;x++)for(int z=-286;z<=-262;z++)for(int y=75;y<=81;y++)
            if(y==75||y==81||x==256||x==280||z==-286||z==-262)plan.add(new BlockPos(x,y,z));
        return plan;
    }
    /** The cells still missing when the job stopped: wall rows y78..80 on the south wall east of x 267 and the east wall south of z -279. */
    private static boolean missing(BlockPos p){
        return p.getY()>=78&&p.getY()<=80&&(p.getZ()==-262&&p.getX()>=268||p.getX()==280&&p.getZ()>=-278);
    }
    private static Terrain world(Set<BlockPos> plan){
        Terrain t=new Terrain();
        for(BlockPos p:plan)if(!missing(p))t.cells.put(p,cobble());
        for(int x=268;x<=280;x++)t.cells.put(new BlockPos(x,80,-261),cobble());
        return t;
    }
    /** The builder's cost context as the wrapper configures it: free plan placements, and `mayBreak` as the break rule. */
    private static CalculationContext builderContext(Terrain terrain,Set<BlockPos> plan,Predicate<BlockPos> mayBreak) throws Exception {
        return builderContext(terrain,256,75,-286,28,plan::contains,mayBreak,p->true);
    }
    /**
     * The same for any plan: `shown` is what the source builder's schematic holds, `mayPlace` the wrapper's mayPlace and
     * movementMayPlace together. The world is loaded `reach` blocks around the origin and nowhere else.
     */
    static CalculationContext builderContext(Terrain terrain,int ox,int oy,int oz,int reach,Predicate<BlockPos> shown,Predicate<BlockPos> mayBreak,Predicate<BlockPos> mayPlace) throws Exception {
        Baritone.settings().allowPlace.value=true;
        ItemStack[] hotbar=new ItemStack[9];hotbar[0]=new ItemStack(net.minecraft.init.Items.iron_pickaxe);
        var base=new CalculationContext(PLANNING_ONLY,true,new CalculationInputs(null,new BlockStateInterface(terrain,(x,z)->x>=ox+12-reach&&x<=ox+12+reach&&z>=oz+12-reach&&z<=oz+12+reach),
            new ToolSet(hotbar,0,1,ReferencePathingTest.VANILLA),true,false,true,0,0,new WorldMemory.Snapshot(0,Map.of(),Map.of(),Map.of()),false,p->true));
        var context=allocate(BuilderProcess.BuilderCalculationContext.class);
        for(Field f:CalculationContext.class.getDeclaredFields()){
            if(Modifier.isStatic(f.getModifiers()))continue;
            f.setAccessible(true);f.set(context,f.get(base));
        }
        context.jumpPenalty+=10;context.backtrackCostFavoringCoefficient=1;
        ISchematic schematic=new ISchematic(){
            public int widthX(){return 25;}public int heightY(){return 7;}public int lengthZ(){return 25;}
            public boolean inSchematic(int x,int y,int z,IBlockState current){return shown.test(new BlockPos(x+ox,y+oy,z+oz));}
            public IBlockState desiredState(int x,int y,int z,IBlockState current,List<IBlockState> materials){return new IBlockState(cobble(),0,null,x+ox,y+oy,z+oz);}
        };
        BuilderProcess.StateValidator validator=(current,wanted,item)->true;
        Map<String,Object> fields=new HashMap<>();
        fields.put("this$0",allocate(BuilderProcess.class));fields.put("placeable",List.of(new IBlockState(cobble(),0,null,0,0,0)));fields.put("schematic",schematic);
        fields.put("originX",ox);fields.put("originY",oy);fields.put("originZ",oz);fields.put("validatorSnapshot",validator);
        fields.put("inventorySnapshot",List.of());fields.put("breakAllowed",mayBreak);fields.put("placementAllowed",mayPlace);
        for(var e:fields.entrySet()){
            Field f=BuilderProcess.BuilderCalculationContext.class.getDeclaredField(e.getKey());f.setAccessible(true);f.set(context,e.getValue());
        }
        return context;
    }
    private static <T>T allocate(Class<T> type) throws Exception {
        Class<?> unsafeClass=Class.forName("sun.misc.Unsafe",true,ClassLoader.getSystemClassLoader());
        Field field=unsafeClass.getDeclaredField("theUnsafe");field.setAccessible(true);
        Object unsafe=field.get(null);return type.cast(unsafeClass.getMethod("allocateInstance",Class.class).invoke(unsafe,type));
    }
    /** Every missing cell's source goal, as BuilderProcess.assemble offers them for a cell whose support exists. */
    private static Goal goals(Set<BlockPos> plan,Terrain terrain){
        List<Goal> out=new ArrayList<>();
        for(BlockPos p:plan)if(missing(p)&&terrain.getBlock(p.getX(),p.getY()-1,p.getZ())!=Blocks.AIR)out.add(new BuilderProcess.GoalAdjacent(p,p.down(),false));
        assertFalse(out.isEmpty());return new GoalComposite(out.toArray(new Goal[0]));
    }
    private static final BetterBlockPos FEET=new BetterBlockPos(280,81,-261);
    private static final BetterBlockPos CORNER=new BetterBlockPos(280,81,-262);
    @Test public void replaceExistingAloneMadeTheFinishedRoofCornerTheWayDown() throws Exception {
        var plan=shell();var terrain=world(plan);
        // The wrapper's rule before PlanBreaks: with replaceExisting, any plan cell not deferred or pending.
        var context=builderContext(terrain,plan,p->true);
        assertEquals("upstream prices a finished cell, it does not forbid it",Baritone.settings().breakCorrectBlockPenaltyMultiplier.value,
            context.breakCostMultiplierAt(280,81,-262,context.get(280,81,-262)),0);
        var result=new AStarPathFinder(FEET.x,FEET.y,FEET.z,goals(plan,terrain),new Favoring(null,context),context).calculate(2000,4000);
        assertEquals(PathCalculationResult.Type.SUCCESS_TO_GOAL,result.getType());
        var path=result.getPath().orElseThrow();
        assertEquals("the live receipt: pathLength 2, MovementFall",2,path.length());
        assertTrue(path.movements().get(0) instanceof MovementFall);
        assertTrue("the fall goes down through the finished corner",Arrays.asList(((Movement)path.movements().get(0)).toBreakAll()).contains(CORNER));
    }
    @Test public void aFinishedCellIsNotTransitAndTheSearchEndsInsteadOfLooping() throws Exception {
        var plan=shell();var terrain=world(plan);
        Predicate<BlockPos> mayBreak=p->!plan.contains(p)||PlanBreaks.allowed(false,!missing(p),false,true,false);
        var context=builderContext(terrain,plan,mayBreak);
        assertTrue(context.breakCostMultiplierAt(280,81,-262,context.get(280,81,-262))>=baritone.api.pathing.movement.ActionCosts.COST_INF);
        var result=new AStarPathFinder(FEET.x,FEET.y,FEET.z,goals(plan,terrain),new Favoring(null,context),context).calculate(500,1000);
        // The search runs out of places to go (it does not time out): the job then stands still and the stall watchdog ends it.
        assertEquals("with the rim over the unfinished wall there is no way down",PathCalculationResult.Type.FAILURE,result.getType());
        assertFalse(result.getPath().isPresent());
    }
}
