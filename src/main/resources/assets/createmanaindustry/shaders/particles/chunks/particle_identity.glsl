// Sidecar identity for local MODEL particles; the 64-byte render ABI is unchanged.
layout(std430, binding = BIND_IDENTITY) buffer Identities { uint serial; uint token[]; } identities;
uniform uint uIdentityReadBase;
uniform uint uIdentityWriteBase;
void cmiNewIdentity(uint slot) { identities.token[uIdentityWriteBase + slot] = atomicAdd(identities.serial, 1u) + 1u; }
void cmiCopyIdentity(uint src, uint dst) { identities.token[uIdentityWriteBase + dst] = identities.token[uIdentityReadBase + src]; }
