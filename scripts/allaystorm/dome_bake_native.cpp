// Optional offline accelerator. Never loaded by Minecraft or packaged in the mod.
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>
struct V {double x,y,z; V operator+(V b)const{return{x+b.x,y+b.y,z+b.z};} V operator-(V b)const{return{x-b.x,y-b.y,z-b.z};} V operator*(double s)const{return{x*s,y*s,z*s};}};
extern "C" __declspec(dllexport) int dome_bake_abi(){return 2;}
static double dot(V a,V b){return a.x*b.x+a.y*b.y+a.z*b.z;}
static double length(V a){return std::sqrt(dot(a,a));}
static V unit(V a){return a*(1/std::max(length(a),1e-9));}
static V cross(V a,V b){return{a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x};}
extern "C" __declspec(dllexport) double dome_step(int n,int pairCount,const int64_t* pairs,const V* old,const V* velocity,V* desired,V* q,const double* floor,double dt,double clearance){
 std::vector<V> normal(pairCount),change(n);std::vector<double> bound(pairCount),error(pairCount),worst(n);std::vector<int> choice(n);
 for(int p=0;p<pairCount;p++){int a=int(pairs[p*2]),b=int(pairs[p*2+1]);V d=old[a]-old[b];double distance=length(d);normal[p]=unit(d);bound[p]=-(distance-.58)/.18;}
 for(int iteration=0;iteration<48;iteration++){
  std::fill(worst.begin(),worst.end(),.001);std::fill(choice.begin(),choice.end(),-1);bool bad=false;
  for(int i=0;i<n;i++){double e=-(old[i].y-floor[i])/.12-desired[i].y;if(e>worst[i]){worst[i]=e;choice[i]=-2;bad=true;}}
  for(int p=0;p<pairCount;p++){int a=int(pairs[p*2]),b=int(pairs[p*2+1]);double e=bound[p]-dot(desired[a]-desired[b],normal[p]);error[p]=e;
   if(e>worst[a]){worst[a]=e;choice[a]=p;bad=true;}if(e>worst[b]){worst[b]=e;choice[b]=p;bad=true;}}
  if(!bad)break;
  for(int i=0;i<n;i++){int p=choice[i];if(p==-1)continue;V out=p==-2?V{0,1,0}:normal[p]*(pairs[p*2]==i?1.:-1.);double radial=dot(desired[i],out);V tangent=desired[i]-out*radial;
   if(length(tangent)<.00001)tangent=cross(out,std::abs(out.y)<.8?V{0,1,0}:V{1,0,0});
   radial=std::clamp(radial+(p==-2?worst[i]*1.01:error[p]*.55),-5.999,5.999);desired[i]=out*radial+unit(tangent)*std::sqrt(36-radial*radial);}
 }
 auto project=[&](int i,V target){
  V direction=unit(target-old[i]);if(length(direction)<.001)direction=length(velocity[i])>.001?unit(velocity[i]):V{1,0,0};double lower=std::clamp((floor[i]-old[i].y)/(6*dt),-1.,1.);
  if(direction.y<lower){V horizontal{direction.x,0,direction.z};horizontal=length(horizontal)>.000001?unit(horizontal):V{1,0,0};direction=horizontal*std::sqrt(1-lower*lower)+V{0,lower,0};}
  return old[i]+direction*(6*dt);
 };
 for(int i=0;i<n;i++)q[i]=project(i,old[i]+desired[i]*dt);
 for(int iteration=0;iteration<32;iteration++){
  std::fill(change.begin(),change.end(),V{0,0,0});bool bad=false;
  for(int p=0;p<pairCount;p++){int a=int(pairs[p*2]),b=int(pairs[p*2+1]);V d=q[a]-q[b];double distance=length(d);if(distance>=clearance)continue;
   V correction=d*((clearance+.000001-distance)/(2*std::max(distance,1e-9)));change[a]=change[a]+correction;change[b]=change[b]-correction;bad=true;}
  if(!bad)break;
  for(int i=0;i<n;i++)q[i]=project(i,q[i]+change[i]*.65);
 }
 double minimum=100;for(int p=0;p<pairCount;p++)minimum=std::min(minimum,length(q[int(pairs[p*2])]-q[int(pairs[p*2+1])]));
 return minimum;
}
