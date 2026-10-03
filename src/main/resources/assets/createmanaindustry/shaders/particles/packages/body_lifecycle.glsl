// Exact sentinels in the existing 64-byte body's reserved sleep field.
const float PACKAGE_PREPARED=-2.0;
const float PACKAGE_RETIRED=-3.0;
// Temporarily stationary while a collision snapshot needed by this body is unavailable.
// This state remains visible/queryable and is retried next step without CPU motion.
const float PACKAGE_COLLISION_FROZEN=-4.0;
// Shared by render and compute shaders through state.glsl. Keep these limits
// here so every shader that includes the lifecycle definitions sees the same
// bounds; longer paths take the bounded local pause instead of rewinding.
const float PACKAGE_MAX_SWEEP_CELLS=16.0;
const int PACKAGE_SWEEP_CELL_VOLUME=8192;
bool packageInContactGrid(float state) { return state!=PACKAGE_PREPARED && state!=PACKAGE_RETIRED; }
