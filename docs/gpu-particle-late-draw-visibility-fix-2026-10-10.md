# GPU 普通粒子不可见修复（2026-10-10）

已修复“当帧包裹/MODEL 阴影改动后，光影路径 additive 与 textured 能进入池但不绘制”的帧生命周期错误，同时修复普通包裹绘制覆盖后续排序绑定的问题。实际 GPU framebuffer 检查覆盖 ADDITIVE、ALPHA、OPAQUE、MODEL 与 Hex 图案；包裹与 MODEL 的 Iris 回归通过。尚未进行 Minecraft 内的画面复验。

## 根因与参考

NeoForge 的 `AFTER_LEVEL` 在 `GameRenderer.renderLevel` 中、调用 `LevelRenderer.renderLevel` **返回后**派发。上一轮的 `ParticleLevelFrameMixin` 只包围这个内部调用，并在其 finally 调用 `leaveLevelFrame`。该清理先将 frameArmed/frameAttempted 清零，再触发 AFTER_LEVEL，导致 endFrame 提前返回：池中数据已经提交，所有晚阶段绘制却都没执行。

包裹与 MODEL 的合并绘制发生在 LevelRenderer 调用内部，阴影也在那里，因此仍然正常。这不是包裹/MODEL 是否存在的条件判断问题；仅有普通粒子的场景也会被截断。ADDITIVE、ALPHA、OPAQUE、Hex 以及 MODEL 合并失败时的普通 fallback 都受到相同影响。

本次按用户要求参考 `.refs`：

- `.refs/neoforge-21.1.227/net/minecraft/client/renderer/GameRenderer.java` 的 LevelRenderer 调用与 AFTER_LEVEL 派发顺序。
- `.refs/neoforge-21.1.227/net/neoforged/neoforge/client/event/RenderLevelStageEvent.java` 对 AFTER_LEVEL 的文档说明。
- `.refs/Iris/common/src/main/java/net/irisshaders/iris/mixin/MixinLevelRenderer.java` 的管线初始化与 finalizeLevelRendering 窗口。

另以项目实际 NeoForge 21.1.236 的已解析 GameRenderer 字节码验证：AFTER_LEVEL 确实位于内部调用之后。引用副本作为源码证据，不执行其文档中的指令。

## 改动

1. 使用 WrapMethod 包围整个 GameRenderer.renderLevel，在最外层 finally 清理帧 scope。原 WrapOperation 只在主相机矩阵准备完成的内部调用点捕获 Camera、DeltaTracker、view/projection；不再在那里结束帧。这样 AFTER_LEVEL 能消费当前提交并完成晚阶段绘制。提前阴影 compute、AFTER_SKY 复用、嵌套/辅助隔离与每帧一次提交规则保留。
2. 每个普通材质 pass 显式绑定自己需要的当前池、emitter、排序/排列、VAO 与 commands。无光影的普通包裹绘制和 light sampling 会覆盖 SSBO 7；原先只在整个 draw 阶段开头绑定一次排序数据，后续 MODEL/ALPHA 会误读包裹光照 buffer。现在 MODEL/ALPHA 在各自入口重新绑定主排序，与阴影列表独立。
3. 提前 compute 的恢复范围补齐 SSBO 0–31。实际 update、Hex、identity/dispatch 路径使用高位 binding，原边界只恢复 0–15，而 unbindShaders 清除 0–31；这会破坏原先的高位外部 binding/range。新增测试覆盖 16/24/27/31 的非零 range。
4. 每个 compute 帧清除 MODEL/package 的旧绘制仲裁，避免上一帧异常后的“已绘制”状态进入下一帧。Hex 原本已有 glUseProgram，不存在该 program 选择缺失；本次为它补齐绘制结束后的 program/VAO 清理，并增加实际像素回归。

物理步长、补步和协议未修改；没有改变粒子材质、主视锥、透明排序或淡出参数。

## 验证

驱动为 NVIDIA GeForce RTX 4070 Laptop GPU，OpenGL 4.5 / NVIDIA 581.15，隐藏 GLFW context。

| 检查 | 结果 |
| --- | --- |
| 最终 build / Java | 566 tests，0 failures/errors/skips；Sable 缺席检查通过 |
| NeoForge 生命周期 | 实际字节码顺序、外层 WrapMethod、内层仅捕获、finally 异常清理与旧仲裁清除检查通过 |
| 完整粒子 GPU | 135,778 断言；kernel/graphics shader 编译、pool/dispatch/sort/rollback，以及新增像素检查通过 |
| 逐类像素 | ADDITIVE-only、ALPHA-only、OPAQUE-only、普通混合、含 MODEL 混合、空池、视锥外，正常提交与提前 compute 恢复两种状态边界均通过 |
| 外部覆盖绑定 | 模拟包裹/光照覆盖 draw SSBO 后，ADDITIVE/ALPHA/OPAQUE/MODEL 仍使用正确当前池和排列；MODEL fallback 实际像素通过 |
| Hex 图案 | 使用真实 hex_pattern 顶点/片元 shader 与间接 command 的实际像素检查通过 |
| 外部高位 SSBO | 16/24/27/31 非零 offset/size 在提前 compute 清理后恢复通过 |
| 包裹原生 Iris | 26,733 断言通过 |
| MODEL 原生 Iris | 4,133,200 断言通过，含实际 400 万容量、阴影独立列表与 fade 隔离 |

[GPU/Iris 日志](benchmarks/particle-late-draw-visibility-2026-10-10/particle-visibility-regression-2026-10-10.log)、[最终构建日志](benchmarks/particle-late-draw-visibility-2026-10-10/particle-visibility-final-build-2026-10-10.log)。粒子日志里的 reload ERROR 来自主动注入坏 shader 的 atomicReload 失败保留测试，随后 PASS，属于预期测试输出。

```powershell
.\gradlew.bat -I scripts/particles/validation.init.gradle build validateHexGpu validatePackageIrisGpu validateModelShadowIrisGpu --no-parallel --max-workers=1 --console=plain
```

无光影的原 AFTER_SKY → AFTER_LEVEL 路径保留；有 Iris 但无启用光影包时使用新的完整 scope。两种 GPU 提交/绑定边界均测试，但这些 driver 与 ABI 夹具不是 Minecraft 中的实际 mixin 应用或光影包切换视觉测试。待游戏内复验单独/混合粒子，以及光影开关、重载与 MODEL 合并 fallback。
