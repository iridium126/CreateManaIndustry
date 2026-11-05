layout(std140,binding=CMI_RESOURCE_SIMULATION_PARAMETERS_BINDING) uniform CmiSimulationParameters {
 uvec4 cmiSimulationLayout;
 vec4 cmiSimulationClock;
 vec4 cmiSimulationRates;
};
#define uCapacity cmiSimulationLayout.x
#define uKillEmit cmiSimulationLayout.y
#define uPlayerCount int(cmiSimulationLayout.z)
#define uCollisionOn int(cmiSimulationLayout.w)
#define uDt cmiSimulationClock.x
#define uStormOmegaNow cmiSimulationClock.y
#define uStormRotPhase cmiSimulationClock.z
#define uStormConvPhase cmiSimulationClock.w
#define uStormConvRate cmiSimulationRates.x
