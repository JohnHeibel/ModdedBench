// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package baritone;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;
import static org.junit.Assert.*;

/** The Baritone jar only talks to Modbench through dev.modbench.api. */
public class SeamTest {
    private static final Pattern MODBENCH=Pattern.compile("dev/modbench/[\\w/$]+");
    /** Every referenced class, descriptor and signature lives in the constant pool's Utf8 entries. */
    static Set<String> foreignReferences(byte[] bytes) throws IOException {
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));in.skipBytes(8);int count=in.readUnsignedShort();Set<String> out=new TreeSet<>();
        for(int i=1;i<count;i++) {
            int tag=in.readUnsignedByte();
            switch(tag) {
                case 1 -> {var m=MODBENCH.matcher(in.readUTF());while(m.find())if(!m.group().startsWith("dev/modbench/api/"))out.add(m.group());}
                case 7,8,16,19,20 -> in.skipBytes(2);
                case 15 -> in.skipBytes(3);
                case 3,4,9,10,11,12,17,18 -> in.skipBytes(4);
                case 5,6 -> {in.skipBytes(8);i++;}
                default -> throw new IOException("unknown constant pool tag "+tag);
            }
        }
        return out;
    }
    @Test public void compiledClassesReferenceOnlyTheApiPackage() throws Exception {
        Map<String,Set<String>> offenders=new TreeMap<>();int scanned=0;
        for(String dir:System.getProperty("modbench.classes","").split(File.pathSeparator)) {
            if(dir.isBlank()||!Files.isDirectory(Path.of(dir)))continue;
            try(Stream<Path> files=Files.walk(Path.of(dir))) {
                for(Path file:files.filter(p->p.toString().endsWith(".class")).toList()) {
                    scanned++;Set<String> found=foreignReferences(Files.readAllBytes(file));
                    if(!found.isEmpty())offenders.put(Path.of(dir).relativize(file).toString(),found);
                }
            }
        }
        assertTrue("no compiled Baritone classes found; is modbench.classes set?",scanned>0);
        assertEquals("Baritone must not reference core/control/bridge classes: "+offenders,Map.of(),offenders);
    }
}
