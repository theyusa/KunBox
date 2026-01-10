# Changelog

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
