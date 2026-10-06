// SPDX-License-Identifier: MIT
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.client;

import java.util.ArrayList;
import java.util.List;

/** Aim directions to test with the game's ray tracer, never assumed collision surfaces. */
final class FaceSamples {
    /** A tangent on a cell boundary can round onto the adjacent cell in the rendered aim. */
    static boolean stableHit(int face,double x,double y,double z) {
        int axis=face<2?1:face<4?2:0;
        double[] hit={x,y,z};
        for(int i=0;i<3;i++)if(i!=axis&&(Math.abs(hit[i])<1e-4||Math.abs(hit[i]-1)<1e-4))return false;
        return true;
    }
    /** How far along the face the surface has to go on to each side of a hit. A hit on the very edge of what is there (a
     *  chest's top, seen low, is hit at its far edge) is where the look of the click itself can pass it by. */
    static final double MARGIN=.02;
    static double[][] beside(int face,double[] hit) {
        int axis=face<2?1:face<4?2:0;
        double[][] out=new double[4][];
        for(int i=0;i<4;i++){out[i]=hit.clone();out[i][(axis+1+i/2)%3]+=i%2==0?MARGIN:-MARGIN;}
        return out;
    }
    /** Where to aim on a face, block-local: the first sample whose hit has surface on every side of it, else the first hit
     *  there is, else null. seen is the game's ray: given a point to look toward, where that look lands on this face. */
    static double[] aim(int face,double[] selectedCentre,java.util.function.UnaryOperator<double[]> seen) {
        double[] edge=null;
        for(double[] h:points(face,selectedCentre)) {
            double[] hit=seen.apply(h);
            if(hit==null||!stableHit(face,hit[0],hit[1],hit[2]))continue;
            boolean wide=true;for(double[] near:beside(face,hit))wide&=seen.apply(near)!=null;
            if(wide)return hit;
            if(edge==null)edge=hit;
        }
        return edge;
    }
    static List<double[]> points(int face,double[] selectedCentre) {
        List<double[]> out=new ArrayList<>();
        int axis=face<2?1:face<4?2:0;
        int a=(axis+1)%3,b=(axis+2)%3;
        for(double u:new double[]{.5,.1,.9})for(double v:new double[]{.5,.1,.9}) {
            double[] point={.5,.5,.5};point[axis]=face%2;point[a]=u;point[b]=v;out.add(point);
        }
        // A multi-block selection centre may belong to a different part of the block.
        // Prefer directions into the requested cell, keeping extended shapes as a fallback.
        out.add(selectedCentre.clone());return out;
    }
}
