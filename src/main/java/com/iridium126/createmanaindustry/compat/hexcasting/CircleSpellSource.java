package com.iridium126.createmanaindustry.compat.hexcasting;

import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv;
import dev.enjarai.trickster.spell.Fragment;
import com.iridium126.createmanaindustry.compat.hexcasting.circle.CircleSlateManaPool;
import dev.enjarai.trickster.spell.execution.SpellExecutionManager;
import dev.enjarai.trickster.spell.execution.source.SpellSource;
import dev.enjarai.trickster.spell.fragment.VoidFragment;
import dev.enjarai.trickster.spell.mana.storage.ManaVariant;
import java.util.Optional;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3d;
import org.ladysnake.cca.api.v3.component.ComponentKey;

/** A playerless source positioned at the circle's currently executing slate. */
final class CircleSpellSource implements SpellSource {
    private final CircleCastEnv env;
    private final BlockPos pos;
    private final Vector3d facing;
    private final Storage<ManaVariant> mana;
    private final SpellExecutionManager manager;
    private Fragment crowMind = VoidFragment.INSTANCE;

    CircleSpellSource(CircleCastEnv env, CircleSlateManaPool pool) {
        this.env = env;
        this.pos = env.circleState().currentPos.immutable();
        var direction = env.circleState().enteredFrom.getOpposite().step();
        this.facing = new Vector3d(direction.x(), direction.y(), direction.z());
        this.mana = pool.storage(env.getWorld());
        this.manager = new BlockSpellExecutionManager(env.getImpetus(), this);
    }

    @Override public <T extends org.ladysnake.cca.api.v3.component.Component> Optional<T> getComponent(ComponentKey<T> key) {
        return Optional.empty();
    }
    @Override public float getHealth() { return -1; }
    @Override public float getMaxHealth() { return -1; }
    @Override public Storage<ManaVariant> getManaStorage() { return mana; }
    @Override public ServerLevel getWorld() { return env.getWorld(); }
    @Override public BlockPos getBlockPos() { return pos; }
    @Override public Vector3d getPos() { return new Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5); }
    @Override public Optional<Vector3d> getFacing() {
        return Optional.of(new Vector3d(facing));
    }
    @Override public Optional<SpellExecutionManager> getExecutionManager() { return Optional.of(manager); }
    @Override public Fragment getCrowMind() { return crowMind; }
    @Override public void setCrowMind(Fragment fragment) { crowMind = fragment; }
    @Override public void showMessage(Component message) { env.printMessage(message); }
    @Override public void offerOrDropItem(ItemStack stack) {
        Vector3d position = getPos();
        getWorld().addFreshEntity(new ItemEntity(getWorld(), position.x, position.y, position.z, stack));
    }
}
