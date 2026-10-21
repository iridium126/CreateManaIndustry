# Allay Storm：开发与验收入口

当前协议14、Dome v4。[当前行为](allay-storm-ai.md)维护攻击参数，[同步契约](allay-storm-sync.md)维护数据所有权。本页统一验证方式，不逐版本复制测试总数、日志或发行JAR哈希。历史结果从Git查阅，原始数据写入忽略的 `build/`。

## 按改动选择检查

从仓库根目录运行，不并发启动复用main或GameTest输出的Gradle进程。GPU任务需要实际OpenGL 4.5驱动，使用隐藏GLFW窗口，不启动Minecraft客户端；GPU不可用报告环境限制，跳过不算通过。

```powershell
# 行为／编解码／持久化与完整构建
.\gradlew.bat test check build --offline --no-configuration-cache
# 只运行风暴单元测试
.\gradlew.bat test --tests '*allaystorm*' --offline --no-configuration-cache
# 独立数学与编解码oracle
python -m unittest discover -s scripts/allaystorm -p test_dome_v4_tools.py
# 专用服务端生命周期及武器／事件钩子
.\gradlew.bat -I scripts/allaystorm/gametest.init.gradle runGameTestServer --offline --no-configuration-cache
```

| 范围 | 测试／工具 |
| --- | --- |
| 存档、增长相位、命令 | `AllayStormPersistenceTest`、`AllayStormCommandTreeTest` |
| 调度、人数与成员互斥 | `MajorAttackScheduleTest`、`AllayHelixTest`、`AllayDomeTest`、`StarfallColumnTest` |
| 身份、包、反馈、槽复用 | `*PacketTest`、`StormWaveSlotsTest`、`StormGatherRulesTest` |
| GPU资源／客户端契约 | `DomeTemplateTest`、`StormClientContractTest`、客户端碰撞测试 |
| 武器与区域伤害 | `src/test/gametest/allaystorm/`；含剑、附魔、暴击、重锤、冷却与副作用恰好一次 |
| 编码与连续证书 | `scripts/allaystorm/`，见[工具说明](../scripts/allaystorm/README.md) |

旧人口阶梯、多柱选员和历史眼位辅助类的孤立测试已删除，不再当作现行需求。仍被共享生产路径调用的旧归属编码、圆环、转向输出和原点漂移回归保留。按功能扩展已有测试，不新建版本号测试集合。

## GPU回归

统一使用 [benchmark运行器](../scripts/benchmarks/run.py)，记录命令、退出状态、元数据及输出：

```powershell
# 三种攻击、姿态、专用碰撞；旧Starfall入口还覆盖共享路径
python scripts/benchmarks/run.py validateStarfallColumnGpu validateStarfallGpu validateHelixGpu validateDomeGpu validateDomePoseGpu validateStormCollisionGpu --offline
# 修改共享绘制／状态边界时追加
python scripts/benchmarks/run.py validateParticleProgramsGpu validateModelPopulationGpu validateModelShadowGpu validateModelShadowIrisGpu validatePackageGpu validatePackageIrisGpu --offline
# 修改Dome运动／反馈时追加步频、延迟、环绕矩阵（默认Dome含20Hz）
python scripts/benchmarks/run.py validateDome30Hz validateDome60Hz validateDome144Hz validateDome240Hz validateDomeIrregular validateDomeDelayed validateDomeClockWrap --offline
```

Dome生产生命周期从普通主体初始状态出发，经过移动集结、95%反馈、服务端LOCK、中心接触与实际穿出结束平面，不能直接注入LOCK代替集结。检查rank唯一、旧反馈隔离、速度／姿态连续、死亡／中止／观察者及归队。证据在 `build/dome-v14/gpu-evidence-*.json`；联验实际候选与服务器时，先运行60Hz，再回放：

```powershell
.\gradlew.bat -I scripts/allaystorm/gametest.init.gradle runGameTestServer '-PdomeEvidence=build/dome-v14/gpu-evidence-60hz.json' --offline --no-configuration-cache
```

普通GameTest可独立运行，但不替代GPU证据链。MODEL／Iris工具使用真实着色器和transformer，仍不代替游戏内光影包／Mixin验收。

## 资源与性能

```powershell
# 编码→结束平面裁剪→解码检查→运动／间距／环流连续证书；候选只写build
python scripts/allaystorm/rebuild_dome_v4.py
# 同机同输入，分别保存基线和当前输出，至少三轮
python scripts/benchmarks/run.py benchmarkStormDome -PstormCoherent --offline
python scripts/benchmarks/run.py benchmarkStormDome --offline
python scripts/benchmarks/run.py benchmarkStormHelix --offline
```

输出 `storm-{dome,helix}-combined*.csv`。`-PstormFixedInput=<文件>`给Dome固定输入，比较时验证输入哈希；`-PstormProfile`按需拆分GPU阶段，不在正常验证启用探针。`validateDomePerformance`提供正常／压力各三轮，目录由 `-PdomePerformanceDirectory=...`指定。正常用各版本参考跟随输入，压力使用相同字节输入。

保留影响实现选择的一次测量：RTX4070 Laptop／驱动581.15、131072成员／4个Dome槽／64柱／100玩家，v13→v14正常GPU批次P95三轮中位数2.859→2.080ms、压力3.090→1.893ms；状态／轨迹显存62.32→41.73MiB，首次上传46.78→24.19MiB，稳态位置上传为0。未缓存首轮初始化约21.65秒，包含编译、夹具与资源准备；后续约0.81–0.88秒，不能当作纯编译时间。

这些只包含网格、更新与Dome接触，不是完整游戏帧时间或服务器吞吐。新的性能改动需重测；历史数据不作当前构建验收。见[性能结果规则](benchmarks/README.md)。

最终编码资源连续证书验证体心间距≥0.50格、加速度≤18、jerk≤120、转向≤180°／秒、0.5秒向心速度≥3格／秒及穿出3格。证书不覆盖任意初始相位交接、动态地形、多攻击交叠或全部动画顶点，分别由生产GPU场景和用户实机补充。

## 用户实机与交付

真实客户端由用户验收，自动化不能代签：

- Dome顶部覆盖、错落集结、贯心可读性、静止中心玩家受击、身体分离和剑翼轻微交叠。
- Helix外缘下集结、95%后立即冻结发射；Starfall绕柱转速、逐成员触地展开与短回流，主体不漂向原点。
- 坡地、台阶、屋顶、动态障碍、未知／卸载碰撞、死亡／中止；多客户端换维度、观察者及authority离开。
- 实际Iris光影包下MODEL／阴影一致，密集多人场景完整CPU／GPU帧时间、显存、上传量和冷启动成本。

当前分发JAR从 `build/libs/` 获取。离线证书在 `src/test/resources/allaystorm/`，只用于校验生产模板，不属于运行时资源。更新模板时同时更新测试证书。发布检查包含内嵌mod JAR：保留当前二进制模板，排除验证证书、旧模板、离线控制点、测试类及GameTest夹具。两端替换并重启，存档与当前配置保留。
