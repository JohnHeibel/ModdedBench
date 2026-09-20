// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.utils.SettingsUtil;
import java.util.*;

/** Structured access to the pinned parser. Changes are atomic and idle-only. */
final class ReferenceSettings {
    private ReferenceSettings(){}
    static Map<String,Object> call(Baritone engine,Map<String,Object> params){
        String operation=String.valueOf(params.getOrDefault("operation","get"));
        Settings actual=Baritone.settings();
        if(operation.equals("set")||operation.equals("reset")){
            if(engine.getInputOverrideHandler().hasActiveLease())throw new IllegalArgumentException("stop the current source process before changing settings");
            Map<?,?> values=params.get("values") instanceof Map<?,?> map?map:Map.of();
            Settings candidate=new Settings();
            for(var setting:actual.allSettings)copy(candidate.byLowerName.get(setting.getName().toLowerCase(Locale.ROOT)),setting.value);
            for(var entry:values.entrySet()){
                String key=entry.getKey().toString().toLowerCase(Locale.ROOT);var setting=candidate.byLowerName.get(key);
                if(setting==null||setting.isJavaOnly())throw new IllegalArgumentException("unknown or Java-only setting: "+key);
                if(operation.equals("reset"))setting.reset();else {
                    String encoded=text(entry.getValue());
                    if(setting.value instanceof Boolean&&!encoded.equalsIgnoreCase("true")&&!encoded.equalsIgnoreCase("false"))throw new IllegalArgumentException("boolean must be true or false: "+key);
                    SettingsUtil.parseAndApply(candidate,key,encoded);
                }
                Object value=setting.value;
                if(value instanceof Number n&&!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("finite setting required: "+key);
                if(key.startsWith("elytra")&&!Objects.equals(value,actual.byLowerName.get(key).value))throw new IllegalArgumentException("native runtime support is not implemented for "+key);
            }
            if(Boolean.TRUE.equals(params.get("save")))SettingsUtil.save(candidate);
            for(var setting:candidate.allSettings)copy(actual.byLowerName.get(setting.getName().toLowerCase(Locale.ROOT)),setting.value);
        }else if(!operation.equals("get"))throw new IllegalArgumentException("operation must be get, set or reset");
        String query=String.valueOf(params.getOrDefault("query","")).toLowerCase(Locale.ROOT);
        List<Map<String,Object>> result=new ArrayList<>();
        for(var setting:actual.allSettings)if(!setting.isJavaOnly()&&setting.getName().toLowerCase(Locale.ROOT).contains(query)){
            Map<String,Object> row=new LinkedHashMap<>();row.put("name",setting.getName());row.put("type",SettingsUtil.settingTypeToString(setting));
            row.put("value",SettingsUtil.settingValueToString(setting));row.put("default",SettingsUtil.settingDefaultToString(setting));result.add(row);
        }
        return Map.of("settings",result,"engine","baritone-1.2.19-source-port","scope","client runtime; declarations alone do not imply that every optional process or renderer is implemented");
    }
    @SuppressWarnings({"rawtypes","unchecked"}) static void copy(Settings.Setting setting,Object value){setting.value=value;}
    static String text(Object value){
        if(value instanceof Map<?,?> map)return map.entrySet().stream().map(e->text(e.getKey())+"->"+text(e.getValue())).collect(java.util.stream.Collectors.joining(","));
        if(value instanceof List<?> list)return list.stream().map(ReferenceSettings::text).collect(java.util.stream.Collectors.joining(","));
        if(value instanceof Number number&&number.doubleValue()==number.longValue())return Long.toString(number.longValue());
        return String.valueOf(value);
    }
}
