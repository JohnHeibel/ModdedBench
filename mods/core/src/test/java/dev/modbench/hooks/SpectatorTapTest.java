// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.hooks;

import static org.junit.Assert.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.DataInputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class SpectatorTapTest {
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}

    @Test public void onlyLoopbackSinks(){
        assertNotNull(SpectatorTap.sink("127.0.0.1:25590"));
        assertNotNull(SpectatorTap.sink("25590"));
        assertNull(SpectatorTap.sink("8.8.8.8:25590"));
        assertNull(SpectatorTap.sink(""));
        assertNull(SpectatorTap.sink(null));
    }

    @Test public void copiesBothWaysInOrderAndPassesTheOriginalsOn()throws Exception{
        try(ServerSocket server=new ServerSocket(0)){
            SpectatorTap.Sender s=new SpectatorTap.Sender(new InetSocketAddress("127.0.0.1",server.getLocalPort()));s.start();
            SpectatorTap.Connection c=new SpectatorTap.Connection(s,7);
            c.record(SpectatorTap.OPENED,bytes("host:25575"));
            EmbeddedChannel ch=new EmbeddedChannel(new SpectatorTap.Handler(c));
            ch.writeOutbound(Unpooled.wrappedBuffer(bytes("hello")));
            ch.writeInbound(Unpooled.wrappedBuffer(bytes("world")));
            ByteBuf out=(ByteBuf)ch.readOutbound(),in=(ByteBuf)ch.readInbound();
            assertEquals("hello",out.toString(StandardCharsets.UTF_8));  // untouched, still readable
            assertEquals("world",in.toString(StandardCharsets.UTF_8));
            ch.pipeline().fireChannelInactive();  // EmbeddedChannel.close() only queues this on its own loop
            try(Socket sink=server.accept()){
                sink.setSoTimeout(5000);
                DataInputStream r=new DataInputStream(sink.getInputStream());
                byte[] magic=new byte[7];r.readFully(magic);assertEquals("MBTAP1\n",new String(magic,StandardCharsets.US_ASCII));
                int[] kinds={SpectatorTap.OPENED,SpectatorTap.TO_SERVER,SpectatorTap.TO_CLIENT,SpectatorTap.CLOSED};
                String[] data={"host:25575","hello","world",""};
                for(int i=0;i<kinds.length;i++){
                    assertEquals(kinds[i],r.readUnsignedByte());assertEquals(7,r.readInt());
                    byte[] b=new byte[r.readInt()];r.readFully(b);assertEquals(data[i],new String(b,StandardCharsets.UTF_8));
                }
            }
        }
    }

    @Test public void noForwarderMeansTheConnectionIsLetGoQuietly()throws Exception{
        int port;try(ServerSocket free=new ServerSocket(0)){port=free.getLocalPort();}  // nothing listens here now
        SpectatorTap.Sender s=new SpectatorTap.Sender(new InetSocketAddress("127.0.0.1",port));s.start();
        SpectatorTap.Connection c=new SpectatorTap.Connection(s,1);
        c.record(SpectatorTap.OPENED,bytes("host:25575"));
        EmbeddedChannel ch=new EmbeddedChannel(new SpectatorTap.Handler(c));
        for(long end=System.currentTimeMillis()+5000;!c.lost&&System.currentTimeMillis()<end;)Thread.sleep(10);
        assertTrue(c.lost);
        ch.writeInbound(Unpooled.wrappedBuffer(bytes("still flows")));
        assertEquals("still flows",((ByteBuf)ch.readInbound()).toString(StandardCharsets.UTF_8));
    }

    @Test public void aFullQueueDropsThatConnectionNotTheGame(){
        SpectatorTap.Sender s=new SpectatorTap.Sender(new InetSocketAddress("127.0.0.1",1));  // never started: nothing drains
        SpectatorTap.Connection c=new SpectatorTap.Connection(s,1);
        EmbeddedChannel ch=new EmbeddedChannel(new SpectatorTap.Handler(c));
        byte[] big=new byte[1<<20];
        for(int i=0;i<70;i++)ch.writeInbound(Unpooled.wrappedBuffer(big));
        assertTrue(c.lost);
        assertTrue(s.queued.get()<=SpectatorTap.CAP);
        assertEquals(70,ch.inboundMessages().size());  // every original went on
    }
}
