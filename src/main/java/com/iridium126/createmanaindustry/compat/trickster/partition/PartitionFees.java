package com.iridium126.createmanaindustry.compat.trickster.partition;

import com.iridium126.createmanaindustry.compat.hextrick.execution.CircleSpellSource;
import dev.enjarai.trickster.spell.Fragment;
import dev.enjarai.trickster.spell.SpellContext;
import dev.enjarai.trickster.spell.TrickContext;
import dev.enjarai.trickster.spell.exception.blunder.WrappableBlunder;
import dev.enjarai.trickster.spell.fragment.DisplaceFragment;
import dev.enjarai.trickster.spell.fragment.EntityFragment;
import dev.enjarai.trickster.spell.fragment.VectorFragment;
import dev.enjarai.trickster.spell.trick.Trick;
import dev.enjarai.trickster.spell.trick.Tricks;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3dc;

/** Audited native costs. Reject a partition crossing before inventory changes or exponents. */
public final class PartitionFees {
    @FunctionalInterface
    private interface Handler { void check(SpellContext context, List<Fragment> arguments); }
    private static final Map<ResourceLocation, Handler> HANDLERS = handlers();

    private PartitionFees() {}

    private static Map<ResourceLocation, Handler> handlers() {
        var handlers = new LinkedHashMap<ResourceLocation, Handler>();
        for (String name : List.of("place_block", "release_entity", "summon_lightning", "summon_arrow",
                "summon_dragon_breath", "summon_fireball", "summon_tnt", "summon_wind_charge")) {
            handlers.put(id(name), PartitionFees::vector);
        }
        handlers.put(id("swap_block"), PartitionFees::swap);
        handlers.put(id("store_entity"), (context, arguments) -> entity(context, arguments, true));
        handlers.put(id("prepare_displace"), (context, arguments) -> entity(context, arguments, false));
        handlers.put(id("displace"), PartitionFees::displace);
        return Map.copyOf(handlers);
    }

    private static ResourceLocation id(String name) { return ResourceLocation.fromNamespaceAndPath("trickster", name); }
    public static Vec3 vec(Vector3dc value) { return new Vec3(value.x(), value.y(), value.z()); }

    public static void check(Object trick, SpellContext context, List<Fragment> arguments) {
        if (arguments.isEmpty() || !PartitionManager.active(context.source().getWorld())
                || !(trick instanceof Trick<?> registered)) return;
        Handler handler = HANDLERS.get(Tricks.REGISTRY.getKey(registered));
        if (handler != null) handler.check(context, arguments);
    }

    private static void vector(SpellContext context, List<Fragment> arguments) {
        if (arguments.getFirst() instanceof VectorFragment target)
            require(context, vec(context.source().getPos()), vec(target.vector()));
    }

    private static void swap(SpellContext context, List<Fragment> arguments) {
        if (arguments.size() == 2 && arguments.getFirst() instanceof VectorFragment first
                && arguments.get(1) instanceof VectorFragment second)
            require(context, vec(first.vector()), vec(second.vector()));
    }

    private static void entity(SpellContext context, List<Fragment> arguments, boolean store) {
        if (!(arguments.getFirst() instanceof EntityFragment reference)) return;
        var target = reference.getEntity(context).orElse(null);
        if (target == null) return;
        Vec3 origin = vec(context.source().getPos());
        if (store && !(context.source() instanceof CircleSpellSource))
            origin = context.source().getPlayer().map(player -> player.position()).orElse(origin);
        require(context, origin, target.position());
    }

    private static void displace(SpellContext context, List<Fragment> arguments) {
        if (arguments.size() != 2 || !(arguments.getFirst() instanceof DisplaceFragment reference)
                || !(arguments.get(1) instanceof VectorFragment movement)) return;
        Vec3 origin = vec(context.source().getPos());
        require(context, origin, origin.add(vec(movement.vector())));
        var target = reference.getEntity(context).orElse(null);
        if (target != null && target.level() == context.source().getWorld()) require(context, origin, target.position());
    }

    private static void require(SpellContext context, Vec3 from, Vec3 to) {
        if (PartitionManager.crosses(context.source().getWorld(), from, to)) throw new InfiniteDistanceBlunder();
    }

    @SuppressWarnings("serial")
    public static final class InfiniteDistanceBlunder extends WrappableBlunder {
        @Override public MutableComponent createMessage(TrickContext context) {
            return Component.translatable("createmanaindustry.partition.infinite_cost");
        }
    }
}
