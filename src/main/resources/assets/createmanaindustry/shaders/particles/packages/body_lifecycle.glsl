// Exact sentinels in the existing 64-byte body's reserved sleep field.
const float PACKAGE_PREPARED=-2.0;
const float PACKAGE_RETIRED=-3.0;
bool packageInContactGrid(float state) { return state!=PACKAGE_PREPARED && state!=PACKAGE_RETIRED; }
