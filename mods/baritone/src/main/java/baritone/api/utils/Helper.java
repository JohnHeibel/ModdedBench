// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.api.utils;

public interface Helper {
    Helper HELPER=new Helper(){};
    default void logDebug(String message){org.apache.logging.log4j.LogManager.getLogger("Baritone").debug(message);}
    default void logDirect(String message){org.apache.logging.log4j.LogManager.getLogger("Baritone").info(message);}
    default void logDirect(String message,boolean notification){logDirect(message);}
    default void logNotification(String message,boolean error){logDirect(message);}
    default void logDirect(String message,net.minecraft.util.EnumChatFormatting... colors){logDirect(message);}
}
