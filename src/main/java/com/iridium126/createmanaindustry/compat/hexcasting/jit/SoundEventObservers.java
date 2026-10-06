package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import java.lang.reflect.Method;
import net.neoforged.bus.EventBus;
import net.neoforged.bus.ListenerList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;

/** Live listener lists include inherited handlers and invalidate when listeners are registered. */
public final class SoundEventObservers {
    private static final ListenerList POSITION = resolve(PlayLevelSoundEvent.AtPosition.class);
    private SoundEventObservers() {}

    private static ListenerList resolve(Class<?> event) {
        try {
            if (NeoForge.EVENT_BUS.getClass() != EventBus.class) return null;
            Method method = EventBus.class.getDeclaredMethod("getListenerList", Class.class);
            if (!method.trySetAccessible()) return null;
            return (ListenerList) method.invoke(NeoForge.EVENT_BUS, event);
        } catch (ReflectiveOperationException | RuntimeException failure) { return null; }
    }

    public static boolean unobserved() {
        return POSITION != null && POSITION.getListeners().length == 0;
    }
}
