// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import java.util.*;

/** Local client commands also make the navigation jar usable without an MCP bridge. */
final class BaritoneCommand extends CommandBase {
    private final BaritoneNavigation navigation;
    BaritoneCommand(BaritoneNavigation navigation) { this.navigation=navigation; }
    @Override public String getCommandName() { return "baritone"; }
    private static final String[] COMMANDS={"goto","goal","stop","status","mine","build","follow","process","settings","cache","selection"};
    @Override public String getCommandUsage(ICommandSender sender) { return "/baritone goto|goal <x> <y> <z>; stop; status; selection <add x y z x y z|clear|list>; mine|build|follow|process|settings|cache <JSON object>"; }
    @Override public int getRequiredPermissionLevel() { return 0; }
    @Override public List addTabCompletionOptions(ICommandSender sender,String[] args){return args.length==1?getListOfStringsMatchingLastWord(args,COMMANDS):List.of();}
    @Override public void processCommand(ICommandSender sender,String[] args) {
        try {
            if(args.length==4 && (args[0].equals("goto")||args[0].equals("goal"))) {
                int x=parseInt(sender,args[1]),y=parseInt(sender,args[2]),z=parseInt(sender,args[3]);
                Minecraft.getMinecraft().displayGuiScreen(null);
                Minecraft.getMinecraft().setIngameFocus();
                if(args[0].equals("goal"))navigation.runApi(api->api.getCustomGoalProcess().setGoalAndPath(new GoalBlock(x,y,z)));
                else navigation.goTo(x,y,z,24000);
                sender.addChatMessage(new ChatComponentText("Baritone: navigating to "+x+", "+y+", "+z));
            } else if(args.length==1 && args[0].equals("stop")) {
                navigation.stop();
                sender.addChatMessage(new ChatComponentText("Baritone: stopped"));
            } else if(args.length==1 && args[0].equals("status")) {
                sender.addChatMessage(new ChatComponentText("Baritone: "+navigation.status()));
            } else if(args.length>=2&&args[0].equals("selection")){
                var manager=navigation.reference().getSelectionManager();
                if(args[1].equals("clear")&&args.length==2)manager.removeAllSelections();
                else if(args[1].equals("add")&&args.length==8)manager.addSelection(new BetterBlockPos(parseInt(sender,args[2]),parseInt(sender,args[3]),parseInt(sender,args[4])),new BetterBlockPos(parseInt(sender,args[5]),parseInt(sender,args[6]),parseInt(sender,args[7])));
                else if(!args[1].equals("list")||args.length!=2)throw new IllegalArgumentException("selection add <x y z x y z> | clear | list");
                sender.addChatMessage(new ChatComponentText("Baritone selections: "+Arrays.toString(manager.getSelections())));
            } else if(args.length>=2&&Set.of("mine","build","follow","process","settings","cache").contains(args[0])){
                Map<String,Object> params=jsonObject(String.join(" ",Arrays.copyOfRange(args,1,args.length)));
                Minecraft.getMinecraft().displayGuiScreen(null);Minecraft.getMinecraft().setIngameFocus();
                Object result=switch(args[0]){
                    case "mine"->navigation.mine(params).status();case "build"->navigation.build(params).status();
                    case "follow"->navigation.follow(params).status();case "process"->navigation.sourceProcess(params).status();
                    case "settings"->navigation.settings(params);case "cache"->navigation.cache(params);default->throw new IllegalArgumentException();
                };
                sender.addChatMessage(new ChatComponentText("Baritone: "+result));
            } else sender.addChatMessage(new ChatComponentText(getCommandUsage(sender)));
        } catch(IllegalArgumentException e) {sender.addChatMessage(new ChatComponentText("Baritone: "+e.getMessage()));}
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> jsonObject(String json){
        try{Object value=new com.google.gson.Gson().fromJson(json,Object.class);if(value instanceof Map<?,?> map)return (Map<String,Object>)map;}
        catch(com.google.gson.JsonParseException error){throw new IllegalArgumentException("invalid JSON: "+error.getMessage());}
        throw new IllegalArgumentException("parameters must be a JSON object using the same fields as the Modbench operation");
    }
}
