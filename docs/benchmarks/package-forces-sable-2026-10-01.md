# Sable 移动力源的 GPU 包裹受力（2026-10-01）

Sable plot 内实体的边界与脚位置，以及移动鼓风机的气流边界、源位置和方向，已通过直接 Sable API 转换到包裹的世界坐标。依赖仍为 `compileOnly "maven.modrinth:T9PomCSv:U678xqle"`，不使用反射；没有安装 Sable 时不加载适配器。未完成初始化或已删除的 sublevel 不作为空气或有效力源，捕获失败沿现有资源覆盖门禁恢复 Create，后续成功捕获可以恢复准备。

owner 线程只读取源实体/风机与 sublevel 的逻辑姿态并复制不可变值；后台构建世界 AABB BVH 及上传数据。实体使用世界边界和脚位置，不新增矩阵；旋转风机在原 64B source 之后追加 64B，包含世界气流盒中心、三条逆变换行和原始局部半尺寸。节点仍为 32B，通用粒子及既有包裹 body ABI 不变。只有自由域有需求时才创建和准备受力资源，锁链域不额外构建受力快照。

参考 `.refs/Sable/neoforge/src/main/java/dev/ryanhcode/sable/neoforge/mixin/compatibility/create/airflow/AirCurrentMixin.java`：原生实现先把世界包裹 AABB 逆投影成 plot AABB，与原气流盒相交；脚位置到风机中心的距离在 plot 中计算；气流方向转换到世界后，再对世界速度差逐轴限幅。GPU 保留这一顺序和 Sable 正尺度，不把速度限幅改成局部轴，也不把原生保守 AABB 判定换成不同的窄阶段语义。普通风机使用 `forces.comp`；包含旋转风机时才使用 `forces_framed.comp`，两者复用源代码，普通路径不执行额外矩阵运算。

服务端和客户端 Create 气流回调的筛选同样通过 typed probe 使用局部边界和位置，保留火焰等处理类型与原生服务端副作用，避免 plot/世界坐标混用导致漏掉处理回调。此处仍有逐包裹 CPU 边界筛选，原生 broad phase 尚未消除；不能把新增 GPU 数学测量当作服务器 tick 达标证据。

真实 Sable math companion 的 300 个固定随机姿态验证覆盖大 plot/world 坐标、旋转中心和非均匀正尺度，检查世界包围盒、世界气流方向、逆变换及快照不可变性。GPU 受力回归共 457896 项断言，包括 12 组姿态、吹/吸风、世界轴限幅、旋转细气流边界的排除、source/frame 混合布局、移动后旧边界失效、切回普通路径、65 个活动体与尾部哨兵。旋转速度容差为 1e-4，未修改字段与排除体位模式精确比较。完整构建通过；73 套共 357 项单元测试零失败，完整包裹 GPU 回归 17413809 项通过，未安装 Sable/companion 的独立 JVM 门禁验证通过。该验证直接调用实际 SDK 数学作为参考，并不替代装有 Sable 的游戏启动与视觉验证。

同机 RTX 4070 Laptop GPU、OpenGL 4.5、NVIDIA 581.15；固定来源和包裹位置，每组 32 次预热、60 个计时样本，重复三次。10000、65536、131072 包裹全部执行受力内核，无 CPU 回退。旋转风机固定 Y 旋转 0.37 rad，气流覆盖全部包裹；64 风机保持交替相反方向及原顺序。

| 131072 活动包裹 | GPU p95，三次范围 | CPU 上传/提交 p95 | worker bake p95 | 每步上传 |
| --- | --- | --- | --- | --- |
| 普通单气流 | 0.0236ms | 0.0063–0.0084ms | 0.0026–0.0034ms | 96B |
| 4095 实体 + 普通气流 | 0.1014–0.1034ms | 0.0310–0.0341ms | 1.5132–1.6432ms | 524256B |
| 普通 64 道相反气流 | 0.2376–0.2734ms | 0.0077–0.0083ms | 0.0111–0.0131ms | 8160B |
| 旋转单气流 | 0.0236–0.0246ms | 0.0067–0.0089ms | 0.0027–0.0039ms | 160B |
| 旋转 64 道相反气流 | 0.3195–0.3420ms | 0.0089–0.0098ms | 0.0183–0.0241ms | 12256B |

[完整 CSV](package-forces-sable-2026-10-01.csv)保留 45 组测量；[上一版本 CSV](package-forces-2026-10-01.csv)仍保留。普通单气流与前次 0.0225–0.0236ms 范围一致；其他普通场景本次更低，但前后采集不是交错测量，不据此宣称可复现提速。CPU 数字只包括不可变数据上传与提交，worker 数字只包括烘焙，不包含实际世界采集、Create 回调、后续碰撞/绘制或网络。计时查询的同步等待只发生在 harness 的 CPU 计时结束之后，生产路径不等待。

本次没有新增力源网络包。本报告时点曾按 Create 原生的 1.5 倍记录带宽验收；用户随后将总带宽优化和比例验收延期到后续版本，本轮沿用现有同步路径，不以该历史比较阻塞接管。整帧 16.7ms、服务器 tick 50ms、视觉不可分辨及全计划验收均未完成。

```powershell
.\gradlew.bat test validatePackageGpu validatePackageSableAbsent build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat validatePackageGpu -PpackageForcesBenchmark --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```
