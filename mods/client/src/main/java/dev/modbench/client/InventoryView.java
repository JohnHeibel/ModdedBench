// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.client;

import com.google.gson.*;
import dev.modbench.bridge.Json;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.*;
import net.minecraft.item.ItemStack;

/** DJ2 slot ownership/geometry semantics, adapted to 1.7.10 and flat GTNH observations. */
final class InventoryView {
    private final Minecraft mc=Minecraft.getMinecraft();
    private Object player,container,screen;
    private long epoch;
    long epoch() {
        Object current=mc.thePlayer==null?null:mc.thePlayer.openContainer;
        if(player!=mc.thePlayer||container!=current||screen!=mc.currentScreen) {player=mc.thePlayer;container=current;screen=mc.currentScreen;epoch++;}
        return epoch;
    }
    Container require(JsonObject params) {
        if(mc.thePlayer==null) throw new IllegalArgumentException("player required");
        if(params.has("epoch")) {
            long expected;
            try {
                JsonElement value=params.get("epoch");
                if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
                expected=value.getAsBigDecimal().longValueExact();
            } catch(ArithmeticException invalid) {throw new IllegalArgumentException("epoch must be an integer");}
            if(expected!=epoch()) throw new IllegalArgumentException("stale_window: screen/container epoch changed");
        }
        Container c=mc.thePlayer.openContainer;
        if(params.has("windowId")&&Json.integer(params,"windowId",-1,0,255)!=c.windowId) throw new IllegalArgumentException("stale_window: windowId changed");
        return c;
    }
    static Slot slot(Container c,int index) {
        if(index<0||index>=c.inventorySlots.size()) throw new IllegalArgumentException("slot index outside observed container");
        return (Slot)c.inventorySlots.get(index);
    }
    static boolean ordinary(Slot slot,int index) {
        String name=slot.getClass().getName().toLowerCase(Locale.ROOT);
        return slot.slotNumber==index&&slot.func_111238_b()&&!name.contains("phantom")&&!name.contains("ghost")&&!name.contains("fake")&&!name.contains("slotme");
    }
    JsonObject container(String detail) {return container(detail,-1);}
    JsonObject container(String detail,int probeSlot) {
        Container c=require(new JsonObject());GuiScreen gui=mc.currentScreen;
        boolean nativeSlots=gui instanceof GuiContainer gc&&gc.inventorySlots==c;
        ItemStack probe=probeSlot<0?null:slot(c,probeSlot).getStack();
        if(probeSlot>=0&&probe==null) throw new IllegalArgumentException("probeSlot is empty");
        JsonObject out=Json.object("windowId",c.windowId,"epoch",epoch(),"class",c.getClass().getName(),"screen",gui==null?null:gui.getClass().getName(),
            "open",gui!=null,"cursor",Stacks.json(mc.thePlayer.inventory.getItemStack()),"serverAcknowledged",false,"nativeSlotGeometry",nativeSlots,
            "visibleContainerClass",gui instanceof GuiContainer gc?gc.inventorySlots.getClass().getName():null);
        int left=0,top=0;
        if(gui instanceof GuiContainer gc) {
            left=UiWidgets.guiInt(gc,"guiLeft","field_147003_i");top=UiWidgets.guiInt(gc,"guiTop","field_147009_r");
            out.add("gui",Json.object("left",left,"top",top,"w",UiWidgets.guiInt(gc,"xSize","field_146999_f"),"h",UiWidgets.guiInt(gc,"ySize","field_147000_g")));
        }
        JsonArray slots=new JsonArray();IdentityHashMap<IInventory,Integer> inventories=new IdentityHashMap<>();int omitted=0;
        for(int i=0;i<c.inventorySlots.size();i++) {
            Slot s=slot(c,i);int index=s.getSlotIndex();boolean playerSlot=s.inventory instanceof InventoryPlayer;
            if(detail.equals("compact")&&playerSlot&&s.getStack()==null) {omitted++;continue;}
            JsonObject entry=Json.object("i",i,"n",s.slotNumber,"idx",index,"kind",playerSlot?(index<9?"hotbar":index<36?"main":"armor"):"container",
                "inventory",inventories.computeIfAbsent(s.inventory,k->inventories.size()),"slotClass",s.getClass().getName(),
                "virtual",s.slotNumber!=i,"ordinary",ordinary(s,i),"enabled",s.func_111238_b(),"canTake",s.canTakeStack(mc.thePlayer),
                "limit",s.getSlotStackLimit(),"stack",Stacks.json(s.getStack()));
            if(!detail.equals("compact")) {
                entry.addProperty("x",s.xDisplayPosition);entry.addProperty("y",s.yDisplayPosition);
                if(nativeSlots) entry.add("clickAt",Json.object("x",left+s.xDisplayPosition+8,"y",top+s.yDisplayPosition+8));
            }
            if(probe!=null) {
                boolean accepts=s.isItemValid(probe);ItemStack old=s.getStack();
                entry.addProperty("acceptsProbe",accepts);
                entry.addProperty("spaceForProbe",!accepts||old!=null&&!Stacks.same(old,probe)?0:Math.max(0,Math.min(probe.getMaxStackSize(),s.getSlotStackLimit())-(old==null?0:old.stackSize)));
            }
            ItemStack held=mc.thePlayer.inventory.getItemStack();
            if(held!=null) entry.addProperty("acceptsCursor",s.isItemValid(held));
            if(s.getClass().getName().startsWith("appeng.client.me.")) try {
                Object ae=s.getClass().getMethod("getAEStack").invoke(s);
                if(ae!=null) {
                    JsonObject me=new JsonObject();
                    for(String[] property:new String[][]{{"stored","getStackSize"},{"craftable","isCraftable"},{"requestable","getCountRequestable"}})
                        me.add(property[0],Json.GSON.toJsonTree(ae.getClass().getMethod(property[1]).invoke(ae)));
                    entry.add("me",me);
                }
            } catch(ReflectiveOperationException failure) {entry.addProperty("meObservationError",failure.toString());}
            slots.add(entry);
        }
        out.add("slots",slots);out.addProperty("slotCount",c.inventorySlots.size());out.addProperty("omittedEmptyPlayerSlots",omitted);
        if(probe!=null) out.add("probe",Json.object("slot",probeSlot,"stack",Stacks.json(probe)));
        if(gui!=null&&!detail.equals("compact")) {
            out.add("size",Json.object("w",gui.width,"h",gui.height));out.add("buttons",UiWidgets.buttons(gui));out.add("labels",UiWidgets.labels(gui));
            out.add("fields",UiWidgets.fields(gui,detail.equals("full")));out.addProperty("widgetGeometry","heuristic for custom widgets; use native hover/hit_test to verify");
            JsonObject modular=ModularWidgets.observe(gui);if(modular!=null) out.add("modularUi",modular);
        }
        return out;
    }
    JsonObject inventory(String detail) {
        require(new JsonObject());JsonArray main=new JsonArray(),armor=new JsonArray();Map<String,JsonObject> totals=new LinkedHashMap<>();int empty=0;
        for(int i=0;i<36;i++) {
            ItemStack stack=mc.thePlayer.inventory.mainInventory[i];JsonObject value=Stacks.json(stack);
            if(stack==null) empty++;else {
                String key=value.get("id").getAsString()+"@"+stack.getItemDamage()+"#"+(value.has("nbt_hash")?value.get("nbt_hash").getAsString():"");
                JsonObject total=totals.computeIfAbsent(key,k->Json.object("identity",value,"count",0,"stacks",0));
                total.addProperty("count",total.get("count").getAsInt()+stack.stackSize);total.addProperty("stacks",total.get("stacks").getAsInt()+1);
            }
            if(!detail.equals("counts")&&(!detail.equals("compact")||stack!=null)) main.add(Json.object("slot",i,"kind",i<9?"hotbar":"main","stack",value));
        }
        for(int i=0;i<4;i++) armor.add(Json.object("slot",i,"stack",Stacks.json(mc.thePlayer.inventory.armorInventory[i])));
        JsonObject out=Json.object("selected",mc.thePlayer.inventory.currentItem,"held",Stacks.json(mc.thePlayer.getHeldItem()),"cursor",Stacks.json(mc.thePlayer.inventory.getItemStack()),"emptySlots",empty);
        if(detail.equals("counts")) out.add("totals",Json.GSON.toJsonTree(totals.values()));else {out.add("main",main);out.add("armor",armor);}
        return out;
    }
}
