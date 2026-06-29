package com.iridium126.createtricks.mixin.bnb;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createtricks.content.kinetics.bnb.BnBKineticsCoreNodes;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChainPathfinder;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.PathedCogwheelNode;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.PlacingCogwheelChain;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.PlacingCogwheelNode;

@Mixin(targets = "com.kipti.bnb.network.packets.from_client.PlaceCogwheelChainPacket", remap = false)
public abstract class PlaceCogwheelChainPacketMixin {

	private static final ThreadLocal<ServerPlayer> CAPTURED_PLAYER = new ThreadLocal<>();

	@Inject(method = "handle", at = @At("HEAD"), remap = false)
	private void createtricks$capturePlayer(ServerPlayer player, CallbackInfo ci) {
		CAPTURED_PLAYER.set(player);
	}

	@Redirect(method = "handle",
		at = @At(value = "INVOKE", target = "Lcom/kipti/bnb/content/kinetics/cogwheel_chain/graph/CogwheelChainPathfinder;buildChainPath(Lcom/kipti/bnb/content/kinetics/cogwheel_chain/graph/PlacingCogwheelChain;)Ljava/util/List;"),
		remap = false)
	private List<PathedCogwheelNode> createtricks$overrideBuildPath(PlacingCogwheelChain placingChain) {
		try {
			List<PathedCogwheelNode> result;
			try {
				result = CogwheelChainPathfinder.buildChainPath(placingChain);
			} catch (com.kipti.bnb.content.kinetics.cogwheel_chain.placement.ChainInteractionFailedException e) {
				result = null;
			}
			if (result != null)
				return result;

			ServerPlayer player = CAPTURED_PLAYER.get();
			if (player == null)
				return null;

			Level level = player.level();
			boolean hasSpellConstruct = false;
			for (PlacingCogwheelNode node : placingChain.getVisitedNodes()) {
				if (BnBKineticsCoreNodes.isModularSpellConstruct(level, node.pos())) {
					hasSpellConstruct = true;
					break;
				}
			}
			if (!hasSpellConstruct)
				return null;

			return manualBuildChainPath(placingChain);
		} finally {
			CAPTURED_PLAYER.remove();
		}
	}

	private static List<PathedCogwheelNode> manualBuildChainPath(PlacingCogwheelChain chain) {
		List<PlacingCogwheelNode> visitedNodes = chain.getVisitedNodes();
		BlockPos controllerPos = chain.getFirstNode().pos();
		List<PathedCogwheelNode> pathNodes = new ArrayList<>();
		int side = 1;
		for (PlacingCogwheelNode node : visitedNodes) {
			pathNodes.add(new PathedCogwheelNode(side, node.isLarge(), node.rotationAxis(),
				node.pos().subtract(controllerPos), node.hasSmallCogwheelOffset()));
			side *= -1;
		}
		return pathNodes;
	}
}
