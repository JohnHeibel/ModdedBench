// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;

import net.minecraft.block.BlockDoor;
import java.util.function.Function;

/** Only metadata encodings whose owning native block classes are known. */
public final class LegacyProperties {
    private LegacyProperties(){}
    public record Property<T>(Function<IBlockState,T> read) {}
    public record PropertyBool(Function<IBlockState,Boolean> read) {}
    public enum Half { BOTTOM, TOP }
    public enum PlantType { OTHER, GRASS, FERN }
    public static final Property<Integer> LEVEL=new Property<>(s->s.meta);
    public static final Property<Integer> LAYERS=new Property<>(s->(s.meta&7)+1);
    public static final Property<Half> SLAB_HALF=new Property<>(s->(s.meta&8)==0?Half.BOTTOM:Half.TOP);
    public static final Property<PlantType> PLANT_VARIANT=new Property<>(s->switch(s.meta&7){case 2->PlantType.GRASS;case 3->PlantType.FERN;default->PlantType.OTHER;});
    public static final Property<EnumFacing> LADDER_FACING=new Property<>(s->switch(s.meta){case 2->EnumFacing.NORTH;case 3->EnumFacing.SOUTH;case 4->EnumFacing.WEST;case 5->EnumFacing.EAST;default->throw new IllegalArgumentException("unsupported ladder metadata: "+s.meta);});
    public static final Property<EnumFacing> HORIZONTAL_FACING=new Property<>(s->{
        int m=s.effectiveDoorMeta();
        if(s.getBlock() instanceof BlockDoor)return switch(m&3){case 0->EnumFacing.EAST;case 1->EnumFacing.SOUTH;case 2->EnumFacing.WEST;default->EnumFacing.NORTH;};
        return switch(m&3){case 0->EnumFacing.SOUTH;case 1->EnumFacing.WEST;case 2->EnumFacing.NORTH;default->EnumFacing.EAST;};
    });
    public static final PropertyBool OPEN=new PropertyBool(s->(s.effectiveDoorMeta()&4)!=0);
    public static final PropertyBool SOUTH=new PropertyBool(s->(s.meta&1)!=0),WEST=new PropertyBool(s->(s.meta&2)!=0),NORTH=new PropertyBool(s->(s.meta&4)!=0),EAST=new PropertyBool(s->(s.meta&8)!=0);
}
