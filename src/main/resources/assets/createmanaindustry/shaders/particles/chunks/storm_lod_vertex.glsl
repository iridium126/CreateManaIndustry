// Shared between the standalone OPAQUE path and the native pack entity input.
// Positions in the render-only records are camera-relative; geometry/UVs remain project assets.
const vec3 CMI_LOD_NORMALS[6]=vec3[6](vec3(1,0,0),vec3(-1,0,0),vec3(0,1,0),vec3(0,-1,0),vec3(0,0,1),vec3(0,0,-1));
uniform int cmi_LodPass;
uniform mat4 cmi_LodInverseView;
void cmiLodVertex(vec4 position,vec4 motion,vec3 local,vec2 sourceUv,int part,int normalCode,
        out vec3 vertex,out vec3 normal,out vec2 uv){
    if(cmi_LodPass==2){
        // One opaque square per member. Constant UV samples a cyan allay skin texel.
        vec2 corner=vec2((gl_VertexID==1||gl_VertexID==2||gl_VertexID==4)?1.:-1.,
                         (gl_VertexID==2||gl_VertexID==4||gl_VertexID==5)?1.:-1.);
        vec3 centre=position.xyz+vec3(0,.3,0);
        // Record w is the world-space half size: .3 makes a .6 x .6 block square.
        // Perspective alone determines its screen coverage, including subpixel sizes.
        vertex=centre+cmi_LodInverseView[0].xyz*(corner.x*position.w)+cmi_LodInverseView[1].xyz*(corner.y*position.w);
        normal=cmi_LodInverseView[2].xyz;
        uv=vec2(7.5/128.,12.5/64.);
        return;
    }
    vec3 pivot=part==0?vec3(0,19.51,0):vec3(0,19.5,0);
    if(part==2)pivot+=vec3(-1.75,.5,0);
    if(part==3)pivot+=vec3(1.75,.5,0);
    vec3 p=(local+pivot)/16.;
    p=vec3(-p.x,1.501-p.y,p.z)*(.6/MODEL_ABOVE_FEET);
    vec3 n=CMI_LOD_NORMALS[normalCode]*vec3(-1,-1,1);
    float yaw=dot(motion.xz,motion.xz)>.0001?atan(motion.x,-motion.z):motion.w*6.2831853;
    float a=3.14159265-yaw,c=cos(a),s=sin(a);
    vertex=position.xyz+vec3(c*p.x+s*p.z,p.y,-s*p.x+c*p.z);
    normal=vec3(c*n.x+s*n.z,n.y,-s*n.x+c*n.z);
    uv=sourceUv;
}
