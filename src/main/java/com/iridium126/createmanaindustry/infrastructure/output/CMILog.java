package com.iridium126.createmanaindustry.infrastructure.output;

/**
 * Compile-time log prefixes and the single log-format contract for the mod.
 *
 * <p>Every runtime log line uses {@code [CMI/<module>] <message>}:
 * <pre>
 * [CMI/particles] Sprite atlas created: 512x512
 * [CMI/allvr] cube 1,2,3: header invalid, continuing on header 2
 * [CMI/hexjit] Arithmetic codegen failed; falling back to the interpreter
 * </pre>
 *
 * <p>Level semantics (keep them consistent across modules):
 * <ul>
 *   <li>{@code info} — a one-off state change or startup fact worth reading in a log;</li>
 *   <li>{@code warn} — a recoverable degradation or a rejected request;</li>
 *   <li>{@code error} — an exception or a failed operation that needs attention;</li>
 *   <li>{@code debug} — diagnostic detail, disabled by default.</li>
 * </ul>
 *
 * <p>Always use slf4j {@code {}} placeholders and pass a {@code Throwable} as the
 * last argument so its stack trace survives. Never build messages with
 * {@code String.format}, string concatenation of runtime values, or legacy
 * {@code colour} codes; never add a second hand-written prefix on top of
 * these constants.
 *
 * <p>The values are {@code static final} compile-time constants, so
 * {@code CMILog.PARTICLES + "sprite atlas ready"} folds into one string and
 * costs nothing at runtime — important for the per-frame log sites.
 *
 * <p>The bootstrap source set runs before the mod classloader is ready and
 * cannot reference this class; it mirrors the same layout locally.
 */
public final class CMILog {

    public static final String PARTICLES = "[CMI/particles] ";
    public static final String PACKAGES = "[CMI/packages] ";
    public static final String FRAMES = "[CMI/frames] ";
    public static final String CAPTURE = "[CMI/capture] ";
    public static final String PROFILE = "[CMI/profile] ";
    public static final String SHADERPACK = "[CMI/shaderpack] ";
    public static final String SHADOW = "[CMI/shadow] ";
    public static final String EFFECTS = "[CMI/effects] ";
    public static final String STORM = "[CMI/storm] ";
    public static final String RIFT = "[CMI/rift] ";
    public static final String HEX = "[CMI/hex] ";
    public static final String HEXJIT = "[CMI/hexjit] ";
    public static final String ALLVR = "[CMI/allvr] ";
    public static final String YSM = "[CMI/ysm] ";
    public static final String TRICKSTER = "[CMI/trickster] ";
    public static final String DISPLAY = "[CMI/display] ";
    public static final String CONFIG = "[CMI/config] ";
    public static final String MIXIN = "[CMI/mixin] ";
    public static final String BOOTSTRAP = "[CMI/bootstrap] ";

    private CMILog() {
    }
}
