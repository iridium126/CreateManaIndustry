package com.iridium126.createmanaindustry.compat.ysm.internal.security;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Decrypts the resource archives embedded in outer crypto versions 1 and 2. */
public final class YsmLegacyArchiveParser {
    private static final int HEADER_BYTES = 24;
    public static Map<String, byte[]> decrypt(byte[] file) throws Exception {
        int version = YsmFileFormat.cryptoVersion(file);
        if (version != 1 && version != 2) throw new IllegalArgumentException("Expected a crypto version 1 or 2 YSM file");
        if (file.length < HEADER_BYTES) throw new IllegalArgumentException("Truncated YSM archive header");

        var resources = new LinkedHashMap<String, byte[]>();
        var names = new java.util.HashSet<String>();
        long total = 0;
        int offset = HEADER_BYTES;
        while (offset < file.length) {
            int nameLength = readLength(file, offset, "filename"); offset += 4;
            byte[] encodedName = take(file, offset, nameLength, "filename"); offset += nameLength;
            String path = decodeName(encodedName, version);
            if (!names.add(path.toLowerCase(java.util.Locale.ROOT))) throw new IllegalArgumentException("Duplicate YSM archive path");

            int encryptedLength = readLength(file, offset, "encrypted resource"); offset += 4;
            byte[] key;
            if (version == 1) {
                key = take(file, offset, 16, "AES key"); offset += 16;
            } else {
                int keyLength = readLength(file, offset, "encrypted key"); offset += 4;
                if (keyLength != 32) throw new IllegalArgumentException("Invalid YSM v2 encrypted key length");
                byte[] encryptedKey = take(file, offset, keyLength, "encrypted key"); offset += keyLength;
                byte[] iv = take(file, offset, 16, "IV"); offset += 16;
                byte[] ciphertext = take(file, offset, encryptedLength, "encrypted resource");
                byte[] digest = MessageDigest.getInstance("MD5").digest(ciphertext);
                long seed = ByteBuffer.wrap(digest, 8, 8).order(ByteOrder.BIG_ENDIAN).getLong();
                byte[] randomKey = new byte[16];
                new Random(seed).nextBytes(randomKey);
                key = aesDecrypt(randomKey, iv, encryptedKey);
                if (key.length < 16) throw new IllegalArgumentException("Invalid YSM v2 content key");
                key = Arrays.copyOf(key, 16);
                byte[] decrypted = aesDecrypt(key, iv, ciphertext); offset += encryptedLength;
                byte[] content = inflate(decrypted, YsmCrypt.MAX_BYTES - (int) total);
                total += content.length;
                resources.put(path, content);
                continue;
            }

            byte[] iv = take(file, offset, 16, "IV"); offset += 16;
            byte[] ciphertext = take(file, offset, encryptedLength, "encrypted resource"); offset += encryptedLength;
            byte[] decrypted = aesDecrypt(key, iv, ciphertext);
            byte[] content = inflate(decrypted, YsmCrypt.MAX_BYTES - (int) total);
            total += content.length;
            resources.put(path, content);
        }
        if (resources.isEmpty()) throw new IllegalArgumentException("YSM archive contains no resources");
        return resources;
    }

    private static int readLength(byte[] file, int offset, String field) {
        if (offset < HEADER_BYTES || file.length - offset < 4) throw new IllegalArgumentException("Truncated YSM " + field + " length");
        long value = YsmFileFormat.uint32be(file, offset);
        if (value > YsmCrypt.MAX_BYTES) throw new IllegalArgumentException("YSM " + field + " exceeds Java byte-array limit");
        return (int) value;
    }

    private static byte[] take(byte[] file, int offset, int length, String field) {
        if (length < 0 || offset < HEADER_BYTES || offset > file.length || length > file.length - offset)
            throw new IllegalArgumentException("Truncated YSM " + field);
        return Arrays.copyOfRange(file, offset, offset + length);
    }

    private static String decodeName(byte[] encoded, int version) {
        byte[] bytes = encoded;
        if (version == 2) {
            try { bytes = Base64.getDecoder().decode(new String(encoded, StandardCharsets.US_ASCII)); }
            catch (IllegalArgumentException failure) { throw new IllegalArgumentException("Invalid YSM v2 filename encoding", failure); }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("Invalid UTF-8 YSM filename", failure);
        }
    }

    private static byte[] aesDecrypt(byte[] key, byte[] iv, byte[] ciphertext) throws Exception {
        if (key.length < 16 || iv.length != 16 || ciphertext.length == 0 || (ciphertext.length & 15) != 0)
            throw new IllegalArgumentException("Invalid YSM AES-CBC input");
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(Arrays.copyOf(key, 16), "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(ciphertext);
    }

    private static byte[] inflate(byte[] compressed, int remainingLimit) {
        if (remainingLimit <= 0) throw new IllegalArgumentException("YSM archive exceeds size limit");
        Inflater inflater = new Inflater();
        inflater.setInput(compressed);
        int initialCapacity = (int) Math.min(Math.max((long) compressed.length * 2, 256), remainingLimit);
        var output = new ByteArrayOutputStream(initialCapacity);
        byte[] buffer = new byte[8192];
        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count > remainingLimit - output.size()) throw new IllegalArgumentException("YSM archive exceeds size limit");
                if (count > 0) output.write(buffer, 0, count);
                else if (inflater.finished()) break;
                else if (inflater.needsDictionary() || inflater.needsInput()) throw new IllegalArgumentException("Truncated YSM zlib resource");
                else throw new IllegalArgumentException("Invalid YSM zlib resource");
            }
            return output.toByteArray();
        } catch (DataFormatException failure) {
            throw new IllegalArgumentException("Invalid YSM zlib resource", failure);
        } finally { inflater.end(); }
    }

    private YsmLegacyArchiveParser() {}
}
