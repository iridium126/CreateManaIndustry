package com.iridium126.createmanaindustry.compat.ysm.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.*;

/**
 * Fits an editable cuboid to native YSM vertices, then verifies every vertex/UV.
 * Native positions use blocks and reflect X; editor positions use Bedrock pixels.
 * The fit does not claim to recover the author's original pivot or inflate.
 */
public final class YsmBakedGeometry {
    private static final double EPS = 0.002; // pixels; accounts for baked float precision
    private static final Vector X = new Vector(1, 0, 0), Y = new Vector(0, 1, 0), Z = new Vector(0, 0, 1);
    private static final List<Vector> NORMALS = List.of(mul(Z, -1), Z, X, mul(X, -1), Y, mul(Y, -1));
    private static final List<Vector> U_AXES = List.of(mul(X,-1), X, mul(Z,-1), Z, mul(X,-1), mul(X,-1));
    private static final List<Vector> V_AXES = List.of(mul(Y,-1),mul(Y,-1),mul(Y,-1),mul(Y,-1),mul(Z,-1),Z);
    public record Vertex(Vector position, double u, double v) {
        public Vertex {
            if (!Double.isFinite(u) || !Double.isFinite(v)) throw new IllegalArgumentException("Non-finite baked UV");
        }
    }
    public record BakedFace(Vector normal, List<Vertex> vertices) {
        public BakedFace {
            vertices = List.copyOf(vertices);
            if (vertices.size() != 4) throw new IllegalArgumentException("Baked face must have four vertices");
        }
    }

    public static Cube reconstruct(List<BakedFace> baked, int width, int height) {
        if (baked.isEmpty() || baked.size() > 6 || width <= 0 || height <= 0) throw new IllegalArgumentException("Unsupported baked cube");
        var axes = new ArrayList<Vector>();
        for (BakedFace face : baked) {
            axis(axes, face.normal());
            for (int i = 0; i < 4; i++) axis(axes, sub(face.vertices().get((i + 1) % 4).position(), face.vertices().get(i).position()));
        }
        if (axes.isEmpty()) throw new IllegalArgumentException("Baked cube has no orientation");
        Vector first = axes.getFirst();
        Vector second = axes.size() > 1 ? axes.get(1) : unit(cross(first, Math.abs(first.x()) < 0.9 ? X : Y));
        second = unit(sub(second, mul(first, dot(first, second))));
        Vector[] basis = closestFrame(new Vector[]{first, second, unit(cross(first, second))}, baked);
        double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (BakedFace face : baked) for (Vertex vertex : face.vertices()) {
            Vector p = mul(vertex.position(), 16);
            for (int axis = 0; axis < 3; axis++) {
                double q = dot(p, basis[axis]); min[axis] = Math.min(min[axis], q); max[axis] = Math.max(max[axis], q);
            }
        }
        Vector center = add(add(mul(basis[0], (min[0] + max[0]) / 2), mul(basis[1], (min[1] + max[1]) / 2)), mul(basis[2], (min[2] + max[2]) / 2));
        Vector pivot = new Vector(-center.x(), center.y(), center.z());
        Vector size = new Vector(max[0] - min[0], max[1] - min[1], max[2] - min[2]);
        Vector origin = sub(pivot, mul(size, 0.5));
        Vector euler = euler(basis);
        var faces = new ArrayList<>(Collections.nCopies(6, new Face(0, 0, 0, 0, 0, false)));
        Cube frame = new Cube(origin, size, pivot, euler, Vector.ONE, 0, true, faces, "{}");
        boolean[] assigned = new boolean[6];
        for (BakedFace face : baked) {
            Vector n = unit(face.normal()); int direction = -1;
            for (int i = 0; i < 6; i++) if (dot(n, transform(basis, NORMALS.get(i))) > 0.9999) { direction = i; break; }
            if (direction < 0 || assigned[direction]) throw new IllegalArgumentException("Baked face normals are not a cuboid");
            assigned[direction] = true;
            List<Vector> expected = positions(frame, direction);
            double[] u = new double[4], v = new double[4];
            boolean[] used = new boolean[4];
            for (int i = 0; i < 4; i++) {
                int found = -1;
                for (int j = 0; j < 4; j++) if (!used[j] && distance(expected.get(i), mul(face.vertices().get(j).position(), 16)) <= EPS) { found = j; break; }
                if (found < 0) throw new IllegalArgumentException("Baked vertices do not match a cuboid (including normals)");
                used[found] = true;
                u[i] = face.vertices().get(found).u() * width;
                v[i] = face.vertices().get(found).v() * height;
            }
            Face fitted = fitUv(u, v);
            faces.set(direction, fitted);
        }
        Cube result = new Cube(origin, size, pivot, euler, Vector.ONE, 0, true, faces, "{}");
        verify(baked, bake(result, width, height), width, height);
        return result;
    }

    /** Port of YSMParser's UV-tangent scored Blockbench-cube restoration. */
    public static Cube reconstructReference(List<BakedFace> baked, int width, int height) {
        if (baked.isEmpty() || width <= 0 || height <= 0) throw new IllegalArgumentException("Unsupported baked cube");
        var unique = new java.util.LinkedHashMap<String, Vector>();
        var axes = new ArrayList<Vector>();
        var info = new ArrayList<FaceInfo>();
        for (BakedFace face : baked) {
            Vector[] points = new Vector[4];
            for (int i = 0; i < 4; i++) {
                points[i] = mul(face.vertices().get(i).position(), 16);
                Vector rounded = clean(points[i]);
                unique.putIfAbsent(key(rounded), rounded);
            }
            Vector tangentU = Vector.ZERO, tangentV = Vector.ZERO;
            for (int i = 0; i < 4; i++) {
                int next = (i + 1) & 3;
                Vector delta = sub(points[i], points[next]);
                double du = face.vertices().get(i).u() - face.vertices().get(next).u();
                double dv = face.vertices().get(i).v() - face.vertices().get(next).v();
                double length = Math.sqrt(dot(delta, delta));
                if (length < 1e-5) continue;
                if (Math.abs(du) > 1e-5 && Math.abs(dv) < 1e-5) tangentU = mul(delta, (du > 0 ? 1 : -1) / length);
                if (Math.abs(dv) > 1e-5 && Math.abs(du) < 1e-5) tangentV = mul(delta, (dv > 0 ? 1 : -1) / length);
            }
            info.add(new FaceInfo(face.normal(), tangentU, tangentV, face));
            axes.add(face.normal());
            if (length(tangentU) > 0.5) axes.add(tangentU);
            if (length(tangentV) > 0.5) axes.add(tangentV);
        }

        var rawAxes = new ArrayList<Vector>();
        for (Vector axis : axes) {
            if (dot(axis, axis) < 1e-12) continue;
            Vector normalized = unit(axis);
            boolean found = false;
            for (Vector existing : rawAxes) if (Math.abs(dot(normalized, existing)) > 0.95) { found = true; break; }
            if (!found) rawAxes.add(normalized);
            if (rawAxes.size() == 3) break;
        }
        if (rawAxes.size() == 1) {
            Vector n = rawAxes.getFirst();
            Vector temp = Math.abs(n.x()) < 0.9 ? X : Y;
            Vector u = unit(cross(n, temp));
            rawAxes.add(u); rawAxes.add(unit(cross(n, u)));
        } else if (rawAxes.size() == 2) {
            Vector w = cross(rawAxes.get(0), rawAxes.get(1));
            if (length(w) > 1e-5) rawAxes.add(unit(w));
        } else if (rawAxes.isEmpty()) {
            rawAxes.add(X); rawAxes.add(Y); rawAxes.add(Z);
        }
        if (rawAxes.size() < 3) throw new IllegalArgumentException("Insufficient baked cube axes");

        int[][] permutations = {{0,1,2},{0,2,1},{1,0,2},{1,2,0},{2,0,1},{2,1,0}};
        int[][] signs = {{1,1,1},{1,1,-1},{1,-1,1},{1,-1,-1},{-1,1,1},{-1,1,-1},{-1,-1,1},{-1,-1,-1}};
        Vector[] best = null;
        Vector bestEuler = Vector.ZERO;
        double bestScore = Double.NEGATIVE_INFINITY;
        double bestEulerSum = Double.POSITIVE_INFINITY;
        for (int[] permutation : permutations) for (int[] sign : signs) {
            Vector c0 = mul(rawAxes.get(permutation[0]), sign[0]);
            Vector c1 = mul(rawAxes.get(permutation[1]), sign[1]);
            Vector c2 = mul(rawAxes.get(permutation[2]), sign[2]);
            if (dot(c0, cross(c1, c2)) < 0.9) continue;
            c0 = unit(c0);
            c1 = unit(sub(c1, mul(c0, dot(c1, c0))));
            c2 = unit(cross(c0, c1));
            Vector[] frame = {c0, c1, c2};
            double score = 0;
            for (FaceInfo face : info) {
                Vector normal = transpose(frame, face.normal());
                Vector tu = transpose(frame, face.tangentU()), tv = transpose(frame, face.tangentV());
                Vector[] expected = expectedUv(normal);
                score += Math.abs(dot(tu, expected[0])) + Math.abs(dot(tv, expected[1]));
            }
            Vector euler = matrixEuler(frame);
            double eulerSum = Math.abs(euler.x()) + Math.abs(euler.y()) + Math.abs(euler.z());
            if (score > bestScore + 1e-4 || (Math.abs(score - bestScore) <= 1e-4 && eulerSum < bestEulerSum)) {
                bestScore = score; bestEulerSum = eulerSum; best = frame; bestEuler = euler;
            }
        }
        if (best == null) throw new IllegalArgumentException("Cannot restore baked cube orientation");

        double[] localMin = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] localMax = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (Vector point : unique.values()) {
            Vector local = transpose(best, point);
            for (int axis = 0; axis < 3; axis++) {
                double value = component(local, axis);
                localMin[axis] = Math.min(localMin[axis], value);
                localMax[axis] = Math.max(localMax[axis], value);
            }
        }
        Vector localCenter = new Vector((localMin[0] + localMax[0]) * 0.5,
                (localMin[1] + localMax[1]) * 0.5, (localMin[2] + localMax[2]) * 0.5);
        Vector center = add(add(mul(best[0], localCenter.x()), mul(best[1], localCenter.y())), mul(best[2], localCenter.z()));
        Vector pivot = new Vector(-center.x(), center.y(), center.z());

        double[] sizeSigns = {1,1,1};
        for (int axis = 0; axis < 3; axis++) {
            Vector localAxis = best[axis];
            for (FaceInfo face : info) {
                Vector normal = transpose(best, face.normal());
                double component = component(normal, axis);
                if (Math.abs(component) <= 0.5) continue;
                Vector faceCenter = Vector.ZERO;
                for (Vertex vertex : face.face().vertices()) faceCenter = add(faceCenter, mul(vertex.position(), 4));
                Vector offset = sub(faceCenter, center);
                double projection = dot(offset, localAxis);
                if (Math.abs(projection) > 1e-3 && projection * component < 0) sizeSigns[axis] = -1;
                break;
            }
        }
        double[] extents = {0,0,0};
        for (Vector point : unique.values()) {
            Vector delta = sub(point, center);
            for (int axis = 0; axis < 3; axis++) extents[axis] = Math.max(extents[axis], Math.abs(dot(delta, best[axis])));
        }
        Vector size = new Vector(2 * extents[0] * sizeSigns[0], 2 * extents[1] * sizeSigns[1], 2 * extents[2] * sizeSigns[2]);
        Vector origin = sub(pivot, mul(size, 0.5));
        Vector rotation = new Vector(-bestEuler.x(), -bestEuler.y(), bestEuler.z());
        var faces = new ArrayList<Face>(Collections.nCopies(6, new Face(0,0,0,0,0,false)));
        for (FaceInfo face : info) {
            Vector normal = transpose(best, face.normal());
            Vector tu = transpose(best, face.tangentU()), tv = transpose(best, face.tangentV());
            Vector[] expected = expectedUv(normal);
            expected[0] = componentMultiply(expected[0], sizeSigns);
            expected[1] = componentMultiply(expected[1], sizeSigns);
            double uMin = Double.POSITIVE_INFINITY, vMin = Double.POSITIVE_INFINITY;
            double uMax = Double.NEGATIVE_INFINITY, vMax = Double.NEGATIVE_INFINITY;
            for (Vertex vertex : face.face().vertices()) {
                double u = vertex.u() * width, v = vertex.v() * height;
                uMin = Math.min(uMin, u); uMax = Math.max(uMax, u); vMin = Math.min(vMin, v); vMax = Math.max(vMax, v);
            }
            double u = uMin, v = vMin, uSize = uMax - uMin, vSize = vMax - vMin;
            if (dot(tu, expected[0]) < -0.2) { u = uMax; uSize = -uSize; }
            if (dot(tv, expected[1]) < -0.2) { v = vMax; vSize = -vSize; }
            int direction = faceDirection(normal);
            if (direction < 0) throw new IllegalArgumentException("Cannot restore baked face normal");
            faces.set(direction, new Face(clean(u), clean(v), clean(uSize), clean(vSize), 0, true));
        }
        return new Cube(origin, size, pivot, rotation, Vector.ONE, 0, true, faces, "{}");
    }

    private record FaceInfo(Vector normal, Vector tangentU, Vector tangentV, BakedFace face) {}

    private static Vector[] expectedUv(Vector normal) {
        Vector n = new Vector(Math.round(normal.x()), Math.round(normal.y()), Math.round(normal.z()));
        if (n.x() == -1) return new Vector[]{Z, mul(Y,-1)};
        if (n.x() == 1) return new Vector[]{mul(Z,-1), mul(Y,-1)};
        if (n.y() == 1) return new Vector[]{mul(X,-1), mul(Z,-1)};
        if (n.y() == -1) return new Vector[]{mul(X,-1), Z};
        if (n.z() == -1) return new Vector[]{mul(X,-1), mul(Y,-1)};
        if (n.z() == 1) return new Vector[]{X, mul(Y,-1)};
        throw new IllegalArgumentException("Invalid baked face normal");
    }

    private static int faceDirection(Vector normal) {
        if (normal.x() > 0.9) return 2;
        if (normal.x() < -0.9) return 3;
        if (normal.y() > 0.9) return 4;
        if (normal.y() < -0.9) return 5;
        if (normal.z() > 0.9) return 1;
        if (normal.z() < -0.9) return 0;
        return -1;
    }

    private static Vector transpose(Vector[] matrix, Vector value) {
        return new Vector(dot(matrix[0], value), dot(matrix[1], value), dot(matrix[2], value));
    }
    private static Vector matrixEuler(Vector[] m) {
        double sy = Math.sqrt(m[0].x() * m[0].x() + m[0].y() * m[0].y());
        boolean singular = sy < 1e-6;
        double x = singular ? Math.atan2(-m[2].y(), m[1].y()) : Math.atan2(m[1].z(), m[2].z());
        double y = Math.atan2(-m[0].z(), sy);
        double z = singular ? 0 : Math.atan2(m[0].y(), m[0].x());
        return new Vector(Math.toDegrees(x), Math.toDegrees(y), Math.toDegrees(z));
    }
    private static Vector componentMultiply(Vector vector, double[] signs) {
        return new Vector(vector.x() * signs[0], vector.y() * signs[1], vector.z() * signs[2]);
    }
    private static double component(Vector v, int axis) { return axis == 0 ? v.x() : axis == 1 ? v.y() : v.z(); }
    private static Vector clean(Vector value) { return new Vector(clean(value.x()),clean(value.y()),clean(value.z())); }
    private static double clean(double value) {
        double scaled = value * 10_000;
        return (scaled < 0 ? Math.ceil(scaled - 0.5) : Math.floor(scaled + 0.5)) / 10_000;
    }
    private static String key(Vector value) { return value.x() + "," + value.y() + "," + value.z(); }
    private static double length(Vector value) { return Math.sqrt(dot(value, value)); }

    /** Produces native-space geometry for numerical appearance comparisons. */
    public static List<BakedFace> bake(Cube cube, int width, int height) {
        if (!cube.scale().equals(Vector.ONE) || cube.inflate() != 0) throw new IllegalArgumentException("Baked verification requires unit scale and zero inflate");
        var out = new ArrayList<BakedFace>();
        Vector[] matrix = matrix(cube.rotation());
        for (int i = 0; i < 6; i++) {
            Face face = cube.faces().get(i);
            if (!cube.visible() || !face.visible()) continue;
            List<Vector> positions = positions(cube, i);
            var vertices = new ArrayList<Vertex>();
            for (int j = 0; j < 4; j++) {
                double[] uv = uv(face, j);
                vertices.add(new Vertex(mul(positions.get(j), 1.0 / 16), uv[0] / width, uv[1] / height));
            }
            out.add(new BakedFace(transform(matrix, NORMALS.get(i)), vertices));
        }
        return out;
    }
    public static void verify(List<BakedFace> expected, List<BakedFace> actual, int width, int height) {
        if (expected.size() != actual.size()) throw new IllegalArgumentException("Reconstruction changed face count");
        for (BakedFace face : expected) {
            var candidates = actual.stream().filter(other -> dot(unit(face.normal()), unit(other.normal())) > 0.9999).toList();
            if (candidates.size() != 1) throw new IllegalArgumentException("Reconstruction changed normals");
            boolean[] used = new boolean[4];
            for (Vertex v : face.vertices()) {
                int match = -1;
                for (int j = 0; j < 4; j++) {
                    Vertex other = candidates.getFirst().vertices().get(j);
                    if (!used[j] && distance(v.position(), other.position()) * 16 <= EPS
                            && Math.abs(v.u() - other.u()) * width <= EPS && Math.abs(v.v() - other.v()) * height <= EPS) { match = j; break; }
                }
                if (match < 0) throw new IllegalArgumentException("Reconstruction changed vertices or UV");
                used[match] = true;
            }
        }
    }
    private static Face fitUv(double[] u, double[] v) {
        for (int rotation = 0; rotation < 4; rotation++) {
            double[] a = new double[4], b = new double[4];
            for (int i = 0; i < 4; i++) { a[(i + rotation) % 4] = u[i]; b[(i + rotation) % 4] = v[i]; }
            if (near(a[0], a[3]) && near(a[1], a[2]) && near(b[0], b[1]) && near(b[2], b[3]))
                return new Face(a[1], b[0], a[0] - a[1], b[2] - b[0], rotation * 90, true);
        }
        throw new IllegalArgumentException("Baked UV is not a rectangular face mapping");
    }
    private static double[] uv(Face face, int index) {
        int corner = (index + face.rotation() / 90) % 4;
        return new double[]{face.u() + (corner == 0 || corner == 3 ? face.width() : 0), face.v() + (corner >= 2 ? face.height() : 0)};
    }
    private static List<Vector> positions(Cube cube, int face) {
        Vector origin = cube.origin(), size = cube.size();
        double x0 = -origin.x() - size.x(), x1 = -origin.x(), y0 = origin.y(), y1 = y0 + size.y(), z0 = origin.z(), z1 = z0 + size.z();
        Vector[] corners = {new Vector(x0,y0,z0), new Vector(x0,y0,z1), new Vector(x0,y1,z0), new Vector(x0,y1,z1),
                new Vector(x1,y0,z0), new Vector(x1,y0,z1), new Vector(x1,y1,z0), new Vector(x1,y1,z1)};
        int[][] indices = {{2,6,4,0},{7,3,1,5},{6,7,5,4},{3,2,0,1},{3,7,6,2},{0,4,5,1}};
        Vector pivot = new Vector(-cube.pivot().x(), cube.pivot().y(), cube.pivot().z());
        Vector[] matrix = matrix(cube.rotation());
        var result = new ArrayList<Vector>();
        for (int index : indices[face]) result.add(add(pivot, transform(matrix, sub(corners[index], pivot))));
        return result;
    }
    private static Vector[] matrix(Vector rotation) {
        double x = -Math.toRadians(rotation.x()), y = -Math.toRadians(rotation.y()), z = Math.toRadians(rotation.z());
        double sx=Math.sin(x),cx=Math.cos(x),sy=Math.sin(y),cy=Math.cos(y),sz=Math.sin(z),cz=Math.cos(z);
        return new Vector[]{new Vector(cz*cy,sz*cy,-sy),new Vector(cz*sy*sx-sz*cx,sz*sy*sx+cz*cx,cy*sx),new Vector(cz*sy*cx+sz*sx,sz*sy*cx-cz*sx,cy*cx)};
    }
    private static Vector euler(Vector[] m) {
        double y = Math.asin(Math.clamp(-m[0].z(), -1, 1));
        double x = Math.abs(Math.cos(y)) > 1e-8 ? Math.atan2(m[1].z(), m[2].z()) : 0;
        double z = Math.abs(Math.cos(y)) > 1e-8 ? Math.atan2(m[0].y(), m[0].x()) : Math.atan2(-m[1].x(), m[1].y());
        return new Vector(-Math.toDegrees(x), -Math.toDegrees(y), Math.toDegrees(z));
    }
    private static Vector[] closestFrame(Vector[] axes, List<BakedFace> faces) {
        double score = -Double.MAX_VALUE; Vector[] best = null;
        for (int a=0;a<3;a++) for(int b=0;b<3;b++) if(a!=b) for(int sa : new int[]{-1,1}) for(int sb : new int[]{-1,1}) {
            Vector x=mul(axes[a],sa), y=mul(axes[b],sb), z=cross(x,y);
            double candidate = x.x()+y.y()+z.z();
            Vector[] frame = {x,y,z};
            // Prefer a frame preserving native UV orientation, avoiding gratuitous
            // per-face quarter turns when the source was representable without them.
            for (BakedFace face : faces) {
                int direction = -1;
                for (int i=0;i<6;i++) if(dot(unit(face.normal()),transform(frame,NORMALS.get(i)))>0.9999) {direction=i;break;}
                if(direction<0) continue;
                for(int edge=0;edge<4;edge++) {
                    Vertex p=face.vertices().get(edge),q=face.vertices().get((edge+1)%4);
                    Vector delta=sub(q.position(),p.position());
                    if(dot(delta,delta)<1e-12) continue;
                    if(Math.abs(q.u()-p.u())>1e-8 && Math.abs(q.v()-p.v())<1e-8)
                        candidate+=100*Math.abs(dot(unit(delta),transform(frame,U_AXES.get(direction))));
                    if(Math.abs(q.v()-p.v())>1e-8 && Math.abs(q.u()-p.u())<1e-8)
                        candidate+=100*Math.abs(dot(unit(delta),transform(frame,V_AXES.get(direction))));
                }
            }
            if(candidate>score) { score=candidate; best=new Vector[]{x,y,z}; }
        }
        return best;
    }
    private static void axis(List<Vector> axes, Vector value) {
        if (dot(value,value) < 1e-12) return;
        Vector n=unit(value);
        if(axes.stream().noneMatch(existing -> Math.abs(dot(n,existing)) > 0.9999)) axes.add(n);
    }
    private static boolean near(double a,double b) { return Math.abs(a-b)<=EPS; }
    private static Vector transform(Vector[] m, Vector v) { return add(add(mul(m[0],v.x()),mul(m[1],v.y())),mul(m[2],v.z())); }
    private static Vector add(Vector a,Vector b) { return new Vector(a.x()+b.x(),a.y()+b.y(),a.z()+b.z()); }
    private static Vector sub(Vector a,Vector b) { return new Vector(a.x()-b.x(),a.y()-b.y(),a.z()-b.z()); }
    private static Vector mul(Vector a,double b) { return new Vector(a.x()*b,a.y()*b,a.z()*b); }
    private static double dot(Vector a,Vector b) { return a.x()*b.x()+a.y()*b.y()+a.z()*b.z(); }
    private static Vector cross(Vector a,Vector b) { return new Vector(a.y()*b.z()-a.z()*b.y(),a.z()*b.x()-a.x()*b.z(),a.x()*b.y()-a.y()*b.x()); }
    private static double distance(Vector a,Vector b) { Vector d=sub(a,b);return Math.sqrt(dot(d,d)); }
    private static Vector unit(Vector v) { double length=Math.sqrt(dot(v,v));if(length<1e-12)throw new IllegalArgumentException("Zero baked normal");return mul(v,1/length); }
    private YsmBakedGeometry() {}
}
