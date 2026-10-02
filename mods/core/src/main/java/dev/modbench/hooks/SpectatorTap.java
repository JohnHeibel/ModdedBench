// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.hooks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A copy of the client's game connections for the spectator forwarder, which runs beside the client. Off unless
 * the modbench.spectatorTap property, or else the file ~/.moddedbench/spectator-tap, names a directory (read again
 * at each new connection while off, so it can be switched on without a restart). Each connection gets a handler at
 * the socket end of its pipeline that copies the bytes going each way and passes the originals on untouched. The
 * copies go into a bounded queue that one daemon thread appends to a file per connection in that directory: a file,
 * so the forwarder can restart, or start late, and still read the connection from its first byte. Nothing here
 * waits, and nothing here can fail the connection: a full queue, a full disk or any error stops the copy for that
 * connection, and the game carries on. A new connection deletes the files that no running client is writing.
 *
 * File: "MBTAP1\n", then records: kind (0 client to server, 1 server to client, 2 opened, 3 closed), connection
 * number, length, bytes. Named started-millis-pid-connection.tap, so the newest sorts last.
 */
public final class SpectatorTap {
    static final int TO_SERVER=0,TO_CLIENT=1,OPENED=2,CLOSED=3;
    static final long CAP=64L<<20;        // bytes of copies that may queue before the connection that overflows is dropped
    static final long FILE_CAP=8L<<30;    // one connection's file; a day of play is about 2 GiB
    static final long DISK_SPARE=2L<<30;  // free space a new file leaves alone
    private static final AtomicInteger CONNECTIONS=new AtomicInteger();
    static volatile Writer sender;

    /** NetworkManager.channelActive: tap a client connection to a real socket, when the tap is on. */
    public static void attach(Object context){
        try{
            Writer s=sender();
            if(s==null)return;
            ChannelHandlerContext ctx=(ChannelHandlerContext)context;
            if(!(ctx.channel().remoteAddress() instanceof InetSocketAddress remote))return;  // integrated server: no socket
            Connection c=new Connection(s,CONNECTIONS.incrementAndGet());
            c.record(OPENED,(remote.getHostString()+":"+remote.getPort()).getBytes(StandardCharsets.UTF_8));
            ctx.pipeline().addFirst("modbench_spectator_tap",new Handler(c));
        }catch(Throwable ignored){}  // the tap is never a reason for a connection to fail
    }

    private static Writer sender(){
        if(sender!=null)return sender;
        Path dir=dir(System.getProperty("modbench.spectatorTap",setting()));
        if(dir==null)return null;
        synchronized(SpectatorTap.class){
            if(sender==null){Writer s=new Writer(dir);s.start();sender=s;}
            return sender;
        }
    }

    private static String setting(){
        try{return Files.readString(Path.of(System.getProperty("user.home"),".moddedbench","spectator-tap")).trim();}
        catch(Exception e){return null;}
    }

    static Path dir(String spec){
        if(spec==null||spec.isBlank())return null;
        try{return Path.of(spec).toAbsolutePath();}catch(Exception e){return null;}
    }

    /** One tapped connection. Its copies stop for good once one is lost: a file with a gap would mislead the forwarder. */
    static final class Connection{
        final Writer s;final int id;volatile boolean lost,closed;long written;
        Connection(Writer s,int id){this.s=s;this.id=id;}
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

    /** One daemon thread: opens a file when a connection opens, appends records in order, flushes whenever it has
     *  caught up, and on any failure lets go of that connection. */
    static final class Writer extends Thread{
        final Path dir;
        final LinkedBlockingQueue<Record> queue=new LinkedBlockingQueue<>();
        final AtomicLong queued=new AtomicLong();
        private final Map<Connection,OutputStream> files=new HashMap<>();
        Writer(Path dir){super("ModdedBench spectator tap");this.dir=dir;setDaemon(true);setPriority(MIN_PRIORITY);}

        @Override public void run(){
            byte[] head=new byte[9];
            while(true){
                Record r;
                try{r=queue.poll(5,TimeUnit.SECONDS);}catch(InterruptedException e){return;}
                if(r==null){flush();continue;}
                queued.addAndGet(-r.bytes.length);
                if(r.kind==OPENED&&!create(r.c)){r.c.lost=true;continue;}
                OutputStream out=files.get(r.c);
                if(out==null)continue;
                head[0]=(byte)r.kind;put(head,1,r.c.id);put(head,5,r.bytes.length);
                try{
                    if((r.c.written+=head.length+r.bytes.length)>FILE_CAP)throw new IOException("file cap");
                    out.write(head);out.write(r.bytes);
                    if(r.kind==CLOSED){files.remove(r.c);out.close();}
                    else if(queue.isEmpty())out.flush();
                }catch(IOException e){r.c.lost=true;files.remove(r.c);try{out.close();}catch(IOException ignored){}}
            }
        }

        private boolean create(Connection c){
            try{
                Files.createDirectories(dir);
                sweep();
                if(Files.getFileStore(dir).getUsableSpace()<DISK_SPARE)return false;
                Path f=dir.resolve(System.currentTimeMillis()+"-"+ProcessHandle.current().pid()+"-"+c.id+".tap");
                OutputStream out=new BufferedOutputStream(new FileOutputStream(f.toFile()),1<<16);
                out.write("MBTAP1\n".getBytes(StandardCharsets.US_ASCII));
                files.put(c,out);
                return true;
            }catch(Exception e){return false;}
        }

        /** Deletes the files of finished connections: this client's closed ones, and any whose client is gone. */
        private void sweep(){
            long me=ProcessHandle.current().pid();
            try(DirectoryStream<Path> s=Files.newDirectoryStream(dir,"*.tap")){
                for(Path p:s){
                    try{
                        String[] n=p.getFileName().toString().split("[-.]");
                        long pid=Long.parseLong(n[1]);int id=Integer.parseInt(n[2]);
                        boolean live=pid==me?files.keySet().stream().anyMatch(c->c.id==id)
                                            :ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
                        if(!live)Files.deleteIfExists(p);
                    }catch(Exception ignored){}  // e.g. a file the forwarder still holds open on Windows: next time
                }
            }catch(Exception ignored){}
        }

        private void flush(){
            for(var it=files.entrySet().iterator();it.hasNext();){
                var e=it.next();
                try{e.getValue().flush();}catch(IOException x){e.getKey().lost=true;it.remove();}
            }
        }
    }

    private static void put(byte[] b,int at,int v){b[at]=(byte)(v>>>24);b[at+1]=(byte)(v>>>16);b[at+2]=(byte)(v>>>8);b[at+3]=(byte)v;}

    private SpectatorTap(){}
}
