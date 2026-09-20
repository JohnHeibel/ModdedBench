// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
/* Derived from Baritone SphereMask and CylinderMask. LGPL-3.0-or-later. */
package baritone.gtnh.pathing;

public final class ConstructionMask {
    private ConstructionMask() {}
    public static boolean contains(String shape,String axis,int x,int y,int z,int w,int h,int d) {
        if(shape.equals("sphere")||shape.equals("hsphere")) {
            double a=Math.abs(x+.5-w/2.0),b=Math.abs(y+.5-h/2.0),c=Math.abs(z+.5-d/2.0);
            double ra=w*w/4.0,rb=h*h/4.0,rc=d*d/4.0;
            return !outside(a,b,c,ra,rb,rc)&&(shape.equals("sphere")||outside(a+1,b,c,ra,rb,rc)||outside(a,b+1,c,ra,rb,rc)||outside(a,b,c+1,ra,rb,rc));
        }
        double ca=(axis.equals("x")?h:w)/2.0,cb=(axis.equals("z")?h:d)/2.0;
        double a=Math.abs((axis.equals("x")?y:x)+.5-ca),b=Math.abs((axis.equals("z")?y:z)+.5-cb);
        double ra=(ca-1)*(ca-1),rb=(cb-1)*(cb-1);
        // Keep the upstream cylinder's inset radius and IEEE zero-radius behavior.
        return !outside(a,b,0,ra,rb,1)&&(shape.equals("cylinder")||outside(a+1,b,0,ra,rb,1)||outside(a,b+1,0,ra,rb,1));
    }
    private static boolean outside(double a,double b,double c,double ra,double rb,double rc){return a*a/ra+b*b/rb+c*c/rc>1;}
}
