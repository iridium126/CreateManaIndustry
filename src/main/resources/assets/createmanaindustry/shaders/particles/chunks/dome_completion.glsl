// Compute-only terminal state publication, shared by terrain and final-body audit.
// Explicit buffer/marker arguments keep ownership local to each production pass.
#define CMI_DOME_COMPLETE(STATE, MEMBER, WORD, BIT, NORMAL, MARKER) \
 { STATE.words[(MEMBER)*2u+1u]=6u|0x100u|((NORMAL)?0x20000000u:0x80000000u); \
   STATE.words[CMI_GATHER_BLOCKED+(MEMBER)]=0u; \
   STATE.words[CMI_GATHER_STREAK+(MEMBER)]=0u; \
   atomicOr(STATE.words[CMI_COMPLETIONS+(WORD)],(BIT)); \
   MARKER=0.; }
