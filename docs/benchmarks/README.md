# 性能结论与复现

每项架构决策只保留一份结论，写在对应主题的主文档里。逐版本日期报告、原始 CSV、样本、日志、截图和测试报告不进入 Git，统一输出到忽略的 `build/benchmarks/`。当前验证以实际运行结果为准，历史数值不作为验收依据。

[GPU 运行时当前报告](gpu-runtime-architecture.md) 是运行时架构的唯一性能主报告。

## 运行

```powershell
python scripts/benchmarks/run.py benchmarkPackagePause --offline
python scripts/benchmarks/run.py validatePackageGpu -PpackagePriorityBenchmark --offline
# 比较算法需要在同一目录中完成前三轮及必要的追加轮次。
python scripts/benchmarks/run.py --output build/benchmarks/index-experiment benchmarkPackageIndexes --offline
python scripts/particles/compare_package_indexes.py build/benchmarks/index-experiment/package-index-comparison
python scripts/benchmarks/run.py --output build/benchmarks/index-experiment benchmarkPackageIndexes -PpackageIndexBenchmarkExtra --offline
```

运行器创建独立目录，保存实际命令、退出状态和完整日志；Gradle 任务另存源码提交、工作区状态、参数、Java 与平台信息。GPU/驱动信息以本次 OpenGL 日志为准。直接使用 Gradle 时默认输出 `build/benchmarks/current/`，可用 `-PbenchmarkOutput=build/benchmarks/<实验>` 指定。

参数、预热、样本量和质量门槛由 benchmark 源码定义。比较前确认场景及计时范围一致；微基准不能推算完整游戏 FPS。任务清单见 [测试入口](../testing.md)。

## 保留规则

- 每项架构决策只保留一份结论、适用条件、测量环境及复现命令，放在该主题的主文档。
- 数据重新测量后更新主文档，不新增日期日志文档。
- 必须长期保留的历史证据放在外部归档，并在主文档给出链接。
- 正确性测试夹具保存在测试目录；不要把 benchmark 输出当作测试输入。
