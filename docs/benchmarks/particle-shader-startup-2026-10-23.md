# 世界进入阶段的着色器编译阻塞修复

## 定位

用户的 `run/logs/debug.log` 在进入世界的渲染初始化段出现长时间空白，随后恢复输出；单凭日志无法取得空白段的原生调用栈。代码审计发现 `ClientGpuScene.computeFrame` 无条件调用 `ParticleRuntime.prepareResources`，后者在渲染线程一次性重建全部23个粒子程序；旧编译器紧接 `glCompileShader/glLinkProgram` 查询编译／链接结果，驱动尚未完成时会同步等待。资源重载还会在源码完全未变时重复这一路径。

在本机 RTX 4070 Laptop、NVIDIA 581.15、OpenGL 4.5 上，用生产展开源码和无语义宏生成新驱动缓存键，复现了分钟级驱动链接：

| 程序 | 提交链接耗时 | 等待链接完成 | 等待期间推进的渲染循环 | 完成轮询最大耗时 |
| --- | ---: | ---: | ---: | ---: |
| `update.comp` | 0.068ms | 116.279s | 38541 | 1.504ms |
| `dome_contacts.comp` | 0.020ms | 62.796s | 20611 | 1.629ms |
| `dome_path.comp` | 0.025ms | 63.619s | 21117 | 0.991ms |

这证明生产着色器链接足以形成所报告的分钟级卡顿；它不是所有存档加载耗时都来自着色器的证明，也没有取得原游戏卡顿瞬间的线程转储。没有修改地形加载、存档或风暴运动算法。

## 实现与边界

- `GpuShaderCompiler.Compilation` 将提交、完成轮询、结果检查和所有权移交分开；支持 ARB 扩展时，只在完成后读取编译／链接结果和错误日志。
- `ParticlePrograms` 每帧推进一个编译步骤，临时请求2个驱动编译线程；结束、取消或失败时恢复原线程提示。每5秒输出正在等待的程序，不再静默等待。
- 完整候选集成功后一次性替换全部23个程序。热重载期间保留旧程序；失败清理候选，不清空工作集；关闭和源码回滚也取消在途候选。
- 缓存已提交的展开源码，源码未变时复用程序，不触发粒子身份失效；同一在途源码不重复启动编译。
- 首次编译完成前，CMI共享粒子场景暂不可用，正常世界渲染继续。编译总时间没有被消除，不宣称秒开所有 GPU 效果。
- 无 ARB 扩展的驱动保留每帧一个步骤的同步兼容路径并警告，不能承诺其冷链接不阻塞；扩展线程数本身也只是驱动提示，不是硬实时保证。[Khronos规范](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_parallel_shader_compile.txt)

协议、存档、配置和模板格式不变，没有新增网络上传、Java后台GL上下文或每帧GPU读回。

## 自动化证据

原始日志和CSV保留在忽略目录 `build/shader-startup/`：`probe.log` 为单程序冷链接探针，`cold.log` 为首次生产异步生命周期冷变体，`regression.log` 为扩展失败／取消回归，`final.log` 为最终冷编译及构建。每次运行的元数据由GPU初始化脚本保存，不能以源码节点数或测试断言数量替代响应耗时。

首次生产异步冷变体：23程序组117.780s，期间38775次渲染循环、一次原子发布；单次 `rebuildWithSources` 最大98.017ms（含源码展开、分配、提交和轮询）。最终唯一冷变体复测119.166s、40154次循环、一次发布，最大38.642ms；最终夹具的全部场景最大87.624ms（首次失败场景源码读取）。这是隐藏窗口测试，不是游戏加载屏幕帧率。扩展回归验证首次编译失败、缺失资源、语法错误和链接接口错误、在途链接取消、热重载失败保留旧句柄、同源码复用、成功替换及重复关闭；真实驱动通过。

相关真实GPU回归通过：旧程序生命周期405项、场景帧72764项、MODEL阴影／Iris 4133508项。最终 `test check build` 通过，803项JVM测试无失败、无跳过；发行包审计无缺失owner或测试夹具泄漏，产物为 `build/libs/createmanaindustry-0.2.7.jar`。没有变动GLSL数学或攻击流程。

复现命令（顺序运行，勿并发Gradle）：

```powershell
.\gradlew.bat -I scripts/particles/validation.init.gradle validateParticleStartupGpu -PparticleStartupProbe --offline --no-configuration-cache
.\gradlew.bat -I scripts/particles/validation.init.gradle validateParticleStartupGpu -PparticleStartupCold -PbenchmarkOutput=build/shader-startup/final-cold --offline --no-configuration-cache
.\gradlew.bat -I scripts/particles/validation.init.gradle validateParticleProgramsGpu validateSceneFrameGpu validateModelShadowIrisGpu test check build --offline --no-configuration-cache
```

冷变体只改变未使用的宏；每次运行使用唯一标记，不清理用户驱动缓存。只对指定程序确保新源码键，不声称清空全部驱动／系统缓存。普通热运行不能代替冷变体。

## 用户实机待验收

使用新构建完整重启客户端，进入原存档，确认地形加载后窗口仍持续响应；若驱动仍在后台链接，日志应每5秒显示当前程序，CMI粒子在首次完成后出现。退出到标题再进入存档，以及不改资源的F3+T，应复用程序；真正改变着色器源码的热重载则应保留旧效果直至整组替换。另测Iris开启／关闭。

若仍有分钟级窗口无响应，需要在该时段采集Java线程转储或原生采样，继续区分其他模组、驱动调用和存档IO；隐藏GLFW自动化不替代此实机验收。本轮未操作用户游戏窗口或修改用户存档。
