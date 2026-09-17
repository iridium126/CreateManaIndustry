using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Security.Cryptography;

// Opt-in continuous reconstruction for the redwood maps. The structural grammar
// is untouched; all variation is keyed by global position, never tile-local XYZ.
static class RedwoodRefinement
{
    static uint Hash(int x, int y, int z, int seed)
    {
        unchecked {
            uint h = (uint)seed ^ (uint)x * 0x9E3779B9u ^ (uint)y * 0x85EBCA6Bu ^ (uint)z * 0xC2B2AE35u;
            h ^= h >> 16; h *= 0x7FEB352Du; h ^= h >> 15; h *= 0x846CA68Bu; return h ^ (h >> 16);
        }
    }
    static float Lerp(float a, float b, float t) => a + (b - a) * t;
    static float Noise(float x, float y, float z, int seed)
    {
        int ix = (int)MathF.Floor(x), iy = (int)MathF.Floor(y), iz = (int)MathF.Floor(z);
        float fx = x - ix, fy = y - iy, fz = z - iz;
        fx *= fx * (3 - 2 * fx); fy *= fy * (3 - 2 * fy); fz *= fz * (3 - 2 * fz);
        float H(int a, int b, int c) => (Hash(a, b, c, seed) & 0xffffff) / 16777215f;
        return Lerp(Lerp(Lerp(H(ix,iy,iz),H(ix+1,iy,iz),fx),Lerp(H(ix,iy+1,iz),H(ix+1,iy+1,iz),fx),fy),
                    Lerp(Lerp(H(ix,iy,iz+1),H(ix+1,iy,iz+1),fx),Lerp(H(ix,iy+1,iz+1),H(ix+1,iy+1,iz+1),fx),fy),fz);
    }
    public static void Apply(Grid source, Grid target, int stage, int seed)
    {
        var timer = Stopwatch.StartNew();
        Console.WriteLine($"redwood detail stage {stage}: source {Convert.ToHexString(SHA256.HashData(source.state)).ToLowerInvariant()}");
        int sx = target.MX / source.MX, sy = target.MY / source.MY, sz = target.MZ / source.MZ;
        var family = new byte[source.C];
        for (int c=0;c<source.C;c++) family[c] = (byte)("DNnMt".Contains(source.characters[c]) ? 1 : "GEgH".Contains(source.characters[c]) ? 2 : "JVKLF".Contains(source.characters[c]) ? 3 : 0);
        byte dark=target.values['D'], bark=target.values['N'], light=target.values['n'];
        byte leaf=target.values['G'], shade=target.values['E'], sun=target.values['g'];
        byte vine=target.values['J'], vineLeaf=target.values['V'];
        byte moss=target.values.TryGetValue('M',out byte m) ? m : dark;
        bool[] active = new bool[source.state.Length];
        int plane=source.MX*source.MY;
        for(int z=0;z<source.MZ;z++) for(int y=0;y<source.MY;y++) for(int x=0;x<source.MX;x++)
        {
            if(source.state[x+y*source.MX+z*plane]==0) continue;
            for(int dz=-1;dz<=1;dz++) for(int dy=-1;dy<=1;dy++) for(int dx=-1;dx<=1;dx++)
            {
                int a=x+dx,b=y+dy,c=z+dz;
                if(a>=0&&b>=0&&c>=0&&a<source.MX&&b<source.MY&&c<source.MZ) active[a+b*source.MX+c*plane]=true;
            }
        }
        var botany = stage == 2 ? new RedwoodBotany(target.MX,target.MY,target.MZ,seed) : null;
        int worldStep=stage==1 ? 4 : 1;
        long retained=0;
        for(int pz=0;pz<source.MZ;pz++) for(int py=0;py<source.MY;py++) for(int px=0;px<source.MX;px++)
        {
            int parent=px+py*source.MX+pz*plane;
            if(!active[parent]) continue;
            for(int dz=0;dz<sz;dz++) for(int dy=0;dy<sy;dy++) for(int dx=0;dx<sx;dx++)
            {
                int x=px*sx+dx,y=py*sy+dy,z=pz*sz+dz;
                int index=x+y*target.MX+z*target.MX*target.MY;
                byte old=target.state[index];
                float wx=(x+.5f)*worldStep,wy=(y+.5f)*worldStep,wz=(z+.5f)*worldStep;
                float broad=Noise(wx/17f,wy/17f,wz/37f,seed);
                float ridge=Noise(wx/5f,wy/5f,wz/96f,seed+19);
                float fine=Noise(wx/2.7f,wy/2.7f,wz/3.9f,seed+43);
                float warp=stage==1 ? .14f : .30f;
                float ax=(x+.5f)/sx-.5f+(broad-.5f)*warp;
                float ay=(y+.5f)/sy-.5f+(ridge-.5f)*warp;
                float az=(z+.5f)/sz-.5f+(fine-.5f)*(stage==1 ? .07f : .18f);
                int ix=(int)MathF.Floor(ax),iy=(int)MathF.Floor(ay),iz=(int)MathF.Floor(az);
                float fx=ax-ix,fy=ay-iy,fz=az-iz,wood=0,foliage=0,liana=0;
                for(int c=0;c<2;c++) for(int b=0;b<2;b++) for(int a=0;a<2;a++)
                {
                    int xx=ix+a,yy=iy+b,zz=iz+c;
                    if(xx<0||yy<0||zz<0||xx>=source.MX||yy>=source.MY||zz>=source.MZ) continue;
                    float weight=(a==0?1-fx:fx)*(b==0?1-fy:fy)*(c==0?1-fz:fz);
                    byte type=family[source.state[xx+yy*source.MX+zz*plane]];
                    if(type==1) wood+=weight; else if(type==2) foliage+=weight; else if(type==3) liana+=weight;
                }
                float density=wood+foliage+liana;
                int kind=wood>=foliage&&wood>=liana?1:foliage>=liana?2:3;
                float threshold=kind==1 ? .39f+(ridge-.5f)*(stage==2?.42f:.16f) : kind==2 ? .43f+(fine-.5f)*(stage==2?.72f:.32f)+(broad-.5f)*.16f : .30f+(fine-.5f)*.12f;
                bool occupied=density>=threshold;
                if(stage==2 && kind==2 && density<.97f && fine>.72f+broad*.08f) occupied=false;
                // Preserve the root contact and terminal shoot's original occupied height.
                if((z==0||z==target.MZ-1)&&old!=0) { occupied=true; kind=family[source.state[parent]]; }
                if(!occupied) { target.state[index]=0; continue; }
                byte color;
                if(kind==1)
                {
                    float grain=.70f*ridge+.30f*fine;
                    color=grain<.38f?dark:grain>.64f?light:bark;
                    if(stage==2&&density<.72f&&broad>.68f&&ridge<.51f&&wz<2450) color=moss;
                }
                else if(kind==2) color=(broad*.65f+fine*.35f)<.39f?shade:(broad*.65f+fine*.35f)>.62f?sun:leaf;
                else color=fine>.62f?vineLeaf:vine;
                if(stage==1 && kind==1 && old==14) color=14;
                if(stage==2 && z>0 && z<target.MZ-1) {
                    if(kind==2) {
                        color=botany.needle(wx,wy,wz,broad*.65f+fine*.35f);
                        float tipX=wx-target.MX*.5f, tipY=wy-target.MY*.5f;
                        if(wz>target.MZ-256 && tipX*tipX+tipY*tipY<2.5f) color=14;
                    }
                    else if(kind==3) color=botany.vine(wx,wy,wz);
                }
                target.state[index]=color; if(color!=0) retained++;
            }
        }
        Console.WriteLine($"redwood detail stage {stage}: {retained} botanical voxels, {timer.ElapsedMilliseconds} ms");
    }
}
