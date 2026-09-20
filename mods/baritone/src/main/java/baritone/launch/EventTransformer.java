// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.launch;

import java.util.Set;
import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Small fail-fast native boundaries corresponding to the upstream mixin events. */
public final class EventTransformer implements IClassTransformer {
    private static final String HOOK="baritone/compat/NativeEvents";
    private static final Set<String> TARGETS=Set.of("net.minecraft.client.Minecraft","net.minecraft.client.multiplayer.WorldClient",
        "net.minecraft.network.NetworkManager","net.minecraft.entity.Entity","net.minecraft.entity.EntityLivingBase",
        "net.minecraft.client.entity.EntityPlayerSP","net.minecraft.client.entity.EntityClientPlayerMP","net.minecraft.client.gui.GuiChat","dev.modbench.clock.ClockPackets");
    @Override public byte[] transform(String name,String transformed,byte[] bytes){
        if(bytes==null||!TARGETS.contains(transformed))return bytes;
        ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);int edits=0,expected=1;
        for(MethodNode m:node.methods){
            switch(transformed){
                case "net.minecraft.client.Minecraft":
                    if(match(m,"(Lnet/minecraft/client/multiplayer/WorldClient;Ljava/lang/String;)V","loadWorld","func_71353_a")){
                        phases(m,post->{InsnList list=new InsnList();load(list,Opcodes.ALOAD,1);flag(list,post);call(list,"world","(Ljava/lang/Object;Z)V");return list;});edits++;
                    }break;
                case "net.minecraft.client.multiplayer.WorldClient":
                    expected=2;
                    if(match(m,"(IIZ)V","doPreChunk","func_73025_a")){
                        phases(m,post->{InsnList l=new InsnList();load(l,Opcodes.ALOAD,0);for(int i=1;i<=3;i++)load(l,Opcodes.ILOAD,i);flag(l,post);call(l,"chunk","(Ljava/lang/Object;IIZZ)V");return l;});edits++;
                    }
                    if(match(m,"(IIILnet/minecraft/block/Block;I)Z","func_147492_c")){
                        for(AbstractInsnNode instruction:m.instructions.toArray())if(instruction.getOpcode()==Opcodes.IRETURN){
                            InsnList l=new InsnList();l.add(new InsnNode(Opcodes.DUP));load(l,Opcodes.ALOAD,0);for(int i=1;i<=3;i++)load(l,Opcodes.ILOAD,i);call(l,"block","(ZLjava/lang/Object;III)V");m.instructions.insertBefore(instruction,l);
                        }edits++;
                    }break;
                case "net.minecraft.network.NetworkManager":
                    expected=2;
                    if(match(m,"(Lnet/minecraft/network/Packet;[Lio/netty/util/concurrent/GenericFutureListener;)V","scheduleOutboundPacket","func_150725_a")){
                        phases(m,post->{InsnList l=new InsnList();load(l,Opcodes.ALOAD,0);load(l,Opcodes.ALOAD,1);flag(l,post);call(l,"send","(Ljava/lang/Object;Ljava/lang/Object;Z)V");return l;});edits++;
                    }
                    if(match(m,"()V","processReceivedPackets","func_74428_b")){
                        int calls=0;
                        for(AbstractInsnNode instruction:m.instructions.toArray())if(instruction instanceof MethodInsnNode c&&Set.of("processPacket","func_148833_a").contains(c.name)&&c.desc.equals("(Lnet/minecraft/network/INetHandler;)V")){
                            m.instructions.set(c,new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"receive","(Lnet/minecraft/network/Packet;Lnet/minecraft/network/INetHandler;)V",false));calls++;
                        }else if(instruction instanceof MethodInsnNode c&&c.owner.equals("dev/modbench/clock/ClockPackets")&&c.name.equals("dispatch")){
                            // ClockPackets is transformed separately, AFTER its admission
                            // decision. Never bypass the existing pause/transaction gate.
                            calls++;
                        }require(calls==1,transformed+" packet processing sites="+calls);edits++;
                    }break;
                case "dev.modbench.clock.ClockPackets":
                    if(match(m,"(Lnet/minecraft/network/Packet;Lnet/minecraft/network/INetHandler;)V","dispatch")){
                        for(AbstractInsnNode instruction:m.instructions.toArray())if(instruction instanceof MethodInsnNode c&&Set.of("processPacket","func_148833_a").contains(c.name)&&c.desc.equals("(Lnet/minecraft/network/INetHandler;)V")){
                            m.instructions.set(c,new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"receive","(Lnet/minecraft/network/Packet;Lnet/minecraft/network/INetHandler;)V",false));edits++;
                        }
                    }break;
                case "net.minecraft.entity.Entity":
                case "net.minecraft.entity.EntityLivingBase":
                    boolean jump=transformed.endsWith("EntityLivingBase");
                    if(jump?match(m,"()V","jump","func_70664_aZ"):match(m,"(FFF)V","moveFlying","func_70060_a")){
                        int fields=0;
                        for(AbstractInsnNode instruction:m.instructions.toArray())if(instruction instanceof FieldInsnNode f&&f.getOpcode()==Opcodes.GETFIELD&&f.desc.equals("F")&&Set.of("rotationYaw","field_70177_z").contains(f.name)){
                            InsnList l=new InsnList();load(l,Opcodes.ALOAD,0);flag(l,jump);call(l,"yaw","(FLjava/lang/Object;I)F");m.instructions.insert(instruction,l);fields++;
                        }require(fields>0,transformed+" missing yaw reads");edits++;
                    }break;
                case "net.minecraft.client.entity.EntityPlayerSP":
                    // PlayerAPI moves the native body to localOnLivingUpdate and
                    // leaves a dispatch wrapper under the Minecraft name.
                    boolean playerApi=node.methods.stream().anyMatch(method->method.name.equals("localOnLivingUpdate")&&method.desc.equals("()V"));
                    if(playerApi?match(m,"()V","localOnLivingUpdate"):match(m,"()V","onLivingUpdate","func_70636_d")){
                        int calls=0;
                        for(AbstractInsnNode instruction:m.instructions.toArray())if(instruction instanceof MethodInsnNode c&&c.owner.equals("net/minecraft/client/settings/KeyBinding")&&Set.of("getIsKeyPressed","func_151470_d").contains(c.name)&&c.desc.equals("()Z")){
                            m.instructions.set(c,new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"sprint","(Lnet/minecraft/client/settings/KeyBinding;)Z",false));calls++;
                        }require(calls>0,"missing sprint key reads");edits++;
                    }break;
                case "net.minecraft.client.entity.EntityClientPlayerMP":
                    if(match(m,"(Ljava/lang/String;)V","sendChatMessage","func_71165_d")){m.instructions.insert(gate("chat"));edits++;}break;
                case "net.minecraft.client.gui.GuiChat":
                    if(match(m,"(Ljava/lang/String;Ljava/lang/String;)V","func_146405_a")){
                        m.instructions.insert(gate("tab"));
                        int calls=0;
                        for(AbstractInsnNode instruction:m.instructions.toArray())if(instruction instanceof MethodInsnNode c&&c.owner.equals("net/minecraftforge/client/ClientCommandHandler")&&c.name.equals("autoComplete")){
                            InsnList hook=new InsnList();call(hook,"finishTab","()V");m.instructions.insert(c,hook);calls++;
                        }require(calls==1,"missing native local completion boundary");edits++;
                    }break;
            }
        }
        require(edits==expected,transformed+" expected "+expected+" event methods, found "+edits);
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);return writer.toByteArray();
    }
    private static boolean match(MethodNode m,String desc,String... names){return m.desc.equals(desc)&&Set.of(names).contains(m.name);}
    private static void require(boolean condition,String detail){if(!condition)throw new IllegalStateException("Baritone event hook: "+detail);}
    private static void load(InsnList l,int opcode,int slot){l.add(new VarInsnNode(opcode,slot));}
    private static void flag(InsnList l,boolean value){l.add(new InsnNode(value?Opcodes.ICONST_1:Opcodes.ICONST_0));}
    private static void call(InsnList l,String name,String desc){l.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,name,desc,false));}
    private static InsnList gate(String method){
        InsnList l=new InsnList();load(l,Opcodes.ALOAD,1);call(l,method,"(Ljava/lang/String;)Z");
        LabelNode proceed=new LabelNode();l.add(new JumpInsnNode(Opcodes.IFEQ,proceed));l.add(new InsnNode(Opcodes.RETURN));l.add(proceed);
        l.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));return l;
    }
    private static void phases(MethodNode m,java.util.function.Function<Boolean,InsnList> hook){
        for(AbstractInsnNode instruction:m.instructions.toArray())if(instruction.getOpcode()==Opcodes.RETURN)m.instructions.insertBefore(instruction,hook.apply(true));
        m.instructions.insert(hook.apply(false));
    }
}
