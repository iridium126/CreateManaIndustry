// One census at the simulation publication; display and the next tick reuse its slots.
layout(std430,binding=CMI_RESOURCE_ORDINARY_ROSTER_BINDING) buffer OrdinaryRoster {uvec4 header;uint slots[];} ordinaryRoster;
uniform bool uOrdinaryRosterEnabled,uOrdinaryWriteEnabled;
uint cmiOrdinaryCount(uint live){return uOrdinaryRosterEnabled?ordinaryRoster.header.x:live;}
uint cmiOrdinarySlot(uint rank){return uOrdinaryRosterEnabled?ordinaryRoster.slots[rank]:rank;}
void cmiOrdinaryRecord(uint slot){
 if(!uOrdinaryWriteEnabled)return;uint row=atomicAdd(ordinaryRoster.header.x,1u);
 if(row<ordinaryRoster.header.y)ordinaryRoster.slots[row]=slot;
}
