// One predicted contact, solved analytically inside the six-metre speed ball.
// Internal Dome spacing is baked; a per-thread array/iterative multi-plane
// solve would consume registers even in the ordinary application/audit passes.
vec3 cmiDomeContactVelocity(vec3 preferred,vec4 plane,out bool feasible){
 float bound=plane.w;feasible=bound<=6.;if(!feasible)return vec3(0);
 if(dot(plane.xyz,preferred)>=bound)return preferred;
 vec3 tangent=preferred-plane.xyz*dot(plane.xyz,preferred);float radius=sqrt(max(0.,36.-bound*bound)),speed=length(tangent);
 return plane.xyz*bound+(speed>radius?tangent*(radius/max(speed,.000001)):tangent);
}
