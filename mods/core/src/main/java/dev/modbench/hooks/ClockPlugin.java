// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.hooks;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;
import java.util.Map;

@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.SortingIndex(1001)
@IFMLLoadingPlugin.TransformerExclusions({"dev.modbench.hooks.ClockPlugin", "dev.modbench.hooks.ClockTransformer", "dev.modbench.hooks.GuiInputTransformer", "dev.modbench.hooks.EventTransformer", "dev.modbench.hooks.ActionTransformer"})
public final class ClockPlugin implements IFMLLoadingPlugin {
    public String[] getASMTransformerClass() { return cpw.mods.fml.relauncher.FMLLaunchHandler.side().isClient()
        ? new String[]{"dev.modbench.hooks.ClockTransformer","dev.modbench.hooks.GuiInputTransformer","dev.modbench.hooks.EventTransformer","dev.modbench.hooks.ActionTransformer"}
        : new String[]{"dev.modbench.hooks.ClockTransformer"}; }
    public String getModContainerClass() { return null; }
    public String getSetupClass() { return null; }
    public void injectData(Map<String, Object> data) {}
    public String getAccessTransformerClass() { return null; }
}
