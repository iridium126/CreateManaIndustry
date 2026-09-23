import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometryIO;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmBakedGeometry;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometryJson;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometryReader;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.*;

/** Standalone boundary tests, independent of native YSM availability. */
public final class YsmGeometryValidation {
    private static final String DIGEST = "a".repeat(64);
    public static void main(String[] args) {
        Cube cube = Cube.empty();
        var mutable = new ArrayList<Cube>();
        mutable.add(cube);
        Group bone = bone("root", mutable, List.of());
        mutable.clear();
        check(bone.cubes().size() == 1, "defensive list copy");
        check(bone.equals(bone("root", List.of(cube), List.of())), "value equality");
        rejects(() -> bone.cubes().clear());
        Group root = root("main.json", List.of(bone));
        check(YsmGeometry.validateRoots(List.of(root), Set.of("main.json")).equals(DIGEST), "complete provenance");
        check(YsmGeometry.validateRoots(List.of(root("main.json", List.of())), Set.of("main.json")).equals(DIGEST), "empty geometry");
        rejects(() -> YsmGeometry.validateRoots(List.of(root, root), Set.of("main.json")));
        rejects(() -> YsmGeometry.validateRoots(List.of(root), Set.of("main.json", "arm.json")));
        rejects(() -> YsmGeometry.validateRoots(List.of(root("main.json", List.of(bone, bone))), Set.of("main.json")));
        rejects(() -> bone("nested", List.of(), List.of(root)));
        rejects(() -> new Root(DIGEST, "../outside.json", 64, 64, "{}"));
        rejects(() -> new Vector(Double.NaN, 0, 0));
        rejects(() -> new Face(0, 0, 1, 1, 45, true));
        rejects(() -> bone("large", Collections.nCopies(YsmGeometry.MAX_NODES, cube), List.of()));
        bone("boundary", Collections.nCopies(YsmGeometry.MAX_NODES - 1, cube), List.of());
        Group deep = Group.empty();
        for (int i = 1; i < YsmGeometry.MAX_DEPTH; i++) deep = bone("b" + i, List.of(), List.of(deep));
        Group limit = deep;
        rejects(() -> bone("too deep", List.of(), List.of(limit)));
        check(YsmGeometryIO.decodeCube(YsmGeometryIO.encodeCube(cube)).equals(cube), "cube binary round trip");
        byte[] encoded = YsmGeometryIO.encodeGroup(root);
        check(YsmGeometryIO.decodeGroup(encoded).equals(root), "root binary round trip");
        for (int length = 0; length < encoded.length; length++) {
            byte[] truncated = java.util.Arrays.copyOf(encoded, length);
            rejects(() -> YsmGeometryIO.decodeGroup(truncated));
        }
        rejects(() -> YsmGeometryIO.decodeGroup(java.util.Arrays.copyOf(encoded, encoded.length + 1)));
        byte[] wrongVersion = encoded.clone(); wrongVersion[0] = 2;
        rejects(() -> YsmGeometryIO.decodeGroup(wrongVersion));
        rejects(() -> YsmGeometryIO.encodeGroup(bone("large", Collections.nCopies(YsmGeometry.MAX_NODES - 1, cube), List.of())));
        Cube hiddenCube = new Cube(new Vector(3, 0, 0), cube.size(), cube.pivot(), cube.rotation(), cube.scale(),
                cube.inflate(), false, cube.faces(), "{}");
        Group hiddenBone = new Group("hidden", Vector.ZERO, Vector.ZERO, Vector.ONE, false,
                List.of(cube, hiddenCube), List.of(), null, "{}");
        Group multipleGeometries = new Group("models/main.json", Vector.ZERO, Vector.ZERO, Vector.ONE, true,
                List.of(), List.of(hiddenBone), new Root(DIGEST, "models/main.json", 64, 64, "{}"),
                "{\"minecraft:geometry\":[{}, {\"description\":{\"identifier\":\"geometry.unedited\"},\"bones\":[]}]}");
        String exported = YsmGeometryJson.write(multipleGeometries).toString();
        Group recovered = YsmGeometryReader.read(DIGEST, "models/main.json", exported);
        check(recovered.children().getFirst().cubes().size() == 2, "hidden cubes preserved");
        check(!recovered.children().getFirst().visible(), "hidden group preserved");
        check(recovered.children().getFirst().cubes().getFirst().visible()
                && !recovered.children().getFirst().cubes().get(1).visible(), "cube visibility and order preserved");
        check(com.iridium126.createmanaindustry.compat.ysm.model.YsmJson.object(exported)
                .getAsJsonArray("minecraft:geometry").size() == 2, "additional geometry preserved");
        for (Vector rotation : List.of(Vector.ZERO, new Vector(20, -35, 70), new Vector(15, 90, 45), new Vector(-40, -90, 170))) {
            List<Face> uvFaces = java.util.stream.IntStream.range(0, 6)
                    .mapToObj(i -> new Face(3 + i, 7, i % 2 == 0 ? 5 : -5, 9, (i % 4) * 90, true)).toList();
            Cube rotated = new Cube(new Vector(-3, 6, 2), new Vector(4, 7, 9), new Vector(8, 4, -2), rotation, Vector.ONE, 0, true, uvFaces, "{}");
            var baked = YsmBakedGeometry.bake(rotated, 128, 64);
            var reconstructed = YsmBakedGeometry.reconstruct(baked, 128, 64);
            YsmBakedGeometry.verify(baked, YsmBakedGeometry.bake(reconstructed, 128, 64), 128, 64);
            var broken = new ArrayList<>(baked);
            var vertices = new ArrayList<>(broken.getFirst().vertices());
            var old = vertices.getFirst();
            vertices.set(0, new YsmBakedGeometry.Vertex(new Vector(old.position().x() + 0.25, old.position().y(), old.position().z()), old.u(), old.v()));
            broken.set(0, new YsmBakedGeometry.BakedFace(broken.getFirst().normal(), vertices));
            rejects(() -> YsmBakedGeometry.reconstruct(broken, 128, 64));
        }
        System.out.println("PASS: immutable values, equality, provenance, hidden geometry, multiple geometries, UV and complexity boundaries");
    }
    private static Group bone(String name, List<Cube> cubes, List<Group> children) {
        return new Group(name, Vector.ZERO, Vector.ZERO, Vector.ONE, true, cubes, children, null, "{}");
    }
    private static Group root(String part, List<Group> children) {
        return new Group(part, Vector.ZERO, Vector.ZERO, Vector.ONE, true, List.of(), children,
                new Root(DIGEST, part, 64, 64, "{}"), "{}");
    }
    private static void rejects(Runnable operation) {
        try { operation.run(); }
        catch (IllegalArgumentException | UnsupportedOperationException expected) { return; }
        throw new AssertionError("Invalid geometry was accepted");
    }
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
