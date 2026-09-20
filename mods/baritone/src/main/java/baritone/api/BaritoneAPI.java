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
    public static final class Provider {
        public baritone.api.cache.IWorldScanner getWorldScanner(){return baritone.cache.WorldScanner.INSTANCE;}
        public java.util.List<Baritone> getAllBaritones(){return Baritone.initialized()?java.util.List.of(Baritone.instance()):java.util.List.of();}
        public Baritone getPrimaryBaritone(){return Baritone.instance();}
        public Baritone getBaritoneForPlayer(net.minecraft.client.entity.EntityPlayerSP player){
            Baritone b=Baritone.instance();return player!=null&&b.getPlayerContext().player()==player?b:null;
        }
    }
}
