# NeoSimupack · 筑艺坊

[![Build](https://github.com/RealSuQingqing/NeoSimupack/actions/workflows/build.yml/badge.svg)](https://github.com/RealSuQingqing/NeoSimupack/actions/workflows/build.yml)
[![License: GPL v3](https://img.shields.io/badge/license-GPL--3.0--only-blue.svg)](LICENSE)
[![Minecraft 1.21.1](https://img.shields.io/badge/Minecraft-1.21.1-3C8527.svg)](https://www.minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.228%2B-E88E33.svg)](https://neoforged.net/)

**A building pack manager and workshop for [New: Sim-U-Kraft](https://modrinth.com/mod/new-sim-u-kraft).**
**《新：模拟大都市》（New: Sim-U-Kraft）的拓展包管理器与创作工坊。**

**[English](#english)** | **[中文](#中文)**

---

## English

### What it is

NeoSimupack (`simupack`) is a NeoForge add-on for *New: Sim-U-Kraft*. It takes over
the job of finding, validating, installing, converting and updating **building
packs** — the content packs that give Sim-U-Kraft its buildings — and adds an
in-game workshop for authoring them.

The mod is under active development. The current version is `2.0.0`, and the pack
format may still change between releases.

### Features

- **Building pack manager** — a dedicated screen listing every installed pack with
  per-pack detail, building counts, and enable/disable toggles. Official built-in
  packs are marked as such and are locked against disabling or deletion.
- **Legacy pack conversion** — packs that predate `pack.json` (old naming scheme,
  structures that are not `.nbt` yet) are detected on load, badged as *legacy*, and
  converted in place. A converted pack arrives **disabled**, so you can inspect the
  result before enabling it.
- **Pack update checking** — compares installed packs against the version bundled
  with the mod and offers a one-click update.
- **Attention centre** — gathers pack problems (malformed packs, packs awaiting
  conversion, available official updates) and surfaces them as notices instead of
  letting them fail silently.
- **Pack manifests** — builds a manifest of pack contents and compares manifests
  between client and server, so a mismatch in installed packs is reported instead
  of turning into mysterious behaviour.
- **Creation workshop** — a keybind opens the editor, where you can lay out a
  building: two-point region selection (left click sets point 1, right click sets
  point 2), building categories (residential / commercial / industrial / public /
  other), a searchable item picker, a block-or-block-tag picker, project
  create/edit dialogs, and building rename/delete.
- **Bilingual interface** — complete English and Simplified Chinese localisation.

The online *workshop* tab (search, categories, pack details) is stubbed until the
official site API becomes available.

### Requirements

| Component | Version |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.228 or newer |
| [LDLib2](https://github.com/Low-Drag-MC/LDLib2) | 2.2.41 or newer |
| [New: Sim-U-Kraft](https://modrinth.com/mod/new-sim-u-kraft) | 2.0.0 or newer |

Because pack manifests are compared between client and server, keep the same set
of building packs enabled on both sides.

### Installation

Prebuilt copies are not published on this repository yet. Either
[build from source](#building-from-source), or take the jar produced by the latest
successful run of the [Build workflow](https://github.com/RealSuQingqing/NeoSimupack/actions/workflows/build.yml).

Then drop the jar into your `mods/` folder, alongside NeoForge, LDLib2 and
New: Sim-U-Kraft.

### Building from source

Java 21 is required.

```bash
git clone https://github.com/RealSuQingqing/NeoSimupack.git
cd NeoSimupack

./gradlew build          # macOS / Linux
gradlew.bat build        # Windows
```

The mod jar is written to `build/libs/simupack-1.21.1-2.0.0.jar`.

```bash
./gradlew test           # JUnit 5 unit tests
./gradlew runClient      # development client
```

#### About the New: Sim-U-Kraft jar

`build.gradle` looks for `New-Sim-U-Kraft-2.2.0-neoforge-1.21.1.jar` in the project
root. When it is present, the jar is added to the development runtime classpath
(`compileOnly` + `localRuntime`); it is deliberately **not** committed, because it
is a third-party binary.

You do not need it to build or to test. NeoSimupack reaches every Sim-U-Kraft class
through reflection and never through a compile-time import, so the project compiles
against nothing. The jar is only needed to launch a development client with
Sim-U-Kraft actually loaded. Download it from
[Modrinth](https://modrinth.com/mod/new-sim-u-kraft) and keep the file name above.

### Repository layout

```text
src/main/java/com/rstech/simupack/
├── SimuPackMod.java     mod entry point
├── attention/           "needs your attention" notices: faulty packs, updates
├── client/              screens, widgets, region selection, client settings
├── creation/            building definitions, incl. the industrial action layer
├── network/             pack manifests and client/server manifest comparison
└── pack/                discovery, activation, updates, and legacy conversion
src/main/resources/      pack.mcmeta, lang/{en_us,zh_cn}.json, textures
src/main/templates/      neoforge.mods.toml template
src/test/java/           JUnit 5 tests
```

### Contributing

Bug reports and pull requests are welcome. Please use the issue templates, and when
reporting a problem include your NeoForge, LDLib2 and Sim-U-Kraft versions together
with the relevant log excerpt.

### Credits

- [New: Sim-U-Kraft](https://modrinth.com/mod/new-sim-u-kraft) by NSUK Studio — the
  mod this project extends. NeoSimupack does not bundle or redistribute it.
- [LDLib2](https://github.com/Low-Drag-MC/LDLib2) by KilaBash and contributors — the
  UI framework the screens are built on.
- [NeoForge](https://neoforged.net/) and the NeoForge Mod Development Kit, which this
  project was started from. The template's own license notice is kept in
  [TEMPLATE_LICENSE.txt](TEMPLATE_LICENSE.txt).

### License

Copyright (C) 2026 RealSuQingqing and contributors.

NeoSimupack is free software: you can redistribute it and/or modify it under the
terms of the **GNU General Public License, version 3 only** (`GPL-3.0-only`), as
published by the Free Software Foundation.

It is distributed in the hope that it will be useful, but **without any warranty**;
without even the implied warranty of merchantability or fitness for a particular
purpose. See [LICENSE](LICENSE) for the full text.

---

## 中文

### 这是什么

NeoSimupack（`simupack`，游戏内显示名 **筑艺坊**）是《新：模拟大都市》（New: Sim-U-Kraft）
的 NeoForge 附属模组。它负责**拓展包**的查找、校验、安装、转换与更新——拓展包正是为
Sim-U-Kraft 提供建筑内容的数据包——并内置了一个用于创作建筑的工坊。

本模组仍在积极开发中。当前版本为 `2.0.0`，拓展包格式在正式稳定前仍可能发生变化。

### 功能

- **拓展包管理器** —— 独立界面列出所有已安装的拓展包，支持查看详情、建筑数量统计，以及
  逐个启用 / 禁用。官方内置包带有专门标记，且被锁定，无法禁用或删除。
- **旧版拓展包转换** —— 对早于 `pack.json` 的旧版拓展包（旧命名规则、结构文件尚未转为
  `.nbt`）会在加载时被识别、标记为「旧版」，并就地转换。转换结果默认处于**未启用**状态，
  便于你确认无误后再启用。
- **检查更新** —— 将本地已安装的拓展包与模组内置的版本进行比对，并支持一键更新。
- **提醒中心** —— 汇总拓展包相关的异常（格式错误、待转换的旧版包、可用的官方更新），
  以提醒的形式呈现，而不是让问题被静默忽略。
- **拓展包清单校验** —— 依据拓展包内容生成清单，并在客户端与服务端之间比对，使拓展包
  不一致能被明确报告，而不是表现为难以排查的异常行为。
- **创作工坊** —— 通过快捷键打开编辑器，用于搭建建筑：两点选区（左键设点 1，右键设点 2）、
  建筑分类（住宅 / 商业 / 工业 / 公共 / 其他）、可搜索的物品选择器、方块或方块标签选择器、
  工程新建 / 编辑对话框，以及建筑重命名与删除。
- **双语界面** —— 完整的英文与简体中文本地化。

在线「创意工坊」标签页（搜索、分类、包详情）目前为占位实现，待官网接口接入后启用。

### 依赖

| 组件 | 版本 |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.228 或更高 |
| [LDLib2](https://github.com/Low-Drag-MC/LDLib2) | 2.2.41 或更高 |
| [新：模拟大都市 (New: Sim-U-Kraft)](https://modrinth.com/mod/new-sim-u-kraft) | 2.0.0 或更高 |

由于拓展包清单会在客户端与服务端之间比对，请确保两端启用相同的拓展包集合。

### 安装

本仓库暂未发布构建产物。请参考下文[从源码构建](#从源码构建)，或从
[Build 工作流](https://github.com/RealSuQingqing/NeoSimupack/actions/workflows/build.yml)
最近一次成功运行的产物中获取 jar。

将 jar 放入 `mods/` 文件夹，与 NeoForge、LDLib2 和《新：模拟大都市》一同安装。

### 从源码构建

需要 Java 21。

```bash
git clone https://github.com/RealSuQingqing/NeoSimupack.git
cd NeoSimupack

./gradlew build          # macOS / Linux
gradlew.bat build        # Windows
```

构建产物位于 `build/libs/simupack-1.21.1-2.0.0.jar`。

```bash
./gradlew test           # JUnit 5 单元测试
./gradlew runClient      # 开发环境客户端
```

#### 关于《新：模拟大都市》的 jar

`build.gradle` 会在项目根目录查找 `New-Sim-U-Kraft-2.2.0-neoforge-1.21.1.jar`。
若该文件存在，它会被加入开发运行时的类路径（`compileOnly` + `localRuntime`）；该 jar
**不会**被提交到仓库，因为它属于第三方二进制文件。

构建与运行测试都不需要它。NeoSimupack 完全通过反射访问《新：模拟大都市》的类，不存在任何
编译期引用，因此本项目在没有该 jar 的情况下也能正常编译。只有当你需要在开发客户端中
真正加载《新：模拟大都市》时才需要它。请从
[Modrinth](https://modrinth.com/mod/new-sim-u-kraft) 下载，并保持上文的文件名不变。

### 仓库结构

```text
src/main/java/com/rstech/simupack/
├── SimuPackMod.java     模组入口
├── attention/           提醒中心：异常拓展包与更新通知
├── client/              界面、控件、选区、客户端设置
├── creation/            建筑定义，含工业动作组数据层
├── network/             拓展包清单与客户端/服务端清单比对
└── pack/                加载、启用、更新，以及旧版转换
src/main/resources/      pack.mcmeta、lang/{en_us,zh_cn}.json、贴图
src/main/templates/      neoforge.mods.toml 模板
src/test/java/           JUnit 5 测试
```

### 参与贡献

欢迎提交问题反馈与 Pull Request。请使用仓库提供的 issue 模板；反馈问题时请附上 NeoForge、
LDLib2 与此模组的版本号，以及相关的日志片段。

### 致谢

- [新：模拟大都市 (New: Sim-U-Kraft)](https://modrinth.com/mod/new-sim-u-kraft)，作者 NSUK Studio
  —— 本模组所扩展的对象。NeoSimupack 不打包、也不再分发该模组。
- [LDLib2](https://github.com/Low-Drag-MC/LDLib2)，作者 KilaBash 及贡献者 —— 界面所依赖的
  UI 框架。
- [NeoForge](https://neoforged.net/) 及 NeoForge Mod Development Kit —— 本项目由其模板起步，
  模板自身的许可声明保留在 [TEMPLATE_LICENSE.txt](TEMPLATE_LICENSE.txt)。

### 许可证

版权所有 (C) 2026 RealSuQingqing 及贡献者。

NeoSimupack 是自由软件：你可以依据自由软件基金会发布的 **GNU 通用公共许可证第 3 版
（仅此版本，`GPL-3.0-only`）** 条款，对本程序进行再分发和/或修改。

本程序的发布是希望其有用，但**不提供任何担保**，甚至不提供可商用性或适用于特定用途的
默示担保。完整条款见 [LICENSE](LICENSE)。
