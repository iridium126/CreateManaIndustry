"""Publish computed renders with captions; only uniform image scaling and viewport framing."""
import csv
import json
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

source=Path('build/julia-offline')
dest=Path('docs/design-assets/julia-offline')
dest.mkdir(parents=True,exist_ok=True)
BG=(11,19,26)
FG=(219,236,237)
MUTED=(145,172,180)
def font(n):return ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',n)
def native(index,view):
    a=Image.open(source/f'render/{index:03d}-view{view}.png')
    b=Image.new('RGB',a.size,BG);b.paste(a,(0,0),a)
    return b
def put(out,text,xy,size=25,color=FG):ImageDraw.Draw(out).text(xy,text,font=font(size),fill=color)

for index,label in ((12,'c045'),(49,'c035')):
    for view,name in ((0,'front'),(1,'oblique'),(2,'side')):
        native(index,view).save(dest/f'quadratic-{label}-{name}.png')

hero=Image.new('RGB',(1600,1280),BG)
put(hero,'未经变形的二次 Julia · 天然近宽菱形', (58,28),42)
put(hero,'q(n+1) = q(n)^2 + c     c = (-0.45, 0, 0, 0)     切片 q0 = (x, y, z, 0)',(61,90),26,MUTED)
hero.paste(native(12,0).crop((0,240,1600,1360)),(0,145))
put(hero,'直接计算三维切片表面；无菱形包络、非等比缩放、坐标扭曲或中心挖孔。',(60,1199),24,MUTED)
put(hero,'青色仅为观察材质；未合成风暴与天空。参考眼点要求已改为 y=64。',(60,1238),22,MUTED)
hero.save(dest/'quadratic-c045-hero.png')

sheet=Image.new('RGB',(1800,1430),BG)
put(sheet,'同一个 Julia 集合的三视图',(50,22),40)
put(sheet,'A：c = -0.45    二次四元数迭代    w=0切片    仅改变观察方向',(52,82),25,MUTED)
for x,view,label in ((35,0,'正视 / 自然宽菱形轮廓'),(620,1,'斜视 / 保留天然立体厚度'),(1205,2,'侧视 / 非平面图案拉伸')):
    sheet.paste(native(12,view).resize((560,560),Image.Resampling.LANCZOS),(x,130))
    put(sheet,label,(x+17,688),25)
put(sheet,'相邻参数对照：同一公式、同一正视相机、同一等比显示尺度',(50,770),29)
for x,index,label in ((35,49,'B：c = -0.35 / 更饱满'),(935,12,'A：c = -0.45 / 更宽、边缘层次更明显')):
    view=native(index,0).crop((0,250,1600,1350)).resize((830,570),Image.Resampling.LANCZOS)
    sheet.paste(view,(x,820));put(sheet,label,(x+20,1335),24)
put(sheet,'所有形状均为原始迭代集合的有限精度离线渲染。中央表面保留，不制作空心眼眶。',(50,1390),22,MUTED)
sheet.save(dest/'quadratic-julia-views-and-comparison.png')

rows=list(csv.DictReader((source/'candidates.csv').open()))
manifest={
 'reference_eye_y':64,
 'geometry':'boundary of the bounded-orbit set in the unmodified quaternion slice',
 'quaternion_order':['real','i','j','k'],
 'iteration':'q[n+1] = q[n]*q[n] + c',
 'slice':'q[0] = (x,y,z,0)',
 'surface_render':{'resolution':[1600,1600],'max_iterations':128,'max_ray_steps':1100,'epsilon':.00045,'orthographic_half_height':1.85,
                   'camera_positions':[[0,0,4],[2.2,1.3,3.1],[3.6,.8,.8]],'target':[0,0,0],'camera_up_hint':[0,1,0],'camera_roll':0},
 'allowed_display_operations':['lighting','uniform material color','orthographic camera','background framing','uniform image resizing'],
 'not_used':['diamond envelope','CSG','coordinate folding','nonuniform scaling','displacement','center hole','AI image generation'],
 'candidates':[rows[i] for i in (12,49)],
 'independent_silhouette_verification':json.loads((source/'verification.json').read_text()),
 'rendering_limit':'finite-iteration/finite-epsilon approximation; microscopic surface features are not an exact infinite-set proof'
}
(dest/'parameters-and-verification.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
print(dest/'quadratic-c045-hero.png')
print(dest/'quadratic-julia-views-and-comparison.png')
