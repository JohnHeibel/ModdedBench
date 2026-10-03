// SPDX-License-Identifier: LGPL-3.0-or-later
package baritone.gtnh;

import baritone.ForgePlanningTestRunner;
import baritone.compat.BlockPos;
import baritone.compat.LegacyPlayerController;
import org.junit.*;
import java.util.List;
import static org.junit.Assert.*;

@org.junit.runner.RunWith(ForgePlanningTestRunner.class)
public class ConstructionInteractionTest {
    @BeforeClass public static void bootstrap(){
        cpw.mods.fml.common.Loader.injectData("7","99","40","1614","1.7.10","9.05",new java.io.File("."),List.of());
        net.minecraft.init.Bootstrap.func_151354_b();
    }
    @Test public void markedDoorClicksDoNotBecomeSchematicPlacements(){
        List<BlockPos> door=List.of(new BlockPos(3,64,5),new BlockPos(3,65,5));
        assertTrue(LegacyPlayerController.interacting(door,true,new BlockPos(3,65,5),false));
        // Another tick's mark, another block under the crosshair, or a sneaking click are placements.
        assertFalse(LegacyPlayerController.interacting(door,false,new BlockPos(3,64,5),false));
        assertFalse(LegacyPlayerController.interacting(door,true,new BlockPos(4,64,5),false));
        assertFalse(LegacyPlayerController.interacting(door,true,new BlockPos(3,64,5),true));
        assertFalse(LegacyPlayerController.interacting(List.of(),true,new BlockPos(3,64,5),false));
    }
}
