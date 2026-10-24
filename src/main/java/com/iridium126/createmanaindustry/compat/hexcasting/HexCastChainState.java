package com.iridium126.createmanaindustry.compat.hexcasting;

import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.forge.xplat.ForgeXplatImpl;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChain;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChainSnapshot;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerPlayer;

/** Replaces only CMI's component; never decodes the VM or mutates an encoded snapshot in place. */
public final class HexCastChainState {
    private HexCastChainState() {}
    /** Per-VM invocation stack entries are never serialized or shared with an image. */
    public static final class Invocation {
        public final CastChain chain;
        public boolean terminal;
        public Invocation(CastChain chain) { this.chain = chain; }
    }
    public static CastChainSnapshot get(CastingImage image) {
        return image.getComponent(CMIHexImageComponents.CAST_CHAIN.get());
    }
    public static CastingImage publish(CastingImage image, CastChainSnapshot snapshot) {
        if (get(image) == snapshot) return image;
        return snapshot == null ? image.withoutComponent(CMIHexImageComponents.CAST_CHAIN.get())
                : image.withComponent(CMIHexImageComponents.CAST_CHAIN.get(), snapshot);
    }
    private static CompoundTag components(ServerPlayer player) {
        return player.getPersistentData().getCompound(ForgeXplatImpl.TAG_VM).getCompound("components");
    }
    private static String key() { return CMIHexImageComponents.CAST_CHAIN.getId().toString(); }
    public static UUID savedId(ServerPlayer player) {
        var data = components(player).getCompound(key());
        return data.hasUUID("id") && data.hasUUID("player") && player.getUUID().equals(data.getUUID("player"))
                ? data.getUUID("id") : null;
    }
    public static CastChainSnapshot saved(ServerPlayer player) {
        var encoded = components(player).get(key());
        return encoded == null ? null : CastChainSnapshot.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
    }
    public static void persist(ServerPlayer player, CastChain chain) {
        if (chain.runtime() != player.getServer() || !player.getUUID().equals(chain.player())
                || !chain.id().equals(savedId(player))) return;
        components(player).put(key(), CastChainSnapshot.CODEC.encodeStart(NbtOps.INSTANCE, chain.snapshot()).getOrThrow());
    }
    public static void clearSaved(ServerPlayer player, CastChain chain) {
        if (chain.runtime() == player.getServer() && player.getUUID().equals(chain.player())
                && chain.id().equals(savedId(player))) components(player).remove(key());
    }
}
