package com.iridium126.createmanaindustry.compat.hexcasting.ysm;

import java.util.ArrayList;
import java.util.List;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.*;
import com.iridium126.createmanaindustry.compat.ysm.YsmReferenceStore;
import com.iridium126.createmanaindustry.compat.ysm.YsmServerRuntime;
import at.petrak.hexcasting.api.casting.castables.ConstMediaAction;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.iota.*;
import at.petrak.hexcasting.api.casting.mishaps.Mishap;
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/** Pure immutable editing operations. No world changes or resource loading occur here. */
public record OpGeometry(boolean setter) implements ConstMediaAction {
    static final List<String> PROPERTIES = List.of("origin", "size", "pivot", "rotation", "scale", "inflate",
            "visible", "uv", "name", "cubes", "children", "texture_size", "part", "source");

    @Override public int getArgc() { return setter ? 3 : 2; }
    @Override public long getMediaCost() { return 0; }

    @Override public List<Iota> execute(List<? extends Iota> args, CastingEnvironment env) throws Mishap {
        Iota target = args.get(0);
        if (!(target instanceof CubeIota) && !(target instanceof GroupIota))
            throw new MishapInvalidIota(target, getArgc() - 1,
                    Component.translatable("hexcasting.iota.createmanaindustry:geometry"));

        int propertyIndex;
        try {
            propertyIndex = propertyIndex(args.get(1));
        } catch (IllegalArgumentException failure) {
            throw new MishapInvalidGeometry(failure.getMessage());
        }

        String property = PROPERTIES.get(propertyIndex);
        if (!supports(propertyIndex, target)) {
            boolean cubeProperty = propertyIndex <= 1 || propertyIndex == 5 || propertyIndex == 7;
            throw new MishapInvalidIota(target, getArgc() - 1,
                    Component.translatable("hexcasting.iota.createmanaindustry:" + (cubeProperty ? "cube" : "group")));
        }
        if (setter && propertyIndex >= 12)
            throw new MishapInvalidGeometry("Property is read-only");

        try {
            Iota result = applyGeometry(target, propertyIndex, setter ? args.get(2) : null, store(env));
            return List.of(result);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw new MishapInvalidGeometry(failure.getMessage());
        }
    }

    Iota applyGeometry(Iota input, int propertyIndex, Iota replacement, YsmReferenceStore store) {
        if (propertyIndex < 0 || propertyIndex >= PROPERTIES.size())
            throw new IllegalArgumentException("Property index must be between 0 and 13");
        if (!supports(propertyIndex, input))
            throw new IllegalArgumentException("Property is not available for this geometry reference");
        if (setter && propertyIndex >= 12)
            throw new IllegalArgumentException("Property is read-only");

        String property = PROPERTIES.get(propertyIndex);
        if (input instanceof CubeIota) return cube(input, property, replacement, store);
        return group(input, property, replacement, store);
    }

    static int propertyIndex(Iota selector) {
        if (!(selector instanceof DoubleIota value)) throw new IllegalArgumentException("Expected a number");
        double index = value.getDouble();
        if (!Double.isFinite(index)) throw new IllegalArgumentException("Expected a finite number");
        if (index != Math.rint(index)) throw new IllegalArgumentException("Property index must be an integer");
        if (index < 0 || index >= PROPERTIES.size())
            throw new IllegalArgumentException("Property index must be between 0 and 13");
        return (int) index;
    }

    private static boolean supports(int propertyIndex, Iota target) {
        if (target instanceof CubeIota) return propertyIndex <= 7;
        if (target instanceof GroupIota) return propertyIndex >= 2 && propertyIndex <= 13 && propertyIndex != 5;
        return false;
    }

    private Iota cube(Iota input, String property, Iota replacement, YsmReferenceStore store) {
        CubeIota reference = (CubeIota) input;
        Cube c = store.readCube(reference.key());
        if (!setter) return switch (property) {
            case "origin" -> vector(c.origin()); case "size" -> vector(c.size());
            case "pivot" -> vector(c.pivot()); case "rotation" -> vector(c.rotation()); case "scale" -> vector(c.scale());
            case "inflate" -> new DoubleIota(c.inflate()); case "visible" -> new BooleanIota(c.visible());
            case "uv" -> new ListIota(c.faces().stream().<Iota>map(f -> new ListIota(List.of(new DoubleIota(f.u()), new DoubleIota(f.v()),
                    new DoubleIota(f.width()), new DoubleIota(f.height()), new DoubleIota(f.rotation()), new BooleanIota(f.visible())))).toList());
            default -> throw new IllegalArgumentException("Unknown cube property");
        };
        Cube edited = new Cube(property.equals("origin") ? vector(replacement) : c.origin(),
                property.equals("size") ? vector(replacement) : c.size(), property.equals("pivot") ? vector(replacement) : c.pivot(),
                property.equals("rotation") ? vector(replacement) : c.rotation(), property.equals("scale") ? vector(replacement) : c.scale(),
                property.equals("inflate") ? number(replacement) : c.inflate(), property.equals("visible") ? bool(replacement) : c.visible(),
                property.equals("uv") ? faces(replacement) : c.faces(), c.extraJson());
        return new CubeIota(store.writeCubeReference(edited, reference.key()));
    }
    private Iota group(Iota input, String property, Iota replacement, YsmReferenceStore store) {
        GroupIota reference = (GroupIota) input;
        YsmReferenceStore.GroupNode node = store.readGroup(reference.key());
        Group g = node.value();
        if (!setter) return switch (property) {
            case "name" -> new StringIota(g.name()); case "pivot" -> vector(g.pivot());
            case "rotation" -> vector(g.rotation()); case "scale" -> vector(g.scale()); case "visible" -> new BooleanIota(g.visible());
            case "cubes" -> new ListIota(store.cubeKeys(node).stream().<Iota>map(CubeIota::new).toList());
            case "children" -> new ListIota(store.groupKeys(node).stream().<Iota>map(GroupIota::new).toList());
            case "part" -> new StringIota(node.sourcePart() == null ? "" : node.sourcePart());
            case "source" -> new StringIota(node.sourceDigest() == null ? "" : node.sourceDigest());
            case "texture_size" -> {
                Root source = root(g);
                yield new Vec3Iota(new Vec3(source.textureWidth(), source.textureHeight(), 0));
            }
            default -> throw new IllegalArgumentException("Unknown group property");
        };
        Root root = g.root();
        if (property.equals("texture_size")) {
            if (root == null) throw new IllegalArgumentException("Expected a geometry file root");
            Vector v = vector(replacement);
            if (v.z() != 0 || v.x() != Math.rint(v.x()) || v.y() != Math.rint(v.y())
                    || v.x() < 1 || v.y() < 1 || v.x() > Integer.MAX_VALUE || v.y() > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Texture size must use positive integer width and height, followed by 0");
            Root old = root(g);
            root = new Root(old.snapshot(), old.part(), (int)v.x(), (int)v.y(), old.descriptionJson());
        }
        List<String> cubeKeys = property.equals("cubes") ? referenceCubes(replacement, store) : store.cubeKeys(node);
        List<String> groupKeys = property.equals("children") ? referenceGroups(replacement, store) : store.groupKeys(node);
        Group header = new Group(property.equals("name") ? string(replacement) : g.name(),
                property.equals("pivot") ? vector(replacement) : g.pivot(), property.equals("rotation") ? vector(replacement) : g.rotation(),
                property.equals("scale") ? vector(replacement) : g.scale(), property.equals("visible") ? bool(replacement) : g.visible(),
                List.of(), List.of(), root, g.extraJson());
        return new GroupIota(store.writeGroupReference(header, cubeKeys, groupKeys, reference.key()));
    }
    static YsmReferenceStore store(CastingEnvironment env) {
        if (env == null || env.getWorld().getServer() == null) throw new IllegalStateException("YSM reference requires a server world");
        return YsmServerRuntime.get(env.getWorld().getServer()).references();
    }
    private static Root root(Group group) {
        if (group.root() == null) throw new IllegalArgumentException("Expected a geometry file root");
        return group.root();
    }
    private static Iota vector(Vector v) { return new Vec3Iota(new Vec3(v.x(), v.y(), v.z())); }
    private static Vector vector(Iota iota) {
        if (!(iota instanceof Vec3Iota v)) throw new IllegalArgumentException("Expected a vector");
        Vec3 value = v.getVec3(); return new Vector(value.x, value.y, value.z);
    }
    private static double number(Iota iota) {
        if (!(iota instanceof DoubleIota n)) throw new IllegalArgumentException("Expected a number");
        double value = n.getDouble();
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Expected a finite number");
        return value;
    }
    private static boolean bool(Iota iota) {
        if (!(iota instanceof BooleanIota b)) throw new IllegalArgumentException("Expected a boolean");
        return b.getBool();
    }
    private static List<Iota> list(Iota iota) {
        if (!(iota instanceof ListIota l)) throw new IllegalArgumentException("Expected a list");
        var values = new ArrayList<Iota>(); l.getList().forEach(values::add); return values;
    }
    private static List<String> referenceCubes(Iota iota, YsmReferenceStore store) {
        var keys = new ArrayList<String>();
        for (Iota entry : list(iota)) {
            if (entry instanceof CubeIota ref) { store.readCube(ref.key()); keys.add(ref.key()); }
            else throw new IllegalArgumentException("Expected only cube iotas");
        }
        return keys;
    }
    private static List<String> referenceGroups(Iota iota, YsmReferenceStore store) {
        var keys = new ArrayList<String>();
        for (Iota entry : list(iota)) {
            if (entry instanceof GroupIota ref) { store.readGroup(ref.key()); keys.add(ref.key()); }
            else throw new IllegalArgumentException("Expected only group iotas");
        }
        return keys;
    }
    private static List<Face> faces(Iota iota) {
        List<Iota> entries = list(iota);
        if (entries.size() != 6) throw new IllegalArgumentException("Expected six UV faces");
        var faces = new ArrayList<Face>();
        for (Iota entry : entries) {
            var fields = list(entry);
            if (fields.size() != 6) throw new IllegalArgumentException("Expected six fields per UV face");
            double rotation = number(fields.get(4));
            if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) throw new IllegalArgumentException("Invalid UV rotation");
            faces.add(new Face(number(fields.get(0)), number(fields.get(1)), number(fields.get(2)), number(fields.get(3)), (int)rotation, bool(fields.get(5))));
        }
        return faces;
    }
    private static String string(Iota iota) {
        if (iota instanceof StringIota value) return value.value();
        throw new IllegalArgumentException("Expected a String Iota");
    }
}
