# 独立三模式索引 benchmark

此目录只用于复现 linked / bounded_linked / exact_ranges 比较，不属于 main source set，不进入 mod JAR。参考 solver 保留相同的修复后 CCD、碰撞、环境调用与暂停规则；环境程序及非索引公共代码从 main classpath 获取。参考 shader 仅保留 solver 编译的程序与依赖，重复的原生观察、交还、绘制、增量、光照和整套回归已删除。

完整实验每组预热 50 步、采样 200 步；默认前三轮轮换模式顺序。追加两轮读取同一输出目录的前三轮 CSV。首次使用新输出目录，避免混用不同源码或驱动下的实验；记录源码提交和驱动版本。历史五轮 CSV 保留在 docs/benchmarks/package-index-2026-10-02，不由新运行覆盖。

```powershell
.\gradlew.bat -I scripts/particles/validation.init.gradle benchmarkPackageIndexes
python scripts/particles/compare_package_indexes.py build/package-index-comparison
# 前两名差距 <3% 时追加两轮
.\gradlew.bat -I scripts/particles/validation.init.gradle benchmarkPackageIndexes -PpackageIndexBenchmarkExtra
python scripts/particles/compare_package_indexes.py build/package-index-comparison
# 快速验证编译、三模式和全部五场景；65 包裹、50 步预热、3 步采样，不用于选型
.\gradlew.bat -I scripts/particles/validation.init.gradle benchmarkPackageIndexes -PpackageIndexSmoke -PpackageIndexOutput=build/package-index-smoke
```

可用 `-PpackageIndexOutput=新目录` 保留独立实验。评分脚本必须输入完整规模和采样量，拒绝 smoke 数据。
