import com.iridium126.createmanaindustry.worldgen.markov.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;

/** Compares all 1,289,945,088 output voxels, including air, against the C# interpreter. */
public class RedwoodValidation {
    public static void main(String[] args) throws Exception {
        int seed = Integer.parseInt(args[1]);
        long start = System.nanoTime();
        EpicRedwoodModel model;
        try (var input = Files.newInputStream(Path.of(args[0]))) { model = new EpicRedwoodModel(input); }
        var result = model.generate(seed, (stage, volume) -> {
            try {
                var digest = MessageDigest.getInstance("SHA-256");
                volume.writeTo(new DigestOutputStream(OutputStream.nullOutputStream(), digest));
                System.out.println(stage + " sha256=" + HexFormat.of().formatHex(digest.digest()) + " bytes=" + volume.allocatedBytes());
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        System.out.printf("Java seed=%d generation+stage-hash=%.3fs%n", seed, (System.nanoTime()-start)/1e9);
        if (args.length > 2) try (var reference = new BufferedInputStream(new GZIPInputStream(Files.newInputStream(Path.of(args[2]))), 65536)) {
            result.writeTo(new OutputStream() {
                long offset;
                final byte[] buffer = new byte[result.x];
                @Override public void write(int value) throws IOException {
                    if (reference.read() != (value & 255)) throw new IOException("Voxel mismatch at index " + offset);
                    offset++;
                }
                @Override public void write(byte[] row, int from, int length) throws IOException {
                    int read = reference.readNBytes(buffer, 0, length);
                    if (read != length) throw new IOException("Reference ended at " + offset);
                    for (int i = 0; i < length; i++) if (buffer[i] != row[from + i])
                        throw new IOException("Voxel mismatch at index " + (offset + i));
                    offset += length;
                }
            });
            if (reference.read() != -1) throw new AssertionError("Trailing reference voxels");
            System.out.println("PASS: every voxel matches upstream, seed=" + seed);
        }
    }
}
