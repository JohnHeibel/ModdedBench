// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.hooks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A copy of the client's game connections for the spectator forwarder, which runs beside the client. Off unless
 * the modbench.spectatorTap property, or else the file ~/.moddedbench/spectator-tap, names a loopback port (read
 * again at each new connection while off, so it can be switched on without a restart). Each connection gets a handler at the socket end of its pipeline that
 * copies the bytes going each way and passes the originals on untouched. The copies go into a bounded queue that
 * one daemon thread writes to the forwarder. Nothing here waits, and nothing here can fail the connection: a full
 * queue, a forwarder that is not there, or any error drops the copy for that connection, and the game carries on.
 *
 * Records: kind (0 client to server, 1 server to client, 2 opened, 3 closed), connection number, length, bytes.
 */
public final class SpectatorTap {
    static final int TO_SERVER=0,TO_CLIENT=1,OPENED=2,CLOSED=3;
    static final long CAP=64L<<20;  // bytes of copies that may queue before the connection that overflows is dropped
    private static final AtomicInteger CONNECTIONS=new AtomicInteger();
    static volatile Sender sender;

    /** NetworkManager.channelActive: tap a client connection to a real socket, when the tap is on. */
    public static void attach(Object context){
        try{
            Sender s=sender();
            if(s==null)return;
            ChannelHandlerContext ctx=(ChannelHandlerContext)context;
            if(!(ctx.channel().remoteAddress() instanceof InetSocketAddress remote))return;  // integrated server: no socket
            Connection c=new Connection(s,CONNECTIONS.incrementAndGet());
            c.record(OPENED,(remote.getHostString()+":"+remote.getPort()).getBytes(StandardCharsets.UTF_8));
            ctx.pipeline().addFirst("modbench_spectator_tap",new Handler(c));
        }catch(Throwable ignored){}  // the tap is never a reason for a connection to fail
    }

    private static Sender sender(){
        if(sender!=null)return sender;
        InetSocketAddress sink=sink(System.getProperty("modbench.spectatorTap",setting()));
        if(sink==null)return null;
        synchronized(SpectatorTap.class){
            if(sender==null){Sender s=new Sender(sink);s.start();sender=s;}
            return sender;
        }
    }

    private static String setting(){
        try{return Files.readString(Path.of(System.getProperty("user.home"),".moddedbench","spectator-tap")).trim();}
        catch(Exception e){return null;}
    }

    static InetSocketAddress sink(String spec){
        if(spec==null||spec.isBlank())return null;
        try{
            int colon=spec.lastIndexOf(':');
            InetAddress host=InetAddress.getByName(colon<0?"127.0.0.1":spec.substring(0,colon));
            if(!host.isLoopbackAddress())return null;  // the copy never leaves this machine; the forwarder does that
            return new InetSocketAddress(host,Integer.parseInt(spec.substring(colon+1)));
        }catch(Exception e){return null;}
    }

    /** One tapped connection. Its copies stop for good once one is lost: the forwarder cannot pick up mid-stream. */
    static final class Connection{
        final Sender s;final int id;volatile boolean lost,closed;
        Connection(Sender s,int id){this.s=s;this.id=id;}
        void record(int kind,byte[] bytes){
            if(lost)return;
            if(s.queued.addAndGet(bytes.length)>CAP){s.queued.addAndGet(-bytes.length);lose();return;}
            s.queue.offer(new Record(this,kind,bytes));
        }
        void lose(){lost=true;close();}
        void close(){if(!closed){closed=true;s.queue.offer(new Record(this,CLOSED,new byte[0]));}}
    }
    record Record(Connection c,int kind,byte[] bytes){}

    static final class Handler extends ChannelDuplexHandler{
        final Connection c;
        Handler(Connection c){this.c=c;}
        @Override public void channelRead(ChannelHandlerContext ctx,Object msg)throws Exception{copy(TO_CLIENT,msg);ctx.fireChannelRead(msg);}
        @Override public void write(ChannelHandlerContext ctx,Object msg,ChannelPromise promise)throws Exception{copy(TO_SERVER,msg);ctx.write(msg,promise);}
        @Override public void channelInactive(ChannelHandlerContext ctx)throws Exception{
            try{c.lost=true;c.close();}catch(Throwable ignored){}
            ctx.fireChannelInactive();
        }
        private void copy(int kind,Object msg){
            try{
                if(c.lost||!(msg instanceof ByteBuf buf)||!buf.isReadable())return;
                byte[] b=new byte[buf.readableBytes()];buf.getBytes(buf.readerIndex(),b);c.record(kind,b);
            }catch(Throwable t){try{c.lose();}catch(Throwable ignored){}}
        }
    }

    /** One daemon thread: connects when a connection opens, writes records in order, and on any failure lets go of every connection it was carrying. */
    static final class Sender extends Thread{
        final InetSocketAddress sink;
        final LinkedBlockingQueue<Record> queue=new LinkedBlockingQueue<>();
        final AtomicLong queued=new AtomicLong();
        private final java.util.Set<Connection> live=new java.util.HashSet<>();
        private Socket socket;private OutputStream out;
        Sender(InetSocketAddress sink){super("ModdedBench spectator tap");this.sink=sink;setDaemon(true);setPriority(MIN_PRIORITY);}

        @Override public void run(){
            byte[] head=new byte[9];
            while(true){
                Record r;
                try{r=queue.poll(5,TimeUnit.SECONDS);}catch(InterruptedException e){return;}
                if(r==null){flush();continue;}
                queued.addAndGet(-r.bytes.length);
                if(r.kind==OPENED){
                    if(out==null)connect();
                    if(out==null){r.c.lost=true;continue;}
                    live.add(r.c);
                }
                if(!live.contains(r.c))continue;
                head[0]=(byte)r.kind;put(head,1,r.c.id);put(head,5,r.bytes.length);
                try{out.write(head);out.write(r.bytes);if(queue.isEmpty())out.flush();}
                catch(IOException e){drop();continue;}
                if(r.kind==CLOSED)live.remove(r.c);
            }
        }
        private void connect(){
            try{
                socket=new Socket();socket.connect(sink,1000);socket.setTcpNoDelay(true);
                out=new BufferedOutputStream(socket.getOutputStream(),1<<16);
                out.write("MBTAP1\n".getBytes(StandardCharsets.US_ASCII));
            }catch(IOException e){drop();}
        }
        private void flush(){if(out!=null)try{out.flush();}catch(IOException e){drop();}}
        private void drop(){
            try{if(socket!=null)socket.close();}catch(IOException ignored){}
            socket=null;out=null;
            for(Connection c:live)c.lost=true;
            live.clear();
        }
    }

    private static void put(byte[] b,int at,int v){b[at]=(byte)(v>>>24);b[at+1]=(byte)(v>>>16);b[at+2]=(byte)(v>>>8);b[at+3]=(byte)v;}

    private SpectatorTap(){}
}
