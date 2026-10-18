package com.iridium126.createmanaindustry.compat.hextrick.data;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import net.minecraft.nbt.*;

/** Native uncompressed NBT layout with bounds checked before collection allocation. */
public final class BoundedIotaNbt {
    private BoundedIotaNbt() {}

    public static CompoundTag read(byte[] bytes, DecodeBudget budget) throws IOException {
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readUnsignedByte() != 10) throw new DecodeBudget.DecodeFailure("nbt_root");
            input.readUTF();
            CompoundTag result = (CompoundTag) tag(10, input, budget);
            if (input.read() != -1) throw new DecodeBudget.DecodeFailure("trailing_nbt_data");
            return result;
        }
    }
    private static int length(DataInputStream input, int width, DecodeBudget budget, boolean collection) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > input.available() / width) throw new DecodeBudget.DecodeFailure("nbt_array_length");
        if (collection) budget.collection(length);
        return length;
    }
    private static Tag tag(int type, DataInputStream input, DecodeBudget budget) throws IOException {
        budget.push();
        try {
            return switch (type) {
                case 1 -> ByteTag.valueOf(input.readByte());
                case 2 -> ShortTag.valueOf(input.readShort());
                case 3 -> IntTag.valueOf(input.readInt());
                case 4 -> LongTag.valueOf(input.readLong());
                case 5 -> FloatTag.valueOf(input.readFloat());
                case 6 -> DoubleTag.valueOf(input.readDouble());
                case 7 -> {
                    int count = length(input, 1, budget, false);
                    byte[] values = new byte[count]; input.readFully(values); yield new ByteArrayTag(values);
                }
                case 8 -> StringTag.valueOf(input.readUTF());
                case 9 -> {
                    int childType = input.readUnsignedByte();
                    int count = length(input, 1, budget, true);
                    if (childType > 12 || childType == 0 && count != 0) throw new DecodeBudget.DecodeFailure("nbt_list_type");
                    var list = new ListTag();
                    for (int i = 0; i < count; i++) list.add(tag(childType, input, budget));
                    yield list;
                }
                case 10 -> {
                    var compound = new CompoundTag();
                    int childType;
                    while ((childType = input.readUnsignedByte()) != 0) {
                        String name = input.readUTF();
                        compound.put(name, tag(childType, input, budget));
                    }
                    yield compound;
                }
                case 11 -> {
                    int count = length(input, 4, budget, false);
                    int[] values = new int[count]; for (int i = 0; i < count; i++) values[i] = input.readInt();
                    yield new IntArrayTag(values);
                }
                case 12 -> {
                    int count = length(input, 8, budget, false);
                    long[] values = new long[count]; for (int i = 0; i < count; i++) values[i] = input.readLong();
                    yield new LongArrayTag(values);
                }
                default -> throw new DecodeBudget.DecodeFailure("nbt_tag_type");
            };
        } finally { budget.pop(); }
    }
}
