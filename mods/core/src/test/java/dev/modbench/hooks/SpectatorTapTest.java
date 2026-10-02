// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.hooks;

import static org.junit.Assert.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;

public class SpectatorTapTest {
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}

    private static List<Path> taps(Path dir){
        try(Stream<Path> s=Files.list(dir)){return s.sorted().toList();}catch(Exception e){return List.of();}
    }

    private static void until(java.util.function.BooleanSupplier ok)throws Exception{
        for(long end=System.currentTimeMillis()+5000;!ok.getAsBoolean()&&System.currentTimeMillis()<end;)Thread.sleep(10);
        assertTrue(ok.getAsBoolean());
    }

    @Test public void settingNamesADirectory(){
        assertNotNull(SpectatorTap.dir("C:/x/tap"));
        assertNull(SpectatorTap.dir(""));
        assertNull(SpectatorTap.dir(null));
    }

    @Test public void copiesBothWaysInOrderToAFileAndPassesTheOriginalsOn()throws Exception{
        Path dir=Files.createTempDirectory("tap");
        SpectatorTap.Writer s=new SpectatorTap.Writer(dir);s.start();
        SpectatorTap.Connection c=new SpectatorTap.Connection(s,7);
        c.record(SpectatorTap.OPENED,bytes("host:25575"));
        EmbeddedChannel ch=new EmbeddedChannel(new SpectatorTap.Handler(c));
        ch.writeOutbound(Unpooled.wrappedBuffer(bytes("hello")));
        ch.writeInbound(Unpooled.wrappedBuffer(bytes("world")));
        ByteBuf out=(ByteBuf)ch.readOutbound(),in=(ByteBuf)ch.readInbound();
        assertEquals("hello",out.toString(StandardCharsets.UTF_8));  // untouched, still readable
        assertEquals("world",in.toString(StandardCharsets.UTF_8));
        ch.pipeline().fireChannelInactive();  // EmbeddedChannel.close() only queues this on its own loop
        long size=7+4*9+10+5+5;
        until(()->{try{return taps(dir).size()==1&&Files.size(taps(dir).get(0))==size;}catch(Exception e){return false;}});
        Path f=taps(dir).get(0);
        assertTrue(f.getFileName().toString().matches("\\d+-"+ProcessHandle.current().pid()+"-7\\.tap"));
        DataInputStream r=new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(f)));
        byte[] magic=new byte[7];r.readFully(magic);assertEquals("MBTAP1\n",new String(magic,StandardCharsets.US_ASCII));
        int[] kinds={SpectatorTap.OPENED,SpectatorTap.TO_SERVER,SpectatorTap.TO_CLIENT,SpectatorTap.CLOSED};
        String[] data={"host:25575","hello","world",""};
        for(int i=0;i<kinds.length;i++){
            assertEquals(kinds[i],r.readUnsignedByte());assertEquals(7,r.readInt());
            byte[] b=new byte[r.readInt()];r.readFully(b);assertEquals(data[i],new String(b,StandardCharsets.UTF_8));
        }
    }

    @Test public void aNewConnectionClearsFinishedFilesOnly()throws Exception{
        Path dir=Files.createTempDirectory("tap");
        Files.write(dir.resolve("1-999999999-1.tap"),bytes("a client that is gone"));
        SpectatorTap.Writer s=new SpectatorTap.Writer(dir);s.start();
        SpectatorTap.Connection a=new SpectatorTap.Connection(s,1);a.record(SpectatorTap.OPENED,bytes("x"));
        until(()->taps(dir).size()==1&&taps(dir).get(0).getFileName().toString().endsWith("-1.tap")
                  &&!taps(dir).get(0).getFileName().toString().startsWith("1-"));
        SpectatorTap.Connection b=new SpectatorTap.Connection(s,2);b.record(SpectatorTap.OPENED,bytes("y"));
        until(()->taps(dir).size()==2);  // the first is still open: kept
        a.close();
        SpectatorTap.Connection d=new SpectatorTap.Connection(s,3);d.record(SpectatorTap.OPENED,bytes("z"));
        until(()->taps(dir).size()==2&&taps(dir).stream().noneMatch(p->p.getFileName().toString().endsWith("-1.tap")));
    }

    @Test public void aDirectoryThatCannotBeMadeLetsTheConnectionGoQuietly()throws Exception{
        Path file=Files.createTempFile("tap",".not-a-dir");
        SpectatorTap.Writer s=new SpectatorTap.Writer(file.resolve("sub"));s.start();
        SpectatorTap.Connection c=new SpectatorTap.Connection(s,1);
        c.record(SpectatorTap.OPENED,bytes("host:25575"));
        EmbeddedChannel ch=new EmbeddedChannel(new SpectatorTap.Handler(c));
        until(()->c.lost);
        ch.writeInbound(Unpooled.wrappedBuffer(bytes("still flows")));
        assertEquals("still flows",((ByteBuf)ch.readInbound()).toString(StandardCharsets.UTF_8));
    }

    @Test public void aFullQueueDropsThatConnectionNotTheGame()throws Exception{
        SpectatorTap.Writer s=new SpectatorTap.Writer(Files.createTempDirectory("tap"));  // never started: nothing drains
        SpectatorTap.Connection c=new SpectatorTap.Connection(s,1);
        EmbeddedChannel ch=new EmbeddedChannel(new SpectatorTap.Handler(c));
        byte[] big=new byte[1<<20];
        for(int i=0;i<70;i++)ch.writeInbound(Unpooled.wrappedBuffer(big));
        assertTrue(c.lost);
        assertTrue(s.queued.get()<=SpectatorTap.CAP);
        assertEquals(70,ch.inboundMessages().size());  // every original went on
    }
}
