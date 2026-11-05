layout(std430,binding=BIND_POPULATION_WRITE) buffer PopulationWrite {
    uint modelCount,stormCount,live,valid;
    uvec2 generation;uint modelCapacity,stormCapacity;
    uint slots[];
} populationWrite;
uniform bool uPopulationWriteEnabled;
uniform uvec2 uPopulationWriteGeneration;
uniform uint uPopulationModelCapacity,uPopulationStormCapacity;
#ifndef CMI_POPULATION_STORM_PAIRS_DECLARED
#define CMI_POPULATION_STORM_PAIRS_DECLARED
uniform bool uPopulationStormPairs;
#endif
void cmiPopulationRecord(uint slot,bool storm){
    if(!uPopulationWriteEnabled)return;
    uint row=atomicAdd(populationWrite.modelCount,1u);
    if(row<populationWrite.modelCapacity){populationWrite.slots[row*2u]=slot;populationWrite.slots[row*2u+1u]=slot;}
    if(storm){row=atomicAdd(populationWrite.stormCount,1u);
        if(row<populationWrite.stormCapacity){uint at=populationWrite.modelCapacity*2u+row*(uPopulationStormPairs?2u:1u);populationWrite.slots[at]=slot;if(uPopulationStormPairs)populationWrite.slots[at+1u]=slot;}}
}
