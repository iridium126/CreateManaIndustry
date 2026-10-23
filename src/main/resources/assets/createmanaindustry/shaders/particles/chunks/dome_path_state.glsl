const uint CMI_DOME_PATH_BASE=2365032u;
const uint CMI_DOME_PATH_REQUESTS=CMI_DOME_PATH_BASE+49152u*12u;
const uint CMI_DOME_PATH_CONTROL=CMI_DOME_PATH_REQUESTS+1536u;
const uint CMI_DOME_PATH_TASKS=CMI_DOME_PATH_CONTROL+32u;
const uint CMI_DOME_PATH_TASK_WORDS=1048u;
const uint CMI_DOME_PATH_IDLE=0u,CMI_DOME_PATH_QUEUED=1u,CMI_DOME_PATH_SEARCHING=2u;
const uint CMI_DOME_PATH_READY=3u,CMI_DOME_PATH_NO_ROUTE=4u,CMI_DOME_PATH_UNKNOWN=5u,CMI_DOME_PATH_CAPACITY=6u;
uint cmiDomePathRecord(uint assignment){return CMI_DOME_PATH_BASE+(cmiStarSlot(assignment)*12288u+cmiMajorRank(assignment))*12u;}
vec3 cmiDomePathCell(uint cell){return vec3(cell&7u,(cell>>3u)&7u,(cell>>6u)&7u);}
