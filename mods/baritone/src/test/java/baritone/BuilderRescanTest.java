// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone;

import baritone.api.schematic.ISchematic;
import baritone.api.utils.*;
import baritone.compat.*;
import baritone.pathing.movement.CalculationContext;
import baritone.process.BuilderProcess;
import baritone.utils.*;
import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.util.ForgeDirection;
import org.junit.*;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;

/**
 * What the source builder knows after a full rescan, by running its own fullRecalc (private, reached by reflection on an
 * instance made without a game) over plans of several shapes. This records the behaviour, it does not change it: the
 * rescan takes no account of where the player is, so what it keeps is the first incorrectSize+1 wrong cells in y, z, x
 * order. It only runs when nothing the builder knows is left, which the live ledgers show is rare (see
 * harness/smoke/build_order.py); the numbers here are how far that can then send the player.
 */
@org.junit.runner.RunWith(ForgePlanningTestRunner.class)
public class BuilderRescanTest {
    @BeforeClass public static void bootstrap(){
        cpw.mods.fml.common.Loader.injectData("7","99","40","1614","1.7.10","9.05",new java.io.File("."),List.of());
        net.minecraft.init.Bootstrap.func_151354_b();
    }
    @Before public void reset(){Baritone.settings().allSettings.forEach(s->s.reset());}
    private static Block cobble(){return net.minecraft.init.Blocks.cobblestone;}
    private static final class Terrain implements IBlockAccess {
        final Set<BlockPos> solid=new HashSet<>();
        public Block getBlock(int x,int y,int z){return solid.contains(new BlockPos(x,y,z))?cobble():y<64?Blocks.STONE:Blocks.AIR;}
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
    private static <T>T allocate(Class<T> type) throws Exception {
        Class<?> unsafeClass=Class.forName("sun.misc.Unsafe",true,ClassLoader.getSystemClassLoader());
        Field field=unsafeClass.getDeclaredField("theUnsafe");field.setAccessible(true);
        Object unsafe=field.get(null);return type.cast(unsafeClass.getMethod("allocateInstance",Class.class).invoke(unsafe,type));
    }
    private static void set(Class<?> owner,Object target,String name,Object value) throws Exception {
        Field f=owner.getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    private long lastNanos;
    /** The source rescan over `plan` (absolute cells, all cobblestone) in a world where `built` already stand. */
    private Set<BlockPos> rescan(Set<BlockPos> plan,Set<BlockPos> built) throws Exception {
        int minX=plan.stream().mapToInt(BlockPos::getX).min().orElseThrow(),minY=plan.stream().mapToInt(BlockPos::getY).min().orElseThrow(),minZ=plan.stream().mapToInt(BlockPos::getZ).min().orElseThrow();
        int w=plan.stream().mapToInt(BlockPos::getX).max().orElseThrow()-minX+1,h=plan.stream().mapToInt(BlockPos::getY).max().orElseThrow()-minY+1,l=plan.stream().mapToInt(BlockPos::getZ).max().orElseThrow()-minZ+1;
        ISchematic schematic=new ISchematic(){
            public int widthX(){return w;}public int heightY(){return h;}public int lengthZ(){return l;}
            public boolean inSchematic(int x,int y,int z,IBlockState current){return plan.contains(new BlockPos(x+minX,y+minY,z+minZ));}
            public IBlockState desiredState(int x,int y,int z,IBlockState current,List<IBlockState> materials){return new IBlockState(cobble(),0,null,x+minX,y+minY,z+minZ);}
        };
        Terrain terrain=new Terrain();terrain.solid.addAll(built);
        var builder=allocate(BuilderProcess.class);builder.resetAdapters();
        set(BuilderProcess.class,builder,"schematic",schematic);set(BuilderProcess.class,builder,"origin",new Vec3i(minX,minY,minZ));
        set(BuilderProcess.class,builder,"observedCompleted",BuilderProcess.class.getDeclaredField("observedCompleted").getType().getConstructor().newInstance());set(BuilderProcess.class,builder,"approxPlaceable",List.of());
        var context=allocate(BuilderProcess.BuilderCalculationContext.class);
        set(CalculationContext.class,context,"bsi",new BlockStateInterface(terrain,(x,z)->true));
        Method full=BuilderProcess.class.getDeclaredMethod("fullRecalc",BuilderProcess.BuilderCalculationContext.class);full.setAccessible(true);
        long started=System.nanoTime();full.invoke(builder,context);lastNanos=System.nanoTime()-started;
        Set<BlockPos> kept=new HashSet<>();for(var p:builder.incorrectPositions())kept.add(new BlockPos(p.x,p.y,p.z));
        return kept;
    }
    private static List<BlockPos> scanOrder(Set<BlockPos> cells){
        return cells.stream().sorted(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getX)).toList();
    }
    private static double nearest(Set<BlockPos> cells,BlockPos from){
        return cells.stream().mapToDouble(c->Math.sqrt(c.distanceSq(from))).min().orElseThrow();
    }
    /** Kept cells are the scan's first 101; returns {nearest kept cell, nearest wrong cell} from `player`. */
    private double[] measure(Set<BlockPos> plan,Set<BlockPos> built,BlockPos player) throws Exception {
        Set<BlockPos> wrong=new HashSet<>(plan);wrong.removeAll(built);
        var kept=rescan(plan,built);
        assertEquals(new HashSet<>(scanOrder(wrong).subList(0,Math.min(wrong.size(),Baritone.settings().incorrectSize.value+1))),kept);
        return new double[]{nearest(kept,player),nearest(wrong,player)};
    }
    private static Set<BlockPos> box(int x0,int y0,int z0,int x1,int y1,int z1){
        Set<BlockPos> out=new HashSet<>();for(int x=x0;x<=x1;x++)for(int y=y0;y<=y1;y++)for(int z=z0;z<=z1;z++)out.add(new BlockPos(x,y,z));return out;
    }
    private static Set<BlockPos> walls(int y0,int y1){
        Set<BlockPos> out=box(0,y0,0,24,y1,24);out.removeIf(p->p.getX()>0&&p.getX()<24&&p.getZ()>0&&p.getZ()<24);return out;
    }
    /** A 25 x 25 slab at height y, laid but for its five northmost rows (z 0..4) and its two southmost (z 23..24). */
    private static Set<BlockPos> slabWithBothEndsOpen(int y){
        Set<BlockPos> built=box(0,y,0,24,y,24);built.removeIf(p->p.getZ()<=4||p.getZ()>=23);return built;
    }
    @Test public void aFloorOpenAtBothEndsIsResumedAtTheNorthEndWhereverThePlayerIs() throws Exception {
        // The player stands on the laid part six rows from the south gap, out of the per-tick scan's reach of it (5), so
        // a rescan is what happens next. It keeps rows z 0..3 and one cell of row 4: 14 blocks north, not 6 south.
        var d=measure(box(0,64,0,24,64,24),slabWithBothEndsOpen(64),new BlockPos(12,65,17));
        assertEquals(6.1,d[1],0.1);assertEquals(14.0,d[0],0.1);
    }
    @Test public void aPerimeterWallIsKnownOneRowAtATime() throws Exception {
        // 25 x 25 walls five high, nothing built: the bottom row (96 cells) and five cells of the second.
        var plan=walls(64,68);var kept=rescan(plan,Set.of());
        assertEquals(96,kept.stream().filter(p->p.getY()==64).count());assertEquals(5,kept.stream().filter(p->p.getY()==65).count());
        // Bottom two rows built except the south-east corner column: those two cells come first, then the whole third row.
        Set<BlockPos> built=walls(64,65);built.removeIf(p->p.getX()==24&&p.getZ()==24);
        kept=rescan(plan,built);
        assertTrue(kept.containsAll(Set.of(new BlockPos(24,64,24),new BlockPos(24,65,24))));
        assertEquals(96,kept.stream().filter(p->p.getY()==66).count());assertEquals(3,kept.stream().filter(p->p.getY()==67).count());
    }
    @Test public void aRoofOverStandingWallsBehavesAsTheFloorDoes() throws Exception {
        // Walls y 64..68 stand; the roof y 69 is open at both ends as above, the player on it.
        Set<BlockPos> plan=walls(64,68);plan.addAll(box(0,69,0,24,69,24));
        Set<BlockPos> built=walls(64,68);built.addAll(slabWithBothEndsOpen(69));
        var d=measure(plan,built,new BlockPos(12,70,17));
        assertEquals(6.1,d[1],0.1);assertEquals(14.0,d[0],0.1);
    }
    @Test public void anLShapedWallWithPillarsIsKnownLowestRowFirstAcrossAllOfIt() throws Exception {
        // Not a box: an L of two walls four high and a grid of pillars four high between them.
        Set<BlockPos> plan=new HashSet<>();plan.addAll(box(0,64,0,24,67,0));plan.addAll(box(0,64,0,0,67,24));
        for(int x=4;x<=24;x+=4)for(int z=4;z<=24;z+=4)plan.addAll(box(x,64,z,x,67,z));
        var kept=rescan(plan,Set.of());
        // 49 wall cells and 36 pillar feet on the bottom row, then the first 16 of the second row: all of it on the north wall.
        assertEquals(85,kept.stream().filter(p->p.getY()==64).count());
        assertTrue(kept.stream().filter(p->p.getY()==65).allMatch(p->p.getZ()==0));
        // Every pillar but the far one finished, the walls finished: the one cell left is found however far it is.
        Set<BlockPos> built=new HashSet<>(plan);built.remove(new BlockPos(24,67,24));
        var d=measure(plan,built,new BlockPos(1,65,1));
        assertEquals(d[1],d[0],0.01);
    }
    @Test public void aRescanOfNineThousandCellsCostsAFewMilliseconds() throws Exception {
        // A 95 x 95 floor (9,025 cells) complete but for its last cell: the scan visits every cell. Warm it, then time it.
        var plan=box(0,64,0,94,64,94);Set<BlockPos> built=new HashSet<>(plan);built.remove(new BlockPos(94,64,94));
        long best=Long.MAX_VALUE;
        for(int i=0;i<30;i++){assertEquals(Set.of(new BlockPos(94,64,94)),rescan(plan,built));best=Math.min(best,lastNanos);}
        System.out.println("RESCAN_9025_CELLS_MICROS "+best/1000);
        assertTrue("a full rescan of 9,025 cells took "+best/1000+" us",best<50_000_000L);
    }
}
