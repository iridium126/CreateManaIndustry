package com.iridium126.createmanaindustry.compat.hextrick.data;

import dev.enjarai.trickster.EndecTomfoolery;
import dev.enjarai.trickster.spell.Fragment;
import dev.enjarai.trickster.spell.SpellPart;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.wispforest.endec.SerializationContext;
import io.wispforest.endec.format.bytebuf.ByteBufSerializer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;

/** Current Trickster compact format: gzip with its fixed ten-byte header omitted. */
public final class BridgeFragmentCodec {
    private BridgeFragmentCodec() {}

    public static byte[] encode(Fragment fragment) {
        FragmentStructures.validate(fragment);
        ByteBuf buffer = Unpooled.buffer(256, DecodeBudget.MAX_DECODED);
        try {
            buffer.writeByte(Fragment.PROTOCOL_VERSION);
            Fragment.ENDEC.encode(context(Fragment.PROTOCOL_VERSION), ByteBufSerializer.of(buffer), fragment);
            var output = DecodeBudget.encodedOutput(10);
            try (var gzip = new GZIPOutputStream(output)) { buffer.readBytes(gzip, buffer.readableBytes()); }
            byte[] bytes = output.toByteArray();
            DecodeBudget.encoded(bytes.length - 10);
            return Arrays.copyOfRange(bytes, 10, bytes.length);
        } catch (IOException | IndexOutOfBoundsException failure) {
            throw new DecodeBudget.DecodeFailure("encode_limit", failure);
        } finally { buffer.release(); }
    }

    public static SpellPart decodeSpell(byte[] bytes) {
        Fragment fragment = decode(bytes);
        if (!(fragment instanceof SpellPart spell)) throw new DecodeBudget.DecodeFailure("not_a_spell");
        return spell;
    }

    public static SpellPart fromBase64(String value) {
        if (value.length() > ((DecodeBudget.MAX_ENCODED + 2L) / 3L) * 4L + 16)
            throw new DecodeBudget.DecodeFailure("base64_size");
        try { return decodeSpell(Base64.getDecoder().decode(value.strip())); }
        catch (IllegalArgumentException failure) { throw new DecodeBudget.DecodeFailure("base64_or_spell", failure); }
    }

    public static String toBase64(Fragment fragment) { return Base64.getEncoder().encodeToString(encode(fragment)); }

    public static Fragment decode(byte[] bytes) {
        DecodeBudget.encoded(bytes.length);
        if (bytes.length < 10) throw new DecodeBudget.DecodeFailure("truncated_gzip");
        try (var budget = DecodeBudget.enter()) {
            byte[] raw = inflate(bytes, budget);
            ByteBuf buffer = Unpooled.wrappedBuffer(raw);
            try {
                byte version = buffer.readByte();
                if (version < 3 || version > Fragment.PROTOCOL_VERSION)
                    throw new DecodeBudget.DecodeFailure("unsupported_protocol");
                Fragment result = Fragment.ENDEC.decode(context(version), new BoundedFragmentDeserializer(buffer, budget));
                if (buffer.isReadable()) throw new DecodeBudget.DecodeFailure("trailing_fragment_data");
                FragmentStructures.validateDecoded(result, budget);
                return result;
            } catch (RuntimeException failure) {
                throw new DecodeBudget.DecodeFailure("fragment_structure", failure);
            } finally { buffer.release(); }
        }
    }

    private static SerializationContext context(byte version) {
        return SerializationContext.empty().withAttributes(EndecTomfoolery.UBER_COMPACT_ATTRIBUTE,
                EndecTomfoolery.PROTOCOL_VERSION_ATTRIBUTE.instance(version));
    }

    public static byte[] decodeGzip(byte[] gzip, DecodeBudget budget) {
        DecodeBudget.encoded(gzip.length);
        if (gzip.length < 18 || gzip[0] != 0x1f || gzip[1] != (byte) 0x8b || gzip[2] != 8 || gzip[3] != 0)
            throw new DecodeBudget.DecodeFailure("unsupported_gzip_header");
        return inflate(Arrays.copyOfRange(gzip, 10, gzip.length), budget);
    }

    private static byte[] inflate(byte[] bytes, DecodeBudget budget) {
        Inflater inflater = new Inflater(true);
        var output = new ByteArrayOutputStream();
        var crc = new CRC32();
        try {
            inflater.setInput(bytes, 0, bytes.length - 8);
            byte[] chunk = new byte[8192];
            while (!inflater.finished()) {
                int count = inflater.inflate(chunk);
                if (count == 0 && !inflater.finished()) throw new DecodeBudget.DecodeFailure("truncated_deflate");
                budget.bytes(count);
                output.write(chunk, 0, count);
                crc.update(chunk, 0, count);
            }
            if (inflater.getRemaining() != 0) throw new DecodeBudget.DecodeFailure("trailing_deflate_data");
            var trailer = ByteBuffer.wrap(bytes, bytes.length - 8, 8).order(ByteOrder.LITTLE_ENDIAN);
            if (Integer.toUnsignedLong(trailer.getInt()) != crc.getValue()
                    || Integer.toUnsignedLong(trailer.getInt()) != output.size())
                throw new DecodeBudget.DecodeFailure("gzip_checksum");
            return output.toByteArray();
        } catch (DataFormatException failure) {
            throw new DecodeBudget.DecodeFailure("deflate", failure);
        } finally { inflater.end(); }
    }
}
