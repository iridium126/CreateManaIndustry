// Identity-stable antisymmetric direction, including coincident bodies. Keep
// this shared by ordinary storm steering and exceptional Dome contacts.
vec3 cmiStormSeparationDelta(vec3 delta,uint mine,uint other){
    if(dot(delta,delta)>1e-8)return delta;
    float angle=float(min(mine,other))*2.39996323;
    return normalize(vec3(cos(angle),.3,sin(angle)))*.01*(mine<other?1.:-1.);
}
