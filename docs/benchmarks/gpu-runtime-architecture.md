# GPU 运行时重构：当前验证与性能边界

更新：2026-10-17。第四批结构和自动正确性回归通过，真实客户端及整帧性能仍待用户验收。调用归属、预算、故障边界和测试入口见[第四批报告](../gpu-runtime-stage4.md)。固定 RTX 4070 Laptop GPU／NVIDIA 581.15／OpenGL 4.5；Minecraft 1.21.1／NeoForge 21.1.236／Create 6.0.10。

## 第四批独立基线

修改前保存实际工作区源码、编译产物、资源和 classpath；基线 HEAD `92157ea5d39fe04bbcea7da77860511b382d05d2`，源码快照 SHA-256 `c1d78afa50a9e2c58878f56fde391ab01675205aff7921f9106377108faf3ad4`。原暂存补丁 SHA-256 `c596e3fd1e504d4243eea8b2ac7bdcd5c2fac7554199a62743f11156666549ce` 保持不变。包裹另外从修改前 INDEX 生成独立命名的历史 Java／shader，仅迁移类型位置和必要可见性，不修改历史方法或 kernel。

原始结果在忽略的 `build/benchmarks/gpu-runtime-stage4-20261017/`。`final-identity-cache/` 是最终通用池对照，`performance-final/` 是包裹空气／地面对照；此前目录记录修复过程，不能挑选为最终结果。

## 第四批通用池生产 pass A/B

同一进程使用独立类加载器、池、程序及 shader 资源，交替运行冻结基线和候选。每场景先各执行 20000 帧使 JVM 编译稳定，再做五次试验，每次先预热 5×20 帧、后采样 40 帧，试验间交换先后顺序。两边都使用已有生产 `GpuUniformCache`，避免夹具 `HashMap<Integer,...>` 的 GL 名称装箱污染分配。下表取五次分位数的中位数。

| 场景 | GPU p50 ms 基线→当前 | GPU p95 变化 | CPU 提交 p50 ms 基线→当前 | CPU p95 变化 |
| --- | --- | --- | --- | --- |
| 空池 | 0.061440→0.062464（+1.67%） | +2.04% | 0.006900→0.007100（+2.90%） | -0.99% |
| 16384 additive 静止 | 0.080896→0.081920（+1.27%） | -0.81% | 0.005900→0.005900（+0.00%） | -1.33% |
| 16384 additive／ALPHA 各半 | 0.087040→0.087040（+0.00%） | +3.13% | 0.005600→0.005800（+3.57%） | +4.23% |

| 场景 | GPU p99 ms 基线→当前 | CPU p99 ms 基线→当前 | 夹具分配 B／帧 基线→当前 |
| --- | --- | --- | --- |
| 空池 | 0.103424→0.103424 | 0.010400→0.011000 | 57→57 |
| 16384 additive | 0.152576→0.129024 | 0.012500→0.007900 | 57→57 |
| 16384 混合 | 0.135168→0.137216 | 0.007300→0.008600 | 57→57 |

三组 GPU p50 最大回退 +1.67%，CPU 提交 p50 最大回退 +3.57%；GPU／CPU p95 最大回退分别 +3.13%／+4.23%。均在该受控范围的 5% 内。40 个样本的 p99 接近最大值，只作观测，不宣称尾延迟改善。

每帧实际执行 reset、update、keygen、radix、capture、pool／population 提升及两种间接绘制，保留 10 次 dispatch API 和 2 次 draw API；空池零组数／零实例仍保留相同提交路径。每次均校验存活计数、两种材质实例数及 GL 错误。有效上传为 2048 B／帧的 histogram 清零，采样路径读回为 0；验证计数读取在采样外。夹具 GPU 查询会等待结果以分离 CPU 提交和 GPU 耗时，生产仍用异步 timestamp。

该范围**不包含**场景组合根、输入生产、完整 CPU 预算 scope、世界／碰撞采集、Storm 反馈、包裹事务、Iris 或 Hex 图案的整帧组合，也不模拟普通地面碰撞。分配列包含计时夹具自身，不能当作游戏整帧分配。不能用这些数值代替用户执行的空场景、地面和混合整帧验收。

初版每粒子 CAS 重试在密集追加时产生严重串行等待，已否决；最终单记录追加恢复原子计数，拒绝时饱和并在 barrier 后消费，整体输出保留全收或不收预约。Hex 运动和死亡表现也纳入保护。绑定缓存改为接收退休通知直接遗忘名称；uniform 缓存在一个绑定阶段复用，退休时原地失效。没有改变表现、逻辑 tick、攻击规则，未引入第五批工作组／排序／睡眠候选。

## 第四批包裹空气／地面 A/B

同驱动、同进程交替旧／新物理与环境 kernel，五次试验，每次预热 5×20 步后采样 40 次：

| 包裹场景／数量 | GPU p50 ms 基线→当前 | CPU p50 ms 基线→当前 | GPU p95 变化 | CPU p95 变化 |
| --- | --- | --- | --- | --- |
| 空气／1024 | 0.515072→0.517120（+0.40%） | 0.020900→0.018700（-10.53%） | +1.36% | -18.49% |
| 空气／8192 | 0.614400→0.615424（+0.17%） | 0.032400→0.033700（+4.01%） | -0.16% | -19.19% |
| 地面／1024 | 0.123904→0.124928（+0.83%） | 0.015800→0.015700（-0.63%） | +0.79% | +17.07% |
| 地面／8192 | 0.162816→0.162816（+0.00%） | 0.033100→0.031800（-3.93%） | +0.00% | -2.22% |

四组 GPU／CPU p50 最大回退分别 +0.83%／+4.01%，通过该范围的中位门槛。地面 1024 的 CPU p95 增加 4.2 µs（+17.07%），如实保留；它没有通过第五批算法候选的 p95 门槛，本批也未替换算法。其余范围和旧夹具相同：不包含完整 prepare、网络、服务器事务或绘制，不能声明整帧性能通过。

复现（冻结运行时目录必须是修改前保存的产物，不能从候选重新生成基线）：

```powershell
.\gradlew.bat benchmarkParticlePipelineAB --offline --no-configuration-cache --no-parallel -I scripts/particles/validation.init.gradle -Pstage4BaselineRuntime=build/benchmarks/gpu-runtime-stage4-20261017/baseline -PbenchmarkOutput=build/benchmarks/stage4-pipeline-repeat
python -B scripts/particles/prepare_package_baseline.py --ref 92157ea5d39fe04bbcea7da77860511b382d05d2 --output build/stage4-package-ref-baseline
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache --no-parallel -I scripts/particles/validation.init.gradle -I scripts/particles/environment-benchmark.init.gradle -PpackageEnvironmentBaseline=build/stage4-package-ref-baseline -PbenchmarkOutput=build/benchmarks/stage4-package-repeat
```

包裹原始 CSV 另写入 `build/package-environment-benchmark*.csv`，运行结束后复制到该次结果目录。原始显存测量仍交由外部系统／驱动工具；没有新增游戏内显存输出。

## 此前批次历史结果

以下结果使用历史基线 `6b27b6ff10fce3264f9d7bd1da755db8f3fc4cc3`，对应此前批次的局部修改，不能作为第四批的新验收结果。

## 普通包裹场景 A/B

通过 `prepare_package_baseline.py` 从未修改 HEAD 生成独立命名的 Java 与原始 shader，在同一进程／驱动交替比较。每组五轮试验；每次五轮、每轮 20 逻辑步预热，再采集 40 个样本。下表取五次试验各自分位数的中位数，单位 ms。

| 场景／数量 | GPU p50 基线→当前 | 变化 | CPU 提交 p50 基线→当前 | 变化 | GPU p95 变化 | CPU p95 变化 |
| --- | --- | --- | --- | --- | --- | --- |
| 空气／1024 | 0.514048→0.514048 | +0.00% | 0.016800→0.016100 | -4.17% | +1.38% | -13.39% |
| 空气／8192 | 0.612352→0.615424 | +0.50% | 0.032400→0.032300 | -0.31% | +0.50% | -39.47% |
| 地面／1024 | 0.120832→0.120832 | +0.00% | 0.015900→0.015600 | -1.89% | -1.64% | +0.84% |
| 地面／8192 | 0.157696→0.159744 | +1.30% | 0.032200→0.032400 | +0.62% | +2.48% | +1.85% |

这四组通过普通场景中位回退不超过 5% 的门槛，不能据此声称整帧或 FPS 提升。范围为碰撞 atlas 与物理／环境 kernel 的受控步进，**不包含**完整 `PackageWorldRuntime.prepare`、环境 capture／下载、区域路由、网络、机器事务与绘制。夹具 journal 下载为零也不能替代新反馈路径的成本测量。显存由外部工具核对，本表不把 harness 申请字节当作实际驻留。

## 环境捕获与读回 A/B

同一驱动与进程内交替测试 HEAD 的环境实现／读回环与当前实现；两者的 Java 类和 shader 都独立命名，避免基线借用新读回机制。每组五次试验，每次先预热 5×20 次，再测 40 次，以下取五次分位数的中位数。

| 身份容量／有效事件 | GPU p50 基线→当前 ms | GPU 变化 | CPU 读取 p50 基线→当前 ms | CPU 变化 | GPU p95 变化 | CPU 读取 p95 变化 | CPU 读取字节 基线→当前 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 8192／0 | 0.177152→0.016384 | -90.75% | 0.036000→0.010500 | -70.83% | -89.86% | -80.69% | 524304→16 |
| 8192／17 | 0.175104→0.022528 | -87.13% | 0.038900→0.011200 | -71.21% | -87.10% | -73.69% | 524304→17424 |
| 8192／512 | 0.184320→0.044032 | -76.11% | 0.033100→0.023500 | -29.00% | -77.51% | -35.58% | 524304→524304 |
| 131072／0 | 0.201728→0.016384 | -91.88% | 0.035700→0.008500 | -76.19% | -93.70% | -76.64% | 524304→16 |
| 131072／17 | 0.194560→0.025600 | -86.84% | 0.034900→0.009500 | -72.78% | -89.94% | -71.83% | 524304→17424 |
| 131072／512 | 0.192512→0.098304 | -48.94% | 0.033000→0.023200 | -29.70% | -53.70% | -31.13% | 524304→524304 |

范围包含 capture 的两次分类 dispatch、快照提交及 fence 完成后的 CPU 有效记录读取／回调；不包含物理步、网络、服务器事务或绘制。计时查询仅用于隔离夹具，每次测量等待完成以拆开 GPU 与 CPU 读取耗时，生产路径使用零超时 fence 轮询。事件数逐次校验，不通过丢失工作取得收益。

最初“先下载头、再调用驱动下载记录”的方案在饱和输出上出现 CPU 回退，已移除。当前方案使用共享 coherent 持久只读映射，并将环境 kernel 输出直接写入空闲快照槽，去掉最大区间复制及逐帧 map／驱动读取调用。仅在 fence 完成后读取，遵循 [ARB_buffer_storage 的可见性规则](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_buffer_storage.txt)。六组中 GPU p50／p95、CPU 读取 p50／p95 均改善；这一结果只通过本表范围的门槛，不代表整帧验收。

## 环境 ACK 列表 A/B

同一驱动交替比较未修改 HEAD 的逐条 uniform／dispatch 与当前有界列表。每组五次试验，每次预热 5×20 次后采集 40 个样本；下表取各次分位数的中位数。当前路径包含行初始化、完整生命周期编码与 GPU 二次核验、列表上传、提交和 GPU 应用；基线通过绑定后的 MethodHandle 调用原始五参数 ACK 入口，避免逐条反射分配。两边均不计夹具 header 重置和结果读取，逐样本校验每个身份的确认序号／生命值及 dispatch 数。

| ACK 数 | dispatch 基线→当前 | GPU p50 基线→当前 ms | GPU 变化 | CPU p50 基线→当前 ms | CPU 变化 | GPU p95 变化 | CPU p95 变化 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 0 | 0→0 | 0.001024→0.001024 | +0.00% | 0.000100→0.000100 | +0.00% | +0.00% | +0.00% |
| 1 | 1→1 | 0.006144→0.007168 | +16.67% | 0.001800→0.002100 | +16.67% | +16.67% | -23.53% |
| 17 | 17→1 | 0.057344→0.007168 | -87.50% | 0.002500→0.002300 | -8.00% | -87.72% | +7.41% |
| 512 | 512→1 | 1.487872→0.015360 | -98.97% | 0.031000→0.011900 | -61.61% | -98.91% | -63.14% |
| 513 | 513→2 | 1.506304→0.020480 | -98.64% | 0.031000→0.012500 | -59.68% | -97.84% | -60.49% |

空批没有 GPU 工作，表中的约 1 µs 是查询底噪；生产空队列不进入 ACK 批次，ACK 缓冲首次非空提交时才分配。513 条是存储边界专项，生产每帧 ACK 预算为 512，余项留在有界队列等待下一帧，控制预算不再顺带逐条消费 ACK。

单条 ACK 增加 GPU 约 1.024 µs、CPU p50 约 0.3 µs，是列表上传与重复生命周期核验的固定成本；17 条 CPU p95 增加约 0.2 µs（+7.41%），如实保留，不宣称所有数量／尾分位都改善。17～513 条的 GPU p50 下降约 87.5%～99.0%，512 条 CPU p50 下降约 61.6%。这是第二批 ACK 提交与可靠性修改的局部测量，尚不能通过整帧性能验收，亦不代替第五批算法候选的完整路径与 p95 门槛。

范围不包含网络收发、服务器事务、碰撞步、区域查找、纠正姿态和绘制。当前 128 B 行包含纠正字段，但此 A/B 输入不请求纠正；真实纠正／运动镜像由 GPU 回归另行验证。p99 也写入原始汇总，40 样本的 p99 接近最大值，仅作观测，不据此宣称尾延迟结论。复现：

```powershell
python -B scripts/particles/prepare_package_baseline.py
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache --no-parallel -I scripts/particles/validation.init.gradle -I scripts/particles/environment-benchmark.init.gradle -PpackageEnvironmentAckBenchmark
```

原始汇总／样本在忽略的 `build/package-environment-ack*.csv`，本次 metadata 在 `build/benchmarks/gpu-runtime-ack-benchmark-final`。增量归属与区域退休保留其他 body 的回归、完整修复专项及 JVM 测试通过；区域网络合包已接入协议 15，局部 CPU 测量见下文；网络／事务全路径仍待验收。

## 已验证的修改

- Hex 直接输入 255／256／257、饱和与边界保护；原生 palette 对实际 ColorProvider 的 RGB 逐字节差分，含深色、负时间和长时间。
- 真实 ConjureParticle.tick 的离散运动、零 Y 加速、分帧和暂停对照，原生 tick 队列、回绕与同 tick 多帧不重复推进，完整精灵和实机画面尚未验收。
- Hex 独立几何、尾点／版本、预算与 bank 背压、原生像素、状态恢复，以及真实 Iris transformer 的受控驱动验证；这不等于真实游戏内 shaderpack 验收。
- Great Trick 首帧头上传／发布前绑定恢复、Shared Fate／Partition 实际构形与像素回归。
- 源表增长保留 GPU 分数相位与邻接脏更新；Storm 最后一个身份写入、MODEL 人口及三种攻击回归。
- 通用计算／绘制编译与失败清理、所有者 uniform 缓存；Storm 通过窄端口调度并迁入业务包，发射写入器实际 GPU 混合 256 命令／170 palette 与压缩归属；64 B／20 vec4 具名布局门禁。
- 固定阶段／混合版本拒绝／失败失效的帧发布单元测试与实际引擎提交顺序门禁。真实 Minecraft 客户端帧协调仍须实机验收，不能用这些测试代替。
- 环境有效范围读取的零／少量／饱和、持久映射与直接写入快照槽、不可变捕获、FIFO 拒绝与重试，待处理位图的字边界、ACK 清除与退役复用；现有包裹修复专项和实际 Create 6.0.10 的 25 项环境／输出 GameTest。

各次结果按实际日志与 JUnit XML 判断；故意注入的 shader 重载错误用于验证保留旧程序，不是验收失败。

## 复现与剩余工作

```powershell
python -B scripts/particles/prepare_package_baseline.py
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache --no-parallel -I scripts/particles/validation.init.gradle -I scripts/particles/environment-benchmark.init.gradle
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache --no-parallel -I scripts/particles/validation.init.gradle -I scripts/particles/environment-benchmark.init.gradle -PpackageEnvironmentFeedbackBenchmark
.\gradlew.bat test validateHexGpu validateHexSharedIrisGpu validateSharedFateGpu --offline --no-configuration-cache --no-parallel -I scripts/particles/validation.init.gradle
.\gradlew.bat runGameTestServer --offline --no-configuration-cache --no-parallel -I scripts/particles/package-output-gametest.init.gradle -I scripts/particles/validation.init.gradle
```

原始数据在忽略目录 `build/package-environment-benchmark*.csv`、`build/package-environment-feedback*.csv`、`build/benchmarks/gpu-runtime-*` 与隔离 GameTest 日志中，不提交。后续仍须完成包含网络／事务的反馈全路径、通用粒子／Hex／Storm／Great Trick 混合场景、外部实际显存、服务器尾延迟，以及算法候选的独立 A/B。没有这些证据，不通过全量性能验收，也不默认启用实验算法。

## 协议 15 区域合包 CPU A/B

本次续作采用实际 `PackageEnvironmentUplink`、`ServerboundPackagePacket.STREAM_CODEC`、区域原点转换和 journal 解码。参考端按协议 14 的每事件单包方式执行；每条新旧路径的生命周期、样本及区域内顺序必须严格相等，包数逐次校验。没有 GPU、socket／压缩、世界事务或客户端帧。

每场景五次试验，每次先预热 5×20 轮，再交替采集各 60 个样本。下表是五次试验各自分位数的中位数，单位 ms。CPU 分配包含客户端 staging、实际信封序列化／反序列化与服务端 journal 解码。

| 事件／区域 | CPU p50 单包→合包 | CPU p95 单包→合包 | CPU p99 单包→合包 | 包数 单包→合包 |
| --- | --- | --- | --- | --- |
| 0／1 | 0.0001→0.0001 | 0.0001→0.0001 | 0.0001→0.0015 | 0→0 |
| 0／8 | 0.0001→0.0001 | 0.0001→0.0001 | 0.0002→0.0002 | 0→0 |
| 1／1 | 0.0065→0.0077 | 0.0107→0.0188 | 0.0200→0.0471 | 1→1 |
| 1／8 | 0.0027→0.0034 | 0.0037→0.0048 | 0.0112→0.0082 | 1→1 |
| 17／1 | 0.0179→0.0106 | 0.0243→0.0169 | 0.0477→0.0179 | 17→1 |
| 17／8 | 0.0239→0.0248 | 0.0338→0.0352 | 0.0376→0.0427 | 17→8 |
| 24／1 | 0.0124→0.0092 | 0.0151→0.0116 | 0.0199→0.0462 | 24→1 |
| 24／8 | 0.0121→0.0132 | 0.0159→0.0179 | 0.0174→0.0274 | 24→8 |
| 25／1 | 0.0114→0.0096 | 0.0138→0.0115 | 0.0342→0.0128 | 25→2 |
| 25／8 | 0.0112→0.0121 | 0.0141→0.0145 | 0.0149→0.0156 | 25→8 |
| 512／1 | 0.1893→0.1748 | 0.2117→0.1887 | 1.2012→0.2169 | 512→22 |
| 512／8 | 0.1922→0.1738 | 0.2096→0.1920 | 0.2375→0.2719 | 512→24 |

512 条的局部 CPU p50 分别下降 7.66%／9.57%，p95 下降 10.86%／8.40%，CPU 分配中位数约下降 5.7%；每轮 wire 总字节分别由 532480→524661、531456→524648。空队列没有封包、分配或网络提交。单条增加约 0.7～1.2 µs 的 p50，24 条分散到 8 个区域的 p50／p95 分别回退 9.09%／12.58%；这些固定成本如实保留。60 样本的 p99 接近最大值，且不同场景仍受 JIT／GC 影响，不能宣称尾延迟全面改善。

第一次测量暴露空 flush 的 iterator 分配；当前通过待发送字节计数跳过空 flush／clear，单条解码直接构造不可变单元素列表。表格只对应这次修正后的源码与 metadata，不混用第一次结果。此处验证第二批反馈传输的局部收益与固定成本，不构成第五批算法候选或整帧的准入证据。未启用新的算法候选。

```powershell
python scripts/benchmarks/run.py benchmarkPackageEnvironmentUplink --offline --no-parallel
```

本次最终原始 CSV、逐样本及 SHA-256 metadata 位于忽略的 `build/benchmarks/gpu-runtime-continuation-20261017/final/`。metadata 新增服务端包裹源码摘要，避免遗漏 wire／事务代码变化。

## 公共资源与本次验证边界

公共 `GpuCapabilities` 按当前 LWJGL GL 上下文缓存 SSBO／TBO／工作组限制；容量计算使用 long，并由使用者检查自己的 pass。公共 `GpuTimestampRing`／`GpuPassTelemetry` 承接普通绘制、Iris MODEL 合并、Hex 四上下文和包裹计时；满环保留未完成查询，失败范围可以丢弃样本，资源按所有者关闭。这些是资源／可靠性修改，本次未给出其整帧性能收益。

本次 `test assemble` 通过；真实驱动通过 Hex／Iris、Shared Fate／Partition、MODEL 阴影、Storm 三类攻击、包裹修复、绘制状态与 timestamp 满环／恢复验证。实际 Create 6.0.10 的已有 25 项环境／输出 GameTest 通过。发行 JAR 不含验证夹具，原有暂存补丁 SHA-256 未变化。日志在同一忽略目录。

`audit_package_environment.py --check` 通过，`--check --acceptance` 退出 1，明确报告 148 行缺少完整原版差分证据。区域合包仅有 JVM／codec 路径验证，尚未有真实多客户端网络事务与服务器尾延迟测量。当前依然不能通过第一批的全部实机场景，更不能将完整原版环境行为、独立业务预算或算法候选标成完成。历史存储分页的后续实现与未通过项见下一节。

## 历史存储分页续作：实现与未通过项

本轮比较固定 NVIDIA RTX 4070 Laptop GPU／581.15，基线使用修改前的暂存实现，标识为 `INDEX@6b27b6ff10fce3264f9d7bd1da755db8f3fc4cc3+dcde38c8cf2d0fe6547076d76cec9c0608da05a48c5aa992ef8684b6717f373b`。它已经包含先前的待处理位图、直接捕获槽和批量 ACK，不能把先前相对 HEAD 的收益再次计入本轮。两边均恢复相同 native motion checkpoint。每组五次试验，交替执行顺序，每次先预热 5×20 步，再采样 40 次；下表取各次分位数的中位数。

131072 身份的历史存储由每身份固定 21×48 B 改为运动工作区、内联样本、固定元数据和按需 19 槽页。该 SSBO 初始实际申请 16 MiB，满页逻辑布局为 132694080 B，二次幂分配高水位为 128 MiB；初始至满页的 GPU copy 与旧缓冲退休均有正确性测试，尚无其完整维护成本的性能测量。页回收复用，物理存储不主动收缩；这些是离线缓冲申请量，外部实际显存仍未测量，游戏内不输出字节。

普通空气／地面受控步进，单位 ms：

| 场景／数量 | GPU p50 基线→当前 | GPU p50 变化 | GPU p95 变化 | CPU p50 基线→当前 | CPU p50 变化 | CPU p95 变化 |
| --- | --- | --- | --- | --- | --- | --- |
| 空气／1024 | 0.509952→0.512000 | +0.40% | +0.80% | 0.0171→0.0167 | -2.34% | +12.07% |
| 空气／8192 | 0.612352→0.611328 | -0.17% | +0.17% | 0.0316→0.0309 | -2.22% | -34.95% |
| 地面／1024 | 0.128000→0.128000 | +0.00% | +4.00% | 0.0147→0.0152 | +3.40% | +10.04% |
| 地面／8192 | 0.149504→0.155648 | +4.11% | +6.67% | 0.0308→0.0301 | -2.27% | -3.24% |

四组 GPU／CPU p50 回退均不超过 5%；地面 8192 的 GPU p95、两组 CPU p95 超过 5%，如实保留，不通过第五批算法候选门槛，也不据此宣称整体收益。dispatch 两边分别保持 32／34，未确认环境字节均为零。1024 数量下固定元数据使总缓冲增加 16000 B；8192 空气／地面夹具总缓冲分别下降约 44.88%／23.42%。测量包含物理／碰撞／环境 kernel 提交，不含 prepare、捕获、网络、世界事务、绘制、首次分配与增长。

反馈捕获另行比较，包含两遍捕获、不可变槽提交、已有 16 B 异步头内的缺页统计，以及有效记录读取／回调。夹具直接播种 0／17／512 条待处理记录，不进行模拟或 ACK；页池增长成本不在此表中。

| 容量／事件 | GPU p50 基线→当前 ms | GPU p50 变化 | GPU p95 变化 | CPU poll p50 基线→当前 ms | CPU poll p95 变化 | 读取 B |
| --- | --- | --- | --- | --- | --- | --- |
| 8192／0 | 0.016384→0.016384 | +0.00% | -10.53% | 0.0090→0.0093 | -40.64% | 16 |
| 8192／17 | 0.028672→0.026624 | -7.14% | -6.90% | 0.0105→0.0102 | -8.76% | 17424 |
| 8192／512 | 0.046080→0.045056 | -2.22% | -2.17% | 0.0201→0.0203 | +2.30% | 524304 |
| 131072／0 | 0.016384→0.016384 | +0.00% | -15.00% | 0.0070→0.0072 | +7.06% | 16 |
| 131072／17 | 0.032768→0.035840 | +9.38% | +9.09% | 0.0076→0.0077 | +3.26% | 17424 |
| 131072／512 | 0.099328→0.163840 | +64.95% | +66.33% | 0.0199→0.0202 | +0.93% | 524304 |

高容量饱和反馈新增约 0.0645 ms GPU p50，是尚未解决的回退，不能标记本项性能验收完成。读取量两边严格相等；CPU submit p50 变化在 -1.89%～+1.67%，poll p50 变化在 -2.86%～+3.33%。后续需要隔离页映射寻址／需求统计的成本，并测试真实历史分布及 ACK／增长维护路径；当前结果不能证明回退来源，也不替代完整网络／事务／整帧测试。

p99 从逐样本数据计算，各次 p99 再取中位数：普通四组 GPU 为 0.529408→0.559104、0.616448→0.616448、0.129024→0.133120、0.159744→0.166912 ms；CPU 为 0.0598→0.1017、0.0667→0.0702、0.0250→0.0322、0.0685→0.0741 ms。反馈六组 GPU 为 0.022528→0.020480、0.029696→0.027648、0.047104→0.047104、0.021504→0.018432、0.033792→0.037888、0.102400→0.172032 ms。每次只有 40 样本，p99 接近最大值，不据此下稳定尾延迟结论。未新增默认算法候选。

验证分层保留实际时间顺序：该轮 JVM 结果为 680 项、零失败；较早的完整修复专项和 Hex／Iris 回归通过，其后将页需求统计迁入异步头并完善消费屏障，分页专项通过 409517 条断言，覆盖一页争用、20 槽环绕、部分 ACK、迟到 ACK、在途快照、消费异常重试、带历史扩容、嵌套状态退休及 131072 唯一页／全 ACK 回收。当轮最后一次联合回归因自动审批额度耗尽被拒绝启动，GameTest 也未重跑；额度恢复后的补跑与捕获修复见下一节。存储测试不改变 148 项原版行为的严格未验收状态。

复现时先保存基线，再修改实现；已有暂存变更不得被覆盖：

```powershell
python -B scripts/particles/prepare_package_baseline.py --ref INDEX
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache --no-parallel -I scripts/particles/validation.init.gradle -I scripts/particles/environment-benchmark.init.gradle
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache --no-parallel -I scripts/particles/validation.init.gradle -I scripts/particles/environment-benchmark.init.gradle -PpackageEnvironmentFeedbackBenchmark
.\gradlew.bat test validatePackageGpu validateHexSharedIrisGpu --offline --no-configuration-cache --no-parallel -I scripts/particles/validation.init.gradle -PpackageHistoryPages
```

修改前补丁、基线描述、原始 CSV／样本／metadata、专项与 A/B 日志保存在忽略的 `build/benchmarks/gpu-runtime-pages-20261017/`；最终 A/B 子目录为 `ordinary-final` 和 `feedback-final`。未提交原始数据，没有自动暂存、提交、推送或发布。

## 缺页统计修复与补跑

额度恢复后，原来受阻的 `test assemble`、包裹完整修复、Hex／Iris、观察流、光照和包裹 shaderpack 联合回归已成功补跑，日志为 `final-validation.log`。随后隔离捕获回退：原实现每个命中的位图字都对持久映射头进行缺页原子累加。正式修复将统计保存在已有设备端分配器 scratch 字段，第二遍捕获经原有 barrier 后一次取出清零并发布到头部。没有新增正常路径的 dispatch、上传、读回、clear 或 SSBO 绑定；捕获中断的残留统计在下一次可用捕获前清理，不改变历史或 ACK。

隔离分支只保存在忽略的构建目录：去掉需求统计是定位用负对照，缺失必要逻辑，禁止进入正式路径；工作组合并映射头原子写仍有高容量回退，否决；每次显式清理设备计数增加空场景固定 GPU 成本，否决；工作组 scratch 汇总也增加高容量空场景固定成本，否决。没有将这些候选保留在生产源码。最终方案是统计的存储／发布修复，没有切换第五批模拟、排序或装饰节流算法。

另保存修改前分页实现 `WORKTREE@6b27b6ff10fce3264f9d7bd1da755db8f3fc4cc3+420b7cd4b39ee5d097cee8585004ee5fd5e40324b364ee1e2320a6ec8f5bbeb4` 为独立基线。它与 INDEX 基线分别交替运行五次，每次预热 5×20 次后采样 40 次。计时前验证所有事件的生命周期／序号及唯一身份，采样中继续验证每轮数量；读取量仍为 16／17424／524304 B。此处只测 capture、提交及 fence 后有效记录 poll，首次分配、页增长／ACK 回收、世界事务、网络和绘制均不在范围内。

相对修复前分页版本，高容量 512 条的 GPU p50 为 **0.169984→0.095232 ms（-43.98%）**，p95 为 0.183296→0.102400 ms（-44.13%）；131072／17 条 p50 下降 25%。8192／512 条 p50 增加 4.76%，131072／空 p50 增加 1.024 µs（+6.25%），各组 CPU poll p95 仍有不同方向波动；不宣称所有场景或尾延迟都改善。

最终实现相对原 INDEX 基线，单位 ms，各次分位数取中位数：

| 容量／事件 | GPU p50 基线→当前 | GPU p50 变化 | GPU p95 变化 | CPU poll p50 基线→当前 | CPU poll p95 变化 |
| --- | --- | --- | --- | --- | --- |
| 8192／0 | 0.016384→0.016384 | +0.00% | +4.55% | 0.0099→0.0099 | -52.73% |
| 8192／17 | 0.022528→0.022528 | +0.00% | +0.00% | 0.0115→0.0116 | +0.00% |
| 8192／512 | 0.044032→0.045056 | +2.33% | +2.17% | 0.0311→0.0320 | +1.23% |
| 131072／0 | 0.016384→0.016384 | +0.00% | +9.52% | 0.0078→0.0080 | +0.00% |
| 131072／17 | 0.027648→0.027648 | +0.00% | +3.03% | 0.0086→0.0089 | +12.37% |
| 131072／512 | 0.101376→0.100352 | -1.01% | +0.97% | 0.0242→0.0254 | +21.71% |

原约 65% 的高容量饱和 GPU 中位回退已消除；六组 GPU／CPU poll／提交 p50 的回退均不超过 5%，但 CPU poll p95 在 131072／17、512 条分别增加约 1.2、6.1 µs，空输出 GPU p95 增加 2.048 µs。尾分位门槛仍未关闭，不能将本表当作第五批算法候选或整帧的准入证据。饱和场景 GPU／CPU poll p99 分别为 0.106496→0.112640、0.0335→0.0381 ms；40 样本 p99 接近最大值，完整逐样本保留在忽略目录。

正式统计修复后的 JVM 680 项与分页专项 409595 条断言通过，新增 `captureDemandStatistics` 检查字／工作组边界、终止和依赖等待排除、重复重传、四槽满环、设备 scratch 清零与中断残留处理。随后完整包裹修复 1537775 条断言、实际 Create 6.0.10 的 25 项环境／输出 GameTest 均通过，日志为 `device-counter-integration.log`。最终 `assemble` 和包含冻结分页布局的基线编译通过，递归检查发行 JAR 及内嵌 JAR 未包含验证夹具。原版严格差分仍未完整，存储页池不等于有序效果 journal；增长维护成本、真实多客户端、服务器尾延迟和整帧测量继续保持未验收。

复现独立修复 A/B 时，须在修改前保存分页工作区，且使用新的输出目录保护已有基线：

```powershell
python -B scripts/particles/prepare_package_baseline.py --ref WORKTREE --output build/package-history-capture-baseline
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache --no-parallel -I scripts/particles/validation.init.gradle -I scripts/particles/environment-benchmark.init.gradle -PpackageEnvironmentFeedbackBenchmark -PpackageEnvironmentBaseline=build/package-history-capture-baseline
```

正式修复的 A/B、逐样本与 SHA-256 metadata 分别位于 `device-counter-ab`（旧分页基线）、`device-counter-index-ab`（原暂存基线），正确性日志为 `device-counter-correctness.log`。metadata 现在记录实际选用基线的标识和摘要，避免多个基线混用。原暂存二进制差分 SHA-256 保持不变。

真实客户端验收按用户要求交由用户执行。[隔离启动与检查清单](../gpu-runtime-client-acceptance.md)已提供，准备任务已运行并核对 Sodium／Trickster、其余运行时 JAR 和独立配置输出；未启动修正后的客户端，不记为 Mixin／画面通过。此前临时启动因遗漏 Sodium 在加载期失败，日志保留为 `client-validation.log`，不能计入成功样本。没有用户结果的实机场景继续保持未完成。
