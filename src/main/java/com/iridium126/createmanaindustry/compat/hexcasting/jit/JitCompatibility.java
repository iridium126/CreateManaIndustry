package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.LoggerFactory;

/** Bootstrap-safe: must not reference Hexcasting classes or initialise registries. */
public final class JitCompatibility {
    private static final Set<String> VERIFIED = ConcurrentHashMap.newKeySet();
    private static volatile String failure;
    private static volatile String motionFailure;
    private static volatile boolean motionTargetVerified;
    private static volatile String particleCoalescingFailure;
    private static volatile String fastActionFailure;
    private static volatile boolean fastActionTargetVerified;
    private static volatile String stackValidationFailure;
    private static volatile boolean stackValidationTargetVerified;
    private static volatile String frameTailFailure;
    private static volatile boolean treeListTargetVerified;
    private static volatile String specialHandlerMathFailure;
    private static volatile boolean specialHandlerMathTargetVerified;
    private static volatile String specialHandlerLookupFailure;
    private static volatile boolean specialHandlerLookupTargetVerified;
    private static volatile String numberLiteralFailure;
    private static volatile boolean numberLiteralTargetVerified;
    private static volatile boolean mediaPoolTargetVerified;
    private static volatile boolean verifierInstalled;
    private static volatile boolean coreReady;
    private static volatile boolean motionReady;
    private JitCompatibility() {}
    public static void verifierInstalled() { verifierInstalled = true; refreshReadiness(); }
    public static void verified(String name) { VERIFIED.add(name); refreshReadiness(); }
    public static void motionTargetVerified() { motionTargetVerified = true; refreshReadiness(); }
    public static void disableMotion(String reason) {
        if (motionFailure == null) LoggerFactory.getLogger("CMI Hex JIT").warn("Hex JIT Add Motion batching disabled: {}", reason);
        motionFailure = reason;
        refreshReadiness();
    }
    public static void disableParticleCoalescing(String reason) {
        if (particleCoalescingFailure == null)
            LoggerFactory.getLogger("CMI Hex JIT").warn("Hex JIT particle coalescing disabled: {}", reason);
        particleCoalescingFailure = reason;
    }
    public static void fastActionTargetVerified() { fastActionTargetVerified = true; }
    public static void disableFastAction(String reason) {
        if (fastActionFailure == null) LoggerFactory.getLogger("CMI Hex JIT").warn("Hex JIT Add Motion call specialization disabled: {}", reason);
        fastActionFailure = reason;
    }
    public static void stackValidationTargetVerified() { stackValidationTargetVerified = true; }
    public static void disableStackValidation(String reason) {
        if (stackValidationFailure == null) LoggerFactory.getLogger("CMI Hex JIT").warn("Hex JIT stack validation specialization disabled: {}", reason);
        stackValidationFailure = reason;
    }
    public static void treeListTargetVerified() { treeListTargetVerified = true; }
    public static void disableFrameTailCache(String reason) {
        if (frameTailFailure == null) LoggerFactory.getLogger("CMI Hex JIT").warn("Hex JIT FrameEvaluate tail reuse disabled: {}", reason);
        frameTailFailure = reason;
    }
    public static void specialHandlerMathTargetVerified() { specialHandlerMathTargetVerified = true; }
    public static void disableSpecialHandlerMath(String reason) {
        if (specialHandlerMathFailure == null) LoggerFactory.getLogger("CMI Hex JIT").warn("Hex JIT special-handler math fast path disabled: {}", reason);
        specialHandlerMathFailure = reason;
    }
    public static void specialHandlerLookupTargetVerified() { specialHandlerLookupTargetVerified = true; }
    public static void disableSpecialHandlerLookup(String reason) {
        if (specialHandlerLookupFailure == null) LoggerFactory.getLogger("CMI Hex JIT").warn("Hex JIT special-handler lookup fast path disabled: {}", reason);
        specialHandlerLookupFailure = reason;
    }
    public static void numberLiteralTargetVerified() { numberLiteralTargetVerified = true; }
    public static void mediaPoolTargetVerified() { mediaPoolTargetVerified = true; }
    public static void disableNumberLiteral(String reason) {
        if (numberLiteralFailure == null) LoggerFactory.getLogger("CMI Hex JIT").warn("Hex JIT number-literal fast path disabled: {}", reason);
        numberLiteralFailure = reason;
    }
    public static void disable(String reason) {
        if (failure == null) LoggerFactory.getLogger("CMI Hex JIT").warn("Hex JIT disabled: {}", reason);
        failure = reason;
        refreshReadiness();
    }
    public static boolean ready() { return coreReady; }
    public static boolean motionBatchingReady() { return motionReady; }
    public static boolean particleCoalescingReady() { return coreReady && particleCoalescingFailure == null; }
    public static boolean fastAddMotionReady() { return coreReady && fastActionTargetVerified && fastActionFailure == null; }
    public static boolean fastStackValidationReady() {
        return coreReady && stackValidationTargetVerified && treeListTargetVerified && stackValidationFailure == null;
    }
    public static boolean frameTailCacheReady() { return coreReady && treeListTargetVerified && frameTailFailure == null; }
    public static boolean specialHandlerMathReady() {
        return specialHandlerMathTargetVerified && specialHandlerMathFailure == null;
    }
    public static boolean specialHandlerLookupReady() {
        return coreReady && specialHandlerLookupTargetVerified && specialHandlerLookupFailure == null;
    }
    public static boolean fastNumberLiteralReady() {
        return coreReady && numberLiteralTargetVerified && numberLiteralFailure == null;
    }
    public static boolean mediaPoolTargetReady() { return mediaPoolTargetVerified; }
    public static String status() {
        String core = failure != null ? failure : ready() ? "verified pre-53" : "waiting for target verification (" + VERIFIED.size() + "/7)";
        return core + (motionBatchingReady() ? ", motionBatch=verified" : motionFailure != null
                ? ", motionBatch=disabled (" + motionFailure + ")" : ", motionBatch=unverified")
                + (particleCoalescingReady() ? ", particleCoalescing=verified" : particleCoalescingFailure != null
                ? ", particleCoalescing=disabled (" + particleCoalescingFailure + ")" : ", particleCoalescing=unverified")
                + (fastAddMotionReady() ? ", addMotionArgs=verified" : fastActionFailure != null
                ? ", addMotionArgs=disabled (" + fastActionFailure + ")" : ", addMotionArgs=unverified")
                + (fastStackValidationReady() ? ", stackValidation=verified" : stackValidationFailure != null
                ? ", stackValidation=disabled (" + stackValidationFailure + ")" : ", stackValidation=unverified")
                + (frameTailCacheReady() ? ", frameTail=verified" : frameTailFailure != null
                ? ", frameTail=disabled (" + frameTailFailure + ")" : ", frameTail=unverified")
                + (specialHandlerMathReady() ? ", specialHandlerMath=verified" : specialHandlerMathFailure != null
                ? ", specialHandlerMath=disabled (" + specialHandlerMathFailure + ")" : ", specialHandlerMath=unverified")
                + (specialHandlerLookupReady() ? ", specialHandlerLookup=verified" : specialHandlerLookupFailure != null
                ? ", specialHandlerLookup=disabled (" + specialHandlerLookupFailure + ")" : ", specialHandlerLookup=unverified")
                + (fastNumberLiteralReady() ? ", numberLiteral=verified" : numberLiteralFailure != null
                ? ", numberLiteral=disabled (" + numberLiteralFailure + ")" : ", numberLiteral=unverified");
    }
    private static void refreshReadiness() {
        coreReady = failure == null && verifierInstalled && VERIFIED.size() == 7;
        motionReady = coreReady && motionTargetVerified && motionFailure == null;
    }
}
