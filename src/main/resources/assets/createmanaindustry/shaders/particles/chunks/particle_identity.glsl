// Sidecar identity for local MODEL particles; the 64-byte render ABI is unchanged.
layout(std430, binding = BIND_IDENTITY) buffer Identities { uint serial; uint token[]; } identities;
uniform uint uIdentityReadBase;
uniform uint uIdentityWriteBase;
uniform uint uIdentityCapacity,uDamageApprovalCount;
bool cmiCombatApproved(uint token,float previousHurt){
    for(uint i=0u;i<uDamageApprovalCount;i++)if(identities.token[2u*uIdentityCapacity+2u*i]==token)
        return identities.token[2u*uIdentityCapacity+2u*i+1u]==token;
    return previousHurt>0.0;
}
void cmiNewIdentity(uint slot) { identities.token[uIdentityWriteBase + slot] = atomicAdd(identities.serial, 1u) + 1u; }
void cmiCopyIdentity(uint src, uint dst) { identities.token[uIdentityWriteBase + dst] = identities.token[uIdentityReadBase + src]; }
