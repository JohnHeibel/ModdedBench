// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.api.utils;

import baritone.compat.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import java.util.Optional;

public interface IPlayerContext {
    Minecraft minecraft();
    EntityPlayerSP player();
    LegacyPlayerController playerController();
    World world();
    default baritone.api.cache.IWorldData worldData(){return baritone.Baritone.instance().getWorldProvider().getCurrentWorld();}
    RayTraceResult objectMouseOver();
    default BetterBlockPos playerFeet(){
        return NavigationCoordinates.feet(player().posX,player().boundingBox.minY,player().posZ,
            p->world().getBlockState(p).getBlock() instanceof net.minecraft.block.BlockSlab);
    }
    default Vec3d playerFeetAsVec(){return new Vec3d(player().posX,player().boundingBox.minY,player().posZ);}
    default Vec3d playerHead(){return LegacyPlayer.eyes(player());}
    default Vec3d playerMotion(){return new Vec3d(player().motionX,player().motionY,player().motionZ);}
    default BetterBlockPos viewerPos(){return playerFeet();}
    default Rotation playerRotations(){return new Rotation(player().rotationYaw,player().rotationPitch);}
    static double eyeHeight(boolean sneak){return sneak?1.54:1.62;}
    default Optional<BlockPos> getSelectedBlock(){RayTraceResult r=objectMouseOver();return r!=null&&r.typeOfHit==RayTraceResult.Type.BLOCK?Optional.of(r.getBlockPos()):Optional.empty();}
    default boolean isLookingAt(BlockPos pos){return getSelectedBlock().equals(Optional.of(pos));}
}
