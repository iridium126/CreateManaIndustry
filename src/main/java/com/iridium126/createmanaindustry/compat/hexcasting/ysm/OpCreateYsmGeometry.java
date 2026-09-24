package com.iridium126.createmanaindustry.compat.hexcasting.ysm;

import java.util.List;

import com.iridium126.createmanaindustry.compat.ysm.YsmReferenceStore;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Cube;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Group;

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.mishaps.Mishap;

/** Creates an empty, immutable geometry reference in the current world save. */
public record OpCreateYsmGeometry(boolean cube) implements ConstMediaAction {
    @Override public int getArgc() { return 0; }
    @Override public long getMediaCost() { return 0; }

    @Override
    public List<Iota> execute(List<? extends Iota> args, CastingEnvironment env) throws Mishap {
        try {
            YsmReferenceStore store = OpGeometry.store(env);
            return List.of(cube ? new CubeIota(store.writeCube(Cube.empty()))
                    : new GroupIota(store.writeTree(Group.empty())));
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw new MishapYsm(failure.getMessage());
        }
    }
}
