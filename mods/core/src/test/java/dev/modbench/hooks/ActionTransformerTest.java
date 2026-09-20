// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package dev.modbench.hooks;

import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.Assert.*;

public class ActionTransformerTest {
    private byte[] target(String method){
        ClassWriter writer=new ClassWriter(0);writer.visit(Opcodes.V1_6,Opcodes.ACC_PUBLIC,"net/minecraft/client/Minecraft",null,"java/lang/Object",null);
        MethodVisitor body=writer.visitMethod(Opcodes.ACC_PRIVATE,method,"(Z)V",null,null);body.visitCode();body.visitInsn(Opcodes.RETURN);body.visitMaxs(0,2);body.visitEnd();writer.visitEnd();return writer.toByteArray();
    }
    private byte[] transactionTarget(String... methods){
        ClassWriter writer=new ClassWriter(0);writer.visit(Opcodes.V1_6,Opcodes.ACC_PUBLIC,"net/minecraft/client/network/NetHandlerPlayClient",null,"java/lang/Object",null);
        for(String method:methods){MethodVisitor body=writer.visitMethod(Opcodes.ACC_PUBLIC,method,"(Lnet/minecraft/network/play/server/S32PacketConfirmTransaction;)V",null,null);body.visitCode();body.visitInsn(Opcodes.RETURN);body.visitMaxs(0,2);body.visitEnd();}
        writer.visitEnd();return writer.toByteArray();
    }
    private MethodNode transactionMethod(byte[] bytes){
        ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);return node.methods.get(0);
    }
    @Test public void nativeAttackLoopIsGatedBeforeItsReset(){
        byte[] transformed=new ActionTransformer().transform("net.minecraft.client.Minecraft","net.minecraft.client.Minecraft",target("func_147115_a"));
        ClassNode node=new ClassNode();new ClassReader(transformed).accept(node,0);
        MethodNode method=node.methods.get(0);assertTrue(method.instructions.getFirst() instanceof MethodInsnNode);
        MethodInsnNode hook=(MethodInsnNode)method.instructions.getFirst();
        assertEquals("dev/modbench/hooks/GameHooks",hook.owner);assertEquals("ownsNativeActions",hook.name);
        assertEquals(Opcodes.IFEQ,method.instructions.get(1).getOpcode());assertEquals(Opcodes.RETURN,method.instructions.get(2).getOpcode());
    }
    @Test public void unexpectedNativeVersionFailsLoudlyInsteadOfLosingTheHook(){
        assertThrows(IllegalStateException.class,()->new ActionTransformer().transform("net.minecraft.client.Minecraft","net.minecraft.client.Minecraft",target("differentMethod")));
    }
    @Test public void otherClassesAreUntouched(){
        byte[] bytes=target("func_147115_a");assertSame(bytes,new ActionTransformer().transform("other.Type","other.Type",bytes));
    }
    @Test public void obfuscatedTransactionConfirmationCallsTheAcknowledgementHookFirst(){
        MethodNode method=transactionMethod(new ActionTransformer().transform("net.minecraft.client.network.NetHandlerPlayClient","net.minecraft.client.network.NetHandlerPlayClient",transactionTarget("func_147239_a")));
        assertEquals(Opcodes.ALOAD,method.instructions.get(0).getOpcode());assertEquals(1,((VarInsnNode)method.instructions.get(0)).var);
        MethodInsnNode hook=(MethodInsnNode)method.instructions.get(1);assertEquals(Opcodes.INVOKESTATIC,hook.getOpcode());assertEquals("dev/modbench/hooks/GameHooks",hook.owner);assertEquals("confirmed",hook.name);assertEquals("(Ljava/lang/Object;)V",hook.desc);
        assertEquals(Opcodes.RETURN,method.instructions.get(2).getOpcode());
    }
    @Test public void deobfuscatedTransactionConfirmationIsAlsoAccepted(){
        MethodNode method=transactionMethod(new ActionTransformer().transform("net.minecraft.client.network.NetHandlerPlayClient","net.minecraft.client.network.NetHandlerPlayClient",transactionTarget("handleConfirmTransaction")));
        assertEquals("confirmed",((MethodInsnNode)method.instructions.get(1)).name);
    }
    @Test public void missingOrDuplicateTransactionHandlersFailLoudly(){
        ActionTransformer transformer=new ActionTransformer();
        assertThrows(IllegalStateException.class,()->transformer.transform("net.minecraft.client.network.NetHandlerPlayClient","net.minecraft.client.network.NetHandlerPlayClient",transactionTarget("other")));
        assertThrows(IllegalStateException.class,()->transformer.transform("net.minecraft.client.network.NetHandlerPlayClient","net.minecraft.client.network.NetHandlerPlayClient",transactionTarget("func_147239_a","handleConfirmTransaction")));
    }
}
