// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.api;

import baritone.Baritone;
public final class BaritoneAPI {
    private BaritoneAPI(){}
    public static Settings getSettings(){return Baritone.settings();}
    private static final Provider PROVIDER=new Provider();
    public static Provider getProvider(){return PROVIDER;}
    /** The one lookup left for pinned upstream code; the mod container owns the engine. */
    public static final class Provider {
        private Baritone primary;
        public void attach(Baritone engine){
            if(primary!=null)throw new IllegalStateException("one Baritone engine per client");primary=engine;
        }
        public baritone.api.cache.IWorldScanner getWorldScanner(){return baritone.cache.WorldScanner.INSTANCE;}
        public java.util.List<Baritone> getAllBaritones(){return primary!=null?java.util.List.of(primary):java.util.List.of();}
        public Baritone getPrimaryBaritone(){if(primary==null)throw new IllegalStateException("Baritone has not been initialized");return primary;}
        public Baritone getBaritoneForPlayer(net.minecraft.client.entity.EntityPlayerSP player){
            Baritone b=getPrimaryBaritone();return player!=null&&b.getPlayerContext().player()==player?b:null;
        }
    }
}
