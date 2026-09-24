package com.iridium126.createmanaindustry.compat.hexcasting.ysm;

import java.nio.file.Files;
import java.util.List;

import com.iridium126.createmanaindustry.compat.ysm.YsmReferenceStore;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Cube;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Face;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Group;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Root;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Vector;

import at.petrak.hexcasting.api.casting.iota.BooleanIota;
import at.petrak.hexcasting.api.casting.iota.DoubleIota;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.ListIota;
import at.petrak.hexcasting.api.casting.iota.NullIota;
import at.petrak.hexcasting.api.casting.iota.Vec3Iota;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class SharedGeometryActionsGameTests {
    @GameTest(template = "worldgen_test")
    public static void indexedPropertiesCoverCubeAndGroupReferences(GameTestHelper helper) throws Exception {
        var directory = Files.createTempDirectory("cmi-ysm-indexed-properties-");
        try (var store = new YsmReferenceStore(directory)) {
            CubeIota cubeRef = new CubeIota(store.writeCube(Cube.empty()));
            GroupIota groupRef = new GroupIota(store.writeTree(Group.empty()));
            String sourceDigest = "ab".repeat(32);
            Root root = new Root(sourceDigest, "parts/body.geo.json", 64, 32, "{}");
            Group fileRoot = new Group("root", Vector.ZERO, Vector.ZERO, Vector.ONE, true,
                    List.of(), List.of(), root, "{}");
            GroupIota fileRootRef = new GroupIota(store.writeTreeReference(fileRoot));

            OpGeometry getter = new OpGeometry(false);
            OpGeometry setter = new OpGeometry(true);
            helper.assertTrue(getter.getArgc() == 2 && setter.getArgc() == 3,
                    "Geometry getter/setter argument counts changed");

            for (int index = 0; index <= 7; index++) {
                Iota value = getter.applyGeometry(cubeRef, index, null, store);
                assertCubeGetter(helper, index, value);
            }
            for (int index : new int[] {2, 3, 4, 6})
                assertGroupGetter(helper, index, getter.applyGeometry(groupRef, index, null, store));
            helper.assertTrue(text(getter.applyGeometry(groupRef, 8, null, store)).equals("bone"),
                    "Group name getter returned the wrong value");
            helper.assertTrue(((ListIota) getter.applyGeometry(groupRef, 9, null, store)).getList().isEmpty(),
                    "Empty cube list getter returned entries");
            helper.assertTrue(((ListIota) getter.applyGeometry(groupRef, 10, null, store)).getList().isEmpty(),
                    "Empty child list getter returned entries");
            helper.assertTrue(text(getter.applyGeometry(groupRef, 12, null, store)).isEmpty()
                            && text(getter.applyGeometry(groupRef, 13, null, store)).isEmpty(),
                    "Standalone group source metadata should be empty");
            assertVector(helper, getter.applyGeometry(fileRootRef, 11, null, store), new Vec3(64, 32, 0),
                    "Texture size getter");
            helper.assertTrue(text(getter.applyGeometry(fileRootRef, 12, null, store)).equals("parts/body.geo.json"),
                    "Part getter returned the wrong value");
            helper.assertTrue(text(getter.applyGeometry(fileRootRef, 13, null, store)).equals(sourceDigest),
                    "Source getter returned the wrong value");
            GroupIota sourcedChild = new GroupIota(store.writeGroupReference(Group.empty(), List.of(), List.of(), fileRootRef.key()));
            helper.assertTrue(text(getter.applyGeometry(sourcedChild, 12, null, store)).equals("parts/body.geo.json"),
                    "Child group did not inherit its source part");
            helper.assertTrue(text(getter.applyGeometry(sourcedChild, 13, null, store)).equals(sourceDigest),
                    "Child group did not inherit its source digest");

            for (int index = 0; index <= 7; index++) {
                Iota edited = setter.applyGeometry(cubeRef, index, replacement(index, cubeRef, groupRef), store);
                helper.assertTrue(edited instanceof CubeIota, "Cube setter changed the reference type at index " + index);
                assertCubeSetter(helper, index, store.readCube(((CubeIota) edited).key()));
            }
            for (int index : new int[] {2, 3, 4, 6}) {
                Iota edited = setter.applyGeometry(groupRef, index, replacement(index, cubeRef, groupRef), store);
                helper.assertTrue(edited instanceof GroupIota, "Group setter changed the reference type at index " + index);
                assertGroupSetter(helper, index, store.readGroup(((GroupIota) edited).key()).value());
            }
            GroupIota renamed = (GroupIota) setter.applyGeometry(groupRef, 8,
                    replacement(8, cubeRef, groupRef), store);
            helper.assertTrue(text(getter.applyGeometry(renamed, 8, null, store)).equals("骨骼"),
                    "Group name setter did not update the name");
            GroupIota withCubes = (GroupIota) setter.applyGeometry(groupRef, 9,
                    replacement(9, cubeRef, groupRef), store);
            helper.assertTrue(store.cubeKeys(store.readGroup(withCubes.key())).equals(List.of(cubeRef.key())),
                    "Cube list setter did not update the list");
            GroupIota withChildren = (GroupIota) setter.applyGeometry(groupRef, 10,
                    replacement(10, cubeRef, groupRef), store);
            helper.assertTrue(store.groupKeys(store.readGroup(withChildren.key())).equals(List.of(groupRef.key())),
                    "Child list setter did not update the list");
            GroupIota resizedRoot = (GroupIota) setter.applyGeometry(fileRootRef, 11,
                    replacement(11, cubeRef, groupRef), store);
            helper.assertTrue(store.readGroup(resizedRoot.key()).value().root().textureWidth() == 128
                            && store.readGroup(resizedRoot.key()).value().root().textureHeight() == 64,
                    "Texture size setter did not update root metadata");

            helper.assertTrue(store.readCube(cubeRef.key()).equals(Cube.empty()), "Cube setter mutated the old reference");
            helper.assertTrue(store.readGroup(groupRef.key()).value().equals(Group.empty()), "Group setter mutated the old reference");
            helper.assertTrue(store.readGroup(fileRootRef.key()).value().root().textureWidth() == 64,
                    "Texture size setter mutated the old root reference");

            assertIllegalArgument(helper, () -> OpGeometry.propertyIndex(new DoubleIota(1.5)), "integer");
            assertIllegalArgument(helper, () -> OpGeometry.propertyIndex(new DoubleIota(-1)), "0 and 13");
            assertIllegalArgument(helper, () -> OpGeometry.propertyIndex(new DoubleIota(14)), "0 and 13");
            assertIllegalArgument(helper, () -> OpGeometry.propertyIndex(new NullIota()), "number");
            assertIllegalArgument(helper, () -> getter.applyGeometry(cubeRef, 8, null, store), "not available");
            assertIllegalArgument(helper, () -> getter.applyGeometry(groupRef, 5, null, store), "not available");
            assertIllegalArgument(helper, () -> getter.applyGeometry(groupRef, 11, null, store), "geometry file root");
            assertIllegalArgument(helper, () -> setter.applyGeometry(groupRef, 11, replacement(11, cubeRef, groupRef), store), "geometry file root");
            assertIllegalArgument(helper, () -> setter.applyGeometry(fileRootRef, 11,
                    new Vec3Iota(new Vec3(0, 32, 0)), store), "positive integer");
            assertIllegalArgument(helper, () -> getter.applyGeometry(new NullIota(), 2, null, store), "not available");
            assertIllegalArgument(helper, () -> setter.applyGeometry(fileRootRef, 12, new NullIota(), store), "read-only");
            assertIllegalArgument(helper, () -> setter.applyGeometry(fileRootRef, 13, new NullIota(), store), "read-only");
            assertIllegalArgument(helper, () -> setter.applyGeometry(cubeRef, 5, new Vec3Iota(Vec3.ZERO), store), "number");
        }
        helper.succeed();
    }

    private static void assertCubeGetter(GameTestHelper helper, int index, Iota value) {
        switch (index) {
            case 0, 2, 3 -> assertVector(helper, value, Vec3.ZERO, "Cube vector getter " + index);
            case 1, 4 -> assertVector(helper, value, new Vec3(1, 1, 1), "Cube vector getter " + index);
            case 5 -> helper.assertTrue(value instanceof DoubleIota number && number.getDouble() == 0,
                    "Inflate getter returned the wrong value");
            case 6 -> helper.assertTrue(value instanceof BooleanIota visible && visible.getBool(),
                    "Visibility getter returned the wrong value");
            case 7 -> helper.assertTrue(value instanceof ListIota faces && faces.getList().size() == 6,
                    "UV getter did not return six faces");
            default -> helper.fail("Unknown cube property index " + index);
        }
    }

    private static void assertGroupGetter(GameTestHelper helper, int index, Iota value) {
        switch (index) {
            case 2, 3 -> assertVector(helper, value, Vec3.ZERO, "Group vector getter " + index);
            case 4 -> assertVector(helper, value, new Vec3(1, 1, 1), "Group scale getter");
            case 6 -> helper.assertTrue(value instanceof BooleanIota visible && visible.getBool(),
                    "Group visibility getter returned the wrong value");
            default -> helper.fail("Unknown group property index " + index);
        }
    }

    private static void assertCubeSetter(GameTestHelper helper, int index, Cube cube) {
        Vector expected = new Vector(3, 4, 5);
        switch (index) {
            case 0 -> assertVector(helper, cube.origin(), expected, "Cube origin setter");
            case 1 -> assertVector(helper, cube.size(), expected, "Cube size setter");
            case 2 -> assertVector(helper, cube.pivot(), expected, "Cube pivot setter");
            case 3 -> assertVector(helper, cube.rotation(), expected, "Cube rotation setter");
            case 4 -> assertVector(helper, cube.scale(), expected, "Cube scale setter");
            case 5 -> helper.assertTrue(cube.inflate() == 0.5, "Cube inflate setter failed");
            case 6 -> helper.assertTrue(!cube.visible(), "Cube visibility setter failed");
            case 7 -> helper.assertTrue(cube.faces().getFirst().equals(new Face(1, 2, 3, 4, 90, false)),
                    "Cube UV setter failed");
            default -> helper.fail("Unknown cube property index " + index);
        }
    }

    private static void assertGroupSetter(GameTestHelper helper, int index, Group group) {
        Vector expected = new Vector(3, 4, 5);
        switch (index) {
            case 2 -> assertVector(helper, group.pivot(), expected, "Group pivot setter");
            case 3 -> assertVector(helper, group.rotation(), expected, "Group rotation setter");
            case 4 -> assertVector(helper, group.scale(), expected, "Group scale setter");
            case 6 -> helper.assertTrue(!group.visible(), "Group visibility setter failed");
            default -> helper.fail("Unknown group property index " + index);
        }
    }

    private static Iota replacement(int index, CubeIota cubeRef, GroupIota groupRef) {
        return switch (index) {
            case 0, 1, 2, 3, 4 -> new Vec3Iota(new Vec3(3, 4, 5));
            case 5 -> new DoubleIota(0.5);
            case 6 -> new BooleanIota(false);
            case 7 -> uvValue();
            case 8 -> textValue("骨骼");
            case 9 -> new ListIota(List.of(cubeRef));
            case 10 -> new ListIota(List.of(groupRef));
            case 11 -> new Vec3Iota(new Vec3(128, 64, 0));
            default -> throw new IllegalArgumentException("No setter value for property index " + index);
        };
    }

    private static ListIota uvValue() {
        Iota face = new ListIota(List.of(new DoubleIota(1), new DoubleIota(2), new DoubleIota(3),
                new DoubleIota(4), new DoubleIota(90), new BooleanIota(false)));
        return new ListIota(List.of(face, face, face, face, face, face));
    }

    private static StringIota textValue(String text) { return new StringIota(text); }

    private static String text(Iota value) {
        return ((StringIota) value).value();
    }

    private static void assertVector(GameTestHelper helper, Iota actual, Vec3 expected, String message) {
        helper.assertTrue(actual instanceof Vec3Iota value && value.getVec3().equals(expected), message);
    }

    private static void assertVector(GameTestHelper helper, Vector actual, Vector expected, String message) {
        helper.assertTrue(actual.equals(expected), message + ": " + actual);
    }

    private static void assertIllegalArgument(GameTestHelper helper, Runnable operation, String expectedMessage) {
        try {
            operation.run();
            helper.fail("Expected an error containing: " + expectedMessage);
        } catch (IllegalArgumentException expected) {
            helper.assertTrue(expected.getMessage().contains(expectedMessage),
                    "Expected error containing '" + expectedMessage + "', got: " + expected.getMessage());
        }
    }

    private SharedGeometryActionsGameTests() {}
}
