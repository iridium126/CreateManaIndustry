package com.iridium126.createmanaindustry.client.render;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iridium126.createmanaindustry.Config;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.kinetics.kineticatomizer.KineticAtomizerBlockEntity;

import foundry.veil.api.client.render.post.PostPipeline;
import foundry.veil.api.event.VeilPostProcessingEvent;
import foundry.veil.platform.VeilEventPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-side handler that collects active Kinetic Atomizer positions and
 * passes them as uniforms to the Veil mist volumetric shader.
 * <p>
 * Active atomizers are tracked via a {@link ConcurrentHashMap} populated by
 * {@link #setActive(BlockPos, boolean)} which should be called from the
 * atomizer block entity's client-side sync handler.
 * <p>
 * Call {@link #init()} once during client setup to register the Veil
 * post-processing listener.
 */
public final class ClientMistHandler {

	private static final ResourceLocation PIPELINE_ID = CreateManaIndustry.modLoc("mist");
	private static final int MAX_ATOMIZERS = 16;

	/** Client-side registry of active atomizer positions. */
	private static final Map<BlockPos, Boolean> activeAtomizers = new ConcurrentHashMap<>();

	private static final float[] atomizerData = new float[MAX_ATOMIZERS * 4];
	private static int atomizerCount = 0;
	private static boolean initialized = false;
	private static boolean dirty = true;

	private ClientMistHandler() {}

	/**
	 * Registers the Veil post-processing listener. Safe to call multiple times.
	 * Must be called on the client after Veil has initialized.
	 */
	public static void init() {
		if (initialized)
			return;
		initialized = true;

		// Bridge: register for client sync notifications from atomizer BEs
		KineticAtomizerBlockEntity.setMistSyncCallback(ClientMistHandler::setActive);

		// Listen for Veil post-processing to inject uniforms
		VeilEventPlatform.INSTANCE.preVeilPostProcessing(ClientMistHandler::onPrePostProcessing);
	}

	/**
	 * Called by the atomizer BE when its active state is synced to the client.
	 */
	public static void setActive(BlockPos pos, boolean active) {
		if (active)
			activeAtomizers.put(pos.immutable(), Boolean.TRUE);
		else
			activeAtomizers.remove(pos);
		dirty = true;
	}

	// --- Veil event callbacks ---

	private static void onPrePostProcessing(ResourceLocation name, PostPipeline pipeline,
			PostPipeline.Context context) {
		if (!PIPELINE_ID.equals(name))
			return;

		if (dirty) {
			packAtomizerData();
			dirty = false;
		}

		var countUniform = pipeline.getUniform("AtomizerCount");
		if (countUniform != null)
			countUniform.setInt(atomizerCount);

		var dataUniform = pipeline.getUniform("AtomizerData");
		if (dataUniform != null)
			dataUniform.setFloats(atomizerData);

		var colorUniform = pipeline.getUniform("MistColor");
		if (colorUniform != null)
			colorUniform.setVector(0.70f, 0.75f, 0.80f, 0.6f);

		var densityUniform = pipeline.getUniform("MistDensity");
		if (densityUniform != null)
			densityUniform.setFloat(0.08f);

		var stepUniform = pipeline.getUniform("MistStepScale");
		if (stepUniform != null)
			stepUniform.setFloat(0.8f);

		var sunUniform = pipeline.getUniform("SunDirection");
		if (sunUniform != null) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.level != null) {
				float sunAngle = mc.level.getSunAngle(mc.getTimer().getGameTimeDeltaTicks());
				sunUniform.setVector((float) -Math.cos(sunAngle), (float) (Math.sin(sunAngle) * 0.3), 0.0f, 0.0f);
			} else {
				sunUniform.setVector(-0.7f, 0.5f, 0.3f, 0.0f);
			}
		}
	}

	private static void packAtomizerData() {
		int count = 0;
		for (BlockPos pos : activeAtomizers.keySet()) {
			if (count >= MAX_ATOMIZERS)
				break;
			int base = count * 4;
			atomizerData[base] = pos.getX() + 0.5f;
			atomizerData[base + 1] = pos.getY() + 0.5f;
			atomizerData[base + 2] = pos.getZ() + 0.5f;
			atomizerData[base + 3] = Config.mistMaxRadius;
			count++;
		}
		atomizerCount = count;
	}
}
