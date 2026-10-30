#ifndef CMI_SURFACE_STATE
#define CMI_SURFACE_STATE
#define CMI_SURFACE_TILE 65536u
#define CMI_SURFACE_MISSING 66560u
#define CMI_SURFACE_DIRTY 68608u
#define CMI_SURFACE_PATCH 68616u
uint cmiSurfaceTile(ivec2 p){return uint((p.x&15)+((p.y&15)<<4));}
uint cmiSurfaceCell(ivec2 p){return uint((p.x&255)+((p.y&255)<<8));}
uint cmiSurfacePack(int y){return 0x80000000u|uint(y+33554432);}
int cmiSurfaceHeight(uint word){return int(word&0x3ffffffu)-33554432;}
#endif
