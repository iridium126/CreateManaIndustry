# GPU 包裹绘制优化（2026-10-04）

本轮针对 shaderpack 绘制准备的 CPU 与 GPU 热点做了定向优化。测量环境为 NVIDIA GeForce RTX 4070 Laptop GPU、OpenGL 4.5、NVIDIA 581.15。基准覆盖 10,000、65,536、131,072 个候选包裹；每档预热至少 30 次且至少 1 秒，随后三轮各测 120 次。场景中一半为地面包裹、一半为链上包裹，所有候选均通过剔除并生成 box/rig 共 1.5 个实例/包裹。

## 改动

- `draw_count` 在拆分材质 pass 中执行一次完整可见性测试，并把候选判定写入复用的 GPU scratch buffer；`draw_scatter` 直接消费判定，不再重算相同的 6/13 面剔除、距离和父传动轮可见性。普通提交变体不使用此缓存。
- `PackagePoolGpu` 缓存最近上传的传动轮可见性位图。掩码不变时跳过逐帧 `glBufferSubData`；掩码变化时仍完整上传，主视锥与阴影使用不同掩码时按实际内容切换。
- `PackageRenderState` 只保存/恢复绘制 pass 实际触碰的状态：SSBO 0–11（包含光照反馈用 9–11 的非零 range/offset）、绘制用纹理/TBO、blend、program、VAO、深度/剔除和相关绑定。包裹 pass 不改 framebuffer 或 copy-buffer 绑定，因此不再查询和重置它们。
- 主视锥包裹准入只遍历当前有 GPU 租约的传动轮；曾被跟踪但当前没有 GPU 包裹的传动轮不会参与逐帧 renderer 检查。

## 前后对比

表中数值为三轮各自 P95 的中位数，单位毫秒。`split_pass` 仅计绘制命令清空、可见性分组/剔除和必要屏障；它不包含最终顶点/片元、材质 shader、真实阴影或整帧成本。

| 候选数 | GPU pass P95：前 → 后 | CPU 提交 P95：前 → 后 |
| ---: | ---: | ---: |
| 10,000 | 0.028672 → 0.025600（−10.7%） | 0.0048 → 0.0042（−12.5%） |
| 65,536 | 0.102400 → 0.098304（−4.0%） | 0.0111 → 0.0041（−63.1%） |
| 131,072 | 0.190464 → 0.184320（−3.2%） | 0.0065 → 0.0048（−26.2%） |

另一个 `split_boundary_13` 模式把 13 面阴影准备包在实际状态 capture/restore 边界中。CPU 提交 P95 中位数在 10,000 候选时由 0.2395 ms 降为 0.0823 ms，在 65,536 候选时由 0.1527 ms 降为 0.0098 ms，在 131,072 候选时由 0.1613 ms 降为 0.0102 ms。低负载档仍有明显系统抖动，不据此推算帧率。

CPU 数值是 OpenGL 命令提交区间，不表示服务器 tick 或整帧时间。该基准使用隐藏 GL context，不代表真实 Iris shaderpack 下的最终画面性能。最大候选档新增 512 KiB 可见性 scratch；主线程按活动租约遍历的收益取决于世界中未租约传动轮的数量，本轮没有单独量化这一项。

## 验证

```powershell
.\gradlew.bat test build validatePackageSableAbsent --offline --no-configuration-cache -I scripts/particles/validation.init.gradle --console=plain
.\gradlew.bat validatePackageGpu -PpackageDrawPassOnly --offline --no-configuration-cache -I scripts/particles/validation.init.gradle --console=plain
.\gradlew.bat validatePackageGpu -PpackageDrawPassOnly -PpackageDrawPassBenchmark -PpackageIrisBoundaryBenchmark --offline --no-configuration-cache -I scripts/particles/validation.init.gradle --console=plain
```

Java 测试、构建、Sable 缺席门禁通过；最终绘制 GPU 回归及 13 面边界基准通过 568,820 项断言。绘制与边界基准均在修改前后运行。
