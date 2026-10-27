"""Design diagram only; does not implement the game's fractal or renderer."""
from pathlib import Path
import math
import random
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = Path(__file__).resolve().parent
S = 2
W, H = 1800, 1160
im = Image.new('RGB', (W*S, H*S), '#081820')
d = ImageDraw.Draw(im)
font_path = 'C:/Windows/Fonts/msyh.ttc'
def font(n): return ImageFont.truetype(font_path, int(n*S))
def text(x, y, value, size=22, fill='#D4EBEA'):
    d.text((x*S, y*S), value, font=font(size), fill=fill)
def line(points, fill, width=1):
    d.line([(int(x*S),int(y*S)) for x,y in points], fill=fill, width=max(1,int(width*S)), joint='curve')
def poly(points, fill): d.polygon([(int(x*S),int(y*S)) for x,y in points], fill=fill)
def ellipse(box, fill=None, outline=None, width=1):
    d.ellipse(tuple(int(v*S) for v in box), fill=fill, outline=outline, width=max(1,int(width*S)))
def panel(x,y,w,h): d.rounded_rectangle((x*S,y*S,(x+w)*S,(y+h)*S),radius=14*S,fill='#102731',outline='#28444B',width=S)

text(48,28,'分形巨眼  /  序号7构图方案',42,'#E3FBF4')
text(50,88,'参考机位：风暴中心正下方，脚点 y=64，正常站立仰望',24)
text(50,127,'造型与空间关系示意 · 不是游戏截图、正式 Julia 渲染或已实现资产',18,'#91AFB4')
panel(40,178,1230,598)
panel(1290,178,470,598)
cx,cy=650,473

# Rhombic frame in the reference projection, with intentionally stylized recursive seams.
def loop(a,b,phase=0,depth=0):
    points=[]
    for i in range(601):
        t=i/600*math.tau
        radius=1/(abs(math.cos(t))/a+abs(math.sin(t))/b)
        fine=(math.sin(15*t+phase)+.42*math.sin(45*t-phase)+.15*math.sin(135*t))*3
        points.append((cx+(radius+fine)*math.cos(t)+depth,cy+(radius+fine)*math.sin(t)+depth*.5))
    return points

outer=loop(489,250,0,12)
poly(outer,'#07131C')
for a,b,phase,color,depth in [(484,245,.3,'#173E48',9),(478,239,.0,'#2C6C75',3),(470,231,.4,'#1C4C58',0)]:
    poly(loop(a,b,phase,depth),color)
inner=loop(470,235,.2,0)
poly(inner,'#101E35')
line(loop(478,239,.0), '#58BFC6',1.3)
line(inner,'#9AE7E2',1.7)

# Ordinary night sky stands in for the time-shifted native sky; no new constellation is proposed.
rng=random.Random(7)
for _ in range(110):
    x=rng.uniform(-440,440);y=rng.uniform(-210,210)
    if abs(x)/443+abs(y)/210<.96:
        ellipse((cx+x-1,cy+y-1,cx+x+1,cy+y+1),fill='#93AFC0')
ellipse((926,424,950,448), fill='#D5DDE0')
ellipse((932,418,954,440), fill='#101E35')

# The iris is the nearer, existing allay storm, not an extra painted disc in the implementation.
iris=202
ellipse((cx-iris,cy-iris,cx+iris,cy+iris),fill='#163C4C',outline='#43818C',width=1)
for arm in range(6):
    points=[]
    for j in range(150):
        r=22+(iris-26)*j/149
        t=arm*math.tau/6 + 2.6*math.log(max(r,22)/22)
        points.append((cx+r*math.cos(t),cy+r*math.sin(t)))
    line(points,'#245668',15)
    line(points,'#448F9C',5)
    line(points,'#81D8DA',1.5)
for j in range(600):
    t=rng.uniform(0,math.tau);r=rng.uniform(27,iris)
    x=cx+r*math.cos(t);y=cy+r*math.sin(t)
    ellipse((x-.65,y-.65,x+.65,y+.65),fill='#85BDC1')
ellipse((cx-22,cy-22,cx+22,cy+22),fill='#101E35',outline='#71C6C9')

# Recursion here is an explanatory line motif; production geometry uses the proposed 3D Julia field.
def branch(p,q,level,side):
    if level<=0:return
    dx=q[0]-p[0];dy=q[1]-p[1]
    length=math.hypot(dx,dy)
    if length<1:return
    mx=p[0]+dx*.54-dy*.15*side;my=p[1]+dy*.54+dx*.15*side
    line([p,(mx,my),q], '#4CA7AE',max(.7,level*.38))
    tip=(mx-dy*.28*side,my+dx*.28*side)
    branch((mx,my),tip,level-1,-side)
    branch((mx,my),q,level-1,side)
for t in [i*math.tau/28 for i in range(28)]:
    r=1/(abs(math.cos(t))/467+abs(math.sin(t))/231)
    p=(cx+r*math.cos(t),cy+r*math.sin(t))
    q=(cx+(r+15)*math.cos(t+.025),cy+(r+15)*math.sin(t+.025))
    branch(p,q,4,1)

text(66,196,'正下方参考投影',22,'#D5F7F0')
line([(227,263),(350,286),(397,346)],'#94BFC1')
text(75,228,'菱形分形眼眶',24)
text(75,265,'四端稳定，外侧有厚度',18,'#9DBABD')
line([(960,693),(863,641),(797,607)],'#94BFC1')
text(970,674,'真实六臂虹膜',23)
text(970,710,'随生成规模成长',18,'#9DBABD')
text(72,728,'形状匹配按世界参考点反推；移动镜头后保留真实透视，不做屏幕贴花。',19,'#9DBABD')

text(1320,207,'画面约束',27,'#D5F7F0')
for y,title,detail in [(267,'01  眼眶由投影定尺寸','不固定为192×160或240×208'),(365,'02  眼眶有三维侧壁','中心68格净空，入口规则不变'),(463,'03  天空自然相差半天','白昼与夜晚，不加专属滤色'),(561,'04  云只来自有效光影包','无包、无云或关云 → 不补云')]:
    text(1320,y,title,22)
    text(1320,y+40,detail,18,'#9DBABD')
text(1320,680,'参考画面为“晴天／夜空”组合。',18,'#9DBABD')
text(1320,710,'云形、天气和曝光以实际光影包为准。',17,'#9DBABD')

panel(40,798,690,315)
panel(750,798,1010,315)
text(64,817,'空间剖面  /  近虹膜、远眼眶',23,'#D5F7F0')
ex,ey=339,1080
py=870;sy=1039
rr=26; projected=rr*(ey-py)/(ey-sy)
line([(ex-projected,py),(ex,ey),(ex+projected,py)],'#6B9EA8',1)
line([(ex-242,py),(ex+242,py)],'#64CECF',3)
line([(ex-242,py-8),(ex+242,py-8)],'#285D6A',4)
line([(ex-rr,sy),(ex+rr,sy)],'#78BEC6',8)
ellipse((ex-4,ey-4,ex+4,ey+4),fill='#D3F6EF')
text(573,858,'裂口基面',17)
text(573,885,'H+64',16,'#9DBABD')
text(394,1018,'真实风暴',17)
text(394,1044,'默认中心128',16,'#9DBABD')
text(76,1064,'脚64 / 眼高≈65.62',17,'#9DBABD')

text(778,817,'同一套原生天空  /  太阳相位落后12000 tick',23,'#D5F7F0')
for x,label,fill,sun in [(780,'主世界：正午6000','#5488B2',True),(1265,'悦灵维度：午夜18000','#111D34',False)]:
    d.rounded_rectangle((x*S,878*S,(x+455)*S,1032*S),radius=12*S,fill=fill)
    if sun:
        ellipse((x+205,909,x+250,954),fill='#F2E9CE')
    else:
        for _ in range(33):
            sx=x+rng.uniform(18,437);yy=rng.uniform(892,1018)
            ellipse((sx-.8,yy-.8,sx+.8,yy+.8),fill='#9CB5CA')
        ellipse((x+204,908,x+244,948),fill='#CEDDE2')
        ellipse((x+216,901,x+253,939),fill=fill)
    text(x,1043,label,20)
text(781,1080,'昼夜流速相同；天气沿用主世界一致的既有行为。',18,'#9DBABD')
text(48,1128,'设计示意：分形分叉不是正式Julia采样结果；本轮只提交方案，不改变渲染、云或游戏时间。',17,'#8CA9AF')

im.resize((W,H),Image.Resampling.LANCZOS).save(ROOT/'stage7-eye-concept.png')
print(ROOT/'stage7-eye-concept.png')
