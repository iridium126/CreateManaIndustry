// Simulation appends (pool slot, animation slot) pairs before publishing the
// candidate population header. Counter word 3 is the append cursor here.
uint cmiCandidateStormCount(uint appended){
    return min(appended,populationRead.stormCapacity);
}
uint cmiCandidateStormSlot(uint rank){
    return populationRead.slots[populationRead.modelCapacity*2u+rank*2u];
}
