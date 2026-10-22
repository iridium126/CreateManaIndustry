package com.iridium126.createmanaindustry.content.fluids.fueltank;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.fluid.SmartFluidTank;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

/** Exercises the production coordinator/serializer against a world that throws on unloaded reads. */
@SuppressWarnings("unchecked")
public class FuelTankWorldTest {
    private static final Map<Level, Fixture> WORLDS = new IdentityHashMap<>();
    private static Class<?> worldClass;
    private static FakeBlock block;
    @BeforeAll static void bootstrap() throws Exception {
        if (net.neoforged.fml.loading.LoadingModList.get() == null)
            net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        block = allocate(FakeBlock.class);
        BlockState state = allocate(BlockState.class);
        field(state, net.minecraft.world.level.block.state.StateHolder.class, "owner", block);
        field(block, Block.class, "defaultBlockState", state);
        ClassWriter writer = new ClassWriter(0);
        String name = "com/iridium126/createmanaindustry/content/fluids/fueltank/HeadlessFuelWorld";
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "net/minecraft/world/level/Level", null);
        Map<String, Method> abstracts = new HashMap<>();
        for (Method method : Level.class.getMethods()) if (Modifier.isAbstract(method.getModifiers()))
            abstracts.put(method.getName() + org.objectweb.asm.Type.getMethodDescriptor(method), method);
        for (Method method : Level.class.getDeclaredMethods()) if (Modifier.isAbstract(method.getModifiers()))
            abstracts.put(method.getName() + org.objectweb.asm.Type.getMethodDescriptor(method), method);
        for (Method method : abstracts.values()) {
            MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, method.getName(), org.objectweb.asm.Type.getMethodDescriptor(method), null, null);
            mv.visitCode(); Class<?> result = method.getReturnType();
            if (result == void.class) mv.visitInsn(Opcodes.RETURN);
            else if (result == long.class) { mv.visitInsn(Opcodes.LCONST_0); mv.visitInsn(Opcodes.LRETURN); }
            else if (result == double.class) { mv.visitInsn(Opcodes.DCONST_0); mv.visitInsn(Opcodes.DRETURN); }
            else if (result == float.class) { mv.visitInsn(Opcodes.FCONST_0); mv.visitInsn(Opcodes.FRETURN); }
            else if (result.isPrimitive()) { mv.visitInsn(Opcodes.ICONST_0); mv.visitInsn(Opcodes.IRETURN); }
            else { mv.visitInsn(Opcodes.ACONST_NULL); mv.visitInsn(Opcodes.ARETURN); }
            mv.visitMaxs(2, 30); mv.visitEnd();
        }
        override(writer, "isLoaded", "(Lnet/minecraft/core/BlockPos;)Z", "loaded", Opcodes.IRETURN);
        override(writer, "getBlockState", "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", "state", Opcodes.ARETURN);
        override(writer, "getBlockEntity", "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;", "entity", Opcodes.ARETURN);
        writer.visitEnd();
        worldClass = new ClassLoader(FuelTankWorldTest.class.getClassLoader()) {
            Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
        }.define(writer.toByteArray());
    }
    private static void override(ClassWriter writer, String name, String descriptor, String target, int result) {
        var mv = writer.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null); mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD,0); mv.visitVarInsn(Opcodes.ALOAD,1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, org.objectweb.asm.Type.getInternalName(FuelTankWorldTest.class), target,
            "(Lnet/minecraft/world/level/Level;" + descriptor.substring(1),false);
        mv.visitInsn(result); mv.visitMaxs(2,2); mv.visitEnd();
    }
    public static boolean loaded(Level world, BlockPos pos) { return !WORLDS.get(world).unloaded.contains(Math.floorDiv(pos.getX(),16)); }
    public static BlockState state(Level world, BlockPos pos) {
        if (!loaded(world,pos)) throw new AssertionError("Forced unloaded block read: " + pos);
        return WORLDS.get(world).parts.containsKey(pos) ? block.defaultBlockState() : Blocks.AIR.defaultBlockState();
    }
    public static BlockEntity entity(Level world, BlockPos pos) {
        if (!loaded(world,pos)) throw new AssertionError("Forced unloaded entity read: " + pos);
        return WORLDS.get(world).parts.get(pos);
    }
    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe"); Field field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance",Class.class).invoke(field.get(null),type));
    }
    private static void field(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field=owner.getDeclaredField(name); field.setAccessible(true); field.set(target,value);
    }
    static final class Fixture {
        final Level world;
        final Map<BlockPos,FakeTank> parts = new HashMap<>();
        final Set<Integer> unloaded = new HashSet<>();
        Fixture() throws Exception {
            world=(Level)allocate(worldClass); WORLDS.put(world,this);
            field(world,Level.class,"registryAccess",RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        }
        FakeTank add(BlockPos pos, int amount) throws Exception {
            FakeTank tank=allocate(FakeTank.class);
            field(tank,BlockEntity.class,"worldPosition",pos); field(tank,BlockEntity.class,"level",world);
            field(tank,BlockEntity.class,"blockState",block.defaultBlockState());
            tank.count=1; tank.tankInventory=new SmartFluidTank(8000,tank::onFluidStackChanged);
            tank.tankInventory.setFluid(amount==0?FluidStack.EMPTY:new FluidStack(Fluids.WATER,amount));
            tank.basins=FuelTankFluidStateTest.state(Set.of(pos)); tank.basins.settle(amount);
            parts.put(pos,tank); tank.refreshCapability(); return tank;
        }
    }
    static class FakeBlock extends FuelTankBlock {
        FakeBlock() { super(Block.Properties.of()); }
        @Override public net.minecraft.world.level.block.entity.BlockEntityType<? extends com.simibubi.create.content.decoration.copycat.CopycatBlockEntity> getBlockEntityType() { return null; }
    }
    static class FakeTank extends FuelTankBlockEntity {
        private FakeTank() { super(null,BlockPos.ZERO,null); }
        @Override public void invalidateCapabilities() {}
        @Override protected void updateConnectivity() { updateConnectivity=false; FuelTankConnectivity.updateConnectivity(this); }
        @Override public void sendData() {}
        @Override public void setChanged() {}
        @Override void finishFluidChange(boolean structural) {}
        @Override public void forEachBehaviour(Consumer<BlockEntityBehaviour> action) {}
        @Override protected void write(CompoundTag tag, HolderLookup.Provider registries, ItemStack stack, BlockState material) {}
        CompoundTag saved(HolderLookup.Provider registries) { CompoundTag tag=new CompoundTag();write(tag,registries,false);return tag; }
    }

    @Test void productionMergeIncludesTheNewControllerAndPreservesTwoSources() throws Exception {
        Fixture fixture=new Fixture();
        FakeTank left=fixture.add(new BlockPos(0,64,0),1000), right=fixture.add(new BlockPos(2,64,0),1000);
        FakeTank bridge=fixture.add(new BlockPos(1,64,0),0);
        FuelTankConnectivity.updateConnectivity(bridge);
        assertEquals(2000,left.getTankInventory().getFluidAmount()); assertEquals(2000,left.basins.total());
        assertSame(left,right.getControllerBE()); assertEquals(0,right.getTankInventory().getFluidAmount());
        fixture.parts.remove(bridge.getBlockPos()); FuelTankConnectivity.split(bridge);
        assertEquals(left.getTankInventory().getFluidAmount(),left.basins.total());
        assertEquals(right.getTankInventory().getFluidAmount(),right.basins.total());
        assertEquals(1333,left.basins.total()+right.basins.total());
    }

    @Test void partialChunksRetainTheFullInventoryAndRecoverWithoutLoadingNeighbors() throws Exception {
        Fixture fixture=new Fixture(); FakeTank left=fixture.add(new BlockPos(15,64,0),7000);
        fixture.add(new BlockPos(16,64,0),1000); FuelTankConnectivity.updateConnectivity(left);
        assertEquals(8000,left.basins.total()); fixture.unloaded.add(1);
        FuelTankConnectivity.updateConnectivity(left);
        assertEquals(8000,left.getTankInventory().getFluidAmount()); assertEquals(2,left.count); assertTrue(left.retryConnectivity);
        fixture.unloaded.clear(); FuelTankConnectivity.updateConnectivity(left);
        assertFalse(left.retryConnectivity); assertEquals(8000,left.basins.total());
    }

    @Test void serializationDoesNotConsumeInitializationOrIncrementalGeometry() throws Exception {
        Fixture fixture=new Fixture(); FakeTank tank=fixture.add(new BlockPos(0,64,0),1000);
        var registries=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        tank.markBasinsDirty(); tank.saved(registries);
        CompoundTag first=tank.getUpdateTag(registries), second=tank.getUpdateTag(registries);
        assertTrue(first.contains("BasinGeometry")); assertEquals(first,second);
        CompoundTag update=tank.getUpdatePacket().getTag();
        assertTrue(update.contains("BasinGeometry"));
        tank.getTankInventory().setFluid(new FluidStack(Fluids.WATER,1050));
        update=tank.getUpdatePacket().getTag();
        assertFalse(update.contains("BasinGeometry")); assertTrue(update.contains("Volumes"));
        assertTrue(tank.getUpdateTag(registries).contains("BasinGeometry"));
    }

    @Test void incompatibleFluidsAndComponentVariantsRemainSeparate() throws Exception {
        for(boolean components:new boolean[]{false,true}) {
            Fixture fixture=new Fixture();FakeTank left=fixture.add(new BlockPos(0,64,0),1000);
            FakeTank right=fixture.add(new BlockPos(2,64,0),1000),bridge=fixture.add(new BlockPos(1,64,0),0);
            FluidStack variant=new FluidStack(components?Fluids.WATER:Fluids.LAVA,1000);
            if(components)variant.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("variant"));
            right.getTankInventory().setFluid(variant);
            FuelTankConnectivity.updateConnectivity(bridge);
            assertNotSame(left.getControllerBE(),right.getControllerBE());
            assertEquals(1000,left.getControllerBE().getTankInventory().getFluidAmount());
            assertEquals(1000,right.getControllerBE().getTankInventory().getFluidAmount());
            assertTrue(FluidStack.isSameFluidSameComponents(variant,right.getControllerBE().getTankInventory().getFluid()));
        }
    }

    @Test void clientDropsGeometryWhenControllerChangesOrGeometryIsExplicitlyCleared() throws Exception {
        Fixture server=new Fixture(),client=new Fixture();
        var registries=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        FakeTank source=server.add(new BlockPos(0,64,0),1000);
        FakeTank target=client.add(new BlockPos(0,64,0),0);
        target.readClient(source.getUpdateTag(registries),registries);
        assertNotNull(target.basins); assertEquals(1000,target.basins.total());
        source.basins=null; target.readClient(source.getUpdateTag(registries),registries); assertNull(target.basins);
        source.basins=FuelTankFluidStateTest.state(Set.of(source.getBlockPos()));source.basins.settle(1000);
        target.readClient(source.getUpdateTag(registries),registries);assertNotNull(target.basins);
        source.controller=new BlockPos(1,64,0);
        target.readClient(source.getUpdateTag(registries),registries);assertNull(target.basins);assertFalse(target.isController());
    }

    @Test void capabilityTransactionsMaintainExactDistributionWithoutRebuildingGeometry() throws Exception {
        Fixture fixture=new Fixture(); FakeTank tank=fixture.add(new BlockPos(0,300,0),1000);
        var geometry=tank.basins.geometry();
        var handler=tank.getFluidCapability();
        assertEquals(50,handler.fill(new FluidStack(Fluids.WATER,50),net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE));
        assertEquals(1000,tank.basins.total());
        for(int i=0;i<40;i++)handler.fill(new FluidStack(Fluids.WATER,50),net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
        assertEquals(3000,tank.basins.total()); assertSame(geometry,tank.basins.geometry());
        handler.drain(1751,net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
        assertEquals(1249,tank.basins.total()); assertEquals(1249,tank.tankInventory.getFluidAmount());
    }

    @Test void removingControllerAcrossUnloadedChunksPreservesUnloadedSharesUntilHeal() throws Exception {
        Fixture fixture=new Fixture();
        FakeTank owner=fixture.add(new BlockPos(14,64,0),7000);
        FakeTank carrier=fixture.add(new BlockPos(15,64,0),0);
        fixture.add(new BlockPos(16,64,0),0); FuelTankConnectivity.updateConnectivity(owner);
        long lost=owner.basins.cellAmounts().get(owner.getBlockPos());
        fixture.unloaded.add(1); fixture.parts.remove(owner.getBlockPos()); FuelTankConnectivity.split(owner);
        assertTrue(carrier.needsDeferredSplitHeal); assertEquals(7000-lost,carrier.basins.total());
        fixture.unloaded.clear(); FuelTankConnectivity.healDeferredSplit(carrier);
        assertFalse(carrier.needsDeferredSplitHeal); assertEquals(7000-lost,carrier.basins.total());
        assertEquals(carrier.tankInventory.getFluidAmount(),carrier.basins.total());
    }

    @Test void arbitraryShapeTranslationRetainsDistributionAndPerCellMaterialTags() throws Exception {
        Set<BlockPos> cells=Set.of(new BlockPos(1,0,0),new BlockPos(1,1,0),new BlockPos(0,1,0));
        FuelTankFluidState source=FuelTankFluidStateTest.state(cells); source.fill(new BlockPos(1,0,0),9123);
        
        Fixture fixture=new Fixture();
        var transform=new com.simibubi.create.content.contraptions.StructureTransform(new BlockPos(10,64,0),0,0,0);
        var placement=FuelTankContraptionBridge.begin(fixture.world,transform,List.of(new FuelTankContraptionBridge.MovingGroup(source,new FluidStack(Fluids.WATER,9123),cells)));
        for(BlockPos local:cells) {
            FakeTank tank=fixture.add(transform.apply(local),0); FuelTankContraptionBridge.placed(tank);
            assertTrue(FuelTankContraptionBridge.isPlacing(fixture.world));
            assertEquals(0,tank.getTankInventory().getFluidAmount());
        }
        FuelTankContraptionBridge.end(placement,true);
        var owner=fixture.parts.get(transform.apply(source.geometry().origin)).getControllerBE();
        assertNotNull(owner); assertEquals(9123,owner.basins.total());
        Map<BlockPos,Long> expected=new HashMap<>(); source.cellAmounts().forEach((p,a)->expected.put(transform.apply(p),a));
        assertEquals(expected,owner.basins.cellAmounts());
        for(BlockPos cell:cells) { CompoundTag tag=new CompoundTag();tag.putString("Material",cell.toShortString());tag.putBoolean("Window",true);FuelTankContraptionBridge.prepareCellTag(tag);assertEquals(cell.toShortString(),tag.getString("Material"));assertTrue(tag.getBoolean("Window")); }
        assertFalse(FuelTankContraptionBridge.isPlacing(fixture.world));
    }

    @Test void rotationResettlesAndMovingTransfersUseCurrentMountedAmount() throws Exception {
        Set<BlockPos> cells=Set.of(new BlockPos(0,0,0),new BlockPos(1,0,0),new BlockPos(2,0,0));
        for(float[] angles:new float[][]{{0,90,0},{90,0,0},{0,0,90}}) {
            FuelTankFluidState source=FuelTankFluidStateTest.state(cells); source.fill(BlockPos.ZERO,1000);
            Fixture fixture=new Fixture();
            var transform=new com.simibubi.create.content.contraptions.StructureTransform(new BlockPos(10,64,0),angles[0],angles[1],angles[2]);
            var placement=FuelTankContraptionBridge.begin(fixture.world,transform,List.of(new FuelTankContraptionBridge.MovingGroup(source,new FluidStack(Fluids.WATER,2500),cells)));
            Set<BlockPos> targets=new HashSet<>();
            for(BlockPos pos:cells) { BlockPos target=transform.apply(pos); targets.add(target); FuelTankContraptionBridge.placed(fixture.add(target,0)); }
            FuelTankContraptionBridge.end(placement,true);
            var owner=fixture.parts.values().iterator().next().getControllerBE();
            FuelTankFluidState expected=FuelTankFluidStateTest.state(targets); expected.settle(2500);
            assertEquals(2500,owner.basins.total()); assertEquals(expected.cellAmounts(),owner.basins.cellAmounts());
        }
    }

    @Test void failedMovingCellLosesOnlyItsShareAndLandingMergesExistingFluidOnce() throws Exception {
        Set<BlockPos> cells=Set.of(new BlockPos(0,0,0),new BlockPos(1,0,0),new BlockPos(2,0,0));
        FuelTankFluidState source=FuelTankFluidStateTest.state(cells); source.settle(9000);
        
        Fixture fixture=new Fixture(); fixture.add(new BlockPos(9,64,0),1000);
        var transform=new com.simibubi.create.content.contraptions.StructureTransform(new BlockPos(10,64,0),0,0,0);
        var placement=FuelTankContraptionBridge.begin(fixture.world,transform,List.of(new FuelTankContraptionBridge.MovingGroup(source,new FluidStack(Fluids.WATER,9000),cells)));
        for(BlockPos pos:cells) if(pos.getX()!=2)FuelTankContraptionBridge.placed(fixture.add(transform.apply(pos),0));
        FuelTankContraptionBridge.end(placement,true);
        var owner=fixture.parts.get(new BlockPos(9,64,0)).getControllerBE();
        assertEquals(7000,owner.basins.total()); assertEquals(7000,owner.getTankInventory().getFluidAmount());
    }
}

