package com.iridium126.createmanaindustry.infrastructure.output;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Single source of truth for player-visible CMI command and chat output.
 *
 * <p>Structured layout, evolved from the old single-line
 * {@code /cmi particle status} {@code key=value} rendering:
 * <pre>
 * [CMI/particles] INFO  Particle engine status
 *     live              : 2048 / 262144
 *     streams           : 3
 *     emission          : 100%
 *     frame budget      : 8.0 ms
 * </pre>
 *
 * <p>Rules:
 * <ul>
 *   <li>Heading: {@code [CMI/<module>] <OUTCOME>  <summary>}. The outcome badge
 *       is coloured by meaning — OK green, FAIL red, WARN yellow, INFO grey.</li>
 *   <li>Fields: four-space indent, label left-padded to a fixed width, then
 *       {@code " : "} and the value highlighted in aqua so numbers stand out.</li>
 *   <li>Multi-line reports are sent as one component with a heading, grouped
 *       by section, instead of one chat message per line.</li>
 *   <li>FAIL goes through {@code sendFailure}; every other outcome goes through
 *       {@code sendSuccess} with a lazy supplier and no broadcast to ops.</li>
 *   <li>All text is English and hard-coded; use {@code ChatFormatting}, never
 *       {@code §} codes. Existing translatable messages keep their language
 *       keys and are only wrapped with {@link #prefixed}.</li>
 * </ul>
 *
 * <p>Command feedback is a cold path, but the heading is built inside the
 * {@code sendSuccess} supplier so nothing is allocated unless the message is
 * actually delivered.
 */
public final class CMICommandOutput {

    public static final String PARTICLES = "particles";
    public static final String PACKAGES = "packages";
    public static final String PROFILE = "profile";
    public static final String SHADERPACK = "shaderpack";
    public static final String STORM = "storm";
    public static final String RIFT = "rift";
    public static final String HEXJIT = "hexjit";
    public static final String HEX = "hex";
    public static final String ALLVR = "allvr";
    public static final String YSM = "ysm";
    public static final String TRICKSTER = "trickster";
    public static final String KINETICS = "kinetics";

    /** Result badge of a heading line. FAIL also switches to sendFailure. */
    public enum Outcome {
        OK("OK", ChatFormatting.GREEN),
        FAIL("FAIL", ChatFormatting.RED),
        WARN("WARN", ChatFormatting.YELLOW),
        INFO("INFO", ChatFormatting.GRAY);

        private final String label;
        private final ChatFormatting color;

        Outcome(String label, ChatFormatting color) {
            this.label = label;
            this.color = color;
        }
    }

    private static final int LABEL_WIDTH = 18;
    private static final Pattern SEGMENTS = Pattern.compile("  \\|  ");
    private static final Pattern KEY_VALUE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*=[^\\s]+");

    private CMICommandOutput() {
    }

    /** {@code [CMI/<module>] <OUTCOME>  <summary>} with semantic colours. */
    public static MutableComponent heading(String module, Outcome outcome, String summary) {
        return Component.empty()
                .append(Component.literal("[CMI/" + module + "] ").withStyle(ChatFormatting.DARK_AQUA))
                .append(Component.literal(outcome.label).withStyle(outcome.color, ChatFormatting.BOLD))
                .append(Component.literal("  " + summary).withStyle(ChatFormatting.WHITE));
    }

    /** One aligned, highlighted {@code label : value} line. */
    public static MutableComponent field(Object label, Object value) {
        String name = Objects.toString(label, "");
        return Component.empty()
                .append(Component.literal("    " + pad(name) + " : ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Objects.toString(value, "n/a")).withStyle(ChatFormatting.AQUA));
    }

    /** Heading plus aligned fields, sent as one message. Pairs are label, value, label, value, ... */
    public static MutableComponent report(String module, String title, Object... labelValuePairs) {
        MutableComponent out = heading(module, Outcome.INFO, title);
        for (int i = 0; i + 1 < labelValuePairs.length; i += 2) {
            out.append(Component.literal("\n"))
                    .append(field(labelValuePairs[i], labelValuePairs[i + 1]));
        }
        return out;
    }

    /**
     * Wraps pre-formatted plain-text report lines under one uniform heading.
     * Every line uses the same four-space base indent plus its own relative
     * indent; {@code "  |  "} separates independent {@code label : value}
     * segments so every value is highlighted on its own, and plain
     * {@code key=value} tokens get their values highlighted as well.
     */
    public static MutableComponent reportLines(String module, String title, List<String> lines) {
        MutableComponent out = heading(module, Outcome.INFO, title);
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String text = line.stripTrailing();
            int indent = 0;
            while (indent < text.length() && text.charAt(indent) == ' ') {
                indent++;
            }
            out.append(Component.literal("\n    " + " ".repeat(indent)));
            String[] segments = SEGMENTS.split(text.substring(indent), -1);
            for (int i = 0; i < segments.length; i++) {
                if (i > 0) {
                    out.append(Component.literal("  |  ").withStyle(ChatFormatting.DARK_GRAY));
                }
                appendSegment(out, segments[i]);
            }
        }
        return out;
    }

    /** Renders one segment as a grey label plus an aqua value. */
    private static void appendSegment(MutableComponent out, String segment) {
        int separator = segment.indexOf(" : ");
        if (separator >= 0) {
            out.append(Component.literal(segment.substring(0, separator + 3)).withStyle(ChatFormatting.GRAY));
            out.append(Component.literal(segment.substring(separator + 3)).withStyle(ChatFormatting.AQUA));
            return;
        }
        Matcher matcher = KEY_VALUE.matcher(segment);
        int cursor = 0;
        while (matcher.find()) {
            int valueStart = matcher.start() + matcher.group().indexOf('=') + 1;
            out.append(Component.literal(segment.substring(cursor, valueStart)).withStyle(ChatFormatting.GRAY));
            out.append(Component.literal(segment.substring(valueStart, matcher.end())).withStyle(ChatFormatting.AQUA));
            cursor = matcher.end();
        }
        if (cursor < segment.length()) {
            out.append(Component.literal(segment.substring(cursor)).withStyle(ChatFormatting.GRAY));
        }
    }

    /** Uniform brand + badge in front of an existing translatable component. */
    public static MutableComponent prefixed(String module, Outcome outcome, Component message) {
        return Component.empty()
                .append(Component.literal("[CMI/" + module + "] ").withStyle(ChatFormatting.DARK_AQUA))
                .append(Component.literal(outcome.label + "  ").withStyle(outcome.color))
                .append(message);
    }

    /** Single-line feedback; FAIL is delivered through sendFailure. */
    public static void send(CommandSourceStack src, String module, Outcome outcome, String summary) {
        if (outcome == Outcome.FAIL) {
            src.sendFailure(heading(module, outcome, summary));
        } else {
            src.sendSuccess(() -> heading(module, outcome, summary), false);
        }
    }

    /** Multi-line feedback; FAIL is delivered through sendFailure. */
    public static void send(CommandSourceStack src, Outcome outcome, MutableComponent body) {
        if (outcome == Outcome.FAIL) {
            src.sendFailure(body);
        } else {
            src.sendSuccess(() -> body, false);
        }
    }

    /** Structured key/value status report. */
    public static void sendReport(CommandSourceStack src, String module, String title, Object... labelValuePairs) {
        src.sendSuccess(() -> report(module, title, labelValuePairs), false);
    }

    /** Structured report built from plain-text lines. */
    public static void sendReportLines(CommandSourceStack src, String module, String title, List<String> lines) {
        src.sendSuccess(() -> reportLines(module, title, lines), false);
    }

    private static String pad(String label) {
        int padding = LABEL_WIDTH - label.length();
        return padding <= 0 ? label : label + " ".repeat(padding);
    }
}
