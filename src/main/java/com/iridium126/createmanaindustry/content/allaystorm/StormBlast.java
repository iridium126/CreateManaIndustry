package com.iridium126.createmanaindustry.content.allaystorm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/** A scope around a single vanilla explosion. Candidate blocks are captured after protection events. */
public final class StormBlast {
    private static final ThreadLocal<StormBlast> ACTIVE = new ThreadLocal<>();
    private static final ThreadLocal<Cell[]> SPARE = ThreadLocal.withInitial(() -> new Cell[19 * 19 * 19]);
    private final ServerLevel level;
    private final Vec3 center;
    private Explosion rays;
    private Cell[] cells;
    private final BlockPos minimum;
    private final ArrayList<BlockPos> removed = new ArrayList<>();

    private StormBlast(ServerLevel level, Vec3 center) {
        this.level = level;
        this.center = center;
        this.minimum = BlockPos.containing(center).offset(-9,-9,-9);
    }

    private static final class Cell extends BlockPos {
        BlockState block;
        FluidState fluid;
        Optional<Float> resistance;
        boolean candidate;
        Cell(int x,int y,int z) { super(x,y,z); }
    }

    static List<BlockPos> explode(ServerLevel level, Vec3 point) {
        var scope = new StormBlast(level, point);
        StormBlast previous = ACTIVE.get();
        ACTIVE.set(scope);
        try {
            level.explode(null, point.x, point.y, point.z, 5, false, Level.ExplosionInteraction.MOB);
        } finally {
            if(scope.cells!=null){
                Arrays.fill(scope.cells,null);
                SPARE.set(scope.cells);
            }
            if (previous == null) ACTIVE.remove();
            else ACTIVE.set(previous);
        }
        scope.removed.removeIf(pos -> occupied(level, pos));
        return scope.removed;
    }

    public static void beginRays(Explosion explosion) {
        var active=ACTIVE.get();
        if(active!=null&&active.rays==null&&explosion.radius()==5&&active.center.equals(explosion.center())){
            active.rays=explosion;
            active.cells=SPARE.get();
            SPARE.remove();
        }
    }

    public static BlockPos rayCell(Explosion explosion,double x,double y,double z) {
        var active=ACTIVE.get();
        if(active==null||active.rays!=explosion)return BlockPos.containing(x,y,z);
        int bx=Mth.floor(x),by=Mth.floor(y),bz=Mth.floor(z);
        int dx=bx-active.minimum.getX(),dy=by-active.minimum.getY(),dz=bz-active.minimum.getZ();
        if(dx<0||dy<0||dz<0||dx>=19||dy>=19||dz>=19)return new BlockPos(bx,by,bz);
        int index=dx+19*(dy+19*dz);
        Cell cell=active.cells[index];
        if(cell==null)active.cells[index]=cell=new Cell(bx,by,bz);
        return cell;
    }

    public static BlockState rayBlock(Level level,BlockPos pos) {
        if(!(pos instanceof Cell cell))return level.getBlockState(pos);
        if(cell.block==null)cell.block=level.getBlockState(pos);
        return cell.block;
    }

    public static FluidState rayFluid(Level level,BlockPos pos) {
        if(!(pos instanceof Cell cell))return level.getFluidState(pos);
        if(cell.fluid==null)cell.fluid=level.getFluidState(pos);
        return cell.fluid;
    }

    public static Optional<Float> rayResistance(ExplosionDamageCalculator calculator,Explosion explosion,
                                                BlockGetter level,BlockPos pos,BlockState block,FluidState fluid) {
        if(!(pos instanceof Cell cell)||calculator.getClass()!=ExplosionDamageCalculator.class)
            return calculator.getBlockExplosionResistance(explosion,level,pos,block,fluid);
        if(cell.resistance==null)cell.resistance=calculator.getBlockExplosionResistance(explosion,level,pos,block,fluid);
        return cell.resistance;
    }

    public static boolean candidate(Set<BlockPos> blocks,BlockPos pos) {
        if(pos instanceof Cell cell){
            if(cell.candidate)return false;
            cell.candidate=true;
        }
        return blocks.add(pos);
    }

    public static void blockChanged(Level level,BlockPos pos,BlockState previous) {
        var active = ACTIVE.get();
        if(active!=null&&level==active.level&&active.rays!=null&&!previous.isAir()
                &&!previous.getCollisionShape(level,pos).isEmpty()&&!occupied(active.level,pos)){
            active.removed.add(pos.immutable());
        }
    }

    private static boolean occupied(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(level, pos).isEmpty();
    }
}
