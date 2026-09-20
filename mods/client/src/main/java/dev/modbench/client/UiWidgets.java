/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.modbench.client;

import com.google.gson.*;
import cpw.mods.fml.common.ObfuscationReflectionHelper;
import dev.modbench.bridge.Json;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import java.lang.reflect.*;
import java.util.*;

/** DJ2 widget introspection port; geometry from unknown widgets is explicitly heuristic. */
final class UiWidgets {
    @SuppressWarnings("unchecked")
    static List<GuiButton> buttonListOf(GuiScreen screen) {
        List<GuiButton> out=new ArrayList<>();
        try {
            List<GuiButton> list = ObfuscationReflectionHelper.getPrivateValue(GuiScreen.class, screen, "buttonList", "field_146292_n");
            if(list!=null) out.addAll(list);
        } catch (Throwable unavailable) {}
        // NEI keeps its native recipe-transfer buttons outside GuiScreen.buttonList.
        try {
            Object overlays=screen.getClass().getMethod("getOverlayButtons").invoke(screen);
            if(overlays instanceof Iterable<?> values) for(Object value:values) if(value instanceof GuiButton button&&!out.contains(button)) out.add(button);
        } catch(ReflectiveOperationException unavailable) {}
        return out;
    }

    static JsonArray buttons(GuiScreen screen) {
        JsonArray a = new JsonArray();
        for (GuiButton bt : buttonListOf(screen)) {
            if (bt == null) {
                continue;
            }
            a.add(button(bt, null));
        }
        return a;
    }

    static JsonObject button(GuiButton bt, String field) {
        JsonObject e = Json.object("id", bt.id, "x", bt.xPosition, "y", bt.yPosition, "w", width(bt), "h", height(bt), "text", strip(bt.displayString), "enabled", bt.enabled, "visible", bt.visible);
        if (!bt.getClass().equals(GuiButton.class)) {
            e.addProperty("class", bt.getClass().getName());
        }
        if (field != null) {
            e.addProperty("field", field);
        }
        for(String method:List.of("getMessage","getSetting","getCurrentValue")) try {
            Object value=bt.getClass().getMethod(method).invoke(bt);if(value!=null) e.addProperty(method,strip(String.valueOf(value)));
        } catch(ReflectiveOperationException unavailable) {}
        if(bt.getClass().getName().startsWith("codechicken.nei.recipe.")) try {
            Object ref=bt.getClass().getField("handlerRef").get(bt);
            Object handler=ref.getClass().getField("handler").get(ref);
            JsonObject recipe=Json.object("index",ref.getClass().getField("recipeIndex").get(ref),"handler",handler.getClass().getName());
            for(String method:List.of("canFillCraftingGrid","requireShiftForOverlayRecipe","hasOverlay")) try {
                recipe.add(method,Json.GSON.toJsonTree(bt.getClass().getMethod(method).invoke(bt)));
            } catch(NoSuchMethodException unavailable) {}
            e.add("nei",recipe);
        } catch(ReflectiveOperationException unavailable) {}
        return e;
    }

    static int width(GuiButton bt) {
        try {
            return ObfuscationReflectionHelper.getPrivateValue(GuiButton.class, bt, "width", "field_146120_f");
        } catch (Throwable t) {
            return -1;
        }
    }

    static int height(GuiButton bt) {
        try {
            return ObfuscationReflectionHelper.getPrivateValue(GuiButton.class, bt, "height", "field_146121_g");
        } catch (Throwable t) {
            return -1;
        }
    }

    @SuppressWarnings("unchecked")
    static JsonArray labels(GuiScreen screen) {
        JsonArray a = new JsonArray();
        try {
            List<GuiLabel> list = ObfuscationReflectionHelper.getPrivateValue(GuiScreen.class, screen, "labelList", "field_146293_o");
            if (list != null) {
                for (GuiLabel l : list) {
                    List<String> lines = ObfuscationReflectionHelper.getPrivateValue(GuiLabel.class, l, "labels", "field_146173_k");
                    JsonObject e = Json.object("x", l.field_146162_g, "y", l.field_146174_h);
                    e.add("lines", Json.GSON.toJsonTree(lines == null ? new ArrayList<>() : lines));
                    a.add(e);
                }
            }
        } catch (Throwable ignored) {
        }
        return a;
    }

    static int tfInt(GuiTextField tf, String... names) {
        try {
            return ObfuscationReflectionHelper.getPrivateValue(GuiTextField.class, tf, names);
        } catch (Throwable t) {
            return -1;
        }
    }

    static JsonObject textField(GuiTextField tf, String field) {
        JsonObject e = Json.object("kind", "text_field", "x", tf.xPosition, "y", tf.yPosition, "w", tfInt(tf, "width", "field_146218_h"), "h", tfInt(tf, "height", "field_146219_i"), "text", tf.getText(), "focused", tf.isFocused(), "visible", tf.getVisible(), "max", tf.getMaxStringLength());
        if (field != null) {
            e.addProperty("field", field);
        }
        return e;
    }
    static boolean isTextField(Object value) {
        if(value instanceof GuiTextField) return true;
        if(value==null) return false;
        try {value.getClass().getMethod("textboxKeyTyped",char.class,int.class);value.getClass().getMethod("getText");return true;}
        catch(NoSuchMethodException missing) {return false;}
    }
    static JsonObject textField(Object value,String path) {
        if(value instanceof GuiTextField tf) return textField(tf,path);
        JsonObject out=Json.object("kind","text_field","field",path,"class",value.getClass().getName());
        try {
            for(String[] property:new String[][]{{"x","xPos"},{"y","yPos"},{"w","getWidth"},{"h","getHeight"},{"text","getText"},{"focused","isFocused"},{"visible","isVisible"}})
                out.add(property[0],Json.GSON.toJsonTree(value.getClass().getMethod(property[1]).invoke(value)));
        } catch(ReflectiveOperationException unavailable) {out.addProperty("observationError",unavailable.toString());}
        return out;
    }

    /**
     * Reflective walk over the screen's own fields (up to but excluding GuiScreen): text fields, extra buttons,
     * scroll lists, and any other object with x/y-like fields, so mod GUIs without vanilla widgets still expose
     * something clickable.
     */
    @SuppressWarnings("unchecked")
    static JsonArray fields(GuiScreen screen, boolean deep) {
        JsonArray a = new JsonArray();
        Set<Object> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        List<GuiButton> known;
        try {
            known = ObfuscationReflectionHelper.getPrivateValue(GuiScreen.class, screen, "buttonList", "field_146292_n");
        } catch (Throwable t) {
            known = new ArrayList<>();
        }
        if (known != null) {
            seen.addAll(known);
        }
        for (Class<?> c = screen.getClass(); c != null && c != GuiScreen.class && c != GuiContainer.class && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                Object v;
                try {
                    f.setAccessible(true);
                    v = f.get(screen);
                } catch (Throwable t) {
                    continue;
                }
                if (v == null) {
                    continue;
                }
                String name = c.getSimpleName() + "." + f.getName();
                if (v instanceof Collection) {
                    int i = 0;
                    for (Object x : (Collection<Object>) v) {
                        if (i++ > 64) {
                            break;
                        }
                        widget(x, name + "[" + (i - 1) + "]", a, seen, deep);
                    }
                } else if (v instanceof Object[]) {
                    int i = 0;
                    for (Object x : (Object[]) v) {
                        if (i++ > 64) {
                            break;
                        }
                        widget(x, name + "[" + (i - 1) + "]", a, seen, deep);
                    }
                } else if (v instanceof Map) {
                    int i = 0;
                    for (Object x : ((Map<Object, Object>) v).values()) {
                        if (i++ > 64) {
                            break;
                        }
                        widget(x, name + "{" + (i - 1) + "}", a, seen, deep);
                    }
                } else {
                    widget(v, name, a, seen, deep);
                }
            }
        }
        return a;
    }

    private static void widget(Object v, String name, JsonArray a, Set<Object> seen, boolean deep) {
        if (v == null || seen.contains(v)) {
            return;
        }
        if (v instanceof GuiButton) {
            seen.add(v);
            a.add(button((GuiButton) v, name));
        } else if (isTextField(v)) {
            seen.add(v);
            a.add(textField(v, name));
        } else if (deep) {
            Class<?> cl = v.getClass();
            String cn = cl.getName();
            if (cn.startsWith("java.") || cn.startsWith("net.minecraft.") && !cn.contains("Gui") || v instanceof Number || v instanceof String || v instanceof Boolean || v instanceof Enum || v instanceof ItemStack) {
                return;
            }
            // anything with plain x/y (or xPos/yPos) int fields and a width/height is probably a custom widget;
            // so is anything carrying a java.awt.Rectangle (overlays, pickers, tabs in EnderCore/CoFH-style GUIs)
            Integer x = intAccessor(v, intField(v, "x", "xPos", "posX", "xPosition"), "posX", "getX", "getPosX"), y = intAccessor(v, intField(v, "y", "yPos", "posY", "yPosition"), "posY", "getY", "getPosY");
            Integer w = intField(v, "currentWidth", "width", "w", "xSize", "sizeX"), h = intField(v, "currentHeight", "height", "h", "ySize", "sizeY");
            java.awt.Rectangle r = null;
            if (x == null || y == null) {
                r = rectField(v);
                if (r == null) {
                    return;
                }
                x = r.x;
                y = r.y;
                w = r.width;
                h = r.height;
            }
            seen.add(v);
            JsonObject e = Json.object("field", name, "class", cn, "x", x, "y", y);
            if (r != null) {
                e.addProperty("bounds", "rectangle");
            }
            if (w != null) {
                e.addProperty("w", w);
            }
            if (h != null) {
                e.addProperty("h", h);
            }
            String text = strField(v, "text", "label", "displayString", "name", "title", "tooltip");
            if (text != null) {
                e.addProperty("text", strip(text));
            }
            Boolean vis = boolField(v, "visible", "isVisible", "enabled");
            if (vis != null) {
                e.addProperty("visible", vis);
            }
            Method ht = hitMethod(v);
            if (ht != null) {
                e.addProperty("hitTest", ht.getName());
            }
            int[] bd = boundsAccessor(v);
            if (bd != null) {
                e.add("bounds", Json.object("x", bd[0], "y", bd[1], "w", bd[2], "h", bd[3]));
            }
            a.add(e);
        }
    }

    // ---- gui.hit_test ----

    private static final String[] HIT_NAMES = {"intersectsWith", "isMouseOver", "isMouseOverElement", "isMouseInside", "mouseOver", "isHovered", "isHovering", "isPointInside", "isWithin", "inBounds", "isInBounds", "contains", "containsPoint", "isInside", "isOver", "hitTest", "isMouseOverWidget"};

    /** A public/declared boolean method (int,int) (or (double,double)) with a hit-test-like name, on the object's class chain. */
    static Method hitMethod(Object o) {
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || m.getReturnType() != boolean.class || m.getParameterCount() != 2) {
                    continue;
                }
                Class<?>[] pt = m.getParameterTypes();
                boolean ints = pt[0] == int.class && pt[1] == int.class, dbls = pt[0] == double.class && pt[1] == double.class;
                if (!ints && !dbls) {
                    continue;
                }
                for (String n : HIT_NAMES) {
                    if (m.getName().equals(n)) {
                        m.setAccessible(true);
                        return m;
                    }
                }
            }
        }
        return null;
    }

    static JsonObject hitTest(GuiScreen screen, int x, int y) {
        JsonObject o = Json.object("x", x, "y", y, "screen", screen.getClass().getName());
        // container slot (GuiContainer.getSlotAtPosition is private)
        if (screen instanceof GuiContainer) {
            GuiContainer gc = (GuiContainer) screen;
            Slot hit = null;
            try {
                Method m = method(GuiContainer.class,"getSlotAtPosition","func_146975_c",int.class,int.class);
                hit = (Slot) m.invoke(gc, x, y);
            } catch (Throwable t) {
                for (Slot s : (List<Slot>)gc.inventorySlots.inventorySlots) {
                    int sx = guiInt(gc,"guiLeft","field_147003_i") + s.xDisplayPosition, sy = guiInt(gc,"guiTop","field_147009_r") + s.yDisplayPosition;
                    if (x >= sx && x < sx + 16 && y >= sy && y < sy + 16) {
                        hit = s;
                        break;
                    }
                }
            }
            if (hit == null) {
                o.add("slot", JsonNull.INSTANCE);
            } else {
                int li = gc.inventorySlots.inventorySlots.indexOf(hit);
                JsonObject e = Json.object("i", li < 0 ? hit.slotNumber : li, "x", hit.xDisplayPosition, "y", hit.yDisplayPosition, "enabled", hit.func_111238_b(), "slotClass", hit.getClass().getSimpleName());
                if (li >= 0 && li != hit.slotNumber) {
                    e.addProperty("virtual", true);
                }
                e.add("stack", Stacks.json(hit.getStack()));
                o.add("slot", e);
            }
            o.add("gui", Json.object("left", guiInt(gc,"guiLeft","field_147003_i"), "top", guiInt(gc,"guiTop","field_147009_r"), "w", guiInt(gc,"xSize","field_146999_f"), "h", guiInt(gc,"ySize","field_147000_g")));
        }
        JsonArray bts = new JsonArray();
        for (GuiButton bt : buttonListOf(screen)) {
            if (bt != null && bt.visible && x >= bt.xPosition && x < bt.xPosition + width(bt) && y >= bt.yPosition && y < bt.yPosition + height(bt)) {
                bts.add(button(bt, null));
            }
        }
        o.add("buttons", bts);
        JsonArray tfs = new JsonArray();
        for (Object[] f : textFields(screen)) {
            JsonObject tf=textField(f[1],(String)f[0]);
            if(tf.has("x")&&tf.has("y")&&tf.has("w")&&tf.has("h")&&x>=tf.get("x").getAsInt()&&x<tf.get("x").getAsInt()+tf.get("w").getAsInt()&&y>=tf.get("y").getAsInt()&&y<tf.get("y").getAsInt()+tf.get("h").getAsInt()) tfs.add(tf);
        }
        o.add("fields", tfs);
        JsonArray widgets = new JsonArray();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(screen);
        int gl = screen instanceof GuiContainer ? guiInt(((GuiContainer) screen),"guiLeft","field_147003_i") : 0, gt = screen instanceof GuiContainer ? guiInt(((GuiContainer) screen),"guiTop","field_147009_r") : 0;
        for (Class<?> c = screen.getClass(); c != null && c != GuiScreen.class && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                Object v;
                try {
                    f.setAccessible(true);
                    v = f.get(screen);
                } catch (Throwable t) {
                    continue;
                }
                hitWalk(v, c.getSimpleName() + "." + f.getName(), x, y, gl, gt, widgets, seen, 0);
            }
        }
        o.add("widgets", widgets);
        o.addProperty("hits", (o.has("slot") && !o.get("slot").isJsonNull() ? 1 : 0) + bts.size() + tfs.size() + widgets.size());
        return o;
    }

    @SuppressWarnings("unchecked")
    private static void hitWalk(Object v, String name, int x, int y, int gl, int gt, JsonArray out, Set<Object> seen, int depth) {
        if (v == null || seen.contains(v) || depth > 3 || out.size() > 64) {
            return;
        }
        if (v instanceof Collection) {
            int i = 0;
            for (Object e : (Collection<Object>) v) {
                if (i++ > 128) {
                    break;
                }
                hitWalk(e, name + "[" + (i - 1) + "]", x, y, gl, gt, out, seen, depth + 1);
            }
            return;
        }
        if (v instanceof Object[]) {
            int i = 0;
            for (Object e : (Object[]) v) {
                if (i++ > 128) {
                    break;
                }
                hitWalk(e, name + "[" + (i - 1) + "]", x, y, gl, gt, out, seen, depth + 1);
            }
            return;
        }
        if (v instanceof Map) {
            int i = 0;
            for (Object e : ((Map<Object, Object>) v).values()) {
                if (i++ > 128) {
                    break;
                }
                hitWalk(e, name + "{" + (i - 1) + "}", x, y, gl, gt, out, seen, depth + 1);
            }
            return;
        }
        Class<?> cl = v.getClass();
        String cn = cl.getName();
        if (cn.startsWith("java.") || cn.startsWith("javax.") || cn.startsWith("net.minecraft.") && !cn.contains("Gui") || v instanceof Number || v instanceof String || v instanceof Boolean || v instanceof Enum || v instanceof ItemStack || v instanceof GuiButton || v instanceof GuiTextField) {
            return;
        }
        seen.add(v);
        // Widgets keep their geometry either in absolute screen coordinates or relative to the container's guiLeft/guiTop
        // (CoFH/EnderCore elements, whose intersectsWith(mouseX - guiLeft, mouseY - guiTop) the screen calls); try both frames.
        Method ht = hitMethod(v);
        String frame = null;
        Boolean byMethod = null;
        if (ht != null) {
            for (int[] pt : new int[][]{{x, y, 0}, {x - gl, y - gt, 1}}) {
                if (pt[2] == 1 && gl == 0 && gt == 0) {
                    break;
                }
                try {
                    Boolean r0 = ht.getParameterTypes()[0] == int.class ? (Boolean) ht.invoke(v, pt[0], pt[1]) : (Boolean) ht.invoke(v, (double) pt[0], (double) pt[1]);
                    if (byMethod == null || Boolean.TRUE.equals(r0)) {
                        byMethod = r0;
                    }
                    if (Boolean.TRUE.equals(r0)) {
                        frame = pt[2] == 0 ? "absolute" : "gui";
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        Integer wx = intAccessor(v, intField(v, "x", "xPos", "posX", "xPosition"), "posX", "getX", "getPosX"), wy = intAccessor(v, intField(v, "y", "yPos", "posY", "yPosition"), "posY", "getY", "getPosY");
        Integer ww = intField(v, "currentWidth", "width", "w", "xSize", "sizeX"), wh = intField(v, "currentHeight", "height", "h", "ySize", "sizeY");
        java.awt.Rectangle r = (wx == null || wy == null) ? rectField(v) : null;
        Boolean byGeom = null;
        String geomFrame = null;
        if (r != null) {
            wx = r.x;
            wy = r.y;
            ww = r.width;
            wh = r.height;
        }
        if (wx != null && wy != null && ww != null && wh != null) {
            byGeom = x >= wx && x < wx + ww && y >= wy && y < wy + wh;
            if (byGeom) {
                geomFrame = "absolute";
            } else if (gl != 0 || gt != 0) {
                int rx = x - gl, ry = y - gt;
                if (rx >= wx && rx < wx + ww && ry >= wy && ry < wy + wh) {
                    byGeom = true;
                    geomFrame = "gui";
                }
            }
        }
        // a widget's bounds accessor (getBounds()/getRect(): CoFH Rectangle4i, java.awt.Rectangle, anything with x/y/w/h)
        // is usually the screen-space rectangle the screen itself draws and clicks against
        int[] bounds = boundsAccessor(v);
        Boolean byBounds = bounds == null ? null : x >= bounds[0] && x < bounds[0] + bounds[2] && y >= bounds[1] && y < bounds[1] + bounds[3];
        // Trust any positive signal: the widget's own 2-arg hit method can be the wrong overload for the screen's real
        // hit-test (CoFH tabs are tested with intersectsWith(mouseX, mouseY, shiftX, shiftY) against shifted anchors,
        // so the inherited 2-arg form says false while the drawn geometry is right). Every signal is reported.
        boolean hit = Boolean.TRUE.equals(byMethod) || Boolean.TRUE.equals(byGeom) || Boolean.TRUE.equals(byBounds);
        if (hit) {
            JsonObject e = Json.object("field", name, "class", cn, "x", wx, "y", wy, "w", ww, "h", wh);
            String fr = Boolean.TRUE.equals(byMethod) && frame != null ? frame : geomFrame != null ? geomFrame : frame;
            if (Boolean.TRUE.equals(byBounds)) {
                e.add("bounds", Json.object("x", bounds[0], "y", bounds[1], "w", bounds[2], "h", bounds[3]));
                e.add("clickAt", Json.object("x", bounds[0] + bounds[2] / 2, "y", bounds[1] + bounds[3] / 2));
            }
            if (fr != null) {
                e.addProperty("frame", fr);
                if (!e.has("clickAt") && wx != null && wy != null && ww != null && wh != null) {
                    int ax = fr.equals("gui") ? wx + gl : wx, ay = fr.equals("gui") ? wy + gt : wy;
                    e.add("clickAt", Json.object("x", ax + ww / 2, "y", ay + wh / 2));
                }
            }
            if (byMethod != null) {
                e.addProperty("hitTest", ht.getName());
                e.addProperty("methodHit", byMethod);
            }
            if (byGeom != null) {
                e.addProperty("geometryHit", byGeom);
            }
            if (byBounds != null) {
                e.addProperty("boundsHit", byBounds);
            }
            String text = strField(v, "text", "label", "displayString", "name", "title", "tooltip");
            if (text != null) {
                e.addProperty("text", strip(text));
            }
            Boolean vis = boolField(v, "visible", "isVisible", "enabled");
            if (vis != null) {
                e.addProperty("visible", vis);
            }
            out.add(e);
        }
        // descend into the widget's own children (tab lists, element lists) but not into arbitrary objects
        if (depth < 3 && (hit || wx == null)) {
            for (Class<?> c = cl; c != null && c != Object.class && !c.getName().startsWith("net.minecraft."); c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    Class<?> ft = f.getType();
                    if (!(Collection.class.isAssignableFrom(ft) || ft.isArray() || Map.class.isAssignableFrom(ft))) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                        hitWalk(f.get(v), name + "." + f.getName(), x, y, gl, gt, out, seen, depth + 1);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    /**
     * A widget's drawn position may differ from its anchor field (CoFH tabs on the left side draw at posX - currentWidth
     * and expose that through posX()); prefer a zero-arg int accessor with one of these names over the raw field.
     */
    private static Integer intAccessor(Object o, Integer fallback, String... names) {
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (String n : names) {
                try {
                    Method m = c.getDeclaredMethod(n);
                    if (m.getReturnType() == int.class || m.getReturnType() == float.class || m.getReturnType() == double.class) {
                        m.setAccessible(true);
                        return ((Number) m.invoke(o)).intValue();
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return fallback;
    }

    private static Field findField(Object o, String... names) {
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (String n : names) {
                try {
                    Field f = c.getDeclaredField(n);
                    f.setAccessible(true);
                    return f;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** First java.awt.Rectangle-typed instance field (any name) declared on the object's class chain. */
    /**
     * Screen-space rectangle from a zero-arg bounds accessor (getBounds/getRect/getRectangle/bounds/getBoundingBox):
     * a java.awt.Rectangle or any object with int x/y (or left/top, x1/y1) and w/h (or width/height, x2/y2) fields.
     */
    static int[] boundsAccessor(Object o) {
        for (Class<?> c = o.getClass(); c != null && c != Object.class && !c.getName().startsWith("net.minecraft."); c = c.getSuperclass()) {
            for (String n : new String[]{"getBounds", "getRect", "getRectangle", "bounds", "getBoundingBox", "getArea"}) {
                try {
                    Method m = c.getDeclaredMethod(n);
                    if (m.getReturnType() == void.class || m.getReturnType().isPrimitive()) {
                        continue;
                    }
                    m.setAccessible(true);
                    Object r = m.invoke(o);
                    if (r == null) {
                        continue;
                    }
                    if (r instanceof java.awt.Rectangle) {
                        java.awt.Rectangle a = (java.awt.Rectangle) r;
                        return new int[]{a.x, a.y, a.width, a.height};
                    }
                    Integer x = intField(r, "x", "left", "x1", "minX", "xMin"), y = intField(r, "y", "top", "y1", "minY", "yMin");
                    Integer w = intField(r, "w", "width", "sizeX"), h = intField(r, "h", "height", "sizeY");
                    if (x == null || y == null) {
                        continue;
                    }
                    if (w == null || h == null) {
                        Integer x2 = intField(r, "x2", "right", "maxX", "xMax"), y2 = intField(r, "y2", "bottom", "maxY", "yMax");
                        if (x2 == null || y2 == null) {
                            continue;
                        }
                        w = x2 - x;
                        h = y2 - y;
                    }
                    if (w <= 0 || h <= 0) {
                        continue;
                    }
                    return new int[]{x, y, w, h};
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static java.awt.Rectangle rectField(Object o) {
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType() != java.awt.Rectangle.class) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    return (java.awt.Rectangle) f.get(o);
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static Integer intField(Object o, String... names) {
        Field f = findField(o, names);
        if (f == null || (f.getType() != int.class && f.getType() != float.class && f.getType() != double.class)) {
            return null;
        }
        try {
            return ((Number) f.get(o)).intValue();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String strField(Object o, String... names) {
        Field f = findField(o, names);
        if (f == null || f.getType() != String.class) {
            return null;
        }
        try {
            return (String) f.get(o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Boolean boolField(Object o, String... names) {
        Field f = findField(o, names);
        if (f == null || f.getType() != boolean.class) {
            return null;
        }
        try {
            return f.getBoolean(o);
        } catch (Throwable t) {
            return null;
        }
    }

    static String strip(String s) {
        return s == null ? null : net.minecraft.util.EnumChatFormatting.getTextWithoutFormattingCodes(s);
    }

    static int guiInt(GuiContainer screen,String name,String srg) {
        return ObfuscationReflectionHelper.getPrivateValue(GuiContainer.class,screen,name,srg);
    }
    static Method method(Class<?> type,String mcp,String srg,Class<?>... args) throws Exception {
        Method m;try {m=type.getDeclaredMethod(mcp,args);}catch(NoSuchMethodException absent) {m=type.getDeclaredMethod(srg,args);}
        m.setAccessible(true);return m;
    }
    static List<Object[]> textFields(GuiScreen screen) {
        List<Object[]> out=new ArrayList<>();Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Class<?> type=screen.getClass();type!=null&&type!=GuiScreen.class;type=type.getSuperclass())
            for(Field field:type.getDeclaredFields()) if(!Modifier.isStatic(field.getModifiers())) try {
                field.setAccessible(true);Object value=field.get(screen);
                boolean indexed=value instanceof Collection<?>||value instanceof Object[],mapped=value instanceof Map<?,?>;
                Iterable<?> values=value instanceof Collection<?> list?list:value instanceof Object[] array?Arrays.asList(array):value instanceof Map<?,?> map?map.values():Collections.singletonList(value);
                int i=0;for(Object v:values) {
                    if(i++>=65) break;
                    String path=type.getSimpleName()+"."+field.getName()+(indexed?"["+(i-1)+"]":mapped?"{"+(i-1)+"}":"");
                    if(isTextField(v) && seen.add(v)) out.add(new Object[]{path,v});
                }
            } catch(ReflectiveOperationException|RuntimeException unavailable) {}
        return out;
    }
}
