package com.iridium126.createmanaindustry.compat.hexparse;

import at.petrak.hexcasting.api.casting.iota.Iota;
import dev.enjarai.trickster.spell.Fragment;
import dev.enjarai.trickster.spell.SpellPart;
import io.yukkuric.hexparse.parsers.nbt2str.INbt2Str;
import io.yukkuric.hexparse.parsers.str2nbt.IStr2Nbt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Objects;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/** HexParse's portable text codec for TrickIota. */
public final class TrickIotaHexParseAdapter implements IStr2Nbt, INbt2Str<com.iridium126.createmanaindustry.compat.hexcasting.TrickIota> {
    public static final TrickIotaHexParseAdapter INSTANCE = new TrickIotaHexParseAdapter();
    private static final String PREFIX = "createmanaindustry:trick_";
    private static final int MAGIC = 0x434d4954; // CMIT
    private static final int VERSION = 1;
    private static final int MAX_SPELL_BYTES = 64 * 1024 * 1024;
    private static final int MAX_TOKEN_CHARS = 90 * 1024 * 1024;

    @Override public boolean match(String node) { return node.startsWith(PREFIX); }

    @Override
    public Iota parse(String node) {
        try {
            if (node.length() > MAX_TOKEN_CHARS) throw new IllegalArgumentException("TrickIota token exceeds the size limit");
            byte[] compressed = Base64.getUrlDecoder().decode(node.substring(PREFIX.length()));
            String canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(compressed);
            if (!canonical.equals(node.substring(PREFIX.length())))
                throw new IllegalArgumentException("Non-canonical TrickIota Base64URL payload");
            Inflater inflater = new Inflater();
            try (DataInputStream input = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(compressed), inflater))) {
                if (input.readInt() != MAGIC) throw new IllegalArgumentException("Unknown TrickIota payload");
                if (input.readUnsignedByte() != VERSION) throw new IllegalArgumentException("Unsupported TrickIota payload version");
                int length = input.readInt();
                if (length < 1 || length > MAX_SPELL_BYTES) throw new IllegalArgumentException("Invalid TrickIota spell length");
                byte[] bytes = input.readNBytes(length);
                if (bytes.length != length || input.read() != -1 || !inflater.finished() || inflater.getRemaining() != 0)
                    throw new IllegalArgumentException("Truncated or trailing TrickIota payload data");
                Fragment fragment = Fragment.fromBytes(bytes);
                if (!(fragment instanceof SpellPart spell)) throw new IllegalArgumentException("TrickIota payload is not a spell part");
                return new com.iridium126.createmanaindustry.compat.hexcasting.TrickIota(spell);
            } finally {
                inflater.end();
            }
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid TrickIota payload: " + failure.getMessage(), failure);
        }
    }

    @Override public Class<com.iridium126.createmanaindustry.compat.hexcasting.TrickIota> getType() {
        return com.iridium126.createmanaindustry.compat.hexcasting.TrickIota.class;
    }

    @Override
    public String parse(com.iridium126.createmanaindustry.compat.hexcasting.TrickIota iota) {
        Objects.requireNonNull(iota);
        try {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(new DeflaterOutputStream(compressed))) {
                output.writeInt(MAGIC);
                output.writeByte(VERSION);
                byte[] spell = iota.getSpell().toBytes();
                if (spell.length < 1 || spell.length > MAX_SPELL_BYTES)
                    throw new IllegalArgumentException("TrickIota spell exceeds the size limit");
                output.writeInt(spell.length);
                output.write(spell);
            }
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(compressed.toByteArray());
        } catch (IOException failure) {
            throw new IllegalArgumentException("Unable to encode TrickIota", failure);
        }
    }
}
