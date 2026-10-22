bool cmiHelix(uint b){float kind=cmiStarVec(b+20u).x;return kind>1.5&&kind<2.5;}
vec4 cmiHelixInfo(uint b){return cmiStarVec(b+288u);}
vec4 cmiHelixTimes(uint b){return cmiStarVec(b+292u);}
float cmiHelixPhase(uint b,float time){vec4 t=cmiStarVec(b+4u);if(cmiStarVec(b).y>2.5||time>t.w)return 6.;return cmiStarVec(b+20u).y<3.||time<t.y?1.:4.;}
float cmiHelixHeading(uint b,float time){return cmiStarVec(b+20u).z;}
float cmiHelixProgress(uint b,float time){float elapsed=max(0.,time-cmiHelixTimes(b).y);return cmiHelixInfo(b).z*(elapsed-.075*(1.-pow(1.-clamp(elapsed/.15,0.,1.),2.)));}
vec3 cmiHelixLocal(uint a){uint slot=cmiStarSlot(a),group=cmiStarGroup(a),rank=cmiStarRank(a)+group*2048u,at=152304u+(slot*4096u+rank)*3u;return cmiStarVec(at).xyz;}
float cmiHelixMemberPhase(uint a,float time){return cmiHelixPhase(cmiStarBase(cmiStarSlot(a)),time);}
bool cmiHelixPassed(uint b,vec3 position){return dot(position-cmiStarVec(b+24u).xyz,cmiStarVec(b+308u).xyz)>=cmiStarVec(b+296u).z;}
float cmiAttackSpeed(float identity,float time,float base){int slot=cmiStarClaim(identity,time);if(slot<0)return base;uint b=cmiStarBase(uint(slot));if(cmiDome(b))return 6.;if(!cmiHelix(b))return base;float phase=cmiHelixMemberPhase(cmiStarAssignment(identity),cmiStarTime(b,time));return phase==1.?12.:phase==4.?cmiHelixInfo(b).w:base;}
