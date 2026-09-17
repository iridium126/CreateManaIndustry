import com.iridium126.createmanaindustry.dimension.gen.AllvrSanctuary;
import com.iridium126.createmanaindustry.worldgen.markov.MarkovModel;
import java.nio.file.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

public class CausewayValidation {
    public static void main(String[] args) throws Exception {
        var root = Path.of("build/causeway-validation");
        MarkovModel model;
        try (var xml = Files.newInputStream(Path.of("src/main/resources/data/createmanaindustry/markov/karst_causeways.xml"))) {
            model = MarkovModel.load(xml, 121, 161, 1);
        }
        for (int seed : new int[]{0, 1, 42, 137, 2026, -1, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            byte[] java = model.generate(seed, 2000), reference = Files.readAllBytes(root.resolve(seed + ".bin"));
            if (!Arrays.equals(java, reference)) throw new AssertionError("Upstream mismatch " + seed);
            for (int y = 0; y < 160; y += 16) for (int x = 0; x < 121; x++)
                if (java[x + y * 121] == 0) throw new AssertionError("Missing crossing");
            System.out.println("PARITY seed=" + seed + " ten crossings and connector intact");
        }
        var f = new AllvrSanctuary(137);
        // Export true field voxels. Cutaway removes the near half so walls and arch intrados are visible.
        var top = new BufferedImage(481, 481, BufferedImage.TYPE_INT_RGB);
        var cut = new BufferedImage(970, 480, BufferedImage.TYPE_INT_RGB);
        int[] depths = new int[970 * 480]; Arrays.fill(depths, Integer.MIN_VALUE);
        for (int z = -240; z <= 240; z++) for (int x = -240; x <= 240; x++) {
            var c = f.column(x, z);
            for (int y = -12; y <= 95; y++) {
                boolean rock = !f.cavity(c, y), bridge = c.masonry(y);
                if (!rock && !bridge) continue;
                int color = bridge ? (y == 95 ? 0xb8b0a0 : 0x8f897c)
                    : y == 95 ? 0x729151 : 0x9d937f;
                top.setRGB(x + 240, z + 240, color);
                if (z > 55) continue;
                int u = x - z + 485, v = 110 - y + (x + z + 480) / 2;
                if (u < 0 || u >= 970 || v < 0 || v >= 480) continue;
                int depth = x + z + y * 2, i = u + v * 970;
                if (depth < depths[i]) continue;
                depths[i] = depth;
                double shade = (y == 95 || f.cavity(c, y + 1) && !c.masonry(y + 1)) ? 1 : .68;
                int r = (int) ((color >> 16 & 255) * shade), g = (int) ((color >> 8 & 255) * shade), b = (int) ((color & 255) * shade);
                cut.setRGB(u, v, r << 16 | g << 8 | b);
            }
        }
        ImageIO.write(top, "png", root.resolve("top.png").toFile());
        ImageIO.write(cut, "png", root.resolve("cutaway.png").toFile());
        System.out.println("Actual-field previews: " + root.toAbsolutePath());
    }
}
