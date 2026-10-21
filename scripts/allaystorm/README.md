# Allay Storm 离线工具

从仓库根目录运行；Python 3.10+、NumPy、SciPy，仅用于开发，不进入运行时或分发JAR。测试、GPU和服务端入口统一见[验证说明](../../docs/allay-storm-validation.md)。

```powershell
python scripts/allaystorm/rebuild_dome_v4.py
python -m unittest discover -s scripts/allaystorm -p test_dome_v4_tools.py
```

`rebuild_dome_v4.py` 是Dome唯一重建入口，候选必须留在 `build/`，不自动替换生产资源。依次使用以下模块：

| 模块 | 职责 |
| --- | --- |
| `generate_dome_v4.py`、`dome_flow_v4.py` | 解码控制点，编码C2曲线、闭合流线与逆弧长表 |
| `complete_dome_v4.py` | 精确裁剪近心穿出段，保留结束平面 |
| `dome_resource_v4.py` | 独立解码与CPU参考求值 |
| `validate_dome_v4.py` | 最终解码几何、阶段、速度与接触范围检查 |
| `dome_v4_certificate.py` | 连续运动上界，包含弧长时钟 |
| `dome_v4_space_certificate.py` | 连续时空间距，歧义区间自适应细分 |
| `dome_v4_gather_certificate.py` | 闭合流线及同／跨流线间距 |
| `test_dome_v4_tools.py` | 基变换、内部峰值、C2端点、时钟和无损编解码oracle |
| `generate_helix_template.py` | Helix确定性4096点模板；显式指定 `--output build/...` 试制 |

`data/dome-v4-source.npz` 保存float32五次B样条控制点、knots、结束时间及流线元数据，控制点使用无损XOR字节平面。源SHA256为 `15caaf4c32bdc6310d289a5137bdb75808b5226021f7ce3b7f7bce86b1633d4f`；成品SHA256为 `53456dfd103eb61f5f7dec5022d33f1d2eaf06adfa2ed584e0d4ffcf27f1fe94`。完整证书随成品位于 `src/main/resources/data/createmanaindustry/dome-template-v4.validation.json`。

编码、曲线或分离变化后，重新运行最终解码检查和全部连续证书，再运行生产GPU生命周期／服务端回放；数学工具不代替运行时交接验证。旧v3生成、位置投影修补、原生烘焙器和跨版本比较工具已移除，需要历史研究时从Git提取，不恢复为默认路径。
