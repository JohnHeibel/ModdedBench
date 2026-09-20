// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;
import baritone.api.utils.BlockUtils;
/** Registry IDs plus metadata, never session-local numeric block IDs. */
public final class CacheStateCodec {
    private CacheStateCodec(){}
    public static String encode(IBlockState state){return BlockUtils.blockToString(state.getBlock())+"@"+state.meta;}
    public static IBlockState decode(String text){
        int separator=text.lastIndexOf('@');
        if(separator<=0)throw new IllegalArgumentException("cache state requires registry identity and metadata");
        int meta=Integer.parseInt(text.substring(separator+1));
        if(meta<0||meta>15)throw new IllegalArgumentException("invalid cached metadata");
        return IBlockState.of(BlockUtils.stringToBlockRequired(text.substring(0,separator)),meta);
    }
}
