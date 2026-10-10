#pragma cmi_include chunks/hex_shared_vertex.glsl
uniform mat4 ModelViewMat,ProjMat;
out vec4 vertexColor;
void main(){cmiHexVertex();gl_Position=ProjMat*ModelViewMat*cmi_VertexLevel;vertexColor=cmi_Tint;}
