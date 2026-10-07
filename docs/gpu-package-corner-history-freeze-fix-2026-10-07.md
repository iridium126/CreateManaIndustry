# 旋转边角与静态 section 历史版本冻结

## 本轮日志

新 `debug.log` 截至 16:20:06，有 59 条 `MOVING_SWEEP`（5 条采样时仍冻结）和 3401 条 `SECTION_MISSING`（全部采样时已恢复）。上一轮的 `MOVING_GEOMETRY` 和 `ENV_BACKPRESSURE` 没有再次出现。

仍冻结的扫掠样本来自结构 3 的节点 9 / 11 / 12，全部耗尽 32 次推进；时间分别包含 `0.9982149`、`0.5093092`、`0.21701819`、`0.0064723133`、`0.81564176`。大量 section 样本集中在 `(-1,4,1)`、step 1493。它们是积压的历史诊断记录，不能解读为同时有 3401 个包裹持续冻结。汇总中的 section 冻结为少量暂时事件，随后恢复。

日志没有完整结构姿态、碰撞盒，也没有缺失 section 当时要求的 revision，不能逐条精确重放。以下修复对应代码中确认并复现的两种失效机制，原存档仍需复测。

## 只有边角轴能证明分离的扫掠

旧推进只使用三个 OBB 面的局部界限。当包裹位于旋转盒的边角处，三个结构面轴可能都重叠，而世界面轴或边/边轴仍分离。此时只能退回使用整个结构半径的速度界限；明明不碰撞的微小间距路径也可能耗尽 32 次迭代。

回归使用 `192 × 1 × 256` 面板，初始绕 Z 轴旋转 0.3 弧度，一步再旋转 0.01 弧度。包裹沿最高边角的切线运动，初始间距约 0.0004 块。1025 个独立双精度 SAT 参考点确认整条路径分离，但旧的完整物理管线仍冻结。

修复在结构面界限不能覆盖剩余时间时，使用当前 SAT 的实际分离法向作为固定世界轴。对八个变换后顶点分别求投影距离的保守二次推进区间，取最小值，证明整个旋转盒始终位于包裹的分离平面之外。该轴可以来自世界面或边/边分离，不依赖它在后续时刻仍为 OBB 面轴。保留旋转速率误差、平移及缩放界限。

仍使用 32 次推进预算、原有碰撞精度和无法确认时的冻结保护，没有增加物理 dispatch 或 Body 字段。针对性 GPU 检查通过 51,686 项断言，包含完整边角路径、上一轮擦边与真实中途碰撞，以及 384 个旋转/平移/缩放安全区间参考样本。

## 已上传的旧 section 丢失

原静态 atlas 替换当前 slot 后只保留提交中的 GPU 表 fence。之后再请求旧历史版本时，历史视图只检查当前 slot；即使旧版本曾经完整上传，也会变成缺失 section。移动结构已有版本保留，而静态 section 原来没有对应机制。

新增不可变版本引用计数及旧 slot 元数据。GPU 上传前登记尚未消费的输入引用，历史视图按 section 与 revision 精确选择当前或保留的旧 slot；实时视图继续只选择当前版本。旧几何不用于代替未上传的不同版本。

存储仍是原来的 `capacity × 2` 个 slot。没有空闲 slot 时替换继续等待，不覆盖历史或在途数据。物理 tick 提交并提交时钟后释放输入引用；GPU 表自身的 fence 继续保护已经提交的读取。诊断输入映射保留到正常过期，消费标记保证不重复释放。这样已处理 tick 不会继续占满替换槽。

GPU 回归验证：不保留的参考路径复现缺失冻结；保留后旧空气版本允许下落，新固体版本正确阻挡；两个 slot 都占用时第三版本不能覆盖；释放引用后新上传恢复；保留的固体历史继续碰撞；过期历史表不能重用已经释放的 slot。针对性历史检查通过 69 项断言，包含既有版本隔离和 LRU 回归。

四项历史引用单元回归覆盖重复 revision、最后引用过期、同 tick 不可重写、可变输入隔离、200 TPS 区间、消费释放、重复消费与跳过输入。真正没有上传的版本、未捕获 section、存储或在途容量压力仍可能产生必要的暂时等待。

## 最终验证

```powershell
.\gradlew.bat validatePackageGpu -PpackageMovingAdvance --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat validatePackageGpu -PpackageWorldHistory --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat test validatePackageGpu build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

516 项单元测试（103 个套件，无失败、错误或跳过）、14,308,630 项完整 GPU 断言、无 Sable 桥接检查及正式构建通过。发布包为 `build/libs/createmanaindustry-0.2.6.jar`，本轮着色器与主要/历史元数据类已核对匹配源文件和编译输出。

日志：`build/moving-corner-before.log`、`build/moving-corner-targeted.log`、`build/world-history-targeted.log`、`build/package-corner-history-final.log`。驱动为 NVIDIA GeForce RTX 4070 Laptop GPU / OpenGL 4.5 / 581.15。未在原存档复测，也未测量整帧开销。
