# Sable 直接 API 迁移与验证（2026-09-29）

Create 包裹动态碰撞入口现在使用指定依赖 `compileOnly "maven.modrinth:T9PomCSv:U678xqle"`，实际 Sable 2.0.5 发布 JAR。已移除 Sable 的 `Class.forName`、`Method.invoke` 和动态符号表。所有子世界、plot、已加载 chunk、特殊形状、回调分类和嵌套 Create 姿态访问均为直接 Java 类型调用。

Modrinth POM 未声明内嵌 companion。构建从同一发布 JAR 的 `META-INF/jarjar/sable-companion-common-1.21.1-1.6.0.jar` 解包编译 API，缓存由输入 JAR 和输出文件决定，无需额外下载或提交副本。只使用 compileOnly；测试单独加入 companion 数学运行依赖。产物检查未发现 `dev/ryanhcode/sable` 类或 Sable 内嵌依赖，CMI JAR 只包含自己的适配代码。

普通 Create 来源类不引用外部 Sable 类型。`ModList.get().isLoaded("sable")` 通过后才进入独立适配类。缺席分支在既没有 Sable、也没有 companion 的独立 JVM 中执行，保证编译依赖不会变成强制运行依赖。可选 API 链接错误在发现和缓存捕获阶段撤销覆盖；姿态、版本、游标创建及遍历五个失败位置有故障注入与恢复验证。

姿态使用实际 companion 的 `transformNormal` 和 `transformPosition`，每个来源复用暂存向量。正交基直接在局部坐标计算，保留旋转中心和非均匀正缩放；无需用大世界坐标差计算基向量。300 组固定种子随机旋转/缩放、每组 16 点，与实际发布 companion 的坐标转换对照，绝对坐标覆盖 ±3000 万格，容差 3e-8 格。前后平移分别检查；零缩放和镜像拒绝覆盖。

验证命令（本机 JDK 21，使用现有 Gradle 缓存）：

```powershell
.\gradlew.bat build validatePackageGpu -PpackageMovingOnly --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

结果：完整构建成功；160 个 Java 测试通过，无失败、错误或跳过；`validatePackageSableAbsent` 通过；RTX 4070 Laptop GPU / OpenGL 4.5 NVIDIA 581.15 的动态结构 GPU 回归通过 4147 项断言，包含 131072 活动 body 的持续承载容量夹具。GPU 算法和 ABI 本次未改变。

这次没有重新测量内核或整帧性能。历史合成 GPU 测量保持在 [动态结构报告](package-moving-2026-09-29.md)，不能用它证明本次姿态调整提速。实际 Sable 游戏运行、mixin、物流接管、多人增量同步、视觉和 131072 真实活动包裹整帧验收仍待完成；生产接管门禁继续关闭。
