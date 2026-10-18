package com.iridium126.createmanaindustry.compat.hextrick.execution.circle;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.compat.hextrick.execution.CastingOrigin;
import com.iridium126.createmanaindustry.compat.hextrick.execution.CircleSourceDescriptor;
import dev.enjarai.trickster.spell.mana.ManaPoolType;
import dev.enjarai.trickster.spell.mana.MutableManaPool;
import dev.enjarai.trickster.spell.mana.SimpleManaPool;
import dev.enjarai.trickster.spell.mana.storage.ManaStorage;
import dev.enjarai.trickster.spell.mana.storage.ManaVariant;
import io.wispforest.endec.Endec;
import io.wispforest.endec.StructEndec;
import io.wispforest.endec.impl.StructEndecBuilder;
import java.util.ArrayList;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.Registry;
import net.minecraft.world.level.Level;

/** Live storage only. Persistence records a source, never a spendable balance. */
public final class CircleSlateManaPool implements MutableManaPool {
    public static final StructEndec<CircleSlateManaPool> ENDEC = StructEndecBuilder.of(
            Endec.STRING.fieldOf("source", pool -> {
                if (pool.origin == null) throw new IllegalStateException("A transient inventory has no persistent source");
                return pool.origin.encode();
            }), encoded -> new CircleSlateManaPool(CastingOrigin.decode(encoded)));
    public static final ManaPoolType<CircleSlateManaPool> TYPE = Registry.register(ManaPoolType.REGISTRY,
            CreateManaIndustry.modLoc("circle_slate"), new ManaPoolType<>(ENDEC));

    private final CastingOrigin origin;
    private final SlateKnotInventory transientInventory;
    private final Level transientLevel;

    public CircleSlateManaPool(CastingOrigin origin) {
        this.origin = origin;
        transientInventory = null;
        transientLevel = null;
    }

    /** For immediate inventory transactions; intentionally not serializable. */
    public CircleSlateManaPool(SlateKnotInventory inventory, Level level) {
        origin = null;
        transientInventory = inventory;
        transientLevel = level;
    }

    public static void ensureTypeRegistered() {}
    @Override public ManaPoolType<?> type() { return TYPE; }

    public Storage<ManaVariant> storage(Level world) {
        SlateKnotInventory inventory = transientInventory;
        Level sourceWorld = transientLevel;
        if (origin != null) {
            if (world == null || world.getServer() == null) return Storage.empty();
            var resolution = origin.source().resolve(world.getServer());
            if (resolution.status() != CircleSourceDescriptor.Status.READY) return Storage.empty();
            sourceWorld = resolution.level();
            inventory = origin.source().inventory(resolution.level());
        }
        var storages = new ArrayList<Storage<ManaVariant>>();
        for (var slot : InventoryStorage.of(inventory, null).getSlots()) {
            var storage = ManaStorage.ITEM.find(slot.getResource().toStack(),
                    new ManaStorage.LookupContext(ContainerItemContext.ofSingleSlot(slot), sourceWorld));
            if (storage != null) storages.add(storage);
        }
        return new CombinedStorage<>(storages);
    }

    @Override public ManaVariant getVariant(Level world) {
        for (var view : storage(world).nonEmptyViews()) return view.getResource();
        return ManaVariant.EMPTY;
    }

    private static long add(long left, long right) { return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right; }
    @Override public long get(Level world) {
        long total = 0;
        for (var view : storage(world)) total = add(total, view.getAmount());
        return total;
    }
    @Override public long getMax(Level world) {
        long total = 0;
        for (var view : storage(world)) total = add(total, view.getCapacity());
        return total;
    }
    @Override public void setVariant(ManaVariant variant, Level world) {}
    @Override public void setMax(long value, Level world) {}
    @Override public void set(long value, Level world) {
        long current = get(world);
        ManaVariant variant = getVariant(world);
        if (variant.isBlank()) return;
        if (value < current) use(variant, current - Math.max(0, value), world);
        else if (value > current) refill(variant, value - current, world);
    }
    @Override public long use(ManaVariant variant, long amount, Level world) {
        try (var transaction = Transaction.openOuter()) {
            long extracted = storage(world).extract(variant, amount, transaction);
            transaction.commit();
            return amount - extracted;
        }
    }
    @Override public long refill(ManaVariant variant, long amount, Level world) {
        try (var transaction = Transaction.openOuter()) {
            long inserted = storage(world).insert(variant, amount, transaction);
            transaction.commit();
            return amount - inserted;
        }
    }
    @Override public MutableManaPool makeClone(Level world) {
        return new SimpleManaPool(get(world), getMax(world), getVariant(world));
    }
}
