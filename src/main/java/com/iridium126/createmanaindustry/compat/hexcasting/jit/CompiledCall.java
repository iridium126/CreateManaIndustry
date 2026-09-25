package com.iridium126.createmanaindustry.compat.hexcasting.jit;

/** A single original operation. Arguments and results are never retained by generated code. */
@FunctionalInterface
public interface CompiledCall {
    Object call(Object receiver, Object environment, Object image, Object continuation, Object iota) throws Throwable;
}
