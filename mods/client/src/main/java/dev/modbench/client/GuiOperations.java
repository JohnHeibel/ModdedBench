// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.client;

import dev.modbench.api.ControlRegistry;
import com.google.gson.*;
import dev.modbench.bridge.*;
import dev.modbench.api.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.*;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;
import org.lwjgl.input.Keyboard;

/** General native GUI actions and DJ2 exact-count transfer semantics, independent of recipe/container type. */
final class GuiOperations {
    static final List<String> METHODS=List.of("click_slot","transfer","return_cursor","click","click_at","drag","scroll","key","type","button","container_button","text_field");
    private final Minecraft mc=Minecraft.getMinecraft();
    final InventoryView view=new InventoryView();
    private Job active;
    private JsonObject last=Json.object("state","idle");
    static String description(String method) {
        return switch(method) {
            case "click_slot" -> "Native slot {windowId,epoch?,slot,expected?,expectedCursor?,type:pickup|quick_move|swap|throw|pickup_all|clone,path:event|structured,button}; default event reaches custom/virtual slots; never retries";
            case "transfer" -> "Exact ordinary-slot transfer {windowId,epoch?,source,expected,destinations:[indices],count:1..64,destinationPolicy:passive|consuming}; empty cursor required; receipts preserve partial effects";
            case "return_cursor" -> "Return observed cursor via pickup clicks {windowId,epoch?,expectedCursor,destinations:[indices]}; explicit destinations only, never drops or swaps unrelated items";
            case "click","click_at" -> "Native GUI mouse press/release {x,y,button:0..15,shift,ctrl,alt,holdTicks:1..200,epoch?}; coordinates use scaled GUI dimensions";
            case "drag" -> "Native drag {points:[{x,y},...],button:0|1,shift,ctrl,alt,epoch?}; 2..64 points, one per client tick; release stays with original screen";
            case "scroll" -> "Native GUI wheel {x,y,delta:120 per notch,shift,ctrl,alt,epoch?}; custom GUI and overlay handlers receive the event";
            case "key" -> "Native GUI key {key:LWJGL-name,char?,shift,ctrl,alt,epoch?}; event getters and modifier polling agree";
            case "type" -> "Type into focused widget through native keyboard events {text,clear:false,enter:false,epoch?}; clear uses Ctrl+A/Backspace, triggers normal listeners";
            case "text_field" -> "Focus observed native text field {field:observed path,text,clear:true,enter:false,epoch?} and type through native events";
            case "button" -> "Click vanilla GUI button by unique {id|text|index,epoch?}; native mouse handler, no direct state mutation";
            case "container_button" -> "Native Container.enchantItem button channel {windowId,epoch?,id}; no generic process-success assumption";
            default -> throw new IllegalArgumentException("unknown GUI method");
        };
    }
    Object start(Request request) throws Exception {
        if(mc.thePlayer==null||mc.currentScreen==null) throw new IllegalArgumentException("open a GUI first");
        if(active!=null) throw new IllegalArgumentException("GUI operation already active");
        Job job=new Job(request);active=job;
        try {job.begin();} catch(Exception failure) {job.fail("gui_error",failure.getMessage());}
        return null;
    }
    void outgoing(Object packet) {
        Job job=active;if(job!=null&&packet instanceof C0EPacketClickWindow p) job.receipt.sent(p.func_149548_c(),p.func_149547_f());
    }
    void incoming(Object packet) {
        Job job=active;if(job!=null&&packet instanceof S32PacketConfirmTransaction p) job.receipt.received(p.func_148889_c(),p.func_148890_d(),p.func_148888_e());
    }
    void maintain() {
        Job job=active;if(job==null) return;
        if(job.request.isDone()||job.request.expired()||!job.request.session.connected) job.fail("cancelled","request expired, cancelled or disconnected");
    }
    void tick() {
        Job job=active;if(job==null) return;
        try {job.tick();}catch(Exception error) {job.fail("gui_error",error.toString());}
    }
    void cancel(String reason) {if(active!=null) active.fail("cancelled",reason);else UiInput.clear();}
    Object status() {return active==null?last:active.result("running");}

    private final class Job {
        final Request request;
        final JsonObject p;
        final String method;
        final GuiScreen screen=mc.currentScreen;
        final Container container;
        final long epoch,startedEvents=UiInput.delivered();
        final ClickReceipt receipt=new ClickReceipt();
        final Map<Integer,JsonElement> before=new LinkedHashMap<>();
        final JsonElement cursorBefore=Json.GSON.toJsonTree(Stacks.json(mc.thePlayer.inventory.getItemStack()));
        final ArrayDeque<Runnable> steps=new ArrayDeque<>();
        InputArbiter.Lease lease;
        int age,settled,lastX,lastY,mouseButton=-1;
        long afterFrame=-1;
        Runnable pressGuard=()->{};
        boolean done,screenChanged,attempted;
        String error="",pointerWarning="";
        JsonObject transfer;
        Slot source;
        ItemStack sourceStack;
        List<Slot> destinations;
        int destinationBefore,planned;
        Job(Request request) {
            this.request=request;p=request.params;method=request.method.substring(4);container=view.require(p);epoch=view.epoch();
            if(p.has("expectedCursor")&&!cursorBefore.equals(p.get("expectedCursor"))) throw new IllegalArgumentException("stale_cursor: observe again");
            for(int i=0;i<container.inventorySlots.size();i++) before.put(i,Json.GSON.toJsonTree(Stacks.json(InventoryView.slot(container,i).getStack())));
        }
        void begin() throws Exception {
            Json.integer(p,"settleTicks",3,1,100);Json.integer(p,"ackTimeoutTicks",100,1,1200);
            lease=ControlRegistry.controls().arbiter().acquire("gui:"+method,reason->{
                UiInput.clear();
                if(reason.contains("gui")) screenChanged=true;else fail("cancelled",reason);
            });
            ControlRegistry.controls().ownGui(lease,screen);UiInput.clear();
            UiInput.modifier(Keyboard.KEY_LSHIFT,Json.bool(p,"shift",false));
            UiInput.modifier(Keyboard.KEY_LCONTROL,Json.bool(p,"ctrl",false));
            UiInput.modifier(Keyboard.KEY_LMENU,Json.bool(p,"alt",false));
            switch(method) {
                case "transfer": transfer(false);break;
                case "return_cursor": transfer(true);break;
                case "click_slot": clickSlot();break;
                case "click","click_at": click(point(p),Json.integer(p,"button",0,0,15),Json.integer(p,"holdTicks",1,1,200));break;
                case "drag": {
                    if(!p.has("points")||!p.get("points").isJsonArray()) throw new IllegalArgumentException("points array required");
                    var points=p.getAsJsonArray("points");if(points.size()<2||points.size()>64) throw new IllegalArgumentException("drag requires 2..64 points");
                    int button=Json.integer(p,"button",0,0,1);int[] first=point(points.get(0).getAsJsonObject());
                    mouseButton=button;post(first[0],first[1],-1,false,0);
                    steps.add(()->post(first[0],first[1],button,true,0));
                    for(int i=1;i<points.size();i++) {int[] next=point(points.get(i).getAsJsonObject());steps.add(()->post(next[0],next[1],-1,false,0));}
                    steps.add(()->post(lastX,lastY,button,false,0));break;
                }
                case "scroll": {int[] pos=point(p);post(pos[0],pos[1],-1,false,Json.integer(p,"delta",120,-12000,12000));break;}
                case "key": key(p);break;
                case "type": type(p,Json.bool(p,"clear",false));break;
                case "text_field": {
                    String field=Json.string(p,"field","");Object target=null;
                    for(Object[] entry:UiWidgets.textFields(screen)) if(entry[0].equals(field)) target=entry[1];
                    if(target==null) throw new IllegalArgumentException("observed text field unavailable");
                    JsonObject tf=UiWidgets.textField(target,field);
                    if(!tf.has("visible")||!tf.get("visible").getAsBoolean()||!tf.has("h")) throw new IllegalArgumentException("text field hidden or geometry unavailable");
                    click(new int[]{tf.get("x").getAsInt()+Math.min(8,tf.get("w").getAsInt()/2),tf.get("y").getAsInt()+tf.get("h").getAsInt()/2},0,1);
                    steps.add(()->type(p,Json.bool(p,"clear",true)));break;
                }
                case "button": {
                    if((p.has("id")?1:0)+(p.has("text")?1:0)+(p.has("index")?1:0)!=1) throw new IllegalArgumentException("choose exactly one button selector: id, text or index");
                    List<GuiButton> all=UiWidgets.buttonListOf(screen),matches=new ArrayList<>();
                    for(int i=0;i<all.size();i++) {
                        GuiButton b=all.get(i);if(p.has("id")&&Json.integer(p,"id",0,Integer.MIN_VALUE,Integer.MAX_VALUE)==b.id
                            ||p.has("index")&&Json.integer(p,"index",0,0,all.size()-1)==i||p.has("text")&&Json.string(p,"text","").equals(UiWidgets.strip(b.displayString))) matches.add(b);
                    }
                    if(matches.size()!=1) throw new IllegalArgumentException("button selection must match exactly one button");
                    GuiButton b=matches.get(0);if(!b.enabled||!b.visible) throw new IllegalArgumentException("button disabled or hidden");
                    click(new int[]{b.xPosition+UiWidgets.width(b)/2,b.yPosition+UiWidgets.height(b)/2},0,1);break;
                }
                case "container_button": attempted=true;mc.playerController.sendEnchantPacket(container.windowId,Json.integer(p,"id",0,0,255));break;
                default: throw new IllegalArgumentException("unknown GUI operation");
            }
        }
        int[] point(JsonObject point) {
            if(!point.has("x")||!point.has("y")) throw new IllegalArgumentException("x,y required in GUI coordinates");
            return new int[]{Json.integer(point,"x",0,0,screen.width-1),Json.integer(point,"y",0,0,screen.height-1)};
        }
        void post(int x,int y,int button,boolean down,int wheel) {
            if(button>=0&&down) {
                if(p.has("expectedCursor")&&!Stacks.expected(mc.thePlayer.inventory.getItemStack(),p.get("expectedCursor"))) throw new IllegalStateException("stale_cursor before native press");
                pressGuard.run();
            }
            lastX=x;lastY=y;attempted=true;afterFrame=UiInput.frames();
            // Event coordinates and polled coordinates are supplied by UiHooks. Native
            // pointer alignment assists APIs outside LWJGL; a lagging GLFW cursor must
            // not discard an otherwise valid synthetic release or strand a held button.
            try {GuiPointer.move(screen,x,y);}catch(Exception failure) {pointerWarning=failure.toString();}
            UiInput.postMouse(x,y,button,down,wheel);
        }
        void click(int[] pos,int button,int hold) {
            if(pos[0]<0||pos[1]<0||pos[0]>=screen.width||pos[1]>=screen.height) throw new IllegalArgumentException("target outside screen");
            // Hover first, then allow a rendered frame and normal GUI update before
            // pressing. MUI and other GUIs cache hit targets outside their click handler.
            mouseButton=button;post(pos[0],pos[1],-1,false,0);
            steps.add(()->post(pos[0],pos[1],button,true,0));
            for(int i=1;i<hold;i++) steps.add(()->{});
            steps.add(()->post(pos[0],pos[1],button,false,0));
        }
        void key(JsonObject params) {
            String name=Json.string(params,"key","NONE").toUpperCase(Locale.ROOT);int code=Keyboard.getKeyIndex(name);
            if(code==0&&!name.equals("NONE")) throw new IllegalArgumentException("unknown LWJGL key");
            String character=Json.string(params,"char","");if(character.length()>1) throw new IllegalArgumentException("char must be one UTF-16 character");
            char ch=character.isEmpty()?'\0':character.charAt(0);
            if(character.isEmpty()&&Json.bool(params,"ctrl",false)&&name.length()==1&&name.charAt(0)>='A'&&name.charAt(0)<='Z') ch=(char)(name.charAt(0)-'A'+1);
            attempted=true;UiInput.postKey(code,ch,true);UiInput.postKey(code,'\0',false);
        }
        void type(JsonObject params,boolean clear) {
            String text=Json.string(params,"text","");if(text.length()>512) throw new IllegalArgumentException("text exceeds 512 characters; send additional chunks");
            attempted=true;
            if(clear) {
                UiInput.postKey(Keyboard.KEY_LCONTROL,'\0',true);UiInput.postKey(Keyboard.KEY_A,(char)1,true);UiInput.postKey(Keyboard.KEY_A,'\0',false);UiInput.postKey(Keyboard.KEY_LCONTROL,'\0',false);
                UiInput.postKey(Keyboard.KEY_BACK,'\0',true);UiInput.postKey(Keyboard.KEY_BACK,'\0',false);
            }
            for(char ch:text.toCharArray()) {UiInput.postKey(0,ch,true);UiInput.postKey(0,'\0',false);}
            if(Json.bool(params,"enter",false)) {UiInput.postKey(Keyboard.KEY_RETURN,'\r',true);UiInput.postKey(Keyboard.KEY_RETURN,'\0',false);}
        }
        void clickSlot() {
            if(!p.has("slot")||!p.has("windowId")) throw new IllegalArgumentException("windowId,slot required");
            int index=Json.integer(p,"slot",-1,0,container.inventorySlots.size()-1);Slot slot=InventoryView.slot(container,index);
            if(p.has("expected")&&!Stacks.expected(slot.getStack(),p.get("expected"))) throw new IllegalArgumentException("stale_stack: observe again");
            if(p.has("expected")) pressGuard=()->{
                if(index>=container.inventorySlots.size()||InventoryView.slot(container,index)!=slot||!Stacks.expected(slot.getStack(),p.get("expected")))
                    throw new IllegalStateException("stale_stack before native press; virtual results may have reordered");
            };
            String type=Json.string(p,"type",p.has("mode")&&Json.integer(p,"mode",0,0,1)==1?"quick_move":"pickup");
            int mode=switch(type) {case "pickup"->0;case "quick_move"->1;case "swap"->2;case "clone"->3;case "throw"->4;case "pickup_all"->6;default->throw new IllegalArgumentException("unsupported click type; use gui.drag for drag distribution");};
            int button=Json.integer(p,"button",0,0,mode==2?8:mode==3?2:1);
            if(!slot.func_111238_b()) throw new IllegalArgumentException("slot disabled");
            String path=Json.string(p,"path",mode==2||mode==4||mode==6?"structured":"event");if(path.equals("auto")) path="event";
            if(path.equals("structured")) {
                requireVisibleContainer();
                if(!InventoryView.ordinary(slot,index)) throw new IllegalArgumentException("virtual/custom slot requires native event path");
                attempted=true;mc.playerController.windowClick(container.windowId,index,button,mode,mc.thePlayer);
            } else if(path.equals("event")) {
                if(!(screen instanceof GuiContainer gc)||gc.inventorySlots!=container) throw new IllegalArgumentException("no native slot geometry; use gui.click_at with observed screen coordinates");
                int x=UiWidgets.guiInt(gc,"guiLeft","field_147003_i")+slot.xDisplayPosition+8,y=UiWidgets.guiInt(gc,"guiTop","field_147009_r")+slot.yDisplayPosition+8;
                if(mode==0||mode==1||mode==3) {
                    if(mode==1) UiInput.modifier(Keyboard.KEY_LSHIFT,true);
                    click(new int[]{x,y},mode==3?2:button,1);
                } else throw new IllegalArgumentException("swap/throw/pickup_all require path:structured; use separate hover/key primitives for custom key handlers");
            } else throw new IllegalArgumentException("path must be event or structured");
        }
        void transfer(boolean cursor) {
            requireVisibleContainer();
            if(!p.has("windowId")||!p.has("destinations")||!p.get("destinations").isJsonArray()) throw new IllegalArgumentException("windowId and destinations required");
            if(cursor) {
                sourceStack=mc.thePlayer.inventory.getItemStack();
                if(!p.has("expectedCursor")||sourceStack==null) throw new IllegalArgumentException("nonempty expectedCursor required");
                sourceStack=sourceStack.copy();planned=sourceStack.stackSize;
            } else {
                if(!p.has("count")||!p.has("source")) throw new IllegalArgumentException("source and count required");
                if(mc.thePlayer.inventory.getItemStack()!=null) throw new IllegalArgumentException("cursor_occupied: return cursor explicitly first");
                int index=Json.integer(p,"source",-1,0,container.inventorySlots.size()-1);source=InventoryView.slot(container,index);sourceStack=source.getStack();
                if(!p.has("expected")||sourceStack==null||!Stacks.expected(sourceStack,p.get("expected"))) throw new IllegalArgumentException("stale_stack: expected source required");
                sourceStack=sourceStack.copy();
                if(!InventoryView.ordinary(source,index)||!source.canTakeStack(mc.thePlayer)||!source.isItemValid(sourceStack)) throw new IllegalArgumentException("source needs ordinary inventory semantics; use native click for output/custom slots");
                planned=Math.min(Json.integer(p,"count",0,1,64),sourceStack.stackSize);
            }
            String policy=Json.string(p,"destinationPolicy","passive");if(!Set.of("passive","consuming").contains(policy)) throw new IllegalArgumentException("destinationPolicy must be passive or consuming");
            destinations=new ArrayList<>();Set<Integer> seen=new HashSet<>();int capacity=0;
            if(p.getAsJsonArray("destinations").size()>128) throw new IllegalArgumentException("too many destinations");
            for(JsonElement value:p.getAsJsonArray("destinations")) {
                int index=value.getAsBigDecimal().intValueExact();Slot slot=InventoryView.slot(container,index);
                if(!seen.add(index)||slot==source||source!=null&&slot.inventory==source.inventory&&slot.getSlotIndex()==source.getSlotIndex()) throw new IllegalArgumentException("duplicate/source/aliased destination");
                for(Slot previous:destinations) if(previous.inventory==slot.inventory&&previous.getSlotIndex()==slot.getSlotIndex()) throw new IllegalArgumentException("aliased destinations");
                if(!InventoryView.ordinary(slot,index)) throw new IllegalArgumentException("virtual/custom destination requires event interaction");
                if(slot.isItemValid(sourceStack)&&room(slot,sourceStack)>0) {destinations.add(slot);capacity+=room(slot,sourceStack);}
            }
            if(cursor&&capacity<planned) throw new IllegalArgumentException("insufficient capacity to return entire cursor safely");
            planned=Math.min(planned,capacity);destinationBefore=total(destinations,sourceStack);
            transfer=Json.object("requested",cursor?sourceStack.stackSize:Json.integer(p,"count",0,1,64),"planned",planned,"destinationPolicy",policy,"cursorRecovery",cursor);
            if(planned==0) return;
            attempted=true;
            try {
                if(!cursor) click(source,0);
                if(!Stacks.same(mc.thePlayer.inventory.getItemStack(),sourceStack)) throw new IllegalStateException("source did not reach cursor");
                int left=planned;
                for(Slot slot:destinations) {
                    int put=Math.min(left,room(slot,sourceStack));
                    for(int n=0;n<put;n++) click(slot,1);
                    left-=put;if(left==0) break;
                }
            } finally {
                ItemStack held=mc.thePlayer.inventory.getItemStack();
                if(!cursor&&mc.thePlayer.openContainer==container&&Stacks.same(held,sourceStack)&&source.isItemValid(held)&&room(source,held)>=held.stackSize) click(source,0);
            }
            int removed=cursor?sourceStack.stackSize-(mc.thePlayer.inventory.getItemStack()==null?0:mc.thePlayer.inventory.getItemStack().stackSize):sourceStack.stackSize-(Stacks.same(source.getStack(),sourceStack)?source.getStack().stackSize:0);
            int added=total(destinations,sourceStack)-destinationBefore;
            transfer.addProperty("immediateRemoved",removed);transfer.addProperty("immediateAdded",added);
            if(removed!=planned||added!=planned||mc.thePlayer.inventory.getItemStack()!=null) throw new IllegalStateException("immediate transfer mismatch; inspect partial receipt before retrying");
        }
        void click(Slot slot,int button) {mc.playerController.windowClick(container.windowId,slot.slotNumber,button,0,mc.thePlayer);}
        void requireVisibleContainer() {
            if(!(screen instanceof GuiContainer gc)||gc.inventorySlots!=container) throw new IllegalArgumentException("visible GUI does not expose the player's open container; return to that screen before structured inventory actions");
        }
        void tick() {
            if(done) return;age++;
            if(screenChanged||mc.currentScreen!=screen||mc.thePlayer==null||mc.thePlayer.openContainer!=container||view.epoch()!=epoch) {
                if(transfer!=null) fail("stale_window","screen/container changed during transfer; inspect partial effects");else finish("screen_changed");return;
            }
            if(!lease.isActive()) {fail("cancelled","control owner changed");return;}
            if(receipt.rejected()) {fail("server_rejected","native container transaction rejected; inspect synchronized state");return;}
            if(UiInput.pending()>0||UiInput.frames()<=afterFrame) return;
            if(!steps.isEmpty()) {steps.remove().run();settled=0;return;}
            if(++settled<Json.integer(p,"settleTicks",3,1,100)) return;
            if(receipt.count()>0&&!receipt.complete()) {
                if(settled>=Json.integer(p,"ackTimeoutTicks",100,1,1200)) fail("ack_timeout","no complete server acknowledgement; do not retry blindly");
                return;
            }
            if(transfer!=null) {
                int added=total(destinations,sourceStack)-destinationBefore;
                int removed=source==null?planned:sourceStack.stackSize-(Stacks.same(source.getStack(),sourceStack)?source.getStack().stackSize:0);
                boolean retained=added==planned&&removed==planned;
                transfer.addProperty("settledAdded",added);transfer.addProperty("settledRemoved",removed);transfer.addProperty("retained",retained);
                transfer.addProperty("moved",planned);transfer.addProperty("disposition",retained?"retained":"consumed_routed_or_corrected_unproven");
                if(mc.thePlayer.inventory.getItemStack()!=null||!retained&&transfer.get("destinationPolicy").getAsString().equals("passive")) {fail("postcondition_failed","settled transfer differs; inspect before retrying");return;}
            }
            finish("completed");
        }
        JsonObject result(String state) {
            JsonObject out=Json.object("state",state,"method",request.method,"attempted",attempted,"windowId",container.windowId,"epoch",epoch,
                "ticks",age,"eventsDelivered",UiInput.delivered()-startedEvents,"transactions",receipt.status(),"serverAcknowledged",receipt.accepted(),
                "verification",receipt.accepted()?"native_transactions_accepted":"client_event_delivery_only","cursorBefore",cursorBefore,
                "cursorAfter",mc.thePlayer==null?null:Stacks.json(mc.thePlayer.inventory.getItemStack()),"screen",mc.currentScreen==null?null:mc.currentScreen.getClass().getName(),"error",error);
            if(!pointerWarning.isEmpty()) out.addProperty("nativePointerWarning",pointerWarning);
            if(transfer!=null) out.add("transfer",transfer);
            JsonArray changes=new JsonArray();
            if(mc.thePlayer!=null&&mc.thePlayer.openContainer==container) for(var entry:before.entrySet()) {
                if(entry.getKey()>=container.inventorySlots.size()) continue;
                JsonElement after=Json.GSON.toJsonTree(Stacks.json(InventoryView.slot(container,entry.getKey()).getStack()));
                if(!after.equals(entry.getValue())) changes.add(Json.object("slot",entry.getKey(),"before",entry.getValue(),"after",after));
                if(changes.size()>=128) break;
            }
            out.add("changes",changes);return out;
        }
        void finish(String state) {
            if(done) return;done=true;cleanup();last=result(state);active=null;request.reply(last);
        }
        void fail(String code,String reason) {
            if(done) return;done=true;error=reason==null?code:reason;cleanup();last=result("failed");active=null;request.fail(code,error,last);
        }
        void cleanup() {
            // A release may complete a partial drag, but must never be sent to a replacement screen.
            boolean held=mouseButton>=0&&UiInput.buttonDown(mouseButton);
            int releaseX=UiInput.x()<0?lastX:UiInput.x(),releaseY=UiInput.y()<0?lastY:UiInput.y();
            UiInput.clear();steps.clear();
            if(held&&mc.currentScreen==screen) try {
                UiInput.postMouse(releaseX,releaseY,mouseButton,false,0);UiInput.nextMouse();screen.handleMouseInput();
            } catch(Exception failure) {error+="; release_failed:"+failure;}finally {UiInput.clear();}
            ControlRegistry.controls().releaseGui(lease);if(lease!=null) lease.close();
        }
    }
    private static int room(Slot slot,ItemStack stack) {
        ItemStack existing=slot.getStack();if(existing!=null&&!Stacks.same(existing,stack)) return 0;
        return Math.max(0,Math.min(stack.getMaxStackSize(),slot.getSlotStackLimit())-(existing==null?0:existing.stackSize));
    }
    private static int total(List<Slot> slots,ItemStack stack) {
        int total=0;for(Slot slot:slots) if(Stacks.same(slot.getStack(),stack)) total+=slot.getStack().stackSize;return total;
    }
}
