package com.iridium126.createmanaindustry.content.fluids.fueltank;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class FuelTankFluidStateTest {
    static Set<BlockPos> flat(int width, int depth, int y) {
        Set<BlockPos> cells = new HashSet<>();
        for (int x = 0; x < width; x++) for (int z = 0; z < depth; z++) cells.add(new BlockPos(x, y, z));
        return cells;
    }
    static FuelTankFluidState state(Set<BlockPos> cells) { return new FuelTankFluidState(FuelTankGeometry.build(cells, 8000)); }
    static void assertAmounts(FuelTankFluidState state, long expected) {
        assertEquals(expected, state.total());
        assertEquals(expected, Arrays.stream(state.copyVolumes()).sum());
        assertEquals(expected, state.cellAmounts().values().stream().mapToLong(Long::longValue).sum());
        for (var basin : state.geometry().basins) assertTrue(state.volume(basin.id) <= basin.capacity());
    }

    @Test void smallTransfersAreExactAtEveryHeight() {
        for (int y : new int[]{-64, 0, 64, 300, 8_000_000}) {
            FuelTankFluidState state = state(flat(16, 16, y)); BlockPos seed = new BlockPos(0, y, 0);
            FuelTankGeometry original = state.geometry();
            for (int i = 0; i < 40; i++) assertEquals(0, state.fill(seed, 50));
            assertAmounts(state, 2000);
            assertEquals(0, state.drain(seed, 2000));
            assertEquals(0, state.fill(seed, 1000));
            for (int i = 0; i < 1000; i++) assertEquals(0, state.fill(seed, 1));
            assertAmounts(state, 2000); assertSame(original, state.geometry());
            assertEquals(1000, state.drain(seed, 3000)); assertAmounts(state, 0);
        }
    }

    @Test void twoFluidSourcesMergeAndSplitsLoseOnlyRemovedCellShare() {
        FuelTankFluidState left = state(Set.of(new BlockPos(0,64,0)));
        FuelTankFluidState right = state(Set.of(new BlockPos(2,64,0)));
        left.fill(new BlockPos(0,64,0),1000); right.fill(new BlockPos(2,64,0),1000);
        Map<BlockPos,Long> amounts = new HashMap<>(left.cellAmounts()); amounts.putAll(right.cellAmounts());
        FuelTankFluidState merged = FuelTankFluidState.remap(FuelTankGeometry.build(flat(3,1,64),8000), amounts);
        assertAmounts(merged,2000);
        Map<BlockPos,Long> shares = merged.cellAmounts(); BlockPos removed = new BlockPos(1,64,0);
        long lost = shares.getOrDefault(removed,0L);
        FuelTankFluidState a = FuelTankFluidState.remap(left.geometry(), shares);
        FuelTankFluidState b = FuelTankFluidState.remap(right.geometry(), shares);
        assertEquals(2000-lost, a.total()+b.total());
    }

    @Test void invertedUTrapsLiquidAndEventuallyFillsEveryBasin() {
        Set<BlockPos> cells = new HashSet<>();
        for (int y=64;y<=67;y++) { cells.add(new BlockPos(0,y,0)); cells.add(new BlockPos(4,y,0)); }
        for (int x=1;x<4;x++) cells.add(new BlockPos(x,67,0));
        FuelTankFluidState state=state(cells); BlockPos left=new BlockPos(0,64,0), right=new BlockPos(4,64,0);
        state.fill(left,8000);
        assertEquals(0,state.cellAmounts().getOrDefault(right,0L));
        long room=state.geometry().capacity()-8000;
        assertEquals(0,state.fill(right,room)); assertAmounts(state,state.geometry().capacity());
        assertEquals(0,state.drain(left,state.total())); assertAmounts(state,0);
    }

    @Test void randomTransfersRebuildsAndSerializationPreserveEveryMillibucket() {
        Random random=new Random(418905);
        for (int shape=0;shape<20;shape++) {
            Set<BlockPos> cells=new HashSet<>(); BlockPos current=new BlockPos(0,64,0); cells.add(current);
            for (int i=0;i<100;i++) { current=current.relative(Direction.values()[random.nextInt(6)]); cells.add(current); }
            FuelTankFluidState state=state(cells); List<BlockPos> ordered=new ArrayList<>(cells); long total=0;
            for (int operation=0;operation<150;operation++) {
                BlockPos seed=ordered.get(random.nextInt(ordered.size())); int amount=random.nextInt(20000);
                if(random.nextBoolean()) { long accepted=Math.min(amount,state.geometry().capacity()-total);
                    assertEquals(amount-accepted,state.fill(seed,amount), "shape="+shape+" op="+operation); total+=accepted;
                } else { long taken=Math.min(amount,total); assertEquals(amount-taken,state.drain(seed,amount)); total-=taken; }
                assertAmounts(state,total);
                if(operation%25==0) {
                    state=FuelTankFluidState.read(state.write(),state.geometry().origin);
                    state=FuelTankFluidState.remap(state.geometry(),state.cellAmounts());
                    assertAmounts(state,total);
                }
            }
        }
    }

    @Test void perBlockCapacityAndFullCapacityAreBounded() {
        FuelTankFluidState state=new FuelTankFluidState(FuelTankGeometry.build(flat(64,64,64),100000));
        assertEquals(0,state.fill(BlockPos.ZERO,state.geometry().capacity()));
        assertAmounts(state,409_600_000);
        assertEquals(1,state.fill(BlockPos.ZERO,1));
        assertThrows(IllegalArgumentException.class,()->state.readVolumes(new long[]{-1}));
    }
}
