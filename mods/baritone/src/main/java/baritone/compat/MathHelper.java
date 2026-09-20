// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;

public final class MathHelper {
    private MathHelper(){}
    public static int floor(double n){return (int)Math.floor(n);}
    public static double clamp(double n,double min,double max){return Math.max(min,Math.min(max,n));}
    public static float clamp(float n,float min,float max){return Math.max(min,Math.min(max,n));}
    public static float wrapDegrees(float n){return net.minecraft.util.MathHelper.wrapAngleTo180_float(n);}
    public static double wrapDegrees(double n){return net.minecraft.util.MathHelper.wrapAngleTo180_double(n);}
    public static float sin(float n){return net.minecraft.util.MathHelper.sin(n);}
    public static float cos(float n){return net.minecraft.util.MathHelper.cos(n);}
    public static float sqrt(double n){return (float)Math.sqrt(n);}
    public static double atan2(double y,double x){return Math.atan2(y,x);}
}
