// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;

public final class LegacyPlayer {
    private LegacyPlayer(){}
    /** Client-thread query scope only: no input event, physics or packet is sent. */
    public static <T> T withSneakingPose(Entity entity,java.util.function.Supplier<T> query){
        if(!(entity instanceof net.minecraft.client.entity.EntityPlayerSP player)||player.movementInput==null)return query.get();
        boolean previous=player.movementInput.sneak;
        player.movementInput.sneak=true;
        try{return query.get();}finally{player.movementInput.sneak=previous;}
    }
    /** EntityPlayerSP.posY is eye-relative in 1.7.10. Use the native ray origin. */
    public static Vec3d eyes(Entity entity){return entity instanceof EntityPlayer p?Vec3d.fromNative(p.getPosition(1.0F)):new Vec3d(entity.posX,entity.boundingBox.minY+entity.getEyeHeight(),entity.posZ);}
    public static Vec3d sneakingEyes(Entity entity){
        Vec3d eye=eyes(entity);
        if(entity.isSneaking())return eye;
        if(entity instanceof net.minecraft.client.entity.EntityPlayerSP){
            // Unlike 1.12, the native eye position already includes ySize's
            // residual camera offset after releasing sneak. Project the next
            // native sneak step (EntityPlayerSP.onLivingUpdate + moveEntity)
            // instead of subtracting another fixed eye-height difference.
            float nextSize=Math.max(entity.ySize,0.2F)*0.4F;
            return eye.add(0,(double)entity.ySize-nextSize,0);
        }
        return eye.add(0,-0.08,0);
    }
}
