// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.server;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** The shipped server jar carries no development fixtures unless built with -PdevFixtures. */
public class ShippedJarTest {
    /** String constants of a class file: registered RPC names are literals in the constant pool. */
    static Set<String> devRpcNames(byte[] bytes) throws IOException {
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));in.skipBytes(8);int count=in.readUnsignedShort();Set<String> out=new TreeSet<>();
        for(int i=1;i<count;i++) {
            int tag=in.readUnsignedByte();
            switch(tag) {
                case 1 -> {String s=in.readUTF();if(s.startsWith("dev.")&&!s.startsWith("dev.modbench"))out.add(s);}
                case 7,8,16,19,20 -> in.skipBytes(2);
                case 15 -> in.skipBytes(3);
                case 3,4,9,10,11,12,17,18 -> in.skipBytes(4);
                case 5,6 -> {in.skipBytes(8);i++;}
                default -> throw new IOException("unknown constant pool tag "+tag);
            }
        }
        return out;
    }
    @Test public void devFixturesOnlyShipWhenRequested() throws Exception {
        String jar=System.getProperty("modbench.jar","");assertFalse("modbench.jar system property missing",jar.isBlank());
        boolean dev=Boolean.getBoolean("modbench.devFixtures");
        Set<String> fixtureClasses=new TreeSet<>(),devRpcs=new TreeSet<>();
        try(ZipFile zip=new ZipFile(jar)) {
            for(ZipEntry entry:Collections.list(zip.entries())) {
                String name=entry.getName();if(!name.endsWith(".class"))continue;
                if(name.matches("dev/modbench/server/(\\w+Fixture|DevFixtures)\\.class"))fixtureClasses.add(name);
                try(InputStream in=zip.getInputStream(entry)) {devRpcs.addAll(devRpcNames(in.readAllBytes()));}
            }
        }
        if(dev) {assertFalse("-PdevFixtures build must ship the fixtures",fixtureClasses.isEmpty());assertFalse(devRpcs.isEmpty());}
        else {assertEquals("shipped jar must not carry fixture classes",Set.of(),fixtureClasses);assertEquals("shipped jar must not register dev.* RPCs",Set.of(),devRpcs);}
    }
}
