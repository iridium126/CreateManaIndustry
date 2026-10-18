package com.iridium126.createmanaindustry.compat.hextrick.execution;

/** Native roots retain their mixed-language budget across ticks and native save codecs. */
public interface ExecutorBudgetAccess {
    String cmi$encodeBudget();
    void cmi$restoreBudget(String encoded);
    boolean cmi$hasMixedBudget();
}
