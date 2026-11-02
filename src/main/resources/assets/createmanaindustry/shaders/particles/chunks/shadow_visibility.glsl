// Light points towards the receiver, not towards the sun. t>=0 keeps off-screen casters.
uniform bool uShadowViewEnabled,uShadowMemberView;
uniform vec4 uShadowViewPlanes[6];
uniform vec3 uShadowRay;
bool cmiShadowBoxInView(vec3 center,vec3 extent){
    float begin=0.,end=1e20;
    for(int j=0;j<6;j++){
        vec4 plane=uShadowViewPlanes[j];
        float support=dot(plane.xyz,center)+plane.w+dot(abs(plane.xyz),extent);
        float direction=dot(plane.xyz,uShadowRay);
        if(abs(direction)<1e-7){if(support<0.)return false;}
        else if(direction>0.)begin=max(begin,-support/direction);
        else end=min(end,-support/direction);
        if(begin>end)return false;
    }
    return true;
}
bool cmiShadowCylinderInView(vec3 center,float radius,float halfHeight){
    float begin=0.,end=1e20;
    for(int j=0;j<6;j++){
        vec4 plane=uShadowViewPlanes[j];
        float support=dot(plane.xyz,center)+plane.w+length(plane.xz)*radius+abs(plane.y)*halfHeight;
        float direction=dot(plane.xyz,uShadowRay);
        if(abs(direction)<1e-7){if(support<0.)return false;}
        else if(direction>0.)begin=max(begin,-support/direction);
        else end=min(end,-support/direction);
        if(begin>end)return false;
    }
    return true;
}
