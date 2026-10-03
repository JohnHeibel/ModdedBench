// SPDX-License-Identifier: LGPL-3.0-or-later
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
