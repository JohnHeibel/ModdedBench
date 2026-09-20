// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.hooks;

import dev.modbench.api.UiInput;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** Native input fallback with synthetic event/poll semantics; excluded from call-site rewriting. */
public final class UiHooks {
    private UiHooks() {}
    private static java.lang.reflect.Method keyTyped;
    /** Native GUI event dispatch for synthetic input, independent of GLFW's private queue. */
    public static void handleKeyboardInput(Object target) {
        var screen=(net.minecraft.client.gui.GuiScreen)target;var event=UiInput.key();
        if(event==null){screen.handleKeyboardInput();return;}
        try {
            if(event.down()){
                if(keyTyped==null){
                    for(String name:new String[]{"func_73869_a","keyTyped"})try{
                        keyTyped=net.minecraft.client.gui.GuiScreen.class.getDeclaredMethod(name,char.class,int.class);
                        keyTyped.setAccessible(true);break;
                    }catch(NoSuchMethodException absent){}
                    if(keyTyped==null)throw new IllegalStateException("native GuiScreen key dispatcher unavailable");
                }
                // Reflection retains virtual dispatch to each mod's actual screen.
                keyTyped.invoke(screen,event.character(),event.key());
            }
            Minecraft.getMinecraft().func_152348_aa();
        }catch(java.lang.reflect.InvocationTargetException error){
            if(error.getCause() instanceof RuntimeException cause)throw cause;
            if(error.getCause() instanceof Error cause)throw cause;
            throw new IllegalStateException(error.getCause());
        }catch(ReflectiveOperationException error){throw new IllegalStateException("native GUI key dispatch failed",error);}
    }
    public static boolean keyDown(int key) {return UiInput.keyDown(key)||Keyboard.isKeyDown(key);}
    public static boolean buttonDown(int button) {return UiInput.buttonDown(button)||Mouse.isButtonDown(button);}
    public static boolean next() {return UiInput.nextMouse()||Mouse.next();}
    public static boolean keyNext() {return UiInput.nextKey()||Keyboard.next();}
    public static int eventKey() {var e=UiInput.key();return e==null?Keyboard.getEventKey():e.key();}
    public static char eventChar() {var e=UiInput.key();return e==null?Keyboard.getEventCharacter():e.character();}
    public static boolean eventKeyState() {var e=UiInput.key();return e==null?Keyboard.getEventKeyState():e.down();}
    public static boolean repeatEvent() {return UiInput.key()==null&&Keyboard.isRepeatEvent();}
    public static int eventX() {var e=UiInput.mouse();return e==null?Mouse.getEventX():displayX(e.x());}
    public static int eventY() {var e=UiInput.mouse();return e==null?Mouse.getEventY():displayY(e.y());}
    public static int eventButton() {var e=UiInput.mouse();return e==null?Mouse.getEventButton():e.button();}
    public static boolean eventButtonState() {var e=UiInput.mouse();return e==null?Mouse.getEventButtonState():e.down();}
    public static int eventDWheel() {var e=UiInput.mouse();return e==null?Mouse.getEventDWheel():e.wheel();}
    public static int dWheel() {return UiInput.takeWheel()+Mouse.getDWheel();}
    public static int mouseX() {return UiInput.x()<0?Mouse.getX():displayX(UiInput.x());}
    public static int mouseY() {return UiInput.y()<0?Mouse.getY():displayY(UiInput.y());}
    private static int displayX(int x) {
        var mc=Minecraft.getMinecraft();int width=mc.currentScreen==null?mc.displayWidth:mc.currentScreen.width;
        return (int)Math.ceil((x+.5)*mc.displayWidth/width);
    }
    private static int displayY(int y) {
        var mc=Minecraft.getMinecraft();int height=mc.currentScreen==null?mc.displayHeight:mc.currentScreen.height;
        return (int)Math.ceil((height-1-y+.5)*mc.displayHeight/height);
    }
}
