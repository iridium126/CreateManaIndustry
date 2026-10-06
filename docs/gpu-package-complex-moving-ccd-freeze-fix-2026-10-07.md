# 复杂已驻留场景中的移动碰撞冻结

日期：2026-10-07。用户补充：边界两侧 section 已上传，包含 Sable、Create 动态结构、静态鼓风机／分散网和 Sable 风机；高速自由包裹跨界仍冻结，大量包裹在 Sable 分散网附近被吸附时也会停住。

## 确定性复现

本轮 `debug.log` 仅有退出世界时的正常 GPU 暂停记录，没有环境接触或位置确认拒绝，不能用上一轮的服务端拒绝解释这次现象。

真实 OpenGL 夹具在物理运行前上传全部静态 section、Sable 与 Create 碰撞几何，然后让结构每 tick 绕 Y 轴旋转 0.001 弧度。包裹使用真实宽高 0.625×0.75，贴近一个 Sable 表面，从 y=13.5 向上穿过 y=16；另一个分支验证在 Sable 分散网附近受到吸力的低速包裹。静态和结构风场均通过生产 GPU 路径施加。

旧实现第一次跨界即设置 `PACKAGE_COLLISION_FROZEN`。夹具专用标记确认来自 `moving_contacts.comp` 中保守推进耗尽 32 次迭代的分支。冻结与 section 请求、上传或 CPU 结构发现预算无关。

## 原因

旧推进使用当前 SAT 分离距离除以全向速度上界。上界包含包裹全部运动速度及整个碰撞盒相对结构原点的旋转半径。高速运动几乎沿表面方向时，真正接近表面的速度可能很小，但总速度很大，推进步长被严重缩小。

吸附场景还存在吸力与旋转表面运动几乎抵消的情况。将旋转贡献总是作为正的闭合速度累加，会再次把一个实际仍分离的包裹误判为无法完成查询。旧实现随后回退完整位置并冻结，不能继续沿表面运动。

另一条相关路径是静止结构的虚假角速度：`moving_prepare.comp` 原来用 `2*acos(dot(qa,qb))` 求角度。float 四元数归一化后，`dot(q,q)` 仍可能略小于 1；即使两个姿态完全相同，也能产生约 0.0012 弧度的虚假旋转。确定性夹具在修复前得到 `0.0011959828`，将完全静止的结构错误送入保守推进。

## 修复与界限

保留原有通用保守推进，并额外利用三个 OBB 面分离轴所证明的安全时间区间，取其中最大的安全区间推进。发生碰撞需要所有轴都重叠，因此任一轴持续分离都足以证明这段时间安全。

面分离轴的计算保留平移与角速度的正负方向，使相互抵消的运动得到正确处理。旋转的距离上界采用包裹相对旋转轴的垂直距离及包围盒半尺寸，另对剩余时间内的导数变化、nlerp 角速度变化、包裹投影变化和非均匀缩放加入保守界限。

结构角度改用相对四元数的 `atan` 计算。前后原始旋转／缩放矩阵完全相同时，角速度界限明确设置为零；相同四元数也明确返回零，避免乘减融合运算的舍入残差。静止结构因此可靠地进入原有精确线性碰撞路径。

- 恒定方向和缩放的精确线性 sweep 路径保持原样。
- 32 次迭代限制、真正缺失或未知碰撞数据的保护、世界覆盖和最终 SAT 解穿透检查均保留。
- 没有新增生产 GPU dispatch、缓冲区、同步读回、网络字段或主线程世界查询。
- 未修改鼓风机力度、分散网吸力、section 缓存容量或上传预算。

## 验证

`complexResidentNozzleProgress` 覆盖 65 和 20,000 个真实尺寸包裹，分别进行高速跨界与分散网吸附测试，并组合完全静止和轻微旋转的 Sable 姿态，共八个场景。最终夹具含静态风机碰撞体、静态风场、Sable 风场及两类动态结构；另一个包裹实际落到 Create 动态结构上并与其接触。静态 section 和所有结构几何预先上传，步骤间只更新结构姿态和风场快照。每步检查无冻结、自由运动持续，以及独立计算的 Sable 表面和 Create 碰撞约束没有被穿透。

`movingAdvanceSafety` 对 160 个确定性旋转、平移、非均匀缩放与包裹运动组合生成 GPU 安全区间；每个有效区间取 129 个点，以独立 CPU 的完整 15 轴 SAT 检查没有跳过碰撞。已有旋转结构中间姿态碰撞、携带、缩放、未知几何、密集支撑和环境回归也继续运行。

另有 64 个任意方向及非均匀缩放的静止姿态，直接验证生产 GPU 姿态准备得到严格为零的旋转界限。

```powershell
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache -PpackageComplexFreeOnly -I scripts/particles/validation.init.gradle
.\gradlew.bat test validatePackageGpu build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

设备为 Java 21、NVIDIA GeForce RTX 4070 Laptop GPU、OpenGL 4.5、驱动 581.15。验证日志为 `build/package-complex-free-before.log`、`build/package-complex-free-crowd.log`、`build/package-complex-free-safety.log`、`build/package-complex-free-validation.log` 及 `build/package-complex-free-final.log`。尚未在用户原存档中复测，也未测量原存档整帧性能。

最终验证：501 项单元测试（100 个套件，零失败、错误或跳过）、14,270,295 项完整 GPU 断言、无 Sable 桥接检查及正式构建全部通过。发布包为 `build/libs/createmanaindustry-0.2.6.jar`。
