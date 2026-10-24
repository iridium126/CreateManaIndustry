const uint CMI_DOME_PATH_BASE=2365032u;
const uint CMI_DOME_PATH_REQUESTS=CMI_DOME_PATH_BASE+131072u*12u;
const uint CMI_DOME_PATH_CONTROL=CMI_DOME_PATH_REQUESTS+4096u;
const uint CMI_DOME_PATH_TASKS=CMI_DOME_PATH_CONTROL+32u;
const uint CMI_DOME_PATH_TASK_WORDS=1048u;
const uint CMI_DOME_PATH_IDLE=0u,CMI_DOME_PATH_QUEUED=1u,CMI_DOME_PATH_SEARCHING=2u;
const uint CMI_DOME_PATH_READY=3u,CMI_DOME_PATH_NO_ROUTE=4u,CMI_DOME_PATH_UNKNOWN=5u,CMI_DOME_PATH_CAPACITY=6u;
const uint CMI_NAV_EXTRA=CMI_DOME_PATH_TASKS+128u*1048u,CMI_NAV_MEMBERS=419236u;
uint cmiNavigationMember(uint assignment){return cmiStarWord(CMI_NAV_MEMBERS+cmiStarSlot(assignment)*12288u+cmiMajorRank(assignment));}
uint cmiNavigationRecord(uint member){return CMI_DOME_PATH_BASE+member*12u;}
uint cmiNavigationExtra(uint member){return CMI_NAV_EXTRA+member*16u;}
uint cmiDomePathRecord(uint assignment){return cmiNavigationRecord(cmiNavigationMember(assignment));}
vec3 cmiDomePathCell(uint cell){return vec3(cell&7u,(cell>>3u)&7u,(cell>>6u)&7u);}
