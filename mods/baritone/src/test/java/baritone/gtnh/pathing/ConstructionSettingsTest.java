// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConstructionSettingsTest {
    private static Map<String,Object> plan(){return new LinkedHashMap<>(Map.of("mode","builder","cells",List.of(Map.of("pos",List.of(0,40,0),"id","pack:block","meta",9))));}
    private static void invalid(Runnable action){try{action.run();fail("invalid plan accepted");}catch(IllegalArgumentException expected){}}
    @Test public void misspelledAndIllTypedControlsAreRejectedBeforeWork() {
        var plan=plan();plan.put("allowBrake",true);invalid(()->WorkSpec.cells(plan));plan.remove("allowBrake");plan.put("allowBreak","true");invalid(()->WorkSpec.cells(plan));
        invalid(()->new ConstructionSettings(Map.of("buildRepeatCount",0)));
        invalid(()->new ConstructionSettings(Map.of("layerHeight",0)));
        invalid(()->new ConstructionSettings(Map.of("metadataMasks",Map.of("pack:block",16))));
        invalid(()->new ConstructionSettings(Map.of("buildSubstitutes",Map.of("pack:block",List.of(Map.of("id","pack:other","placement",Map.of("face",6)))))));
        invalid(()->new ConstructionSettings(Map.of("buildSubstitutes",Map.of("pack:block",List.of(Map.of("id","pack:other","placement",Map.of("ywa",90)))))));
    }
    @Test public void metadataBitsAndRepeatAreNativeNotFlattenedStates() {
        var settings=new ConstructionSettings(Map.of("metadataMasks",Map.of("pack:block",3),"buildRepeat",List.of(-20,3,5),"buildRepeatCount",-1));
        assertEquals(3,settings.metadataMask("pack:block"));assertEquals(15,settings.metadataMask("pack:other"));assertEquals(new BlockPos(-20,3,5),settings.repeat());
        var plan=plan();plan.put("settings",settings.values);assertEquals(9,WorkSpec.cells(plan).get(0).meta());
    }
    @Test public void builderAdmitsLargeSelectionButBlueprintKeepsItsContract() {
        var spec=new LinkedHashMap<String,Object>();spec.put("mode","builder");spec.put("selection",Map.of("min",List.of(0,30,0),"max",List.of(127,31,127),"block",Map.of("id","pack:block","meta",7)));
        var cells=WorkSpec.cells(spec);assertEquals(32768,cells.size());assertEquals(7,cells.get(cells.size()-1).meta());
        spec.put("mode","blueprint");invalid(()->WorkSpec.cells(spec));
    }
    @Test public void verificationKeepsNativeVariantSeparateFromBlockAndPlacementItem() {
        var spec=plan();spec.put("cells",List.of(Map.of("pos",List.of(0,40,0),"id","pack:shared_block","meta",3,
            "item",Map.of("id","pack:placement_tool","meta",17),"verify",Map.of("pickedItem",Map.of("id","pack:shared_item","meta",314,"nbt","{variant:7}")))));
        var cell=WorkSpec.cells(spec).get(0);assertEquals(3,cell.meta());assertEquals(17,cell.item().get("meta"));assertEquals(314,WorkSpec.child(cell.verify(),"pickedItem").get("meta"));
        invalid(()->WorkSpec.verification(Map.of("tileType","guessed")));
        invalid(()->WorkSpec.verification(Map.of("pickedItem",Map.of("meta",314))));
    }
}
