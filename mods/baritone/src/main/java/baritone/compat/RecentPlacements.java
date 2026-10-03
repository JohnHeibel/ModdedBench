// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.compat;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;

/** Cells this client placed into, by player tick: a click against one waits at least a tick, so the server has the block first. Client thread only. */
public final class RecentPlacements {
    private RecentPlacements(){}
    private static final Map<BlockPos,Integer> AT=new HashMap<>();
    public static void mark(BlockPos p){var mc=Minecraft.getMinecraft();if(mc.thePlayer==null)return;if(AT.size()>256)AT.clear();AT.put(new BlockPos(p.getX(),p.getY(),p.getZ()),mc.thePlayer.ticksExisted);}
    public static boolean recent(BlockPos p){var mc=Minecraft.getMinecraft();Integer at=AT.get(new BlockPos(p.getX(),p.getY(),p.getZ()));return at!=null&&mc.thePlayer!=null&&mc.thePlayer.ticksExisted-at<1&&mc.thePlayer.ticksExisted>=at;}
}
