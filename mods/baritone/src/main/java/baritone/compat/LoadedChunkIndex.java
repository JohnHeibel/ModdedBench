// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;

import cpw.mods.fml.common.ObfuscationReflectionHelper;
import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import java.util.*;

/** Vanilla and GTNH/Hodgepodge loaded-chunk stores; captured only on the client thread. */
public final class LoadedChunkIndex {
    private LoadedChunkIndex(){}
    public static Map<Long,Chunk> capture(ChunkProviderClient provider){
        List<?> list=ObfuscationReflectionHelper.getPrivateValue(ChunkProviderClient.class,provider,"chunkListing","field_73237_c");
        Iterator<?> values;
        if(list!=null)values=list.iterator();
        else {
            Object mapping=ObfuscationReflectionHelper.getPrivateValue(ChunkProviderClient.class,provider,"chunkMapping","field_73236_b");
            // Hodgepodge deliberately removes chunkListing and exposes iteration through this public method.
            if(!mapping.getClass().getName().equals("com.mitchej123.hodgepodge.util.FastUtilLongHashMap"))
                throw new IllegalStateException("unsupported loaded-chunk store: "+mapping.getClass().getName());
            try{values=(Iterator<?>)mapping.getClass().getMethod("valuesIterator").invoke(mapping);}
            catch(ReflectiveOperationException e){throw new IllegalStateException("cannot read GTNH loaded-chunk index",e);}
        }
        Map<Long,Chunk> result=new HashMap<>();
        while(values.hasNext()){
            Object value=values.next();if(!(value instanceof Chunk chunk))throw new IllegalStateException("non-chunk in native chunk index");
            if(!(chunk instanceof EmptyChunk)&&chunk.isChunkLoaded)result.put((long)chunk.xPosition&0xffffffffL|(long)chunk.zPosition<<32,chunk);
        }
        return Collections.unmodifiableMap(result);
    }
}
