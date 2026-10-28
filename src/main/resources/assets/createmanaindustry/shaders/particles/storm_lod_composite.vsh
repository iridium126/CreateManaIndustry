out vec2 texCoord;
void main(){texCoord=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(texCoord*2.-1.,0,1);}
