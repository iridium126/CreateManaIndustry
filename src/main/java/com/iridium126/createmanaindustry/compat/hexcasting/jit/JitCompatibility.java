package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.LoggerFactory;

/** Bootstrap-safe: must not reference Hexcasting classes or initialise registries. */
public final class JitCompatibility {
    private static final Set<String> VERIFIED = ConcurrentHashMap.newKeySet();
    private static final Set<String> STAFF_CALLBACKS = ConcurrentHashMap.newKeySet();
    private static volatile String staffCallbackFailure;
    private static volatile boolean staffCallbacksReady;
    private static volatile boolean statLookupVerified;
    private static volatile boolean quotedVectorsVerified;
    private static volatile boolean rangeAttributesVerified;
    private static volatile boolean pureQuotesVerified;
    private static final Set<String> SOUND_EMISSION = ConcurrentHashMap.newKeySet();
    private static volatile boolean soundElisionDisabled;
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
    private static volatile String actionResourceKeyCacheFailure;
    private static volatile boolean actionResourceKeyCacheTargetVerified;
    private static volatile String actionTagMembershipFailure;
    private static volatile boolean actionTagMembershipTargetVerified;
    private static volatile String actionPrecheckFailure;
    private static volatile boolean actionPrecheckTargetVerified;
    private static volatile boolean mediaPoolTargetVerified;
    private static volatile boolean personalMediaBatchTargetVerified;
    private static volatile boolean directPreflightCastingEnvironmentVerified;
    private static volatile boolean directPreflightPlayerEnvironmentVerified;
    private static volatile boolean directPreflightStaffEnvironmentVerified;
    private static volatile String directPreflightFailure;
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
    public static void actionResourceKeyCacheTargetVerified() { actionResourceKeyCacheTargetVerified = true; }
    public static void actionTagMembershipTargetVerified() { actionTagMembershipTargetVerified = true; }
    public static void actionPrecheckTargetVerified() { actionPrecheckTargetVerified = true; }
    public static void disableActionResourceKeyCache(String reason) {
        if (actionResourceKeyCacheFailure == null)
            LoggerFactory.getLogger("CMI Hex JIT").warn("Hex JIT action ResourceKey cache disabled: {}", reason);
        actionResourceKeyCacheFailure = reason;
    }
    public static void disableActionTagMembership(String reason) {
        if (actionTagMembershipFailure == null)
            LoggerFactory.getLogger("CMI Hex JIT").warn("Hex JIT action tag membership cache disabled: {}", reason);
        actionTagMembershipFailure = reason;
    }
    public static void disableActionPrechecks(String reason) {
        if (actionPrecheckFailure == null)
            LoggerFactory.getLogger("CMI Hex JIT").warn("Hex JIT action precheck cache disabled: {}", reason);
        actionPrecheckFailure = reason;
    }
    public static void mediaPoolTargetVerified() { mediaPoolTargetVerified = true; }
    public static void personalMediaBatchTargetVerified() { personalMediaBatchTargetVerified = true; }
    public static void directPreflightCastingEnvironmentVerified() { directPreflightCastingEnvironmentVerified = true; }
    public static void directPreflightPlayerEnvironmentVerified() { directPreflightPlayerEnvironmentVerified = true; }
    public static void directPreflightStaffEnvironmentVerified() { directPreflightStaffEnvironmentVerified = true; }
    public static void disableDirectMediaPreflight(String reason) {
        if (directPreflightFailure == null)
            LoggerFactory.getLogger("CMI Hex JIT").warn("Hex JIT direct Tick media preflight disabled: {}", reason);
        directPreflightFailure = reason;
    }
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
    public static void verifiedPureQuotes() { pureQuotesVerified = true; }
    public static void disablePureQuotes() { pureQuotesVerified = false; }
    public static boolean pureQuotesReady() { return coreReady && pureQuotesVerified && quotedVectorReady() && rangeAttributesReady(); }
    public static void verifiedSoundEmission(String name) { SOUND_EMISSION.add(name); }
    public static void disableSoundElision() { soundElisionDisabled = true; }
    public static boolean soundElisionReady() { return coreReady && !soundElisionDisabled && SOUND_EMISSION.size() == 3; }
    public static void verifiedRangeAttributes() { rangeAttributesVerified = true; }
    public static void disableRangeAttributes() { rangeAttributesVerified = false; }
    public static boolean rangeAttributesReady() { return coreReady && rangeAttributesVerified; }
    public static void verifiedQuotedVectors() { quotedVectorsVerified = true; }
    public static void disableQuotedVectors() { quotedVectorsVerified = false; }
    public static boolean quotedVectorReady() { return coreReady && quotedVectorsVerified; }
    public static void verifiedStatLookup() { statLookupVerified = true; }
    public static void disableStatLookup() { statLookupVerified = false; }
    public static boolean statLookupReady() { return coreReady && statLookupVerified; }
    public static void verifiedStaffCallback(String name) {
        STAFF_CALLBACKS.add(name);
        staffCallbacksReady = staffCallbackFailure == null && STAFF_CALLBACKS.size() == 3;
    }
    public static void disableStaffCallbacks(String reason) { staffCallbackFailure = reason; staffCallbacksReady = false; }
    public static boolean fastStaffCallbacksReady() {
        return coreReady && staffCallbacksReady;
    }
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
    public static boolean actionResourceKeyCacheReady() {
        return actionResourceKeyCacheTargetVerified && actionResourceKeyCacheFailure == null;
    }
    public static boolean actionTagMembershipReady() {
        return actionTagMembershipTargetVerified && actionTagMembershipFailure == null;
    }
    public static boolean actionPrechecksReady() {
        return coreReady && actionPrecheckTargetVerified && actionPrecheckFailure == null;
    }
    public static boolean mediaPoolTargetReady() { return mediaPoolTargetVerified; }
    public static boolean personalMediaBatchTargetReady() { return personalMediaBatchTargetVerified; }
    public static boolean directMediaPreflightReady() {
        return directPreflightFailure == null && directPreflightCastingEnvironmentVerified
                && directPreflightPlayerEnvironmentVerified && directPreflightStaffEnvironmentVerified;
    }
    public static String status() {
        String core = failure != null ? failure : ready() ? "verified pre-53" : "waiting for target verification (" + VERIFIED.size() + "/7)";
        return core + ", staffCallbacks=" + (fastStaffCallbacksReady() ? "verified" : staffCallbackFailure != null
                ? staffCallbackFailure : "waiting")
                + (motionBatchingReady() ? ", motionBatch=verified" : motionFailure != null
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
                ? ", numberLiteral=disabled (" + numberLiteralFailure + ")" : ", numberLiteral=unverified")
                + (actionResourceKeyCacheReady() ? ", actionResourceKeyCache=verified" : actionResourceKeyCacheFailure != null
                ? ", actionResourceKeyCache=disabled (" + actionResourceKeyCacheFailure + ")"
                : ", actionResourceKeyCache=unverified")
                + (actionTagMembershipReady() ? ", actionTagMembership=verified" : actionTagMembershipFailure != null
                ? ", actionTagMembership=disabled (" + actionTagMembershipFailure + ")"
                : ", actionTagMembership=unverified")
                + (actionPrechecksReady() ? ", actionPrechecks=verified" : actionPrecheckFailure != null
                ? ", actionPrechecks=disabled (" + actionPrecheckFailure + ")"
                : ", actionPrechecks=unverified");
    }
    private static void refreshReadiness() {
        coreReady = failure == null && verifierInstalled && VERIFIED.size() == 7;
        motionReady = coreReady && motionTargetVerified && motionFailure == null;
    }
}
