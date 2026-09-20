// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.bridge;

/** No Minecraft linkage here: the core transformer may load this before game classes. */
public final class ClockHooks {
    public interface Driver {
        boolean before();
        void after();
        boolean packet(Object packet, Object handler);
        default void beforeNetwork(Object manager) {}
        default void outgoing(Object packet) {}
        default void frameRendered() {}
        default boolean blockAction(int action,int x,int y,int z,int side) {return true;}
    }
    public static volatile Driver server, client;
    public static boolean beforeServer() { return server == null || server.before(); }
    public static void afterServer() { if (server != null) server.after(); }
    public static boolean beforeClient() { return client == null || client.before(); }
    public static void afterClient() { if (client != null) client.after(); }
    public static void frameRendered() {if(client!=null) client.frameRendered();}
    public static boolean packet(Object packet, Object handler) {
        return server != null && server.packet(packet, handler) || client != null && client.packet(packet, handler);
    }
    public static void outgoing(Object packet) {if(client!=null) client.outgoing(packet);}
    public static void beforeNetwork(Object manager) {
        if(server!=null) server.beforeNetwork(manager);
        if(client!=null) client.beforeNetwork(manager);
    }
    public static boolean blockAction(int action,int x,int y,int z,int side) {return client==null || client.blockAction(action,x,y,z,side);}
    public static void registerComputer(Object machine) { ComputerPause.register(machine); }
    public static void runGregTechUpdate(Object work) {
        AsyncPause.GREGTECH.run(()->{
            try { work.getClass().getMethod("modbench$run").invoke(work); }
            catch(java.lang.reflect.InvocationTargetException e) {
                if(e.getCause() instanceof Error error) throw error;
                if(e.getCause() instanceof RuntimeException error) throw error;
                throw new IllegalStateException(e.getCause());
            } catch(ReflectiveOperationException e) { throw new IllegalStateException("GregTech update hook unavailable",e); }
        });
    }
    private ClockHooks() {}
}
