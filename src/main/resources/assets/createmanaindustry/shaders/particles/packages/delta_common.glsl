// Internal free-package sync contract. One stable candidate index per authority epoch.
struct DeltaMeta { uvec4 identity; uvec4 selection; }; // body, server-local ID, ACTIVE, reserved
struct Quantized { ivec4 positionFlags; ivec4 velocityYaw; };
struct DeltaRecord { uvec4 identity; uvec4 header; Quantized value; }; // candidate, local ID, mask, RELEASE
layout(std430,binding=0) buffer Metadata { DeltaMeta meta[]; };
layout(std430,binding=1) buffer Baselines { Quantized baseline[]; };
layout(std430,binding=2) buffer Flights { uint flight[]; };
layout(std430,binding=3) buffer Records { DeltaRecord records[]; };
layout(std430,binding=4) buffer Counts { uint requested; uint accepted; uint overflow; uint reserved; };
uniform uint uCount;
uniform uint uStamp;
uniform uint uCapacity;
