package com.iridium126.createmanaindustry.compat.trickster;

import com.iridium126.createmanaindustry.compat.trickster.trick.EvalIotaTrick;
import com.iridium126.createmanaindustry.compat.trickster.trick.ManaToMediaTrick;
import com.iridium126.createmanaindustry.compat.trickster.trick.ReadIotaTrick;
import com.iridium126.createmanaindustry.compat.trickster.trick.ReadPotionEffectsTrick;
import com.iridium126.createmanaindustry.compat.trickster.trick.TemporaryKineticStressTrick;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import dev.enjarai.trickster.spell.trick.Trick;
import dev.enjarai.trickster.spell.trick.Tricks;
import net.minecraft.core.Registry;

/** CMI's tricks, declared and registered like Trickster's {@link Tricks}. */
public final class CMITricks {
    public static final TemporaryKineticStressTrick TEMPORARY_KINETIC_STRESS =
            register("temporary_kinetic_stress", new TemporaryKineticStressTrick());
    public static final ReadPotionEffectsTrick READ_POTION_EFFECTS =
            register("read_potion_effects", new ReadPotionEffectsTrick());

    private CMITricks() {}

    private static <T extends Trick<?>> T register(String path, T trick) {
        // Tricks.register(String, T) uses the trickster namespace. Register in
        // the same registry with CMI's namespace so saved IDs and lang keys match.
        return Registry.register(Tricks.REGISTRY, CreateManaIndustry.modLoc(path), trick);
    }

    /** Called only when Trickster is loaded; touching this class registers its constants. */
    public static void register() {
        if (CreateManaIndustry.HEX_ACTIVE) {
            CMITricksterFragmentTypes.register();
            Hexcasting.register();
            com.iridium126.createmanaindustry.compat.trickster.greattrick.GreatTricks.register();
        }
    }

    /** Loaded only with Hexcasting, keeping optional API references out of the outer class. */
    public static final class Hexcasting {
        public static final ReadIotaTrick READ_IOTA = CMITricks.register("read_iota", new ReadIotaTrick());
        public static final EvalIotaTrick EVAL_IOTA = CMITricks.register("eval_iota", new EvalIotaTrick());
        public static final ManaToMediaTrick MANA_TO_MEDIA = CMITricks.register("mana_to_media", new ManaToMediaTrick());

        private Hexcasting() {}

        private static void register() {
            // Initialise the Hexcasting bridge constants.
        }
    }
}
