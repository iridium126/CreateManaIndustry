package com.iridium126.createmanaindustry.compat.trickster;

import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import static org.junit.jupiter.api.Assertions.*;

class TricksterMixinTargetsTest {
    @Test
    void manaPrecheckRedirectTargetsCurrentTricksterApi() throws Exception {
        ClassNode mixin = read("com/iridium126/createmanaindustry/mixin/trickster/SpellContextMixin");
        ClassNode target = read("dev/enjarai/trickster/spell/SpellContext");
        var handler = mixin.methods.stream().filter(m -> m.name.equals("createmanaindustry$overrideCheckSource"))
                .findFirst().orElseThrow();
        var redirect = handler.visibleAnnotations.stream().filter(a -> a.desc.endsWith("/Redirect;"))
                .findFirst().orElseThrow();
        var selectors = (List<?>) redirect.values.get(redirect.values.indexOf("method") + 1);
        assertEquals(2, selectors.size());
        for (Object selector : selectors) {
            assertTrue(target.methods.stream().anyMatch(m -> (m.name + m.desc).equals(selector)),
                    "Missing Trickster method: " + selector);
        }
    }

    private static ClassNode read(String name) throws Exception {
        try (InputStream input = TricksterMixinTargetsTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_CODE);
            return node;
        }
    }
}
