// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.clock;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;
import java.util.Map;

@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.SortingIndex(1001)
@IFMLLoadingPlugin.TransformerExclusions({"dev.modbench.clock.ClockPlugin", "dev.modbench.clock.ClockTransformer", "dev.modbench.clock.GuiInputTransformer"})
public final class ClockPlugin implements IFMLLoadingPlugin {
    public String[] getASMTransformerClass() { return cpw.mods.fml.relauncher.FMLLaunchHandler.side().isClient()
        ? new String[]{"dev.modbench.clock.ClockTransformer","dev.modbench.clock.GuiInputTransformer"}
        : new String[]{"dev.modbench.clock.ClockTransformer"}; }
    public String getModContainerClass() { return null; }
    public String getSetupClass() { return null; }
    public void injectData(Map<String, Object> data) {}
    public String getAccessTransformerClass() { return null; }
}
