// SPDX-License-Identifier: MIT
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.hooks;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class UiHooksKeyTest {
    public static class Container {
        final List<String> events=new ArrayList<>();
        boolean searchFocused;
    }
    public static class Manager {
        final Container screen;
        Manager(Container screen){this.screen=screen;}
        public static Manager getManager(Container screen){return new Manager(screen);}
        public void keyTyped(char character,int key){
            screen.events.add("handler:"+character+":"+key);
            if(!screen.searchFocused)screen.events.add("screen:"+character+":"+key);
        }
    }
    private boolean dispatch(Object screen,char character,int key) throws Exception {
        return UiHooks.containerKey(screen,character,key,Manager.class.getMethod("getManager",Container.class),Manager.class.getMethod("keyTyped",char.class,int.class));
    }
    @Test public void focusedOverlayReceivesSyntheticTextBeforeScreen() throws Exception {
        Container screen=new Container();screen.searchFocused=true;
        assertTrue(dispatch(screen,'s',0));
        assertEquals(List.of("handler:s:0"),screen.events);
    }
    @Test public void unhandledKeysKeepNativeForwardingAndPlainScreensFallBack() throws Exception {
        Container screen=new Container();
        assertTrue(dispatch(screen,'\u0001',30));
        assertEquals(List.of("handler:\u0001:30","screen:\u0001:30"),screen.events);
        assertFalse(dispatch(new Object(),'s',0));
        assertFalse(UiHooks.containerKey(screen,'s',0,null,null));
    }
}
