// SPDX-License-Identifier: MIT
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.server;

import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The text form a captured tile entity travels in must read back as the tag it was written from. */
public class SnbtTest {
    private static final String[] ODD={"","a\"b","ends in a backslash\\","\\\"","x:y,z","{[]}"," lead and trail ","1b","true","[3 bytes]","0:zero","C:\\path","line\nbreak","\u706b\uD83D\uDE80"};

    /** Every tag type, lists of compounds and of lists, the empty cases, and strings and keys that look like syntax. */
    private static NBTTagCompound everything() {
        NBTTagCompound root=new NBTTagCompound(),inner=new NBTTagCompound();
        root.setByte("byte",(byte)-2);root.setShort("short",(short)-300);root.setInteger("int",123456);root.setLong("long",-900719925474099L);
        root.setFloat("float",1.5f);root.setFloat("smallFloat",1.0E-4f);root.setDouble("double",-2.25);root.setDouble("bigDouble",1.0E10);
        root.setByteArray("bytes",new byte[]{15,-128,127,0});root.setByteArray("noBytes",new byte[0]);
        root.setIntArray("ints",new int[]{1,-2,3});root.setIntArray("oneInt",new int[]{5});root.setIntArray("noInts",new int[0]);
        root.setTag("emptyList",new NBTTagList());root.setTag("emptyCompound",new NBTTagCompound());
        NBTTagList items=new NBTTagList(),strings=new NBTTagList(),lists=new NBTTagList(),numbers=new NBTTagList(),doubles=new NBTTagList();
        for(int slot=0;slot<3;slot++) {
            NBTTagCompound item=new NBTTagCompound(),tag=new NBTTagCompound();NBTTagList lore=new NBTTagList();
            lore.appendTag(new NBTTagString("line "+slot));tag.setTag("Lore",lore);tag.setLong("MaxDamage",25600L);
            item.setByte("Slot",(byte)slot);item.setShort("id",(short)(4000+slot));item.setByte("Count",(byte)64);item.setTag("tag",tag);items.appendTag(item);
            NBTTagList row=new NBTTagList();for(int n=0;n<=slot;n++)row.appendTag(new NBTTagInt(n));lists.appendTag(row);
            numbers.appendTag(new NBTTagInt(slot));doubles.appendTag(new NBTTagDouble(slot/4.0));
        }
        for(String text:ODD) {strings.appendTag(new NBTTagString(text));inner.setString(text,text);}
        inner.setInteger("GT.ToolStats",1);inner.setInteger("with space",2);
        root.setTag("Items",items);root.setTag("strings",strings);root.setTag("lists",lists);root.setTag("numbers",numbers);root.setTag("doubles",doubles);root.setTag("odd",inner);
        return root;
    }

    @Test public void everyTagReadsBackAsItWasWritten() {
        NBTTagCompound tag=everything();String text=Snbt.write(tag);
        assertEquals(tag,Snbt.read(text));
        assertEquals(text,Snbt.write(Snbt.read(text)));
        assertTrue(text,text.contains("bytes:[B;15,-128,127,0]")&&text.contains("noBytes:[B;]")&&text.contains("noInts:[I;]")&&text.contains("oneInt:[I;5]")&&text.contains("emptyList:[]"));
    }

    /** The text the Python side of the smoke suite parses (harness/tests/test_scene_diff.py holds the same line). */
    @Test public void writesOneCanonicalText() {
        NBTTagCompound tag=new NBTTagCompound(),first=new NBTTagCompound(),second=new NBTTagCompound(),inner=new NBTTagCompound(),display=new NBTTagCompound();NBTTagList items=new NBTTagList();
        display.setString("Name","a \"q\" , } ] : b\\");inner.setTag("display",display);
        first.setByte("Slot",(byte)0);first.setShort("id",(short)1);first.setByte("Count",(byte)64);first.setTag("tag",inner);items.appendTag(first);
        second.setByte("Slot",(byte)5);second.setShort("id",(short)2);second.setByte("Count",(byte)1);items.appendTag(second);
        tag.setTag("Items",items);tag.setByteArray("bytes",new byte[]{1,-2});tag.setTag("empty",new NBTTagList());tag.setFloat("f",1.0E-4f);
        tag.setString("id","Chest");tag.setIntArray("ints",new int[0]);tag.setString("odd key","");tag.setInteger("x",10);
        String text="{Items:[0:{Count:64b,Slot:0b,id:1s,tag:{display:{Name:\"a \\\"q\\\" , } ] : b\\\\\"}}},1:{Count:1b,Slot:5b,id:2s}],bytes:[B;1,-2],empty:[],f:1.0E-4f,id:\"Chest\",ints:[I;],\"odd key\":\"\",x:10}";
        assertEquals(text,Snbt.write(tag));
        assertEquals(tag,Snbt.read(text));
    }

    @Test public void theSameTagIsTheSameTextWhateverItsKeyOrder() {
        NBTTagCompound a=new NBTTagCompound(),b=new NBTTagCompound();
        for(int n=0;n<40;n++) {a.setInteger("key"+n,n);b.setInteger("key"+(39-n),39-n);}
        assertEquals(Snbt.write(a),Snbt.write(b));
    }

    @Test public void numbersThatAreNotFiniteOrPlainSurvive() {
        // NaN is never equal to itself as a tag, so these are compared as text.
        for(NBTBase tag:new NBTBase[]{new NBTTagFloat(Float.NaN),new NBTTagFloat(Float.NEGATIVE_INFINITY),new NBTTagDouble(Double.POSITIVE_INFINITY),new NBTTagDouble(4.9E-324),new NBTTagFloat(3.4028235E38f)})
            assertEquals(tag.toString(),Snbt.read(Snbt.write(tag)).toString());
        assertEquals(5,Snbt.read(Snbt.write(new NBTTagFloat(Float.NaN))).getId());
    }

    /** Why this form exists: each of these is lost between the game's toString and its own JsonToNBT. */
    @Test public void theGamesOwnTextLosesTheseTags() throws Exception {
        NBTTagCompound bytes=new NBTTagCompound(),empty=new NBTTagCompound(),oneInt=new NBTTagCompound(),noInts=new NBTTagCompound(),exponent=new NBTTagCompound();
        bytes.setByteArray("a",new byte[]{1,2,3});empty.setString("a","");oneInt.setIntArray("a",new int[]{5});noInts.setIntArray("a",new int[0]);exponent.setFloat("a",1.0E-4f);
        assertEquals("{a:[3 bytes],}",bytes.toString());
        for(NBTTagCompound tag:new NBTTagCompound[]{bytes,empty,oneInt,noInts,exponent}) {
            assertNotEquals(tag.toString(),tag,JsonToNBT.func_150315_a(tag.toString()));
            assertEquals(tag,Snbt.read(Snbt.write(tag)));
        }
    }

    @Test public void readsWhatTheGameWritesAndWhatJsonToNbtTakes() throws Exception {
        NBTTagCompound tag=new NBTTagCompound(),stats=new NBTTagCompound();NBTTagList items=new NBTTagList();
        for(int slot=0;slot<2;slot++) {NBTTagCompound item=new NBTTagCompound();item.setByte("Slot",(byte)slot);item.setShort("id",(short)1);item.setByte("Count",(byte)64);items.appendTag(item);}
        stats.setString("PrimaryMaterial","Iron");stats.setLong("MaxDamage",25600L);stats.setLong("Damage",0L);
        tag.setTag("Items",items);tag.setTag("GT.ToolStats",stats);tag.setIntArray("ints",new int[]{1,2,3});tag.setString("id","minecraft:chest");tag.setDouble("d",0.5);tag.setFloat("f",2.0f);
        assertEquals(tag,JsonToNBT.func_150315_a(tag.toString()));   // the game reads this one itself: the reader below must agree with it
        assertEquals(tag,Snbt.read(tag.toString()));
        String hand=" { Items:[{Slot:0b,id:1s,Count:64b},{Slot:1b,id:1s,Count:64b}], GT.ToolStats:{PrimaryMaterial:Iron,MaxDamage:25600L,Damage:0L,},ints:[1, 2, 3],id:\"minecraft:chest\",d:0.5,f:2.0F } ";
        assertEquals(tag,JsonToNBT.func_150315_a(hand));
        assertEquals(tag,Snbt.read(hand));
        NBTTagCompound flags=(NBTTagCompound)Snbt.read("{on:true,off:FALSE,big:300b,empty:,name:Stone Bricks,path:\"C:\\dir\"}");
        assertEquals(1,flags.getByte("on"));assertEquals(0,flags.getByte("off"));assertEquals(1,flags.getTag("off").getId());
        assertEquals("300b",flags.getString("big"));assertEquals("",flags.getString("empty"));assertEquals(8,flags.getTag("empty").getId());
        assertEquals("Stone Bricks",flags.getString("name"));assertEquals("C:\\dir",flags.getString("path"));
    }

    @Test public void refusesTextThatIsNotOneTag() {
        for(String text:new String[]{"","{a:1","{a:1}}","{a:1} x","{a 1}","[0:1,1:\"x\"]","[0:1b,1:2s]","[B;1,x]","[B;300]","[I;1,2","{a:\"open}"})
            try {NBTBase tag=Snbt.read(text);fail(text+" read as "+tag);} catch(IllegalArgumentException expected) {assertTrue(expected.getMessage(),expected.getMessage().startsWith("NBT text: "));}
    }

    @Test public void arraysAndListsKeepTheirTypes() {
        assertEquals(new NBTTagIntArray(new int[]{7}),Snbt.read("[7]"));
        assertEquals(new NBTTagIntArray(new int[]{7,8}),Snbt.read("[7,8,]"));
        assertEquals(9,Snbt.read("[0:7,1:8]").getId());
        assertEquals(9,Snbt.read("[]").getId());
        assertEquals(new NBTTagByteArray(new byte[]{-1,2}),Snbt.read("[B; -1 , 2 ]"));
        assertEquals(9,Snbt.read("[1b,2b]").getId());
    }
}
