#ifndef CMI_HEX_TICK
#define CMI_HEX_TICK
uniform bool uHexTickReady;
uniform uint uHexCurrentTick;
void advanceHexTicks(inout vec3 position,inout vec3 velocity,bool gravity,int ticks){
    for(int tick=0;tick<ticks;tick++){
        if(gravity)velocity.y+=.008;
        bool blockedY=double(position.y)==double(position.y)+double(velocity.y)/20.lf;
        position+=velocity*.05;
        if(blockedY)velocity.xz*=1.1;
        velocity*=.96;
    }
}
#endif
