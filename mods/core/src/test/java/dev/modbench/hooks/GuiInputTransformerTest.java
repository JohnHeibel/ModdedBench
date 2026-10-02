// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.hooks;

import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;
import static org.junit.Assert.*;

public class GuiInputTransformerTest {
    /** A class whose one method reads the keyboard and mouse queues, as runTick and a GUI screen do. */
    private byte[] reader(String type, String owner) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC, type, null, "java/lang/Object", null);
        MethodVisitor body = writer.visitMethod(Opcodes.ACC_PUBLIC, "runTick", "()V", null, null);
        body.visitCode();
        for (String[] call : new String[][]{{"Keyboard", "next", "()Z"}, {"Keyboard", "getEventKey", "()I"}, {"Keyboard", "getEventKeyState", "()Z"}, {"Mouse", "next", "()Z"}, {"Mouse", "getEventButton", "()I"}}) {
            body.visitMethodInsn(Opcodes.INVOKESTATIC, owner + call[0], call[1], call[2], false);
            body.visitInsn(Opcodes.POP);
        }
        body.visitInsn(Opcodes.RETURN);
        body.visitMaxs(1, 1);
        body.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private List<String> calls(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> out = new ArrayList<>();
        for (AbstractInsnNode insn : node.methods.get(0).instructions.toArray())
            if (insn instanceof MethodInsnNode call) out.add(call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name);
        return out;
    }

    @Test public void minecraftKeepsItsKeyboardQueueForLwjgl3ifysKeyBindingSlice() {
        for (String owner : new String[]{"org/lwjgl/input/", "org/lwjglx/input/"}) {
            byte[] out = new GuiInputTransformer().transform("net.minecraft.client.Minecraft", "net.minecraft.client.Minecraft", reader("net/minecraft/client/Minecraft", owner));
            assertEquals(List.of("Keyboard.next", "Keyboard.getEventKey", "Keyboard.getEventKeyState", "UiHooks.next", "UiHooks.eventButton"), calls(out));
        }
    }

    @Test public void screensStillTakeSyntheticKeys() {
        byte[] out = new GuiInputTransformer().transform("some.mod.Screen", "some.mod.Screen", reader("some/mod/Screen", "org/lwjgl/input/"));
        assertEquals(List.of("UiHooks.keyNext", "UiHooks.eventKey", "UiHooks.eventKeyState", "UiHooks.next", "UiHooks.eventButton"), calls(out));
    }
}
