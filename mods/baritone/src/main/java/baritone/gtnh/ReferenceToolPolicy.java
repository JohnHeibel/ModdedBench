// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

/** The source engine's door to the one tool answer and choice in MiningTools. */
public final class ReferenceToolPolicy {
    private ReferenceToolPolicy() {}
    /** What the game says one tick of a stack does to a block: the share of it broken, and whether it drops. */
    public record Answer(double strength,boolean harvest) {}
    /** The tool kind (MiningTools.toolKind) a running mining job's toolSlot forces, or null. It is the kind, not the slot:
     *  a swap onto the hotbar moves the tool, and durability changes with every swing. */
    public static volatile String forcedTool;
    /** Resolve the original slot once; durable resumes retain its kind after inventory changes. */
    static String remember(java.util.Map<String,Object> progress,java.util.function.Supplier<String> current){
        Object saved=progress.get("forcedTool");
        if(saved instanceof String kind)return kind;
        String kind=current.get();progress.put("forcedTool",kind);return kind;
    }
    /** The slot holding the forced tool, the selected one first; -1 when nothing is forced or it is gone. */
    public static int forced(net.minecraft.item.ItemStack[] stacks,int selected){
        String kind=forcedTool;if(kind==null)return -1;
        for(int i:MiningTools.order(selected,stacks.length))if(stacks[i]!=null&&kind.equals(MiningTools.toolKind(stacks[i])))return i;
        return -1;
    }
    public static boolean eligible(net.minecraft.item.ItemStack stack){return MiningTools.rejected(stack)==null;}
    /** The game's answer for each stack against a block, provisional off the game thread until it has answered
     *  (MiningTools.answers); null entries where there is no answer of either kind. */
    public static Answer[] answers(net.minecraft.item.ItemStack[] stacks,baritone.compat.IBlockState state){return MiningTools.answers(stacks,state.getBlock(),state.meta,state.x,state.y,state.z,state.hasAccess());}
    /** The one tool choice (MiningTools.pick) with `selected` preferred on ties; -1 when nothing breaks the block. */
    public static int pick(Answer[] answers,boolean[] eligible,int selected){return MiningTools.pick(answers,eligible,MiningTools.order(selected,answers.length));}
    /** Answers for blocks with a tile entity are per position; this is that position, or one value for all others. */
    public static long cell(baritone.compat.IBlockState state){return MiningTools.cell(state.getBlock(),state.meta,state.x,state.y,state.z,state.hasAccess());}
    /** On the game thread before a search, with the stacks it will ask about: warm their answers around (x,y,z). */
    public static void warm(net.minecraft.item.ItemStack[] stacks,int x,int y,int z){MiningTools.warm(stacks,x,y,z);}
    /** Called once per game tick, on the game thread. */
    public static void answer(){MiningTools.answer();}
}
