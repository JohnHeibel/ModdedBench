// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.client;

import com.google.gson.*;
import dev.modbench.bridge.*;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.item.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.nbt.*;
import net.minecraftforge.oredict.OreDictionary;
import net.minecraftforge.fluids.*;

/** Uses GTNH NEI's native catalogue, query parser and registered recipe handlers. Client-thread only. */
final class NeiAccess {
    private List<ItemStack> catalogue;
    private long generation;
    private final ArrayDeque<Search> searches=new ArrayDeque<>();
    private final LinkedHashMap<String,List<ItemStack>> cachedSearches=new LinkedHashMap<>();
    private final LinkedHashMap<String,List<?>> cachedRecipes=new LinkedHashMap<>();

    Object status() throws Exception {
        boolean installed=cpw.mods.fml.common.Loader.isModLoaded("NotEnoughItems");
        boolean ready=installed && Boolean.TRUE.equals(call(Class.forName("codechicken.nei.NEIClientConfig"),"isLoaded"))
            && Boolean.TRUE.equals(field(Class.forName("codechicken.nei.ItemList"),"loadFinished"));
        if(ready) refresh();
        return Json.object("installed",installed,"ready",ready,"items",catalogue==null?0:catalogue.size(),
            "generation",generation,"pendingSearches",searches.size(),"querySyntax","native GTNH NEI",
            "searchFilters",List.of("query","id","meta","mod","ore"),"recipes",true,"uses",true);
    }
    @SuppressWarnings("unchecked") private void refresh() throws Exception {
        List<ItemStack> latest=(List<ItemStack>)field(Class.forName("codechicken.nei.ItemList"),"items");
        if(latest!=catalogue) { catalogue=latest;generation++;cachedSearches.clear();cachedRecipes.clear(); }
    }
    private void requireReady() throws Exception {
        if(!((JsonObject)status()).get("ready").getAsBoolean() || catalogue==null)
            throw new IllegalArgumentException("NEI catalogue is still loading; retry nei.status");
    }
    Object search(Request request) throws Exception {
        requireReady();
        JsonObject p=request.params;
        int offset=Json.integer(p,"offset",0,0,1000000),limit=Json.integer(p,"limit",30,1,100);
        String query=Json.string(p,"query","");
        if(query.length()>512) throw new IllegalArgumentException("query exceeds 512 characters");
        String id=Json.string(p,"id",""),mod=Json.string(p,"mod",""),ore=Json.string(p,"ore","");
        int meta=Json.integer(p,"meta",-1,-1,32767);
        String key=Json.GSON.toJson(Json.object("query",query,"id",id,"mod",mod,"ore",ore,"meta",meta));
        List<ItemStack> cached=cachedSearches.get(key);
        if(cached!=null) return searchPage(cached,offset,limit);
        if(searches.size()>=4) throw new IllegalArgumentException("NEI search queue full; wait for an existing search");
        Object filter=call(Class.forName("codechicken.nei.SearchField"),"getFilter",query);
        Method matches=Class.forName("codechicken.nei.api.ItemFilter").getMethod("matches",ItemStack.class);
        searches.add(new Search(request,key,catalogue,generation,filter,matches,id,mod,ore,meta,offset,limit));
        return null;
    }
    /** Bound main-thread search work so observations, cancellation and time control stay responsive. */
    void pump(boolean paused) {
        // NEI presentation has its own lifecycle. It remains live without advancing world/player/Forge ticks.
        GuiScreen gui=Minecraft.getMinecraft().currentScreen;
        if(paused && gui!=null) {
            for(Class<?> type=gui.getClass();type!=null;type=type.getSuperclass()) {
                if(type.getName().equals("codechicken.nei.recipe.GuiRecipe")) { gui.updateScreen();break; }
            }
        }
        long deadline=System.nanoTime()+3_000_000L;
        while(!searches.isEmpty() && System.nanoTime()<deadline) {
            Search task=searches.peek();
            if(task.request.isDone()||task.request.expired()||!task.request.session.connected) {
                task.request.fail("cancelled","NEI search cancelled or expired");searches.remove();continue;
            }
            try {
                if(task.cursor[0]<task.items.size()) {
                    ItemStack stack=task.items.get(task.cursor[0]++);
                    if(stack!=null && task.matches(stack)) task.result.add(stack);
                } else {
                    if(task.generation==generation) remember(cachedSearches,task.key,task.result,16);
                    task.request.reply(searchPage(task.result,task.offset,task.limit));searches.remove();
                }
            } catch(Exception error) { searches.remove();task.request.fail("nei_error",cause(error)); }
        }
    }
    private record Search(Request request,String key,List<ItemStack> items,long generation,Object filter,Method predicate,
                          String id,String mod,String ore,int meta,int offset,int limit,List<ItemStack> result,int[] cursor) {
        Search(Request r,String key,List<ItemStack> items,long gen,Object f,Method predicate,String id,String mod,String ore,int meta,int offset,int limit) {
            this(r,key,items,gen,f,predicate,id,mod,ore,meta,offset,limit,new ArrayList<>(),new int[]{0});
        }
        boolean matches(ItemStack stack) throws Exception {
            String registry=String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem()));
            if(!id.isEmpty()&&!registry.equals(id) || !mod.isEmpty()&&!registry.startsWith(mod+":") || meta>=0&&stack.getItemDamage()!=meta) return false;
            if(!ore.isEmpty() && Arrays.stream(OreDictionary.getOreIDs(stack)).noneMatch(n->OreDictionary.getOreName(n).equals(ore))) return false;
            return Boolean.TRUE.equals(predicate.invoke(filter,stack));
        }
    }
    private JsonObject searchPage(List<ItemStack> result,int offset,int limit) throws Exception {
        List<Object> hits=new ArrayList<>();int end=Math.min(result.size(),offset+limit);
        for(int i=offset;i<end;i++) hits.add(stack(result.get(i)));
        return Json.object("source","NEI","generation",generation,"total",result.size(),"offset",offset,
            "nextOffset",end<result.size()?end:null,"items",hits);
    }
    Object fluids(JsonObject p) throws Exception {
        requireReady();String query=Json.string(p,"query","").toLowerCase(Locale.ROOT);
        int offset=Json.integer(p,"offset",0,0,100000),limit=Json.integer(p,"limit",30,1,100);
        List<Fluid> matches=new ArrayList<>();
        for(Fluid f:FluidRegistry.getRegisteredFluids().values()) {
            if((f.getName()+" "+f.getLocalizedName(new FluidStack(f,1000))).toLowerCase(Locale.ROOT).contains(query)) matches.add(f);
        }
        matches.sort(Comparator.comparing(Fluid::getName));List<Object> values=new ArrayList<>();int end=Math.min(matches.size(),offset+limit);
        for(int i=offset;i<end;i++) {
            Fluid f=matches.get(i);FluidStack fs=new FluidStack(f,1000);
            values.add(Json.object("id",f.getName(),"name",f.getLocalizedName(fs),"temperatureK",f.getTemperature(fs),
                "density",f.getDensity(fs),"viscosity",f.getViscosity(fs),"gaseous",f.isGaseous(fs)));
        }
        return Json.object("source","Forge fluid registry","total",matches.size(),"offset",offset,
            "nextOffset",end<matches.size()?end:null,"fluids",values);
    }
    Object item(JsonObject p) throws Exception {
        requireReady();ItemStack item=resolve(p);JsonObject out=stack(item);
        out.add("tooltip",Json.GSON.toJsonTree(item.getTooltip(Minecraft.getMinecraft().thePlayer,true)));
        if(item.getItem() instanceof net.minecraft.item.ItemBlock)out.add("placement",Json.GSON.toJsonTree(dev.modbench.control.NativePlacement.describe(item)));
        return out;
    }
    private ItemStack resolve(JsonObject p) throws Exception {
        String fluidId=Json.string(p,"fluid","");
        if(!fluidId.isEmpty()) {
            Fluid f=FluidRegistry.getFluid(fluidId);
            if(f==null) throw new IllegalArgumentException("unknown fluid; use nei.fluids first");
            FluidStack fs=new FluidStack(f,Json.integer(p,"amount",1000,1,1000000));
            if(p.has("nbt")&&!p.get("nbt").isJsonNull()) fs.tag=parseTag(p.get("nbt").getAsString());
            return (ItemStack)call(Class.forName("gregtech.api.util.GTUtility"),"getFluidDisplayStack",fs,true);
        }
        String id=Json.string(p,"id","");
        if(id.isBlank() || !Item.itemRegistry.containsKey(id)) throw new IllegalArgumentException("an exact registered item id is required; use nei.search first");
        if(!p.has("meta")||p.get("meta").isJsonNull()) throw new IllegalArgumentException("metadata is required; preserve the value returned by nei.search");
        ItemStack stack=new ItemStack((Item)Item.itemRegistry.getObject(id),1,Json.integer(p,"meta",0,0,32767));
        if(p.has("nbt")&&!p.get("nbt").isJsonNull()) {
            stack.setTagCompound(parseTag(p.get("nbt").getAsString()));
        }
        return stack;
    }
    private NBTTagCompound parseTag(String text) throws Exception {
        if(text.length()>65536) throw new IllegalArgumentException("NBT exceeds 65536 characters");
        NBTBase tag=JsonToNBT.func_150315_a(text);
        if(!(tag instanceof NBTTagCompound compound)) throw new IllegalArgumentException("NBT must be an SNBT compound");
        return compound;
    }
    private List<?> queryHandlers(JsonObject p) throws Exception {
        ItemStack target=resolve(p);String mode=Json.string(p,"mode","recipes");
        if(!mode.equals("recipes")&&!mode.equals("uses")) throw new IllegalArgumentException("mode must be recipes or uses");
        String key=mode+":"+Json.GSON.toJson(stackIdentity(target));List<?> handlers=cachedRecipes.get(key);
        if(handlers==null) {
            Class<?> gui=Class.forName("codechicken.nei.recipe."+(mode.equals("recipes")?"GuiCraftingRecipe":"GuiUsageRecipe"));
            handlers=(List<?>)call(gui,mode.equals("recipes")?"getCraftingHandlers":"getUsageHandlers","item",new Object[]{target.copy()});
            remember(cachedRecipes,key,handlers,12);
        }
        return handlers;
    }
    private String handlerKey(Object handler) throws Exception {
        return call(handler,"getHandlerId")+"|"+call(handler,"getOverlayIdentifier")+"|"+call(handler,"getRecipeName");
    }
    private Object catalysts(Object handler) throws Exception {
        return positions((List<?>)call(Class.forName("codechicken.nei.recipe.RecipeCatalysts"),"getRecipeCatalysts",handler),0,32);
    }
    Object handlers(JsonObject p) throws Exception {
        requireReady();String query=Json.string(p,"query","").toLowerCase(Locale.ROOT);
        int offset=Json.integer(p,"offset",0,0,10000),limit=Json.integer(p,"limit",30,1,100);
        LinkedHashMap<String,Object> all=new LinkedHashMap<>();
        for(String kind:List.of("Crafting","Usage")) {
            Class<?> gui=Class.forName("codechicken.nei.recipe.Gui"+kind+"Recipe");
            for(String list:List.of(kind.toLowerCase(Locale.ROOT)+"handlers","serial"+kind+"Handlers")) {
                for(Object handler:(List<?>)field(gui,list)) all.putIfAbsent(handlerKey(handler),handler);
            }
        }
        List<Object> result=new ArrayList<>();int total=0;
        for(var entry:all.entrySet()) {
            Object handler=entry.getValue();if(!entry.getKey().toLowerCase(Locale.ROOT).contains(query)) continue;
            if(total>=offset && result.size()<limit) result.add(Json.object("key",entry.getKey(),"id",call(handler,"getHandlerId"),
                "name",call(handler,"getRecipeName"),"class",handler.getClass().getName(),"catalysts",catalysts(handler)));
            total++;
        }
        return Json.object("total",total,"offset",offset,"nextOffset",offset+result.size()<total?offset+result.size():null,"handlers",result);
    }
    Object view(JsonObject p) throws Exception {
        requireReady();String wanted=Json.string(p,"handlerKey","");
        if(wanted.isEmpty()) throw new IllegalArgumentException("handlerKey from nei.recipes is required to select the exact category");
        int index=Json.integer(p,"index",0,0,1000000);Object selected=null;
        for(Object handler:queryHandlers(p)) if(handlerKey(handler).equals(wanted)) {selected=handler;break;}
        if(selected==null || index>=((Number)call(selected,"numRecipes")).intValue()) throw new IllegalArgumentException("recipe no longer found; query nei.recipes again");
        String mode=Json.string(p,"mode","recipes");Class<?> type=Class.forName("codechicken.nei.recipe.Gui"+(mode.equals("recipes")?"Crafting":"Usage")+"Recipe");
        Constructor<?> ctor=type.getDeclaredConstructor(ArrayList.class);ctor.setAccessible(true);
        GuiScreen gui=(GuiScreen)ctor.newInstance(new ArrayList<>(List.of(selected)));
        Minecraft.getMinecraft().displayGuiScreen(gui);
        // Page by the native reference index: diagrams can have no ingredient-based RecipeId.
        Class<?> base=Class.forName("codechicken.nei.recipe.GuiRecipe");
        Field pages=base.getDeclaredField("handlerPages");pages.setAccessible(true);
        call(pages.get(gui),"gotoRefIndex",index);
        gui.updateScreen();
        GuiPointer.move(gui,0,gui.height-1);
        List<?> indices=(List<?>)call(gui,"getRecipeIndices");
        if(!indices.contains(index)) throw new IllegalArgumentException("NEI did not expose the requested recipe page");
        return Json.object("opened",true,"handlerKey",wanted,"index",index,"visibleRecipeIndices",indices,
            "guiClass",gui.getClass().getName(),"width",gui.width,"height",gui.height,
            "inspection","Use nei.inspect and sys.screenshot for native tooltips, scrolling, diagrams, aspects and handler-specific text.");
    }
    /** Inspect the real page, including native handler tooltips and mouse-wheel interactions. */
    @SuppressWarnings("unchecked")
    Object inspect(JsonObject p) throws Exception {
        GuiScreen gui=Minecraft.getMinecraft().currentScreen;
        Class<?> base=Class.forName("codechicken.nei.recipe.GuiRecipe");
        if(gui==null || !base.isInstance(gui)) throw new IllegalArgumentException("open a native recipe with nei.view first");
        int x=Json.integer(p,"x",0,0,gui.width-1),y=Json.integer(p,"y",0,0,gui.height-1);
        int scroll=Json.integer(p,"scroll",0,-1,1);
        Minecraft mc=Minecraft.getMinecraft();
        java.awt.Point pointer=GuiPointer.move(gui,x,y);
        // Dispatch before the next Display poll; coordinates are the native GUI's logical pixels.
        if(scroll!=0) {
            if(pointer.x!=x || pointer.y!=y) throw new IllegalArgumentException("native pointer did not reach requested coordinates; retry inspection");
            call(gui,"mouseScrolled",scroll);gui.updateScreen();
        }
        net.minecraft.inventory.Slot slot;
        try { slot=(net.minecraft.inventory.Slot)call(gui,"getSlotAtPosition",x,y); }
        catch(NoSuchMethodException obfuscated) { slot=(net.minecraft.inventory.Slot)call(gui,"func_146975_c",x,y); }
        ItemStack hovered=slot==null?null:slot.getStack();
        List<String> itemTip=hovered==null?new ArrayList<>():new ArrayList<>(hovered.getTooltip(mc.thePlayer,true));
        if(hovered!=null) itemTip=(List<String>)call(gui,"handleItemTooltip",gui,hovered,x,y,itemTip);
        Object tooltip=call(gui,"handleTooltip",gui,x,y,new ArrayList<String>());
        Object hotkeys=call(gui,"handleHotkeys",gui,x,y,new LinkedHashMap<String,String>());
        return Json.object("x",x,"y",y,"width",gui.width,"height",gui.height,"scroll",scroll,
            "nativePointer",Json.object("x",pointer.x,"y",pointer.y),"visibleRecipeIndices",call(gui,"getRecipeIndices"),
            "item",stack(hovered),"itemTooltip",itemTip,"handlerTooltip",tooltip,"hotkeys",hotkeys,
            "coverage","native item/handler tooltip callbacks; draw-only graphics remain available through screenshots");
    }
    Object recipes(JsonObject p) throws Exception {
        requireReady();ItemStack target=resolve(p);String mode=Json.string(p,"mode","recipes");List<?> handlers=queryHandlers(p);
        int offset=Json.integer(p,"offset",0,0,1000000),limit=Json.integer(p,"limit",0,0,20);
        String detail=Json.string(p,"detail","full");
        if(!detail.equals("summary") && !detail.equals("full")) throw new IllegalArgumentException("detail must be summary or full");
        int selectedIndex=Json.integer(p,"index",-1,-1,1000000);
        int altOffset=Json.integer(p,"alternativesOffset",0,0,1000000),altLimit=Json.integer(p,"alternativesLimit",32,1,256);
        String wanted=Json.string(p,"handler","").toLowerCase(Locale.ROOT);
        List<Object> summary=new ArrayList<>(),results=new ArrayList<>();int total=0;
        for(Object handler:handlers) {
            String id=String.valueOf(call(handler,"getHandlerId")),name=String.valueOf(call(handler,"getRecipeName"));
            int count=((Number)call(handler,"numRecipes")).intValue();
            String key=handlerKey(handler);
            JsonObject category=Json.object("key",key,"id",id,"name",name,"count",count,"requirementsCoverage",isGT(handler)?"GregTech native data and view":"native layout and view");
            if(isGT(handler) && count>0) {
                int min=Integer.MAX_VALUE,max=Integer.MIN_VALUE;
                for(Object cached:(List<?>)field(handler,"arecipes")) {
                    int eut=((Number)field(field(cached,"mRecipe"),"mEUt")).intValue();min=Math.min(min,eut);max=Math.max(max,eut);
                }
                category.add("baseEUtRange",Json.object("min",min,"max",max));
            }
            summary.add(category);
            if(!wanted.isEmpty() && !id.toLowerCase(Locale.ROOT).contains(wanted) && !name.toLowerCase(Locale.ROOT).contains(wanted) && !key.toLowerCase(Locale.ROOT).equals(wanted)) continue;
            for(int index=0;index<count;index++,total++) {
                if(selectedIndex>=0 && (!key.toLowerCase(Locale.ROOT).equals(wanted) || index!=selectedIndex)) continue;
                if(total<offset||results.size()>=limit) continue;
                JsonObject recipe=Json.object("handler",id,"handlerKey",key,"name",name,"index",index,
                    "nativeRecipesPerPage",call(handler,"recipiesPerPage"),"catalysts",catalysts(handler),
                    "inputs",positions((List<?>)call(handler,"getIngredientStacks",index),altOffset,altLimit),
                    "result",position(call(handler,"getResultStack",index),altOffset,altLimit),
                    "other",positions((List<?>)call(handler,"getOtherStacks",index),altOffset,altLimit));
                recipe.addProperty("structuredCoverage",isGT(handler)?"native ingredient layout plus GregTech recipe data; native view may contain additional context":"native ingredient layout; use nei.view for custom drawn requirements");
                if(isGT(handler)) enrichGT(recipe,handler,index);
                if(detail.equals("summary")) {
                    compactPositions(recipe,"inputs");compactPositions(recipe,"other");compactPositions(recipe,"catalysts");
                    if(recipe.has("result")) recipe.add("result",compactPosition(recipe.get("result")));
                    recipe.addProperty("detailsRequired",true);
                    recipe.addProperty("summaryNote","Alternative examples are previews. Fetch detail=full with this exact handlerKey and index before choosing ingredients; NBT is omitted from previews.");
                    if(recipe.has("gregtech")) {
                        JsonObject gt=recipe.getAsJsonObject("gregtech");
                        compactStacks(gt,"inputs");compactStacks(gt,"outputs");
                    }
                }
                results.add(recipe);
            }
        }
        if(selectedIndex>=0 && (wanted.isEmpty() || results.isEmpty())) throw new IllegalArgumentException("index lookup requires exact handlerKey in handler, a valid native index, and limit>=1");
        return Json.object("source","NEI","target",stack(target),"mode",mode,"handlers",summary,"total",total,"offset",offset,
            "detail",detail,"overviewOnly",limit==0,"ordering","native NEI ordering; not a progression recommendation",
            "selectionNote","Choose a category and compare its variants against available machines, power, ingredients and research. No progression route is selected automatically.",
            "nextOffset",limit>0 && selectedIndex<0 && offset+results.size()<total?offset+results.size():null,"recipes",results,
            "requirementsNote","GT fields are native recipe data; other handlers expose ingredient layouts. Not every custom GUI requirement is machine-readable.");
    }
    private JsonObject compactStack(JsonElement value) {
        if(value==null || value.isJsonNull()) return null;
        JsonObject source=value.getAsJsonObject(),out=new JsonObject();
        for(String key:List.of("id","meta","name","count","fluid")) if(source.has(key)) out.add(key,source.get(key));
        if(source.has("nbt")) out.addProperty("nbtPresent",true);
        return out;
    }
    private void compactStacks(JsonObject object,String key) {
        JsonArray values=new JsonArray();for(JsonElement stack:object.getAsJsonArray(key)) values.add(compactStack(stack));object.add(key,values);
    }
    private JsonObject compactPosition(JsonElement value) {
        if(value==null || value.isJsonNull()) return null;
        JsonObject source=value.getAsJsonObject();JsonArray examples=new JsonArray();
        JsonArray alternatives=source.getAsJsonArray("alternatives");
        for(int i=0;i<Math.min(2,alternatives.size());i++) examples.add(compactStack(alternatives.get(i)));
        return Json.object("alternativeCount",source.get("alternativeCount"),"exampleOffset",source.get("alternativesOffset"),"examples",examples);
    }
    private void compactPositions(JsonObject object,String key) {
        JsonArray values=new JsonArray();for(JsonElement position:object.getAsJsonArray(key)) values.add(compactPosition(position));object.add(key,values);
    }
    private static boolean isGT(Object handler) {
        for(Class<?> c=handler.getClass();c!=null;c=c.getSuperclass()) if(c.getName().equals("gregtech.nei.GTNEIDefaultHandler")) return true;
        return false;
    }
    private void enrichGT(JsonObject out,Object handler,int index) throws Exception {
        Object cached=((List<?>)field(handler,"arecipes")).get(index),recipe=field(cached,"mRecipe");
        List<Object> inputs=new ArrayList<>(),outputs=new ArrayList<>(),fin=new ArrayList<>(),fout=new ArrayList<>();
        for(ItemStack s:(ItemStack[])field(recipe,"mInputs")) inputs.add(stack(s));
        for(ItemStack s:(ItemStack[])field(recipe,"mOutputs")) outputs.add(stack(s));
        List<Integer> chances=new ArrayList<>();
        for(int i=0;i<outputs.size();i++) chances.add(((Number)call(recipe,"getOutputChance",i)).intValue());
        for(FluidStack f:(FluidStack[])field(recipe,"mFluidInputs")) fin.add(fluid(f));
        for(FluidStack f:(FluidStack[])field(recipe,"mFluidOutputs")) fout.add(fluid(f));
        JsonObject metadata=new JsonObject();
        Object storage=call(recipe,"getMetadataStorage");
        if(storage!=null) for(Object value:(Set<?>)call(storage,"getEntries")) {
            Map.Entry<?,?> entry=(Map.Entry<?,?>)value;Object v=entry.getValue();
            metadata.add(String.valueOf(entry.getKey()),v instanceof Number||v instanceof Boolean||v instanceof String?Json.GSON.toJsonTree(v):new JsonPrimitive(String.valueOf(v)));
        }
        out.add("gregtech",Json.object("recipeMap",field(call(handler,"getRecipeMap"),"unlocalizedName"),
            "durationTicks",field(recipe,"mDuration"),"EUt",field(recipe,"mEUt"),"specialValue",field(recipe,"mSpecialValue"),
            "inputs",inputs,"outputs",outputs,"fluidInputs",fin,"fluidOutputs",fout,
            "outputChancesOutOf10000",chances,"metadata",metadata,
            "specialItems",specialValue(field(recipe,"mSpecialItems")),"enabled",field(recipe,"mEnabled"),
            "fakeRecipe",field(recipe,"mFakeRecipe"),"nbtSensitive",field(recipe,"isNBTSensitive"),"needsEmptyOutput",field(recipe,"mNeedsEmptyOutput"),"neiDescription",call(recipe,"getNeiDesc"),"powerValues","base recipe values before machine overclocking"));
    }
    private Object specialValue(Object value) throws Exception {
        if(value==null || value instanceof Number || value instanceof Boolean || value instanceof String) return value;
        if(value instanceof ItemStack item) return stack(item);
        if(value instanceof FluidStack f) return fluid(f);
        if(value instanceof Iterable<?> values) {
            List<Object> out=new ArrayList<>();for(Object v:values) out.add(specialValue(v));return out;
        }
        if(value.getClass().isArray()) {
            List<Object> out=new ArrayList<>();for(int i=0;i<Array.getLength(value);i++) out.add(specialValue(Array.get(value,i)));return out;
        }
        return Json.object("class",value.getClass().getName(),"text",String.valueOf(value),"structured",false);
    }
    private List<Object> positions(List<?> values,int offset,int limit) throws Exception {
        List<Object> out=new ArrayList<>();if(values!=null) for(Object value:values) out.add(position(value,offset,limit));return out;
    }
    private Object position(Object value,int offset,int limit) throws Exception {
        if(value==null) return null;
        call(value,"generatePermutations");ItemStack[] variants=(ItemStack[])field(value,"items");
        List<Object> alternatives=new ArrayList<>();int end=Math.min(variants.length,offset+limit);
        for(int i=offset;i<end;i++) alternatives.add(stack(variants[i]));
        return Json.object("x",field(value,"relx"),"y",field(value,"rely"),"alternatives",alternatives,
            "alternativeCount",variants.length,"alternativesOffset",offset,"nextAlternativesOffset",end<variants.length?end:null);
    }
    private JsonObject stack(ItemStack s) throws Exception {
        if(s==null) return null;
        JsonObject out=stackIdentity(s);out.addProperty("count",s.stackSize);out.addProperty("name",s.getDisplayName());out.addProperty("maxStackSize",s.getMaxStackSize());
        List<String> ores=new ArrayList<>();for(int id:OreDictionary.getOreIDs(s)) ores.add(OreDictionary.getOreName(id));
        out.add("oreNames",Json.GSON.toJsonTree(ores));
        FluidStack fluid=FluidContainerRegistry.getFluidForFilledItem(s);
        if(fluid==null && s.getItem() instanceof IFluidContainerItem container) fluid=container.getFluid(s);
        if(fluid==null && cpw.mods.fml.common.Loader.isModLoaded("gregtech"))
            fluid=(FluidStack)call(Class.forName("gregtech.api.util.GTUtility"),"getFluidFromContainerOrFluidDisplay",s);
        if(fluid!=null) out.add("fluid",Json.GSON.toJsonTree(fluid(fluid)));
        return out;
    }
    private JsonObject stackIdentity(ItemStack s) {
        return Json.object("id",Item.itemRegistry.getNameForObject(s.getItem()),"meta",s.getItemDamage(),"nbt",s.hasTagCompound()?s.getTagCompound().toString():null);
    }
    private Object fluid(FluidStack f) { return f==null?null:Json.object("id",f.getFluid().getName(),"name",f.getLocalizedName(),"amount",f.amount,"nbt",f.tag==null?null:f.tag.toString()); }
    private static <T> void remember(LinkedHashMap<String,T> cache,String key,T value,int max) {
        cache.put(key,value);while(cache.size()>max) cache.remove(cache.keySet().iterator().next());
    }
    private static Object field(Object target,String name) throws Exception {
        Class<?> type=target instanceof Class<?> c?c:target.getClass();Field f=type.getField(name);f.setAccessible(true);return f.get(target instanceof Class<?>?null:target);
    }
    private static Object call(Object target,String name,Object... args) throws Exception {
        Class<?> type=target instanceof Class<?> c?c:target.getClass();
        for(Method method:type.getMethods()) {
            if(!method.getName().equals(name)||method.getParameterCount()!=args.length) continue;
            try { method.setAccessible(true);return method.invoke(target instanceof Class<?>?null:target,args); }
            catch(IllegalArgumentException wrongSignature) { /* Try the compatible overload. */ }
        }
        throw new NoSuchMethodException(type.getName()+"."+name);
    }
    private static String cause(Exception e) { return String.valueOf(e instanceof InvocationTargetException?e.getCause():e); }
}
