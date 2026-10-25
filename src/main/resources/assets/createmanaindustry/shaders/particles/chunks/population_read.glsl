// Same-generation unculled role lists. MODEL rows are (pool slot, stable animation slot).
// Invalid MODEL metadata yields no members; only the optional Storm subset retains a scan fallback.
layout(std430,binding=BIND_POPULATION_READ) readonly buffer PopulationRead {
    uint modelCount,stormCount,live,valid;
    uvec2 generation;uint modelCapacity,stormCapacity;
    uint slots[];
} populationRead;
uniform bool uPopulationReadEnabled;
uniform uvec2 uPopulationGeneration;
bool cmiPopulationIndexed(uint liveCount,bool storm){
    uint count=storm?populationRead.stormCount:populationRead.modelCount;
    uint capacity=storm?populationRead.stormCapacity:populationRead.modelCapacity;
    return uPopulationReadEnabled&&populationRead.valid==1u&&populationRead.generation==uPopulationGeneration
        &&populationRead.live==liveCount&&count<=capacity;
}
uint cmiPopulationCount(uint liveCount,bool storm){
    if(!uPopulationReadEnabled)return liveCount;
    return cmiPopulationIndexed(liveCount,storm)?(storm?populationRead.stormCount:populationRead.modelCount):(storm?liveCount:0u);
}
uint cmiPopulationSlot(uint rank,uint liveCount,bool storm){
    if(!uPopulationReadEnabled)return rank;
    return cmiPopulationIndexed(liveCount,storm)?populationRead.slots[storm?populationRead.modelCapacity*2u+rank:rank*2u]:rank;
}
uint cmiPopulationAnimation(uint rank){return populationRead.slots[rank*2u+1u];}
