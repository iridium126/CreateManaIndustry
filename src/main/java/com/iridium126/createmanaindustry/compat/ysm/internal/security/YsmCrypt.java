// Adapted from OpenYSM (MIT); see licenses/openysm.
package com.iridium126.createmanaindustry.compat.ysm.internal.security;
import com.iridium126.createmanaindustry.compat.ysm.internal.algorithms.*;
import java.nio.*;
import java.util.Arrays;
public final class YsmCrypt {
    // Java stores encrypted and decompressed input in byte arrays. Mirror the
    // reference parser's lack of a smaller file-size quota up to that boundary.
    public static final int MAX_BYTES = Integer.MAX_VALUE - 8;
    private static final long SEED_FILE_VERIFICATION = 0x9E5599DB80C67C29L;
    private static final long SEED_RES_VERIFICATION = 0xA62B1A2C43842BC3L;
    private static final long SEED_KEY_DERIVATION = 0xD017CBBA7B5D3581L;
    public record Decrypted(int resourceFormat, byte[] payload) {}

    public static Decrypted decryptCrypto3(byte[] fileData) throws Exception {
        if (fileData.length < 8 + 24 + 32 + 8) {
            throw new IllegalArgumentException("Invalid YSM file size");
        }
        YsmFileFormat.Header header = YsmFileFormat.v3(fileData);
        int tailOffset = fileData.length - 64;
        byte[] key = Arrays.copyOfRange(fileData, tailOffset, tailOffset + 32);
        byte[] iv = Arrays.copyOfRange(fileData, tailOffset + 32, tailOffset + 56);
        long fileHash = ByteBuffer.wrap(fileData, tailOffset + 56, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();

        CityHash ch = new CityHash();
        long calculatedHash = ch.hash64WithSeed(Arrays.copyOfRange(fileData, 0, fileData.length - 8), SEED_FILE_VERIFICATION);
        if (calculatedHash != fileHash) {
            throw new RuntimeException("Corrupted YSM file: File hash mismatch.");
        }

        byte[] encryptedBinaryData = Arrays.copyOfRange(fileData, header.payloadOffset(), tailOffset);
        byte[] chachaDecrypted = modifiedChaChaDecrypt(encryptedBinaryData, key, iv, SEED_RES_VERIFICATION);

        byte[] keyIv = new byte[56];
        System.arraycopy(key, 0, keyIv, 0, 32);
        System.arraycopy(iv, 0, keyIv, 32, 24);
        byte[] xorredData = mt19937Xor(chachaDecrypted, keyIv, SEED_KEY_DERIVATION);

        //uint16_t n = xorred_data[0] | (xorred_data[1] << 8); n &= 0x3ff;
        if (xorredData.length < 2) throw new IllegalArgumentException("Truncated YSM payload");
        int n = ((xorredData[0] & 0xFF) | ((xorredData[1] & 0xFF) << 8)) & 0x3FF;


        int zstdOffset = 2 + n;
        if (zstdOffset >= xorredData.length) throw new IllegalArgumentException("Invalid YSM padding");
        byte[] bytes = Arrays.copyOfRange(xorredData, zstdOffset, xorredData.length);

        return new Decrypted(header.resourceFormat(), YsmZstd.decompress(bytes));
    }

    private static byte[] modifiedChaChaDecrypt(byte[] data, byte[] key, byte[] iv, long seed) throws Exception {
        byte[] keyIv = new byte[56];
        System.arraycopy(key, 0, keyIv, 0, 32);
        System.arraycopy(iv, 0, keyIv, 32, 24);

        CityHash ch = new CityHash();
        long hash2 = ch.hash64WithSeed(keyIv, seed);

        // ((hash2 & 0x3f) | 0x40) << 6
        int nextRoundSize = (int) (((hash2 & 0x3FL) | 0x40L) << 6);
        int rounds = (int) (10 * Long.remainderUnsigned(hash2, 3) + 10);

        XChaCha20 ctx = new XChaCha20(key, iv, rounds);

        byte[] result = new byte[data.length];
        int blockPointer = 0;

        while (blockPointer < data.length) {
            if (blockPointer + nextRoundSize > data.length) {
                nextRoundSize = data.length - blockPointer;
            }
            byte[] decChunk = ctx.processBytes(data, blockPointer, nextRoundSize);
            System.arraycopy(decChunk, 0, result, blockPointer, nextRoundSize);
            blockPointer += nextRoundSize;

            if (blockPointer < data.length) {
                long resHash = ch.hash64WithSeed(decChunk, seed);
                nextRoundSize = ctx.updateStateYSM(resHash);
            }
        }

        return result;
    }

    private static byte[] mt19937Xor(byte[] data, byte[] currentKeyIv, long seedDerivation) {
        long mtSeed = new CityHash().hash64WithSeed(currentKeyIv, seedDerivation);
        MT19937 mt = new MT19937(mtSeed);
        byte[] result = new byte[data.length];

        int i = 0;
        while (i < data.length) {
            long rnd = mt.extract_number();
            for (int j = 0; j < 8 && i < data.length; ++j) {
                byte keystreamByte = (byte) ((rnd >>> (j * 8)) & 0xFF);
                result[i] = (byte) (data[i] ^ keystreamByte);
                i++;
            }
        }
        return result;
    }
}
