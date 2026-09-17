import com.iridium126.createmanaindustry.dimension.gen.AllvrSanctuary;
import com.iridium126.createmanaindustry.dimension.gen.SanctuaryNetwork;
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
            model = MarkovModel.load(xml, 7, 60, 7);
        }
        for (int seed : new int[]{0, 1, 42, 137, 2026, -1, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            byte[] java = model.generate(seed, 4000), reference = Files.readAllBytes(root.resolve(seed + ".bin"));
            if (!Arrays.equals(java, reference)) throw new AssertionError("Upstream mismatch " + seed);
            for (char symbol : new char[]{'H','R','G','A','D','E','F'}) {
                int count=0; for(byte value:java) if(model.values().charAt(value)==symbol) count++;
                if(count!=(symbol=='H'?6:symbol=='R'?4:2)) throw new AssertionError("Missing module "+symbol+" count="+count);
            }
            System.out.println("PARITY seed="+seed+" 3D modules, 10 bridges, 8 ramps, 2 geodes intact");
        }
        var f = new AllvrSanctuary(137);
        var network = new SanctuaryNetwork(137);
        // Export true field voxels. Cutaway removes the near half so walls and arch intrados are visible.
        var top = new BufferedImage(481, 481, BufferedImage.TYPE_INT_RGB);
        var cut = new BufferedImage(970, 480, BufferedImage.TYPE_INT_RGB);
        var architecture = new BufferedImage(970,480,BufferedImage.TYPE_INT_RGB);
        int[] architectureDepth=new int[970*480];Arrays.fill(architectureDepth,Integer.MIN_VALUE);
        int[] depths = new int[970 * 480]; Arrays.fill(depths, Integer.MIN_VALUE);
        for (int z = -240; z <= 240; z++) for (int x = -240; x <= 240; x++) {
            var c = f.column(x, z);
            for (int y = -12; y <= 106; y++) {
                int material=network.get(x,y,z);
                boolean rock = y<=95 && material!=SanctuaryNetwork.CLEAR && !f.cavity(c,y);
                boolean built=material>=SanctuaryNetwork.PATH;
                if(!rock && !built) continue;
                int color = built ? switch(material) {
                    case SanctuaryNetwork.PATH,SanctuaryNetwork.PATH_SLAB -> 0xc4b7a2;
                    case SanctuaryNetwork.DECK,SanctuaryNetwork.DECK_SLAB -> 0xb38b5a;
                    case SanctuaryNetwork.ROPE -> 0x9cb19a;
                    case SanctuaryNetwork.LIGHT -> 0xffeec0;
                    case SanctuaryNetwork.ROOT_BARK,SanctuaryNetwork.ROOT_CORE -> 0x65503b;
                    case SanctuaryNetwork.ROOT_MOSS -> 0x617e40;
                    default -> 0x827565;
                } : y==95?0x729151:0x9d937f;
                top.setRGB(x+240,z+240,color);
                if(built) {
                    int au=x-z+485,av=110-y+(x+z+480)/2,ad=x+z+y*2;
                    if(au>=0 && au<970 && av>=0 && av<480 && ad>=architectureDepth[au+av*970]) {
                        architectureDepth[au+av*970]=ad; architecture.setRGB(au,av,color);
                    }
                }
                if (z > 55) continue;
                int u = x - z + 485, v = 110 - y + (x + z + 480) / 2;
                if (u < 0 || u >= 970 || v < 0 || v >= 480) continue;
                int depth = x + z + y * 2, i = u + v * 970;
                if (depth < depths[i]) continue;
                depths[i] = depth;
                double shade = (y == 95 || network.get(x,y+1,z)==SanctuaryNetwork.CLEAR || f.cavity(c,y+1) && network.get(x,y+1,z)==0) ? 1 : .68;
                int r = (int) ((color >> 16 & 255) * shade), g = (int) ((color >> 8 & 255) * shade), b = (int) ((color & 255) * shade);
                cut.setRGB(u, v, r << 16 | g << 8 | b);
            }
        }
        ImageIO.write(architecture,"png",root.resolve("network.png").toFile());
        ImageIO.write(top, "png", root.resolve("top.png").toFile());
        ImageIO.write(cut, "png", root.resolve("cutaway.png").toFile());
        System.out.println("Actual-field previews: " + root.toAbsolutePath());
    }
}
