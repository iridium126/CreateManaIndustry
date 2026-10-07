package com.iridium126.createmanaindustry.compat.hexcasting.jit;

/** Exact unseeded Staff callback parameters. Repeated adjacent sounds need no new key. */
public record CastSoundKey(Object world, Object excludedPlayer, Object sound, Object source,
                           long x, long y, long z, int volume, int pitch) {
    public static CastSoundKey of(Object world, Object excludedPlayer, Object sound, Object source,
                                  double x, double y, double z, float volume, float pitch) {
        return new CastSoundKey(world, excludedPlayer, sound, source,
                Double.doubleToRawLongBits(x), Double.doubleToRawLongBits(y), Double.doubleToRawLongBits(z),
                Float.floatToRawIntBits(volume), Float.floatToRawIntBits(pitch));
    }
    public boolean matches(Object world, Object excludedPlayer, Object sound, Object source,
                           double x, double y, double z, float volume, float pitch) {
        return this.world == world && this.excludedPlayer == excludedPlayer && this.sound == sound && this.source == source
                && this.x == Double.doubleToRawLongBits(x) && this.y == Double.doubleToRawLongBits(y)
                && this.z == Double.doubleToRawLongBits(z) && this.volume == Float.floatToRawIntBits(volume)
                && this.pitch == Float.floatToRawIntBits(pitch);
    }
}
