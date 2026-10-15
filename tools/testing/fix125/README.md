# 愚者1.2.5定向工具

此目录是独立测试模组，生产jar不包含它。仅在完整愚者副本运行，修改测试副本的玩家位置、物品及测试世界，不安装到日常实例。

构建：`./gradlew.bat -I tools/testing/fix125/fixture.gradle fix125Jar reobfFix125Jar`。

将生成的`unified125-checks-1.0.jar`与正式1.2.5 jar放入测试副本；完整模组、配置和数据包保留。准备原存档独立副本，目录名`world125`。使用副本自己的Java/Forge启动参数，启动凭证不要提交Git。

默认流程：真实九头蛇查询→普通自然生物查询→同次查询切换两个真实实例→天境手动高度→沉没之城同步/异步→新世界未探索九头蛇。不会召唤实体或主动放置巢穴。原实例和原存档不得指向此工具。

仅复测未完成部分可使用JVM参数：`-Dunified125.skipHydra=true`或`-Dunified125.skipCreatures=true`；只测新世界使用`-Dunified125.onlyFresh=true -Dunified125.freshName=一个新的测试目录名`。这些只属于测试工具，不是模组配置。

结果写入测试实例`targeted125-results.txt`。测试结束自动退出游戏。功能失败不算启动故障；不通过重启清除游戏内重试计数。原始本轮数据在交付包records中。