package com.iridium126.createmanaindustry.content.kinetics.temporarykinetics;

import org.jetbrains.annotations.Nullable;

public interface TemporaryKineticsAccess {
    record Effect(float speed, float stress, long expiresAt) {}

    @Nullable Effect createmanaindustry$getTemporaryEffect();

    void createmanaindustry$setTemporaryEffect(@Nullable Effect effect);

    boolean createmanaindustry$isRestoringTemporaryEffect();

    boolean createmanaindustry$isTemporarySourcePending();

    void createmanaindustry$setTemporarySourcePending(boolean pending);

    float createmanaindustry$getNativeCapacitySnapshot();

    void createmanaindustry$setNativeCapacitySnapshot(float capacity);
}
