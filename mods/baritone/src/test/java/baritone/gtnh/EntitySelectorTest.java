// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh;

import baritone.ForgePlanningTestRunner;
import java.util.*;
import net.minecraft.entity.Entity;
import org.junit.*;
import static org.junit.Assert.*;

@org.junit.runner.RunWith(ForgePlanningTestRunner.class)
public class EntitySelectorTest {
    /** An entity of the class with no world behind it: the selector reads only what the test asks it to. */
    private static Entity bare(Class<? extends Entity> type)throws Exception{
        Class<?> unsafeClass=Class.forName("sun.misc.Unsafe",true,ClassLoader.getSystemClassLoader());
        var field=unsafeClass.getDeclaredField("theUnsafe");field.setAccessible(true);
        return (Entity)unsafeClass.getMethod("allocateInstance",Class.class).invoke(field.get(null),type);
    }
    @Test public void aClassRuleMatchesByAncestryForEveryEntityOfEachClass()throws Exception{
        var mob=ReferenceFollowJob.selector(Map.of("class","net.minecraft.entity.monster.IMob"));
        var zombie=bare(net.minecraft.entity.monster.EntityZombie.class);var cow=bare(net.minecraft.entity.passive.EntityCow.class);
        // Asked twice each, and of a second entity of the class: the remembered answer is per class, not one for all.
        for(int i=0;i<2;i++){assertTrue(mob.test(zombie));assertFalse(mob.test(cow));}
        assertTrue(mob.test(bare(net.minecraft.entity.monster.EntityZombie.class)));
        assertTrue(mob.test(bare(net.minecraft.entity.monster.EntityCreeper.class)));
        // A superclass by name, and a name nothing has.
        assertTrue(ReferenceFollowJob.selector(Map.of("class","net.minecraft.entity.EntityLivingBase")).test(cow));
        assertFalse(ReferenceFollowJob.selector(Map.of("class","no.such.Kind")).test(cow));
    }
}
