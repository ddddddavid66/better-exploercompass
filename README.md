# 探险者指南针 统一 · Explorer’s Compass Unified

由 **David** 维护，基于 **ChaosTheDude / MattCzyr** 的 Explorer’s Compass Forge 1.20.1 1.4.0 开发的非官方衍生模组。当前版本 **1.1.1**，适配 **Minecraft 1.20.1、Forge 47.x、Java 17**。

一个 jar 完成结构搜索、群系定位、维度筛选和传送。安装时移除原版、Edited、Enhance 和 explorerscompassfilters 旧扩展；客户端与服务端一起升级。保留 `explorerscompass:explorerscompass` 物品 ID、配方、旧物品和统计。

## 功能

- 默认四万格半径；普通、双击、组搜索、寻找下一个共用异步引擎，有界线程池、多玩家独立任务。
- 维度／群系／结构联动筛选；中文、全拼、首字母、注册 ID 搜索；原版五种排序。
- 可选暮色森林地标、Dungeons Plus 九结构、Stalwart Dungeons 末地地牢适配。
- 严格落点与二次降级传送，可切换“默认／安全落点／直接降级”。宝藏固定到真实箱子同一列的地表中心。
- “搜索次数”沿用旧 UI 名称，**实际累计成功传送次数**。重复成功也增加；搜索、失败、取消不增加。
- 原版经验／耐久机制可配置，默认费用和耐久均为零；本地统计、筛选、日志和三种模式持久化。

维度筛选可浏览其他维度，搜索和传送仍需人在对应维度。结构群系筛选判断生成规则，不能保证找到的位置属于所选群系。末地地牢是特殊 Feature，自然十字架可能尚未展开；指南针不使用生成器物品、不主动放置或展开地牢。采用过不同生成规则的旧区块中，尚未加载且未收录的地物可能漏掉。

## 1.1.1 修改

**Alex Caves 2.0.2**：复用群系浮层及“定位群系”，支持磁场洞穴、原始洞穴、毒化洞穴、渊海陷窟、异寂空谷、糖果龋洞，不注册虚拟建筑。稀有域逐列筛选、地下每 4 格检查高度，工作线程各持有官方 Voronoi 实例；主线程完整来源及 BIOMES 区块核实。只保护正在定位这六目标的统一版指南针，不关闭其他物品限制。当前仅审核原版 NoiseBased／MultiNoise 来源；未知包装来源明确降级为主线程四格高度查询。安装 Alex 后，其他搜索也不会后台调用它的共享可变群系状态。

**YUNG 丛林神庙 2.0.5 + API 4.0.5**：审核完整放置排除依赖与起始拼接入口，保留河流／海洋排除、频率和数据包规则；命名起始点使用主线程准备的 palette／Jigsaw 锚点快照。YUNG 海底神殿 3.0.4 共用模板保护。未知能力或模板访问仍进入兼容验证。

**Aquamirae 6.API15**：覆盖 ship、outpost、shelter、ice_maze/common、ice_maze/arch、ice_maze/spiral。官方选出准确起始列后，先遍历所有可达 quart-Y；完全不可能属于允许群系才跳过昂贵高度计算。对可证明与 Y 无关的气候项按列缓存，逐点对照原版计算；保留冰迷宫全部变体、权重、真实结构核对与最近性证明。

**传送**：等待区块不再消耗扫描预算，局部和地表游标独立，重试继续未完成范围，固定 3×3 准备窗口。每次点击总期限 10 秒、单中心等待 5 秒；严格／降级累计检查预算 80／40 ms，共享原 2 ms/tick 调度。降级交替尝试局部空气和地表，新增脚部浸水、头部及眼睛空气的海面候选，仍拒绝岩浆、碰撞、越界和危险坠落。海面落点需自行游泳。成功后准备票据最多保留 40 tick，让玩家区块跟踪接管再释放。

## 安装与迁移

1. 备份存档及配置。
2. 移除旧指南针及配套扩展，只安装统一版一个发布 jar；测试工具 `unifiedchecks` 不要安装到正常存档。
3. 客户端／服务端一同更新。协议仍为 `unified-2`，旧物品 NBT、配方、配置和版本 2 本地统计继续读取。

不重置历史次数，不追溯换算。缺少维度的旧群系目标需重新定位。新统计文件不存在时原样导入旧扩展文件，已有新文件不覆盖；配置迁移优先级为“统一版已有值 → 旧扩展 → 新默认值”。第三方模组均为可选依赖，本项目不重新分发它们。

## 配置和日志

`config/explorerscompass-common.toml` 的 Search 部分：

| 参数 | 默认值 |
|---|---:|
| searchRadius | 40000 |
| maxStructureSamples | 25000000，每种放置规则共享 |
| maxBiomeSamples | 8000000，各高度共享 |
| asyncStructureSearch / asyncBiomeSearch | true |
| searchThreads | 0，自动选 1～4 |
| maxSearchTimePerTick | 2 ms，全部搜索共享 |
| biomeSampleSpacing | 32 |
| biomeVerticalSampleSpacing | 64；0 为仅当前高度 |
| biomeDepthSampleInterval | 4 |

Alex 专用定位采用 4 格高度检查，其他群系保持上述配置。2 ms 是调度预算；单次不可拆分生成／核对可能超出。群系采用网格采样，不保证精确边界，也不承诺所有稀有目标一秒找到。HUD 显示实际异步／混合／同步模式、阶段、采样及上限；未知生成逻辑保留兼容路径。

日志默认关闭，普通玩家可使用客户端指令：

| 指令 | 功能 |
|---|---|
| `/david log open` | 开启记录 |
| `/david log close` | 关闭；兼容 `clsoe` |
| `/david log` | 显示状态、路径及大小 |
| `/david log sort` | 后台导出耗时降序副本 |

文件为游戏实例 `logs/explorerscompass-unified/search-history.jsonl`，跨存档／服务器共用，只记录自己的有效请求，不包含传送耗时。原文件和排序文件分别限制 100,000,000 字节；越限提示，不覆盖历史。独立有界队列写入，不阻塞搜索。服务器另输出每次搜索的群系预筛／跳过高度摘要和传送等待／检查阶段摘要，不逐采样打印。

## 算法与来源

1.1.0 起使用距离下界优先的矩形二分；预测只调整优先级，真实核实结果才构成剪枝上界。未处理范围、在途任务、未消费候选参与最近性证明；额度不足时不会将暂存候选冒充已证明结果。1.1.1 延续这一核心，增加列级计算复用及可选模组审核。

数学依据为 [Hjaltason／Samet 1999](https://www.cs.umd.edu/~hjs/pubs/incnear2.pdf) 和 [Roussopoulos 等 1995](https://www.cs.umd.edu/~nick/papers/nnpaper.pdf)。本项目推导和正确性说明见 [ALGORITHM.md](ALGORITHM.md)。官方基线提交 `e7f31c400bca0ced222d3086524da35e9bcc50de` 记录于 [UPSTREAM.json](UPSTREAM.json)。历史曾参考 Enhance，1.1.0 替换已识别的相近遍历；不宣称从未参考它，也不把改写等同于法律上的无关联。见 [SOURCE_AUDIT.md](SOURCE_AUDIT.md) 与 [THIRD_PARTY.md](THIRD_PARTY.md)。

## 构建与验证

Java 17，使用 Gradle Wrapper：

```powershell
./gradlew.bat test build
./gradlew.bat smokeJar reobfSmokeJar
```

Linux 使用 `./gradlew`。首次构建需连接 Maven、Forge 和 Minecraft 下载源。发布 jar 位于 `build/libs`，确定性打包，上限 **500 KiB**。JUnit 使用 Maven 依赖；TinyPinyin 完整库只用于生成／测试，发布包为紧凑数据。

`src/main` 为生产源码，`src/test` 为单元测试，`src/smoke` 为隔离原生验收，`src/benchmark` 与 `src/benchmarkHarness` 保留历史对比工具。测试和基准类不进入发布 jar。原生工具会在专用新世界执行测试，不要装入正常游戏；`-Dunified.optional111=true` 启用真实服务器 tick 专项，`-Dunified.group111=true` 加测冰迷宫变体组搜；`-Dunified.client111=true` 启用隔离客户端传送／区块检查。

本轮仅做受影响目标专项和功能回归，不重跑 1.1.0 的大型 benchmark。原始 CSV、配置、SHA-256、失败实验与完整验证记录随交付保存在仓库外。专项数据可复算：

```powershell
python tools/summarize-optional.py /absolute/path/to/targeted-data
```

1.1.1 当前构建通过 113 项 JUnit（无失败、无跳过）、八目标三轮真实 tick 对照、冰迷宫变体组搜，以及末地地牢十二项真实 tick 回归。新旧目标坐标一致；首次查询中位数，丛林神庙 1779.961 → 451.146 ms，冰迷宫 common / arch / spiral 分别 12097.665 → 3999.670、12551.339 → 4338.025、12102.183 → 3596.857 ms。海灵船只首次基本持平。每轮目标共用一个新世界，可能共享已生成区块，不能解释为严格独立冷区块基准。

本轮实现阶段的独立服务端整合包副本通过受影响结构、Dungeons Plus 九结构及末地地牢回归；精简客户端通过丛林神庙、海底神殿和 Warped Garden 实际传送及区块/光照检查。Alex 六目标通过原生服务端定位、完整来源验证及生存主副手保护；Alex 隔离客户端启动未完成、完整整合包客户端回归未完成，不能标为通过。白区块在精简客户端未复现，原问题根因未证实。最终缓存补丁通过三轮专项及末地地牢回归，完整客户端尚未用此最终构建复验。

当前交付为 **1.1.1 测试构建**：498517 字节（486.83 KiB），SHA-256 `ef04dc84541cb98d809dfa159a52661351e783151feb9fbf76bc3406decbe8cc`。按用户要求停止继续耗时验收；修复保存在功能分支，暂不合并 main 或建立正式验收标签。

历史 1.1.0 曾完成三版本 750 次请求与独立 500 次复测，热目标几何平均相对 1.0.5 改善 93.84%／92.80%，但部分冷查询更慢。那是历史构建结果，不作为 1.1.1 重新测试或所有场景加速的证据。JMH 工具仍可用；本轮未重新运行。

## Git 与发布

官方固定提交为仓库起点，功能与算法分阶段提交。1.1.1 在修复分支实施，验收后合并 main 并标记 `v1.1.1`。本轮不创建远程、不推送；由你建立 GitHub 空仓库后执行：

```powershell
git remote add origin https://github.com/YOUR_NAME/YOUR_REPOSITORY.git
git push -u origin main
git push origin --tags
```

源码、必要资源、Gradle Wrapper、许可、来源说明与最新 README 纳入 Git；构建缓存、运行实例、存档、日志、第三方 jar、反编译目录、交付包和原始数据由 `.gitignore` 排除。唯一 jar 例外为 Gradle Wrapper。

## 许可与致谢

主体 **CC BY-NC-SA 4.0**，保留署名、修改说明、许可及同许可分享要求；仅限非商业再发布。TinyPinyin 派生数据单独适用 Apache-2.0。详见 LICENSE.md、NOTICE.txt、THIRD_PARTY.md。此为非官方衍生版本，不暗示原作者背书。

感谢 ChaosTheDude / MattCzyr、MCMostWolf、xinian、TeamTwilight、Modding Legacy、Furti、promeG、Alexthe668 / Noonyeyz、YUNG-GANG、ObscuriaLithium 及论文作者。
