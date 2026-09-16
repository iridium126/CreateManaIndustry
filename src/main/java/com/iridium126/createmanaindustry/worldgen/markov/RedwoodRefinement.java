package com.iridium126.createmanaindustry.worldgen.markov;

import java.util.BitSet;

/** Float-for-float port of the reference project's opt-in RedwoodRefinement.cs. */
final class RedwoodRefinement {
    private static int hash(int x, int y, int z, int seed) {
        int h = seed ^ x * 0x9E3779B9 ^ y * 0x85EBCA6B ^ z * 0xC2B2AE35;
        h ^= h >>> 16; h *= 0x7FEB352D; h ^= h >>> 15; h *= 0x846CA68B; return h ^ (h >>> 16);
    }
    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    private static float h(int x, int y, int z, int seed) { return (hash(x,y,z,seed) & 0xffffff) / 16777215f; }
    private static float Noise(float x, float y, float z, int seed) {
        int ix=(int)Math.floor(x), iy=(int)Math.floor(y), iz=(int)Math.floor(z);
        float fx=x-ix, fy=y-iy, fz=z-iz;
        fx *= fx*(3-2*fx); fy *= fy*(3-2*fy); fz *= fz*(3-2*fz);
        return lerp(lerp(lerp(h(ix,iy,iz,seed),h(ix+1,iy,iz,seed),fx),lerp(h(ix,iy+1,iz,seed),h(ix+1,iy+1,iz,seed),fx),fy),
                    lerp(lerp(h(ix,iy,iz+1,seed),h(ix+1,iy,iz+1,seed),fx),lerp(h(ix,iy+1,iz+1,seed),h(ix+1,iy+1,iz+1,seed),fx),fy),fz);
    }
    static RedwoodVolume apply(RedwoodVolume source, String values, int stage, int seed) {
        int sx=stage==1?2:4, sy=sx, sz=4;
        RedwoodVolume target=new RedwoodVolume(source.x*sx,source.y*sy,source.z*sz);
        byte[] family=new byte[values.length()];
        for(int i=0;i<family.length;i++) family[i]=(byte)("DNnM".indexOf(values.charAt(i))>=0?1:"GEg".indexOf(values.charAt(i))>=0?2:"JV".indexOf(values.charAt(i))>=0?3:0);
        // Both map stages have precisely this target palette (validated by EpicRedwoodModel).
        byte dark=1,bark=2,light=3,leaf=4,shade=5,sun=6,vine=7,vineLeaf=8,moss=9;
        BitSet active=new BitSet(source.x*source.y*source.z);
        int plane=source.x*source.y;
        for(int z=0;z<source.z;z++) for(int y=0;y<source.y;y++) for(int x=0;x<source.x;x++) {
            if(source.get(x,y,z)==0) continue;
            for(int dz=-1;dz<=1;dz++) for(int dy=-1;dy<=1;dy++) for(int dx=-1;dx<=1;dx++) {
                int a=x+dx,b=y+dy,c=z+dz;
                if(a>=0&&b>=0&&c>=0&&a<source.x&&b<source.y&&c<source.z) active.set(a+b*source.x+c*plane);
            }
        }
        int worldStep=stage==1?4:1;
        for(int parent=active.nextSetBit(0);parent>=0;parent=active.nextSetBit(parent+1)) {
            int px=parent%source.x,py=parent/source.x%source.y,pz=parent/plane;
            byte parentValue=source.get(px,py,pz);
            byte old=(byte)(parentValue==0?0:EpicRedwoodModel.VALUES.indexOf(values.charAt(parentValue)));
            for(int dz=0;dz<sz;dz++) for(int dy=0;dy<sy;dy++) for(int dx=0;dx<sx;dx++) {
                int x=px*sx+dx,y=py*sy+dy,z=pz*sz+dz;
                float wx=(x+.5f)*worldStep,wy=(y+.5f)*worldStep,wz=(z+.5f)*worldStep;
                float broad=Noise(wx/17f,wy/17f,wz/37f,seed);
                float ridge=Noise(wx/5f,wy/5f,wz/96f,seed+19);
                float fine=Noise(wx/2.7f,wy/2.7f,wz/3.9f,seed+43);
                float warp=stage==1 ? .14f : .30f;
                float ax=(x+.5f)/sx-.5f+(broad-.5f)*warp;
                float ay=(y+.5f)/sy-.5f+(ridge-.5f)*warp;
                float az=(z+.5f)/sz-.5f+(fine-.5f)*(stage==1 ? .07f : .18f);
                int ix=(int)Math.floor(ax),iy=(int)Math.floor(ay),iz=(int)Math.floor(az);
                float fx=ax-ix,fy=ay-iy,fz=az-iz,wood=0,foliage=0,liana=0;
                for(int c=0;c<2;c++) for(int b=0;b<2;b++) for(int a=0;a<2;a++)
                {
                    int xx=ix+a,yy=iy+b,zz=iz+c;
                    if(xx<0||yy<0||zz<0||xx>=source.x||yy>=source.y||zz>=source.z) continue;
                    float weight=(a==0?1-fx:fx)*(b==0?1-fy:fy)*(c==0?1-fz:fz);
                    byte type=family[source.get(xx,yy,zz)];
                    if(type==1) wood+=weight; else if(type==2) foliage+=weight; else if(type==3) liana+=weight;
                }
                float density=wood+foliage+liana;
                int kind=wood>=foliage&&wood>=liana?1:foliage>=liana?2:3;
                float threshold=kind==1 ? .39f+(ridge-.5f)*(stage==2?.42f:.16f) : kind==2 ? .43f+(fine-.5f)*(stage==2?.72f:.32f)+(broad-.5f)*.16f : .30f+(fine-.5f)*.12f;
                boolean occupied=density>=threshold;
                if(stage==2 && kind==2 && density<.97f && fine>.72f+broad*.08f) occupied=false;
                // Preserve the root contact and terminal shoot's original occupied height.
                if((z==0||z==target.z-1)&&old!=0) { occupied=true; kind=family[parentValue]; }
                if(!occupied) { target.set(x,y,z,(byte)0); continue; }
                byte color;
                if(kind==1)
                {
                    float grain=.70f*ridge+.30f*fine;
                    color=grain<.38f?dark:grain>.64f?light:bark;
                    if(stage==2&&density<.72f&&broad>.68f&&ridge<.51f&&wz<2450) color=moss;
                }
                else if(kind==2) color=(broad*.65f+fine*.35f)<.39f?shade:(broad*.65f+fine*.35f)>.62f?sun:leaf;
                else color=fine>.62f?vineLeaf:vine;
                target.set(x,y,z,color);
            }
        }
        target.retainRoot();
        return target;
    }
}
