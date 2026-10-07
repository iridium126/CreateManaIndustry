# HexJIT 实现审查（2026-10-06）

后续六项修复与性能结果见 [修复和性能记录](hexjit-fixes-performance-2026-10-06.md)。下面保留原始审查基线与复现证据。

审查基线：主仓库 `3ca6dc44477f3f36b1a759f5eb103ed89a33c2a8`；`.refs/HexMod` 为 `32c65158c2fba61aa281bd460330435c6f7ea5d2`。运行依赖为 Hexcasting `0.12.0-devel-pre-53`、Minecraft 1.21.1、Java 21。

结论：算术热路径与重复 Tick 法术有明显性能收益，但默认 AUTO 快路径存在五项已复现的正确性问题。另有编译缓存容量契约问题，当前生产调用只使用三个固定 Description，实际风险较低。此次只审查和验证，没有修改生产实现。

## 已复现的正确性问题

### 1. [P1] 跳过整个 postExecution 会丢失原版玩家环境行为

位置：`mixin/hexjit/CastingVMMixin.java:154`，`compat/hexcasting/jit/ExecutionScope.java:355`，`FastTickAction.java:29`。

`canSkipEmptyCallbacks()` 直接复用 `canMutateTickUserDataInPlace()`。后者允许没有 PostExecution 扩展且继承原版 `PlayerBasedCastEnv` / `StaffCastEnv.postExecution` 的环境。这只能说明回调不会保存 image，不能说明它没有行为。

HexMod 的 `PlayerBasedCastEnv.java:76` 会发送事故消息，并更新 ambit / sentinel 半径；`StaffCastEnv.java:39` 还会播放施法声音并更新 `soundsPlayed`。默认 `skipEmptyPostExecution=true` 会跳过这些行为，且不限于 Tick 或已编译 action，普通法杖施法也受影响。

探针使用继承 Staff 回调、仅计数 `sendMishapMsgToPlayer` 的环境，对缺少参数的 Tick 施法：

```text
HEXJIT_REVIEW_MESSAGES off=1 auto=0
```

建议：分别判断“回调不会保存状态”和“回调真正为空”。整段跳过应限制为没有扩展、实际方法来自空实现 `CastingEnvironment.postExecution` 的环境；玩家／法杖环境继续调用，或仅在基类扩展分发层跳过明确声明可跳过的观察者。

### 2. [P1] 批处理读取基类缓存，忽略环境覆写后的 maxOpCount

位置：`compat/hexcasting/jit/FastLoopTickDispatch.java:135`、`:190`；缓存来源是 `mixin/hexjit/CastingEnvironmentMixin.java:56`。

Mixin 缓存的是 `CastingEnvironment.maxOpCount()` 基类方法返回值。环境子类若实现 `Math.min(super.maxOpCount(), localLimit)` 或 `Math.max(...)`，调用 super 时会保存基类值，实际虚方法返回值却是另一个上限。批处理中 `commitIntermediate` / `commitPendingIntermediate` 直接读取 scope 内基类值，绕过子类规则。

用继承 Staff 的环境将上限限制为 3，执行 Eval 后的四次 Tick：

```text
HEXJIT_REVIEW_OP_LIMIT limit=3 offTicks=2 autoTicks=3 offOps=3 autoOps=4
```

AUTO 在最终外层 VM 检查之前已经提交一次额外 Tick 副作用。提高上限的子类则可能过早触发事故。HexMod 原版在每一步调用实际环境的 `maxOpCount()`，不存在这种区别。

建议：批处理中继续调用 `env.maxOpCount()`；现有基类 Mixin 仍可缓存基类配置读取。若需要缓存最终返回值，应在完整虚调用的返回点保存，且限制为上限在当前 cast 内稳定的环境。

### 3. [P1] 直接 Tick 分发丢失 executeInner 的异常隔离

位置：`compat/hexcasting/jit/FastLoopTickDispatch.java:304`、`:320`；入口为 `mixin/hexjit/FrameEvaluateMixin.java:99`。

正常 FrameEvaluate 经 `CastingVM.executeInner` 调用 Iota，后者捕获 `Exception` 并返回 `MishapInternalException`。缓存 Tick 命中后直接调用 `FastTickAction`，只捕获 `Mishap`，没有保留 VM 的普通异常兜底。自定义环境的预检查／媒体检查异常可以因此逃出 `queueExecuteAndWrapIotas`。该直接分发也能作用于顶层 Frame，不只元施法循环。

同一个已缓存 Tick pattern，环境 `precheckAction` 抛出 `IllegalStateException`：

```text
HEXJIT_REVIEW_EXCEPTION off=ERRORED autoEscaped=true
```

建议：在直接执行边界复刻 `executeInner` 的异常转事故语义；副作用异常继续由原版对应边界处理。异常后不能重新执行 action，否则会重复成本或副作用。

### 4. [P2] Tick 子栈校验跳过没有证明输入栈已通过大小校验

位置：`compat/hexcasting/jit/FastTickAction.java:224`，`ExecutionScope.java:441`；消费点是 `mixin/hexjit/CastingVMMixin.java:45` 和 `FastLoopTickDispatch.java:128`、`:183`。

`rememberTickSubstack()` 无条件为去掉一个参数后的输出栈建立跳过标记。其假设是输入已通过序列化大小检查，但 `CastingVM.queueExecuteAndWrapIotas` 开头的 `validateIotaList` 仅验证单个 Iota 的有效性，没有检查总大小或深度。CastingImage 的 Codec 也不保证整个 stack 满足总大小限制。

在初始栈中放入 1024 个 DoubleIota 和一个目标向量，执行一次 Tick，移除参数后仍超限：

```text
HEXJIT_REVIEW_OVERSIZED off=ERRORED auto=EVALUATED offOps=0 autoOps=1
```

原版拒绝提交输出和副作用，AUTO 却执行成功。对于度量可变化的扩展 Iota，也不能仅凭 TreeList 容器不可变就证明度量稳定。

建议：只在确实验证过的输入栈上建立子栈证明，且要求保留元素的 size/depth 稳定；首次 Tick 和存在动态扩展 Iota 的路径保留检查。不要简单新增一个总是拒绝初始栈的前置检查，因为原版允许 action 先缩小初始栈，再检查输出。

### 5. [P2] 没有 PostExecution 观察者不足以证明 CastingImage 独占

位置：`compat/hexcasting/jit/FastLoopTickDispatch.java:201`，`mixin/hexjit/CastingImageLoopMixin.java:32`；资格检查是 `FastTickAction.java:77`。

批处理中把 Kotlin data class `CastingImage` 的 final 字段改成可变，再直接改写 stack / opsConsumed / userData。资格门只检查 PostExecution 扩展和 postExecution 的声明类，没有排除预检查等环境方法保存 VM 当前 image 的情况。一个继承 Staff 回调、覆写 `precheckAction` 来保存快照的环境依然进入此路径。

对四次 Tick 的元施法循环，在每次预检查保存当前 image：

```text
HEXJIT_REVIEW_SNAPSHOTS count=4 ops=[1, 4, 4, 4] secondAndThirdSame=true
```

第二、第三、第四步保存的快照实际指向同一对象，并全部变成 ops=4。原版每次返回新的 image，保存的历史字段不会被后续步骤覆盖。Tick userData 的复用也存在相同的独占性要求。

建议：保留不可变 image，优先优化其内部计算和中间 CastResult；若继续原地更新，需要明确的环境独占状态契约并证明所有可观察入口均满足该契约。仅依赖一个回调列表为空不能证明这一点。

## 编译缓存边界问题

### 6. [P3] 淘汰 pending site 不释放其 Future / Product，预算不覆盖这些字节码

位置：`compat/hexcasting/jit/CompilationCache.java:113`，`:82`，`:64`。

`evict()` 只释放已链接 unit，不维护 `pendingByDescription` 的引用计数。已淘汰的 site 若不再访问，完成的 Future 会继续持有 Product.bytes；映射只在 acquire 消费产品或 close 时移除。`worker.purge()` 仅清理取消任务，此处也没有取消。`residentBytes` 只统计已链接产品。

用 `threshold=1, maxEntries=2, maxBytes=1` 依次提交 200 个不同描述，每个 site 仅访问一次：

```text
HEXJIT_REVIEW_CACHE entries=2 pendingDescriptions=200 accountedBytes=0
```

这证明 CompilationCache 本身没有实现描述／产品总量有界的契约。当前生产调用的描述只有普通 Action、括号 Action 和 Operator 三种，因此不能把该通用探针结果解释为当前玩家可无限制造编译描述的漏洞。

建议：为共享 pending unit 维护引用数，最后一个 site 淘汰时取消／移除，并将待编译产品与已链接产品一起纳入总容量管理。补充共享任务和孤儿产品的测试，现有 LRU 测试只检查 entries 数量。

## 实现与性能判断

当前实现更接近“解释器调用与状态处理专门化”，不是整个 Hex 法术的编译器：

| 层次 | 当前作用 | 性能判断 |
|---|---|---|
| CallCompiler / CompiledCall | hidden class 中生成 CHECKCAST + invokevirtual / invokeinterface | 没有融合多条指令，也没有绑定具体 action；生成 stub 本身收益有限 |
| ArithmeticSite | 直接读取顶部参数、缓存最近类型组合对应的原版 Operator | 消除 ArithmeticEngine 对整个栈的 Stack 拷贝，深栈收益明显 |
| PatternIota / 工厂缓存 | 缓存注册 action 与静态匹配；特殊处理器仍逐次 tryMatch | 保留环境相关匹配这一设计是正确的 |
| FastTickAction / FastLoopTickDispatch | 去掉中间结果分配、重复匹配、媒体扫描与部分逐步回调 | 重复 Tick 的主要收益来源，也是上述正确性问题集中区域 |
| ExecutionScope / TreeList 缓存 | cast 内缓存、共享循环后继与 continuation | 适合重复同一法术；持久后继链会提高长法术对象的保留内存，需要另外测 retained heap |

本机原始两项 GameTest 的一次完整运行数据如下，日志为 `build/hexjit-review-gametest.log`。算术行取各模式三轮的中位数；参考法术使用测试本身输出的中位数。

| 工作负载 | OFF | AUTO | 观察 |
|---|---:|---:|---|
| 原始 Tick 参考法术（92160 次 Tick，99510 ops） | 1472.90 ms | 28.83 ms | 约 51.1 倍；整个优化组合的结果 |
| 算术 add，栈长 2 | 756.89 ns/op | 129.13 ns/op | 分配中位数 696 → 336 bytes/op |
| 算术 add，栈长 64 | 633.03 ns/op | 204.57 ns/op | 分配 1824 → 944 bytes/op |
| 算术 add，栈长 1024 | 4577.96 ns/op | 144.73 ns/op | 分配 9504 → 944 bytes/op |

重要限制：

- 参考法术的 OFF/AUTO 粒子回调数为 92160 / 1，且默认 AUTO 跳过 Staff 回调。51.1 倍包含配置允许的装饰合并和本文发现的行为遗漏，不能称为严格保持全部原版可观察行为的 51.1 倍加速。
- 算术测试直接调用 ArithmeticEngine.run，不包含完整 VM、观察者和副作用开销，不能外推所有法术的加速倍数。
- 切换 `compileActions` 的首轮测量为 44.83 / 32.88 ms（开启／关闭），另一次运行接近 27.17 / 26.58 ms。没有证据支持默认启用普通 Action 字节码 stub；保持默认关闭合理。
- 冷法术现有测试只比较 AUTO 内的开关组合，缺少同条件 OFF 基线。首轮 empty_list 为 2056 ns/cast，开启 fastStackValidation 为 2181.5 ns/cast；小幅差别需更多样本，不能宣称冷路径全面受益。
- 快速随机门明确改变世界 RNG 流，而且默认关闭。这是已声明的行为选项，不列为隐蔽正确性缺陷。

值得保留的设计：编译线程只消费不可变 Description；hidden class 不持有 receiver / image；调用异常不被编译失败处理捕获后重放；动态特殊 handler 不进入注册 Action 缓存；算术选择仍复用原版缓存与候选顺序；扩展 Iota 度量通常保留动态调用；服务器结束和 epoch 变化提供清理点。

## 验证与复现

已有单元测试 9 项通过；原始 GameTest 2 项通过。独立探针添加为单独 GameTest batch 后，最终 3 项全部通过：第三项的断言是确认缺陷差异存在，不是确认实现正确。

```powershell
.\gradlew.bat test --tests 'com.iridium126.createmanaindustry.compat.hexcasting.jit.*' --offline --console=plain
.\gradlew.bat -I scripts/hexjit/gametest.init.gradle runGameTestServer --offline --console=plain
.\gradlew.bat -I scripts/hexjit/gametest.init.gradle -I build/hexjit-review-probes.init.gradle runGameTestServer --offline --console=plain
```

探针源码：`build/hexjit-review-src/com/iridium126/createmanaindustry/compat/hexcasting/jit/HexJitReviewProbes.java`。最终探针日志：`build/hexjit-review-probes.log`；六项结果以 `HEXJIT_REVIEW_` 开头。探针和日志均位于 ignored build 目录，clean 会删除它们。

建议先修复回调保留、实际 op 上限和异常隔离，再处理验证证明与状态独占性；修复后重新测 Tick 参考法术。应增加普通 Staff 环境的声音／事故消息、覆写 maxOpCount、初始超限栈、抛异常环境、被保存的历史 image 等差分用例。现有带自定义 postExecution 的测试环境会被资格检查排除，无法覆盖默认 Staff 的整段回调跳过问题；现有默认 op limit 也不能覆盖覆写限制。
