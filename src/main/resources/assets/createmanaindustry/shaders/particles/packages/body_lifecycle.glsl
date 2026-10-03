// Exact sentinels in the existing 64-byte body's reserved sleep field.
const float PACKAGE_PREPARED=-2.0;
const float PACKAGE_RETIRED=-3.0;
// Temporarily stationary while a collision snapshot needed by this body is unavailable.
// This state remains visible/queryable and is retried next step without CPU motion.
const float PACKAGE_COLLISION_FROZEN=-4.0;
// With the production 2-block cells this covers the 3.92-block terminal
// free-fall step. Both CCD participants and the fast-cell prepass share it.
const float PACKAGE_MAX_SWEEP_CELLS=2.0;
bool packageInContactGrid(float state) { return state!=PACKAGE_PREPARED && state!=PACKAGE_RETIRED; }
