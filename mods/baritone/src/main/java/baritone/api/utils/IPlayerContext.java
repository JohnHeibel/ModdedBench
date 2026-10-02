// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
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
    baritone.api.cache.IWorldData worldData();
    RayTraceResult objectMouseOver();
    default BetterBlockPos playerFeet(){
        double minY=player().boundingBox.minY;
        // ModdedBench: the feet leave a cell whose measured top the player stands on; upstream's slab class until measured
        return NavigationCoordinates.feet(player().posX,minY,player().posZ,p->{
            IBlockState s=world().getBlockState(p);Boolean raised=NavigationCoordinates.raisedFloor(s,minY-p.getY());
            return raised!=null?raised:s.getBlock() instanceof net.minecraft.block.BlockSlab;
        });
    }
    default Vec3d playerFeetAsVec(){return new Vec3d(player().posX,player().boundingBox.minY,player().posZ);}
    default Vec3d playerHead(){return LegacyPlayer.eyes(player());}
    default Vec3d playerMotion(){return new Vec3d(player().motionX,player().motionY,player().motionZ);}
    default BetterBlockPos viewerPos(){return playerFeet();}
    default Rotation playerRotations(){return new Rotation(player().rotationYaw,player().rotationPitch);}
    static double eyeHeight(boolean sneak){return sneak?1.54:1.62;}
    default Optional<BlockPos> getSelectedBlock(){RayTraceResult r=objectMouseOver();return r!=null&&r.typeOfHit==RayTraceResult.Type.BLOCK?Optional.of(r.getBlockPos()):Optional.empty();}
    default boolean isLookingAt(BlockPos pos){return getSelectedBlock().equals(Optional.of(pos));}
    /**
     * ModdedBench: whether the look has come to `target` as nearly as it can. The look moves in mouse steps (0.15 degrees
     * at the default sensitivity) plus the randomLooking jitter, so upstream's 0.01-degree test (isReallyCloseTo) mostly
     * never passed, and whatever waited on it waited forever.
     */
    default boolean isAimSettled(Rotation target){
        float f=minecraft().gameSettings.mouseSensitivity*0.6f+0.2f;
        double tolerance=f*f*f*8.0f*0.15f+baritone.Baritone.settings().randomLooking.value+1e-3;
        Rotation look=playerRotations();
        double yaw=Math.abs(Rotation.normalizeYaw(look.getYaw())-Rotation.normalizeYaw(target.getYaw()));
        return Math.min(yaw,360-yaw)<=tolerance&&Math.abs(look.getPitch()-target.getPitch())<=tolerance;
    }
}
