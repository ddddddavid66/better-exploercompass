# 探险者指南针 统一 · Explorer's Compass Unified

由 **David** 维护，基于 ChaosTheDude / MattCzyr 的 Explorer's Compass Forge 1.20.1 1.4.0 开发的非官方衍生模组。目标版本 **1.1.0**，适配 **Minecraft 1.20.1、Forge 47.x、Java 17**。

> 当前分支正在进行 1.1.0 发布验收。算法和功能测试通过不代表已达到性能门槛；完整三版本 benchmark 与兼容验证完成后才合并 main 并标记 v1.1.0。

## 一个模组完成搜索

- 普通、双击、组搜索和寻找下一个共用异步结构／群系引擎，默认水平半径四万格。
- 维度、群系与结构联合筛选；维度和群系支持中文、全拼、首字母和注册 ID 搜索。
- 原版五种排序、可配置经验／耐久，默认费用和耐久消耗均为零。
- 暮色森林地标、Dungeons Plus 九个结构、Stalwart Dungeons 末地地牢的可选定位适配。
- 群系与结构共用严格落点、二次降级落点检查。可选“默认／安全落点／直接降级”模式，区块非阻塞准备。
- 埋藏的宝藏定位到真实箱子同一列的地表中心，向下挖即可找到箱子；不会因通用落点算法横移。
- 条目里的“搜索次数”延续历史 UI 名称，**累计成功传送次数**，重复成功传送也增加；搜索、失败和取消不增加。
- 本地统计、群系／维度选择、模式设置和搜索日志继续保留。

维度筛选可浏览其他维度；搜索与传送仍需人在对应维度。结构的群系筛选判断生成规则，不要求最终找到的位置一定处于所选群系。末地地牢是特殊 Feature，自然生成点可能尚未展开；指南针不使用生成器物品或主动展开地牢。旧区块采用过不同规则时，尚未加载且未收录的特殊地物可能漏掉。

## 安装和升级

1. 备份存档与配置。
2. 移除原版 Explorer's Compass、Edited、Enhance 和 explorerscompassfilters 旧扩展，只安装统一版一个 jar。它们使用相同模组／物品 ID，不能共装。
3. 客户端和服务端安装相同版本。网络协议继续为 `unified-2`，物品 ID 仍为 `explorerscompass:explorerscompass`。

旧指南针 NBT、配方、配置和版本 2 本地统计继续读取。不重置历史次数，也不将旧次数换算；缺少维度的旧群系目标需重新定位。新统计文件不存在时从旧扩展文件原样迁移，已有新文件不覆盖。原配置迁移采用“统一版已有值 → 旧扩展 → 新默认值”的优先级。

## 配置与日志

搜索配置位于 `config/explorerscompass-common.toml` 的 Search 部分：

| 参数 | 默认值 |
|---|---:|
| searchRadius | 40000 |
| maxStructureSamples | 25000000，每种放置规则共享 |
| maxBiomeSamples | 8000000，各高度共享 |
| asyncStructureSearch / asyncBiomeSearch | true |
| searchThreads | 0，自动选 1～4 |
| maxSearchTimePerTick | 2 ms，全部搜索共享 |
| biomeSampleSpacing | 32 |
| biomeVerticalSampleSpacing | 64，0 表示仅当前高度 |
| biomeDepthSampleInterval | 4 |

单次核对或生成计算可能超过调度预算，2 ms 不代表每个 tick 的严格上限。群系是网格采样定位，不承诺群系边界或任意稀有目标一秒找到。未知生成器／群系来源按主线程分批兼容验证，HUD 显示实际模式和阶段。

日志默认关闭，普通玩家使用客户端命令 `/david log open` 开启，`/david log close` 关闭（兼容 `clsoe`），`/david log` 查看状态，`/david log sort` 后台导出耗时降序副本。文件为游戏实例 `logs/explorerscompass-unified/search-history.jsonl`，跨存档／服务器共用，原文件及排序文件各限制 100,000,000 字节；达到限制在聊天栏提示，不覆盖旧记录。只记录自己的有效请求，日志不包含后续传送耗时。

## 搜索算法与来源

1.1.0 使用距离下界优先的矩形二分域，替换历史圆环和 X 条带遍历。预测不能永久剪枝；只有真实结构核对后的目标形成上界，未处理范围、在途任务和未消费候选全部参与最近性证明。额度不足时显示采样上限，不返回未经证明的最近结果。

理论来自 [Hjaltason／Samet 1999](https://www.cs.umd.edu/~hjs/pubs/incnear2.pdf) 的增量最近邻和 [Roussopoulos 等 1995](https://www.cs.umd.edu/~nick/papers/nnpaper.pdf) 的矩形最短距离思想。本项目自行推导程序生成域；详细范围、负坐标处理、并发证明和测试见 [ALGORITHM.md](ALGORITHM.md)。

官方来源提交为 `e7f31c400bca0ced222d3086524da35e9bcc50de`，见 [UPSTREAM.json](UPSTREAM.json)。历史版本曾参考 Enhance，此事实保留；1.1.0 整体替换已识别的相近遍历实现，不能描述为“从未参考过 Enhance”。模块对应关系见 [SOURCE_AUDIT.md](SOURCE_AUDIT.md)。

## 构建与测试

安装 Java 17，使用仓库 Gradle Wrapper，无需安装第三方模组才能编译：

```powershell
./gradlew.bat test build
./gradlew.bat smokeJar reobfSmokeJar
./gradlew.bat benchmarkHarnessJar reobfBenchmarkHarnessJar benchmarkClasses
```

Linux 使用 `./gradlew`。首次构建需要连接 Maven、Forge 和 Minecraft 下载源。发布 jar 位于 build/libs，体积检查上限 450 KiB。JUnit 与 JMH 使用 Maven 声明，没有本机绝对路径依赖。TinyPinyin 完整库仅生成／测试时使用，发布包保存紧凑拼音数据。

src/main 是生产源码；src/test 为单元测试；src/smoke 为隔离原生验收；src/benchmark 为 JMH；src/benchmarkHarness 为中立服务端比较工具。后三类不会进入发布 jar，正常游戏不要安装测试工具。

## 性能对比与发布门槛

比较统一版 1.0.5、1.1.0 和原装 Enhance 1.6.0。五个固定种子、五种目标、各五次冷／热请求，总计 750 次；相同四万格半径和采样额度，三个独立 Forge 实例轮流运行。调用各版本正常请求入口，由真实服务器 tick 推进，终态重新核实。无法取得的指标写 N/A。

中立服务端工具启动参数为 `-Dcompassbench.enabled=true -Dcompassbench.engine=unified110`；脚本 tools/run-server-comparison.ps1 接受三个实例及外部 jar 路径，不下载或修改第三方 jar。JMH 采用三个 JVM fork、五轮预热、十轮测量及 GC profiler，旧遍历只通过外部 jar API 加载：

```powershell
./gradlew.bat microBenchmark '-PbaselineJar=/absolute/path/unified105.jar' '-PenhanceJar=/absolute/path/enhance160.jar' '-PbenchmarkArgs=TraversalBenchmark -prof gc -rf json -rff /outside/repository/jmh.json'
```

五类热区块目标的跨种子中位耗时等权几何平均需相对 1.0.5 改善至少 15%；每类 P95、主线程累计和每 tick P95 不得出现可复现的超过 10% 退化。正确性必须全部通过。当前正式比较尚未完成，不能把初步测试或未达门槛的实验 jar 当作正式性能通过。原始数据和完整报告作为仓库外交付材料保存，仅最终摘要更新到本 README。不用服务端耗时推断客户端帧率。

## Git 与 GitHub 上传

main 首次提交为官方固定源码基线。功能、独立算法、适配、测试和文档按阶段提交；验收后合并并标记 v1.1.0。当前没有远程仓库或推送。

生产／测试／benchmark 源码、必要资源、Gradle Wrapper、许可和来源说明纳入 Git。构建缓存、运行实例、存档、日志、第三方 jar、反编译目录、交付包和原始 benchmark 数据由 .gitignore 排除；唯一 jar 例外是 Gradle Wrapper。

由你在 GitHub 建立空仓库后，将下面地址替换为自己的仓库地址：

```powershell
git remote add origin https://github.com/YOUR_NAME/YOUR_REPOSITORY.git
git push -u origin main
git push origin --tags
```

## 许可和致谢

主体遵循 **CC BY-NC-SA 4.0**，保留原作者署名、修改说明和同许可；仅限非商业再发布。TinyPinyin 派生数据单独保留 Apache-2.0。详细许可见 LICENSE.md、NOTICE.txt 和 [THIRD_PARTY.md](THIRD_PARTY.md)。本项目是非官方衍生版本，不暗示上游作者背书。感谢 ChaosTheDude / MattCzyr、MCMostWolf、xinian、TeamTwilight、Modding Legacy、Furti、promeG 及论文作者。
