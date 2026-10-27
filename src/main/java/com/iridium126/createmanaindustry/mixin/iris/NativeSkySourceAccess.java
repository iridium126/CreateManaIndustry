package com.iridium126.createmanaindustry.mixin.iris;

import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.properties.ProgramDirectives;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Preserve parsed directives verbatim rather than reparsing a synthetic properties file. */
@Mixin(value=ProgramSource.class,remap=false)
public interface NativeSkySourceAccess {
    @Invoker("<init>")
    static ProgramSource cmi$copy(String name,String vertex,String geometry,String tessControl,String tessEval,String fragment,ProgramDirectives directives,ProgramSet parent) {
        throw new AssertionError("Iris source constructor mixin not applied");
    }
}
