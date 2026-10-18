package com.iridium126.createmanaindustry.compat.hextrick.execution;

import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv;
import com.iridium126.createmanaindustry.compat.hextrick.execution.circle.CircleSlateManaPool;
import dev.enjarai.trickster.spell.Fragment;
import dev.enjarai.trickster.spell.execution.SpellExecutionManager;
import dev.enjarai.trickster.spell.execution.source.PlayerSpellSource;
import dev.enjarai.trickster.spell.execution.source.SpellSource;
import dev.enjarai.trickster.spell.fragment.VoidFragment;
import dev.enjarai.trickster.spell.fragment.storage.SlotFragment;
import dev.enjarai.trickster.spell.mana.storage.ManaVariant;
import java.util.Optional;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3d;
import org.ladysnake.cca.api.v3.component.ComponentKey;

/** A playerless source positioned at the circle's currently executing slate. */
public final class CircleSpellSource implements SpellSource {
    private final CircleCastEnv env;
    private final BlockPos pos;
    private final Vector3d facing;
    private final Storage<ManaVariant> mana;
    private final SpellExecutionManager manager;
    private Fragment crowMind = VoidFragment.INSTANCE;
    private final CastingOrigin origin;
    private final PlayerSpellSource playerSource;

    public CircleSpellSource(CircleCastEnv env, CastingOrigin origin) {
        this.env = env;
        this.origin = origin;
        this.pos = env.circleState().currentPos.immutable();
        var direction = env.circleState().enteredFrom.getOpposite().step();
        this.facing = new Vector3d(direction.x(), direction.y(), direction.z());
        this.mana = new CircleSlateManaPool(origin).storage(env.getWorld());
        var player = origin.owner(env.getWorld().getServer());
        this.playerSource = player == null ? null : new PlayerSpellSource(player);
        this.manager = playerSource == null ? new BlockSpellExecutionManager(env.getImpetus(), this)
                : playerSource.getExecutionManager().orElseThrow();
    }

    public CastingOrigin origin() { return origin; }
    @Override public Optional<ServerPlayer> getPlayer() { return playerSource == null ? Optional.empty() : playerSource.getPlayer(); }
    @Override public Optional<Entity> getCaster() { return playerSource == null ? Optional.empty() : playerSource.getCaster(); }
    @Override public Optional<Container> getInventory() { return playerSource == null ? Optional.empty() : playerSource.getInventory(); }
    @Override public Optional<ItemStack> getOtherHandStack(Predicate<ItemStack> filter) { return playerSource == null ? Optional.empty() : playerSource.getOtherHandStack(filter); }
    @Override public Optional<SlotFragment> getOtherHandSlot() { return playerSource == null ? Optional.empty() : playerSource.getOtherHandSlot(); }

    @Override public <T extends org.ladysnake.cca.api.v3.component.Component> Optional<T> getComponent(ComponentKey<T> key) {
        return playerSource == null ? Optional.empty() : playerSource.getComponent(key);
    }
    @Override public float getHealth() { return playerSource == null ? -1 : playerSource.getHealth(); }
    @Override public float getMaxHealth() { return playerSource == null ? -1 : playerSource.getMaxHealth(); }
    @Override public Storage<ManaVariant> getManaStorage() { return mana; }
    @Override public ServerLevel getWorld() { return env.getWorld(); }
    @Override public BlockPos getBlockPos() { return pos; }
    @Override public Vector3d getPos() { return new Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5); }
    @Override public Optional<Vector3d> getFacing() {
        return Optional.of(new Vector3d(facing));
    }
    @Override public Optional<SpellExecutionManager> getExecutionManager() { return Optional.of(manager); }
    @Override public Fragment getCrowMind() { return playerSource == null ? crowMind : playerSource.getCrowMind(); }
    @Override public void setCrowMind(Fragment fragment) { if (playerSource == null) crowMind = fragment; else playerSource.setCrowMind(fragment); }
    @Override public void showMessage(Component message) { env.printMessage(message); }
    @Override public void offerOrDropItem(ItemStack stack) {
        if (playerSource != null) { playerSource.offerOrDropItem(stack); return; }
        Vector3d position = getPos();
        getWorld().addFreshEntity(new ItemEntity(getWorld(), position.x, position.y, position.z, stack));
    }
}
