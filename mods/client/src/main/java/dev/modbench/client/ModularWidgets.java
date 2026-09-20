// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.client;

import com.google.gson.*;
import dev.modbench.bridge.Json;
import java.awt.Rectangle;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.client.gui.GuiScreen;

/** Optional MUI1 native tree adapter. No machine-specific fields or executable setters. */
final class ModularWidgets {
    static JsonObject observe(GuiScreen screen) {
        boolean supported=false;
        for(Class<?> type=screen.getClass();type!=null;type=type.getSuperclass())
            if(type.getName().equals("com.gtnewhorizons.modularui.common.internal.wrapper.ModularGui")) supported=true;
        if(!supported) return null;
        JsonArray widgets=new JsonArray();JsonObject out=Json.object("adapter","modularui1","coordinates","scaled screen");out.add("widgets",widgets);
        try {
            Object context=invoke(screen,"getContext");Object windows=invoke(context,"getOpenWindows");int index=0;
            Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());
            for(Object window:(Iterable<?>)windows) {if(index>=16) {out.addProperty("truncated",true);break;}walk(window,"window["+index+++"]",widgets,seen,0);}
            out.addProperty("truncated",out.has("truncated")||widgets.size()>=512);
        } catch(Exception failure) {out.addProperty("error",failure.toString());}
        return out;
    }
    private static void walk(Object widget,String path,JsonArray entries,Set<Object> seen,int depth) throws Exception {
        if(widget==null||depth>16||entries.size()>=512||!seen.add(widget)) return;
        JsonObject entry=Json.object("path",path,"class",widget.getClass().getName());entries.add(entry);
        try {
            Object rect=optional(widget,"getRenderAbsoluteRectangle");if(rect==null) rect=optional(widget,"getRectangle");
            if(rect instanceof Rectangle r) {
                entry.add("bounds",Json.object("x",r.x,"y",r.y,"w",r.width,"h",r.height));
                if(r.width>0&&r.height>0) entry.add("clickAt",Json.object("x",r.x+r.width/2,"y",r.y+r.height/2));
            }
            for(String method:List.of("isEnabled","isFocused","isHovering","getName","getInternalName","getWindowLayer","getLayer","getNEITransferRectID")) {
                Object value=optional(widget,method);if(value instanceof Boolean||value instanceof Number||value instanceof String) entry.add(method,Json.GSON.toJsonTree(value));
            }
            Object text=optional(widget,"getText");if(text!=null) entry.addProperty("text",text(text));
            Object tip=optional(widget,"getTooltip");if(tip instanceof Iterable<?> values) {
                JsonArray lines=new JsonArray();for(Object value:values) {if(lines.size()>=32) break;lines.add(new JsonPrimitive(text(value)));}entry.add("tooltip",lines);
            }
        } catch(Exception failure) {entry.addProperty("observationError",failure.toString());}
        Object children=optional(widget,"getChildren");if(children instanceof Iterable<?> values) {
            int index=0;for(Object child:values) {if(entries.size()>=512) break;walk(child,path+"/"+index++,entries,seen,depth+1);}
        }
    }
    private static String text(Object value) throws Exception {
        Object formatted=value instanceof String?value:optional(value,"getFormatted");
        String text=UiWidgets.strip(String.valueOf(formatted==null?value:formatted));return text.length()>2048?text.substring(0,2048)+"…":text;
    }
    private static Object optional(Object object,String name) throws Exception {
        try{return invoke(object,name);}catch(NoSuchMethodException missing) {return null;}
    }
    private static Object invoke(Object object,String name) throws Exception {
        Method method=object.getClass().getMethod(name);method.setAccessible(true);return method.invoke(object);
    }
}
