package com.iridium126.createmanaindustry.compat.hextrick.bootstrap;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.compat.hextrick.data.IotaFragment;
import dev.enjarai.trickster.spell.fragment.FragmentType;
import java.util.OptionalInt;
import net.minecraft.core.Registry;

/** Fragment types for the Hexcasting bridge; loaded only with both mods present. */
public final class CMITricksterFragmentTypes {
    public static final FragmentType<IotaFragment> IOTA = Registry.register(
            FragmentType.REGISTRY, CreateManaIndustry.modLoc("iota"),
            new FragmentType<>(IotaFragment.class, IotaFragment.ENDEC, OptionalInt.of(0xb879ff)));

    private CMITricksterFragmentTypes() {}

    public static void register() {
        // Initialise the fragment type constants before the bridge tricks.
    }
}
