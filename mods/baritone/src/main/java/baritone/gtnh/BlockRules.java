// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.Baritone;
import baritone.api.Settings;
import baritone.compat.IBlockState;
import baritone.compat.Registry;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.block.Block;

/** The model's block lists for the path search (hazards, standOn, neverStandOn), and a count of every time one, or a
 *  never-break rule, decided something: what a receipt shows as pathRules. An entry is "modid:name" (every meta) or
 *  "modid:name:meta". The search reads the lists on its own thread; each value a list is set to is parsed once. */
public final class BlockRules {
    private BlockRules(){}
    private record Parsed(List<String> source,Set<Block> any,Set<IBlockState.StateKey> exact) {
        boolean has(Block b,int meta){return any.contains(b)||exact.contains(new IBlockState.StateKey(b,meta));}
    }
    private static final Map<String,Parsed> parsed=new ConcurrentHashMap<>();
    private static final Map<String,Integer> applied=new ConcurrentHashMap<>();
    static final Set<String> LISTS=Set.of("hazards","standon","neverstandon");

    /** A block the search never enters or stands on. */
    public static boolean hazard(IBlockState s){return hit(Baritone.settings().hazards,s);}
    /** TRUE: the model said stand on it; FALSE: it said never, or it is a hazard; null: the game's shape decides. */
    public static Boolean standOn(IBlockState s){
        var settings=Baritone.settings();
        if(hit(settings.neverStandOn,s))return false;
        if(hit(settings.standOn,s))return true;
        return hazard(s)?false:null;
    }
    public static boolean neverBreak(IBlockState s){
        boolean never=Baritone.settings().blocksToDisallowBreaking.value.contains(s.getBlock());
        if(never)count("blocksToDisallowBreaking",s);
        return never;
    }
    private static boolean hit(Settings.Setting<List<String>> setting,IBlockState s){
        Parsed p=parsed.get(setting.getName());
        if(p==null||p.source()!=setting.value){p=parse(setting.value);parsed.put(setting.getName(),p);}
        boolean in=p.has(s.getBlock(),s.meta);
        if(in)count(setting.getName(),s);
        return in;
    }
    private static void count(String rule,IBlockState s){
        String key=rule+" "+Registry.name(s.getBlock())+":"+s.meta;
        if(applied.size()<128||applied.containsKey(key))applied.merge(key,1,Integer::sum);
    }
    private static Parsed parse(List<String> entries){
        Set<Block> any=new HashSet<>();Set<IBlockState.StateKey> exact=new HashSet<>();
        for(String entry:entries){
            try{Object[] e=entry(entry);if(e[1]==null)any.add((Block)e[0]);else exact.add(new IBlockState.StateKey((Block)e[0],(Integer)e[1]));}
            catch(IllegalArgumentException invalid){/* validate() refuses these when the model sets them */}
        }
        return new Parsed(entries,any,exact);
    }
    private static Object[] entry(String raw){
        String id=raw.trim();Integer meta=null;int colon=id.lastIndexOf(':');
        if(colon>0&&id.indexOf(':')!=colon&&id.substring(colon+1).chars().allMatch(Character::isDigit)){
            meta=Integer.parseInt(id.substring(colon+1));id=id.substring(0,colon);
            if(meta>15)throw new IllegalArgumentException("block metadata is 0..15: "+raw);
        }
        return new Object[]{Registry.block(id),meta};
    }
    /** Refuse an unknown block or a malformed entry when the model sets a list, instead of ignoring it in the search. */
    static void validate(String setting,Object value){
        if(LISTS.contains(setting)&&value instanceof List<?> list)for(Object entry:list)entry(String.valueOf(entry));
    }
    /** How often each rule decided something since the last reset, as {rule:{block:count}}; counts are search checks, not cells. */
    public static Map<String,Map<String,Integer>> applied(){
        Map<String,Map<String,Integer>> out=new TreeMap<>();
        applied.forEach((k,v)->{int space=k.indexOf(' ');out.computeIfAbsent(k.substring(0,space),r->new TreeMap<>()).put(k.substring(space+1),v);});
        return out;
    }
    public static void reset(){applied.clear();}
}
