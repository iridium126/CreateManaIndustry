package com.iridium126.createmanaindustry.client.particles.engine.hardware;

/** Replaces only Dome broad-phase enumeration. Exact swept-contact, velocity,
 * terrain, audit and lifecycle code remains the original source. */
public final class HardwareDomeSource {
    public record Variant(String consumer,String prepare){}
    private HardwareDomeSource(){}
    private static int unique(String s,String key){int at=s.indexOf(key);if(at<0||s.indexOf(key,at+1)>=0)throw new IllegalArgumentException("R7 Dome boundary changed: "+key);return at;}
    private static int close(String s,int open){int depth=0;boolean line=false,comment=false;for(int i=open;i<s.length();i++){char c=s.charAt(i),n=i+1<s.length()?s.charAt(i+1):0;if(line){if(c=='\n')line=false;continue;}if(comment){if(c=='*'&&n=='/'){comment=false;i++;}continue;}if(c=='/'&&n=='/'){line=true;i++;continue;}if(c=='/'&&n=='*'){comment=true;i++;continue;}if(c=='{')depth++;if(c=='}'&&--depth==0)return i;}throw new IllegalArgumentException("Unbalanced Dome query scope");}
    private static String replaceLoop(String s,String start,boolean apply){int at=unique(s,start),outer=s.indexOf('{',at),end=close(s,outer)+1,chain=s.indexOf("while(node!=0){",outer),inner=s.indexOf('{',chain),body=s.indexOf("if(j==i)continue;",inner);if(chain<0||body<0||body>end)throw new IllegalArgumentException("Dome narrow phase changed");String exact=s.substring(body,close(s,inner));String loop=(apply?"if(phase==4u&&stoppedCount>8u)":"")+"for(uint r7Step=0u;r7Step<(r7Valid?r7To-r7From:min(counter.live,uint(pool.data.length())/4u));r7Step++){uint j=r7Valid?r7DomeWord(r7Ids+r7From+r7Step):r7Step;visits++;"+exact+"}\n";return s.substring(0,at)+loop+s.substring(end);}
    private static final String RESULT="""
        layout(r32ui,binding=6)readonly uniform uimageBuffer r7DomeResult;
        uniform bool uR7ResultsEnabled;uniform uvec2 uR7Generation,uR7ResourceEpoch;
        uint r7DomeWord(uint i){return imageLoad(r7DomeResult,int(i)).x;}
        bool r7DomeRange(uint member,out uint from,out uint to,out uint ids){
          from=0u;to=0u;ids=0u;if(!uR7ResultsEnabled||member>=131072u)return false;
          uint size=uint(imageSize(r7DomeResult));if(size<22u)return false;
          if(r7DomeWord(0u)!=0x52375131u||r7DomeWord(1u)!=uR7Generation.x||r7DomeWord(2u)!=uR7Generation.y||r7DomeWord(3u)!=2u||r7DomeWord(5u)!=min(counter.live,uint(pool.data.length())/4u)||r7DomeWord(8u)!=0u||r7DomeWord(9u)!=1u||r7DomeWord(10u)!=uR7ResourceEpoch.x||r7DomeWord(11u)!=uR7ResourceEpoch.y)return false;
          if(r7DomeWord(17u)!=0u||r7DomeWord(18u)!=0u||r7DomeWord(19u)!=0u)return false;
          uint lookup=r7DomeWord(12u),count=r7DomeWord(4u);if(r7DomeWord(13u)!=131072u||lookup>size||131072u>size-lookup||count>(size-22u)/2u)return false;
          uint row=r7DomeWord(lookup+member);if(row>=count)return false;from=r7DomeWord(20u+row*2u);to=r7DomeWord(22u+row*2u);ids=r7DomeWord(6u);
          if(r7DomeWord(21u+row*2u)!=0u||r7DomeWord(23u+row*2u)!=0u||from>to||to>r7DomeWord(16u)||to>r7DomeWord(7u)||ids>lookup||to>lookup-ids||ids<22u+count*2u)return false;
          for(uint k=from;k<to;k++)if(r7DomeWord(ids+k)>=min(counter.live,uint(pool.data.length())/4u))return false;
          return true;
        }
        """;
    private static final String PACKET="""
        layout(r32ui,binding=6)coherent uniform uimageBuffer packet;
        uniform uint phase,pointCapacity,queryCapacity,emitterStride,lookupOffset,diagnosticSort;
        uniform uvec2 nonce,resourceEpoch;uniform uvec4 offsets;
        uint r7Get(uint i){return imageLoad(packet,int(i)).x;}
        void r7Put(uint i,uint v){imageStore(packet,int(i),uvec4(v,0,0,0));}
        void r7Error(uint v){imageAtomicOr(packet,13,v);}
        float r7Budget(uint a,uint phase){return phase==1u?CMI_STORM_INTERCEPT:a==0u?CMI_STORM_CRUISE:CMI_DOME_MOTION_LIMIT(state,a,phase);}
        void main(){uint i=gl_GlobalInvocationID.x,live=min(counter.live,uint(pool.data.length())/4u);
          if(phase==0u){if(i<131072u){r7Put(lookupOffset+i,0xffffffffu);r7Put(lookupOffset+131072u+i,0xffffffffu);}if(i==0u){for(uint k=0u;k<16u;k++)r7Put(k,0u);r7Put(1u,2u);r7Put(2u,nonce.x);r7Put(3u,nonce.y);r7Put(6u,((lookupOffset+131072u)<<2u)|2u|diagnosticSort);r7Put(7u,floatBitsToUint(.000001));r7Put(8u,offsets.x);r7Put(9u,offsets.y);r7Put(10u,offsets.z);r7Put(11u,offsets.w);r7Put(12u,live);r7Put(14u,resourceEpoch.x);r7Put(15u,resourceEpoch.y);r7Put(lookupOffset+262144u,0u);}return;}
          if(phase==3u){if(i==0u){memoryBarrierImage();r7Put(0u,0x52374931u);}return;}
          if(i>=live)return;vec4 p=pool.data[i*4u],life=pool.data[i*4u+3u];uint emitter=floatBitsToUint(life.w),hb=emitter*VEC4_PER_EMITTER;
          if(hb+18u>=uint(emitters.u.length())||emitters.u[hb+18u].x<.5||emitters.u[hb+7u].x<1.5||emitters.u[hb+7u].x>=2.5||life.y<=0.)return;
          uint member=cmiStarMember(p.w);if(member>=131072u||any(isnan(p.xyz))||any(isinf(p.xyz))){r7Error(1u);return;}
          uint a=cmiStarAssignment(p.w),current=CMI_STATE(state,member*2u+1u)&255u;int owner=cmiDomeOwner(p.w);bool dome=owner>=0&&(current==1u||current==4u||current==6u);
          vec3 old;bool known=cmiDomeOld(member,old);if(!known)old=p.xyz-pool.data[i*4u+1u].xyz*uDt;
          float move=length(p.xyz-old),budget=dome?r7Budget(a,current):0.;
          if(phase==1u){uint row=imageAtomicAdd(packet,4,1u);if(row>=pointCapacity){r7Error(2u);return;}if(imageAtomicCompSwap(packet,int(lookupOffset+member),0xffffffffu,row)!=0xffffffffu){r7Error(4u);return;}
            for(uint k=0u;k<3u;k++)r7Put(offsets.x/4u+row*4u+k,floatBitsToUint(p[k]));r7Put(offsets.x/4u+row*4u+3u,member);r7Put(offsets.w/4u+row,i);
            float bound=move+max(move,budget*uDt);if(isnan(bound)||isinf(bound)){r7Error(8u);return;}imageAtomicMax(packet,int(lookupOffset+262144u),floatBitsToUint(bound));return;}
          if(!dome||!known)return;uint point=r7Get(lookupOffset+member);if(point>=r7Get(4u)||point>=pointCapacity||r7Get(offsets.w/4u+point)!=i){r7Error(16u);return;}
          float neighbor=max(CMI_STORM_INTERCEPT,uintBitsToFloat(cmiStarWord(3u))),other=uintBitsToFloat(r7Get(lookupOffset+262144u));
          // Triangle inequalities cover both the solve distance gate and every
          // possible apply swept hit. Positions remain immutable until apply.
          float radius=max(CMI_BODY_RADIUS+(budget+neighbor)*uDt+move,CMI_BODY_RADIUS+budget*uDt+other);
          radius=uintBitsToFloat(floatBitsToUint(radius+max(.0001,radius*.00001))+4u);if(isnan(radius)||isinf(radius)||radius<=0.){r7Error(32u);return;}
          uint row=imageAtomicAdd(packet,5,1u);if(row>=queryCapacity){r7Error(64u);return;}for(uint k=0u;k<3u;k++)r7Put(offsets.y/4u+row*4u+k,floatBitsToUint(old[k]));r7Put(offsets.y/4u+row*4u+3u,floatBitsToUint(radius));r7Put(offsets.z/4u+row,point);r7Put(lookupOffset+131072u+member,row);imageAtomicMax(packet,7,floatBitsToUint(radius));
        }
        """;
    public static Variant build(String source){
        source=source.replace("\r\n","\n");int main=unique(source,"void main(){");String globals=source.substring(0,main);
        if(!globals.contains("layout(local_size_x=64) in;"))throw new IllegalArgumentException("Dome workgroup contract changed");
        String prepare=globals.replace("layout(local_size_x=64) in;","layout(local_size_x=256) in;")+PACKET;
        String consumer=replaceLoop(source,"  for(int x=-cells;",true);consumer=replaceLoop(consumer," for(int x=0;x<=cells*2&&!exhausted;",false);
        String pivot=" if(uMode==3u){";int at=unique(consumer,pivot);consumer=consumer.substring(0,at)+" uint r7From,r7To,r7Ids;bool r7Valid=r7DomeRange(member,r7From,r7To,r7Ids);\n"+consumer.substring(at);
        consumer=consumer.replace("float gatherWorst=0.;","uint r7GatherMember=0xffffffffu,r7BlockingMember=0xffffffffu;float gatherWorst=0.;")
            .replace("if(amount>gatherWorst){gatherWorst=amount;","if(amount>gatherWorst||(amount==gatherWorst&&amount>0.&&otherMember<r7GatherMember)){r7GatherMember=otherMember;gatherWorst=amount;")
            .replace("if(penalty>blockingPenalty){blockingPenalty=penalty;","if(penalty>blockingPenalty||(penalty==blockingPenalty&&penalty>0.&&otherMember<r7BlockingMember)){r7BlockingMember=otherMember;blockingPenalty=penalty;")
            .replace("uint(visits)|","min(uint(visits),65535u)|");
        int where=unique(consumer,"void main(){");consumer=consumer.substring(0,where)+RESULT+consumer.substring(where);
        return new Variant(consumer,prepare);
    }
}
