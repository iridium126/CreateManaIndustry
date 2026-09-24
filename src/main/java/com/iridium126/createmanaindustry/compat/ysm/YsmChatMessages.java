package com.iridium126.createmanaindustry.compat.ysm;

import java.util.Locale;
import net.minecraft.network.chat.Component;

/** Localized player-facing messages shared by YSM-backed Hexcasting actions. */
public final class YsmChatMessages {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final String PREFIX = "createmanaindustry.chat.player_model.";

    public static Component actionFailed(String reason) {
        return Component.translatable(PREFIX + "action_failed", reason(reason));
    }

    public static Component invalidGeometry(String reason) {
        return Component.translatable(PREFIX + "invalid_geometry", reason(reason));
    }

    public static Component applicationSucceeded() {
        return Component.translatable(PREFIX + "application_succeeded");
    }

    public static Component modelRestored() {
        return Component.translatable(PREFIX + "model_restored");
    }

    public static Component applicationFailed(String reason) {
        return Component.translatable(PREFIX + "application_failed", reason(reason));
    }

    public static Component exportSucceeded(String path) {
        return Component.translatable(PREFIX + "export_succeeded", path == null ? "" : path);
    }

    public static Component exportFailed(String reason) {
        return Component.translatable(PREFIX + "export_failed", reason(reason));
    }

    public static Component transferCheckFailed() {
        return Component.translatable(PREFIX + "transfer_check_failed");
    }

    public static Component transferInterrupted() {
        return Component.translatable(PREFIX + "transfer_interrupted");
    }

    private static Component reason(String raw) {
        if (raw == null || raw.isBlank()) return Component.translatable(PREFIX + "reason.unknown");
        String value = raw.toLowerCase(Locale.ROOT);

        if (value.equals("target player is in another dimension"))
            return Component.translatable(PREFIX + "reason.same_dimension");
        if (value.contains("retry later") || value.contains("still loading") || value.contains("still syncing")
                || value.contains("being saved") || value.contains("being prepared"))
            return Component.translatable(PREFIX + "reason.preparing");
        if (value.contains("queue is full") || value.contains("busy"))
            return Component.translatable(PREFIX + "reason.busy");
        if (value.contains("missing") || value.contains("not found") || value.contains("was evicted")
                || value.contains("no such file") || value.contains("nosuchfileexception"))
            return Component.translatable(PREFIX + "reason.missing_data");
        if (value.contains("hash") || value.contains("corrupt") || value.contains("checksum")
                || value.contains("digest mismatch") || value.contains("failed verification"))
            return Component.translatable(PREFIX + "reason.corrupt_data");
        if (value.contains("unavailable") || value.contains("not in the native catalog")
                || value.contains("no longer loaded"))
            return Component.translatable(PREFIX + "reason.unavailable");
        if (value.contains("left the server") || value.contains("no longer in this session"))
            return Component.translatable(PREFIX + "reason.target_unavailable");
        if (value.contains("session is not ready") || value.contains("mapping is still loading"))
            return Component.translatable(PREFIX + "reason.preparing");
        if (value.equals("a connected player client is required for ysm export"))
            return Component.translatable(PREFIX + "reason.client_required");
        if (value.equals("configured media cost is too large"))
            return Component.translatable(PREFIX + "reason.cost");
        if (value.equals("expected a vector")) return Component.translatable(PREFIX + "reason.vector");
        if (value.equals("expected a number")) return Component.translatable(PREFIX + "reason.number");
        if (value.equals("expected a finite number")) return Component.translatable(PREFIX + "reason.finite_number");
        if (value.equals("expected a boolean")) return Component.translatable(PREFIX + "reason.boolean");
        if (value.equals("expected a list")) return Component.translatable(PREFIX + "reason.list");
        if (value.equals("expected a string iota")) return Component.translatable(PREFIX + "reason.string");
        if (value.equals("expected only cube iotas")) return Component.translatable(PREFIX + "reason.cubes_only");
        if (value.equals("expected only group iotas")) return Component.translatable(PREFIX + "reason.groups_only");
        if (value.equals("property index must be an integer")) return Component.translatable(PREFIX + "reason.property_index_integer");
        if (value.equals("property index must be between 0 and 13")) return Component.translatable(PREFIX + "reason.property_index_range");
        if (value.equals("property is read-only")) return Component.translatable(PREFIX + "reason.property_read_only");
        if (value.equals("property is not available for this geometry reference"))
            return Component.translatable(PREFIX + "reason.property_target");
        if (value.equals("expected six uv faces")) return Component.translatable(PREFIX + "reason.six_faces");
        if (value.equals("expected six fields per uv face")) return Component.translatable(PREFIX + "reason.six_face_fields");
        if (value.equals("invalid uv rotation")) return Component.translatable(PREFIX + "reason.uv_rotation");
        if (value.startsWith("texture size must be")) return Component.translatable(PREFIX + "reason.texture_size");
        if (value.equals("expected a geometry file root"))
            return Component.translatable(PREFIX + "reason.geometry_root_required");
        if (value.equals("file roots cannot have a bone transform or cubes")
                || value.equals("geometry roots cannot contain cubes"))
            return Component.translatable(PREFIX + "reason.file_root_geometry");
        if (value.equals("invalid unicode scalar value")) return Component.translatable(PREFIX + "reason.unicode");
        if (value.contains("invalid") || value.contains("expected") || value.contains("unsupported"))
            return Component.translatable(PREFIX + "reason.invalid_data");

        LOGGER.warn("Player-model action failed: {}", raw);
        return Component.translatable(PREFIX + "reason.unknown");
    }

    private YsmChatMessages() {}
}
