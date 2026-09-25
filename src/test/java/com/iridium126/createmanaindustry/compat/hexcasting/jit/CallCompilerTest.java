package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;
import static org.junit.jupiter.api.Assertions.*;

class CallCompilerTest {
    public interface Extension { Object apply(Object env, Object image, Object continuation, Object iota); }
    public static class Fixture implements Extension {
        int calls;
        Object[] received;
        @Override public Object apply(Object env, Object image, Object continuation, Object iota) {
            calls++; received = new Object[] {env, image, continuation, iota}; return image;
        }
        public Object fail(Object env) { calls++; throw FAILURE; }
        public Object noArgs() { calls++; return this; }
    }
    static final IllegalStateException FAILURE = new IllegalStateException("extension failure");
    static CallCompiler.Description fixture() {
        return new CallCompiler.Description(Type.getInternalName(Extension.class), "apply",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
    }

    @Test void generatedInterfaceCallPreservesIdentityAndDoesNotCaptureReceiver() throws Throwable {
        CompiledCall code = CallCompiler.link(CallCompiler.compile(fixture()));
        Fixture a = new Fixture(), b = new Fixture();
        Object env = new Object(), image = new Object(), continuation = new Object(), iota = new Object();
        assertSame(image, code.call(a, env, image, continuation, iota));
        assertArrayEquals(new Object[] {env, image, continuation, iota}, a.received);
        assertSame(image, code.call(b, env, image, continuation, iota));
        assertEquals(1, a.calls); assertEquals(1, b.calls);
        assertTrue(code.getClass().isHidden());
        assertEquals(0, code.getClass().getDeclaredFields().length);
    }

    @Test void thrownExtensionExceptionIsNotWrappedOrReplayed() throws Exception {
        var description = new CallCompiler.Description(Type.getInternalName(Fixture.class), "fail",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        CompiledCall code = CallCompiler.link(CallCompiler.compile(description));
        Fixture fixture = new Fixture();
        assertSame(FAILURE, assertThrows(IllegalStateException.class, () -> code.call(fixture, null, null, null, null)));
        assertEquals(1, fixture.calls);
    }

    @Test void zeroArgumentVirtualCallWorks() throws Throwable {
        var description = new CallCompiler.Description(Type.getInternalName(Fixture.class), "noArgs", "()Ljava/lang/Object;", false);
        Fixture fixture = new Fixture();
        assertSame(fixture, CallCompiler.link(CallCompiler.compile(description)).call(fixture, null, null, null, null));
    }

    @Test void rejectsUnsafeDescriptionShapesAndInvalidBytecode() {
        assertThrows(IllegalArgumentException.class, () -> new CallCompiler.Description("A", "b", "(I)Ljava/lang/Object;", false));
        assertThrows(IllegalArgumentException.class, () -> new CallCompiler.Description("A", "b", "()V", false));
        assertThrows(ClassFormatError.class, () -> CallCompiler.link(new byte[] {1, 2, 3}));
    }
}
