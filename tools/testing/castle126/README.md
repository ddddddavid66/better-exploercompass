# 金色板条箱最小实机检查

独立测试模组，不进入发布 jar。Java 17/ForgeGradle 构建：

```powershell
./gradlew.bat -I tools/testing/castle126/fixture.gradle castle126Jar reobfCastle126Jar
```

只放入独立测试实例，配合统一版和用户自己的 Infinite Abyss 1.8.4。它会创建种子 789123 的新测试世界，将测试玩家设为创造模式并前往第4层，然后调用正常群系筛选搜索入口及“寻找下一个”。验证实际方块、维度目录、官方随机修饰器一致性、不同锚点和槽位释放，完成后退出游戏。禁止安装到日常游戏实例。不会主动放置模板、箱子或复制第三方素材。完整整合包需要另行验收。

优化构建检查使用`-Dcastle126.optimized=true -Dcastle126.world=castle126-opt`，只执行一次普通搜索和一次寻找下一个，跳过已经验证的随机规则大循环。结果复核使用单个FEATURES票据，先检查ticket level，再请求相应future并非阻塞轮询，避免把尚未调度完成的右值当作箱子消失。若仅工具复核出错，可加`-Dcastle126.resume=true`，它会读取原测试物品结果，只补查已有坐标，不重新搜索；只在隔离测试存档中使用。
