// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;

public final class LegacyEntities {
    private LegacyEntities(){}
    public static boolean isAngry(net.minecraft.entity.monster.EntityPigZombie pig){
        net.minecraft.nbt.NBTTagCompound state=new net.minecraft.nbt.NBTTagCompound();
        pig.writeEntityToNBT(state);return state.getShort("Anger")>0;
    }
}
