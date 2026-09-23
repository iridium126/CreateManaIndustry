package com.iridium126.createmanaindustry.compat.hexcasting.ysm;

import java.util.ArrayList;
import java.util.List;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.*;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometryIO;
import at.petrak.hexcasting.api.casting.castables.ConstMediaAction;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.iota.*;
import at.petrak.hexcasting.api.casting.mishaps.Mishap;
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/** Pure immutable editing operations. No world changes or resource loading occur here. */
public record OpGeometry(boolean cube, String property, boolean setter) implements ConstMediaAction {
    @Override public int getArgc() { return property.equals("create") ? 0 : setter ? 2 : 1; }
    @Override public long getMediaCost() { return 0; }
    @Override public List<Iota> execute(List<? extends Iota> args, CastingEnvironment env) throws Mishap {
        if (property.equals("create")) return List.of(cube ? new CubeIota(Cube.empty()) : new GroupIota(Group.empty()));
        Iota target = args.get(0);
        if (cube && !(target instanceof CubeIota) || !cube && !(target instanceof GroupIota))
            throw new MishapInvalidIota(target, getArgc() - 1, Component.translatable("hexcasting.iota.createmanaindustry:" + (cube ? "cube" : "group")));
        try {
            Iota result = cube ? cube((CubeIota) target, setter ? args.get(1) : null)
                    : group((GroupIota) target, setter ? args.get(1) : null);
            if (setter) {
                if (result instanceof CubeIota ci) YsmGeometryIO.encodeCube(ci.value());
                else if (result instanceof GroupIota gi) YsmGeometryIO.encodeGroup(gi.value());
            }
            if (IotaType.isTooLargeToSerialize(List.of(result)))
                throw new MishapYsm("Edited geometry exceeds Hexcasting's iota serialization limit");
            return List.of(result);
        } catch (IllegalArgumentException failure) {
            throw new MishapInvalidIota(setter ? args.get(1) : target, 0,
                    Component.translatable("createmanaindustry.hex.ysm.valid_geometry", Component.literal(failure.getMessage())));
        }
    }

    private Iota cube(CubeIota input, Iota replacement) {
        Cube c = input.value();
        if (!setter) return switch (property) {
            case "origin" -> vector(c.origin()); case "size" -> vector(c.size());
            case "pivot" -> vector(c.pivot()); case "rotation" -> vector(c.rotation()); case "scale" -> vector(c.scale());
            case "inflate" -> new DoubleIota(c.inflate()); case "visible" -> new BooleanIota(c.visible());
            case "uv" -> new ListIota(c.faces().stream().<Iota>map(f -> new ListIota(List.of(new DoubleIota(f.u()), new DoubleIota(f.v()),
                    new DoubleIota(f.width()), new DoubleIota(f.height()), new DoubleIota(f.rotation()), new BooleanIota(f.visible())))).toList());
            default -> throw new IllegalArgumentException("Unknown cube property");
        };
        return new CubeIota(new Cube(property.equals("origin") ? vector(replacement) : c.origin(),
                property.equals("size") ? vector(replacement) : c.size(), property.equals("pivot") ? vector(replacement) : c.pivot(),
                property.equals("rotation") ? vector(replacement) : c.rotation(), property.equals("scale") ? vector(replacement) : c.scale(),
                property.equals("inflate") ? number(replacement) : c.inflate(), property.equals("visible") ? bool(replacement) : c.visible(),
                property.equals("uv") ? faces(replacement) : c.faces(), c.extraJson()));
    }
    private Iota group(GroupIota input, Iota replacement) {
        Group g = input.value();
        if (!setter) return switch (property) {
            case "name" -> text(g.name()); case "pivot" -> vector(g.pivot());
            case "rotation" -> vector(g.rotation()); case "scale" -> vector(g.scale()); case "visible" -> new BooleanIota(g.visible());
            case "cubes" -> new ListIota(g.cubes().stream().<Iota>map(CubeIota::new).toList());
            case "children" -> new ListIota(g.children().stream().<Iota>map(GroupIota::new).toList());
            case "part" -> text(root(g).part()); case "source" -> text(root(g).snapshot());
            case "texture_size" -> new Vec3Iota(new Vec3(root(g).textureWidth(), root(g).textureHeight(), 0));
            default -> throw new IllegalArgumentException("Unknown group property");
        };
        Root root = g.root();
        if (property.equals("texture_size")) {
            Vector v = vector(replacement);
            if (v.z() != 0 || v.x() != Math.rint(v.x()) || v.y() != Math.rint(v.y())) throw new IllegalArgumentException("Texture size must be (integer width, integer height, 0)");
            Root old = root(g);
            root = new Root(old.snapshot(), old.part(), (int)v.x(), (int)v.y(), old.descriptionJson());
        }
        return new GroupIota(new Group(property.equals("name") ? text(replacement) : g.name(),
                property.equals("pivot") ? vector(replacement) : g.pivot(), property.equals("rotation") ? vector(replacement) : g.rotation(),
                property.equals("scale") ? vector(replacement) : g.scale(), property.equals("visible") ? bool(replacement) : g.visible(),
                property.equals("cubes") ? cubes(replacement) : g.cubes(), property.equals("children") ? groups(replacement) : g.children(), root, g.extraJson()));
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
    private static List<Iota> list(Iota iota, int max) {
        if (!(iota instanceof ListIota l) || l.getList().size() > max) throw new IllegalArgumentException("Expected a bounded list");
        var values = new ArrayList<Iota>(); l.getList().forEach(values::add); return values;
    }
    private static List<Cube> cubes(Iota iota) {
        var cubes = new ArrayList<Cube>();
        for (Iota entry : list(iota, 4096)) {
            if (!(entry instanceof CubeIota c)) throw new IllegalArgumentException("Expected only cube iotas");
            cubes.add(c.value());
        }
        return cubes;
    }
    private static List<Group> groups(Iota iota) {
        var groups = new ArrayList<Group>();
        for (Iota entry : list(iota, 4096)) {
            if (!(entry instanceof GroupIota g)) throw new IllegalArgumentException("Expected only group iotas");
            groups.add(g.value());
        }
        return groups;
    }
    private static List<Face> faces(Iota iota) {
        List<Iota> entries = list(iota, 6);
        if (entries.size() != 6) throw new IllegalArgumentException("Expected six UV faces");
        var faces = new ArrayList<Face>();
        for (Iota entry : entries) {
            var fields = list(entry, 6);
            if (fields.size() != 6) throw new IllegalArgumentException("Expected six fields per UV face");
            double rotation = number(fields.get(4));
            if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) throw new IllegalArgumentException("Invalid UV rotation");
            faces.add(new Face(number(fields.get(0)), number(fields.get(1)), number(fields.get(2)), number(fields.get(3)), (int)rotation, bool(fields.get(5))));
        }
        return faces;
    }
    private static Iota text(String value) { return new ListIota(value.codePoints().mapToObj(cp -> (Iota)new DoubleIota(cp)).toList()); }
    private static String text(Iota iota) {
        var out = new StringBuilder();
        for (Iota entry : list(iota, 1024)) {
            double number = number(entry);
            if (number != Math.rint(number) || number < 0 || number > 0x10ffff || number >= 0xd800 && number <= 0xdfff)
                throw new IllegalArgumentException("Invalid Unicode scalar value");
            out.appendCodePoint((int)number);
        }
        return out.toString();
    }
}
