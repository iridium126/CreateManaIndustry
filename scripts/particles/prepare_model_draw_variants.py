"""Validation-only ablations. Never generate or replace production sources."""
from pathlib import Path
import hashlib,json
root=Path('.local/particle-baselines/reference/model-draw-20261030')
manifest=json.loads((root/'manifest.json').read_text())
for name,digest in manifest['files'].items():
    assert hashlib.sha256((root/(name+'.txt')).read_bytes()).hexdigest()==digest,name
name='src/main/java/com/iridium126/createmanaindustry/client/particles/shaderpack/ShaderPackProgramCompiler.java'
old=(root/(name+'.txt')).read_text(encoding='utf8');new=Path('scripts/particles/experiments/model-draw/ShaderPackProgramCompiler.java.txt').read_text(encoding='utf8')
def section(text,start,end):
    a=text.index(start);return text[a:text.index(end,a)]
def swap(text,a,b):
    assert a in text;return text.replace(a,b)
for variant in ['Geometry','Animation','Color','Context']:
    s=old
    if variant=='Geometry':
        for start_old,start_new,end in [
            ('int vb=gl_VertexID*int(MODEL_VERTEX_FLOATS);','int vb=gl_VertexID*2;','vec3 vertex,normal;vec2 uv;'),
            ('uint vb = uint(gl_VertexID) * MODEL_VERTEX_FLOATS;','int vb = gl_VertexID * 2;','float life ='),
            ('vec3 local = gone ?','vec3 local = gone ?','if (pid == 7 && !gone) {')]:
            s=swap(s,section(old,start_old,end),section(new,start_new,end))
    elif variant=='Color':
        s=swap(s,section(old,'vec4 kfr[8];','vec3 baseTint'),section(new,'int cc = max(1, min(8, int(gone','vec3 baseTint'))
    elif variant=='Animation':
        s=swap(s,section(old,'float carryRamp = 0.0;','mat4 M = gone'),section(new,'cmiLoadAllayAnimation((item.x>>9u)','mat4 M = gone'))
    else:
        s=s.replace('#define CMI_STARFALL_TBO\\n','#define CMI_STARFALL_TBO\\n#define CMI_DRAW_CONTEXT\\n')
    dest=Path('build/model-draw-reference')/f'{variant}ModelProgramCompiler.java';dest.parent.mkdir(parents=True,exist_ok=True)
    dest.write_text(s.replace('ShaderPackProgramCompiler',variant+'ModelProgramCompiler'),encoding='utf8')
print('Validated frozen checksums; generated four independent ablations')
