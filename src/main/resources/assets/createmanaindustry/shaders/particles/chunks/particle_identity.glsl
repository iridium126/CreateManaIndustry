// Local MODEL tokens and all-particle compaction history; render records stay 64 bytes.
layout(std430, binding = BIND_IDENTITY) buffer Identities { uint serial; uint token[]; } identities;
uniform uint uIdentityReadBase;
uniform uint uIdentityWriteBase;
uniform uint uIdentityCapacity,uDamageApprovalCount;
// A compaction edge, not a key derived from mutable render fields. Zero means
// birth/teleport; otherwise the value is the previous simulation slot plus one.
uint cmiHistorySource=0xffffffffu;
uniform bool uHistoryEnabled;
void cmiHistoryContinue(uint slot){
    if(!uHistoryEnabled)return;
    identities.token[2u*uIdentityCapacity+2u*DAMAGE_QUEUE_CAP+slot]=cmiHistorySource+1u;
}
void cmiHistorySnap(uint slot){
    if(!uHistoryEnabled)return;
    identities.token[2u*uIdentityCapacity+2u*DAMAGE_QUEUE_CAP+slot]=0u;
}
bool cmiCombatApproved(uint token,float previousHurt){
    for(uint i=0u;i<uDamageApprovalCount;i++)if(identities.token[2u*uIdentityCapacity+2u*i]==token)
        return identities.token[2u*uIdentityCapacity+2u*i+1u]==token;
    return previousHurt>0.0;
}
void cmiNewIdentity(uint slot) { identities.token[uIdentityWriteBase + slot] = atomicAdd(identities.serial, 1u) + 1u; }
void cmiCopyIdentity(uint src, uint dst) { identities.token[uIdentityWriteBase + dst] = identities.token[uIdentityReadBase + src]; }
