// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.api;

import java.util.*;

/** Bounded synthetic GUI input state. The caller owns its lifetime and clears it on every exit. */
public final class UiInput {
    public record MouseEvent(int x,int y,int button,boolean down,int wheel) {}
    public record KeyEvent(int key,char character,boolean down) {}
    private static final ArrayDeque<MouseEvent> mouse=new ArrayDeque<>();
    private static final ArrayDeque<KeyEvent> keyboard=new ArrayDeque<>();
    private static final Set<Integer> keys=new HashSet<>(),buttons=new HashSet<>();
    private static MouseEvent currentMouse;
    private static KeyEvent currentKey;
    private static int x=-1,y=-1,wheel;
    private static long delivered,frames;
    private UiInput() {}
    public static synchronized void postMouse(int x,int y,int button,boolean down,int wheel) {
        if(mouse.size()>=1024) throw new IllegalStateException("GUI mouse queue capacity exceeded");
        mouse.add(new MouseEvent(x,y,button,down,wheel));
    }
    public static synchronized void postKey(int key,char character,boolean down) {
        if(keyboard.size()>=2048) throw new IllegalStateException("GUI key queue capacity exceeded");
        keyboard.add(new KeyEvent(key,character,down));
    }
    public static synchronized void modifier(int key,boolean down) {if(down) keys.add(key);else keys.remove(key);}
    public static synchronized boolean keyDown(int key) {return keys.contains(key);}
    public static synchronized boolean buttonDown(int button) {return buttons.contains(button);}
    public static synchronized boolean nextMouse() {
        currentMouse=mouse.poll();if(currentMouse==null) return false;
        x=currentMouse.x;y=currentMouse.y;wheel+=currentMouse.wheel;
        if(currentMouse.button>=0) {if(currentMouse.down) buttons.add(currentMouse.button);else buttons.remove(currentMouse.button);}
        delivered++;return true;
    }
    public static synchronized boolean nextKey() {
        currentKey=keyboard.poll();if(currentKey==null) return false;
        if(currentKey.key!=0) modifier(currentKey.key,currentKey.down);
        delivered++;return true;
    }
    public static synchronized int takeWheel() {int value=wheel;wheel=0;return value;}
    public static synchronized MouseEvent mouse() {return currentMouse;}
    public static synchronized KeyEvent key() {return currentKey;}
    public static synchronized int x() {return x;}
    public static synchronized int y() {return y;}
    public static synchronized int pending() {return mouse.size()+keyboard.size();}
    public static synchronized long delivered() {return delivered;}
    public static synchronized long frames() {return frames;}
    public static synchronized void rendered() {frames++;}
    public static synchronized void clear() {
        mouse.clear();keyboard.clear();keys.clear();buttons.clear();currentMouse=null;currentKey=null;x=-1;y=-1;wheel=0;
    }
}
