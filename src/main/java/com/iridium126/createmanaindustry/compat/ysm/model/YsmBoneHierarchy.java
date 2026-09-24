package com.iridium126.createmanaindustry.compat.ysm.model;

import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Group;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Shared iterative post-order assembly for bone graphs from plaintext and compiled YSM models. */
final class YsmBoneHierarchy {
    interface Node {
        List<String> childNames();
        Group build(List<Group> children);
    }

    private static final class Work {
        private final Node node;
        private final List<Group> children = new ArrayList<>();
        private int nextChild;

        private Work(Node node) { this.node = node; }
    }

    static List<Group> build(Map<String, ?> bones, Map<String, List<String>> children,
            Function<String, Node> createNode) {
        for (String parent : children.keySet())
            if (!parent.isEmpty() && !bones.containsKey(parent)) throw new IllegalArgumentException("Missing parent bone");

        Set<String> visited = new HashSet<>();
        List<Group> roots = new ArrayList<>();
        Deque<Work> pending = new ArrayDeque<>();
        for (String name : children.getOrDefault("", List.of())) {
            if (!visited.add(name)) throw new IllegalArgumentException("Cyclic bone hierarchy");
            pending.push(new Work(createNode.apply(name)));
            while (!pending.isEmpty()) {
                Work work = pending.peek();
                List<String> childNames = work.node.childNames();
                if (work.nextChild < childNames.size()) {
                    String child = childNames.get(work.nextChild++);
                    if (!visited.add(child)) throw new IllegalArgumentException("Cyclic bone hierarchy");
                    pending.push(new Work(createNode.apply(child)));
                    continue;
                }

                Group completed = work.node.build(List.copyOf(work.children));
                pending.pop();
                if (pending.isEmpty()) roots.add(completed);
                else pending.peek().children.add(completed);
            }
        }
        if (visited.size() != bones.size()) throw new IllegalArgumentException("Cyclic bone hierarchy");
        return List.copyOf(roots);
    }

    private YsmBoneHierarchy() {}
}
