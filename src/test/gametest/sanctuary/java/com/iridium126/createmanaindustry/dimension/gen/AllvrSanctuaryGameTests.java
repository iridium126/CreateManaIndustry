package com.iridium126.createmanaindustry.dimension.gen;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class AllvrSanctuaryGameTests {
    private static ProtoChunk generate(GameTestHelper helper,AllvrSanctuaryGenerator generator,ChunkPos pos) {
        var registry=helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        var chunk=new ProtoChunk(pos,UpgradeData.EMPTY,LevelHeightAccessor.create(-128,512),registry,null);
        var block=new BlockPos.MutableBlockPos();
        for(int z=0;z<16;z++) for(int x=0;x<16;x++) for(int y=-16;y<=120;y++)
            chunk.setBlockState(block.set(pos.getMinBlockX()+x,y,pos.getMinBlockZ()+z),
                (y==30?Blocks.WATER:Blocks.STONE).defaultBlockState(),false);
        Heightmap.primeHeightmaps(chunk,Set.of(Heightmap.Types.OCEAN_FLOOR_WG,Heightmap.Types.WORLD_SURFACE_WG));
        generator.sculpt(chunk,false);
        generator.sculpt(chunk,true);
        generator.structures(helper.getLevel(),chunk);
        generator.decorate(chunk);
        return chunk;
    }

    @GameTest(template="worldgen_test",timeoutTicks=2400)
    public static void vanillaGeodesRootsNetworkAndChunkOrder(GameTestHelper helper) {
        var forwardGenerator=new AllvrSanctuaryGenerator(137,63,Blocks.WATER.defaultBlockState());
        var reverseGenerator=new AllvrSanctuaryGenerator(137,63,Blocks.WATER.defaultBlockState());
        Set<Long> targets=new LinkedHashSet<>();
        for(int[] p:new int[][]{{0,0},{5,0},{6,0},{9,0},{12,0},{-10,-1},{-10,0},{30,0},{43,0},{44,0}})
            targets.add(ChunkPos.asLong(p[0],p[1]));
        for(var p:forwardGenerator.network.geodes()) {
            for(int z=((int)Math.floor(p.z()-23))>>4;z<=((int)Math.ceil(p.z()+13))>>4;z++)
            for(int x=((int)Math.floor(p.x()-23))>>4;x<=((int)Math.ceil(p.x()+13))>>4;x++) targets.add(ChunkPos.asLong(x,z));
        }
        for(var route:forwardGenerator.network.routes()) if(route.kind().contains("bridge")) {
            var p=route.points().get(route.points().size()/2);
            targets.add(ChunkPos.asLong(((int)Math.floor(p.x()))>>4,((int)Math.floor(p.z()))>>4));
        }
        Map<Long,ProtoChunk> expected=new HashMap<>();
        for(long key:targets) expected.put(key,generate(helper,forwardGenerator,new ChunkPos(key)));
        var reverse=new ArrayList<>(targets);Collections.reverse(reverse);
        var pos=new BlockPos.MutableBlockPos();
        int checked=0,amethyst=0,budding=0,clusters=0,roots=0,ropes=0,gates=0,lanterns=0,clear=0;
        for(long key:reverse) {
            var actual=generate(helper,reverseGenerator,new ChunkPos(key));
            var original=expected.get(key);int x0=actual.getPos().getMinBlockX(),z0=actual.getPos().getMinBlockZ();
            for(int z=z0;z<z0+16;z++) for(int x=x0;x<x0+16;x++) for(int y=-16;y<=110;y++) {
                var state=actual.getBlockState(pos.set(x,y,z));
                helper.assertTrue(state==original.getBlockState(pos),"Chunk order changed "+pos);checked++;
                int material=forwardGenerator.network.get(x,y,z);
                if(material==SanctuaryNetwork.CLEAR) { helper.assertTrue(state.isAir(),"Blocked reserved headroom at "+pos);clear++; }
                if(state.is(Blocks.AMETHYST_BLOCK)) amethyst++;
                if(state.is(Blocks.BUDDING_AMETHYST)) budding++;
                if(state.getBlock() instanceof net.minecraft.world.level.block.AmethystClusterBlock) clusters++;
                if(state.is(Blocks.DARK_OAK_WOOD) || state.is(Blocks.STRIPPED_DARK_OAK_WOOD)) roots++;
                if(state.getBlock() instanceof FenceBlock) ropes++;
                if(state.getBlock() instanceof FenceGateBlock) {
                    gates++;
                    helper.assertFalse(state.getValue(FenceGateBlock.OPEN), "Horizontal rope gate opened at "+pos);
                }
                if(state.getBlock() instanceof LanternBlock) {
                    lanterns++;
                    helper.assertTrue(state.getValue(BlockStateProperties.HANGING), "Path lantern must hang at "+pos);
                }
                if(material==SanctuaryNetwork.ROPE && forwardGenerator.network.isVerticalRope(x,y,z))
                    helper.assertTrue(state.getBlock() instanceof FenceBlock,
                        "Vertical rope intersection must remain a fence at "+pos);
                if(Math.hypot(x,z)<=90 && y==95) helper.assertTrue(state.is(Blocks.GRASS_BLOCK),"Platform changed");
                if(Math.hypot(x,z)>=700 && y==110) helper.assertTrue(state.is(Blocks.STONE),"Outer terrain changed");
            }
        }
        helper.assertTrue(amethyst>100 && budding>10 && clusters>5,"Missing genuine geode layers/buds");
        helper.assertTrue(roots>100 && ropes>20 && gates>20 && lanterns>5 && clear>500,"Missing roots/ropes/paths/lanterns");
        System.out.println("SANCTUARY_V2 blocks="+checked+" amethyst="+amethyst+" budding="+budding+" clusters="+clusters+" roots="+roots+" ropes="+ropes+" gates="+gates+" lanterns="+lanterns);
        helper.succeed();
    }

    @GameTest(template="worldgen_test",timeoutTicks=200)
    public static void developerPaletteValidation(GameTestHelper helper) throws Exception {
        com.google.gson.JsonObject json;
        try(var stream=AllvrSanctuaryGameTests.class.getResourceAsStream("/data/createmanaindustry/markov/sanctuary_palette.json")) {
            json=com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(stream)).getAsJsonObject();
        }
        var bundled = new SanctuaryPalette(json);
        var verticalRope=bundled.get("rope_vertical",0L);
        helper.assertTrue(verticalRope.getBlock() instanceof FenceBlock,"rope_vertical must resolve to a fence");
        var horizontalRope=bundled.get("rope_horizontal",0L);
        helper.assertTrue(horizontalRope.getBlock() instanceof FenceGateBlock
            && !horizontalRope.getValue(FenceGateBlock.OPEN)
            && !horizontalRope.getValue(FenceGateBlock.POWERED),
            "rope_horizontal must resolve to a closed, unpowered fence gate");
        helper.assertTrue(bundled.horizontalRope(Direction.EAST,0L)
                .getValue(BlockStateProperties.HORIZONTAL_FACING)==Direction.SOUTH,
            "A bridge extending east must use a gate whose connection axis is east-west");
        helper.assertTrue(bundled.lantern(0L).getBlock() instanceof LanternBlock
                && bundled.lantern(0L).getValue(BlockStateProperties.HANGING),
            "lantern material must be a hanging lantern");
        int zeroChain=0,oneChain=0;
        for(int x=0;x<32;x++) {
            if(AllvrSanctuaryGenerator.lanternChainLength(42L,x,22,7)==0) zeroChain++;
            else oneChain++;
        }
        helper.assertTrue(zeroChain>0 && oneChain>0,"Lantern chain length must mix 0 and 1");
        int wallBase = SanctuaryPalette.KEYS.indexOf("wall_base") + 2;
        var wallMaterials = new HashSet<net.minecraft.world.level.block.Block>();
        for (long seed = 0; seed < 128; seed++) wallMaterials.add(bundled.material(wallBase, seed).getBlock());
        helper.assertTrue(wallMaterials.contains(Blocks.DRIPSTONE_BLOCK)
            && wallMaterials.contains(Blocks.TUFF), "Weighted wall_base variants did not mix");
        var custom=json.deepCopy();
        custom.add("bridge_deck",com.google.gson.JsonParser.parseString("{\"Name\":\"createmanaindustry:prismarine_quartz_block\"}"));
        var palette=new SanctuaryPalette(custom);
        helper.assertTrue(palette.material(SanctuaryNetwork.DECK).is(com.iridium126.createmanaindustry.CMIBlocks.PRISMARINE_QUARTZ_BLOCK.get()),"Cross-namespace palette ignored");
        for(String key:new String[]{"path","root_core","wall_base"}) {
            var invalid=json.deepCopy();invalid.remove(key);boolean rejected=false;
            try { new SanctuaryPalette(invalid); } catch(IllegalArgumentException ex) { rejected=true; }
            helper.assertTrue(rejected,"Missing material accepted: "+key);
        }
        for(String state:new String[]{"{\"Name\":\"minecraft:air\"}","{\"Name\":\"minecraft:water\"}","{\"Name\":\"minecraft:chest\"}","{\"Name\":\"minecraft:torch\"}"}) {
            var invalid=json.deepCopy();invalid.add("path",com.google.gson.JsonParser.parseString(state));boolean rejected=false;
            try { new SanctuaryPalette(invalid); } catch(IllegalArgumentException ex) { rejected=true; }
            helper.assertTrue(rejected,"Invalid structural state accepted: "+state);
        }
        var invalid=json.deepCopy();invalid.add("bridge_slab",com.google.gson.JsonParser.parseString("{\"Name\":\"minecraft:stone\"}"));
        boolean rejected=false;try { new SanctuaryPalette(invalid); } catch(IllegalArgumentException ex) { rejected=true; }
        helper.assertTrue(rejected,"Non-slab half step accepted");
        helper.succeed();
    }
}
