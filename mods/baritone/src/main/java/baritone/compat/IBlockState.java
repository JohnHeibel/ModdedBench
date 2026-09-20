// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.material.Material;
import net.minecraft.world.IBlockAccess;

/** A 1.7 block plus metadata; never invents a metadata-zero state for a position. */
public final class IBlockState {
    private final Block block;
    public final int meta;
    private final IBlockAccess access;
    public final int x,y,z;
    private net.minecraft.item.ItemStack placementItem;
    private StackIdentity placementIdentity;
    private boolean approximateMaterial;
    public IBlockState(Block block,int meta,IBlockAccess access,int x,int y,int z){this.block=java.util.Objects.requireNonNull(block);this.meta=meta;this.access=access;this.x=x;this.y=y;this.z=z;}
    public Block getBlock(){return block;}
    public Material getMaterial(){return block.getMaterial();}
    public static IBlockState of(Block block,int meta){return new IBlockState(block,meta,null,0,0,0);}
    public IBlockState withPlacementItem(net.minecraft.item.ItemStack item){IBlockState state=new IBlockState(block,meta,access,x,y,z);state.placementItem=item==null?null:item.copy();state.placementIdentity=StackIdentity.capture(item);return state;}
    public StackIdentity placementIdentity(){return placementIdentity;}
    public IBlockState asApproximateMaterial(){approximateMaterial=true;return this;}
    public boolean isApproximateMaterial(){return approximateMaterial;}
    public net.minecraft.item.ItemStack placementItem(){return placementItem==null?null:placementItem.copy();}
    @Override public boolean equals(Object other){return other instanceof IBlockState state&&block==state.block&&meta==state.meta;}
    @Override public int hashCode(){return 31*System.identityHashCode(block)+meta;}
    @Override public String toString(){return Registry.name(block)+":"+meta;}
    public <T> T getValue(LegacyProperties.Property<T> p){return p.read().apply(this);}
    public boolean getValue(LegacyProperties.PropertyBool p){return p.read().apply(this);}
    int effectiveDoorMeta(){return block instanceof BlockDoor&&(meta&8)!=0&&access!=null?access.getBlockMetadata(x,y-1,z):meta;}
    public boolean isBlockNormalCube(){return block.isBlockNormalCube();}
    public boolean isFullCube(){return block.isBlockNormalCube();}
    public boolean isFullBlock(){return block.isBlockNormalCube();}
    public AxisAlignedBB getBoundingBox(World world,BlockPos p){
        // 1.7 stores mutable selection bounds on Block, so only native game-thread callers may read them.
        if(!net.minecraft.client.Minecraft.getMinecraft().func_152345_ab())throw new IllegalStateException("native selection bounds require the client thread");
        synchronized(block){
            block.setBlockBoundsBasedOnState(world.nativeWorld,p.getX(),p.getY(),p.getZ());
            var selected=dev.modbench.api.ControlRegistry.targeting().withContext(()->block.getSelectedBoundingBoxFromPool(world.nativeWorld,p.getX(),p.getY(),p.getZ()));
            if(selected!=null)return new AxisAlignedBB(selected.minX-p.getX(),selected.minY-p.getY(),selected.minZ-p.getZ(),selected.maxX-p.getX(),selected.maxY-p.getY(),selected.maxZ-p.getZ());
            return new AxisAlignedBB(block.getBlockBoundsMinX(),block.getBlockBoundsMinY(),block.getBlockBoundsMinZ(),block.getBlockBoundsMaxX(),block.getBlockBoundsMaxY(),block.getBlockBoundsMaxZ());
        }
    }
    public int stateId(){return Block.getIdFromBlock(block)<<4|meta&15;}
    public record StateKey(Block block,int meta){}
    public StateKey key(){return new StateKey(block,meta);}
    public boolean isPassable(IBlockAccess view){return block.getBlocksMovement(view,x,y,z);}
    public boolean isReplaceable(IBlockAccess view){return block.isReplaceable(view,x,y,z);}
    public boolean isDoubleSlab(){return block instanceof net.minecraft.block.BlockSlab&&block.isOpaqueCube();}
    public boolean canFallThrough(){return block==Blocks.AIR||block==Blocks.FIRE||getMaterial()==Material.water||getMaterial()==Material.lava;}
}
