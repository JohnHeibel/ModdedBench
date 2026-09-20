// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone;

import baritone.api.event.events.*;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.utils.GameEventHandler;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class GameEventHandlerTest {
    @Test public void listenersCanRegisterDuringDispatchWithoutChangingThisEvent(){
        var bus=new GameEventHandler();List<String> calls=new ArrayList<>();
        var later=new AbstractGameEventListener(){public void onPlayerDeath(){calls.add("later");}};
        bus.registerEventListener(new AbstractGameEventListener(){public void onPlayerDeath(){calls.add("first");bus.registerEventListener(later);}});
        bus.onPlayerDeath();assertEquals(List.of("first"),calls);calls.clear();
        bus.onPlayerDeath();assertEquals(List.of("first","later"),calls);
    }
    @Test public void cancellationAndMutableRotationPropagateInRegistrationOrder(){
        var bus=new GameEventHandler();
        bus.registerEventListener(new AbstractGameEventListener(){
            public void onSendChatMessage(ChatEvent event){event.cancel();}
            public void onPlayerRotationMove(RotationMoveEvent event){event.setYaw(90);}
        });
        bus.registerEventListener(new AbstractGameEventListener(){
            public void onSendChatMessage(ChatEvent event){assertTrue(event.isCancelled());}
            public void onPlayerRotationMove(RotationMoveEvent event){assertEquals(90,event.getYaw(),0);event.setPitch(20);}
        });
        var chat=new ChatEvent("local command");bus.onSendChatMessage(chat);assertTrue(chat.isCancelled());
        var rotation=new RotationMoveEvent(RotationMoveEvent.Type.JUMP,10,0);bus.onPlayerRotationMove(rotation);assertEquals(20,rotation.getPitch(),0);
    }
}
