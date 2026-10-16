package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

/** State-only, allocation-free material codes shared by capture and environment verification.
 * Bits 0..15 remain collision/environment flags and fluid height; 16..23 are material,
 * 24..31 are material-specific state. No world or block entity is retained in this table. */
@net.neoforged.fml.common.EventBusSubscriber(modid="createmanaindustry")
public final class PackageBlockMaterial {
    private static final java.util.concurrent.ConcurrentHashMap<BlockState,Integer> CODES=new java.util.concurrent.ConcurrentHashMap<>();
    public static final int ORDINARY=0,POWDER_SNOW=1,SCAFFOLDING=2,WEB=3,BERRY=4,HONEY=5,
            SLIME=6,BED=7,SOUL_SAND=8,CLIMBABLE=9,TRAPDOOR=10,BUBBLE=11,
            WATER=12,LAVA=13,FIRE=14,PORTAL=15,MACHINE=16,CACTUS=17,MAGMA=18,CAMPFIRE=19,
            WITHER_ROSE=20,WATER_CAULDRON=21,LAVA_CAULDRON=22,SNOW_CAULDRON=23,DRIPLEAF=24,
            PRESSURE_PLATE=25,TRIPWIRE=26,REDSTONE_ORE=27,CROP=28,FARMLAND=29,TURTLE_EGG=30,
            SCULK_SENSOR=31,SCULK_SHRIEKER=32,HOPPER=33,FROGSPAWN=34,WATERLILY=35,
            DRILL=36,SAW=37,CRUSHER=38,TURNTABLE=39,STICKER=40,SEAT=41,COPYCAT=42,
            DRIPSTONE=43,BUTTON=44,SAIL=45;
    public static final int MAX_MATERIAL=SAIL;
    private PackageBlockMaterial() {}
    public static int encode(BlockState state) {
        var existing=CODES.get(state);if(existing!=null)return existing;
        int code=classify(state);CODES.putIfAbsent(state,code);return code;
    }
    @net.neoforged.bus.api.SubscribeEvent public static void tagsUpdated(net.neoforged.neoforge.event.TagsUpdatedEvent event){CODES.clear();}
    private static int classify(BlockState state) {
        int material=ORDINARY,parameter=0;
        Block block=state.getBlock();
        if(block==Blocks.POWDER_SNOW)material=POWDER_SNOW;
        else if(block instanceof ScaffoldingBlock&&(!(block instanceof com.simibubi.create.content.decoration.MetalScaffoldingBlock)||!state.getValue(ScaffoldingBlock.BOTTOM))){material=SCAFFOLDING;
            parameter=state.getValue(ScaffoldingBlock.BOTTOM)&&state.getValue(ScaffoldingBlock.DISTANCE)!=0?1:0;}
        else if(block==Blocks.COBWEB)material=WEB;
        else if(block==Blocks.SWEET_BERRY_BUSH){material=BERRY;parameter=state.getValue(SweetBerryBushBlock.AGE);}
        else if(block==Blocks.HONEY_BLOCK)material=HONEY;
        else if(block==Blocks.SLIME_BLOCK)material=SLIME;
        else if(block instanceof BedBlock)material=BED;
        else if(block instanceof com.simibubi.create.content.contraptions.bearing.SailBlock sail&&!sail.isFrame())material=SAIL;
        else if(block instanceof com.simibubi.create.content.decoration.copycat.CopycatBlock)material=COPYCAT;
        else if(block==Blocks.SOUL_SAND)material=SOUL_SAND;
        else if(block==Blocks.BUBBLE_COLUMN){material=BUBBLE;parameter=state.getValue(BubbleColumnBlock.DRAG_DOWN)?1:0;}
        else if(block instanceof TrapDoorBlock)material=state.getValue(TrapDoorBlock.OPEN)?TRAPDOOR:ORDINARY;
        else if(state.is(BlockTags.CLIMBABLE))material=CLIMBABLE;
        else if(block==Blocks.WATER)material=WATER;
        else if(block==Blocks.LAVA)material=LAVA;
        else if(block instanceof BaseFireBlock)material=FIRE;
        else if(block instanceof NetherPortalBlock||block instanceof EndPortalBlock||block instanceof EndGatewayBlock)material=PORTAL;
        else if(block instanceof CactusBlock)material=CACTUS;
        else if(block instanceof MagmaBlock)material=MAGMA;
        else if(block instanceof CampfireBlock){material=CAMPFIRE;parameter=state.getValue(CampfireBlock.LIT)?1:0;}
        else if(block instanceof WitherRoseBlock)material=WITHER_ROSE;
        else if(block==Blocks.WATER_CAULDRON)material=WATER_CAULDRON;
        else if(block==Blocks.LAVA_CAULDRON)material=LAVA_CAULDRON;
        else if(block==Blocks.POWDER_SNOW_CAULDRON)material=SNOW_CAULDRON;
        else if(block instanceof BigDripleafBlock)material=DRIPLEAF;
        else if(block instanceof BasePressurePlateBlock)material=PRESSURE_PLATE;
        else if(block instanceof TripWireBlock)material=TRIPWIRE;
        else if(block instanceof RedStoneOreBlock)material=REDSTONE_ORE;
        // Crop trampling requires a ravager; hopper/lily/frogspawn/button callbacks require
        // an item/boat/falling-block/arrow, and a shrieker requires a player/projectile owner.
        // PackageEntity is none of these. Keep their verified no-op path as ordinary geometry.
        else if(block instanceof CropBlock||block instanceof PitcherCropBlock)material=ORDINARY;
        else if(block instanceof FarmBlock)material=FARMLAND;
        else if(block instanceof TurtleEggBlock)material=TURTLE_EGG;
        else if(block instanceof SculkShriekerBlock)material=ORDINARY;
        else if(block instanceof SculkSensorBlock)material=SCULK_SENSOR;
        else if(block instanceof HopperBlock||block instanceof FrogspawnBlock||block instanceof WaterlilyBlock
                ||block instanceof PointedDripstoneBlock||block instanceof ButtonBlock)material=ORDINARY;
        else {
            // These classes have one state-only semantic; no reflection or world access on capture.
            String name=block.getClass().getName();
            material=switch(name){
                case "com.simibubi.create.content.kinetics.drill.DrillBlock"->DRILL;
                case "com.simibubi.create.content.kinetics.saw.SawBlock"->SAW;
                case "com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlock",
                     "com.simibubi.create.content.kinetics.crusher.CrushingWheelBlock"->CRUSHER;
                case "com.simibubi.create.content.kinetics.turntable.TurntableBlock"->TURNTABLE;
                case "com.simibubi.create.content.contraptions.chassis.StickerBlock"->state.getValue(com.simibubi.create.content.contraptions.chassis.StickerBlock.FACING)==net.minecraft.core.Direction.UP?SLIME:ORDINARY;
                case "com.simibubi.create.content.contraptions.actors.seat.SeatBlock"->ORDINARY;
                case "com.simibubi.create.content.decoration.copycat.CopycatBlock"->COPYCAT;
                default->ORDINARY;
            };
        }
        // PackageEntity.onInsideBlock tests WATERLOGGED after the block's own callback,
        // including otherwise ordinary blocks such as a slab or fence.
        if(material==ORDINARY&&state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
                &&state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED))material=WATER;
        return material<<16|parameter<<24;
    }
}
