// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone;

import baritone.api.IBaritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.PathCalculationResult;
import baritone.behavior.LookBehavior;
import baritone.behavior.PathingBehavior;
import baritone.compat.BlockPos;
import baritone.compat.Blocks;
import baritone.compat.CalculationInputs;
import baritone.compat.IBlockState;
import baritone.gtnh.pathing.Move;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.BlockStateInterface;
import baritone.utils.InputOverrideHandler;
import baritone.utils.ToolSet;
import baritone.utils.pathing.Favoring;
import dev.modbench.api.WorldMemory;
import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.util.ForgeDirection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A world of cells and the route search over it, without a game: what the test of a move needs. The class under test
 * runs with ForgePlanningTestRunner and calls bootstrap() once.
 */
public final class Planning {
    private Planning(){}
    /** Nobody plays: planning that aims, walks or presses a key is a bug. */
    public static final IBaritone NOBODY=new IBaritone(){
        public IPlayerContext getPlayerContext(){return null;}
        public LookBehavior getLookBehavior(){throw new AssertionError("planning must not aim");}
        public PathingBehavior getPathingBehavior(){throw new AssertionError("planning must not control the player");}
        public InputOverrideHandler getInputOverrideHandler(){throw new AssertionError("planning must not press keys");}
    };
    public static void bootstrap(){
        cpw.mods.fml.common.Loader.injectData("7","99","40","1614","1.7.10","9.05",new java.io.File("."),List.of());
        net.minecraft.init.Bootstrap.func_151354_b();
    }
    /** Stone below y 64 and air from there up, unless floor is off; set() puts single blocks. */
    public static final class Terrain implements IBlockAccess {
        final Map<BlockPos,IBlockState> cells=new HashMap<>();
        public boolean floor=true;
        public void set(int x,int y,int z,Block block,int meta){cells.put(new BlockPos(x,y,z),new IBlockState(block,meta,this,x,y,z));}
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
    public static final ToolSet.Answers VANILLA=(stacks,state)->{
        var out=new baritone.gtnh.ReferenceToolPolicy.Answer[stacks.length];
        float hardness=state.getBlock().getBlockHardness(null,state.x,state.y,state.z);
        for(int i=0;i<stacks.length;i++){ItemStack s=stacks[i];
            boolean harvest=state.getMaterial().isToolNotRequired()||s!=null&&s.getItem().canHarvestBlock(state.getBlock(),s);
            float speed=s==null?1:s.getItem().getDigSpeed(s,state.getBlock(),state.meta);
            out[i]=new baritone.gtnh.ReferenceToolPolicy.Answer(hardness<0?-1:speed/hardness/(harvest?30:100),harvest);}
        return out;
    };
    /** A search over this terrain within 64 blocks of the origin, by a sprinting player with bare hands: the walker's own moves, then these. */
    public static CalculationContext context(Terrain terrain,Move... added){
        var inputs=new CalculationInputs(null,new BlockStateInterface(terrain,(x,z)->Math.abs(x)<64&&Math.abs(z)<64),new ToolSet(new ItemStack[9],0,1,VANILLA),
            false,false,true,0,0,new WorldMemory.Snapshot(0,Map.of(),Map.of(),Map.of()),false,p->true);
        return new CalculationContext(NOBODY,true,inputs.withMoves(baritone.gtnh.pathing.MoveRegistry.with(added)));
    }
    public static PathCalculationResult search(CalculationContext context,BetterBlockPos start,Goal goal){
        return new AStarPathFinder(start.x,start.y,start.z,goal,new Favoring(null,context),context).calculate(2000,4000);
    }
    /** The route, which must reach the goal. */
    public static IPath path(CalculationContext context,BetterBlockPos start,Goal goal){
        var result=search(context,start,goal);
        if(result.getType()!=PathCalculationResult.Type.SUCCESS_TO_GOAL)throw new AssertionError(result.toString());
        return result.getPath().orElseThrow();
    }
}
