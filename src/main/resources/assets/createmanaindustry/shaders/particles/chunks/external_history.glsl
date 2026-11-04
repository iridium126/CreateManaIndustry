// The append producer owns correspondence; mutable render fields are never keys.
layout(std430,binding=31) buffer ExternalHistory {uint words[];} externalHistory;
uniform bool uHistoryEnabled;
uniform uint uHistoryBase,uSimulationGeneration;
void cmiExternalHistory(uint slot,uint previousPlusOne){
    if(uHistoryEnabled)externalHistory.words[uHistoryBase+slot]=previousPlusOne;
}
