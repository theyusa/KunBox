# Changelog

## [2.6.1](https://github.com/roseforljh/KunBox/compare/v2.6.0...v2.6.1) (2026-01-23)


### Bug Fixes

* 修复了一些已知问题 ([51f6994](https://github.com/roseforljh/KunBox/commit/51f6994d20f6edf52f71f8ed163383feced4fb15))

## [2.6.0](https://github.com/roseforljh/KunBox/compare/v2.5.0...v2.6.0) (2026-01-22)


### Features

* add release-please workflow to auto-create release PRs ([43a0516](https://github.com/roseforljh/KunBox/commit/43a051623d0e07eb8507fd25c6efb08fa1be3e2c))
* **core:** add kernel-level hot reload for config changes ([c4732a3](https://github.com/roseforljh/KunBox/commit/c4732a37c5673c547c01970d2b5b37c2ab90133c))
* improve node link parsing and latency test settings ([3bf90af](https://github.com/roseforljh/KunBox/commit/3bf90af0a7b0a420a2e51827e0293446ba38e51e))
* **ipc:** improve VPN state management and add kernel build docs ([ce8d5e9](https://github.com/roseforljh/KunBox/commit/ce8d5e972f2602596f2a1c28c89b46a8beccab94))
* **kernel:** add kernel-level HTTP fetch and URLTest extensions ([fa6cd20](https://github.com/roseforljh/KunBox/commit/fa6cd20bc48f86b8a64b48da48185e073b621cf0))
* **latency:** persist node latency data and optimize libbox build ([316ed9b](https://github.com/roseforljh/KunBox/commit/316ed9b24f1ef98bb0a4e5bdcbcfc70c2623c0f0))
* **network:** add NetworkSwitchManager for WiFi/Cellular handoff ([a88a18f](https://github.com/roseforljh/KunBox/commit/a88a18fa0cd040b5b0d55d7c9b4385ca6cdc2610))
* **nodes:** add latency test progress bar with success/timeout stats ([7d333a3](https://github.com/roseforljh/KunBox/commit/7d333a332536fb59f999c511ae99263c6f768cdb))
* **nodes:** add profile selection for manual node creation ([4da7bef](https://github.com/roseforljh/KunBox/commit/4da7bef9ce9708ab0b4878ea8c7ebc525c6388d3))
* **nodes:** 优化搜索栏设计 ([1e3a2db](https://github.com/roseforljh/KunBox/commit/1e3a2dbb49c5682dc4c0e6d6b81ba03cd06d08da))
* **nodes:** 搜索栏跟随滚动显隐 ([dfda72a](https://github.com/roseforljh/KunBox/commit/dfda72a54ed1854e7f0af8223982de9f6a345916))
* **parser:** add Clash Meta advanced features support ([d60e3a2](https://github.com/roseforljh/KunBox/commit/d60e3a2639b73bcaef2ea9b6b74c4a2bded9be6a))
* **routing:** smart domain recognition + fix domain routing priority ([4014073](https://github.com/roseforljh/KunBox/commit/4014073ebc221ff0da0bfc1b793ea275e82d4e5c))
* **ui:** improve navigation animations and add gengar floating effect ([6f8c214](https://github.com/roseforljh/KunBox/commit/6f8c214e4ef14d7a725e21e2f63d4d7a911a5268))
* **ui:** optimize app selector dialog layout and sort selected apps first ([35b9ea2](https://github.com/roseforljh/KunBox/commit/35b9ea26d335419f688eb069f09b5d684bc1a2d4))
* **ui:** sync node selector dialog state with nodes list ([624b1d0](https://github.com/roseforljh/KunBox/commit/624b1d0788950fb141e1211b04d01144177a2b11))
* update workflow logic and dynamic versioning ([e76fbdf](https://github.com/roseforljh/KunBox/commit/e76fbdfa5d19963a87ce312da5bdace6031dbc64))
* **update:** implement app version check and notification ([ee5ee2f](https://github.com/roseforljh/KunBox/commit/ee5ee2f8d2c56ce27d4abe684aee04cfdc45113b))
* 优化磁贴交互与CI配置，完善文档 ([49447bc](https://github.com/roseforljh/KunBox/commit/49447bc146990562c06cb9dea8f70d21196c2ced))
* 应用分流 ([6193046](https://github.com/roseforljh/KunBox/commit/6193046e9e36400031d6b8abd77d0e7a6ff653db))


### Bug Fixes

* ：JSON 格式不标准 (Gson 解析异常) ([a9f2c86](https://github.com/roseforljh/KunBox/commit/a9f2c8621408523b3df34266b376aa5b4b9c9303))
* ? ([57a84d7](https://github.com/roseforljh/KunBox/commit/57a84d73670733961c794000925d7d29ece33cfe))
* 2 ([795f2ec](https://github.com/roseforljh/KunBox/commit/795f2ec3a768c516bef42a71069ad92f4b85f628))
* Add English;随便修了一些bug ([f0a12ff](https://github.com/roseforljh/KunBox/commit/f0a12ff70727ecaaec5c1f2d354255b0b3623751))
* bug ([cb5f10a](https://github.com/roseforljh/KunBox/commit/cb5f10aaa95771d0e47b3289797654b30e64ed87))
* bug修复 ([f6ab50f](https://github.com/roseforljh/KunBox/commit/f6ab50f1f5d610507122213d6af08edba1309234))
* **ci:** merge build into release-please workflow ([9845fda](https://github.com/roseforljh/KunBox/commit/9845fdaac2d0ef089bad73533be87c5586feedf6))
* **ci:** separate release-please and build workflows ([d879764](https://github.com/roseforljh/KunBox/commit/d879764566791477d43d6809d815a71279b5a7ff))
* **config:** node state persistence and profile node memory ([a34bab3](https://github.com/roseforljh/KunBox/commit/a34bab312758b6c324c518441c2f4c3cedd0c9c7))
* **core:** 修复 VPN 服务因 bbolt 数据库并发冲突导致自动停止的 bug ([ac301f7](https://github.com/roseforljh/KunBox/commit/ac301f7df98f35c1e6596f895f04d2e2a88b65bf))
* **core:** 彻底修复 bbolt panic - 根本原因是多进程并发访问同一数据库 ([42ba8cf](https://github.com/roseforljh/KunBox/commit/42ba8cf0018dda3f9e02e8702e8971f4ee75ac86))
* **core:** 确保测试数据库目录存在 - 添加 mkdirs() 调用 ([d57838d](https://github.com/roseforljh/KunBox/commit/d57838d98dca559ec49f06eaad68e6db03c82764))
* **data:** persist node latency and improve update check ([a017357](https://github.com/roseforljh/KunBox/commit/a017357603793546c4a54462d236f6fd9ea282a8))
* **db:** remove foreign key constraint to fix node latency persistence ([905b07e](https://github.com/roseforljh/KunBox/commit/905b07e10782d4d12c68f98db6c799ed3643ff59))
* **filter:** preserve filter keywords when switching filter modes ([e21efa8](https://github.com/roseforljh/KunBox/commit/e21efa8dc620ded02557bb28d5a18c501b877086))
* force release pr generation ([9a7398a](https://github.com/roseforljh/KunBox/commit/9a7398ad4fbc59fa431aecedb55f91796b9b8d93))
* huawei ([b4ad894](https://github.com/roseforljh/KunBox/commit/b4ad8941688d31aaa3b6ad5ec550fa04a2d6b4dc))
* internet op ([87c8238](https://github.com/roseforljh/KunBox/commit/87c8238dcf2d5b27f68bf33293adb291e88ada93))
* **ipc:** resolve UI stuck loading after Doze wake and proxy idle ([14d1713](https://github.com/roseforljh/KunBox/commit/14d171353071b3076122efcdefc9d7ce256a88bd))
* **ipc:** 修复从后台恢复后 UI 一直加载中的问题 ([806fa42](https://github.com/roseforljh/KunBox/commit/806fa42f911c13f022e75767ce99e2dceb5cb031))
* **ipc:** 修复后台恢复时UI卡住的问题 - 参考NekoBox实现 ([624361a](https://github.com/roseforljh/KunBox/commit/624361ac8348093f9fd00eae6767f3b9ee50cfe9))
* **ipc:** 应用返回前台时重置所有连接，修复 TG 等应用一直加载中的问题 ([3549747](https://github.com/roseforljh/KunBox/commit/35497474d0fec6031cafa1112d13e118885c3e07))
* **latency-test:** resolve no available network interface error ([ef5b60c](https://github.com/roseforljh/KunBox/commit/ef5b60cbcb2aa8bae7e23140a8de4cd0a408effd))
* **latency:** enforce user-configured timeout for single node test ([9103dba](https://github.com/roseforljh/KunBox/commit/9103dba01b0f5ac7a1c68c9c358bca52ae804a53))
* **latency:** pass latency value directly in callback to fix race condition ([ac2e134](https://github.com/roseforljh/KunBox/commit/ac2e134db6c9c7c5febc2adcff95849cd5a579d2))
* **latency:** resolve SS+ShadowTLS dependency for latency testing ([451e29e](https://github.com/roseforljh/KunBox/commit/451e29eded484e531a0861faab0043fbeac0bfea))
* **latency:** restore latency data when switching profiles ([67afd6f](https://github.com/roseforljh/KunBox/commit/67afd6f276d17574f5491fab5752a7fe33be3aaf))
* **latency:** support latency testing for nodes from all profiles ([020f82b](https://github.com/roseforljh/KunBox/commit/020f82b1b6c755af1e2ae0a578c323f9051ffd7d))
* **libbox:** adapt to new CommandServer API and fix service shutdown ([6b3df81](https://github.com/roseforljh/KunBox/commit/6b3df8176adfec224a03b180fb21880d8214237b))
* **memory:** optimize memory usage with LRU caches and static TypeTokens ([f25fa5e](https://github.com/roseforljh/KunBox/commit/f25fa5eea01a9343fd4208448dd1bb731e622769))
* **nav:** correct route mapping for Diagnostics and Logs pages ([cd18066](https://github.com/roseforljh/KunBox/commit/cd18066b55adcd8aae379e7e1438db7f85629ee1))
* **network:** add idle connection cleanup for TG image loading issue ([b6c54ef](https://github.com/roseforljh/KunBox/commit/b6c54efe70dda696c0f3b5efc8d504dec6d9f32c))
* **network:** add proactive recovery for faster foreground resume ([85764bd](https://github.com/roseforljh/KunBox/commit/85764bd46b5e2770d1a9703077eecae8c336f5f6))
* **notification:** persist activeLabel to VpnStateStore for cross-process sync ([f7507ae](https://github.com/roseforljh/KunBox/commit/f7507ae62ca9119077d26d26f035c1c7afadd837))
* **parser:** add wireguard and ssh link parsing support ([3494923](https://github.com/roseforljh/KunBox/commit/3494923947477f59bcffa927734bc11e90050dea))
* **parser:** handle empty string fields in Clash YAML hysteria2 nodes ([a4edefc](https://github.com/roseforljh/KunBox/commit/a4edefc3608b4a6bc62e6cfb75be9148d2ca4aef))
* **parser:** handle multiple links in clipboard import ([223c085](https://github.com/roseforljh/KunBox/commit/223c0852fd539ee5a7cc6150dd72062735032067))
* **parser:** 修复 vmess 链接无法从剪贴板导入的问题 ([882eeca](https://github.com/roseforljh/KunBox/commit/882eeca4b2861c8c15f09b01ba3b3d51acb32db5))
* **power:** add background power saving with configurable delay ([208d59a](https://github.com/roseforljh/KunBox/commit/208d59a1f1ae3cdbbba9d3c80d2cccda4164e09b))
* **power:** enable main process self-termination for power saving ([0b39d1f](https://github.com/roseforljh/KunBox/commit/0b39d1f70e0faf5e330ee7ef82c90d6742626920))
* **rulesets:** initialize defaults with toggleable downloads ([6d522ff](https://github.com/roseforljh/KunBox/commit/6d522ff25c7eeba18f0073762b3d363668d97927))
* **rulesets:** load geosite list via github tree ([79e06b4](https://github.com/roseforljh/KunBox/commit/79e06b4f1b0865f10fb7204ec98504d47cfd615e))
* **service:** notification bar node switch not working ([3dfb731](https://github.com/roseforljh/KunBox/commit/3dfb731d438376eb176245e0b314f6d071c76b3a))
* **service:** notification not dismissed when app removed from recents ([1dc9834](https://github.com/roseforljh/KunBox/commit/1dc983460b537444372366ceeba44f29ca71e4fd))
* **service:** 修复息屏后返回App时Telegram等应用一直加载的问题 ([137e64d](https://github.com/roseforljh/KunBox/commit/137e64d65dfaa8b7181dcd2990181f34c3ee8e7c))
* **settings:** 移除断线重连功能和UI ([bc2e5b8](https://github.com/roseforljh/KunBox/commit/bc2e5b8bc573e93fcaff264f597641f989028cf1))
* **shortcut:** node selection and VPN toggle not working from app shortcut ([1253563](https://github.com/roseforljh/KunBox/commit/12535633853cc1dcee0ea08188405d6d1672f10b))
* **subscription:** 修复取消导入后弹窗重复弹出及应用无法使用自身代理的问题 ([ef12cbb](https://github.com/roseforljh/KunBox/commit/ef12cbbf9166d2e87deed1f5d073ade70e38e3a0))
* **subscription:** 兼容订阅剩余/到期信息 ([8af42e1](https://github.com/roseforljh/KunBox/commit/8af42e1657ebf1dba2eb3a3d6edd56b13b9e58a5))
* **tun:** refine app list dialog quick select ([21c4c43](https://github.com/roseforljh/KunBox/commit/21c4c430db38b1a72cf2dca448a3269dcab4b92d))
* **ui:** add smooth crossfade animation for toggle icon transition ([4cdb257](https://github.com/roseforljh/KunBox/commit/4cdb2573bfcbedadee9401c3ef14b89a2d53d87d))
* **ui:** adjust ProfileCard layout weight for better text display ([480ca11](https://github.com/roseforljh/KunBox/commit/480ca11005d3e2ca996dab2c3aa4caae133bbded))
* **ui:** clean build warnings and consolidate stability notes ([3eee087](https://github.com/roseforljh/KunBox/commit/3eee087a49171f9ab80175f646cb9ad1caaa045c))
* **ui:** improve latency display placeholder ([73fb2a0](https://github.com/roseforljh/KunBox/commit/73fb2a09ff46672b91e63a5cfb8e9c536c5149bb))
* **ui:** InputDialog 输入框支持水平滚动 ([a63fcd1](https://github.com/roseforljh/KunBox/commit/a63fcd1ec7dfcb761b13cd02fe2abf234fdc6d05))
* **ui:** prevent gengar image clipping during crossfade animation ([39055b9](https://github.com/roseforljh/KunBox/commit/39055b9f5cfe1e0c624212bfb29ea77dad7d271b))
* **ui:** toggle fab visibility on scroll ([c42cff8](https://github.com/roseforljh/KunBox/commit/c42cff82b5f3c9e7e21c951ee0b617f54866799a))
* **ui:** 列表到底部时隐藏悬浮按钮 ([a232719](https://github.com/roseforljh/KunBox/commit/a23271949474e9b777c2872d64ea29069e9c00e6))
* **ui:** 应用分流仅显示仅允许列表中的应用并移除直连选项 ([05ff1da](https://github.com/roseforljh/KunBox/commit/05ff1da8acb967b3be6b1de88c53460200603180))
* ui控制参数 ([6745e0d](https://github.com/roseforljh/KunBox/commit/6745e0d65bf1d4f0a81b92bca73137e1fa7eef3b))
* **ui:** 调整 BigToggle 图像显示 ([8a53ced](https://github.com/roseforljh/KunBox/commit/8a53ced548eaf4cca29909706eb37c3a59bae928))
* **vpn:** hot reload failure causes network loss ([2000436](https://github.com/roseforljh/KunBox/commit/20004364e3aa3e56a1d731f9a2e5f1762f70b335))
* **vpn:** per-app proxy settings not applied to TUN interface ([f3a9320](https://github.com/roseforljh/KunBox/commit/f3a93203861231fb83704c739687ec12c9b36d42))
* **vpn:** 修复VPN自动关闭和启动期间连接泄漏问题 ([9975a2e](https://github.com/roseforljh/KunBox/commit/9975a2ea6f4f8746f7f999477aff6ec864f059ba))
* **vpn:** 修复多个 VPN 启动和节点切换问题 ([f61fb11](https://github.com/roseforljh/KunBox/commit/f61fb11acc18bcb158698204e2393ce541a0ede7))
* **vpn:** 修复息屏后 Telegram 等应用卡在加载中的问题 ([093ab71](https://github.com/roseforljh/KunBox/commit/093ab71863228341421a8e61f6aee6614ef21cb1))
* **vpn:** 全面加固 VPN 息屏保活机制,防止被系统杀死 ([0b39d09](https://github.com/roseforljh/KunBox/commit/0b39d0981024dd5be46fc6efa160eab2a41a8ffd))
* **vpn:** 添加周期性健康检查防止 VPN 服务僵尸状态 ([843d4ec](https://github.com/roseforljh/KunBox/commit/843d4ecb413485663f72fd4c56a00c01f90cc2cb))
* 主题适配 ([234924c](https://github.com/roseforljh/KunBox/commit/234924cc7d537167d50e091c5a70abff23e5b2a1))
* 优化 ([567b554](https://github.com/roseforljh/KunBox/commit/567b5542ef48b785fb10fbf884f8474b8f19563f))
* 优化 SingBoxCore 代码注释和错误处理 ([d69bd8d](https://github.com/roseforljh/KunBox/commit/d69bd8dc5b4849179e17e1170e049098d286c1ec))
* 优化延迟测试 ([a1fdcb4](https://github.com/roseforljh/KunBox/commit/a1fdcb47f221edc69228f6af080e9453b8be0251))
* 优化延迟测试 ([7b67a0a](https://github.com/roseforljh/KunBox/commit/7b67a0a26888099c0e06a66dae838cda10a3afb9))
* 优化构建配置和 ProGuard 混淆规则 ([046362a](https://github.com/roseforljh/KunBox/commit/046362af725fb7d87bcd9ea3e93a6365a15b0aca))
* 优化网络稳定 ([b03dd47](https://github.com/roseforljh/KunBox/commit/b03dd47ed083fcd9795ecf0baabb5066607d5a72))
* 优化订阅更新逻辑 ([75fa6c5](https://github.com/roseforljh/KunBox/commit/75fa6c5d0ec4227f8e10b02b5734ec22e8e6036a))
* 优化重连、断连、热重载 ([95b3bfd](https://github.com/roseforljh/KunBox/commit/95b3bfd547be8ba80e091f096b0eb7049c6cef6e))
* 修复了热切换节点后 连接延迟的问题 ([894b2af](https://github.com/roseforljh/KunBox/commit/894b2affa8ce6a8a6ac53c3837b11b75b0d22f71))
* 修复从通知栏进入app后返回键需按两次才能退出的问题 ([1240f82](https://github.com/roseforljh/KunBox/commit/1240f8246a52b05189a33502577157873d223bb2))
* 修复节点选择状态未正确保存和显示的问题 ([1cdf3f7](https://github.com/roseforljh/KunBox/commit/1cdf3f7f69ff23dff50cb18ee748725d595e21b7))
* 修复跨配置切换节点时无法上网的问题 ([ab3ba67](https://github.com/roseforljh/KunBox/commit/ab3ba67ac8c0deea104f1ee2cde16d7250ec09da))
* 全局禁用endpoint_independent_nat和auto_route ([2aa437e](https://github.com/roseforljh/KunBox/commit/2aa437ecba131769cb32b2f94a84bdcb4206a4a2))
* 关于应用里加上app本体及singbox的版本号 ([2c6bd61](https://github.com/roseforljh/KunBox/commit/2c6bd61777b3f61d615ca4496ff6358539c9109b))
* 删除组逻辑 ([5aa7021](https://github.com/roseforljh/KunBox/commit/5aa702157adf0cfbb92aec1eb810105ca96e990b))
* 只读节点信息 ([4c0976b](https://github.com/roseforljh/KunBox/commit/4c0976b9e2eb3f8305cb91a32bd2b97ecce55fd5))
* 可能解决了一些闪退的问题 ([ef5e728](https://github.com/roseforljh/KunBox/commit/ef5e7283710ac2839548fe1bfe9a99303c8304f3))
* 同步官方内核 ([f054302](https://github.com/roseforljh/KunBox/commit/f054302a5c90c74e9ff95ae52ab0929b844e363e))
* 增加日志开关 ([9beb8ff](https://github.com/roseforljh/KunBox/commit/9beb8ff72f75911b04fef7e0335e9a4c650c53ea))
* 多处优化 ([189e8c9](https://github.com/roseforljh/KunBox/commit/189e8c9077b167f9d6ca10b3869aff544af48bca))
* 多节点闪退 ([d0d26e5](https://github.com/roseforljh/KunBox/commit/d0d26e5a7d9ce3a386107a91b771976c16f8ccab))
* 实时显示上传和下载速度 ([0af613e](https://github.com/roseforljh/KunBox/commit/0af613ee7a5aa3afb2b2d4140ed0c2de11f601cd))
* 实现配置管理，加号按钮里面本地文件和扫描二维码 ([ec24666](https://github.com/roseforljh/KunBox/commit/ec24666f53992d0137469513f520f3db9275fb0f))
* 底部导航切换过渡动画、磁贴按钮 ([1a4f92c](https://github.com/roseforljh/KunBox/commit/1a4f92c06b4b104088149d52bb223894305c66b3))
* 性能优化 ([4e3cfee](https://github.com/roseforljh/KunBox/commit/4e3cfee07cfb44ad8b66a291df9561b6cbc729bc))
* 性能优化 ([38b27c0](https://github.com/roseforljh/KunBox/commit/38b27c07aba1165c203ba1dc04fb133ae38c0c83))
* 新增 URL Scheme 深度链接支持和问题修复文档 ([5432a6b](https://github.com/roseforljh/KunBox/commit/5432a6b948e705bc49fec833e626c521f612a829))
* 新增应用启动时清理遗留临时数据库文件的功能 ([1bf5a2a](https://github.com/roseforljh/KunBox/commit/1bf5a2a6f27b32a7be22fb7af9c15e36223756e7))
* 新增项目文档索引和优化指南 ([108d3b9](https://github.com/roseforljh/KunBox/commit/108d3b9a1fcb3fa891dff3fcd9686d4f5358b537))
* 更新 planning-with-files submodule 和临时修复代码 ([dffd1e9](https://github.com/roseforljh/KunBox/commit/dffd1e90c5b92f5ca27e3f103df7d645a39a5dad))
* 更新镜像、添加导入导出数据功能 ([a20a115](https://github.com/roseforljh/KunBox/commit/a20a115986e7d0ef646dca830a4788135a4c3433))
* 清理不必要的临时文件和 planning-with-files ([04f9670](https://github.com/roseforljh/KunBox/commit/04f96701c1f8f8c4fee5422cdecb9f08aa459776))
* 清理冗余调试日志以提升性能和可读性 ([dd3a404](https://github.com/roseforljh/KunBox/commit/dd3a404a0301ed2a8be4550a9b3ef196e34b4726))
* 清理废弃的构建脚本并优化 libbox 构建流程 ([de78f57](https://github.com/roseforljh/KunBox/commit/de78f576f6e998a326cc9a0d3d55dbcbc84990c4))
* 移除内核版本管理功能并优化核心服务 ([931e169](https://github.com/roseforljh/KunBox/commit/931e1697574444ecf59c565b402aac169a5b7d93))
* 移除已过时的 bugfix 文档 ([2e80582](https://github.com/roseforljh/KunBox/commit/2e8058272a2c113347d9a196c8af7c621734ee78))
* 移除节点名称自动添加协议后缀 ([2bd0423](https://github.com/roseforljh/KunBox/commit/2bd0423a97ccb72c40b725c5df226b1fc98710f7))
* 稳定网络 ([2bcd87d](https://github.com/roseforljh/KunBox/commit/2bcd87d8e21d210704662f487fe0849450d6fea2))
* 统一 TUN MTU 默认值并移除冗余日志 ([7af9639](https://github.com/roseforljh/KunBox/commit/7af96395f66d20b3c79a7d105eb5bbcf9eab643e))
* 规则集定时自动更新 ([b1803b4](https://github.com/roseforljh/KunBox/commit/b1803b40ab9590ebb5d358ac6219ed16a6a72898))
* 解决 VPN 快速重启导致的网络接口不同步问题 ([0732fd6](https://github.com/roseforljh/KunBox/commit/0732fd607529d3d28522320a698f5bab67ea10dd))
* 解析器识别 anytls 节点 ([742cd95](https://github.com/roseforljh/KunBox/commit/742cd95d84e429df2f22ffc7ebab552b7f63fb27))
* 订阅定时更新 ([0363898](https://github.com/roseforljh/KunBox/commit/0363898cc9377c9f4cd6042a74f01cfbcc24c94a))
* 部分订阅节点丢失 ([ecb9f74](https://github.com/roseforljh/KunBox/commit/ecb9f745eda3443e02a25014ec6e626c195504a3))
* 长按功能 ([276fc8e](https://github.com/roseforljh/KunBox/commit/276fc8e3589660a9e19573f23c4a5678fcac13dd))
* 长按图标出现的操作选项 ([db340c0](https://github.com/roseforljh/KunBox/commit/db340c095572ce3cdf5cd6414ef903a21b445bfa))
* 首页设计 ([3a86145](https://github.com/roseforljh/KunBox/commit/3a86145d750e62abb38de4a0f6521ba0efc0cb51))


### Performance Improvements

* implement 4 performance optimizations ([465a7ee](https://github.com/roseforljh/KunBox/commit/465a7eea13a46c2a8a6448dfa82acc29d9d30b69))
* **network:** optimize throughput and enable GSO ([ed83201](https://github.com/roseforljh/KunBox/commit/ed832018a458ed55de3a627f0fda47ef457f4b1e))
* **repository:** optimize node import performance and add Kryo serialization ([29f8268](https://github.com/roseforljh/KunBox/commit/29f8268ee3d93492684fd510e648ac3b4df09bdf))
* **repository:** parallelize node extraction and subscription updates ([a86228e](https://github.com/roseforljh/KunBox/commit/a86228e6994b0cf6662fd3d359473e53434519de))

## [2.5.0](https://github.com/roseforljh/KunBox/compare/v2.4.5...v2.5.0) (2026-01-22)


### Features

* add release-please workflow to auto-create release PRs ([43a0516](https://github.com/roseforljh/KunBox/commit/43a051623d0e07eb8507fd25c6efb08fa1be3e2c))
* **core:** add kernel-level hot reload for config changes ([c4732a3](https://github.com/roseforljh/KunBox/commit/c4732a37c5673c547c01970d2b5b37c2ab90133c))
* improve node link parsing and latency test settings ([3bf90af](https://github.com/roseforljh/KunBox/commit/3bf90af0a7b0a420a2e51827e0293446ba38e51e))
* **ipc:** improve VPN state management and add kernel build docs ([ce8d5e9](https://github.com/roseforljh/KunBox/commit/ce8d5e972f2602596f2a1c28c89b46a8beccab94))
* **kernel:** add kernel-level HTTP fetch and URLTest extensions ([fa6cd20](https://github.com/roseforljh/KunBox/commit/fa6cd20bc48f86b8a64b48da48185e073b621cf0))
* **latency:** persist node latency data and optimize libbox build ([316ed9b](https://github.com/roseforljh/KunBox/commit/316ed9b24f1ef98bb0a4e5bdcbcfc70c2623c0f0))
* **network:** add NetworkSwitchManager for WiFi/Cellular handoff ([a88a18f](https://github.com/roseforljh/KunBox/commit/a88a18fa0cd040b5b0d55d7c9b4385ca6cdc2610))
* **nodes:** add latency test progress bar with success/timeout stats ([7d333a3](https://github.com/roseforljh/KunBox/commit/7d333a332536fb59f999c511ae99263c6f768cdb))
* **nodes:** add profile selection for manual node creation ([4da7bef](https://github.com/roseforljh/KunBox/commit/4da7bef9ce9708ab0b4878ea8c7ebc525c6388d3))
* **nodes:** 优化搜索栏设计 ([1e3a2db](https://github.com/roseforljh/KunBox/commit/1e3a2dbb49c5682dc4c0e6d6b81ba03cd06d08da))
* **nodes:** 搜索栏跟随滚动显隐 ([dfda72a](https://github.com/roseforljh/KunBox/commit/dfda72a54ed1854e7f0af8223982de9f6a345916))
* **parser:** add Clash Meta advanced features support ([d60e3a2](https://github.com/roseforljh/KunBox/commit/d60e3a2639b73bcaef2ea9b6b74c4a2bded9be6a))
* **routing:** smart domain recognition + fix domain routing priority ([4014073](https://github.com/roseforljh/KunBox/commit/4014073ebc221ff0da0bfc1b793ea275e82d4e5c))
* **ui:** improve navigation animations and add gengar floating effect ([6f8c214](https://github.com/roseforljh/KunBox/commit/6f8c214e4ef14d7a725e21e2f63d4d7a911a5268))
* **ui:** optimize app selector dialog layout and sort selected apps first ([35b9ea2](https://github.com/roseforljh/KunBox/commit/35b9ea26d335419f688eb069f09b5d684bc1a2d4))
* **ui:** sync node selector dialog state with nodes list ([624b1d0](https://github.com/roseforljh/KunBox/commit/624b1d0788950fb141e1211b04d01144177a2b11))
* update workflow logic and dynamic versioning ([e76fbdf](https://github.com/roseforljh/KunBox/commit/e76fbdfa5d19963a87ce312da5bdace6031dbc64))
* **update:** implement app version check and notification ([ee5ee2f](https://github.com/roseforljh/KunBox/commit/ee5ee2f8d2c56ce27d4abe684aee04cfdc45113b))
* 优化磁贴交互与CI配置，完善文档 ([49447bc](https://github.com/roseforljh/KunBox/commit/49447bc146990562c06cb9dea8f70d21196c2ced))
* 应用分流 ([6193046](https://github.com/roseforljh/KunBox/commit/6193046e9e36400031d6b8abd77d0e7a6ff653db))


### Bug Fixes

* ：JSON 格式不标准 (Gson 解析异常) ([a9f2c86](https://github.com/roseforljh/KunBox/commit/a9f2c8621408523b3df34266b376aa5b4b9c9303))
* ? ([57a84d7](https://github.com/roseforljh/KunBox/commit/57a84d73670733961c794000925d7d29ece33cfe))
* 2 ([795f2ec](https://github.com/roseforljh/KunBox/commit/795f2ec3a768c516bef42a71069ad92f4b85f628))
* Add English;随便修了一些bug ([f0a12ff](https://github.com/roseforljh/KunBox/commit/f0a12ff70727ecaaec5c1f2d354255b0b3623751))
* bug ([cb5f10a](https://github.com/roseforljh/KunBox/commit/cb5f10aaa95771d0e47b3289797654b30e64ed87))
* bug修复 ([f6ab50f](https://github.com/roseforljh/KunBox/commit/f6ab50f1f5d610507122213d6af08edba1309234))
* **ci:** separate release-please and build workflows ([d879764](https://github.com/roseforljh/KunBox/commit/d879764566791477d43d6809d815a71279b5a7ff))
* **config:** node state persistence and profile node memory ([a34bab3](https://github.com/roseforljh/KunBox/commit/a34bab312758b6c324c518441c2f4c3cedd0c9c7))
* **core:** 修复 VPN 服务因 bbolt 数据库并发冲突导致自动停止的 bug ([ac301f7](https://github.com/roseforljh/KunBox/commit/ac301f7df98f35c1e6596f895f04d2e2a88b65bf))
* **core:** 彻底修复 bbolt panic - 根本原因是多进程并发访问同一数据库 ([42ba8cf](https://github.com/roseforljh/KunBox/commit/42ba8cf0018dda3f9e02e8702e8971f4ee75ac86))
* **core:** 确保测试数据库目录存在 - 添加 mkdirs() 调用 ([d57838d](https://github.com/roseforljh/KunBox/commit/d57838d98dca559ec49f06eaad68e6db03c82764))
* **data:** persist node latency and improve update check ([a017357](https://github.com/roseforljh/KunBox/commit/a017357603793546c4a54462d236f6fd9ea282a8))
* **db:** remove foreign key constraint to fix node latency persistence ([905b07e](https://github.com/roseforljh/KunBox/commit/905b07e10782d4d12c68f98db6c799ed3643ff59))
* **filter:** preserve filter keywords when switching filter modes ([e21efa8](https://github.com/roseforljh/KunBox/commit/e21efa8dc620ded02557bb28d5a18c501b877086))
* force release pr generation ([9a7398a](https://github.com/roseforljh/KunBox/commit/9a7398ad4fbc59fa431aecedb55f91796b9b8d93))
* huawei ([b4ad894](https://github.com/roseforljh/KunBox/commit/b4ad8941688d31aaa3b6ad5ec550fa04a2d6b4dc))
* internet op ([87c8238](https://github.com/roseforljh/KunBox/commit/87c8238dcf2d5b27f68bf33293adb291e88ada93))
* **ipc:** resolve UI stuck loading after Doze wake and proxy idle ([14d1713](https://github.com/roseforljh/KunBox/commit/14d171353071b3076122efcdefc9d7ce256a88bd))
* **ipc:** 修复从后台恢复后 UI 一直加载中的问题 ([806fa42](https://github.com/roseforljh/KunBox/commit/806fa42f911c13f022e75767ce99e2dceb5cb031))
* **ipc:** 修复后台恢复时UI卡住的问题 - 参考NekoBox实现 ([624361a](https://github.com/roseforljh/KunBox/commit/624361ac8348093f9fd00eae6767f3b9ee50cfe9))
* **ipc:** 应用返回前台时重置所有连接，修复 TG 等应用一直加载中的问题 ([3549747](https://github.com/roseforljh/KunBox/commit/35497474d0fec6031cafa1112d13e118885c3e07))
* **latency-test:** resolve no available network interface error ([ef5b60c](https://github.com/roseforljh/KunBox/commit/ef5b60cbcb2aa8bae7e23140a8de4cd0a408effd))
* **latency:** enforce user-configured timeout for single node test ([9103dba](https://github.com/roseforljh/KunBox/commit/9103dba01b0f5ac7a1c68c9c358bca52ae804a53))
* **latency:** pass latency value directly in callback to fix race condition ([ac2e134](https://github.com/roseforljh/KunBox/commit/ac2e134db6c9c7c5febc2adcff95849cd5a579d2))
* **latency:** resolve SS+ShadowTLS dependency for latency testing ([451e29e](https://github.com/roseforljh/KunBox/commit/451e29eded484e531a0861faab0043fbeac0bfea))
* **latency:** restore latency data when switching profiles ([67afd6f](https://github.com/roseforljh/KunBox/commit/67afd6f276d17574f5491fab5752a7fe33be3aaf))
* **latency:** support latency testing for nodes from all profiles ([020f82b](https://github.com/roseforljh/KunBox/commit/020f82b1b6c755af1e2ae0a578c323f9051ffd7d))
* **libbox:** adapt to new CommandServer API and fix service shutdown ([6b3df81](https://github.com/roseforljh/KunBox/commit/6b3df8176adfec224a03b180fb21880d8214237b))
* **memory:** optimize memory usage with LRU caches and static TypeTokens ([f25fa5e](https://github.com/roseforljh/KunBox/commit/f25fa5eea01a9343fd4208448dd1bb731e622769))
* **nav:** correct route mapping for Diagnostics and Logs pages ([cd18066](https://github.com/roseforljh/KunBox/commit/cd18066b55adcd8aae379e7e1438db7f85629ee1))
* **network:** add idle connection cleanup for TG image loading issue ([b6c54ef](https://github.com/roseforljh/KunBox/commit/b6c54efe70dda696c0f3b5efc8d504dec6d9f32c))
* **network:** add proactive recovery for faster foreground resume ([85764bd](https://github.com/roseforljh/KunBox/commit/85764bd46b5e2770d1a9703077eecae8c336f5f6))
* **notification:** persist activeLabel to VpnStateStore for cross-process sync ([f7507ae](https://github.com/roseforljh/KunBox/commit/f7507ae62ca9119077d26d26f035c1c7afadd837))
* **parser:** add wireguard and ssh link parsing support ([3494923](https://github.com/roseforljh/KunBox/commit/3494923947477f59bcffa927734bc11e90050dea))
* **parser:** handle empty string fields in Clash YAML hysteria2 nodes ([a4edefc](https://github.com/roseforljh/KunBox/commit/a4edefc3608b4a6bc62e6cfb75be9148d2ca4aef))
* **parser:** handle multiple links in clipboard import ([223c085](https://github.com/roseforljh/KunBox/commit/223c0852fd539ee5a7cc6150dd72062735032067))
* **parser:** 修复 vmess 链接无法从剪贴板导入的问题 ([882eeca](https://github.com/roseforljh/KunBox/commit/882eeca4b2861c8c15f09b01ba3b3d51acb32db5))
* **power:** add background power saving with configurable delay ([208d59a](https://github.com/roseforljh/KunBox/commit/208d59a1f1ae3cdbbba9d3c80d2cccda4164e09b))
* **power:** enable main process self-termination for power saving ([0b39d1f](https://github.com/roseforljh/KunBox/commit/0b39d1f70e0faf5e330ee7ef82c90d6742626920))
* **rulesets:** initialize defaults with toggleable downloads ([6d522ff](https://github.com/roseforljh/KunBox/commit/6d522ff25c7eeba18f0073762b3d363668d97927))
* **rulesets:** load geosite list via github tree ([79e06b4](https://github.com/roseforljh/KunBox/commit/79e06b4f1b0865f10fb7204ec98504d47cfd615e))
* **service:** notification bar node switch not working ([3dfb731](https://github.com/roseforljh/KunBox/commit/3dfb731d438376eb176245e0b314f6d071c76b3a))
* **service:** notification not dismissed when app removed from recents ([1dc9834](https://github.com/roseforljh/KunBox/commit/1dc983460b537444372366ceeba44f29ca71e4fd))
* **service:** 修复息屏后返回App时Telegram等应用一直加载的问题 ([137e64d](https://github.com/roseforljh/KunBox/commit/137e64d65dfaa8b7181dcd2990181f34c3ee8e7c))
* **settings:** 移除断线重连功能和UI ([bc2e5b8](https://github.com/roseforljh/KunBox/commit/bc2e5b8bc573e93fcaff264f597641f989028cf1))
* **shortcut:** node selection and VPN toggle not working from app shortcut ([1253563](https://github.com/roseforljh/KunBox/commit/12535633853cc1dcee0ea08188405d6d1672f10b))
* **subscription:** 修复取消导入后弹窗重复弹出及应用无法使用自身代理的问题 ([ef12cbb](https://github.com/roseforljh/KunBox/commit/ef12cbbf9166d2e87deed1f5d073ade70e38e3a0))
* **subscription:** 兼容订阅剩余/到期信息 ([8af42e1](https://github.com/roseforljh/KunBox/commit/8af42e1657ebf1dba2eb3a3d6edd56b13b9e58a5))
* **tun:** refine app list dialog quick select ([21c4c43](https://github.com/roseforljh/KunBox/commit/21c4c430db38b1a72cf2dca448a3269dcab4b92d))
* **ui:** add smooth crossfade animation for toggle icon transition ([4cdb257](https://github.com/roseforljh/KunBox/commit/4cdb2573bfcbedadee9401c3ef14b89a2d53d87d))
* **ui:** adjust ProfileCard layout weight for better text display ([480ca11](https://github.com/roseforljh/KunBox/commit/480ca11005d3e2ca996dab2c3aa4caae133bbded))
* **ui:** clean build warnings and consolidate stability notes ([3eee087](https://github.com/roseforljh/KunBox/commit/3eee087a49171f9ab80175f646cb9ad1caaa045c))
* **ui:** improve latency display placeholder ([73fb2a0](https://github.com/roseforljh/KunBox/commit/73fb2a09ff46672b91e63a5cfb8e9c536c5149bb))
* **ui:** InputDialog 输入框支持水平滚动 ([a63fcd1](https://github.com/roseforljh/KunBox/commit/a63fcd1ec7dfcb761b13cd02fe2abf234fdc6d05))
* **ui:** prevent gengar image clipping during crossfade animation ([39055b9](https://github.com/roseforljh/KunBox/commit/39055b9f5cfe1e0c624212bfb29ea77dad7d271b))
* **ui:** toggle fab visibility on scroll ([c42cff8](https://github.com/roseforljh/KunBox/commit/c42cff82b5f3c9e7e21c951ee0b617f54866799a))
* **ui:** 列表到底部时隐藏悬浮按钮 ([a232719](https://github.com/roseforljh/KunBox/commit/a23271949474e9b777c2872d64ea29069e9c00e6))
* **ui:** 应用分流仅显示仅允许列表中的应用并移除直连选项 ([05ff1da](https://github.com/roseforljh/KunBox/commit/05ff1da8acb967b3be6b1de88c53460200603180))
* ui控制参数 ([6745e0d](https://github.com/roseforljh/KunBox/commit/6745e0d65bf1d4f0a81b92bca73137e1fa7eef3b))
* **ui:** 调整 BigToggle 图像显示 ([8a53ced](https://github.com/roseforljh/KunBox/commit/8a53ced548eaf4cca29909706eb37c3a59bae928))
* **vpn:** hot reload failure causes network loss ([2000436](https://github.com/roseforljh/KunBox/commit/20004364e3aa3e56a1d731f9a2e5f1762f70b335))
* **vpn:** per-app proxy settings not applied to TUN interface ([f3a9320](https://github.com/roseforljh/KunBox/commit/f3a93203861231fb83704c739687ec12c9b36d42))
* **vpn:** 修复VPN自动关闭和启动期间连接泄漏问题 ([9975a2e](https://github.com/roseforljh/KunBox/commit/9975a2ea6f4f8746f7f999477aff6ec864f059ba))
* **vpn:** 修复多个 VPN 启动和节点切换问题 ([f61fb11](https://github.com/roseforljh/KunBox/commit/f61fb11acc18bcb158698204e2393ce541a0ede7))
* **vpn:** 修复息屏后 Telegram 等应用卡在加载中的问题 ([093ab71](https://github.com/roseforljh/KunBox/commit/093ab71863228341421a8e61f6aee6614ef21cb1))
* **vpn:** 全面加固 VPN 息屏保活机制,防止被系统杀死 ([0b39d09](https://github.com/roseforljh/KunBox/commit/0b39d0981024dd5be46fc6efa160eab2a41a8ffd))
* **vpn:** 添加周期性健康检查防止 VPN 服务僵尸状态 ([843d4ec](https://github.com/roseforljh/KunBox/commit/843d4ecb413485663f72fd4c56a00c01f90cc2cb))
* 主题适配 ([234924c](https://github.com/roseforljh/KunBox/commit/234924cc7d537167d50e091c5a70abff23e5b2a1))
* 优化 ([567b554](https://github.com/roseforljh/KunBox/commit/567b5542ef48b785fb10fbf884f8474b8f19563f))
* 优化 SingBoxCore 代码注释和错误处理 ([d69bd8d](https://github.com/roseforljh/KunBox/commit/d69bd8dc5b4849179e17e1170e049098d286c1ec))
* 优化延迟测试 ([a1fdcb4](https://github.com/roseforljh/KunBox/commit/a1fdcb47f221edc69228f6af080e9453b8be0251))
* 优化延迟测试 ([7b67a0a](https://github.com/roseforljh/KunBox/commit/7b67a0a26888099c0e06a66dae838cda10a3afb9))
* 优化构建配置和 ProGuard 混淆规则 ([046362a](https://github.com/roseforljh/KunBox/commit/046362af725fb7d87bcd9ea3e93a6365a15b0aca))
* 优化网络稳定 ([b03dd47](https://github.com/roseforljh/KunBox/commit/b03dd47ed083fcd9795ecf0baabb5066607d5a72))
* 优化订阅更新逻辑 ([75fa6c5](https://github.com/roseforljh/KunBox/commit/75fa6c5d0ec4227f8e10b02b5734ec22e8e6036a))
* 优化重连、断连、热重载 ([95b3bfd](https://github.com/roseforljh/KunBox/commit/95b3bfd547be8ba80e091f096b0eb7049c6cef6e))
* 修复了热切换节点后 连接延迟的问题 ([894b2af](https://github.com/roseforljh/KunBox/commit/894b2affa8ce6a8a6ac53c3837b11b75b0d22f71))
* 修复从通知栏进入app后返回键需按两次才能退出的问题 ([1240f82](https://github.com/roseforljh/KunBox/commit/1240f8246a52b05189a33502577157873d223bb2))
* 修复节点选择状态未正确保存和显示的问题 ([1cdf3f7](https://github.com/roseforljh/KunBox/commit/1cdf3f7f69ff23dff50cb18ee748725d595e21b7))
* 修复跨配置切换节点时无法上网的问题 ([ab3ba67](https://github.com/roseforljh/KunBox/commit/ab3ba67ac8c0deea104f1ee2cde16d7250ec09da))
* 全局禁用endpoint_independent_nat和auto_route ([2aa437e](https://github.com/roseforljh/KunBox/commit/2aa437ecba131769cb32b2f94a84bdcb4206a4a2))
* 关于应用里加上app本体及singbox的版本号 ([2c6bd61](https://github.com/roseforljh/KunBox/commit/2c6bd61777b3f61d615ca4496ff6358539c9109b))
* 删除组逻辑 ([5aa7021](https://github.com/roseforljh/KunBox/commit/5aa702157adf0cfbb92aec1eb810105ca96e990b))
* 只读节点信息 ([4c0976b](https://github.com/roseforljh/KunBox/commit/4c0976b9e2eb3f8305cb91a32bd2b97ecce55fd5))
* 可能解决了一些闪退的问题 ([ef5e728](https://github.com/roseforljh/KunBox/commit/ef5e7283710ac2839548fe1bfe9a99303c8304f3))
* 同步官方内核 ([f054302](https://github.com/roseforljh/KunBox/commit/f054302a5c90c74e9ff95ae52ab0929b844e363e))
* 增加日志开关 ([9beb8ff](https://github.com/roseforljh/KunBox/commit/9beb8ff72f75911b04fef7e0335e9a4c650c53ea))
* 多处优化 ([189e8c9](https://github.com/roseforljh/KunBox/commit/189e8c9077b167f9d6ca10b3869aff544af48bca))
* 多节点闪退 ([d0d26e5](https://github.com/roseforljh/KunBox/commit/d0d26e5a7d9ce3a386107a91b771976c16f8ccab))
* 实时显示上传和下载速度 ([0af613e](https://github.com/roseforljh/KunBox/commit/0af613ee7a5aa3afb2b2d4140ed0c2de11f601cd))
* 实现配置管理，加号按钮里面本地文件和扫描二维码 ([ec24666](https://github.com/roseforljh/KunBox/commit/ec24666f53992d0137469513f520f3db9275fb0f))
* 底部导航切换过渡动画、磁贴按钮 ([1a4f92c](https://github.com/roseforljh/KunBox/commit/1a4f92c06b4b104088149d52bb223894305c66b3))
* 性能优化 ([4e3cfee](https://github.com/roseforljh/KunBox/commit/4e3cfee07cfb44ad8b66a291df9561b6cbc729bc))
* 性能优化 ([38b27c0](https://github.com/roseforljh/KunBox/commit/38b27c07aba1165c203ba1dc04fb133ae38c0c83))
* 新增 URL Scheme 深度链接支持和问题修复文档 ([5432a6b](https://github.com/roseforljh/KunBox/commit/5432a6b948e705bc49fec833e626c521f612a829))
* 新增应用启动时清理遗留临时数据库文件的功能 ([1bf5a2a](https://github.com/roseforljh/KunBox/commit/1bf5a2a6f27b32a7be22fb7af9c15e36223756e7))
* 新增项目文档索引和优化指南 ([108d3b9](https://github.com/roseforljh/KunBox/commit/108d3b9a1fcb3fa891dff3fcd9686d4f5358b537))
* 更新 planning-with-files submodule 和临时修复代码 ([dffd1e9](https://github.com/roseforljh/KunBox/commit/dffd1e90c5b92f5ca27e3f103df7d645a39a5dad))
* 更新镜像、添加导入导出数据功能 ([a20a115](https://github.com/roseforljh/KunBox/commit/a20a115986e7d0ef646dca830a4788135a4c3433))
* 清理不必要的临时文件和 planning-with-files ([04f9670](https://github.com/roseforljh/KunBox/commit/04f96701c1f8f8c4fee5422cdecb9f08aa459776))
* 清理冗余调试日志以提升性能和可读性 ([dd3a404](https://github.com/roseforljh/KunBox/commit/dd3a404a0301ed2a8be4550a9b3ef196e34b4726))
* 清理废弃的构建脚本并优化 libbox 构建流程 ([de78f57](https://github.com/roseforljh/KunBox/commit/de78f576f6e998a326cc9a0d3d55dbcbc84990c4))
* 移除内核版本管理功能并优化核心服务 ([931e169](https://github.com/roseforljh/KunBox/commit/931e1697574444ecf59c565b402aac169a5b7d93))
* 移除已过时的 bugfix 文档 ([2e80582](https://github.com/roseforljh/KunBox/commit/2e8058272a2c113347d9a196c8af7c621734ee78))
* 移除节点名称自动添加协议后缀 ([2bd0423](https://github.com/roseforljh/KunBox/commit/2bd0423a97ccb72c40b725c5df226b1fc98710f7))
* 稳定网络 ([2bcd87d](https://github.com/roseforljh/KunBox/commit/2bcd87d8e21d210704662f487fe0849450d6fea2))
* 统一 TUN MTU 默认值并移除冗余日志 ([7af9639](https://github.com/roseforljh/KunBox/commit/7af96395f66d20b3c79a7d105eb5bbcf9eab643e))
* 规则集定时自动更新 ([b1803b4](https://github.com/roseforljh/KunBox/commit/b1803b40ab9590ebb5d358ac6219ed16a6a72898))
* 解决 VPN 快速重启导致的网络接口不同步问题 ([0732fd6](https://github.com/roseforljh/KunBox/commit/0732fd607529d3d28522320a698f5bab67ea10dd))
* 解析器识别 anytls 节点 ([742cd95](https://github.com/roseforljh/KunBox/commit/742cd95d84e429df2f22ffc7ebab552b7f63fb27))
* 订阅定时更新 ([0363898](https://github.com/roseforljh/KunBox/commit/0363898cc9377c9f4cd6042a74f01cfbcc24c94a))
* 部分订阅节点丢失 ([ecb9f74](https://github.com/roseforljh/KunBox/commit/ecb9f745eda3443e02a25014ec6e626c195504a3))
* 长按功能 ([276fc8e](https://github.com/roseforljh/KunBox/commit/276fc8e3589660a9e19573f23c4a5678fcac13dd))
* 长按图标出现的操作选项 ([db340c0](https://github.com/roseforljh/KunBox/commit/db340c095572ce3cdf5cd6414ef903a21b445bfa))
* 首页设计 ([3a86145](https://github.com/roseforljh/KunBox/commit/3a86145d750e62abb38de4a0f6521ba0efc0cb51))


### Performance Improvements

* implement 4 performance optimizations ([465a7ee](https://github.com/roseforljh/KunBox/commit/465a7eea13a46c2a8a6448dfa82acc29d9d30b69))
* **network:** optimize throughput and enable GSO ([ed83201](https://github.com/roseforljh/KunBox/commit/ed832018a458ed55de3a627f0fda47ef457f4b1e))
* **repository:** optimize node import performance and add Kryo serialization ([29f8268](https://github.com/roseforljh/KunBox/commit/29f8268ee3d93492684fd510e648ac3b4df09bdf))
* **repository:** parallelize node extraction and subscription updates ([a86228e](https://github.com/roseforljh/KunBox/commit/a86228e6994b0cf6662fd3d359473e53434519de))

## [2.4.5](https://github.com/roseforljh/KunBox/compare/v2.4.4...v2.4.5) (2026-01-18)


### Bug Fixes

* 同步官方内核 ([471ed80](https://github.com/roseforljh/KunBox/commit/471ed808fab514a6eb991be71ed9073e8ef8005a))

## [2.4.4](https://github.com/roseforljh/KunBox/compare/v2.4.3...v2.4.4) (2026-01-18)


### Bug Fixes

* **filter:** preserve filter keywords when switching filter modes ([1177d35](https://github.com/roseforljh/KunBox/commit/1177d355acbff542233c4523a35409fef4959c0e))
* **latency:** restore latency data when switching profiles ([7fc75a0](https://github.com/roseforljh/KunBox/commit/7fc75a0793336c44acbc8a02cff400c32a0664a7))
* **latency:** support latency testing for nodes from all profiles ([b8c5054](https://github.com/roseforljh/KunBox/commit/b8c5054b0a9e2cc0081c4d9a7f02e3c5994ab3e9))

## [2.4.3](https://github.com/roseforljh/KunBox/compare/v2.4.2...v2.4.3) (2026-01-18)


### Bug Fixes

* **db:** remove foreign key constraint to fix node latency persistence ([663966c](https://github.com/roseforljh/KunBox/commit/663966c415133a671754d69b4475fd5653d1a385))

## [2.4.2](https://github.com/roseforljh/KunBox/compare/v2.4.1...v2.4.2) (2026-01-17)


### Bug Fixes

* **data:** persist node latency and improve update check ([2a21b55](https://github.com/roseforljh/KunBox/commit/2a21b5519d4ebb858dd6798e1f71cefd418b2e51))
* **power:** enable main process self-termination for power saving ([549e079](https://github.com/roseforljh/KunBox/commit/549e079d8d54f91a1d282128a5fcdec697b68356))

## [2.4.1](https://github.com/roseforljh/KunBox/compare/v2.4.0...v2.4.1) (2026-01-17)


### Bug Fixes

* **ipc:** resolve UI stuck loading after Doze wake and proxy idle ([f834ff9](https://github.com/roseforljh/KunBox/commit/f834ff9b482b47dde81abaf245bcf68f268f0e07))
* **latency:** enforce user-configured timeout for single node test ([d2ce2b4](https://github.com/roseforljh/KunBox/commit/d2ce2b48beed652eeab54650f4b9f325dae802f9))
* **latency:** pass latency value directly in callback to fix race condition ([79a3ab6](https://github.com/roseforljh/KunBox/commit/79a3ab6a445474e58e293358069be4ad71498513))
* **memory:** optimize memory usage with LRU caches and static TypeTokens ([e8d4f87](https://github.com/roseforljh/KunBox/commit/e8d4f872b2970d37a25423b927da4cd54e4e890f))
* **notification:** persist activeLabel to VpnStateStore for cross-process sync ([a2d3b03](https://github.com/roseforljh/KunBox/commit/a2d3b03eb918e108912e8481b2ee03e160fb09c5))
* **parser:** handle empty string fields in Clash YAML hysteria2 nodes ([8910bac](https://github.com/roseforljh/KunBox/commit/8910bac91b3dbf8781e99950253218ece012a274))
* **power:** add background power saving with configurable delay ([572e921](https://github.com/roseforljh/KunBox/commit/572e9214c35483bf2296d28b084204b06c0bc1b2))

## [2.4.0](https://github.com/roseforljh/KunBox/compare/v2.3.1...v2.4.0) (2026-01-17)


### Features

* **core:** add kernel-level hot reload for config changes ([4a059bc](https://github.com/roseforljh/KunBox/commit/4a059bcefe2fdd9c9aa21701b5e9306137c46b2e))
* **routing:** smart domain recognition + fix domain routing priority ([63bbfc4](https://github.com/roseforljh/KunBox/commit/63bbfc4674a8bfb86e91c7bb148c28ccf8133e71))


### Bug Fixes

* **config:** node state persistence and profile node memory ([7ae5d66](https://github.com/roseforljh/KunBox/commit/7ae5d66e02f6132a537cbe7077d9cb083f14e00a))
* **parser:** handle multiple links in clipboard import ([b69d0d2](https://github.com/roseforljh/KunBox/commit/b69d0d252e8a55878a8a2ee1fc9bd21f900c124d))
* **service:** notification bar node switch not working ([fdc36b6](https://github.com/roseforljh/KunBox/commit/fdc36b66496f274e9644f2e52b88aca8de9cc54b))
* **service:** notification not dismissed when app removed from recents ([a6f55c0](https://github.com/roseforljh/KunBox/commit/a6f55c05e98a17a0a0f2935484793f3d3655b5c3))
* **shortcut:** node selection and VPN toggle not working from app shortcut ([6099561](https://github.com/roseforljh/KunBox/commit/60995613c0dd048cc3c1249d3bb0173f1b173c55))
* **vpn:** hot reload failure causes network loss ([42d4d14](https://github.com/roseforljh/KunBox/commit/42d4d1438f242bdb0a8cf4cb0f24aa6e5399f10c))
* **vpn:** per-app proxy settings not applied to TUN interface ([09d1605](https://github.com/roseforljh/KunBox/commit/09d1605d374c0e331e443f19382b61d98dfdbb36))

## [2.3.1](https://github.com/roseforljh/KunBox/compare/v2.3.0...v2.3.1) (2026-01-15)


### Performance Improvements

* implement 4 performance optimizations ([6b32e46](https://github.com/roseforljh/KunBox/commit/6b32e468511496e696652d25d584203048e61e5a))

## [2.3.0](https://github.com/roseforljh/KunBox/compare/v2.2.0...v2.3.0) (2026-01-15)


### Features

* **kernel:** add kernel-level HTTP fetch and URLTest extensions ([94718c5](https://github.com/roseforljh/KunBox/commit/94718c59246702cae51f185dddc9870fa66e041d))
* **nodes:** add profile selection for manual node creation ([ed9f8ae](https://github.com/roseforljh/KunBox/commit/ed9f8aea4b8f867d2cce1f3557a9df071344987a))
* **parser:** add Clash Meta advanced features support ([5b8e5df](https://github.com/roseforljh/KunBox/commit/5b8e5dfd5a5a56efa9514689812e9502fe0818b0))
* **update:** implement app version check and notification ([91fa6e2](https://github.com/roseforljh/KunBox/commit/91fa6e2b8ca5a672ae7a6813e958c3c014a9db24))


### Bug Fixes

* **latency:** resolve SS+ShadowTLS dependency for latency testing ([215a108](https://github.com/roseforljh/KunBox/commit/215a1080675face4095eb1b5bf49a98904182d26))
* 删除组逻辑 ([dc48bdb](https://github.com/roseforljh/KunBox/commit/dc48bdb974b99c184b889e3208b13794bd47f8e6))
* 只读节点信息 ([62f0a56](https://github.com/roseforljh/KunBox/commit/62f0a56a966f914b3f12411fcbc4c16556413900))


### Performance Improvements

* **network:** optimize throughput and enable GSO ([3e2cb1d](https://github.com/roseforljh/KunBox/commit/3e2cb1dcc69f5bbaf5fd8a0ca4a8a0e27f313e8d))
* **repository:** optimize node import performance and add Kryo serialization ([fd6e219](https://github.com/roseforljh/KunBox/commit/fd6e2193d44ed690d2938433f507a2986c5d29d4))
* **repository:** parallelize node extraction and subscription updates ([80aca3b](https://github.com/roseforljh/KunBox/commit/80aca3b371f8672916aeffa305dc90e494adcde9))

## [2.2.0](https://github.com/roseforljh/KunBox/compare/v2.1.8...v2.2.0) (2026-01-14)


### Features

* improve node link parsing and latency test settings ([b19c63e](https://github.com/roseforljh/KunBox/commit/b19c63e1574f141f1996583fddecd324bebf1850))
* **ipc:** improve VPN state management and add kernel build docs ([0bae618](https://github.com/roseforljh/KunBox/commit/0bae6186598726bf04744d64f8ef84ea49729a40))
* **latency:** persist node latency data and optimize libbox build ([c62c9c0](https://github.com/roseforljh/KunBox/commit/c62c9c01d592ea0a9354268ace1cbde05f54901c))
* **nodes:** add latency test progress bar with success/timeout stats ([744eb33](https://github.com/roseforljh/KunBox/commit/744eb330dd2865cf5431cf0d6d1c2353c438e49c))
* **nodes:** 优化搜索栏设计 ([c84a7e8](https://github.com/roseforljh/KunBox/commit/c84a7e87c469ae1b26e09179b2948d3a1cd258bf))
* **nodes:** 搜索栏跟随滚动显隐 ([be8ae78](https://github.com/roseforljh/KunBox/commit/be8ae78b22265a893cc16419337f9422dfb06798))
* **ui:** improve navigation animations and add gengar floating effect ([eacb729](https://github.com/roseforljh/KunBox/commit/eacb729e94542a7c19ab7d2ce7c64a73b29e28f2))
* **ui:** optimize app selector dialog layout and sort selected apps first ([3ee9e49](https://github.com/roseforljh/KunBox/commit/3ee9e490534a70eb60bbed880de0b77cedcba841))
* **ui:** sync node selector dialog state with nodes list ([d2e7e7a](https://github.com/roseforljh/KunBox/commit/d2e7e7abe9843e7aa431bbf46cf2d93df2605143))


### Bug Fixes

* **ipc:** 修复后台恢复时UI卡住的问题 - 参考NekoBox实现 ([5fcb6f7](https://github.com/roseforljh/KunBox/commit/5fcb6f7762b0a07c14f67962c1ca3b7fb3cf378b))
* **latency-test:** resolve no available network interface error ([08d8b45](https://github.com/roseforljh/KunBox/commit/08d8b456478198809efc03b6b9739839a6e3c6b7))
* **nav:** correct route mapping for Diagnostics and Logs pages ([0016b03](https://github.com/roseforljh/KunBox/commit/0016b03dffe9f91397f49a4c081857daefd72875))
* **rulesets:** initialize defaults with toggleable downloads ([2923af1](https://github.com/roseforljh/KunBox/commit/2923af1ca5046c9ab92afdb9591948b4b6a8e257))
* **rulesets:** load geosite list via github tree ([0a01fc8](https://github.com/roseforljh/KunBox/commit/0a01fc885998067915e3ed4f36fa1edd211b5ba9))
* **service:** 修复息屏后返回App时Telegram等应用一直加载的问题 ([d5cc889](https://github.com/roseforljh/KunBox/commit/d5cc8891bae11e38a65e73fb6d281e4b21a2afbc))
* **subscription:** 兼容订阅剩余/到期信息 ([c44c68d](https://github.com/roseforljh/KunBox/commit/c44c68da7a85a1e2db765936ae61eb8222f3b82f))
* **tun:** refine app list dialog quick select ([f2f0f3f](https://github.com/roseforljh/KunBox/commit/f2f0f3f5676b55ec19314876e162351bc70d1dd2))
* **ui:** add smooth crossfade animation for toggle icon transition ([9482e06](https://github.com/roseforljh/KunBox/commit/9482e0621c0322aad4657d61ef51a1bdd469f596))
* **ui:** adjust ProfileCard layout weight for better text display ([fe2369b](https://github.com/roseforljh/KunBox/commit/fe2369b3da899d7550527d97570071147a3de0e5))
* **ui:** clean build warnings and consolidate stability notes ([fe669be](https://github.com/roseforljh/KunBox/commit/fe669be56761f250d418db47fa0e03a633cd01d6))
* **ui:** improve latency display placeholder ([e26c45f](https://github.com/roseforljh/KunBox/commit/e26c45f3d2f8fa8827e668db03c9c3a1953c7891))
* **ui:** prevent gengar image clipping during crossfade animation ([dc3e93c](https://github.com/roseforljh/KunBox/commit/dc3e93ca1bc7fd00e8a5b4910b0f8e5ea1835fc7))
* **ui:** toggle fab visibility on scroll ([fc8b2af](https://github.com/roseforljh/KunBox/commit/fc8b2afd5817a17ad6a7be09554263a078284e00))
* **ui:** 列表到底部时隐藏悬浮按钮 ([708b0ff](https://github.com/roseforljh/KunBox/commit/708b0ff69c2610836b1acdc4abd074723a2416f3))
* **ui:** 调整 BigToggle 图像显示 ([b3af454](https://github.com/roseforljh/KunBox/commit/b3af4542ff40bde528e808ef2f8e33d964103762))

## [2.1.8](https://github.com/roseforljh/KunBox/compare/v2.1.7...v2.1.8) (2026-01-10)


### Bug Fixes

* **settings:** 移除断线重连功能和UI ([57a044e](https://github.com/roseforljh/KunBox/commit/57a044eff54fc6fd17b1fb8ddb2d44f4789d70ae))
* **subscription:** 修复取消导入后弹窗重复弹出及应用无法使用自身代理的问题 ([37e4b1b](https://github.com/roseforljh/KunBox/commit/37e4b1b5d2e71360177e32c5bddac1c1e35da507))
* **vpn:** 修复多个 VPN 启动和节点切换问题 ([684c9fa](https://github.com/roseforljh/KunBox/commit/684c9fac81cde7daf74f017de9a0f28542f223c1))
* **vpn:** 修复息屏后 Telegram 等应用卡在加载中的问题 ([de6c7ef](https://github.com/roseforljh/KunBox/commit/de6c7eff56ee06609cc19878a7a5fb815c093430))
* 修复跨配置切换节点时无法上网的问题 ([c953cf3](https://github.com/roseforljh/KunBox/commit/c953cf3aafbe26ea459c269e37a3233ea3f1cd9b))

## [2.1.7](https://github.com/roseforljh/KunBox/compare/v2.1.6...v2.1.7) (2026-01-09)


### Bug Fixes

* ? ([ce75fb3](https://github.com/roseforljh/KunBox/commit/ce75fb3b7ad19b183aa1d8fcac66560357e490bb))
* 2 ([14dff9d](https://github.com/roseforljh/KunBox/commit/14dff9dfece056188d862bc683b54f2546d726ea))
* bug ([daea0e7](https://github.com/roseforljh/KunBox/commit/daea0e7f8c845104114261e6aec5c91f834ecc7c))
* huawei ([50e13fc](https://github.com/roseforljh/KunBox/commit/50e13fc67575c2392f13d38f4b9018e5cd178609))
* 优化 SingBoxCore 代码注释和错误处理 ([9619b59](https://github.com/roseforljh/KunBox/commit/9619b59b1e9cb33fcda7ee75d8338f4a5658b372))
* 优化构建配置和 ProGuard 混淆规则 ([217080d](https://github.com/roseforljh/KunBox/commit/217080d2679892361927977f542e196401cff229))
* 修复从通知栏进入app后返回键需按两次才能退出的问题 ([814b4d3](https://github.com/roseforljh/KunBox/commit/814b4d37749f33266bb01c6d494f63cd23a70ecb))
* 新增 URL Scheme 深度链接支持和问题修复文档 ([9b3f8b3](https://github.com/roseforljh/KunBox/commit/9b3f8b34bc9e27079c319864b1dd4679ca536f04))
* 新增应用启动时清理遗留临时数据库文件的功能 ([eb21abb](https://github.com/roseforljh/KunBox/commit/eb21abb803f0031197f138d486ad3165a3dfe98c))
* 新增项目文档索引和优化指南 ([d0c89c2](https://github.com/roseforljh/KunBox/commit/d0c89c240f259fea326d746c909042be52cd570c))
* 更新 planning-with-files submodule 和临时修复代码 ([9bf826d](https://github.com/roseforljh/KunBox/commit/9bf826d907c96dab2d24aec38e1c30d89019e60e))
* 更新优化后的 libbox.aar ([421de3b](https://github.com/roseforljh/KunBox/commit/421de3b22c6cd204b5f09cef74b4f6d6d4bc1a8f))
* 清理不必要的临时文件和 planning-with-files ([372f471](https://github.com/roseforljh/KunBox/commit/372f471ebd6c8d9f5dc90e392facfa72e27c40fd))
* 清理冗余调试日志以提升性能和可读性 ([2986e53](https://github.com/roseforljh/KunBox/commit/2986e5322a00e3e37dea860417b3b3b59cf439b5))
* 清理废弃的构建脚本并优化 libbox 构建流程 ([8d87215](https://github.com/roseforljh/KunBox/commit/8d8721557717d3aff3fb4f643ebe15d2d97756f4))
* 移除内核版本管理功能并优化核心服务 ([c661d71](https://github.com/roseforljh/KunBox/commit/c661d718cff1630d9700d1fcd54a5afdefcb887d))
* 移除已过时的 bugfix 文档 ([df0dc64](https://github.com/roseforljh/KunBox/commit/df0dc643c75b8e6588e7429fe50cb5b9c6911196))
* 统一 TUN MTU 默认值并移除冗余日志 ([3ea5b7b](https://github.com/roseforljh/KunBox/commit/3ea5b7b9200bc9969dbdc0ed1f3c9d779a259d9a))

## [2.1.6](https://github.com/roseforljh/KunBox/compare/v2.1.5...v2.1.6) (2026-01-08)


### Bug Fixes

* **core:** 修复 VPN 服务因 bbolt 数据库并发冲突导致自动停止的 bug ([a2e689a](https://github.com/roseforljh/KunBox/commit/a2e689a71d2970a0290c2d323b5b051f6323ea4d))
* **core:** 彻底修复 bbolt panic - 根本原因是多进程并发访问同一数据库 ([497b0d9](https://github.com/roseforljh/KunBox/commit/497b0d9751840611c417a2dc26900c2914c152ad))
* **core:** 确保测试数据库目录存在 - 添加 mkdirs() 调用 ([b76a1e6](https://github.com/roseforljh/KunBox/commit/b76a1e6bc3d8db3952e4f13f3b9240c664d21007))
* internet op ([b4c4d0f](https://github.com/roseforljh/KunBox/commit/b4c4d0fcb03a8f964835cdf9b5c1de83d9fb4923))
* **vpn:** 修复VPN自动关闭和启动期间连接泄漏问题 ([3d9f2f2](https://github.com/roseforljh/KunBox/commit/3d9f2f21cf0576e7bd459bcdacd85ffa70fabd7b))
* **vpn:** 全面加固 VPN 息屏保活机制,防止被系统杀死 ([826bee3](https://github.com/roseforljh/KunBox/commit/826bee388ca2e5d40025bb0f2cbb2f88d7e24791))
* **vpn:** 添加周期性健康检查防止 VPN 服务僵尸状态 ([7426735](https://github.com/roseforljh/KunBox/commit/7426735cd84be66fe4fb973158ba41b00e7df237))
* 优化 ([c9036f1](https://github.com/roseforljh/KunBox/commit/c9036f115a37eef2ee12c8ebad6f010e2fbf9037))
* 修复节点选择状态未正确保存和显示的问题 ([4272779](https://github.com/roseforljh/KunBox/commit/42727791c22bbe99c88578a6719e6d6d5aa183ea))

## [2.1.5](https://github.com/roseforljh/KunBox/compare/v2.1.4...v2.1.5) (2026-01-04)


### Bug Fixes

* 优化延迟测试 ([ec969a0](https://github.com/roseforljh/KunBox/commit/ec969a0f02bc3b3bbfccb7ce56a127b720e5a532))
* 优化延迟测试 ([813c358](https://github.com/roseforljh/KunBox/commit/813c35883c518a8d8dbb6a018560d4cd93b3ee6b))
* 优化订阅更新逻辑 ([ccae9b1](https://github.com/roseforljh/KunBox/commit/ccae9b1abd5de21a3834d8eb6977c5bb1d4c6824))
* 可能解决了一些闪退的问题 ([9d67bb8](https://github.com/roseforljh/KunBox/commit/9d67bb808831a7ec225192b615da31efff01b876))
* 多处优化 ([988b49c](https://github.com/roseforljh/KunBox/commit/988b49c9cc1154a0c7c55090b17b9007fdaa21f8))
* 实时显示上传和下载速度 ([a312d2e](https://github.com/roseforljh/KunBox/commit/a312d2e4a0895c707c9bf2f18ca92cf2d73ba2c0))
* 部分订阅节点丢失 ([c766968](https://github.com/roseforljh/KunBox/commit/c7669681064ce9862762552ea19234914f9836e9))
* 长按功能 ([a5d6c4c](https://github.com/roseforljh/KunBox/commit/a5d6c4cdbb246035abe9b4a24418bb7e5e97a209))

## [2.1.4](https://github.com/roseforljh/KunBox/compare/v2.1.3...v2.1.4) (2026-01-03)


### Bug Fixes

* Add English;随便修了一些bug ([24d58a3](https://github.com/roseforljh/KunBox/commit/24d58a3542b62281edc2398c9937f8c1ffbd554d))
* 优化网络稳定 ([5dbe537](https://github.com/roseforljh/KunBox/commit/5dbe53795435cf29e1488c0ee883fa014e0c2fd1))
* 关于应用里加上app本体及singbox的版本号 ([520f158](https://github.com/roseforljh/KunBox/commit/520f1583d301f30cb82b184d2e1f737f8ca774fc))
* 实现配置管理，加号按钮里面本地文件和扫描二维码 ([24e41ee](https://github.com/roseforljh/KunBox/commit/24e41ee59a6fe44793f4695f1936ce5267d695b6))
* 性能优化 ([0f271df](https://github.com/roseforljh/KunBox/commit/0f271dfe88f0052ec0ef7b02253417f1a54f7f25))
* 更新镜像、添加导入导出数据功能 ([1c2c39d](https://github.com/roseforljh/KunBox/commit/1c2c39de187f4cb5788f82fe3c8281999c977ebe))
* 移除节点名称自动添加协议后缀 ([797ec2b](https://github.com/roseforljh/KunBox/commit/797ec2b327dcb9df70e5825864a7e7b887f08704))
* 规则集定时自动更新 ([e5da3c9](https://github.com/roseforljh/KunBox/commit/e5da3c9bd6b03f4efa7f0659f32251ff88de3672))
* 订阅定时更新 ([4312b63](https://github.com/roseforljh/KunBox/commit/4312b6348ec03f16ecff473005e8a051aa1f51b3))
* 长按图标出现的操作选项 ([a7c1e1c](https://github.com/roseforljh/KunBox/commit/a7c1e1c106e9797cddf693c189e88eab7dfeaceb))

## [2.1.3](https://github.com/roseforljh/KunBox/compare/v2.1.2...v2.1.3) (2025-12-31)


### Bug Fixes

* bug修复 ([c531ab3](https://github.com/roseforljh/KunBox/commit/c531ab3d11790f7513c722e0f02749f115205064))
* 优化重连、断连、热重载 ([80890a4](https://github.com/roseforljh/KunBox/commit/80890a4e5aa9743300b7060e9cd8d9c3abc3772e))
* 修复了热切换节点后 连接延迟的问题 ([d0b9027](https://github.com/roseforljh/KunBox/commit/d0b9027814fdfd5d1a0178e0cee00c5b1d74e717))
* 增加日志开关 ([772aa41](https://github.com/roseforljh/KunBox/commit/772aa4112dd3ff3e70c3f0ee3477185dc01656b7))
* 稳定网络 ([6c0e3f0](https://github.com/roseforljh/KunBox/commit/6c0e3f08ab06e765b7c6b5acf60d0b41262926f6))

## [2.1.2](https://github.com/roseforljh/KunBox/compare/v2.1.1...v2.1.2) (2025-12-30)


### Bug Fixes

* ：JSON 格式不标准 (Gson 解析异常) ([8ec60eb](https://github.com/roseforljh/KunBox/commit/8ec60ebd939dd08a05062d6deaebe6ad7b26c75d))
* ui控制参数 ([6ae7bfb](https://github.com/roseforljh/KunBox/commit/6ae7bfbad2e35cc2a85284f2cc025dbc721a33ee))
* 主题适配 ([2d82a90](https://github.com/roseforljh/KunBox/commit/2d82a90314f9e5f9693abf109aa9b27bec01fc5c))
* 全局禁用endpoint_independent_nat和auto_route ([9f3b3f4](https://github.com/roseforljh/KunBox/commit/9f3b3f46c2643dade479c0b2f7693fd429482267))
* 底部导航切换过渡动画、磁贴按钮 ([6c69d4e](https://github.com/roseforljh/KunBox/commit/6c69d4e77d3b35e40310b8784bca6f3d1a2b5e14))
* 解决 VPN 快速重启导致的网络接口不同步问题 ([0052091](https://github.com/roseforljh/KunBox/commit/0052091e2fbf1bd18300e53fe9bd815aa201844b))
* 解析器识别 anytls 节点 ([63d0c1b](https://github.com/roseforljh/KunBox/commit/63d0c1b93a790020f7b2d1dd40705f905524e092))
* 首页设计 ([3d31cf9](https://github.com/roseforljh/KunBox/commit/3d31cf9c76c209761c25b0e1511624254453621f))

## [2.1.1](https://github.com/roseforljh/singboxforandriod/compare/v2.1.0...v2.1.1) (2025-12-30)


### Bug Fixes

* force release pr generation ([ffcdc99](https://github.com/roseforljh/singboxforandriod/commit/ffcdc99ef13e6b3c3dd0aabc4050db3b33349e78))

## [2.1.0](https://github.com/roseforljh/singboxforandriod/compare/v2.0.7...v2.1.0) (2025-12-30)


### Features

* add release-please workflow to auto-create release PRs ([c0bb4e7](https://github.com/roseforljh/singboxforandriod/commit/c0bb4e7f7f48b8ebfda83ad4c9942accd8ec67f0))
* update workflow logic and dynamic versioning ([0fef687](https://github.com/roseforljh/singboxforandriod/commit/0fef68710ad0c4e62b2ffbd489ae48bd6e089b62))


### Bug Fixes

* 多节点闪退 ([a434516](https://github.com/roseforljh/singboxforandriod/commit/a4345164701d540912c112de744eeaf6d03f8803))
