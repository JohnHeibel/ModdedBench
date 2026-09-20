// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package dev.modbench.hooks;

import java.util.*;
import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import static org.junit.Assert.*;

/** Verify real Forge-patched 1.7 bytecode, including each injected method's stack. */
public class EventTransformerTest {
    private static final List<String> TARGETS=List.of("client.Minecraft","client.multiplayer.WorldClient","network.NetworkManager",
        "entity.Entity","entity.EntityLivingBase","client.entity.EntityPlayerSP","client.entity.EntityClientPlayerMP","client.gui.GuiChat");
    private byte[] nativeClass(String name)throws Exception{
        try(var in=getClass().getClassLoader().getResourceAsStream(name.replace('.','/')+".class")){
            assertNotNull("native test class "+name,in);return in.readAllBytes();
        }
    }
    /** The clock transformer runs first in ClockPlugin; NetworkManager receipt is routed through it. */
    private byte[] clocked(String name)throws Exception{byte[] bytes=nativeClass(name);return name.endsWith("NetworkManager")?new ClockTransformer().transform(name,name,bytes):bytes;}
    @Test public void allNativeClassesHaveTheExpectedHooksAndValidOperandStacks()throws Exception{
        for(String suffix:TARGETS){
            String name="net.minecraft."+suffix;byte[] before=clocked(name),after=new EventTransformer().transform(name,name,before);
            assertFalse(name,Arrays.equals(before,after));
            ClassNode node=new ClassNode();new ClassReader(after).accept(node,0);
            for(MethodNode method:node.methods)if((method.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))==0)
                new Analyzer<>(new BasicVerifier()).analyze(node.name,method);
        }
    }
    @Test public void changedNativeSignaturesFailInsteadOfSilentlyDroppingAnEvent()throws Exception{
        String name="net.minecraft.client.multiplayer.WorldClient";ClassNode node=new ClassNode();new ClassReader(nativeClass(name)).accept(node,0);
        node.methods.removeIf(m->m.name.equals("doPreChunk")||m.name.equals("func_73025_a"));
        ClassWriter writer=new ClassWriter(0);node.accept(writer);
        assertThrows(IllegalStateException.class,()->new EventTransformer().transform(name,name,writer.toByteArray()));
    }
    @Test public void unrelatedClassesAreIdentical(){byte[] bytes={1,2,3};assertSame(bytes,new EventTransformer().transform("other","other",bytes));}
    @Test public void playerApiDispatchWrapperKeepsHooksInTheActualNativeBody()throws Exception{
        String name="net.minecraft.client.entity.EntityPlayerSP";ClassNode node=new ClassNode();new ClassReader(nativeClass(name)).accept(node,0);
        MethodNode nativeBody=node.methods.stream().filter(m->Set.of("onLivingUpdate","func_70636_d").contains(m.name)&&m.desc.equals("()V")).findFirst().orElseThrow();
        String wrapperName=nativeBody.name;nativeBody.name="localOnLivingUpdate";
        MethodNode wrapper=new MethodNode(Opcodes.ACC_PUBLIC,wrapperName,"()V",null,null);
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,node.name,"localOnLivingUpdate","()V",false));wrapper.instructions.add(new InsnNode(Opcodes.RETURN));wrapper.maxStack=1;wrapper.maxLocals=1;node.methods.add(wrapper);
        ClassWriter writer=new ClassWriter(0);node.accept(writer);ClassNode result=new ClassNode();new ClassReader(new EventTransformer().transform(name,name,writer.toByteArray())).accept(result,0);
        int hooks=0;for(MethodNode method:result.methods)for(AbstractInsnNode instruction:method.instructions.toArray())if(instruction instanceof MethodInsnNode call&&call.owner.equals("dev/modbench/hooks/GameHooks")&&call.name.equals("sprint")){assertEquals("localOnLivingUpdate",method.name);hooks++;}
        assertTrue(hooks>0);
    }
    @Test public void packetReceiptGoesThroughTheClockAdmissionOnceAndNeverAroundIt()throws Exception{
        String name="net.minecraft.network.NetworkManager";
        assertThrows(IllegalStateException.class,()->new EventTransformer().transform(name,name,nativeClass(name)));
        ClassNode result=new ClassNode();new ClassReader(new EventTransformer().transform(name,name,clocked(name))).accept(result,0);
        int clocks=0,receives=0,raw=0;
        for(MethodNode method:result.methods)for(AbstractInsnNode instruction:method.instructions.toArray())if(instruction instanceof MethodInsnNode c){
            if(c.owner.equals("dev/modbench/hooks/ClockPackets")&&c.name.equals("dispatch")){assertTrue(Set.of("processReceivedPackets","func_74428_b").contains(method.name)||c.name.equals("dispatch"));clocks++;}
            if(c.owner.equals("dev/modbench/hooks/GameHooks")&&c.name.equals("receive"))receives++;
            if(c.owner.equals("net/minecraft/network/Packet")&&Set.of("processPacket","func_148833_a").contains(c.name))raw++;
        }
        assertEquals(2,clocks);assertEquals(0,receives);assertEquals(0,raw);
    }
}
