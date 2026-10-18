package com.iridium126.createmanaindustry.compat.hextrick.data;

import io.netty.buffer.ByteBuf;
import io.wispforest.endec.Deserializer;
import io.wispforest.endec.Endec;
import io.wispforest.endec.SerializationContext;
import io.wispforest.endec.format.bytebuf.ByteBufDeserializer;
import dev.enjarai.trickster.spell.fragment.FragmentType;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Same upstream wire format, with checks before allocations and recursive field reads. */
final class BoundedFragmentDeserializer extends ByteBufDeserializer {
    private final ByteBuf buffer;
    private final DecodeBudget budget;
    private boolean componentJson;

    BoundedFragmentDeserializer(ByteBuf buffer, DecodeBudget budget) {
        super(buffer);
        this.buffer = buffer;
        this.budget = budget;
    }

    private <T> Endec<T> bounded(Endec<T> endec) {
        return Endec.of(endec::encode, (context, input) -> {
            budget.push();
            try { return endec.decode(context, input); }
            finally { budget.pop(); }
        });
    }

    private int byteLength(SerializationContext context) {
        int count = readVarInt(context);
        DecodeBudget.encoded(count);
        if (count > buffer.readableBytes()) throw new DecodeBudget.DecodeFailure("truncated_bytes");
        return count;
    }

    @Override public byte[] readBytes(SerializationContext context) {
        byte[] bytes = new byte[byteLength(context)];
        buffer.readBytes(bytes);
        return bytes;
    }
    @Override public String readString(SerializationContext context) {
        int count = byteLength(context);
        try {
            String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(buffer.nioBuffer(buffer.readerIndex(), count)).toString();
            buffer.skipBytes(count);
            if (componentJson) BoundedComponentJson.validate(value, budget);
            return value;
        } catch (java.nio.charset.CharacterCodingException failure) {
            throw new DecodeBudget.DecodeFailure("invalid_utf8", failure);
        }
    }
    @Override public <T> Optional<T> readOptional(SerializationContext context, Endec<T> endec) {
        return super.readOptional(context, bounded(endec));
    }
    @Override public <T> Deserializer.Sequence<T> sequence(SerializationContext context, Endec<T> endec) {
        var sequence = super.sequence(context, bounded(endec));
        budget.collection(sequence.estimatedSize());
        return sequence;
    }
    @Override public <T> Deserializer.Map<T> map(SerializationContext context, Endec<T> endec) {
        var map = super.map(context, bounded(endec));
        budget.collection(map.estimatedSize());
        return map;
    }
    @Override public Deserializer.Struct struct() {
        var struct = super.struct();
        return new Deserializer.Struct() {
            private boolean entity;

            private <T> T readField(String name, SerializationContext context, Endec<T> endec,
                    T fallback, boolean hasFallback) {
                // ByteBuf struct fields decode immediately. Scope the read itself,
                // avoiding a new Endec and captured lambda for every scalar field.
                budget.push();
                boolean previous = componentJson;
                componentJson = entity && name.equals("name");
                try {
                    T value = hasFallback ? struct.field(name, context, endec, fallback) : struct.field(name, context, endec);
                    if (name.equals("type")) entity = value == FragmentType.ENTITY;
                    return value;
                } finally { componentJson = previous; budget.pop(); }
            }
            @Override public <T> T field(String name, SerializationContext context, Endec<T> endec) {
                return readField(name, context, endec, null, false);
            }
            @Override public <T> T field(String name, SerializationContext context, Endec<T> endec, T fallback) {
                return readField(name, context, endec, fallback, true);
            }
        };
    }
}
