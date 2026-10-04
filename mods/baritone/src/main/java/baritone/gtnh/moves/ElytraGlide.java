// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh.moves;

import baritone.api.IBaritone;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.gtnh.pathing.Move;
import baritone.gtnh.pathing.MoveRegistry;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.utils.pathing.MutableMoveResult;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;

/**
 * Gliding on worn wings, from a ledge to ground below: the worked example of an added kind of movement
 * (docs/MOVEMENTS.md). Everything about the wings is in this file; the walker knows only the line that registers SOURCE.
 *
 * A glide is one step of a route: walk off the ledge, open the wings in the air with a press of jump, hold a pitch, touch
 * down, stop. The search asks plan() where that step ends from a cell, which flies it ahead tick by tick in the game's own
 * arithmetic (fall, fly) through the cells the search knows. Gliding then plays it: each tick in the air it flies every
 * pitch it could hold ahead in the same arithmetic and holds the one that comes down nearest the planned touchdown, so a
 * flight that started a little off the plan still ends on it.
 *
 * One move is one direction and one pitch, so from a ledge the search has a short, a middle and a long glide each way.
 */
public record ElytraGlide(int dx,int dz,float pitch,int airtime) implements Move {
    /** The pitches a glide is planned at, degrees below level: steeper comes down sooner and harder. */
    static final float[] PITCHES={10,20,30};
    /** Air a ledge needs under its edge for a glide to be asked at all. */
    static final int DROP=6;
    /** Ticks falling before the wings are open: the press, and the server's answer. */
    static final int FALL=3;
    /** Where the body leaves the ledge, from the cell's centre, and how fast it walks there. */
    static final double EDGE=0.8,WALK=0.2;
    /** The body slides on after touching down: this many ticks' worth of the speed it came in at. */
    static final double SLIDE=4;
    /** A touchdown faster than this downward hurts. */
    static final double HARD=-0.45;
    static final int[][] WAYS={{1,0},{-1,0},{0,1},{0,-1}};

    /** The glides this body has now: none without unbroken wings on, else every way and pitch, lasting what the wings last. */
    public static final MoveRegistry.Source SOURCE=player->{
        int airtime=Wings.airtime(player.player());
        List<Move> moves=new ArrayList<>();
        if(airtime>0)for(int[] way:WAYS)for(float pitch:PITCHES)moves.add(new ElytraGlide(way[0],way[1],pitch,airtime));
        return moves;
    };

    /** What the game says about the wings. The pack's elytra is Et Futurum's; without that mod there are no glides. */
    static final class Wings {
        private static Method worn,broken,flying;
        static {
            try {
                Class<?> item=Class.forName("ganymedes01.etfuturum.items.equipment.ItemArmorElytra");
                worn=item.getMethod("getElytra",EntityLivingBase.class);broken=item.getMethod("isBroken",ItemStack.class);
                flying=Class.forName("ganymedes01.etfuturum.elytra.IElytraPlayer").getMethod("etfu$isElytraFlying");
            } catch(ReflectiveOperationException|LinkageError absent){worn=null;}
        }
        /** Ticks of flight the worn wings have left (they wear one point a second), 0 with none worn or broken. */
        static int airtime(EntityLivingBase body){
            if(worn==null||body==null)return 0;
            try {
                ItemStack wings=(ItemStack)worn.invoke(null,body);
                if(wings==null||(boolean)broken.invoke(null,wings))return 0;
                return wings.isItemStackDamageable()?(wings.getMaxDamage()-wings.getItemDamage()-1)*20:Integer.MAX_VALUE;
            } catch(ReflectiveOperationException e){return 0;}
        }
        static boolean flying(EntityLivingBase body){
            try {return worn!=null&&(boolean)flying.invoke(body);} catch(ReflectiveOperationException|RuntimeException e){return false;}
        }
    }

    // ---- the game's arithmetic: one tick of motion {x,y,z}, before the body is moved by it ----

    /** A tick of falling with the wings shut. */
    public static void fall(double[] v){v[1]=(v[1]-0.08)*0.98;v[0]*=0.91;v[2]*=0.91;}
    /** A tick of flight with the wings open, looking this way: the look turns falling into speed and speed into lift. */
    public static void fly(double[] v,float yaw,float pitch){
        double p=Math.toRadians(pitch),a=Math.toRadians(yaw),flat=Math.cos(p);
        double lx=-Math.sin(a)*flat,lz=Math.cos(a)*flat,speed=Math.sqrt(v[0]*v[0]+v[2]*v[2]),lift=flat*flat;
        flat=Math.abs(flat);
        v[1]+=-0.08+lift*0.06;
        if(v[1]<0&&flat>0){double d=v[1]*-0.1*lift;v[1]+=d;v[0]+=lx*d/flat;v[2]+=lz*d/flat;}
        if(p<0&&flat>0){double d=speed*-Math.sin(p)*0.04;v[1]+=d*3.2;v[0]-=lx*d/flat;v[2]-=lz*d/flat;}
        if(flat>0){v[0]+=(lx/flat*speed-v[0])*0.1;v[2]+=(lz/flat*speed-v[2])*0.1;}
        v[0]*=0.99;v[1]*=0.98;v[2]*=0.99;
    }
    /** The yaw that looks along a direction. */
    static float yaw(double dx,double dz){return (float)Math.toDegrees(Math.atan2(-dx,dz));}

    // ---- the search's side ----

    /** A planned glide: where the feet touch down, the cells the body slides on, where it then stands, the ticks it takes, the cells it flies through. */
    record Flight(double touchX,double touchZ,BetterBlockPos touchdown,int slide,BetterBlockPos dest,double cost,List<BetterBlockPos> cells){}

    /**
     * The glide from standing in this cell, or null where there is none: no ledge, something in the way, wings that
     * would wear out, a touchdown that is too hard or not on ground to stand on, or a chunk the search cannot see.
     */
    Flight plan(CalculationContext context,int x,int y,int z){
        int ax=x+dx,az=z+dz;
        if(!MovementHelper.fullyPassable(context,ax,y-1,az)||!MovementHelper.fullyPassable(context,ax,y,az)||!MovementHelper.fullyPassable(context,ax,y+1,az))return null;
        for(int down=2;down<=DROP;down++)if(!MovementHelper.fullyPassable(context,ax,y-down,az))return null;
        double px=x+.5+dx*EDGE,py=y,pz=z+.5+dz*EDGE;
        double[] v={dx*WALK,0,dz*WALK};
        float yaw=yaw(dx,dz);
        List<BetterBlockPos> cells=new ArrayList<>();
        for(int tick=0;tick<airtime&&py>2;tick++){
            if(tick<FALL)fall(v);else fly(v,yaw,pitch);
            for(int half=1;half<=2;half++){                                    // faster than a cell a tick: look at the middle of the tick's flight too
                double qx=px+v[0]*half/2,qy=py+v[1]*half/2,qz=pz+v[2]*half/2;
                int cx=(int)Math.floor(qx),cy=(int)Math.floor(qy),cz=(int)Math.floor(qz);
                if(!context.isLoaded(cx,cz))return null;
                if(!MovementHelper.fullyPassable(context,cx,cy,cz))return v[1]>HARD?land(context,qx,qz,Math.abs(v[0]*dx+v[2]*dz),cx,cy+1,cz,tick,cells):null;
                if(!clear(context,cx,cy,cz))return null;
                BetterBlockPos cell=new BetterBlockPos(cx,cy,cz);
                if(cells.isEmpty()||!cells.get(cells.size()-1).equals(cell))cells.add(cell);
            }
            px+=v[0];py+=v[1];pz+=v[2];
        }
        return null;
    }
    /** Room to fly through a cell: the body and a cell over its head, a cell to each side, and under it nothing but air or ground it could come down on. */
    private boolean clear(CalculationContext context,int x,int y,int z){
        for(int up=1;up<=3;up++)if(!MovementHelper.fullyPassable(context,x,y+up,z))return false;
        for(int side=-1;side<=1;side+=2)for(int up=0;up<=2;up++)if(!MovementHelper.fullyPassable(context,x+side*dz,y+up,z+side*dx))return false;
        for(int down=1;down<=2;down++)if(!MovementHelper.fullyPassable(context,x,y-down,z)&&!MovementHelper.canWalkOn(context,x,y-down,z))return false;
        return true;
    }
    /** The end of a glide whose feet reach this cell at this speed: level ground to stand on from the touchdown to past where the slide stops. */
    private Flight land(CalculationContext context,double touchX,double touchZ,double speed,int x,int y,int z,int ticks,List<BetterBlockPos> cells){
        int slide=(int)Math.ceil(SLIDE*speed);
        for(int along=0;along<=slide+2;along++){
            int sx=x+dx*along,sz=z+dz*along;
            if(!context.isLoaded(sx,sz)||!MovementHelper.canWalkOn(context,sx,y-1,sz))return null;
            for(int up=0;up<=2;up++)if(!MovementHelper.fullyPassable(context,sx,y+up,sz))return null;
        }
        BetterBlockPos touchdown=new BetterBlockPos(x,y,z);
        return new Flight(touchX,touchZ,touchdown,slide,new BetterBlockPos(x+dx*slide,y,z+dz*slide),
            ActionCosts.WALK_OFF_BLOCK_COST+ticks+slide*ActionCosts.WALK_ONE_BLOCK_COST/2,cells);
    }

    @Override public int xOffset(){return dx;}
    @Override public int yOffset(){return 0;}
    @Override public int zOffset(){return dz;}
    @Override public boolean dynamicXZ(){return true;}
    @Override public boolean dynamicY(){return true;}
    @Override public void apply(CalculationContext context,int x,int y,int z,MutableMoveResult result){
        Flight flight=plan(context,x,y,z);
        if(flight==null)return;
        result.x=flight.dest.x;result.y=flight.dest.y;result.z=flight.dest.z;result.cost=flight.cost;
    }
    @Override public Movement apply0(CalculationContext context,BetterBlockPos src){
        Flight flight=plan(context,src.x,src.y,src.z);
        return flight==null?null:new Gliding(context.getBaritone(),src,this,flight);
    }

    // ---- the body's side ----

    /** Playing a planned glide. Game thread. */
    public static final class Gliding extends Movement {
        private final ElytraGlide glide;
        private final Flight flight;
        private boolean left,pressed;

        Gliding(IBaritone baritone,BetterBlockPos src,ElytraGlide glide,Flight flight){
            super(baritone,src,flight.dest,new BetterBlockPos[0]);
            this.glide=glide;this.flight=flight;
        }
        /** What the same glide costs now: nothing where the body no longer has it (the wings came off), or where it no longer ends here. */
        @Override public double calculateCost(CalculationContext context){
            for(Move move:context.moves)if(move instanceof ElytraGlide now&&now.dx==glide.dx&&now.dz==glide.dz&&now.pitch==glide.pitch){
                Flight again=now.plan(context,src.x,src.y,src.z);
                return again!=null&&again.dest.equals(dest)?again.cost:ActionCosts.COST_INF;
            }
            return ActionCosts.COST_INF;
        }
        /** The ledge, the cells of the planned flight with the room a flight needs around them, and the ground it ends on. */
        @Override protected Set<BetterBlockPos> calculateValidPositions(){
            Set<BetterBlockPos> valid=new HashSet<>();
            valid.add(src);
            for(BetterBlockPos cell:flight.cells)for(int side=-1;side<=1;side++)for(int up=-3;up<=3;up++)valid.add(new BetterBlockPos(cell.x+side*glide.dz,cell.y+up,cell.z+side*glide.dx));
            for(int along=-1;along<=flight.slide+2;along++)for(int up=0;up<=1;up++)valid.add(new BetterBlockPos(flight.touchdown.x+glide.dx*along,dest.y+up,flight.touchdown.z+glide.dz*along));
            return valid;
        }
        /** Off the ledge there is no stopping. */
        @Override protected boolean safeToCancel(MovementState state){return !left||ctx.player().onGround;}
        @Override public void reset(){super.reset();left=pressed=false;}

        @Override public MovementState updateState(MovementState state){
            super.updateState(state);
            if(state.getStatus()!=MovementStatus.RUNNING)return state;
            var body=ctx.player();
            BetterBlockPos feet=ctx.playerFeet();
            float along=yaw(glide.dx,glide.dz);
            if(Wings.flying(body)&&!body.onGround){
                left=true;
                double ahead=(flight.touchX-body.posX)*glide.dx+(flight.touchZ-body.posZ)*glide.dz;
                float yaw=ahead>4?yaw(flight.touchX-body.posX,flight.touchZ-body.posZ):along;
                float pitch=pitchToward(ahead,body.boundingBox.minY-dest.y,body.motionX*glide.dx+body.motionZ*glide.dz,body.motionY);
                return state.setTarget(new MovementState.MovementTarget(new Rotation(yaw,pitch),true));
            }
            if(!body.onGround){                                                 // falling: the wings open on a press of jump, not on a held key
                left=true;
                if(pressed=!pressed)state.setInput(Input.JUMP,true);
                return state.setTarget(new MovementState.MovementTarget(new Rotation(along,ctx.playerRotations().getPitch()),true));
            }
            if(feet.equals(dest))return state.setStatus(MovementStatus.SUCCESS);
            int from=(feet.x-src.x)*glide.dx+(feet.z-src.z)*glide.dz,to=(dest.x-feet.x)*glide.dx+(dest.z-feet.z)*glide.dz;
            boolean inLine=(feet.x-src.x)*glide.dz==0&&(feet.z-src.z)*glide.dx==0;
            if(!left&&feet.y==src.y&&inLine&&from>=0&&from<=1)MovementHelper.moveTowards(ctx,state,new BetterBlockPos(src.x+2*glide.dx,src.y,src.z+2*glide.dz));   // on the ledge: walk off it
            else if(feet.y==dest.y&&inLine&&to>=-2&&to<=flight.slide+1){                     // down: let the slide run out, then stand where the route goes on
                if(Math.hypot(body.motionX,body.motionZ)<WALK)MovementHelper.moveTowards(ctx,state,dest);
            }else return state.setStatus(MovementStatus.UNREACHABLE);          // on the ground somewhere else: the route is planned again from here
            return state;
        }
        /**
         * The pitch to hold now: each one a player could hold is flown ahead from the body's own speed, and the one that
         * comes down nearest the planned touchdown, and not too hard, wins.
         */
        private float pitchToward(double ahead,double height,double speed,double sink){
            float best=glide.pitch;double miss=Double.MAX_VALUE;
            for(float pitch=-10;pitch<=45;pitch+=2.5f){
                double[] v={speed,sink,0};double s=0,h=height;
                for(int tick=0;tick<600&&h>0;tick++){fly(v,-90,pitch);s+=v[0];h+=v[1];}
                double off=Math.abs(s-ahead)+(v[1]>HARD?0:100);
                if(off<miss){miss=off;best=pitch;}
            }
            return best;
        }
    }
}
