// Same-generation unculled role lists. Overflow/dense/invalid data uses the exact live prefix.
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
        &&populationRead.live==liveCount&&count<=capacity&&count<liveCount;
}
uint cmiPopulationCount(uint liveCount,bool storm){
    if(!uPopulationReadEnabled)return liveCount;
    return cmiPopulationIndexed(liveCount,storm)?(storm?populationRead.stormCount:populationRead.modelCount):liveCount;
}
uint cmiPopulationSlot(uint rank,uint liveCount,bool storm){
    if(!uPopulationReadEnabled)return rank;
    return cmiPopulationIndexed(liveCount,storm)?populationRead.slots[(storm?populationRead.modelCapacity:0u)+rank]:rank;
}
