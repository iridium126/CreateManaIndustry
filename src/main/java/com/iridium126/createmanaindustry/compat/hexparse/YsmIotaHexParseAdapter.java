package com.iridium126.createmanaindustry.compat.hexparse;

import at.petrak.hexcasting.api.casting.iota.Iota;
import com.iridium126.createmanaindustry.compat.hexcasting.ysm.CubeIota;
import com.iridium126.createmanaindustry.compat.hexcasting.ysm.GroupIota;
import com.iridium126.createmanaindustry.compat.ysm.YsmServerRuntime;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmPortableGeometryCodec;
import io.yukkuric.hexparse.parsers.IPlayerBinder;
import io.yukkuric.hexparse.parsers.nbt2str.INbt2Str;
import io.yukkuric.hexparse.parsers.str2nbt.IStr2Nbt;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

/** Portable HexParse adapters for YSM geometry handles. */
public final class YsmIotaHexParseAdapter {
    public static final CubeAdapter CUBE = new CubeAdapter();
    public static final GroupAdapter GROUP = new GroupAdapter();

    public static final class CubeAdapter implements IStr2Nbt, INbt2Str<CubeIota>, IPlayerBinder {
        private ServerPlayer player;

        @Override public boolean match(String node) { return node.startsWith(YsmPortableGeometryCodec.cubePrefix()); }

        @Override
        public Iota parse(String node) {
            ServerPlayer caller = requirePlayer();
            var data = YsmPortableGeometryCodec.decodeCube(node);
            String localKey = YsmServerRuntime.get(caller.server).references().importPortableCube(data);
            return new CubeIota(localKey);
        }

        @Override public Class<CubeIota> getType() { return CubeIota.class; }

        @Override
        public String parse(CubeIota iota) {
            ServerPlayer caller = requirePlayer();
            var data = YsmServerRuntime.get(caller.server).references().exportPortableCube(iota.key());
            return YsmPortableGeometryCodec.encodeCube(data);
        }

        @Override public void BindPlayer(@NotNull ServerPlayer player) { this.player = player; }
        private ServerPlayer requirePlayer() {
            if (player == null) throw new IllegalStateException("YSM HexParse requires a connected server player");
            return player;
        }
    }

    public static final class GroupAdapter implements IStr2Nbt, INbt2Str<GroupIota>, IPlayerBinder {
        private ServerPlayer player;

        @Override public boolean match(String node) { return node.startsWith(YsmPortableGeometryCodec.groupPrefix()); }

        @Override
        public Iota parse(String node) {
            ServerPlayer caller = requirePlayer();
            var data = YsmPortableGeometryCodec.decodeGroup(node);
            String localKey = YsmServerRuntime.get(caller.server).references().importPortableGroup(data);
            return new GroupIota(localKey);
        }

        @Override public Class<GroupIota> getType() { return GroupIota.class; }

        @Override
        public String parse(GroupIota iota) {
            ServerPlayer caller = requirePlayer();
            var data = YsmServerRuntime.get(caller.server).references().exportPortableGroup(iota.key());
            return YsmPortableGeometryCodec.encodeGroup(data);
        }

        @Override public void BindPlayer(@NotNull ServerPlayer player) { this.player = player; }
        private ServerPlayer requirePlayer() {
            if (player == null) throw new IllegalStateException("YSM HexParse requires a connected server player");
            return player;
        }
    }

    private YsmIotaHexParseAdapter() {}
}
