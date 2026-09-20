/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.modbench.hooks;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.*;
import java.nio.charset.StandardCharsets;

/** Rewrites input call sites, including GTNH's lwjgl3ify owners and native keyboard events. */
public final class GuiInputTransformer implements IClassTransformer {
    private static final String HOOKS="dev/modbench/hooks/UiHooks";
    private static final byte[] NEEDLE="org/lwjgl/input/".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MODERN="org/lwjglx/input/".getBytes(StandardCharsets.US_ASCII);
    private static int logged;
    private static int asmApi() {
        try {return Opcodes.class.getField("ASM9").getInt(null);}catch(ReflectiveOperationException legacy) {return Opcodes.ASM5;}
    }
    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || name == null || name.startsWith("dev.modbench.") || name.startsWith("org.lwjgl.") || name.startsWith("org.lwjglx.")) {
            return bytes;
        }
        if (!contains(bytes, NEEDLE) && !contains(bytes, MODERN)) {
            return bytes;
        }
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            final int[] sites = {0};
            reader.accept(new ClassVisitor(asmApi(), writer) {
                @Override
                public MethodVisitor visitMethod(int access, String mname, String desc, String signature, String[] exceptions) {
                    return new MethodVisitor(asmApi(), super.visitMethod(access, mname, desc, signature, exceptions)) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String n, String d, boolean itf) {
                            if(opcode==Opcodes.INVOKEVIRTUAL&&owner.equals("net/minecraft/client/gui/GuiScreen")
                                &&(n.equals("handleKeyboardInput")||n.equals("func_146282_l"))&&d.equals("()V")){
                                // lwjgl3ify's later cancellable mixin replaces this
                                // method with its GLFW event reader. Synthetic events
                                // must enter the native virtual keyTyped dispatch before it.
                                sites[0]++;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,HOOKS,"handleKeyboardInput","(Ljava/lang/Object;)V",false);
                                return;
                            }
                            if (opcode == Opcodes.INVOKESTATIC) {
                                String target = map(owner, n, d);
                                if (target != null) {
                                    sites[0]++;
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, target, d, false);
                                    return;
                                }
                            }
                            super.visitMethodInsn(opcode, owner, n, d, itf);
                        }
                    };
                }
            }, 0);
            if (sites[0] == 0) {
                return bytes;
            }
            Registry.patched(sites[0]);
            if (logged < 8) {
                logged++;
                System.out.println("[ModdedBench/input] patched " + sites[0] + " input call site(s) in " + transformedName);
            }
            return writer.toByteArray();
        } catch (Throwable t) {
            System.out.println("[ModdedBench/input] failed to patch " + transformedName + ": " + t);
            return bytes;
        }
    }

    static String map(String owner, String name, String desc) {
        if ("org/lwjgl/input/Keyboard".equals(owner)||"org/lwjglx/input/Keyboard".equals(owner)) {
            if ("isKeyDown".equals(name) && "(I)Z".equals(desc)) {
                return "keyDown";
            }
            return switch(name+desc) {
                case "next()Z" -> "keyNext";
                case "getEventKey()I" -> "eventKey";
                case "getEventCharacter()C" -> "eventChar";
                case "getEventKeyState()Z" -> "eventKeyState";
                case "isRepeatEvent()Z" -> "repeatEvent";
                default -> null;
            };
        }
        if ("org/lwjgl/input/Mouse".equals(owner)||"org/lwjglx/input/Mouse".equals(owner)) {
            switch (name) {
                case "isButtonDown":
                    return "(I)Z".equals(desc) ? "buttonDown" : null;
                // event queue: lets a synthetic press/release/move/wheel flow through Minecraft.runTick's own
                // `while (Mouse.next())` loop and every handleMouseInput() override, exactly like a real click
                case "next":
                    return "()Z".equals(desc) ? "next" : null;
                case "getEventX":
                    return "()I".equals(desc) ? "eventX" : null;
                case "getEventY":
                    return "()I".equals(desc) ? "eventY" : null;
                case "getEventButton":
                    return "()I".equals(desc) ? "eventButton" : null;
                case "getEventButtonState":
                    return "()Z".equals(desc) ? "eventButtonState" : null;
                case "getDWheel":
                    return "()I".equals(desc) ? "dWheel" : null;
                case "getEventDWheel":
                    return "()I".equals(desc) ? "eventDWheel" : null;
                case "getX":
                    return "()I".equals(desc) ? "mouseX" : null;
                case "getY":
                    return "()I".equals(desc) ? "mouseY" : null;
                default:
                    return null;
            }
        }
        return null;
    }

    private static boolean contains(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** Counts are recorded here (loaded early, before the bridge package) and read by LwjglHooks lazily. */
    public static final class Registry {
        public static volatile int classes;
        public static volatile int sites;

        static void patched(int n) {
            classes++;
            sites += n;
        }
    }
}
