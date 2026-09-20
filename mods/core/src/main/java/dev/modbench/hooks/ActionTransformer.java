// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package dev.modbench.hooks;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** The 1.7 vanilla attack loop otherwise clears damage even with isHittingBlock=false. */
public final class ActionTransformer implements IClassTransformer {
    @Override public byte[] transform(String name,String transformedName,byte[] bytes) {
        if(bytes==null)return bytes;
        if(transformedName.equals("net.minecraft.client.network.NetHandlerPlayClient")){
            ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);int count=0;
            for(MethodNode method:node.methods)if(java.util.Set.of("handleConfirmTransaction","func_147239_a").contains(method.name)&&method.desc.endsWith("S32PacketConfirmTransaction;)V")){
                InsnList hook=new InsnList();hook.add(new VarInsnNode(Opcodes.ALOAD,1));
                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"dev/modbench/hooks/GameHooks","confirmed","(Ljava/lang/Object;)V",false));method.instructions.insert(hook);count++;
            }
            if(count!=1)throw new IllegalStateException("Modbench transaction hook expected once, found "+count);
            ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);return writer.toByteArray();
        }
        if(!transformedName.equals("net.minecraft.client.Minecraft"))return bytes;
        ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);int edits=0;
        for(MethodNode method:node.methods) {
            if(!method.desc.equals("(Z)V")||!java.util.Set.of("func_147115_a","sendClickBlockToController").contains(method.name))continue;
            InsnList gate=new InsnList();LabelNode proceed=new LabelNode();
            gate.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"dev/modbench/hooks/GameHooks","ownsNativeActions","()Z",false));
            gate.add(new JumpInsnNode(Opcodes.IFEQ,proceed));gate.add(new InsnNode(Opcodes.RETURN));
            gate.add(proceed);gate.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));method.instructions.insert(gate);edits++;
        }
        if(edits!=1)throw new IllegalStateException("Modbench native attack-loop hook expected once, found "+edits);
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);return writer.toByteArray();
    }
}
