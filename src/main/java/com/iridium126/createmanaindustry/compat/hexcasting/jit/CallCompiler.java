package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import java.lang.invoke.MethodHandles;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Compiles only immutable method descriptions; the worker never loads game classes. */
public final class CallCompiler implements Opcodes {
    private CallCompiler() {}

    public record Description(String owner, String method, String descriptor, boolean isInterface) {
        public Description {
            Type[] args = Type.getArgumentTypes(descriptor);
            if (args.length > 4 || Type.getReturnType(descriptor).getSort() != Type.OBJECT)
                throw new IllegalArgumentException("Expected up to four reference arguments and a reference result");
            for (Type arg : args) if (arg.getSort() != Type.OBJECT && arg.getSort() != Type.ARRAY)
                throw new IllegalArgumentException("Only reference arguments are supported");
        }
    }

    public static byte[] compile(Description description) {
        String name = Type.getInternalName(CallCompiler.class) + "$Step";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, name, null, "java/lang/Object",
                new String[] {Type.getInternalName(CompiledCall.class)});
        MethodVisitor init = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor call = writer.visitMethod(ACC_PUBLIC | ACC_FINAL, "call",
                Type.getMethodDescriptor(Type.getType(Object.class), Type.getType(Object.class),
                        Type.getType(Object.class), Type.getType(Object.class), Type.getType(Object.class),
                        Type.getType(Object.class)), null, new String[] {"java/lang/Throwable"});
        call.visitCode();
        call.visitVarInsn(ALOAD, 1);
        call.visitTypeInsn(CHECKCAST, description.owner());
        Type[] arguments = Type.getArgumentTypes(description.descriptor());
        for (int i = 0; i < arguments.length; i++) {
            call.visitVarInsn(ALOAD, i + 2);
            call.visitTypeInsn(CHECKCAST, arguments[i].getInternalName());
        }
        call.visitMethodInsn(description.isInterface() ? INVOKEINTERFACE : INVOKEVIRTUAL,
                description.owner(), description.method(), description.descriptor(), description.isInterface());
        call.visitInsn(ARETURN);
        call.visitMaxs(0, 0);
        call.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Called by the casting thread, not the compiler. No STRONG option: evicted classes can unload. */
    public static CompiledCall link(byte[] bytecode) throws ReflectiveOperationException {
        Class<?> generated = MethodHandles.lookup().defineHiddenClass(bytecode, true).lookupClass();
        return (CompiledCall) generated.getConstructor().newInstance();
    }
}
