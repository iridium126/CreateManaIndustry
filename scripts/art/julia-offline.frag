#version 330 core
uniform vec2 resolution;
uniform vec4 juliaC;
uniform int degree;
uniform int iterations;
uniform int steps;
uniform float sliceW;
uniform float epsilon;
uniform float viewScale;
uniform vec3 eye;
uniform vec3 upHint;
uniform float roll;
uniform int silhouette;
uniform int perspective;
uniform mat4 viewRayTransform;
out vec4 fragColor;

// Quaternion order: (real, i, j, k). The 3D affine slice is q0=(x,y,z,w).
vec4 squareQ(vec4 q) { return vec4(q.x*q.x-dot(q.yzw,q.yzw),2.0*q.x*q.yzw); }
float estimate(vec3 p) {
    vec4 q=vec4(p,sliceW);
    float derivativeBound=1.0;
    float radius=length(q);
    bool escaped=false;
    for(int i=0;i<192;i++) {
        if(i>=iterations)break;
        radius=length(q);
        if(radius>16.0){escaped=true;break;}
        derivativeBound*=degree==2?2.0*max(radius,1e-12):4.0*max(radius*radius*radius,1e-12);
        derivativeBound=clamp(derivativeBound,1e-24,1e30);
        q=squareQ(q);
        if(degree==4)q=squareQ(q);
        q+=juliaC;
    }
    if(!escaped)return 0.0;
    return max(0.0,0.5*radius*log(radius)/derivativeBound);
}
bool insideFinite(vec3 p) {
    vec4 q=vec4(p,sliceW);
    for(int i=0;i<192;i++) {
        if(i>=iterations)break;
        if(dot(q,q)>256.0)return false;
        q=squareQ(q);if(degree==4)q=squareQ(q);q+=juliaC;
    }
    return dot(q,q)<=256.0;
}
vec3 normalAt(vec3 p,vec3 fallback) {
    float e=epsilon*2.5;
    vec3 dx=vec3(e,0,0),dy=vec3(0,e,0),dz=vec3(0,0,e);
    vec3 gradient=vec3(estimate(p+dx)-estimate(p-dx),estimate(p+dy)-estimate(p-dy),estimate(p+dz)-estimate(p-dz));
    return dot(gradient,gradient)<1e-18?fallback:normalize(gradient);
}
void main() {
    vec3 forward=normalize(-eye),right=normalize(cross(forward,upHint)),up=cross(right,forward);
    vec2 uv=(2.0*gl_FragCoord.xy-resolution)/resolution.y;
    uv=mat2(cos(roll),-sin(roll),sin(roll),cos(roll))*uv;
    vec3 ro=perspective!=0?eye:eye+(right*uv.x+up*uv.y)*viewScale;
    vec3 rd=perspective!=0?normalize(forward+(right*uv.x+up*uv.y)*viewScale):forward;
    if(perspective==2) {
        vec3 screenRay=normalize((viewRayTransform*vec4(uv,1.0,1.0)).xyz);
        float incidence=dot(screenRay,forward);
        if(incidence<=0.0){fragColor=vec4(0);return;}
        // Independent original-orbit oracle: parallel rays from the centre's tangent image plane.
        ro=eye+(eye+screenRay*(length(eye)/incidence));
        rd=forward;
    }
    // Acceleration sphere encloses all candidates; it never supplies a visible surface or clips the set.
    float b=dot(ro,rd),c=dot(ro,ro)-6.25,disc=b*b-c;
    if(disc<0.0){fragColor=vec4(0);return;}
    float t=max(0.0,-b-sqrt(disc)),far=-b+sqrt(disc);
    bool hit=false;
    for(int i=0;i<1200;i++) {
        if(i>=steps||t>far)break;
        float distance=estimate(ro+t*rd);
        if(distance<epsilon){hit=true;break;}
        t+=max(epsilon*.18,distance*.45);
    }
    if(!hit){fragColor=vec4(0);return;}
    vec3 p=ro+t*rd;
    if(silhouette!=0){fragColor=vec4(1);return;}
    vec3 n=normalAt(p,-rd);
    vec3 light=normalize(vec3(-.6,.9,1.5));
    float diffuse=max(0.0,dot(n,light));
    float rim=pow(1.0-max(0.0,dot(n,-rd)),2.0);
    float ao=1.0;
    for(int j=1;j<=4;j++) {
        float offset=float(j)*.022;
        ao-=max(0.0,offset-estimate(p+n*offset))*.8/float(j);
    }
    ao=clamp(ao,.36,1.0);
    float spec=pow(max(0.0,dot(n,normalize(light-rd))),38.0);
    vec3 base=mix(vec3(.018,.11,.135),vec3(.10,.40,.44),.5+.5*n.y);
    vec3 color=base*(.19+.98*diffuse)*ao+vec3(.18,.55,.59)*rim*.18+vec3(.7,.9,.89)*spec*.32;
    fragColor=vec4(pow(max(color,vec3(0)),vec3(1.0/2.2)),1);
}
