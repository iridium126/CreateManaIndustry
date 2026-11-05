struct CmiColumnCurve {float radius,root,angle,endAngle,height,endHeight,radial,angular,vertical,arcWarp;};
CmiColumnCurve cmiColumnCurveParameters(uint arc,uint b,uint rank){
 vec3 start=cmiColumnState3(arc+CMI_COLUMN_ORIGIN)-cmiStarLanding(b,0u).xyz,direction=cmiColumnState3(arc+CMI_COLUMN_DIRECTION);vec4 t=cmiStarVec(b+4u);
 float radius=max(length(start.xz),.2),root=cmiColumnRoot(rank),angle=atan(start.z,start.x),endAngle=angle+.7*max(t.z+cmiColumnDelay(rank)-t.y,.1);
 endAngle+=cmiColumnWrap(cmiColumnAngle(b,rank)-endAngle);
 vec2 radial=vec2(cos(angle),sin(angle));
 return CmiColumnCurve(radius,root,angle,endAngle,start.y,cmiColumnHeight(root),clamp(dot(direction.xz,radial)*4.,-.25*radius,.25*radius),dot(direction.xz,vec2(-radial.y,radial.x))*4./radius,direction.y*4.,mix(.1,.2,smoothstep(1.,4.,max(0.,start.y)/radius)));
}
// Monotone integrated P4 grid concentrates knots where the speed changes.
// Geometry is unchanged; all 128 chord samples and the inverse share this grid.
float cmiColumnArcGrid(float t,float strength){return t+strength*t*(1.-t)*(1.-2.*t)*(1.-7.*t+7.*t*t);}
vec3 cmiColumnCurvePoint(CmiColumnCurve p,float u){
 float h0=u*(1.-u)*(1.-u),h1=u*u*(u-1.),blend=cmiColumnSmooth(u);
 float radius=max(.2,mix(p.radius,p.root,blend)+p.radial*h0+.35*h1),angle=mix(p.angle,p.endAngle,blend)+p.angular*h0;
 return vec3(cos(angle)*radius,mix(p.height,p.endHeight,blend)+p.vertical*h0+.35*p.root*.125*h1,sin(angle)*radius);
}
float cmiColumnParameter(uint arc,float distanceArc,float warp){
 if(distanceArc<=0.)return 0.;float total=cmiColumnState(cmiColumnArcNode(arc,CMI_COLUMN_ARC_NODES-1u));if(distanceArc>=total)return 1.;
 uint lo=0u,hi=CMI_COLUMN_ARC_NODES-1u;for(int i=0;i<6;i++){uint mid=(lo+hi)/2u;if(cmiColumnState(cmiColumnArcNode(arc,mid))<distanceArc)lo=mid;else hi=mid;}
 float start=cmiColumnState(cmiColumnArcNode(arc,lo)),delta=max(cmiColumnState(cmiColumnArcNode(arc,hi))-start,.000001);
 return mix(cmiColumnArcGrid(float(lo)/64.,warp),cmiColumnArcGrid(float(hi)/64.,warp),clamp((distanceArc-start)/delta,0.,1.));
}
