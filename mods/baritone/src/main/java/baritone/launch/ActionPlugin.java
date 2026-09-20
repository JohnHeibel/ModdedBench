// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.launch;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;
import java.util.Map;

@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.SortingIndex(1002)
@IFMLLoadingPlugin.TransformerExclusions({"baritone.launch."})
public final class ActionPlugin implements IFMLLoadingPlugin {
    public String[] getASMTransformerClass() {return cpw.mods.fml.relauncher.FMLLaunchHandler.side().isClient()
        ?new String[]{"baritone.launch.ActionTransformer","baritone.launch.EventTransformer"}:new String[0];}
    public String getModContainerClass(){return null;}
    public String getSetupClass(){return null;}
    public void injectData(Map<String,Object> data){}
    public String getAccessTransformerClass(){return null;}
}
