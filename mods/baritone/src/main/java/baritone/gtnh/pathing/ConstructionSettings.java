// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import baritone.compat.BlockPos;
import java.util.*;
import static baritone.gtnh.pathing.WorkSpec.*;

/** Frozen per-build settings; equivalent to BuilderProcess's construction settings. */
public final class ConstructionSettings {
    public final Map<String,Object> values;
    private static final Set<String> FLAGS=Set.of("buildInLayers","layerOrder","skipFailedLayers","buildRepeatSneaky","mapArtMode","buildIgnoreExisting","okIfWater","schematicOrientationX","schematicOrientationY","schematicOrientationZ","breakFromAbove","goalBreakFromAbove","distanceTrim","allowInventory","restricted","repairPlaced");
    public ConstructionSettings(Map<String,Object> input) {
        Set<String> allowed=new HashSet<>(FLAGS);allowed.addAll(Set.of("layerHeight","startAtLayer","buildRepeatCount","incorrectSize","builderTickScanRadius","buildRepeat","breakCorrectBlockPenaltyMultiplier","buildIgnoreBlocks","buildSkipBlocks","okIfAir","buildValidSubstitutes","buildSubstitutes","metadataMasks","acceptableThrowawayItems"));
        fields(input,allowed);
        for(String key:FLAGS)WorkSpec.bool(input,key,false);
        WorkSpec.integer(input,"layerHeight",1,1,256);WorkSpec.integer(input,"startAtLayer",0,0,255);
        int count=WorkSpec.integer(input,"buildRepeatCount",1,-1,100000);if(count==0)throw new IllegalArgumentException("buildRepeatCount must be -1 or positive");
        WorkSpec.integer(input,"incorrectSize",100,1,16384);WorkSpec.integer(input,"builderTickScanRadius",5,1,32);
        WorkSpec.number(input,"breakCorrectBlockPenaltyMultiplier",10,1,1e6);
        if(input.containsKey("buildRepeat"))pos(input.get("buildRepeat"));
        for(String key:List.of("buildIgnoreBlocks","buildSkipBlocks","okIfAir"))if(input.containsKey(key))idList(input.get(key));
        if(input.containsKey("buildValidSubstitutes"))for(var e:object(input.get("buildValidSubstitutes")).entrySet()){id(e.getKey());idList(e.getValue());}
        if(input.containsKey("buildSubstitutes"))for(var e:object(input.get("buildSubstitutes")).entrySet()) {
            id(e.getKey());if(list(e.getValue()).isEmpty())throw new IllegalArgumentException("empty build substitute list");
            for(Object value:list(e.getValue()))if(value instanceof String s)id(s);else {
                var row=object(value);fields(row,Set.of("id","meta","item","placement","verify"));id(string(row,"id",""));WorkSpec.integer(row,"meta",0,0,15);var item=child(row,"item");fields(item,Set.of("id","meta","nbt","ore"));if(!item.isEmpty()&&!item.containsKey("id")&&!item.containsKey("ore"))throw new IllegalArgumentException("item selector needs id or ore");WorkSpec.integer(item,"meta",0,0,32767);WorkSpec.placement(child(row,"placement"));WorkSpec.verification(child(row,"verify"));
            }
        }
        if(input.containsKey("metadataMasks"))for(var e:object(input.get("metadataMasks")).entrySet()){id(e.getKey());WorkSpec.integer(Map.of("mask",e.getValue()),"mask",15,0,15);}
        if(input.containsKey("acceptableThrowawayItems"))for(Object selector:list(input.get("acceptableThrowawayItems")))object(selector);
        values=Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }
    static String id(String value){if(!value.matches("[^\\s:]+:[^\\s:]+"))throw new IllegalArgumentException("namespaced block ID required: "+value);return value;}
    static List<String> idList(Object value){List<String> result=new ArrayList<>();for(Object row:list(value)){if(!(row instanceof String s))throw new IllegalArgumentException("block ID string required");result.add(id(s));}return List.copyOf(result);}
    public boolean bool(String key,boolean fallback){return WorkSpec.bool(values,key,fallback);}
    public int integer(String key,int fallback){return ((Number)values.getOrDefault(key,fallback)).intValue();}
    public double number(String key,double fallback){return ((Number)values.getOrDefault(key,fallback)).doubleValue();}
    public List<String> ids(String key){return values.containsKey(key)?idList(values.get(key)):List.of();}
    public Map<String,Object> mappings(String key){return child(values,key);}
    public int metadataMask(String id){return ((Number)mappings("metadataMasks").getOrDefault(id,15)).intValue();}
    public BlockPos repeat(){return values.containsKey("buildRepeat")?pos(values.get("buildRepeat")):new BlockPos(0,0,0);}
}
