// Adapted from OpenYSM (MIT); see META-INF/licenses/ysm-OpenYSM-MIT.txt.
package com.iridium126.createmanaindustry.compat.ysm.internal.security;
import com.iridium126.createmanaindustry.compat.ysm.internal.algorithms.*;
import java.nio.*;
import java.util.Arrays;
public final class YsmCrypt {
    public static final int MAX_BYTES = 64 * 1024 * 1024;
    private static final long SEED_FILE_VERIFICATION = 0x9E5599DB80C67C29L;
    private static final long SEED_RES_VERIFICATION = 0xA62B1A2C43842BC3L;
    private static final long SEED_KEY_DERIVATION = 0xD017CBBA7B5D3581L;
    public static byte[] decryptYsmFile(byte[] fileData) throws Exception {
        if (fileData.length > MAX_BYTES || fileData.length < 8 + 24 + 32 + 8) {
            throw new RuntimeException("Invalid YSM file: File too short.");
        }

        int headerLength = 0;
        while (headerLength < fileData.length && fileData[headerLength] != 0x00) {
            headerLength++;
        }

//        String headerString = new String(fileData, 0, headerLength, StandardCharsets.UTF_8);
//        System.out.println(headerString);

        int tailOffset = fileData.length - 64;
        if (headerLength + 5 >= tailOffset) throw new IllegalArgumentException("Invalid YSM header boundary");
        byte[] key = Arrays.copyOfRange(fileData, tailOffset, tailOffset + 32);
        byte[] iv = Arrays.copyOfRange(fileData, tailOffset + 32, tailOffset + 56);
        long fileHash = ByteBuffer.wrap(fileData, tailOffset + 56, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();

        CityHash ch = new CityHash();
        long calculatedHash = ch.hash64WithSeed(Arrays.copyOfRange(fileData, 0, fileData.length - 8), SEED_FILE_VERIFICATION);
        if (calculatedHash != fileHash) {
            throw new RuntimeException("Corrupted YSM file: File hash mismatch.");
        }

        int ptrBinaryData = headerLength + 1;
        int crypto = ByteBuffer.wrap(fileData, ptrBinaryData, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (crypto != 3) {
            throw new RuntimeException("Invalid YSM file: Crypto version is not 3.");
        }
        ptrBinaryData += 4;

        byte[] encryptedBinaryData = Arrays.copyOfRange(fileData, ptrBinaryData, tailOffset);
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

        return YsmZstd.decompress(bytes);
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
