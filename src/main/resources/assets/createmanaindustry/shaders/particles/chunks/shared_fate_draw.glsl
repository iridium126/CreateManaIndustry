// Binding 13 is the boid grid during compute and the committed ribbon nodes during billboard draws.
layout(std430,binding=13) readonly buffer FateRibbonNodes{vec4 data[];} fateRibbonNodes;
void cmiDrawFateRibbon(vec4 record,bool halo,vec2 corner){
    uint node=corner.x<.5?floatBitsToUint(record.x):floatBitsToUint(record.y),base=node*4u;
    if(base+3u>=uint(fateRibbonNodes.data.length())){gl_Position=vec4(0,0,2,1);vColor=vec3(0);vAlpha=0;vDist=0;vUv=vec2(0);vHex=2;return;}
    vec4 pose=fateRibbonNodes.data[base],side=fateRibbonNodes.data[base+1u];
    vec4 color=fateRibbonNodes.data[base+2u],detail=fateRibbonNodes.data[base+3u];
    vec3 p=pose.xyz+side.xyz*(corner.y-.5)*(halo?side.w:pose.w);
    gl_Position=ProjMat*ModelViewMat*vec4(p,1);
    vUv=vec2(detail.y,corner.y);vColor=color.rgb;vAlpha=halo?detail.x:color.w;
    vDist=length(p);vHex=2.0;
}
