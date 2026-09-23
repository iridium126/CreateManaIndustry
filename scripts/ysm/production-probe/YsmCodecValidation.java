package com.iridium126.createmanaindustry.compat.ysm;

import java.util.List;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.*;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometryCodecs;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

final class YsmCodecValidation {
    static void verify(RegistryAccess registries) {
        var cube = Cube.empty();
        var bone = new Group("骨骼😀", Vector.ZERO, Vector.ZERO, Vector.ONE, true, List.of(cube), List.of(), null, "{\"preserved\":true}");
        var root = new Group("main.json", Vector.ZERO, Vector.ZERO, Vector.ONE, true, List.of(), List.of(bone),
                new Root("a".repeat(64), "models/main.json", 64, 64, "{}"), "{}");
        var encoded = YsmGeometryCodecs.GROUP.encodeStart(JsonOps.INSTANCE, root).getOrThrow();
        if (!root.equals(YsmGeometryCodecs.GROUP.parse(JsonOps.INSTANCE, encoded).getOrThrow()))
            throw new AssertionError("Geometry Codec round trip failed");
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            YsmGeometryCodecs.GROUP_STREAM.encode(buffer, root);
            if (!root.equals(YsmGeometryCodecs.GROUP_STREAM.decode(buffer)) || buffer.isReadable())
                throw new AssertionError("Geometry StreamCodec round trip failed");
        } finally { buffer.release(); }
        if (YsmGeometryCodecs.GROUP.parse(JsonOps.INSTANCE, new com.google.gson.JsonPrimitive("invalid base64!")).result().isPresent())
            throw new AssertionError("Corrupt Codec input accepted");
        System.out.println("CMI_YSM_CODEC PASS: Codec and StreamCodec round trips, Unicode and corrupt input");
    }
    private YsmCodecValidation() {}
}
