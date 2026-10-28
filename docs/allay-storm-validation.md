# 悦灵风暴验证入口

日期：2026-10-29。阶段7与LOD2固定世界尺寸版已由用户确认客户端实机验收通过。此页只维护现行检查选择和命令；历史性能结论保留在`benchmarks/`，原始结果写入忽略的`build/`。

## 构建与行为检查

从仓库根目录运行，所有复用main／GameTest输出的Gradle进程串行执行。GPU工具使用隐藏GLFW窗口与真实OpenGL 4.5驱动，不启动游戏客户端；不可用或跳过不计通过。

```powershell
.\gradlew.bat build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat test --tests '*allaystorm*' --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

| 改动 | 保留的检查 |
| --- | --- |
| 定义、增长、调度 | Persistence／CommandTree／MajorAttackSchedule及三种攻击规则测试 |
| 网络、身份与回收 | Packet／WaveSlots／StreamClock／GatherRules／ColumnSnapshotSync；旧维度、旧epoch、旧订阅和迟到完成不能复活状态 |
| GPU布局与交互 | StormClientContract的布局／字节码行为、动画存储、当前帧发布和MODEL交互 |
| 裂口与时钟 | RiftRules／Payload／ClientState／RenderFrame、真实Mixin ABI和原版天空时钟查询；Julia资源与投影共用JuliaProjectionMathTest |
| 运输与恢复 | RiftTransaction／Journal／PlotCoordinates／PlotVerification及独立服务端故障／重启矩阵 |
| LOD | StormLodTest的有界编码、双采样、冻结定义、订阅高水位和配置滞回，以及生产GPU验证 |

只断言旧字段不存在、旧源码拼写或复演已删除实现的测试不再维护；当前行为、Java／GLSL布局及实际二进制ABI检查保留。

## GPU正确性与性能分开运行

统一用[结果运行器](../scripts/benchmarks/run.py)保存命令、退出状态、源码元数据及完整日志；也可直接Gradle加`-I scripts/particles/validation.init.gradle`。不要把源码链接、隐藏窗口内核结果或单次更低耗时当作整帧／实机验收。

| 改动范围 | GPU任务 |
| --- | --- |
| LOD位置、档位、距离硬切、透视尺寸、遮挡 | `validateStormLodGpu` |
| LOD当前性能 | `benchmarkStormLodGpu`，按需测解析计算、50000普通OPAQUE常驻keygen基线、两档绘制和目标合成 |
| Julia形状／投影 | `validateJuliaProjectionGpu`，保留独立原始迭代oracle |
| 无包／Iris天空、云与状态恢复 | `validateRiftSkyGpu`、`validateRiftReplayGpu`、`validateVanillaRiftHighLevel`；高层工具实际经过ShaderInstance／BufferUploader |
| 原生包／悦灵材质 | `validateNativeRiftPacks`、`validateModelMaterialGpu`；实际include／AST／驱动链，按需追加`validateShaderPackSamples` |
| 原生／MODEL动画与存储 | `validateAllayAnimationGpu`、`validateModelAnimationStorageGpu`、`validateModelPopulationGpu` |
| 主绘制／独立阴影／重载 | `validateModelShadowGpu`、`validateModelShadowIrisGpu`、`validateParticleProgramsGpu` |
| 运动与专用碰撞 | `validateStormMotionGpu`、`validateStormCollisionGpu`、`validateStormDepenetrationGpu`、`validateStormNavigationGpu` |
| 三种攻击 | `validateStarfallColumnGpu`、`validateStarfallGpu`、`validateHelixGpu`、`validateDomeGpu`、`validateDomePoseGpu` |
| Dome真实集结／路径／地形／退出 | `validateDomeGatherGpu`、`validateDomePathGpu`、`validateDomeTerrainGpu`、`validateDomeExitGpu` |

```powershell
python scripts/benchmarks/run.py validateStormLodGpu --offline
python scripts/benchmarks/run.py benchmarkStormLodGpu --offline
python scripts/benchmarks/run.py validateJuliaProjectionGpu validateRiftSkyGpu validateRiftReplayGpu validateVanillaRiftHighLevel --offline
```

LOD默认仅执行成员身份、种子／位置、死亡／覆盖位图、视锥／档位、当前MODEL／OPAQUE硬切、MRT／深度和GPU顶点投影回归，输出`storm-lod-projection.csv`。基准才输出compute／resident-keygen／render CSV及composite计时；已验收固定像素版本的源码对照、HEAD依赖和截图发布代码已移除。历史选择及测量范围见[LOD尺寸报告](benchmarks/storm-lod-perspective-2026-10-28.md)。

`validateStarfallGpu`还覆盖共享旧路径、圆环及未参战主体不漂向原点，与独立星柱工具不重复。Dome运动／反馈变化追加30／60／144／240Hz、不规则步长、延迟和时钟环绕任务；地形矩阵使用生产START及实际完成反馈，不能用预计时间冒充真实就绪。导航可用`-PnavigationQuick`定位；定位模式不替代完整矩阵。

Dome地形参数保留`-PdomeTerrainPlans=<生产START矩阵>`、`-PdomeFps`、`-PdomeJitter`、`-PdomeReturningTraffic`及过滤项；诊断故障注入必须标明。GPU证据回放、边界退出及迟到完成说明见[Dome地形报告](benchmarks/dome-terrain-2026-10-23.md)。

## 服务端与离线资源

```powershell
.\gradlew.bat -I scripts/allaystorm/gametest.init.gradle runGameTestServer --offline --no-configuration-cache
.\gradlew.bat -I scripts/allaystorm/rift-gametest.init.gradle runGameTestServer --offline --no-configuration-cache
.\scripts\allaystorm\validate-rift-restarts.ps1
.\scripts\allaystorm\validate-rift-restarts.ps1 -Phases PREPARED,PARTIAL,COMMITTED,CHECKPOINTED -Sable
python scripts/allaystorm/rebuild_dome_v4.py
python -m unittest discover -s scripts/allaystorm -p test_dome_v4_tools.py
.\gradlew.bat renderJuliaOffline --offline --no-configuration-cache -I scripts/particles/validation.init.gradle -I scripts/art/julia-offline.init.gradle
python scripts/art/julia_verify.py
```

GameTest只使用`build/gametest`独立目录，故障注入不读写`run/saves`。普通乘骑、Create、Sable／Simulated结构、库存／连接／离线乘客及各日志阶段的恢复检查保留；详见[裂口与传送](allay-storm-rift.md)。

Dome重建保留独立解码、弧长、连续运动／间距／闭合流线证书与无损源控制点，候选留在`build/`，不自动替换生产二进制。Helix生成也须显式指定候选输出。工具与依赖见[scripts/allaystorm](../scripts/allaystorm/README.md)。

Julia离线工具只渲染当前c=-0.5，写`build/julia-offline/parameters.json`及三视图／原始覆盖；CPU工具只复核该参数。`julia_runtime_boundary.py`用于明确重建当前运行时边界，会写生产资源，只有实际修改形状时运行并核对diff；本轮清理未运行该生成器。不要恢复候选扫描／旧参数发布脚本作为默认入口。

## 性能与发行

同机、固定输入与分辨率、预热后三轮，分别记录生成／计算／绘制／合成、CPU提交、显存、上传／读回及整帧。冷编译、缓存命中、首次非零执行和稳态分开；不清除用户驱动缓存。当前任务及规则见[性能结果规范](benchmarks/README.md)。

保留的决策报告：[LOD尺寸](benchmarks/storm-lod-perspective-2026-10-28.md)、[动画存储](benchmarks/allay-animation-2026-10-25.md)、[共享导航](benchmarks/allay-storm-navigation-2026-10-23.md)、[冷编译与脱嵌](benchmarks/allay-storm-cold-escape-2026-10-24.md)、[集结](benchmarks/dome-gather-2026-10-23.md)、[地形](benchmarks/dome-terrain-2026-10-23.md)。报告描述当时条件，当前结果以本次日志为准。

发行包来自`build/libs/`，`audit_storm_package.py`检查生产类、着色器、协议及模板，排除证书、离线控制点、测试类和GameTest夹具。两端升级并重启，F3+T不更新Java类。后续渲染改动回归距离边界、暂停、死亡、STOP重召唤、切维度、资源重载／缩放及无包／八包；运输组合与authority交接保留独立实机范围。

本次清理验证（2026-10-29）：完整构建、943项单元测试、LOD正确性及独立基准、Julia GPU／CPU复核和5项Dome数学测试均通过。所有`src/main`文件逐一SHA256不变；本轮没有改变已验收运行时。发行审计按当前源码协议识别，不再硬编码旧协议18或依赖旧性能快照；真实发行包通过201个着色器、307个类和4个资源核对，负向样本中的着色器改写及混入证书均被拒绝。结果写入`build/benchmarks/current/storm-package-audit.json`。
