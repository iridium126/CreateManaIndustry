package com.iridium126.createmanaindustry;

import com.iridium126.createmanaindustry.content.allaystorm.AllayStormData;
import com.iridium126.createmanaindustry.content.fluids.mist.MistFieldStore.MistFieldData;
import com.iridium126.createmanaindustry.content.kinetics.temporarykinetics.TemporaryKineticsTicker;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * NeoForge data attachments for CreateManaIndustry.
 * <p>
 * {@link #MIST_FIELD} attaches the per-dimension mist field data to each
 * {@link net.minecraft.world.level.Level}. The data is created lazily on first
 * access and lives exactly as long as the level does — the server holds it on
 * its {@code ServerLevel}, the client on its own (empty) {@code ClientLevel} —
 * so no static per-dimension maps or dimension-unload hooks are needed.
 */
public final class CMIAttachments {
    public static final DeferredRegister<AttachmentType<?>> REGISTER =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, CreateManaIndustry.MODID);

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<MistFieldData>> MIST_FIELD =
            REGISTER.register("mist_field", () -> AttachmentType.builder(MistFieldData::new).build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<TemporaryKineticsTicker.LevelUpdates>>
            TEMPORARY_KINETICS_UPDATES = REGISTER.register("temporary_kinetics_updates",
                    () -> AttachmentType.builder(TemporaryKineticsTicker.LevelUpdates::new).build());

    /**
     * Server-authoritative Allay Storm state (the GPU boss swarm): definition,
     * member death set and sparse HP table, serialized with the level save so
     * the storm survives restarts with consistent member counts.
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<AllayStormData>> STORM_DATA =
            REGISTER.register("storm_data",
                    () -> AttachmentType.builder(AllayStormData::new)
                            .serialize(new AllayStormData.Serializer())
                            .build());

    private CMIAttachments() {}

    public static void register(IEventBus bus) {
        REGISTER.register(bus);
    }
}
