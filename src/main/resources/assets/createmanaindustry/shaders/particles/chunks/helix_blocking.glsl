bool cmiHelixBlocked(inout vec4 window,bool clipped,float dt,float expected,float progress){
    if(window.x<=0.&&!clipped)return false;
    window+=vec4(dt,clipped?dt:0.,max(0.,expected),progress);
    if(window.x<.5)return false;
    bool blocked=window.y>=.1&&window.z>=.05&&window.w<window.z*.1;
    window=vec4(0);
    return blocked;
}
