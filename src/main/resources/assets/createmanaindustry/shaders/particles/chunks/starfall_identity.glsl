// Roster/assignment decoding without any attack geometry or motion dependencies.
bool cmiColumnAssignment(uint a){return (a&0x40000000u)!=0u;}
uint cmiStarSlot(uint a){return cmiColumnAssignment(a)?4u+((a>>1u)&63u):((a>>1u)&3u);}
uint cmiStarGroup(uint a){return cmiColumnAssignment(a)?0u:((a>>3u)&31u);}
uint cmiStarRank(uint a){return (a&0x3fffffffu)>>8u;}
uint cmiStarMember(float identity){return (uint(identity+.5)&262143u)-1u;}
uint cmiStarAssignment(float identity){uint member=cmiStarMember(identity);return uStarfallEnabled&&member<131072u?cmiStarWord(16u+member):0u;}
uint cmiMajorRankFromKind(uint a,float kind){return cmiStarRank(a)+(kind>1.5&&kind<2.5?cmiStarGroup(a)*2048u:0u);}
