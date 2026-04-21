# Memo Widget

一个后端是 **GitHub** 的 Android 笔记 + 日程 app。写的每一条笔记、每一个日程都会自动 commit 到你自己的 GitHub 仓库，多设备之间自动同步，离线也能用。

[![build](https://img.shields.io/badge/build-passing-brightgreen)]()
[![tests](https://img.shields.io/badge/tests-8%20passed-brightgreen)]()
[![kotlin](https://img.shields.io/badge/kotlin-2.0.10-blue)]()
[![compose](https://img.shields.io/badge/compose-material3-blue)]()
[![glance](https://img.shields.io/badge/glance-1.1.0-blue)]()

---

## 这个 app 能干什么

| 功能 | 说明 |
|---|---|
| 📝 写笔记 | 按天分组的 Markdown 笔记，每条带时间戳 (`## HH:MM`) |
| 📅 记日程 | 标准 iCalendar (`.ics`) 格式，Google / 苹果日历可以直接订阅 |
| 🔍 搜索 | 全文搜索所有笔记 |
| 🏠 桌面小部件 | 快速写一条 (2×2) + 今日日程清单 (4×2) |
| ☁️ GitHub 同步 | 写完自动推送，每 30 分钟后台拉取最新 |
| 📴 离线可用 | 没网也能写，有网自动 push |
| 🔒 加密 Token | PAT 走 Android Keystore 加密存储，不落磁盘明文 |

---

## 界面截图

### 笔记列表

按日期倒序排列所有笔记，顶部有搜索，右下角浮动按钮快速写一条。

![笔记空态](screenshots/p2/01_notes_empty.png)

### 设置页

填 GitHub Personal Access Token / owner / repo / branch，保存后顶部状态条变蓝。PAT 走 Android Keystore 加密，本机也看不到明文。

| 空态 | 填好后 | 保存 |
|---|---|---|
| ![设置-空](screenshots/p2/02_settings_empty.png) | ![设置-填好](screenshots/p2/03_settings_filled.png) | ![设置-保存](screenshots/p2/05_settings_saved.png) |

### 日历 + 日程

月视图支持前后翻月，有笔记/日程的日期下方有蓝色小点。点某一天，下方列出当天所有日程和笔记。右下角"加日程"弹出编辑对话框。

| 月视图 | 新建日程 |
|---|---|
| ![日历](screenshots/p2/06_calendar_empty.png) | ![新建日程](screenshots/p2/07_event_dialog.png) |

### 写笔记

简单的 Markdown 编辑器，支持多行，可以写列表、引用、代码块（Markdown 友好）。保存后自动推送到 GitHub。

| 空白 | 打字中 |
|---|---|
| ![edit 空](screenshots/p2/11_edit_empty.png) | ![edit 打字](screenshots/p2/12_edit_typed.png) |

### 桌面小部件

长按桌面 → 添加小部件 → 找到 **Memo**（2×2，快速写一条）或 **今日**（4×2，今天的日程 + 备忘一览）。

> Widget 实机截图待补（`adb exec-out screencap` 在 launcher 进程里需要额外权限，下个版本用真机截图补上）。

---

## 怎么装

1. 去 [Releases](https://github.com/qqzlqqzlqqzl/memo-widget/releases) 下载最新的 `app-debug.apk`
2. 手机上点这个 apk → 系统会要求你在"设置 → 应用 → 特殊权限 → 安装未知来源"里给浏览器打勾
3. 装好后打开 app → 去设置页填三项：
   - **GitHub PAT**：[去这里生成一个](https://github.com/settings/tokens/new?scopes=repo)，选 `repo` 权限
   - **Owner**：你的 GitHub 用户名
   - **Repo**：你想存笔记的仓库名（提前建好，空仓库也行）
4. 回笔记页或日历页开始用

---

## 数据是怎么存的

你的 GitHub 仓库会长这样：

```
<你的仓库>/
├── 2026-04-21.md          # 当天的笔记，## HH:MM 分段
├── 2026-04-22.md
├── 2026-04-23.md
└── events/
    ├── 7f3c-4a2d.ics      # 一个日程一个文件（标准 iCalendar）
    └── 8b21-9c5e.ics
```

**笔记文件** 打开来长这样：

```markdown
# 2026-04-21

## 14:30
今天学了 Glance widget。

## 15:12
- 买菜
- 跑步 30min

## 18:05
晚餐：凉面
```

**日程文件** 打开来是标准 iCalendar，可以直接被 Google Calendar / 苹果日历订阅：

```
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//memo-widget//EN
BEGIN:VEVENT
UID:7f3c-4a2d
SUMMARY:团队周会
DTSTART:20260422T070000Z
DTEND:20260422T080000Z
END:VEVENT
END:VCALENDAR
```

---

## 架构一览

```
┌─────────────┐   ┌─────────────┐   ┌──────────────┐
│  主 app UI  │   │  桌面小部件  │   │  WorkManager │
│  (3 tabs)   │   │  (Glance)   │   │  (后台同步)   │
└──────┬──────┘   └──────┬──────┘   └──────┬───────┘
       └─────────┬───────┴─────────────────┘
                 ▼
         ┌─────────────────┐
         │  Repository 层  │  本地优先：先写 Room，再推 GitHub
         └────────┬────────┘
                  ▼
          ┌──────────────┐
          │     Room     │  ←── 所有 UI 数据的唯一来源
          └──────┬───────┘
                 │ 脏行队列
                 ▼
          ┌──────────────┐   HTTPS+PAT   ┌──────────┐
          │  Ktor CIO    │ ────────────▶ │  GitHub  │
          │  GitHub API  │ ◀──────────── │  仓库    │
          └──────────────┘               └──────────┘
```

### 关键点

- **本地优先**：每次写笔记/日程，先写 Room 标记为"待同步"，然后尝试立刻推 GitHub。失败则由 WorkManager 定时重试，UI 上显示"待同步"标记
- **冲突处理**：推送带 SHA 乐观锁，拉取时比对 SHA 跳过无变化的文件；本地 dirty 行永远不会被远端覆盖
- **PAT 加密**：走 `EncryptedSharedPreferences` + Android Keystore 硬件加密；从老版本（P1 之前）明文 DataStore 自动迁移
- **FLAG_SECURE**：设置页 PAT 明文可见时自动加屏蔽标记，截图/多任务窗口看不到

---

## 开发/构建

环境：macOS + Android SDK (compileSdk 35) + JDK 17

```bash
# 克隆
git clone https://github.com/qqzlqqzlqqzl/memo-widget.git
cd memo-widget

# 构建
./gradlew :app:assembleDebug
# APK 位于 app/build/outputs/apk/debug/app-debug.apk

# 跑测试
./gradlew :app:testDebugUnitTest

# lint
./gradlew :app:lintDebug
```

**中国镜像**：`settings.gradle.kts` 已配阿里云 Maven 镜像，`gradle-wrapper.properties` 用腾讯云下 Gradle。

---

## 版本

- **v0.3.0-p2**（本版本）：加日历 + 日程 (`.ics`) + 今日清单 widget + 中文 README
- **v0.2.0-p1**：加 Room 离线缓存 + WorkManager 后台同步 + PAT 加密 + 底部导航
- **v0.1.0**：2×2 Memo widget + GitHub PUT 推送

---

## 技术栈

Kotlin 2.0 · Jetpack Compose + Material 3 · Jetpack Glance 1.1 (widget) · Room 2.6 (本地库) · WorkManager 2.9 (后台同步) · Ktor CIO (HTTP) · EncryptedSharedPreferences (PAT) · Navigation Compose 2.8 · [Kizitonwose Calendar](https://github.com/kizitonwose/Calendar) 2.6 (日历) · 自研精简 iCalendar (RFC 5545) 编解码器

---

## 一些已知限制

- 笔记每次只拉最近 14 天，更早的历史要去 GitHub 仓库手动翻
- 日程不支持循环事件（RRULE），每个 event 是独立一次性的
- 多设备同时改同一个 `YYYY-MM-DD.md` 会产生 409 冲突，当前版本通过 SHA 乐观锁重试；若仍失败，保存会被标记为"待同步"继续轮询
- 还没做正式签名版 APK，只有 debug 版；release 打包需要补充 ProGuard 规则
- 主 app 内大部分字符串是中文，但设置页 OutlinedTextField 的 label 与 widget 描述仍是英文，后续版本统一

这些会在下一阶段（P3）收掉。
