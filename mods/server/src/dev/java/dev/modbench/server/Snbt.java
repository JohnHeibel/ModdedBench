// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.server;

import java.util.regex.Pattern;
import net.minecraft.nbt.*;

/**
 * NBT as text that reads back as the same tag, for capturing a tile entity and placing it again.
 * The game's own pair cannot do that in 1.7.10: toString writes a byte array as "[6 bytes]" and strings unescaped, and
 * JsonToNBT has no byte array, reads "" as two quote characters, [] as a list and [5,] as a string. This is that form
 * with those holes closed: [B;1,2] and [I;1,2] for arrays, strings always quoted with \" and \\, odd keys quoted, keys
 * sorted. The reader also takes what the game writes and what JsonToNBT takes (bare strings, 0:{..} list elements,
 * trailing commas, [1,2,] as an int array).
 */
final class Snbt {
    private static final Pattern BARE_KEY=Pattern.compile("[A-Za-z0-9_.+-]+"),DECIMAL=Pattern.compile("[-+]?[0-9]*\\.?[0-9]+"),
        NUMBER=Pattern.compile("[-+]?(?:[0-9]*\\.?[0-9]+(?:[eE][-+]?[0-9]+)?|NaN|Infinity)");
    private final String s;
    private int i;
    private Snbt(String s){this.s=s;}

    static String write(NBTBase tag){StringBuilder out=new StringBuilder();write(tag,out);return out.toString();}
    private static void write(NBTBase tag,StringBuilder out) {
        if(tag instanceof NBTTagCompound c) {
            out.append('{');
            for(Object key:new java.util.TreeSet<Object>(c.func_150296_c())) {   // sorted: the same tag is always the same text
                String k=(String)key;if(BARE_KEY.matcher(k).matches())out.append(k);else quote(k,out);
                out.append(':');write(c.getTag(k),out);out.append(',');
            }
            close(out,'}');
        } else if(tag instanceof NBTTagList l) {
            out.append('[');for(int n=0;n<l.tagCount();n++){out.append(n).append(':');write(NbtSnapshots.element(l,n),out);out.append(',');}close(out,']');
        } else if(tag instanceof NBTTagByteArray a) {out.append("[B;");for(byte b:a.func_150292_c())out.append(b).append(',');close(out,']');}
        else if(tag instanceof NBTTagIntArray a) {out.append("[I;");for(int n:a.func_150302_c())out.append(n).append(',');close(out,']');}
        else if(tag instanceof NBTTagString t) quote(t.func_150285_a_(),out);
        else out.append(tag);   // numbers as the game writes them: 1b 2s 3 4L 5.0f 6.0d
    }
    private static void close(StringBuilder out,char c){if(out.charAt(out.length()-1)==',')out.setCharAt(out.length()-1,c);else out.append(c);}
    private static void quote(String text,StringBuilder out){out.append('"').append(text.replace("\\","\\\\").replace("\"","\\\"")).append('"');}

    static NBTBase read(String text) {
        Snbt in=new Snbt(text);NBTBase tag=in.value();
        if(in.more()) throw in.bad("text after the tag");
        return tag;
    }
    private IllegalArgumentException bad(String what){return new IllegalArgumentException("NBT text: "+what+" at character "+i);}
    private boolean more(){while(i<s.length()&&Character.isWhitespace(s.charAt(i)))i++;return i<s.length();}
    private char peek(){if(!more())throw bad("unexpected end");return s.charAt(i);}
    private boolean take(char c){if(peek()!=c)return false;i++;return true;}
    private String bare(String ends){int from=i;while(i<s.length()&&ends.indexOf(s.charAt(i))<0)i++;return s.substring(from,i).trim();}
    private String quoted() {
        StringBuilder out=new StringBuilder();
        for(i++;i<s.length();i++) {
            char c=s.charAt(i);if(c=='"'){i++;return out.toString();}
            // Only \" and \\ are escapes: any other backslash is the game's own unescaped text.
            if(c=='\\'&&i+1<s.length()&&"\"\\".indexOf(s.charAt(i+1))>=0)c=s.charAt(++i);
            out.append(c);
        }
        throw bad("unclosed string");
    }
    private NBTBase value() {
        char c=peek();
        if(c=='"') return new NBTTagString(quoted());
        if(c=='{') {
            NBTTagCompound out=new NBTTagCompound();i++;
            while(!take('}')) {
                String key=peek()=='"'?quoted():bare(":");
                if(!take(':')) throw bad("':' expected after a key");
                out.setTag(key,value());
                if(!take(',')&&peek()!='}') throw bad("',' or '}' expected");
            }
            return out;
        }
        if(c!='[') return scalar(bare(",}]"));
        i++;
        if(s.startsWith("B;",i)||s.startsWith("I;",i)) {
            boolean bytes=s.charAt(i)=='B';i+=2;String body=bare("]");
            if(i++>=s.length()) throw bad("unclosed array");
            String[] parts=body.isEmpty()?new String[0]:body.split(",");byte[] b=new byte[parts.length];int[] n=new int[parts.length];
            try {for(int k=0;k<parts.length;k++){if(bytes)b[k]=Byte.parseByte(parts[k].trim());else n[k]=Integer.parseInt(parts[k].trim());}}
            catch(NumberFormatException e) {throw bad("array element is not a number before");}
            return bytes?new NBTTagByteArray(b):new NBTTagIntArray(n);
        }
        NBTTagList out=new NBTTagList();boolean numbered=false;
        while(!take(']')) {
            int from=i;while(i<s.length()&&Character.isDigit(s.charAt(i)))i++;
            if(i>from&&i<s.length()&&s.charAt(i)==':'){i++;numbered=true;} else i=from;   // the game numbers its list elements
            NBTBase item=value();
            // NBTTagList.appendTag drops an element of another type with a warning on stderr; refuse instead.
            if(out.tagCount()>0&&item.getId()!=out.func_150303_d()) throw bad("list elements differ in type before");
            out.appendTag(item);
            if(!take(',')&&peek()!=']') throw bad("',' or ']' expected");
        }
        if(numbered||out.tagCount()==0||out.func_150303_d()!=3) return out;
        int[] n=new int[out.tagCount()];for(int k=0;k<n.length;k++)n[k]=((NBTTagInt)NbtSnapshots.element(out,k)).func_150287_d();
        return new NBTTagIntArray(n);   // as JsonToNBT: bare integers without element numbers are an int array
    }
    private static NBTBase scalar(String t) {
        if(t.equalsIgnoreCase("true")||t.equalsIgnoreCase("false")) return new NBTTagByte((byte)(t.equalsIgnoreCase("true")?1:0));
        if(!t.isEmpty()) try {
            String body=t.substring(0,t.length()-1);
            switch(NUMBER.matcher(body).matches()?Character.toLowerCase(t.charAt(t.length()-1)):' ') {
                case 'b': return new NBTTagByte(Byte.parseByte(body));
                case 's': return new NBTTagShort(Short.parseShort(body));
                case 'l': return new NBTTagLong(Long.parseLong(body));
                case 'f': return new NBTTagFloat(Float.parseFloat(body));
                case 'd': return new NBTTagDouble(Double.parseDouble(body));
                default: if(DECIMAL.matcher(t).matches()) return t.matches("[-+]?[0-9]+")?new NBTTagInt(Integer.parseInt(t)):new NBTTagDouble(Double.parseDouble(t));
            }
        } catch(NumberFormatException notANumber) {/* 300b, 1.5s: text, as in JsonToNBT */}
        return new NBTTagString(t);
    }
}
