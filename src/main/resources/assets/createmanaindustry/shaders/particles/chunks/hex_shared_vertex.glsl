uniform samplerBuffer cmi_HexPoints;
uniform samplerBuffer cmi_HexInstances;
vec4 cmi_VertexLevel,cmi_Tint;
vec4 cmi_TexCoord0v,cmi_LightCoordv;
vec3 cmi_NormalLevel;
const float CMI_HEX_PI=3.141592653589793;
vec2 cmiHexUnit(vec2 p){float n=length(p);return n>1e-8?p/n:vec2(0);}
vec2 cmiHexRotate(vec2 p,float a){return vec2(cos(a)*p.x-sin(a)*p.y,sin(a)*p.x+cos(a)*p.y);}
vec4 cmiHexColor(vec4 a,vec4 b,float t){precise vec4 color=a+(b-a)*t;return trunc(color*255.0)/255.0;}
vec4 cmiHexPoint(uint base,uint i){return texelFetch(cmi_HexPoints,int(base+i));}
float cmiHexJoin(uint base,uint i,uint n,float radius){if(i==0u||i+1u>=n)return 0.0;float lo=min(length(cmiHexPoint(base,i).xy-cmiHexPoint(base,i-1u).xy),length(cmiHexPoint(base,i+1u).xy-cmiHexPoint(base,i).xy))/radius;return clamp(cmiHexPoint(base,i).w,-lo,lo);}
void cmiHexVertex(){
    uint slot=uint(gl_VertexID)/262144u,v=uint(gl_VertexID)%262144u;int row=int(slot)*13;
    mat4 transform=mat4(texelFetch(cmi_HexInstances,row),texelFetch(cmi_HexInstances,row+1),texelFetch(cmi_HexInstances,row+2),texelFetch(cmi_HexInstances,row+3));
    uvec4 shape=floatBitsToUint(texelFetch(cmi_HexInstances,row+4)),sizes=floatBitsToUint(texelFetch(cmi_HexInstances,row+5));
    vec4 widths=texelFetch(cmi_HexInstances,row+6);vec2 pos=vec2(0);float z;vec4 color;
    if(v<sizes.x+sizes.y){bool inner=v>=sizes.x;if(inner)v-=sizes.x;
        float radius=inner?widths.y:widths.x;z=inner?widths.w:widths.z;
        vec4 tail=texelFetch(cmi_HexInstances,row+(inner?9:7)),head=texelFetch(cmi_HexInstances,row+(inner?10:8));if(sizes.w==0u)head=tail;
        uint n=shape.y,base=shape.x;
        if(v<(n-1u)*42u){uint i=v/42u,part=v%42u;vec2 a=cmiHexPoint(base,i).xy,b=cmiHexPoint(base,i+1u).xy;
            vec2 tangent=cmiHexUnit(b-a)*radius,normal=vec2(-tangent.y,tangent.x);float progress=float(i)/float(n);
            if(part<12u){float lo=cmiHexJoin(base,i,n,radius),hi=cmiHexJoin(base,i+1u,n,radius);
                vec2 corners[6]=vec2[6](a+tangent*max(0.0,lo)+normal,a,a+tangent*max(0.0,-lo)-normal,b-tangent*max(0.0,-hi)-normal,b,b-tangent*max(0.0,hi)+normal);
                const int tri[12]=int[12](0,1,2,0,2,3,0,3,4,0,4,5);int corner=tri[part];pos=corners[corner];if(corner>=3)progress=float(i+1u)/float(n);
            }else{float angle=cmiHexPoint(base,i).z;uint steps=uint(ceil(abs(angle)*10.0/CMI_HEX_PI)),j=(part-12u)/3u,c=(part-12u)%3u;pos=a;
                if(i>0u&&j<steps&&c!=0u){float f=float(j+c-1u)/float(steps);pos+=cmiHexRotate(angle<0.0?normal:-normal,angle<0.0?-angle*f:-angle*(1.0-f));}}
            color=cmiHexColor(tail,head,progress);
        }else{uint cap=v-(n-1u)*42u,endpoint=cap<30u?0u:n-1u,previous=cap<30u?1u:n-2u,j=(cap%30u)/3u,c=cap%3u;
            vec2 a=cmiHexPoint(base,endpoint).xy,t=cmiHexUnit(a-cmiHexPoint(base,previous).xy)*radius,normal=vec2(-t.y,t.x);pos=a;if(c!=0u)pos+=cmiHexRotate(normal,-CMI_HEX_PI*(1.0-float(j+c-1u)/10.0));color=cap<30u?tail:head;}
    }else{v-=sizes.x+sizes.y;uint node=v/18u,j=(v%18u)/3u,c=v%3u;pos=cmiHexPoint(shape.z,node).xy;float radius=texelFetch(cmi_HexInstances,row+12).x;
        if(c!=0u){float a=float(j+c-1u)/6.0*2.0*CMI_HEX_PI;pos+=radius*vec2(cos(a),sin(a));}z=1.0;color=texelFetch(cmi_HexInstances,row+11);}
    cmi_VertexLevel=transform*vec4(pos,z,1);cmi_Tint=color;cmi_TexCoord0v=vec4(0,0,0,1);cmi_LightCoordv=vec4(240,240,0,1);cmi_NormalLevel=vec3(0,0,1);
}
