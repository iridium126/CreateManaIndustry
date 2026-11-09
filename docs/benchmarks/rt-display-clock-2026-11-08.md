# RT 周期性运动停顿：显示时钟修复

RT 原生输入每次客户端 tick 都生成递增的逻辑 tick，世界 `gameTime` 另作玩法元数据。此前 `StormProjectionGpu` 却把 `gameTime` 用作显示历史时间戳，渲染时又使用通用 GL 模拟时钟的世界 tick。Minecraft 的时间同步包会直接设置客户端世界时间；重复、回拨或跳号不代表原生模拟缺失了一步。旧播放逻辑会把这种校时判为历史中断，或等待世界时间追上单调钳制后的时间，造成仅 Storm 运动的短暂停顿。

本次将 GPU 显示历史时间戳改为 `FrozenDisplay.tick()`，渲染时间改为最新捕获的原生输入逻辑 tick 加客户端 partial tick。播放时钟不跟随 GPU 完成游标，所以真正的排队迟到仍会耗尽缓冲并计数；没有通过减慢时钟、外推或增加延迟掩盖问题。服务器时间继续用于攻击姿态、网络反馈和权威修正。出生、传送、身份变化、真正缺号和暂停沿用原有恢复规则。

显示仍延迟两个 20 Hz tick，约 100 ms。CUDA 继续直接写共享显存，GL 继续直接读取；本次只调整 CPU 侧的少量时间元数据，不增加位置读回、上传、GPU 等待或资源注册。ABI 13 和预编译 DLL 不变。

## 回归证据

- `StormPlaybackClockGpuValidation` 使用生产 `StormProjectionGpu` 和真实 GL 计算着色器，注入重复、回拨和前跳的世界时间，同时模拟可变完成相位和 30/60/144/240 FPS。旧代码在 30 FPS、逻辑 tick 21.3333 时输出位置 19.1111，预期为 19.3333；修复后 11,188 项检查通过，全部维持两 tick 延迟，不触发校时锚点恢复。
- `StormNativeInputClockTest` 验证世界时间从 100,000 开始、反复校正时，输入捕获、延迟完成和播放仍处于同一逻辑时间域；另验证无完成回执时播放时钟仍前进，避免隐藏实际积压。原有显示时钟测试保留暂停、缺号、身份切换及恢复覆盖。
- 相关 Storm 与原生契约单元测试 90 项通过；原生共享显示及碰撞索引 GPU 夹具 26,435 项检查通过。65,536 成员连续运行 30.10 秒，600 个 tick 全部提交、消费和退休，关闭后原生分配为零。结果见 `build/particle-engine/rt-display-clock-2026-11-08/`。这些离屏测试证明时钟与交接行为，不能代替游戏中原场景的视觉及帧时间复验。

帧采集的 `-storm-display.csv` 接收 tick 和样本年龄使用原生逻辑时间域，JSON 的 `native_display_time_domain` 明确记录这一含义；`-storm-input.csv` 仍记录世界 tick。重复使用一个已完成样本不等于播放停止。

运行方式：

```powershell
.\gradlew.bat -I scripts/particles/gpu.init.gradle test --tests '*NativeStormDisplayClockTest*' --tests '*StormNativeInputClockTest*' runParticleSuite -PparticleSuite=StormPlaybackClockGpuValidation -PparticleOutput=build/particle-engine/rt-display-clock-2026-11-08 --no-configuration-cache
```
