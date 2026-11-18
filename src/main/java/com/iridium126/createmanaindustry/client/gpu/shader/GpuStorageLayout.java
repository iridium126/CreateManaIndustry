package com.iridium126.createmanaindustry.client.gpu.shader;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.lwjgl.opengl.GL43;

/** Packs a program's high logical SSBO bindings into its unused hardware slots. */
final class GpuStorageLayout {
    private static final Pattern CONSTANT = Pattern.compile("(?m)^\\h*#\\h*define\\h+(\\w+)\\h+(\\d+)[uU]?\\h*(?://[^\\r\\n]*)?$");
    private static final Pattern BLOCK = Pattern.compile(
            "\\blayout\\s*\\([^)]*?\\bbinding\\s*=\\s*(\\w+)[^)]*\\)\\s*(?:\\w+\\s+)*?buffer\\s+(\\w+)\\s*\\{");

    private final int limit;
    private final Map<String, Integer> declared = new HashMap<>();

    GpuStorageLayout(int limit) { this.limit = limit; }

    String stage(String source) { return rewrite(source, null); }

    String replay(String source, int[] physicalToLogical) { return rewrite(source, physicalToLogical); }

    private String rewrite(String source, int[] physicalToLogical) {
        var names = new HashMap<String, Integer>();
        var constants = CONSTANT.matcher(source);
        while (constants.find()) names.put(constants.group(1), Integer.parseInt(constants.group(2)));
        var blocks = BLOCK.matcher(source);
        var result = new StringBuilder();
        int copied = 0;
        while (blocks.find()) {
            String token = blocks.group(1);
            Integer logical = names.get(token);
            if (logical == null && Character.isDigit(token.charAt(0)))
                logical = Integer.parseInt(token.replaceFirst("[uU]$", ""));
            if (logical == null || logical < limit) continue;
            int binding = 0;
            if (physicalToLogical == null) {
                Integer previous = declared.putIfAbsent(blocks.group(2), logical);
                if (previous != null && !previous.equals(logical))
                    throw new IllegalStateException("Conflicting logical SSBO binding for " + blocks.group(2));
            } else {
                for (int i = 0; i < physicalToLogical.length; i++) {
                    if (physicalToLogical[i] == logical) { binding = i; break; }
                }
            }
            result.append(source, copied, blocks.start(1)).append(binding);
            copied = blocks.end(1);
        }
        return copied == 0 ? source : result.append(source, copied, source.length()).toString();
    }

    int[] install(int program, String label) {
        if (declared.isEmpty()) return null;
        int count = GL43.glGetProgramInterfacei(program, GL43.GL_SHADER_STORAGE_BLOCK, GL43.GL_ACTIVE_RESOURCES);
        int[] overrides = new int[count], property = {GL43.GL_BUFFER_BINDING}, result = new int[1];
        int[] physicalToLogical = new int[limit];
        Arrays.fill(overrides, -1);
        Arrays.fill(physicalToLogical, -1);
        for (int i = 0; i < count; i++) {
            Integer logical = declared.get(GL43.glGetProgramResourceName(program, GL43.GL_SHADER_STORAGE_BLOCK, i));
            if (logical != null) overrides[i] = logical;
            else {
                GL43.glGetProgramResourceiv(program, GL43.GL_SHADER_STORAGE_BLOCK, i, property, null, result);
                physicalToLogical[result[0]] = result[0];
            }
        }
        boolean remapped = false;
        for (int i = 0; i < count; i++) {
            int logical = overrides[i];
            if (logical < 0) continue;
            int slot = -1;
            for (int j = 0; j < limit; j++) {
                if (physicalToLogical[j] == logical) { slot = j; break; }
                if (slot < 0 && physicalToLogical[j] < 0) slot = j;
            }
            if (slot < 0) throw new IllegalStateException(label + ": SSBO bindings exceed hardware limit " + limit);
            physicalToLogical[slot] = logical;
            GL43.glShaderStorageBlockBinding(program, i, slot);
            remapped = true;
        }
        if (!remapped) return null;
        for (int i = 0; i < limit; i++) if (physicalToLogical[i] < limit) physicalToLogical[i] = -1;
        return physicalToLogical;
    }
}
