package com.iridium126.createmanaindustry.content.kinetics.temporarykinetics;

import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

public interface TemporaryKineticsNetwork {
    static TemporaryKineticsNetwork of(KineticNetwork network) {
        return (TemporaryKineticsNetwork) network;
    }

    void createmanaindustry$setTemporaryCapacity(KineticBlockEntity source, float stress);

    void createmanaindustry$publishTemporaryCapacity();

    float createmanaindustry$getNativeCapacity();
}
