<div align="center">

# Legado · 个人阅读器分支

Fork 自 [LegadoTeam/legado](https://github.com/LegadoTeam/legado) 的 Android 开源小说阅读器，
主线开发**通用扩展包框架**与 **MCP** 能力。

</div>

## 项目从哪来

- Fork 自 [LegadoTeam/legado](https://github.com/LegadoTeam/legado)（GPL-3.0），持续从上游同步
- 功能开发在 `feature/engine-2.0` 分支：扩展包框架（`modules/pack`）+ 宿主集成 + MCP 改进
- 感谢上游项目，以及它使用的开源库（jsoup、JsoupXpath、OkHttp、Glide、Markwon、Rhino、epublib 等，完整清单见上游仓库）

## 它能做什么

**原有阅读能力**：书源/订阅源、RSS、本地书籍（epub / txt / pdf 等）、规则解析与阅读界面，与上游一致。

**本分支新增 —— 通用扩展包框架**：

- 包支持 JS / DEX / native 组件自选与组合，manifest 能力声明后才挂载
- 导入/启用仅登记元数据，**首次调用才执行**（懒加载）；带超时、配额、协作取消与 epoch 回调失效机制
- **隔离模型**：文件、KV、日志、网络客户端互相隔离；包文件存应用私有 `files/packs/`；网络未声明即禁用，白名单支持域名模式，`network=open` 导入时明确确认
- 卸载撤销注册并输出残留报告；组件缺失/超时等显式红牌错误，禁止静默降级
- JS 运行在白名单沙箱；DEX/native 为进程内可信代码，不做同等沙箱承诺
- 自检（selftest）、诊断记录与环形日志（落盘上限 100 MB）

**MCP 改进**：扩展包声明的 MCP 工具经注册表**原子注册/撤销**（命名冲突整体拒绝、启停/卸载联动、工具立即消失契约）；写入工具默认关闭，需在管理界面显式授权。

## 文档

- `docs/pack-spec.md` —— 扩展包规范（manifest、能力声明、错误码）
- `docs/pack-api.md` —— 框架 API 与 wire 契约
- `api.md` —— Web 服务 API
- `sample-packs/` —— 示例包

## 开发

- 分支：`feature/engine-2.0`（扩展包框架开发线）；`master` 跟随上游
- 构建：JDK 21 · Gradle 8.14.4 · AGP 8.13.2 · Kotlin 2.4.20 · compile/target SDK 36 · minSdk 23
- 常用命令：

  ```bash
  ./gradlew :modules:pack:test          # 扩展包框架单测
  ./gradlew :app:testAppDebugUnitTest   # app 全量单测
  ./gradlew :app:assembleAppRelease     # release 构建（R8）
  ./gradlew :app:lintAppDebug           # Android Lint
  ```

- 代码风格沿用上游 Kotlin 官方风格与 Gradle 结构，依赖版本集中在 `gradle/libs.versions.toml`
- 框架改动要求：`modules/pack` 单测保持全绿；涉及书源桥/沙箱的改动需跑对应兼容性测试，不放开 `RhinoClassShutter` 限制

## 许可

GPL-3.0（继承上游）。本项目继续遵守开源义务并保留来源声明。
