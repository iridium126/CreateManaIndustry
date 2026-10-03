# 包裹材质分组与独立阴影命令

后续顶点注入与编译桥接已推进，见[Iris 顶点验证](package-shaderpack-vertices-2026-09-30.md)。以下性能数据及未接入 hook 的限制仍适用。

## 实现范围

新增 `PackagePoolGpu.DrawPass.GBUFFER/SHADOW` 两组独立 GPU command/instance bank。读取同一成功提交的 pool/admission，按输入视锥重新剔除，以 mesh/material 分组：前 meshCount 项是地面 solid，后 meshCount 项是链上 cutoutMipped，链上的 box/rig 共用状态。阴影可以选出主相机外的包裹，不修改主命令、通用槽位、物理或身份。

普通无光影路径保持原本一次多模型 multi-draw。分组是独立编译的 `CMI_SPLIT_DRAW` variant，普通 kernel 不承担运行时材质模式判断；当前共 12 个程序，全部编译通过才替换。共享 `vertex_pose.glsl` 统一地面旋转/nudge、链摆动、吊具翻转、hook offset、前后姿态插值及 normal。

新增三个 TBO view（pool/attachment RGBA32F，sampled light R32UI），缓存 view 与 buffer 的关联，批量绑定/解绑。GPU 存储写后使用 texture-fetch barrier；读的仍是准备 pass 时的成功 generation。capacity 固定 uniform 在程序创建时设置；meshCount 在 mesh 上传/重建时设置；count/bodyCount 仅变化时更新，重建清除缓存。包裹 AFTER_LEVEL 绘制存在性不再只受 CPU 滞后 census 控制，实际可见数量仍来自 GPU indirect commands。

这是 Iris 所需的绘制准备组件。**尚未完成包裹 shaderpack 程序合并、原生材质/扩展属性匹配、shadow directive 门禁及实际 Iris 主渲染/阴影钩子。** 世界运行时仍在光影开启时交还 Create，CHAIN_READY 保持关闭。此组件不构成 Iris 接入完成或 131072 活动包裹整帧验收。

## 测量方法

RTX 4070 Laptop GPU，NVIDIA 581.15，OpenGL 4.5 隐藏 context。10000、65536、131072 包裹，一半地面、一半链上；所有候选有效可见，没有隐藏/CPU 回退。对应 15000、98304、196608 个部件实例。固定 canonical body/chain/history、镜头、两个 mesh；每模式至少 30 次且 1 秒预热，三轮各 120 次，使用复用视锥数组。

`legacy_group_reference` 仅还原更改前 draw_count/scatter/prefix 的分组与读取逻辑，使用当前 Java 资源管理/提交代码。`current_group` 计时普通 stage+commit，包括选择/槽位保留/导入/分组、固定 GPU counter reset 和 barrier。它们可比较 GPU 分组变更，不能用来证明 Java uniform 缓存相对旧 Java 代码的提速幅度。

`split_pass` 仅计时对已有成功 pool/admission 的清空、分组 count/prefix/scatter 和 barrier。**它是额外 pass 的成本；与含导入的 current_group 工作范围不同，不能把两者之差当成整体加速。** 不含顶点/片元、光照采样、真实 Iris 编译/渲染、物理、网络和世界采集。显式 query 等待仅在 benchmark 外壳，生产 preparePass 没有读回或 fence 等待。

GPU p95（ms，三轮）：

| 包裹数 | 旧分组参考 | 普通当前路径 | 独立材质 pass 准备 |
| ---: | --- | --- | --- |
| 10000 | .031744 / .031744 / .031744 | .037888 / .037888 / .037888 | .026624 / .026624 / .026624 |
| 65536 | .078848 / .078848 / .078848 | .078848 / .078848 / .078848 | .099328 / .099328 / .099328 |
| 131072 | .286720 / .287744 / .288768 | .287744 / .287744 / .287744 | .181248 / .181248 / .181248 |

131072 的当前普通路径 GPU p50 为 .284672ms，独立 pass 为 .180224–.181248ms；后者 CPU submit p95 为 .0030/.0038/.0043ms。当前路径与旧参考在大负载下处于相同测量范围，没有可宣称的整体提速。10000 p95 多约 .006ms，包含新增有效 slot/capacity 检查及运行波动；该检查是正确性修复，没有以提速为由去掉。

独立材质分组在需要不同 shaderpack 程序时才使用，两个材质各一次 multi-draw；普通路径不增加 pass。两个 pass 都被创建时，额外 instance storage 约 4MiB（131072×16B×2），command storage 为 64×maxMeshes 字节，cursors 比之前多 4×maxMeshes 字节。TBO 不复制底层存储。实际驱动显存、GL 调用总数及分配量尚未独立测量。

数据：[汇总](package-draw-pass-2026-09-30.csv)、[3240 次采样](package-draw-pass-samples-2026-09-30.csv.gz)。没有整帧、服务端 tick 或真实 shaderpack 的前后性能报告。

## 正确性验证

完整 GPU 回归 14160011 项断言，光照 GPU 回归最终输出 886164 项断言（零 timeout 轮询次数会影响断言总数），Java 255 项、0 失败。无 Sable/companion 分支、构建通过；继续使用指定的 compileOnly Sable 发布版本及直接 API。

新增覆盖 0/1/63/64/65/131072、主视锥全剔除而光源可见、独立材质计数/连续 prefix、box/rig 唯一性、隐藏候选排除、正确 slot 身份、TBO 读取同代 pool、另一 pass 不受修改、pool/admission 不受修改、另一个 pool 被拒绝、失败帧保留、成功新 generation/clear 拒绝旧 pass。实际间接分层绘制与普通 framebuffer 逐字节相同，包含失败/成功 shader reload；原姿态/颜色/光照参考仍通过。

```powershell
.\gradlew.bat test validatePackageGpu validatePackageLightGpu validatePackageSableAbsent build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat validatePackageGpu -PpackageDrawPassBenchmark --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

仅绘制组件正确性使用 `-PpackageDrawPassOnly`。实际游戏的 Iris、阴影与资源重载录像由用户执行，但当前仍未启用这些包裹接管路径，不能提前开始对其作视觉验收。
