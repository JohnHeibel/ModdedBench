// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh.pathing;

import baritone.api.IBaritone;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.compat.BlockPos;
import baritone.gtnh.BlockShapes;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.block.BlockDoor;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;

/**
 * Doors and gates a path opened, closed again behind it: a player leaves a door as they found it. MovementTraverse
 * records a door when it clicks one that was shut across its lane; once the player is clear of the door's cells and
 * the path does not lead back through them, the executor spends a few ticks turning to it and clicking it, as a player
 * does. Shut is judged as the opening was, by the door's boxes measured now crossing the lane, so a modded door is
 * handled the same and nothing here names a door. A door shut by someone else, gone, or left out of reach is dropped;
 * three clicks that do not shut it give up. Off with the closeDoorsBehind setting. Game thread only.
 */
public final class DoorsBehind {
    private static final int CLICKS=3,AIM_TICKS=20;
    private static final double FORGET=8;
    private static final class Door{
        final BlockPos pos;final boolean alongX;int clicks,wait,aim;
        Door(BlockPos pos,boolean alongX){this.pos=pos;this.alongX=alongX;}
    }
    private final List<Door> doors=new ArrayList<>();

    /** A traverse is clicking this door open: it was shut across the lane of a walk along x (or z). */
    public void opened(BlockPos pos,boolean alongX){
        if(doors.stream().noneMatch(d->d.pos.equals(pos)))doors.add(new Door(pos,alongX));
    }
    public boolean pending(){return !doors.isEmpty();}

    /** One tick: true when it used the tick turning to a door or clicking it, so the path waits; `ahead` is the cells the path still walks through. */
    public boolean tick(IBaritone baritone,IPlayerContext ctx,Collection<? extends BlockPos> ahead){
        if(doors.isEmpty())return false;
        World world=ctx.world().nativeWorld;
        for(Iterator<Door> it=doors.iterator();it.hasNext();){
            Door d=it.next();
            Block block=world.getBlock(d.pos.getX(),d.pos.getY(),d.pos.getZ());
            List<BlockPos> cells=cells(world,d.pos,block);
            if(cells.isEmpty()||BlockShapes.blocksLane(world,d.pos.getX(),d.pos.getY(),d.pos.getZ(),d.alongX)||d.clicks>=CLICKS||d.aim>AIM_TICKS
                    ||ctx.player().getDistanceSq(d.pos.getX()+.5,d.pos.getY()+.5,d.pos.getZ()+.5)>FORGET*FORGET){it.remove();continue;}
            if(cells.stream().anyMatch(ahead::contains)||cells.stream().anyMatch(c->touches(ctx,c)))continue;  // still to walk through, or still in it
            Optional<Rotation> look=RotationUtils.reachable(ctx,d.pos);
            if(!look.isPresent())continue;
            baritone.getInputOverrideHandler().clearAllKeys();
            baritone.getLookBehavior().updateTarget(look.get(),true);
            if(d.wait>0){d.wait--;return true;}
            if(ctx.getSelectedBlock().filter(cells::contains).isPresent()){
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT,true);ctx.playerController().markInteraction(cells);d.clicks++;d.wait=2;
            }else d.aim++;
            return true;
        }
        return false;
    }

    /** The cells one door fills: both halves of a door, the one cell of a gate; none once it is no door or gate. */
    public static List<BlockPos> cells(World world,BlockPos p,Block block){
        if(block instanceof BlockDoor){
            BlockPos other=world.getBlock(p.getX(),p.getY()+1,p.getZ())==block?p.up():p.down();
            return List.of(p,other);
        }
        return block instanceof net.minecraft.block.BlockFenceGate?List.of(p):List.of();
    }
    private static boolean touches(IPlayerContext ctx,BlockPos c){
        return ctx.player().boundingBox.intersectsWith(AxisAlignedBB.getBoundingBox(c.getX()-.05,c.getY(),c.getZ()-.05,c.getX()+1.05,c.getY()+1,c.getZ()+1.05));
    }
}
