package com.iridium126.createmanaindustry.compat.trickster.casting;

public interface ExecutorChainAccess {
    default void cmi$releaseExecution() { CastChains.release(cmi$executorChain(), this); }
    CastChain cmi$executorChain();
    void cmi$executorChain(CastChain chain);
}
