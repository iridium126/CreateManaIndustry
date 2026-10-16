# 性能结论与复现

这里保留影响实现选择的 Markdown 结论。带日期的报告描述当时的实现和测量条件，不是当前版本的验收结果。

原始 CSV、样本、日志、截图、测试报告和构建产物不进入 Git。2026-10-12 清理时，已有本地产物移至忽略目录 `build/benchmarks/history-cleanup-2026-10-12/`；执行 `clean` 后可重新运行对应 benchmark，不能要求得到相同的历史数值。

## 运行

```powershell
python scripts/benchmarks/run.py benchmarkPackagePause --offline
python scripts/benchmarks/run.py validatePackageGpu -PpackagePriorityBenchmark --offline
# 比较算法需要在同一目录中完成前三轮及必要的追加轮次。
python scripts/benchmarks/run.py --output build/benchmarks/index-experiment benchmarkPackageIndexes --offline
python scripts/particles/compare_package_indexes.py build/benchmarks/index-experiment/package-index-comparison
python scripts/benchmarks/run.py --output build/benchmarks/index-experiment benchmarkPackageIndexes -PpackageIndexBenchmarkExtra --offline
```

运行器创建独立目录，保存实际命令、退出状态和完整日志。Gradle 任务另存源码提交、工作区状态、参数、Java 与平台信息。GPU/驱动信息以本次 OpenGL 日志为准。直接使用 Gradle 时，默认输出为 `build/benchmarks/current/`，可用 `-PbenchmarkOutput=build/benchmarks/<实验>` 指定目录。

参数、预热、样本量和质量门槛由 benchmark 源码定义。比较前确认场景及计时范围一致；微基准不能直接推算完整游戏 FPS。具体任务见 [测试入口](../testing.md)。

## 保留规则

- 每项架构决策保留一份结论、适用条件、测量环境及复现命令。
- 数据重新测量后，更新对应主报告，不新增重复的日期日志文档。
- 必须长期保留的历史证据放在外部归档，并在报告中提供链接。
- 正确性测试夹具保存在测试目录；不要把 benchmark 输出当作测试输入。
