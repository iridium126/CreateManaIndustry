layout(std430,binding=BIND_POPULATION_WRITE) buffer PopulationWrite {
    uint modelCount,stormCount,live,valid;
    uvec2 generation;uint modelCapacity,stormCapacity;
    uint slots[];
} populationWrite;
uniform bool uPopulationWriteEnabled;
uniform uvec2 uPopulationWriteGeneration;
uniform uint uPopulationModelCapacity,uPopulationStormCapacity;
void cmiPopulationRecord(uint slot,bool storm){
    if(!uPopulationWriteEnabled)return;
    uint row=atomicAdd(populationWrite.modelCount,1u);
    if(row<populationWrite.modelCapacity)populationWrite.slots[row]=slot;
    if(storm){row=atomicAdd(populationWrite.stormCount,1u);
        if(row<populationWrite.stormCapacity)populationWrite.slots[populationWrite.modelCapacity+row]=slot;}
}
