// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.server;

import com.google.gson.JsonObject;
import dev.modbench.bridge.Json;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.*;

/** Native GT recipe, cable, item output and fluid-pipe fixtures inside TimeFixture's recovery volume. */
final class ProgressionFixture {
    private final WorldServer world;
    private final int ox, oz;
    private final Map<String,TileEntity> tiles=new LinkedHashMap<>();
    private final JsonObject recipes=new JsonObject();
    ProgressionFixture(WorldServer world,int ox,int oz) { this.world=world;this.ox=ox;this.oz=oz; }
    void create() throws Exception {
        Object[] catalog=(Object[])Class.forName("gregtech.api.GregTechAPI").getField("METATILEENTITIES").get(null);
        int cable=find(catalog,o->o.getClass().getSimpleName().equals("MTECable")
            && number(o,"mVoltage")==32 && number(o,"mAmperage")>=1 && Boolean.TRUE.equals(field(o,"mInsulated")));
        int pipe=find(catalog,o->o.getClass().getSimpleName().equals("MTEFluidPipe")
            && field(o,"mMaterial").toString().equals("Copper"));
        int tank=find(catalog,o->o.getClass().getSimpleName().equals("MTESuperTank"));
        machine("generator",1110,18,4,catalog);face("generator",ForgeDirection.EAST);
        machine("cable",cable,19,4,catalog);
        machine("macerator",301,20,4,catalog);face("macerator",ForgeDirection.EAST);
        world.setBlock(ox+21,176,oz+4,Blocks.chest,0,3);tiles.put("chest",world.getTileEntity(ox+21,176,oz+4));
        machine("extractor",511,24,4,catalog);face("extractor",ForgeDirection.EAST);
        machine("pipe",pipe,25,4,catalog);
        machine("tank",tank,26,4,catalog);
        for(String name:List.of("cable","pipe")) for(ForgeDirection dir:List.of(ForgeDirection.WEST,ForgeDirection.EAST)) call(meta(name),"connect",dir);
        set(meta("macerator"),"mItemTransfer",true);set(meta("extractor"),"mFluidTransfer",true);
        // Source energy is a fixture input. The macerator receives EU exclusively through its native cable.
        call(tiles.get("generator"),"setStoredEU",Math.min(32000L,((Number)call(tiles.get("generator"),"getEUCapacity")).longValue()));
        call(tiles.get("extractor"),"setStoredEU",Math.min(32000L,((Number)call(tiles.get("extractor"),"getEUCapacity")).longValue()));
        loadRecipe("macerator","maceratorRecipes",false);
        loadRecipe("extractor","fluidExtractionRecipes",true);
    }
    private void machine(String name,int id,int x,int z,Object[] catalog) throws Exception {
        Block block=(Block)Class.forName("gregtech.api.GregTechAPI").getField("sBlockMachines").get(null);
        int base=((Number)call(catalog[id],"getTileEntityBaseType")).intValue();
        world.setBlock(ox+x,176,oz+z,block,base,3);
        TileEntity te=world.getTileEntity(ox+x,176,oz+z);tiles.put(name,te);
        call(te,"setInitialValuesAsNBT",null,(short)id);
        call(te,"setOwnerUuid",world.getPlayerEntityByName("ModbenchDev").getUniqueID());
        call(meta(name),"initDefaultModes",new Object[]{null});
    }
    private void face(String name,ForgeDirection direction) throws Exception {
        Object mte=meta(name);
        if(name.equals("macerator")||name.equals("extractor")) {
            // This is already a configured current-format fixture, not a legacy machine whose first tick flips its face.
            set(mte,"mHasBeenUpdated",true);
            call(mte,"setMainFacing",ForgeDirection.NORTH);
        }
        call(tiles.get(name),"setFrontFacing",direction);
    }
    private void loadRecipe(String name,String mapName,boolean fluid) throws Exception {
        Object map=Class.forName("gregtech.api.recipe.RecipeMaps").getField(mapName).get(null);
        List<Object> candidates=new ArrayList<>((Collection<?>)call(map,"getAllRecipes"));
        candidates.sort(Comparator.comparing(o->{try { return Arrays.toString((ItemStack[])field(o,"mInputs")); }catch(Exception e){throw new IllegalStateException(e);}}));
        for(Object recipe:candidates) {
            ItemStack[] inputs=(ItemStack[])field(recipe,"mInputs"), outputs=(ItemStack[])field(recipe,"mOutputs");
            FluidStack[] fin=(FluidStack[])field(recipe,"mFluidInputs"), fout=(FluidStack[])field(recipe,"mFluidOutputs");
            long duration=number(recipe,"mDuration"), eu=number(recipe,"mEUt");
            if(inputs.length!=1 || inputs[0]==null || inputs[0].stackSize!=1 || inputs[0].getItemDamage()==32767
                || fin.length!=0 || duration<20 || duration>160 || eu<1 || eu>32 || number(recipe,"mSpecialValue")!=0
                || Boolean.TRUE.equals(field(recipe,"mFakeRecipe"))) continue;
            String id=String.valueOf(Item.itemRegistry.getNameForObject(inputs[0].getItem()));
            if(!id.startsWith("minecraft:") || inputs[0].getMaxStackSize()<8) continue;
            if(fluid) { if(fout.length!=1 || !fout[0].getFluid().getName().equals("water")) continue; }
            else {
                if(outputs.length!=1 || outputs[0]==null || fout.length!=0 || ((Number)call(recipe,"getOutputChance",0)).intValue()!=10000) continue;
            }
            ItemStack input=inputs[0].copy();input.stackSize=8;
            ((IInventory)tiles.get(name)).setInventorySlotContents(((Number)call(meta(name),"getInputSlot")).intValue(),input);
            recipes.add(name,Json.object("input",stack(input),"duration",duration,"EUt",eu,
                "output",fluid?fluid(fout[0]):stack(outputs[0])));
            return;
        }
        throw new IllegalStateException("no simple deterministic GT fixture recipe: "+mapName);
    }
    JsonObject status() throws Exception {
        JsonObject out=Json.object("recipes",recipes);
        for(var entry:tiles.entrySet()) {
            String name=entry.getKey();TileEntity te=entry.getValue();
            JsonObject state=Json.object("class",te.getClass().getName());
            if(te instanceof IInventory inv) {
                List<Object> inventory=new ArrayList<>();
                for(int slot=0;slot<inv.getSizeInventory();slot++) inventory.add(stack(inv.getStackInSlot(slot)));
                state.add("inventory",Json.GSON.toJsonTree(inventory));
            }
            if(!name.equals("chest")) {
                Object mte=meta(name);state.addProperty("metaClass",mte.getClass().getName());
                state.addProperty("front",String.valueOf(call(te,"getFrontFacing")));
                state.addProperty("EU",((Number)call(te,"getStoredEU")).longValue());
                if(name.equals("macerator")||name.equals("extractor")) {
                    for(String f:List.of("mProgresstime","mMaxProgresstime","mEUt")) state.addProperty(f,number(mte,f));
                    state.add("pendingFluid",Json.GSON.toJsonTree(fluid((FluidStack)field(mte,"mOutputFluid"))));
                }
                if(te instanceof IFluidHandler handler) {
                    List<Object> tanks=new ArrayList<>();
                    for(FluidTankInfo tank:handler.getTankInfo(ForgeDirection.UNKNOWN)) if(tank!=null) tanks.add(fluid(tank.fluid));
                    state.add("fluids",Json.GSON.toJsonTree(tanks));
                }
            }
            out.add(name,state);
        }
        return out;
    }
    private Object meta(String name) throws Exception { return call(tiles.get(name),"getMetaTileEntity"); }
    private interface Match { boolean test(Object value) throws Exception; }
    private static int find(Object[] catalog,Match match) throws Exception {
        for(int id=1;id<catalog.length;id++) if(catalog[id]!=null && match.test(catalog[id])) return id;
        throw new IllegalStateException("GT fixture component not found");
    }
    private static Object stack(ItemStack s) { return s==null?null:Json.object("id",Item.itemRegistry.getNameForObject(s.getItem()),"meta",s.getItemDamage(),"count",s.stackSize,"nbt",s.hasTagCompound()?s.getTagCompound().toString():null); }
    private static Object fluid(FluidStack f) { return f==null?null:Json.object("id",f.getFluid().getName(),"amount",f.amount); }
    private static Object field(Object o,String name) throws Exception { return o.getClass().getField(name).get(o); }
    private static long number(Object o,String name) throws Exception { return ((Number)field(o,name)).longValue(); }
    private static void set(Object o,String name,Object value) throws Exception { o.getClass().getField(name).set(o,value); }
    private static Object call(Object o,String name,Object... args) throws Exception {
        for(Method method:o.getClass().getMethods()) {
            if(!method.getName().equals(name)||method.getParameterCount()!=args.length) continue;
            try { return method.invoke(o,args); }
            catch(IllegalArgumentException wrongSignature) { /* Try a compatible overload. */ }
            catch(InvocationTargetException e) { throw new IllegalStateException(name+" failed: "+e.getCause(),e.getCause()); }
        }
        throw new NoSuchMethodException(o.getClass().getName()+"."+name);
    }
}
