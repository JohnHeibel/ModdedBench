// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh;

import baritone.compat.BlockPos;
import baritone.compat.Registry;
import baritone.gtnh.pathing.*;
import baritone.gtnh.pathing.ClickSpace.Voxel;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;

/**
 * A time-sliced copy of the blocks around some clicks, for the pure vantage search: collision boxes, the selection box a
 * look ray stops at, tile entity and fluid flags, and which solid cells the world memory protects. Game thread only;
 * each step() call stays within its time budget.
 */
final class ClickWorld {
    /** The most blocks one copy holds: a stage whose click cells lie further apart than this allows is refused when the job begins. */
    static final int MAX_VOLUME=200_000;
    static final String SPREAD="clicks_too_spread: the click cells of one stage lie too far apart to look at together (more than "+MAX_VOLUME+" blocks around them); give the far ones another stage or another job";
    final World world;
    final BlockPos min,max;
    private final int w,h,d;
    private final boolean override;
    private final Map<BlockPos,Voxel> cells=new HashMap<>();
    final Set<BlockPos> protectedCells=new HashSet<>();
    private int cursor;
    ClickWorld(World world,BlockPos a,BlockPos b,boolean override) {
        this.world=world;this.override=override;
        min=new BlockPos(Math.min(a.getX(),b.getX()),Math.max(0,Math.min(a.getY(),b.getY())),Math.min(a.getZ(),b.getZ()));
        max=new BlockPos(Math.max(a.getX(),b.getX()),Math.min(255,Math.max(a.getY(),b.getY())),Math.max(a.getZ(),b.getZ()));
        w=max.getX()-min.getX()+1;h=max.getY()-min.getY()+1;d=max.getZ()-min.getZ()+1;
        if((long)w*h*d>MAX_VOLUME)throw new IllegalArgumentException(SPREAD);
    }
    /** The box around every target, widened by margin (reach and a step or two). */
    static ClickWorld around(World world,Collection<BlockPos> targets,int margin,boolean override) {
        int x0=Integer.MAX_VALUE,y0=Integer.MAX_VALUE,z0=Integer.MAX_VALUE,x1=Integer.MIN_VALUE,y1=Integer.MIN_VALUE,z1=Integer.MIN_VALUE;
        for(BlockPos p:targets){x0=Math.min(x0,p.getX());y0=Math.min(y0,p.getY());z0=Math.min(z0,p.getZ());x1=Math.max(x1,p.getX());y1=Math.max(y1,p.getY());z1=Math.max(z1,p.getZ());}
        return new ClickWorld(world,new BlockPos(x0-margin,y0-margin-2,z0-margin),new BlockPos(x1+margin,y1+margin,z1+margin),override);
    }
    /** Copy cells until done or the budget is spent: true when the copy is complete. */
    boolean step(long budgetNanos) {
        long end=System.nanoTime()+budgetNanos;int total=w*h*d;
        while(cursor<total&&System.nanoTime()<end)for(int i=0;i<64&&cursor<total;i++,cursor++) {
            int y=cursor%h+min.getY(),z=(cursor/h)%d+min.getZ(),x=cursor/(h*d)+min.getX();
            BlockPos p=new BlockPos(x,y,z);Voxel v=voxel(world,x,y,z);cells.put(p,v);
            if(!v.replaceable()&&v.kind()!=TerrainGrid.UNKNOWN&&WorkAccess.protection(p,override)!=null)protectedCells.add(p);
        }
        return cursor>=total;
    }
    boolean complete(){return cursor>=w*h*d;}
    /** How far the copy has come: work the stall watchdog can see. */
    int cursor(){return cursor;}
    ClickSpace space(){if(!complete())throw new IllegalStateException("capture incomplete");return new ClickSpace(min,max,cells);}
    static Voxel voxel(World world,int x,int y,int z) {
        var cell=ForgeSnapshot.sample(world,x,y,z);
        if(cell.kind()==TerrainGrid.UNKNOWN)return new Voxel(TerrainGrid.UNKNOWN,List.of(),List.of(),false,false,"unknown");
        Block b=world.getBlock(x,y,z);int meta=world.getBlockMetadata(x,y,z);
        boolean air=b.isAir(world,x,y,z);
        List<CollisionBox> collision=cell.kind()==TerrainGrid.SUPPORT?List.of(new CollisionBox(x,y,z,x+1,y+1,z+1)):cell.boxes();
        List<CollisionBox> selection=List.of();
        if(!air&&b.canCollideCheck(meta,false)) {
            b.setBlockBoundsBasedOnState(world,x,y,z);
            AxisAlignedBB s=b.getSelectedBoundingBoxFromPool(world,x,y,z);
            if(s!=null&&s.maxX>s.minX&&s.maxY>s.minY&&s.maxZ>s.minZ)selection=List.of(new CollisionBox(s.minX,s.minY,s.minZ,s.maxX,s.maxY,s.maxZ));
        }
        boolean replaceable=air||b.isReplaceable(world,x,y,z);
        return new Voxel(cell.kind(),collision,selection,b.hasTileEntity(meta),replaceable,air?"minecraft:air":String.valueOf(Registry.name(b)));
    }
    /** The eye height and reach the search assumes: the player's measured eye, lowered while sneaking. */
    static Vantages.Body body(Minecraft mc,boolean sneak) {
        double eye=mc.thePlayer.getPosition(1).yCoord-mc.thePlayer.boundingBox.minY;
        double standing=mc.thePlayer.isSneaking()?eye+.08:eye;
        return new Vantages.Body(sneak?standing-.08:standing,mc.playerController.getBlockReachDistance());
    }
}
