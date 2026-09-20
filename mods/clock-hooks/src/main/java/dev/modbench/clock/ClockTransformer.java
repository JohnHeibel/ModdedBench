// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.clock;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Gates complete Forge simulation ticks; packet servicing remains available while paused. */
public final class ClockTransformer implements IClassTransformer {
    private static final String HOOK = "dev/modbench/bridge/ClockHooks";
    @Override public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null) return null;
        boolean server = transformedName.equals("net.minecraft.server.MinecraftServer");
        boolean client = transformedName.equals("net.minecraft.client.Minecraft");
        boolean network = transformedName.equals("net.minecraft.network.NetworkManager");
        boolean controller = transformedName.equals("net.minecraft.client.multiplayer.PlayerControllerMP");
        boolean computer = transformedName.equals("li.cil.oc.server.machine.Machine");
        boolean gregtech = transformedName.equals("gregtech.api.threads.RunnableMachineUpdate")
            || transformedName.equals("gregtech.api.threads.RunnableCableUpdate");
        if (!server && !client && !network && !computer && !gregtech && !controller) return bytes;
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        if(gregtech) {
            MethodNode original=node.methods.stream().filter(m->m.name.equals("run") && m.desc.equals("()V")).findFirst()
                .orElseThrow(()->new IllegalStateException("GregTech update hook missing: "+transformedName));
            original.name="modbench$run";
            MethodNode wrapper=new MethodNode(Opcodes.ACC_PUBLIC,"run","()V",null,null);
            wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));
            wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"runGregTechUpdate","(Ljava/lang/Object;)V",false));
            wrapper.instructions.add(new InsnNode(Opcodes.RETURN));
            node.methods.add(wrapper);
            ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);return writer.toByteArray();
        }
        int edits = 0,frameEdits=0;
        for (MethodNode method : node.methods) {
            if(client&&method.desc.equals("()V")&&(method.name.equals("runGameLoop")||method.name.equals("func_71411_J"))) {
                // After native achievement/debug overlays, before the completed framebuffer is presented.
                // Keep this separate from the simulation-tick hook and its completeness check.
                for(AbstractInsnNode insn:method.instructions.toArray()) if(insn instanceof MethodInsnNode call
                    &&call.owner.equals("net/minecraft/client/shader/Framebuffer")&&call.desc.equals("()V")
                    &&(call.name.equals("unbindFramebuffer")||call.name.equals("func_147609_e"))) {
                    method.instructions.insertBefore(insn,new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"frameRendered","()V",false));frameEdits++;
                }
            }
            if(controller) {
                int action=-1,first=1;
                if(method.desc.equals("(IIII)V") || method.desc.equals("(IIII)Z")) {
                    if(java.util.Set.of("clickBlock","func_78743_b","onPlayerDamageBlock","func_78759_c","onPlayerDestroyBlock","func_78751_a").contains(method.name)) action=0;
                }
                if(method.name.equals("onPlayerRightClick") || method.name.equals("func_78760_a")) {action=1;first=4;}
                if(method.name.equals("sendUseItem") || method.name.equals("func_78769_a")) action=2;
                if(action<0) continue;
                InsnList head=new InsnList();LabelNode proceed=new LabelNode();
                head.add(new LdcInsnNode(action));
                for(int i=0;i<4;i++) head.add(action==2?new LdcInsnNode(i==3?-1:0):new VarInsnNode(Opcodes.ILOAD,first+i));
                head.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"blockAction","(IIIII)Z",false));
                head.add(new JumpInsnNode(Opcodes.IFNE,proceed));
                if(method.desc.endsWith("Z")) {head.add(new InsnNode(Opcodes.ICONST_0));head.add(new InsnNode(Opcodes.IRETURN));}
                else head.add(new InsnNode(Opcodes.RETURN));
                head.add(proceed);head.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));method.instructions.insert(head);edits++;
            } else if(computer && method.name.equals("<init>")) {
                for(AbstractInsnNode insn:method.instructions.toArray()) if(insn.getOpcode()==Opcodes.RETURN) {
                    InsnList register=new InsnList();register.add(new VarInsnNode(Opcodes.ALOAD,0));
                    register.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"registerComputer","(Ljava/lang/Object;)V",false));
                    method.instructions.insertBefore(insn,register);edits++;
                }
            } else if (network) {
                if((method.name.equals("scheduleOutboundPacket")||method.name.equals("func_150725_a"))&&method.desc.startsWith("(Lnet/minecraft/network/Packet;")) {
                    InsnList head=new InsnList();head.add(new VarInsnNode(Opcodes.ALOAD,1));
                    head.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"outgoing","(Ljava/lang/Object;)V",false));method.instructions.insert(head);
                }

                if(method.desc.equals("()V") && (method.name.equals("processReceivedPackets") || method.name.equals("func_74428_b"))) {
                    InsnList head=new InsnList();head.add(new VarInsnNode(Opcodes.ALOAD,0));
                    head.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"beforeNetwork","(Ljava/lang/Object;)V",false));
                    method.instructions.insert(head);
                }
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode call && call.owner.equals("net/minecraft/network/Packet")
                        && (call.name.equals("processPacket") || call.name.equals("func_148833_a"))
                        && call.desc.equals("(Lnet/minecraft/network/INetHandler;)V")) {
                        method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "dev/modbench/clock/ClockPackets", "dispatch",
                            "(Lnet/minecraft/network/Packet;Lnet/minecraft/network/INetHandler;)V", false));
                        edits++;
                    }
                }
            } else if (method.desc.equals("()V") && (server
                ? method.name.equals("tick") || method.name.equals("func_71217_p")
                : method.name.equals("runTick") || method.name.equals("func_71407_l"))) {
                String side = server ? "Server" : "Client";
                // Existing frames are preserved. The new entry branch has an empty stack.
                for (AbstractInsnNode insn : method.instructions.toArray()) if (insn.getOpcode() == Opcodes.RETURN) {
                    method.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "after"+side, "()V", false));
                }
                InsnList head = new InsnList(); LabelNode proceed = new LabelNode();
                head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "before"+side, "()Z", false));
                head.add(new JumpInsnNode(Opcodes.IFNE, proceed)); head.add(new InsnNode(Opcodes.RETURN));
                head.add(proceed); head.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                method.instructions.insert(head); edits++;
            }
        }
        if(controller && edits!=5) throw new IllegalStateException("Modbench protection hooks missing: expected 5, got "+edits);
        if(client&&frameEdits!=1) throw new IllegalStateException("Modbench presentation hook missing: expected 1, got "+frameEdits);
        if (edits == 0) throw new IllegalStateException("Modbench clock hook missing: " + transformedName);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
