// Per-track 96B: render and logical affine rows. Chain bodies remain native-origin local.
struct ChainFrame { vec4 rx; vec4 ry; vec4 rz; vec4 lx; vec4 ly; vec4 lz; };
vec3 chainFramePoint(vec3 p,vec4 x,vec4 y,vec4 z) { return vec3(dot(x.xyz,p)+x.w,dot(y.xyz,p)+y.w,dot(z.xyz,p)+z.w); }
mat3 chainFrameMatrix(vec4 x,vec4 y,vec4 z) { return transpose(mat3(x.xyz,y.xyz,z.xyz)); }
// Orthogonal positive scales: analytic inverse, no general matrix inverse per package.
vec3 chainFrameInverse(vec3 p,vec4 x,vec4 y,vec4 z) {
    mat3 m=chainFrameMatrix(x,y,z);p-=vec3(x.w,y.w,z.w);
    return vec3(dot(p,m[0])/dot(m[0],m[0]),dot(p,m[1])/dot(m[1],m[1]),dot(p,m[2])/dot(m[2],m[2]));
}
