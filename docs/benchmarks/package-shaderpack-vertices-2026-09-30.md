# 包裹 Iris 顶点与编译桥接验证

这是本日较早阶段的记录；后续主渲染/阴影 hook、原生 Iris patchVanilla 验证和状态边界已经接线，见[后续报告](package-iris-hooks-2026-09-30.md)。下述「尚未接入」描述仅适用于本记录阶段。

## 实现范围

`PackageVertexInjector` 注入 `packages/package_merged.vsh`，通过三个 TBO 读取准备 pass 所属成功 generation 的 pool、attachment、sampled light。复用 `vertex_pose.glsl` 的位置、摆动、吊具翻转及插值；扩展 pose 输出的旋转矩阵用于面法线和切线。没有逐包裹 Java 行为，也没有新增通用粒子槽位。

原 64 字节粒子、20 vec4 emitter header、48 字节 mesh vertex 协议保持不变。模型烘焙保留未受 shade 标记影响的原始法线，加载时生成额外 36 字节/vertex 的静态属性 VBO（法线、切线与镜像符号、quad midpoint UV）。无 world 访问；普通 vertex 程序不消费这个属性流。三角形使用三顶点中心；Create 的 0,1,2,2,3,0 quad 使用四个不同顶点中心，包括 first vertex 不为六倍数的 mesh。退化 UV 使用有限、正交的切线备用值。

与 MODEL 注入不同，包裹的 Iris 扩展属性使用实际数据，而不是中性常量；属性宽度兼容 float/vec2/vec3/vec4。同一声明包含多个扩展属性或其他用户属性时只移除所替代的成员，保留其他声明。缺失源码、声明冲突或转换失败直接拒绝编译，不返回未修改的 native vertex。

光照坐标来自实际 packed light 或 GPU 采样结果，保留 16×light nibble 的原生数值；bit31 的采样有效标记不会进入光照坐标。vertex tint 保留烘焙颜色和普通路径的 diffuse，光照贴图留给 Iris，避免重复乘光照。矩阵、fragment、geometry、tessellation 及 directives 属于光影包。

`PackageShaderCompiler` 和 CMI 自有 typed Iris mixin 桥接已经编译；不使用 iris-veil accessor，不增加运行时反射。Iris 的实体/方块实体阶段将原生 SBB solid/cutoutMipped 都路由到 MOVING_BLOCK，阴影均为 SHADOW_TERRAIN_CUTOUT，编译器按这个规则选取源及 alpha fallback。无 shadow targets 时仅准备主程序；有 targets 时主/阴影候选全部成功才发布。相同 pipeline 重载失败保留旧 bundle；跨 pipeline 不复用旧 framebuffer/program。淘汰 shader 从 Iris loadedShaders 集合移除后关闭，避免 pipeline destroy 再次关闭；已 destroy 的 pipeline 不再次关闭。

TBO 10–13 的 Iris sampler reservation 移至 Iris presence gate，不再要求 iris-veil 提供 mixin。单独安装 Iris 时，只在包裹程序创建的 thread-local 作用域内预留 10–12，结束或失败时恢复，原生程序保留全部 sampler 预算；存在 iris-veil 时保留先前四单位预留行为，MODEL 仍用四个。编译器也跟踪延迟出现的 shadow targets，避免只有主程序的早期成功阻止后来编译阴影。编译桥接目标字段、两个 private create 方法及 ProgramSet 构造签名已用当前依赖 jar 的 javap 检查，与 `.refs/Iris` 相符。

**尚未接入实际 gbuffer/shadow 绘制 hook。** 世界及预览的 shaderpack gate 保持现状，CHAIN_READY 关闭；此阶段不能作 Iris 游戏内视觉验收或完整光影接入声明。仍需接线 shadow directive、渲染状态恢复、tessellation primitive、原生 entity/block entity ID 及移动结构语义。当前 injected vertex 使用 camera-relative level space；依赖每个实例 model-local 坐标的光影效果也未证明对齐。

## 验证

RTX 4070 Laptop，NVIDIA 581.15，OpenGL 4.5 隐藏 context。固定随机种子，ground、chain、flipped rig，插值 0/.25/.5/1，shaderpack 注入后的实际 GPU transform feedback 与普通顶点位置/颜色比较，容差 2e-5；离散属性与 light 精确比较。

覆盖实际 TBO packed/sample light、法线长度、旋转后的切线正交性及镜像符号、quad midpoint、材质数据和实例映射。另通过真实 VBO、两层 GPU indirect draw 读取生成属性，验证 ground、box、rig 的 baseInstance、法线翻转、切线和光照。没有用 CPU 绘制来代替 GPU 渲染验证。

当前 Iris 依赖内嵌 transformer 3.0.0-pre3；独立 `validatePackageIrisGpu` 提取该 jar，仅替换验证运行时的 transformer 2.0.1，不改生产依赖或打包。相同 GPU 测试在 2.0.1 与 3.0.0-pre3 下通过，后者 12012 项断言。当前桥接的 Mixin 实际应用、Iris ShaderCreator 资源/重载失败处理仍需真实客户端验证，javap 与编译通过不能代替它们。

完整回归：Java 258 项、0 失败、52 suite；完整 package GPU 14165850 项断言；light GPU 886165；Iris transformer 3 GPU 12012；Sable absent 与 build 均通过。随后仅调整编译时 sampler 预留边界，补充嵌套失败和跨线程预留隔离测试，最终 Java 260 项、0 失败、53 suite；Iris transformer 3、Sable absent 及 build 再次通过。光照异步零 timeout 轮询可能令断言总数变动。本次没有 GPU/CPU 提速测量，不作整帧或 131072 活动包裹验收声明。

```powershell
.\gradlew.bat test validatePackageGpu validatePackageLightGpu validatePackageIrisGpu validatePackageSableAbsent build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
# 仅光影顶点组件，使用 compileClasspath 的 transformer 2.0.1：
.\gradlew.bat validatePackageGpu -PpackageShaderpackOnly --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
# 使用当前 Iris jar 内嵌的 transformer 3.0.0-pre3：
.\gradlew.bat validatePackageIrisGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```
