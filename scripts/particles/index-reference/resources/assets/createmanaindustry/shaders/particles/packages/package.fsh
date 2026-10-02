uniform sampler2D uAtlas;
in vec2 vUv;
in vec4 vColor;
flat in uint vCutout;
out vec4 fragColor;
void main() {
    vec4 color=texture(uAtlas,vUv)*vColor;
    if(vCutout!=0u && color.a<.1)discard;
    fragColor=color;
}
