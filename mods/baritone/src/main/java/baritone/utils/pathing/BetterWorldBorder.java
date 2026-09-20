// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.utils.pathing;

/** The native 1.7 coordinate boundary; later-version moving world borders do not exist. */
public final class BetterWorldBorder {
    public BetterWorldBorder(){}
    public BetterWorldBorder(BetterWorldBorder other){}
    public boolean canPlaceAt(int x,int z){return x>=-29999984&&x<29999984&&z>=-29999984&&z<29999984;}
    public boolean entirelyContains(int x,int z){return canPlaceAt(x,z);}
}
