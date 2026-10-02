// Minkowski-expanded AABB sweep. Initial overlap is resolved by Jacobi.
bool sweepBox(vec3 p,vec3 motion,vec3 lo,vec3 hi,out float time,out vec3 normal) {
    float enter=-1e30,leave=1e30;normal=vec3(0);
    for(int axis=0;axis<3;axis++) {
        if(abs(motion[axis])<1e-10) {
            if(p[axis]<lo[axis] || p[axis]>hi[axis])return false;
        } else {
            float a=(lo[axis]-p[axis])/motion[axis],b=(hi[axis]-p[axis])/motion[axis];
            float near=min(a,b),far=max(a,b);
            if(near>enter){enter=near;normal=vec3(0);normal[axis]=-sign(motion[axis]);}
            leave=min(leave,far);
        }
    }
    time=enter;return enter>=0.0 && enter<=1.0 && enter<=leave && leave>=0.0;
}
