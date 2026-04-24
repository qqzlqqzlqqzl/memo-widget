<!--
BDD_SCENARIOS.md — memo-widget 的 Behaviour-Driven Development 场景清单（P6 扩展版）。
受众：QA / 接手 AI agent / 回归测试负责人。
目的：把 happy path 之外的真实用户场景、历史 bug、边界条件收敛成一份可照着跑的清单。
每条场景都标注了 Priority（P0/P1/P2），以及 Why —— 说明哪个 bug 曾经在这里失手、或者哪个 feature 的关键路径。

配套 P0 场景的真机回归结果见 `/tmp/bdd_p6/` 下的截图与 session 总结。
-->

# BDD Scenarios — Memo Widget (P6 / P6.1 / P6.1.1 / P7 / P7.0.1 / P8 累积扩展)

> 90 个场景（P6 基础 28 条 + P6.1 新增 11 条 + P6.1.1 新增 3 条 + P7 新增 7 条 + P7.0.1 新增 3 条 + P8 新增 37 条）。P0=必跑回归（每次 release 前），P1=推荐跑（触及关键 feature 时跑），P2=探索式/已知限制。
>
> 下文 "目录" 只枚举最早的 28 条。后续版本扩展的 #29–#90 在对应 "P6.1 扩展" / "P6.1.1 扩展" / "P7 扩展" / "P7.0.1 扩展" / "P8 扩展" 章节内按编号顺序列出。
>
> 环境前提（全部 P0 场景默认成立）：APK 为 `v0.8.0-p5`（versionCode=8）；emulator-5554 运行 Android 14；模拟器已授予 `POST_NOTIFICATIONS`；PAT 或 OAuth 已配置（除非场景另说）。
>
> 命令模板：每条 P0 截图保存于 `/tmp/bdd_p6/<scenario>_*.png`，logcat 用 `adb -s emulator-5554 logcat -d | grep -iE 'FATAL|AndroidRuntime|dev.aria.memo'`。

---

## 目录

1. [场景 1 · 连续快速多次保存（dup bug 回归）](#场景-1--连续快速多次保存dup-bug-回归) **P0**
2. [场景 2 · Widget 跨天显示](#场景-2--widget-跨天显示) **P0**
3. [场景 3 · 离线 10 条笔记批量 push](#场景-3--离线-10-条笔记批量-push) **P0**
4. [场景 4 · 大量笔记首装 bootstrap 限速](#场景-4--大量笔记首装-bootstrap-限速) **P0**
5. [场景 5 · 两台设备同时推同一天（CONFLICT 自愈）](#场景-5--两台设备同时推同一天conflict-自愈) **P0**
6. [场景 6 · PAT 无效后恢复](#场景-6--pat-无效后恢复) **P0**
7. [场景 7 · 循环事件 DST 切换](#场景-7--循环事件-dst-切换) **P1**
8. [场景 8 · 代码块内的 #tag 被索引（已知限制）](#场景-8--代码块内的-tag-被索引已知限制) **P2**
9. [场景 9 · 置顶多次切换不产生重复 front matter](#场景-9--置顶多次切换不产生重复-front-matter) **P1**
10. [场景 10 · 事件删除后提醒取消](#场景-10--事件删除后提醒取消) **P1**
11. [场景 11 · 手机重启后提醒自动重排](#场景-11--手机重启后提醒自动重排) **P1**
12. [场景 12 · OAuth client_id 为空 → 提示](#场景-12--oauth-client_id-为空--提示) **P1**
13. [场景 13 · OAuth device flow 成功路径 + 超时路径](#场景-13--oauth-device-flow-成功路径--超时路径) **P1**
14. [场景 14 · 阅读模式 checkbox 点击（#22 回归）](#场景-14--阅读模式-checkbox-点击22-回归) **P0**
15. [场景 15 · 搜索框切 tab 保留（#30 回归）](#场景-15--搜索框切-tab-保留30-回归) **P0**
16. [场景 16 · 通知栏 quick-add toggle on/off](#场景-16--通知栏-quick-add-toggle-onoff) **P1**
17. [场景 17 · 锁屏通知标题不泄露（#13 回归）](#场景-17--锁屏通知标题不泄露13-回归) **P1**
18. [场景 18 · Pin 后远端被另一设备改内容（合并不破坏 pin）](#场景-18--pin-后远端被另一设备改内容合并不破坏-pin) **P1**
19. [场景 19 · Room schema migration 1→7（升级场景）](#场景-19--room-schema-migration-17升级场景) **P1**
20. [场景 20 · App 内说明书链接打开浏览器（#37 回归）](#场景-20--app-内说明书链接打开浏览器37-回归) **P1**
21. [场景 21 · 无网络首次启动](#场景-21--无网络首次启动) **P1**
22. [场景 22 · 403 rate-limit vs 401 unauthorized 区分](#场景-22--403-rate-limit-vs-401-unauthorized-区分) **P2**
23. [场景 23 · 嵌套标签 CJK 解析](#场景-23--嵌套标签-cjk-解析) **P2**
24. [场景 24 · 切到日历当月 marker 不卡 UI](#场景-24--切到日历当月-marker-不卡-ui) **P1**
25. [场景 25 · 快速添加通知后 widget 刷新](#场景-25--快速添加通知后-widget-刷新) **P2**
26. [场景 26 · 暗色主题下 Markdown 链接色对比度](#场景-26--暗色主题下-markdown-链接色对比度) **P2**
27. [场景 27 · 中文文件名笔记（URL-encoded 路径）](#场景-27--中文文件名笔记url-encoded-路径) **P2**
28. [场景 28 · Widget 配置了但 app 没配置 PAT](#场景-28--widget-配置了但-app-没配置-pat) **P1**

---

### 场景 1 · 连续快速多次保存（dup bug 回归）
- **Given**：已配置 PAT；`Notes` tab 打开；当日文件为空或已有若干条目。
- **When**：用户在 `EditActivity`（Compose 编辑器）快速点击保存按钮 3 次（点击间隔 < 500ms），或者 widget 2×2 上在 2 秒内连续触发两次 `EditActivity`。
- **Then**：当日文件最终只包含 1 条（或者明确的 N 条，取决于用户实际输入了几次不同 body），不产生重复 `## HH:MM\n{body}` 段；logcat 无 FATAL。
- **Why**：用户刚反馈的 bug。触发点是 `PathLocker.withLock(path)` 必须把"读-合并-写 Room-push"串成原子；如果 `PushWorker` 被并行拉起会和前台的 `appendToday` 抢同一个 SHA，导致云端写双份。P4.2 的 #27 修过一次，最新版本需要再回归一遍。
- **Priority**：**P0**

---

### 场景 2 · Widget 跨天显示
- **Given**：用户昨天（LocalDate=N-1）写了 3 条备忘；今天（LocalDate=N）尚未写任何备忘；4×2 `TodayWidget` 已放桌面。
- **When**：时间跨过 00:00（可以用 `adb shell "date 010101002026.00"` 模拟）；触发 Glance 刷新（系统日变化广播 / 开屏幕 / 用户手动长按 widget 刷新）。
- **Then**：`TodayWidget` 显示今天（N）的日期标题；下方内容区出现空态占位文字，**不**展示昨天的 3 条；备忘部分 itemId 序号正确（issue #3）；事件部分仍展示今天仍然有效的日程。
- **Why**：`TodayWidget` 读 `EventExpander.expand()` 基于"今天"边界；备忘读 `MemoRepository.recentEntries(today)`。如果日期取的是 `LocalDateTime.now()` 而非 `LocalDate.now()`，会出现"凌晨 0:05 还显示昨天"的老 bug。
- **Priority**：**P0**

---

### 场景 3 · 离线 10 条笔记批量 push
- **Given**：已配置 PAT；先开飞行模式（`adb shell svc wifi disable; svc data disable`）；连续写 10 条备忘（可以是 1 天内的 10 条，也可以跨 3–5 天）。写完后每条都在 Room 里标 `dirty=true`，UI 上 `SyncBanner` 提示"10 条待同步"。
- **When**：恢复网络（`adb shell svc wifi enable`）；`SyncScheduler` 的周期 pull/push 触发，或者用户手动点设置页的"立即同步"。
- **Then**：`PushWorker` 按 `path` 维度逐一 PUT；全部成功后 `dirty=0`；GitHub 仓库出现对应的 1–N 个新 commit；logcat 里没有 401/409/403；如果遇到 409 则自动刷 SHA 重试一次（issue #27）。
- **Why**：offline-first 的关键路径。`PushWorker` 的重试、WorkManager 的入队退避、`PathLocker` 跨 worker 都在这条路径上。P4.2 把 HttpTimeout 补了，但 batch 场景（尤其 10 条跨多天）最容易暴露 worker 之间的竞争。
- **Priority**：**P0**

---

### 场景 4 · 大量笔记首装 bootstrap 限速
- **Given**：仓库里已存在 30–60 天的按天文件（`2026-02-22.md` ~ `2026-04-21.md`）；用户在干净的设备上首次装 APK 并填 PAT。
- **When**：首次登录完毕，`MemoApplication.onCreate` 排程周期 `PullWorker`，`PullWorker.bootstrapAllNotes` 被调用；GitHub rate_limit 当前剩余 4000+（`gh api rate_limit --jq .resources.core.remaining`）。
- **Then**：一轮 bootstrap 最多拉 `MAX_BOOTSTRAP_PULLS_PER_CYCLE=50` 个文件（issue #19）；超出部分等下一轮；rate_limit 消耗可控（每文件约 1 次 GET contents）；UI 上 `SyncBanner` 展示进度；全过程 logcat 无 `403 rate_limit_exceeded`。
- **Why**：单用户 5000/hr，如果一次性拉 365 个文件会直接打爆。#19 加了封顶。测 30+ 天是为了确认"跨 cycle 续传"真的续上了，而不是 50 条之后永远停在那里。
- **Priority**：**P0**

---

### 场景 5 · 两台设备同时推同一天（CONFLICT 自愈）
- **Given**：设备 A 和设备 B 都登录同一个 PAT + 仓库；两台设备当日文件都缓存过一次（本地 `githubSha` 都是 `S0`）。
- **When**：
  1. 设备 A 写一条 `body_A`，成功 push，远端 SHA 变成 `S1`。
  2. 还没等到设备 B 下次 pull，设备 B 也写一条 `body_B`，本地仍以为 SHA 是 `S0` 就 push，GitHub 返回 **409 Conflict**。
- **Then**：设备 B 的 `PushWorker` 捕获 409 → 重新 `getFile()` 拿到 `S1` + 远端 body → 本地把 `body_B` 追加到远端 body 之后（或者按文件末尾 `## HH:MM` 段合并）→ 再 PUT 一次 → 成功；最终远端文件既包含 `body_A` 又包含 `body_B`。UI 上两边 pull 后都能看见两条。无 FATAL。
- **Why**：这正是 issue #27 的 fix 目标："409 时刷新 SHA 重试一次"。如果 fix 失效，设备 B 的那条笔记就永远丢了。
- **Priority**：**P0**

---

### 场景 6 · PAT 无效后恢复
- **Given**：用户配了一个 PAT，随后这个 PAT 被 user 在 GitHub 上手动 revoke；app 重启或下次 push。
- **When**：
  1. 第一次 push 远端返回 **401 Unauthorized**；`SyncBanner` 弹 "PAT 已失效，去设置页重新填"；UI 不再重复点炸后端（指数退避）。
  2. 用户打开设置页，粘贴一个新的有效 PAT，保存。
  3. 触发 push（写一条新备忘）。
- **Then**：第 3 步成功 push；Room 里之前的 `dirty` 行被 flush（包括 401 期间积压的）；`SyncStatusBus` 由 `Error(Unauthorized)` 切回 `Idle`。不再提示"PAT 无效"。
- **Why**：#21 修过 `SyncStatusBus` 的 `finally` 块，确保哪怕崩掉也能切回正常。但"换 PAT 之后积压的 dirty 行会不会真的自动补发"这一步经常在重构后回归失效。
- **Priority**：**P0**

---

### 场景 7 · 循环事件 DST 切换
- **Given**：两组事件（时区放在 `VTIMEZONE` 里）：
  - 事件 T1：`TZID=Asia/Shanghai`，每周一 09:00 开始，`RRULE:FREQ=WEEKLY`。
  - 事件 T2：`TZID=America/Los_Angeles`，每周一 09:00 开始，`RRULE:FREQ=WEEKLY`，覆盖 2026-03-08（美国 PST→PDT 夏令时跳变日）。
- **When**：`EventExpander.expand()` 对 2026-03-01 ~ 2026-03-31 窗口展开，日历切到 3 月份。
- **Then**：
  - T1（Asia/Shanghai，无 DST）：03-02、03-09、03-16、03-23、03-30 各一次，每次本地 09:00。
  - T2（America/Los_Angeles）：03-02 是 PST 09:00（对应 UTC 17:00），03-09 是 PDT 09:00（对应 UTC 16:00），展开出来的**本地时间仍然是 09:00**（RRULE 是墙钟时间，不是 UTC）；对应的 `AlarmManager` trigger epoch ms 反映这一小时的位移。
- **Why**：kotlinx-datetime 默认按 UTC 计算，如果用了 `Instant + duration` 而不是 `LocalDateTime + zone` 就会在 DST 日踩坑。P4 加了 `FREQ=WEEKLY/MONTHLY`，但 DST 边界需要显式验证。
- **Priority**：**P1**

---

### 场景 8 · 代码块内的 #tag 被索引（已知限制）
- **Given**：当日备忘 body 里包含一个 fenced code block：
  ```
  ## 14:30
  学 bash：

  \`\`\`bash
  grep -r "#TODO" src/
  \`\`\`
  ```
- **When**：切到 Tags tab；`TagIndexer.indexAll()` 跑过。
- **Then**：标签树里出现 `#TODO` 节点（内含这条备忘的引用），**虽然这个 `#TODO` 是写在代码块里的、语义上不是用户打的标签**。点击 `#TODO` 仍能跳回当日笔记。
- **Why**：当前 `TagIndexer` 用简单正则 `#[A-Za-z0-9/_一-龥]+` 扫整个 body，没有 markdown AST 感知；剔除代码块需要 fenced + indented + inline 三套识别，短期内不值。**这条是已知限制，目的是让 QA 知道这不是 bug**，并且 release note 里要写。
- **Priority**：**P2**

---

### 场景 9 · 置顶多次切换不产生重复 front matter
- **Given**：一个已有内容的笔记文件 `2026-04-21.md`，body 形如 `# 2026-04-21\n\n## 14:30\n...`。
- **When**：用户在 NoteList 上点星：pin → unpin → pin → unpin → pin（5 次）。每次 `MemoRepository.togglePin(path)` 会改写 front matter（`---\npinned: true\n---\n...`）并 push。
- **Then**：最后一次（pin=true）后，本地 + 远端的文件内容**恰好**是：
  ```
  ---
  pinned: true
  ---
  # 2026-04-21

  ## 14:30
  ...
  ```
  没有重复的 `---` 块、没有叠着的 `pinned: true\npinned: false\n`、原有 body 一字不差；Room 里 `isPinned=true`；`observeAll()` 排序中这条排在未 pin 的上面。
- **Why**：front-matter 往返是 P4.3 的新 feature。解析必须是幂等的——否则 5 次切换后文件里会有 5 个 `---` 对。`MemoRepositoryPinTest` 覆盖了单元逻辑，但真机上多次 push + pull 的端到端要回归。
- **Priority**：**P1**

---

### 场景 10 · 事件删除后提醒取消
- **Given**：一个有 `reminderMinutesBefore=15` 的一次性事件，`AlarmManager` 已排了一个未来 alarm（`adb shell dumpsys alarm | grep dev.aria.memo` 能看见）。
- **When**：用户在日历编辑对话框点删除 → `EventRepository.delete(uid)` 被调用。
- **Then**：Room 里该事件行被删（或 `tombstoned=true`）；远端 `.ics` 文件被 DELETE；`AlarmScheduler.cancel(uid)` 被调用；`adb shell dumpsys alarm | grep dev.aria.memo` 下次查已经看不到这个事件的 alarm；触发时间到达时**不会**弹通知。
- **Why**：漏删 alarm 是非常 creepy 的 bug —— 用户觉得已经删掉的事会在半夜"诈尸"通知他。`EventRepository.delete` 里 AlarmScheduler.cancel 必须跟 DAO delete 放同一个事务边界里。
- **Priority**：**P1**

---

### 场景 11 · 手机重启后提醒自动重排
- **Given**：有 N=3 个未来事件，都配了提醒；APP 没在前台；`AlarmManager` 里已排 3 个 alarm。
- **When**：`adb reboot`（或 `adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -n dev.aria.memo/.notify.BootReceiver --receiver-foreground`）。
- **Then**：重启后 `BootReceiver.onReceive` 被调（issue #17 限定只处理 `BOOT_COMPLETED`）；它遍历 `EventDao.observeBetween(now, forever)` 的非 tombstoned 事件，对每个调 `AlarmScheduler.schedule`；`dumpsys alarm` 再次显示 3 个 alarm，trigger epoch 正确；对应时间点通知弹出。
- **Why**：Android 一重启所有 `AlarmManager` slot 都清空。`RECEIVE_BOOT_COMPLETED` 是声明在 manifest 的权限；`BootReceiver` 如果写错（比如只处理 `MY_PACKAGE_REPLACED`）就会导致用户重启手机后所有提醒静默消失。
- **Priority**：**P1**

---

### 场景 12 · OAuth client_id 为空 → 提示
- **Given**：构建时 `BuildConfig.GITHUB_OAUTH_CLIENT_ID` 没设（或 `local.properties` 里就是空字符串）；用户进设置页点"用 GitHub 登录"。
- **When**：`OAuthSignInViewModel.start()` 调用 `GitHubOAuthClient.requestDeviceCode("")`。
- **Then**：立即返回 `OAuthResult.Err(OAuthErrorKind.BadClientId, "client_id is blank")`；UI 弹 Dialog "应用没配置 OAuth client_id，请使用 PAT 方式登录"；**不**发出任何网络请求（`logcat -s OkHttp:*` 无 `/device/code`）。
- **Why**：防止用户在 release 编译里点了一个永远无法完成的按钮。`GitHubOAuthClient.requestDeviceCode` 开头显式判 `isBlank()` 就是这条场景。
- **Priority**：**P1**

---

### 场景 13 · OAuth device flow 成功路径 + 超时路径
- **Given**：`BuildConfig.GITHUB_OAUTH_CLIENT_ID` 合法；用户点"用 GitHub 登录"。
- **When (A-成功)**：App 拿到 `user_code`（例如 `WDJB-MJHT`）+ 复制按钮；用户在浏览器完成 `https://github.com/login/device` 授权；App 每 `interval` 秒 poll 一次 `/login/oauth/access_token`；第 N 次轮询返回 `access_token`。
- **Then (A)**：`SecurePatStore.setToken(accessToken)` 存进 keystore；UI 回主页；设置页 PAT 框展示 `****`（掩码）；立刻触发一次 pull，Notes 开始出现远端数据。
- **When (B-超时)**：用户把浏览器关了不授权，15 分钟后 GitHub 返回 `expired_token`。
- **Then (B)**：App 停止 poll；UI 显示 "授权已过期，请重试"；没有无限 loop 消耗电池；`SecurePatStore` 里 PAT 仍保持之前的值（不被覆盖成空）。
- **Why**：device flow 是 P5 新加的登录方式。两条路径（成功 / expired）是契约边界；`pollAccessToken` 里的 delayProvider 要真的退出循环。
- **Priority**：**P1**

---

### 场景 14 · 阅读模式 checkbox 点击（#22 回归）
- **Given**：一条笔记 body 里有 markdown 清单：
  ```
  ## 10:00
  - [ ] 买菜
  - [ ] 跑步
  - [x] 倒垃圾
  ```
  进入 `EditScreen` 的阅读模式（`ChecklistRenderer` 渲染）。
- **When**：用户点第一个 `- [ ] 买菜` 的 checkbox；然后再点一下它切回未选；最后点 `- [x] 倒垃圾` 切回未选。再切出当前屏幕（旋转 / 回到 NoteList 又回来）。
- **Then**：每次 toggle 后 body 的对应行字符串被替换（`- [ ]` ↔ `- [x]`），**同一行内的其他内容不变**；切屏回来状态持久（因为已经写回 Room + push）；`EventEditDialog` 里的 `sessionKey` 残留问题不再出现（issue #22 是关于 dialog 的，但清单 checkbox 的 rememberSaveable 共享 key 容易踩同一个坑）。
- **Why**：#22 修过一次 `rememberSaveable(sessionKey)` —— 对话框 session 串台。Checklist 是 P4.3 新 feature，它也用 rememberSaveable，必须单独覆盖一次回归。
- **Priority**：**P0**

---

### 场景 15 · 搜索框切 tab 保留（#30 回归）
- **Given**：NoteList tab 的搜索框输入 `"会议"`（筛出 3 条）。
- **When**：用户切到 Calendar tab，再切到 Settings tab，再切回 Notes tab。
- **Then**：搜索框里仍然显示 `"会议"`；筛选结果仍然是那 3 条；滚动位置也保留；Tags tab（P4.3）同理。
- **Why**：#30 "Notes tab 状态保存"。`AppNav` 用 `NavController` + `saveState=true`，ViewModel 要在 `SavedStateHandle` 里存搜索字符串，否则切 tab 会把 `_uiState.value.query` 清空。
- **Priority**：**P0**

---

### 场景 16 · 通知栏 quick-add toggle on/off
- **Given**：设置页有 "快速添加常驻通知" 开关（`PreferencesStore.quickAddEnabled`）；初始状态 off；通知栏没有 `memo-widget` 相关持久通知。
- **When**：
  1. 打开开关。
  2. 下拉通知栏。
  3. 点通知 → 进 `EditActivity`。
  4. 写一条备忘保存 → 回主 app。
  5. 关闭开关。
- **Then**：
  - 开启后通知栏出现一条 IMPORTANCE_LOW、静音、不上 badge 的 `quick_add` channel 通知（标题 "快速添加备忘"）。
  - 点击跳 EditActivity。
  - 写完备忘回主 app，通知仍然在（常驻 ongoing）。
  - 关闭开关后，通知立即消失；`adb shell dumpsys notification --noredact | grep dev.aria.memo` 不再返回。
  - App 重启后，`MemoApplication.onCreate` 根据 `PreferencesStore` 值重建 —— off 就不建。
- **Why**：P4.3 新 feature。`QuickAddNotificationManager` 必须幂等（重复 enable 不会建两条）、响应 `PreferencesStore` 变化、app 重启后能正确恢复。
- **Priority**：**P1**

---

### 场景 17 · 锁屏通知标题不泄露（#13 回归）
- **Given**：`event_reminders` channel 的 `VISIBILITY_PRIVATE`；设置锁屏"隐藏敏感通知"（系统设置里默认如此）；有个事件标题叫 `"HR 面谈：离职谈判"`、15 分钟前提醒。
- **When**：锁屏 + 触发时间到 → `EventAlarmReceiver` 发通知。
- **Then**：锁屏通知条目显示的是 channel 默认文案（如 "memo-widget · 事件提醒"），**不暴露具体标题**；解锁后展开才显示 `"HR 面谈：离职谈判"`。
- **Why**：#13 修过一次。这是隐私层面的 bug，也最容易在后续 channel setup 重构时被改回 `VISIBILITY_PUBLIC`。
- **Priority**：**P1**

---

### 场景 18 · Pin 后远端被另一设备改内容（合并不破坏 pin）
- **Given**：设备 A 在 `2026-04-21.md` 写了一条备忘，并设置 `pinned: true`（本地 Room `isPinned=true`，远端文件有 front matter）；设备 B 同一仓库，尚未 pull。
- **When**：
  1. 设备 B pull，获得 front matter + body。
  2. 设备 B 在这个文件 appendToday 追加一条 `body_B`，push。
  3. 设备 A pull 最新版本。
- **Then**：设备 A pull 下来的新文件仍然以 `---\npinned: true\n---` 开头；设备 A Room 里 `isPinned` 保持 true；NoteList 里这条仍然置顶；body 里有 `body_A` 和 `body_B` 两条 `## HH:MM` 段。设备 B pull 下来也一样（front matter 被保留）。
- **Why**：#12 模式的 bug：远端覆盖本地偏好。Pin 是文件级偏好，写在 front matter 里时它是"内容"，但本地 Room 的 `isPinned` 列必须与文件内 front matter 保持一致。合并时不能把 front matter 吃掉。
- **Priority**：**P1**

---

### 场景 19 · Room schema migration 1→7（升级场景）
- **Given**：一台装了 P1 老版本（`versionCode=2`、Room schema v1）的设备，里面已有若干天备忘数据；用户未卸载，直接覆盖安装 `v0.8.0-p5`（versionCode=8、Room schema v7）。
- **When**：app 冷启动，`AppDatabase.build()` 运行 `addMigrations(MIGRATION_1_2, 2_3, 3_4, 4_5, 5_6, 6_7)`。
- **Then**：迁移链跑完无异常；Room 数据库 version 变为 7；`note_files` 表出现 `date` 索引（v6）、`isPinned` 列（v7）；历史数据全部保留（原先的 N 条 day-file 记录还在）；UI 上 NoteList 能正常显示历史笔记；`isPinned` 默认 false；logcat 无 `IllegalStateException: Migration didn't properly handle`。
- **Why**：跨版本升级是用户侧最容易炸的场景。每次 ALTER TABLE 都要加 Migration，漏一个就是强制 clear data。HANDOFF §3 不变量 #7 就是这条。
- **Priority**：**P1**

---

### 场景 20 · App 内说明书链接打开浏览器（#37 回归）
- **Given**：设置页点"查看说明书" → 进 `HelpScreen`；`user_guide.md` 里有 `[README](https://github.com/qqzlqqzlqqzl/memo-widget)` 这类外链。
- **When**：用户点该链接。
- **Then**：系统浏览器（或用户默认浏览器）打开目标 URL；**注意当前行为**：`MarkdownRenderer.kt:293` 对 URL 没有显式 scheme 白名单（只做 `Intent.ACTION_VIEW` + `FLAG_ACTIVITY_NEW_TASK`），因此 http/https 都会启动外链，但 file:// 之类形态走 chooser。如果 issue #37 的本意是 "非 https 阻止"，则需确认这一行为是否与预期一致。
- **Why**：如果渲染器未来被改成支持任意 scheme（比如 `javascript:`、`content://`），可能成为一个钓鱼攻击面。说明书内容本身是 APK 内置的 markdown，不易被篡改，但回归时仍建议确认点击合法链接 → 浏览器；点击非 http(s) 链接 → 不崩、不静默启动。
- **Priority**：**P1**

---

### 场景 21 · 无网络首次启动
- **Given**：开飞行模式；装 APK；第一次打开 app；尚未配置 PAT。
- **When**：用户进入 Notes tab。
- **Then**：Notes 空态（Room 里本来也没数据）；SyncBanner 显示 "未配置"（或 "NOT_CONFIGURED"）；设置页可进入；点"保存"只在 Room 里写，不尝试 push（返回 `ErrorCode.NOT_CONFIGURED`）；无网络 dialog、无崩溃、无 ANR。
- **Why**：首次使用但还没填 PAT 时，很多 app 会无脑尝试网络 + 吐出一个错误 snackbar。offline-first 合约要求这时候一切要安静。
- **Priority**：**P1**

---

### 场景 22 · 403 rate-limit vs 401 unauthorized 区分
- **Given**：构造一个 mock GitHub server（或者用 `gh api -X PUT -H "X-RateLimit-Remaining: 0"` 模拟），返回 403；另一条路径配置一个无效 PAT 返回 401。
- **When**：`PushWorker` 分别遇到这两种响应。
- **Then**：
  - 403：`ErrorCode.RATE_LIMITED`；`SyncBanner` "GitHub 限速中，稍后自动重试"；WorkManager 指数退避；不弹"PAT 无效"。
  - 401：`ErrorCode.UNAUTHORIZED`；`SyncBanner` "PAT 已失效，去设置页重新填"；不继续重试（避免烧 quota）。
- **Why**：issue #26 的 fix：之前 GithubApi 把 403 和 401 都 fold 成一个错误，导致限速时被误报为 "PAT 无效"，用户把 PAT 重填了没卵用。
- **Priority**：**P2**

---

### 场景 23 · 嵌套标签 CJK 解析
- **Given**：备忘 body 里写 `#工作/周会/跟进 TODO` 和 `#读书/技术/Kotlin` 两种标签。
- **When**：Tags tab 打开；`TagIndexer.indexAll()` 跑过。
- **Then**：标签树展示两棵：
  - 工作 → 周会 → 跟进 TODO
  - 读书 → 技术 → Kotlin

  点叶子节点跳到对应日笔记；标签名按 CJK 字符边界正确切分（不会因为空格把 "跟进 TODO" 切断，因为 `#` 到下一个空白/换行才结束）。
- **Why**：`TagIndexerTest` 里覆盖了 CJK + 嵌套，真机上 Compose LazyColumn 渲染 + 点击导航要单独 smoke 一遍。
- **Priority**：**P2**

---

### 场景 24 · 切到日历当月 marker 不卡 UI
- **Given**：当月（2026-04）有 20+ 个事件（含循环）；`CalendarViewModel` 启动时要展开一次。
- **When**：从 Notes tab 切到 Calendar tab。
- **Then**：UI 立刻响应（< 100 ms 显示空月视图 + loading 小圆圈），展开的 marker 在下一帧补上，**不阻塞主线程**；切月份时从缓存（issue #31）读，不重新展开；无 ANR（tracking: `adb shell dumpsys activity | grep ANR`）。
- **Why**：issue #7 把 EventExpander 挪到 Dispatchers.Default；#31 加了缓存。20+ 事件 * 30 天 = 几百次 occurrence 计算，足够在慢机上卡 500ms+。
- **Priority**：**P1**

---

### 场景 25 · 快速添加通知后 widget 刷新
- **Given**：桌面有 `MemoWidget` 2×2 和 `TodayWidget` 4×2；通知栏有 quick-add 常驻通知。
- **When**：从通知进 `EditActivity` 写一条备忘保存 → 回桌面。
- **Then**：两个 widget 在 5 秒内刷新到包含新备忘的内容（`TodayWidget` 今日清单多一条，`MemoWidget` "最近 3 条"更新）；不需要手动长按刷新。
- **Why**：Glance widget 的刷新路径是 `AppWidgetManager.updateAppWidget` 或 `GlanceAppWidgetManager.updateAll`，写入 Room 之后必须主动触发一次。如果 `EditViewModel.save()` 成功后漏调，widget 会停在旧内容上一段时间。
- **Priority**：**P2**

---

### 场景 26 · 暗色主题下 Markdown 链接色对比度
- **Given**：系统深色模式；说明书页面 `MarkdownRenderer` 渲染含多处 `[text](url)`。
- **When**：进入 `HelpScreen`。
- **Then**：链接文字（`MaterialTheme.colorScheme.onSurface` + 下划线）在深色背景上可读；不是纯黑、不是和背景同色；点击时 ripple 正常；WCAG AA 对比度应 ≥ 4.5:1。
- **Why**：探索式 a11y 检查。新加的 `MarkdownRenderer` 用 `onSurface` 作为链接色，这在 Material 3 深色主题下有时会与正文色混同（用户看不见哪个是链接）。
- **Priority**：**P2**

---

### 场景 27 · 中文文件名笔记（URL-encoded 路径）
- **Given**：仓库里存在一个文件名包含中文的备忘：`2026-04-21-工作日志.md`（理论上 `pathTemplate` 不会生成这种，但手动 push 到仓库模拟用户自编辑）。
- **When**：PullWorker 下一轮拉取。
- **Then**：`GitHubApi.getFile(path)` 的 URL 编码正确（`%E5%B7%A5%E4%BD%9C%E6%97%A5%E5%BF%97`），不 400、不 404；Room 里落入一行；NoteList 正常展示。Push 对这个文件也能工作（但因为 `filePathFor()` 不会生成此格式，push 仅发生在手动编辑 body 之后）。
- **Why**：探索式；`pathTemplate` 当前不支持中文占位符，但用户可能在云端手动开了文件。Ktor 的 URL 构建默认会 encode，但在 `contents/{path}` 里如果用了 `%s` 模板就可能漏 encode。
- **Priority**：**P2**

---

### 场景 28 · Widget 配置了但 app 没配置 PAT
- **Given**：桌面已添加 MemoWidget 2×2；app 从未完成设置（PAT 空）。
- **When**：用户点 widget（触发 `EditActivity` → 写 body → 保存）。
- **Then**：Room 里仍然写入本条（offline-first 合约）；`appendToday` 返回 `ErrorCode.NOT_CONFIGURED`，但 UI 不弹 crash；`SyncBanner` 回到 app 后显示"1 条待同步"+ "未配置"；用户在设置页填完 PAT 后，PushWorker 一次性补发。
- **Why**：边界场景。widget 先于 PAT 配置是常见新手路径（用户把 widget 拖上桌面是第一感，设置 PAT 反而靠后）。`appendToday` 的 `NOT_CONFIGURED` 分支必须先写 Room 再返回错误，否则这条笔记就丢了。
- **Priority**：**P1**

---

## 附：Priority 策略

- **P0（必跑）**：1, 2, 3, 4, 5, 6, 14, 15 —— 每个 release 前在真机跑一遍，保证 happy path + 最容易回归的区域无新 bug。
- **P1（触及相关代码时跑）**：7, 9, 10, 11, 12, 13, 16, 17, 18, 19, 20, 21, 24, 28 —— 新 feature/refactor 触及对应路径时触发。
- **P2（探索式 / 已知限制）**：8, 22, 23, 25, 26, 27 —— 有资源时探索，或作为"已知限制"写 release note。

## 附：跑 BDD 的命令模板

```bash
# 1. 安装最新 APK
~/Library/Android/sdk/platform-tools/adb -s emulator-5554 install -r \
    ~/AndroidProjects/memo-widget/app/build/outputs/apk/debug/app-debug.apk

# 2. 清 logcat 缓冲区，开始新一轮
~/Library/Android/sdk/platform-tools/adb -s emulator-5554 logcat -c

# 3. 启动 app
~/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell am start -n dev.aria.memo/.MainActivity

# 4. 每场景截图 + 存档
~/Library/Android/sdk/platform-tools/adb -s emulator-5554 exec-out screencap -p > /tmp/bdd_p6/<scenario>_<step>.png

# 5. 每场景扫 FATAL
~/Library/Android/sdk/platform-tools/adb -s emulator-5554 logcat -d | grep -iE 'FATAL|AndroidRuntime|dev.aria.memo' | head -50

# 6. 查排了哪些 alarm
~/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell dumpsys alarm | grep dev.aria.memo
```

---

---

# P6.1.1 扩展（deferred bug 清零）

> 3 条新增场景，覆盖从 P6.1 deferred 到 P6.1.1 补齐的修复。版本 `v0.10.1-p6.1.1`。

## 场景 41 · 首装未配 PAT 写笔记不丢（issue #57）**P0**

- **Given**：全新安装，未配置 GitHub PAT（Settings 中 owner/repo/PAT 均空）
- **When**：从笔记列表点「+」→ 输入 `hello first note` → 点保存
- **Then**：
  - UI 显示保存成功（SaveState.Success）
  - SyncBanner 提示 `未配置 GitHub · 笔记已存本地，待配置后自动同步`
  - Room `single_notes` 表有 1 条 `dirty=true` 记录
- **And**：杀进程重启 app，笔记仍在列表里（未丢）
- **And**：进 Settings 填 PAT → PushWorker 自动推送，远程 `notes/<fileName>.md` 出现
- **Why**：P6.1 之前 `SingleNoteRepository.create` 在 NOT_CONFIGURED 时直接 return Err 不写 Room，首装用户写的笔记直接丢。与 legacy `appendToday` 同类 pre-existing bug，P6.1.1 修掉

## 场景 42 · recentEntriesAcrossDays SQL LIMIT 下推（issue #51）**P1**

- **Given**：Room 里有 200 个 day-files（2 年历史）
- **When**：Widget 拉取 top-3 recent entries
- **Then**：`MemoRepository.recentEntriesAcrossDays(3)` 只从 Room 读最多 7 个 day-files（`limit * 2 + 1`），不再全表扫
- **How**：打开 Android Studio Database Inspector 查 SQLite 执行计划：`SELECT * FROM note_files ORDER BY isPinned DESC, date DESC LIMIT 7`
- **Fallback**：如果这 7 个文件凑不够 3 条 entries（极端稀疏），回退全表读——手工构造场景：200 天里只有 1 天写了 3 条，其余全是 `# date\n`（无 entry）

## 场景 43 · EditViewModel 通过 Repository 读内容（issue #56）**P2 · 回归守卫**

- **Given**：从 quick-add 通知启动 EditActivity，不带 intent extra
- **When**：EditViewModel.prime() 触发，从 today 的 path 载入现有 content
- **Then**：使用 `ServiceLocator.repository.getContentForPath(path)` 而非 `ServiceLocator.noteDao().get(...)`
- **How**：代码层面 `grep -n "ServiceLocator.noteDao()" app/src/main/java/dev/aria/memo/ui/` 应无 match
- **Why**：UI → Repository → DAO 分层强制；直访 DAO 让未来拆 DI（如 Koin/Hilt）时 UI 层强耦合到 Room

---

**文件位置**：`/Users/aria-score-00/AndroidProjects/memo-widget/BDD_SCENARIOS.md`
**最近更新**：2026-04-23（P6.1.1 BDD 扩展，追加 3 条）
**维护责任人**：每次新 feature 落地后，对应在这里追加一条 BDD 场景（规则：只要 touch 了一个 `.kt`/`.xml`，BDD 就要新增或修订）。

---

# P6.1 扩展（UI 大改版 + SingleNote UI 集成 + 数据层债务）

> 11 条新增场景，覆盖 P6.1 全部 UX/架构改动。环境前提：`v0.10.0-p6.1`（versionCode=10），emulator 同前。
> issues：闭环 #42 / #43 / #49 / #50。

## 场景 29 · Markdown 工具栏环绕选中文本（issue #42）**P0**

- **Given**：编辑页，输入 `这是一段要加粗的文字`，双指选中"要加粗的"
- **When**：点底部工具栏 **B**（加粗按钮）
- **Then**：选区内容变成 `**要加粗的**`，光标落在 `**` 右边界外；字符数统计变化 +4
- **Why**：工具栏有两种模式——环绕选区 / 空选时在光标插入 `****` 并定位光标 —— 两者都要测

## 场景 30 · Markdown 工具栏空选插入（issue #42）**P1**

- **Given**：空编辑页，光标在 body 开头
- **When**：依次点工具栏 **H1** → **列表** → **复选框**
- **Then**：出现 `# \n- \n- [ ] `，光标落在最后一行末
- **Why**：行首 token 的 insertion 要不破坏已有结构

## 场景 31 · WordCountFooter 实时更新（issue #42, #55）**P1**

- **Given**：编辑页，body = `"hello 你好 😀"`
- **When**：输入新字符 `x`
- **Then**：底部文案 `字符数：N  ·  行数：1`，N 按 **codePoint** 计（emoji 占 1 不占 2）
- **Why**：#55 修了 surrogate-pair 双算 bug；确认 UI 反映

## 场景 32 · ScrollAwareFab 随滚动收缩（issue #42, #45）**P0**

- **Given**：笔记列表 ≥ 20 条（足够滚动），FAB 展开为 `写一条`
- **When**：向下滑动 300dp
- **Then**：FAB 收缩为只剩 `+` 图标；LargeTopAppBar 折叠同步发生
- **And**：CalendarScreen 同样行为（`加日程` 随 top bar 折叠一起收缩，issue #45 修复前一直展开）

## 场景 33 · LargeTopAppBar 滚动折叠（issue #42）**P0**

- **Given**：任一顶级 screen（笔记/日历/设置/帮助/标签）
- **When**：内容区滚动
- **Then**：标题从 large 变体平滑过渡到 standard 变体；标题文字保留

## 场景 34 · MemoEmptyState 插画空态（issue #42）**P1**

- **Given**：全新安装，未配 PAT，未写笔记
- **When**：打开笔记 tab
- **Then**：屏幕中心圆形 `tertiaryContainer` 图标 + `还没有笔记` 标题 + `点右下角「写一条」开始记录` 副标题
- **And**：搜索不到结果时用 `SearchOff` 图标 + `没找到匹配的笔记` + `试试换个关键词`
- **And**：日历无事件当日用 `EventAvailable` 图标 + `今日无事件`

## 场景 35 · LegacyDay 与 SingleNote 视觉区分（issue #42）**P0**

- **Given**：仓库同时有老 `2026-04-20.md` 和新 `notes/2026-04-20-0915-xxx.md`
- **When**：打开笔记列表
- **Then**：老按天文件的 EntryCard 左侧是 **primary 绿条**；新单笔记 SingleNoteRow 左侧是 **tertiary 蓝紫条**；两种可一眼区分

## 场景 36 · 点 SingleNote 卡片进入编辑模式（issue #42）**P0**

- **Given**：笔记列表有一条 SingleNote `notes/2026-04-20-0915-foo.md` body=`hello`
- **When**：点该卡片
- **Then**：跳转 EditActivity；编辑器 body 预填 `hello`；TopAppBar 标题 `写点什么`
- **And**：保存改动走 `updateSingleNote(uid, newBody)`，不是 `createSingleNote`
- **And**：未保存时退出，下次从列表点进来，显示的是仓库里的原值（issue #44 race 回归）

## 场景 37 · Widget 点 SingleNote 深链编辑（issue #42）**P0**

- **Given**：桌面 MemoWidget，显示了 3 条 single notes
- **When**：点任一条
- **Then**：打开 EditActivity，extra `EXTRA_NOTE_UID` 非空；编辑器预填该 note；保存走 update（不是 create 新建）

## 场景 38 · 单轮 pull 共享 budget=150（issue #43）**P0**

- **Given**：GitHub 仓库有 60 day-files + 60 events + 50 single-notes（合计 170 个文件）
- **When**：卸载重装（Room 空），触发首次 pull cycle
- **Then**：单轮 GET 请求数 ≤ **150**（bootstrap + window + events + singles 共享预算）；剩余在 retry cycle 再拉；logcat 无 `403 rate_limit_exceeded`
- **How**：`./gradlew installDebug && adb shell am force-stop dev.aria.memo && adb shell pm clear dev.aria.memo && adb shell am start -n dev.aria.memo/.MainActivity`，用 GitHub API `rate_limit` 查 remaining 差值
- **Why**：P6.1 第 6 项的全局 budget 护栏；旧逻辑 50+14+50+50=164 会险中打穿

## 场景 39 · 卸载重装后旧深链走僵尸 uid 路径（issue #50）**P1**

- **Given**：用户 A 曾有 note `uid=abc`，桌面 widget 存了这 uid 的深链
- **When**：卸载 app 重装（Room 重置），再点旧 widget 深链
- **Then**：EditActivity 打开，editor body 空白（load 返回 null）；VM 仍处于 edit-mode (`noteUid != null`)；若用户在 editor 中输入 + 保存 → 仓库返 `NOT_FOUND`，UI 显示 error snackbar
- **Why**：#50 锁定此现行行为；未来若改为"自动降级到 create"需同步改本场景
- **Known limitation**：目前无 `"笔记已不在，是否新建？"` 引导 —— 列入 P6.2 TODO

## 场景 40 · LegacyDay 排序塌陷（issue #49）**P2 · 已知限制**

- **Given**：同一天既有 legacy day-file（含 `## 23:00` 晚间条目）又有 single note `09:00`
- **When**：打开笔记列表
- **Then**：**Single note 09:00 排在 legacy day-file 上方**（即使 legacy 里有 23:00 的条目）
- **Why**：`ITEM_ORDER.timeOf(LegacyDay) = MIDNIGHT` 设计选择 —— LegacyDay 按天聚合不参与时分排序。未来若要精细排序需按 `group.entries.maxOf { it.time }` 重算，但会让大日文件每次排序都 O(n) 扫
- **If not acceptable**：P6.2 切换到 `singleNoteRepo` 一次性迁移后此问题自然消失

---

# P7 扩展（AI 问答助手）

> 7 条新增场景，覆盖 P7 AI 问答功能的所有关键路径：空态引导、配好后对话、上下文三档、长文截断、401/网络错误恢复。版本 `v0.11.0-p7`。

## 场景 44 · AI 未配置 → 空态引导 **P0**

- **Given**：用户第一次点底部「AI 助手」tab；Settings 里 `aiProviderUrl` / `aiModel` / `aiApiKey` 均空；`AiConfig.isConfigured == false`
- **When**：AI 助手 screen 渲染
- **Then**：
  - 屏幕中央出现 `MemoEmptyState`（圆形 `tertiaryContainer` 图标 + 标题 `还没配置 AI`），副标题 `点下方按钮去设置里填 provider URL、model、API key`
  - 底部一个 primary-tonal 按钮「去设置」；点击 → 跳 `SettingsScreen`，自动滚到 AI 配置 section
  - 输入框被 disabled（hint：`先配置 AI 才能聊天`）
  - `AiChatViewModel` 不会发出任何 HTTP 请求（`logcat -s OkHttp:*` 无 `/chat/completions`）
- **Why**：与 PAT 场景 21 同构的 "offline-first / unconfigured-first" 合约——用户没填 key 就安静待命，不弹 dialog、不崩、不骚扰

## 场景 45 · 配好 key 后简单对话 **P0**

- **Given**：Settings 里填：
  - `aiProviderUrl = https://api.openai.com/v1/chat/completions`
  - `aiModel = gpt-4o-mini`
  - `aiApiKey = sk-<valid>`
  - 其他字段不动
- **When**：
  1. 进「AI 助手」tab
  2. 输入框输入 `你好`
  3. 点发送
- **Then**：
  - 500–2000 ms 内（取决于 provider 速度）transcript 下方追加一条 `role=assistant` 消息，内容是中文问候（如 "你好！有什么可以帮你的？"）
  - 发送期间按钮转圈（`isSending=true`），返回后恢复
  - 输入框清空，可继续下一轮
  - logcat 有一条 `POST https://api.openai.com/v1/chat/completions` 200 的 OkHttp 日志
  - Request body 含 `Authorization: Bearer sk-<valid>`
- **Why**：端到端 smoke。覆盖 DTO 序列化、Ktor 请求、ContentNegotiation 解析 choices[0].message.content 这条 golden path

## 场景 46 · 上下文 = 当前笔记 **P1**

- **Given**：有一条 SingleNote `uid=note-abc`，body 内容 `今天下午 3 点开了一个关于 Q2 路线图的会，讨论了性能、可观测性、移动端三个方向`
- **When**：
  1. 笔记列表长按该卡片，选择 `问 AI`（或卡片出菜单 → 「AI 助手」）
  2. AI screen 打开时 `initialNoteUid = note-abc` 被传入
  3. `state.contextMode` 默认为 `CURRENT_NOTE`
  4. 输入框问 `总结一下这次会议`，发送
- **Then**：
  - `AiContextBuilder.buildSystemPrompt(CURRENT_NOTE, currentNoteBody = "今天下午 3 点...")` 被调用，system prompt 包含该笔记完整 body
  - AI 回答内容引用到 "Q2 路线图" 或 "性能、可观测性、移动端" 等笔记关键词（证明 context 起作用）
  - Settings 中切换 toggle 可以改 contextMode，但默认跟笔记走
- **Why**：P7 的核心 UX 卖点——"问 AI 这条笔记"比"复制粘贴到 ChatGPT" 省 5 秒

## 场景 47 · 上下文 = 全部笔记 **P1**

- **Given**：仓库有 20–30 条最近 2 周的笔记，分散在多个日期
- **When**：
  1. AI screen 打开，contextMode toggle 切到 `全部笔记`
  2. 输入 `我这周最关心的三件事是什么？`，发送
- **Then**：
  - `AiContextBuilder.buildSystemPrompt(ALL_NOTES, allNoteBodies = [...])` 被调用，system prompt 按时间倒序拼接 recent 笔记 body
  - AI 回答里出现本周笔记的具体内容/关键词，证明 AI 读到了全部笔记
  - system prompt 字符数 ≤ `charBudget=15000`（通过 MemoryDumpLogger 验证或 logcat 打印长度）
- **Why**：让 AI 当"第二大脑"——用户不想手动翻笔记，想直接问结论

## 场景 48 · 长上下文超预算截断 **P2**

- **Given**：仓库有 500+ 条笔记（或构造人造极限：ALL_NOTES 下 body 总字符 > 30000）
- **When**：ALL_NOTES 模式下发送任意问题
- **Then**：
  - `AiContextBuilder` 按输入顺序尽可能多塞，第一个会超出预算的 body 起之后全部丢弃（不切片 splicing）
  - 最终 system prompt 字符数 ≤ `charBudget=15000`（留出 wrapper 余量，实际 ≤ 15000 * 1.2 为上限容忍）
  - AI 仍能回答，但回答基于前 N 条笔记（丢掉了 tail）
  - logcat 理想情况下有一条 warn 日志：`context truncated: 280/500 notes fit in 15000 chars`（Agent A 实现若加则测，不加也不 block）
- **Why**：单轮 context 有 token 上限（大多数 provider 4–32k tokens）；粗暴全塞会 413 或 provider 拒绝。必须有预算护栏，且护栏的行为是 deterministic 的（按输入顺序丢 tail）
- **⚠️ 截断方向澄清（P7.0.1 fix #75）**：
  - **CURRENT_NOTE 模式**：body 从 **尾部** 截断，开头保留（front matter / 标题通常在头部，保留前文有助模型理解笔记主旨）；截断点处加 `...(truncated)` 标记
  - **ALL_NOTES 模式**：按**输入列表顺序**保留前 N 条完整笔记（第一条塞不下就整条丢，不切片）；最后一条能塞下的截断也走头保尾丢规则
  - QA 需构造 "问笔记开头内容" 的 case，**不要**构造 "问笔记末段" —— 末段在超长时会被丢

## 场景 49 · API key 错误 (401) **P0**

- **Given**：Settings 里 `aiApiKey = sk-wrong-key-xxxx`（语法看着对，但 provider 会拒）；其他字段正常
- **When**：AI screen 输入任意问题，发送
- **Then**：
  - 500–2000 ms 内 Snackbar/错误条显示 `API key 无效 · 未授权（401）`（或等价中文，来自 `ErrorCode.UNAUTHORIZED` 的本地化）
  - transcript 里用户那条 message 仍在（可以点 retry），但没有 assistant 回复
  - `state.error != null`；点 Snackbar 的 `关闭` 或再次发送后 `clearError()`
  - `state.isSending` 回到 false，输入框可再用
  - logcat 有 `POST /chat/completions 401` 的一行
  - **不**自动连续重试（避免账号被封、quota 被烧）
- **Why**：用户换/过期 API key 是最常见的故障路径。错误信息必须 actionable（告诉用户是 key 的问题，不是网络问题、不是"未知错误"）

## 场景 50 · 网络错误重试 **P1**

- **Given**：Settings 配置齐全；设备开飞行模式（`adb shell svc wifi disable; svc data disable`）
- **When**：
  1. 离线状态发送 `你好`
  2. Snackbar 显示 `网络错误 · 请检查连接`（`ErrorCode.NETWORK`）
  3. transcript 留下用户那条 message + error 状态
  4. 恢复联网（`adb shell svc wifi enable`），等 5 秒连上
  5. 在输入框重新输入 `你好` 再点发送（或者点 transcript 上用户消息旁的 `重试` 按钮，如有）
- **Then**：
  - 第一次：state.error 为 NETWORK，isSending=false，无 assistant 回复
  - 第二次：success，assistant 回复落地，error 被清
  - 离线期间不会有无限重试或静默积压（无 WorkManager 挂起的 AI 请求）
- **Why**：AI 是 online-only 的功能（与 memo 的 offline-first 合约不同），但断网时要优雅降级——不崩、提示清晰、在线后手动重试即可

---

# P7.0.1 扩展（review fix wave）

> 3 条补充场景 + deferred gap 覆盖。发布随 v0.11.0-p7。

## 场景 51 · 多轮对话累积上下文（issue #68） **P0**

- **Given**：Settings 已配 AI（API key 有效），进入 AI 助手页
- **When**：
  1. 输入 `记住数字 42`，发送 → 收到 assistant 回复
  2. 输入 `刚才那个数字是多少`，发送
- **Then**：
  - 第 2 次请求的 body 里 messages 数组应包含：第 1 轮 user + 第 1 轮 assistant + 第 2 轮 user（顺序正确）
  - 第 2 轮 assistant 回复应引用 "42"
  - `vm.state.messages.size == 4`（2 user + 2 assistant）
- **Why**：单轮 chat 实现最容易被下一次重构打坏；多轮是 P7 的核心卖点，必须有 regression 护栏。同步 `AiChatViewModelTest.multi-turn...` 单测

## 场景 52 · 429 rate-limit 降级 （issue #69） **P1**

- **Given**：provider 当前在速率限制中（OpenAI 免费层 3 RPM、OpenRouter 突发额度超、DeepSeek 配额满）
- **When**：发送消息，provider 返 `HTTP 429 Too Many Requests`
- **Then**：
  - 当前行为：`AiClient` 的 5xx 分支**不**匹配 429；走默认 `UNKNOWN` 分支
  - Snackbar 显示 `AI 请求失败（HTTP 429）` 或类似（P7.0.1 fix #63 后不回显 body）
  - 无自动重试、无 quota 烧爆
- **Why**：429 是 AI provider 最常见的瞬时故障，但当前实现未单独映射。本场景 lock 住现行行为，**若用户反馈重复**，P7.1 再补 `429 → RATE_LIMITED` + 指数退避

## 场景 53 · SingleNote 长按 "问 AI" 深链（issue #71） **P1**

- **Given**：笔记列表里有一条 SingleNote `notes/2026-04-23-1500-foo.md` body=`这是一个测试笔记`
- **When**：
  1. 长按该卡片（> 500ms）
  2. DropdownMenu 弹出，含 `问 AI` 项（`Psychology` 图标）
  3. 点 `问 AI`
- **Then**：
  - 跳转 `ai_chat?noteUid=<uid>`
  - AiChatScreen 顶部 FilterChip 默认选中 `当前笔记`（`hasCurrentNote == true`）
  - 对话发起时 system prompt 含 "这是一个测试笔记" 的内容
  - 返回笔记列表仍保留原状态
- **Why**：P7 UI 最初只有 tab 级入口（忽略 noteUid），per-note 入口是 `factoryFor(noteUid)` / CURRENT_NOTE chip 的**唯一**使用路径。P7.0.1 补齐后必须有 BDD 回归守卫

---

# P8 扩展（Widget 重做 + 自动刷新）

> 37 条新增场景（#54–#90），覆盖 P8 的 widget 自动刷新合约、widget 新列表形态（最近 20 条）、widget 交互（深链 / 刷新按钮），以及额外 10 条主动发现的边界（权限、断网、生命周期、主题、字体、长文本、多实例、pin 显示等）。版本 `v0.12.0-p8`。
>
> 环境前提：APK 为 `v0.12.0-p8`（versionCode=13），emulator 同前。桌面已添加 MemoWidget 2×2（或 4×4，视场景要求）与 TodayWidget 4×2。

## Widget 自动刷新（#54–#63）

### 场景 54 · 保存新笔记后 widget 立即显示新条目 **P0**

- **Given**：桌面已添加 MemoWidget；widget 当前显示 3 条历史笔记；PAT 已配置；app 在前台
- **When**：用户在 app 中创建新笔记 `晚餐吃寿司` 并点击保存
- **Then**：
  - widget 在 1 秒内自动刷新（无需手动长按或等 Glance 自身 tick）
  - widget 顶部出现新条目 `晚餐吃寿司`
  - 历史笔记下移一位，最旧的一条（若超出可视区）被挤出屏幕外但仍在列表中
  - 不需要用户切回桌面后再手动刷新
- **Why**：P8 的核心 UX 卖点——"写 app 即写 widget"。写路径末尾调 `WidgetRefresher.refreshAll(context)` 必须触发，否则用户会觉得 widget "卡住了"

### 场景 55 · 删除笔记后 widget 立即移除该条目 **P0**

- **Given**：桌面 MemoWidget 当前显示 5 条笔记，其中第 2 条标题为 `要删掉的便签`
- **When**：用户在 app 笔记列表长按 `要删掉的便签` → 菜单选 `删除` → 确认
- **Then**：
  - widget 在 1 秒内刷新
  - 该条目从 widget 列表消失
  - 其他 4 条仍按原顺序显示，第 3 条（原来第 3）上移到第 2 位
  - 若笔记列表现在只剩 4 条，widget 可视区最后一行变空白（或下一条历史笔记补位，取决于总数）
- **Why**：`SingleNoteRepository.delete` 成功返回前调 refresh；若漏调，用户会在 widget 上继续看到已删除的笔记，下次点击触发 NOT_FOUND

### 场景 56 · 置顶笔记后 widget 顺序调整 **P0**

- **Given**：MemoWidget 按时间倒序显示 5 条笔记 A、B、C、D、E，全部未置顶
- **When**：用户在 app 列表把第 3 条 C 置顶（togglePin）
- **Then**：
  - widget 在 1 秒内刷新
  - 新顺序为 C（置顶）、A、B、D、E
  - 若 widget 支持 pin 图标（见 #90），C 行标题前显示 📌
  - 再次取消 pin，顺序回到 A、B、C、D、E
- **Why**：`togglePin` / `togglePinByPath` 成功后调 refresh；`observeRecent` 的排序规则是 `isPinned DESC, date DESC, time DESC`

### 场景 57 · PullWorker 拉取到新笔记后 widget 自动刷新 **P0**

- **Given**：设备 A 已配置 PAT；桌面有 MemoWidget；当前显示 3 条笔记；设备 B 在同一仓库推了一条新笔记 `远程新笔记`（远端有，本地还没拉）
- **When**：周期 `PullWorker.doWork()` 触发（或用户手动点"立即同步"），成功拉下远端新笔记
- **Then**：
  - widget 在 `PullWorker` 返回 `Result.success()` 后的 1–2 秒内刷新
  - widget 顶部出现 `远程新笔记`
  - 不需要用户打开 app 手动触发
- **Why**：hook 点 #7——PullWorker 末尾调 refresh。若漏调，用户只有在打开 app 后 widget 才更新，失去"widget 是最新鲜实时视图"的承诺

### 场景 58 · PushWorker 同步成功后 widget 反映同步状态 **P1**

- **Given**：离线状态下连续写 3 条笔记，Room 里 `dirty=true`；widget 当前显示这 3 条但（若有同步角标）标示为"未同步"；恢复联网
- **When**：`PushWorker.doWork()` 把 3 条 PUT 到远端，全部成功返回 `Result.success()`
- **Then**：
  - widget 在 push 完成后的 1–2 秒内刷新
  - 3 条笔记仍显示，但（若有同步角标）现在标示为"已同步"
  - `dirty` 标识清零
- **Why**：hook 点 #8——PushWorker 末尾调 refresh。即使 widget 的内容没变，同步状态变了也要重绘

### 场景 59 · 切换 PAT 配置从未配置 → 已配置后 widget 从 prompt → 笔记列表 **P0**

- **Given**：全新安装，PAT 未配置；桌面添加 MemoWidget；widget 显示 `先打开 app 配置 GitHub PAT`（或类似 prompt）；用户在 Room 里本地有 2 条 dirty 笔记
- **When**：用户进 Settings 填 PAT 并保存
- **Then**：
  - widget 在 1–2 秒内刷新
  - 从 prompt 态切换到笔记列表态
  - 显示 2 条本地 dirty 笔记
  - 随后 PushWorker 自动推送，同步成功后再刷一次（覆盖 #58）
- **Why**：hook 点 #9——`SettingsStore.updatePat` 等必须触发 refresh；`isConfigured` 变化是 widget 态切换的关键边界

### 场景 60 · AI Chat 不触发 widget 刷新 **P1**

- **Given**：桌面 MemoWidget；PAT / AI 都已配好；widget 当前显示 3 条笔记；用户打开 AI 助手 tab 进行多轮对话
- **When**：用户发送 5 条 message，AI 返回 5 条 reply（AI Chat 不产生新笔记，也不修改笔记）
- **Then**：
  - widget 内容保持不变（3 条笔记）
  - widget 不会因为 AI 交互而闪烁 / 刷新
  - logcat 里没有 `MemoWidget.updateAll` 被调用的痕迹
- **Why**：边界场景——只有笔记 CRUD（create/update/delete/pin）和 sync worker 才触发 refresh。AI Chat 不改变笔记数据，不应引发 widget 重绘。验证 hook 点清单的"只在该调的地方调"

### 场景 61 · WidgetRefresher 异常不阻塞写路径 **P0**

- **Given**：特殊环境：widget 尚未添加到桌面（`GlanceAppWidgetManager.getGlanceIds()` 返回空），或 `updateAll` 抛出 `GlanceId not found` 等 recoverable 异常
- **When**：用户在 app 中保存一条新笔记（触发 `appendToday` / `createSingleNote`）
- **Then**：
  - 笔记保存成功（UI 显示 SaveState.Success）
  - Room 里有新行
  - `WidgetRefresher.refreshAll(context)` 内部 `runCatching` 吃掉异常，不传播
  - 无 FATAL、无 ANR、UI 不崩
  - logcat 可能有 warn 级日志（`widget refresh skipped: no ids`），但不影响用户动作
- **Why**：`WidgetRefresher` 用 `SupervisorJob` + `runCatching` 双重保护——写路径绝不能因为 widget 刷新问题而失败。这条是架构合约的护栏

### 场景 62 · App 冷启动后 widget 反映最新状态 **P1**

- **Given**：app 被 `adb shell am force-stop dev.aria.memo` 杀掉；桌面 widget 当前显示 3 条；在 app 被杀期间，另一台设备推了一条新笔记到远端（但本地 widget 没刷新）
- **When**：用户点桌面 app 图标冷启动；`MemoApplication.onCreate` 或 `AppBootObserver` 触发一次可选 refresh
- **Then**：
  - app 冷启动后 1–2 秒内 widget 刷新一次（保底机制）
  - 若此时 PullWorker 也拉下了新笔记，widget 再刷新一次反映远端（与 #57 叠加）
  - 用户观感：打开 app 的瞬间 widget 就新了
- **Why**：保底路径——防止某些边界（比如上次 refresh 时系统 throttle 了）导致 widget 内容滞后。冷启动补刷是一种"乐观同步"

### 场景 63 · 多次快速写入 widget 不重复刷新导致闪烁 **P1**

- **Given**：桌面 MemoWidget；用户快速连写 5 条笔记（每条间隔 < 300ms），每条保存都触发 `WidgetRefresher.refreshAll`
- **When**：连续 5 次保存完成
- **Then**：
  - widget 最终内容包含全部 5 条新笔记
  - widget 视觉上不出现明显闪烁（可接受：有一次 shimmer，不可接受：连闪 5 次白屏）
  - 若实现有 debounce / throttle（如 200ms 合并），logcat 显示 `updateAll` 实际只调用 2–3 次
  - 若无 debounce，至少 `SupervisorJob` 保证不崩；实际调用 5 次也可接受（Glance 内部有 diff）
- **Why**：UX polish。频繁的 Glance updateAll 会触发系统级 throttle（Android O+ widget 每小时最多 X 次），用力过猛反而被系统打回。Agent 2 可选实现 debounce；未实现时本场景锁定"至少不崩、最终内容正确"

## Widget 列表展示（#64–#73）

### 场景 64 · MemoWidget 显示最近 20 条笔记 **P0**

- **Given**：仓库有 50 条笔记；PAT 已配好；桌面 MemoWidget 2×2（默认尺寸）
- **When**：widget 加载完成
- **Then**：
  - widget 内部数据源拉取的笔记数量 ≤ 20（`observeRecent(limit=20)` / `recentEntries(limit=20)`）
  - UI 上因为 2×2 空间有限，可能只显示最前面 3–4 行；但 `LazyColumn` 内部持有 20 条数据
  - resize 到 4×4 后可看到更多行（见 #65）
  - 不再是 P7 的 "3 条今日快照"，而是 "最近 20 条 rolling feed"
- **Why**：P8 的新形态——widget 从"今日快照"升级成"最近 20 条滚动列表"。`limit=20` 是源码常量

### 场景 65 · Widget resize 到 4×4 后可看更多笔记 **P1**

- **Given**：MemoWidget 当前 2×2，显示最前 3 条笔记；仓库有 20 条笔记可显示
- **When**：用户长按 widget → 拖拽四角 resize 到 4×4
- **Then**：
  - widget 可视区扩大，显示约 10–15 行笔记（具体数量取决于字体大小）
  - LazyColumn 正确布局，不溢出、不裁切标题
  - 刷新按钮和 "+" 按钮仍在顶部条栏可见
  - 再 resize 回 2×2，回到显示前 3 条，数据不丢
- **Why**：Glance 的 `SizeMode.Exact` 支持 resize，widget_info.xml 要声明可调尺寸范围。验证 P8 的"可滚动更大列表"卖点

### 场景 66 · Widget 列表可滚动 **P0**

- **Given**：MemoWidget 4×4；仓库有 20 条笔记；widget 可视区显示前 10 条
- **When**：用户在 widget 内往上滑动列表
- **Then**：
  - LazyColumn 平滑滚动，显示第 11–20 条
  - 滚到底仍能继续滚回顶部
  - 滚动期间 widget 本身不崩、不重绘整个 RemoteViews
  - 点击任意条目仍可正常触发深链（见 #74）
- **Why**：P7 的 widget 是固定 3 行，不支持滚动；P8 改成 LazyColumn 天然支持。验证 Glance LazyColumn 在 AppWidget 上下文里确实可滚

### 场景 67 · SingleNote 笔记优先于 legacy 日期条目 **P1**

- **Given**：仓库既有老 `2026-04-20.md`（legacy day-file，内有 2 条 `## HH:MM` 条目），也有 5 条新 `notes/2026-04-XX-xxxx.md`（SingleNote）
- **When**：MemoWidget 加载，数据源把两种笔记合并到统一列表
- **Then**：
  - widget 列表按时间倒序混合显示 SingleNote 和 legacy entry
  - SingleNote 条目左侧（若有 accent bar）显示 tertiary 蓝紫色
  - Legacy entry 左侧显示 primary 绿色
  - 两种可一眼区分（与 #35 保持一致）
  - 点击 SingleNote 走 deep-link by uid（#78），点击 legacy entry 走 EditActivity no extras（#79）
- **Why**：widget 是 NoteList 的精简投影，排序 / 视觉区分规则要一致

### 场景 68 · TodayWidget 显示今天的 events + 今天的 memos **P1**

- **Given**：今天（2026-04-24）有 2 个事件（上午 10:00 开会、下午 3:00 咖啡）和 3 条备忘；仓库还有昨天和前天的备忘（不应出现）
- **When**：TodayWidget 4×2 加载
- **Then**：
  - 上半部分显示今天的 2 个事件（含时间）
  - 下半部分显示今天的 3 条备忘（limit=20 但今天只有 3）
  - 不显示昨天/前天的备忘
  - 语义是"今天"（TodayWidget 如其名）
  - 底部有刷新按钮 🔄（和 MemoWidget 一致，见 #76）
- **Why**：TodayWidget 保留"今天"范畴，与 MemoWidget 的"最近 20 条"不冲突；内部 limit 从 6 提到 20（LazyColumn 自己滚）

### 场景 69 · 跨日切换时 TodayWidget 自动更新"今天"的定义 **P1**

- **Given**：TodayWidget 显示 2026-04-23 的 3 条笔记；系统时间在 2026-04-23 23:58
- **When**：时间跨过 00:00 变成 2026-04-24；系统发送 `ACTION_DATE_CHANGED` 广播或下次 Glance 刷新
- **Then**：
  - TodayWidget 自动重新计算"今天"的边界（`LocalDate.now()`）
  - 显示 2026-04-24 的备忘/事件（若尚无，显示空态）
  - 不再显示昨天 2026-04-23 的 3 条笔记
  - 日期标题（若有）变成 `2026-04-24`
- **Why**：与 #2 同构，P8 重构时不能把这层跨日边界弄丢

### 场景 70 · 空态：用户没有笔记 → widget 显示占位提示 **P1**

- **Given**：全新安装；PAT 已配好（但仓库是空的）；Room 里无笔记；桌面刚拖入 MemoWidget
- **When**：widget 加载完成
- **Then**：
  - widget 可视区显示占位文字，如 `还没有备忘，点 + 开始` / `还没有笔记，点右下角「+」`
  - 占位文字居中，不显示空白 LazyColumn
  - 刷新按钮 🔄 和 "+" 按钮仍正常工作
  - 点击 "+" 走 #75 路径打开新建
- **Why**：空态不能是空白屏（用户会以为 widget 坏了）；UX 一致性与 #34 MemoEmptyState 呼应

### 场景 71 · NOT_CONFIGURED 态：widget 显示"先打开 app 配置 GitHub PAT" **P0**

- **Given**：全新安装；PAT 未配置；Room 为空；桌面添加 MemoWidget
- **When**：widget 加载完成
- **Then**：
  - widget 显示 prompt 文字，如 `先打开 app 配置 GitHub PAT`
  - 不显示笔记列表（即使 Room 里有 dirty 笔记也可考虑先提示配置）
  - 点击 widget 任意位置跳 MainActivity（见 #77）
  - 用户配好 PAT 后 widget 自动切到笔记列表态（见 #59）
- **Why**：首装桌面拖 widget 但没配 PAT 是常见新手路径，widget 不能一直空白或崩

### 场景 72 · Widget 每行显示 MM/DD HH:mm 前缀 **P2**

- **Given**：MemoWidget 显示 5 条笔记，包含 SingleNote `2026-04-24 15:30` body=`下午茶` 和 legacy entry `2026-04-23 10:00` body=`早会`
- **When**：widget 加载
- **Then**：
  - 每行格式为 `MM/DD HH:mm  内容标题`，如 `04/24 15:30  下午茶` / `04/23 10:00  早会`
  - 日期在前、内容在后，视觉对齐（等宽 prefix）
  - SingleNote 的 `createdAt` 时间戳和 legacy 的 `## HH:MM` 都能正确转成前缀
- **Why**：P8 widget 不再绑定"今天"，用户需要时间 prefix 定位条目；MM/DD 比完整年月日更紧凑

### 场景 73 · Widget 置顶笔记标题前有 📌 图标 **P2**

- **Given**：MemoWidget 显示 5 条笔记；其中第 1 条 `重要备忘` 已置顶（isPinned=true）
- **When**：widget 加载
- **Then**：
  - 第 1 条的标题前（或前缀位置）显示 📌 或等效视觉标记
  - 其他 4 条无该标记
  - 取消 pin 后刷新，📌 消失，条目回到按时间排序的正常位置（见 #56）
- **Why**：与 #9 front-matter pinned 一致；widget 上的 pin 视觉提示让用户"一眼看见重要备忘"

## Widget 交互（#74–#80）

### 场景 74 · 点击 widget 条目打开对应笔记 Editor **P0**

- **Given**：MemoWidget 显示 5 条笔记；其中第 3 条 `周会记录` uid=`abc123`
- **When**：用户点击 `周会记录` 那一行
- **Then**：
  - 跳转 EditActivity，intent extra `EXTRA_NOTE_UID=abc123`
  - 编辑器 body 预填 `周会记录` 的正文内容
  - TopAppBar 显示"写点什么"或笔记标题
  - 保存时走 `updateSingleNote(uid, newBody)`，不 create 新
- **Why**：widget 深链是核心交互。与 #36 / #37 / #53 一致

### 场景 75 · 点击 widget "+ New" 按钮打开新建 Editor **P0**

- **Given**：MemoWidget；顶栏右侧有 "+" SquareIconButton
- **When**：用户点击 "+"
- **Then**：
  - 跳转 EditActivity
  - intent 无 `EXTRA_NOTE_UID`（走 create 路径）
  - 编辑器 body 空白
  - 保存时走 `createSingleNote(body)` 或 `appendToday`（取决于实现）
  - 保存成功后 widget 自动刷新显示新条目（见 #54）
- **Why**：新建入口必须从 widget 直达——这是 widget 区别于 widget 纯展示器的关键

### 场景 76 · 点击 widget 🔄 刷新按钮触发 refreshAll **P0**

- **Given**：MemoWidget；顶栏右侧有 🔄 SquareIconButton（P8 新增）
- **When**：用户点击 🔄
- **Then**：
  - 触发一个 BroadcastReceiver，调 `MemoWidget().updateAll(context)`
  - widget 在 1 秒内视觉上有刷新反馈（可能短暂显示 loading 态）
  - 数据从 Repository 重新拉一次（即使没新内容，至少跑一次 `observeRecent`）
  - 不跳 app，不改状态，纯 refresh
  - 可触发 `WidgetRefresher.refreshAll` 进入 TodayWidget 也刷新
- **Why**：P8 新增的手动刷新按钮——兜底 happy path 的"如果自动刷新漏了，用户点这里"

### 场景 77 · 未配置态点击 widget 任意位置跳 MainActivity **P1**

- **Given**：PAT 未配置；widget 显示 `先打开 app 配置 GitHub PAT` prompt 态
- **When**：用户点击 widget 正文区（或任意位置）
- **Then**：
  - 跳转 MainActivity（不是 EditActivity，因为没法编辑）
  - MainActivity 可引导用户去 Settings（或自动跳 Settings tab）
  - 不崩、不走 EditActivity 的空路径
- **Why**：未配置态没有编辑意义，让用户先填 PAT

### 场景 78 · Widget 点击条目 deep-link by uid（SingleNote） **P0**

- **Given**：widget 显示一条 SingleNote `uid=note-xyz`
- **When**：用户点击该条目
- **Then**：
  - 跳转 EditActivity，extra `EXTRA_NOTE_UID=note-xyz`
  - 编辑器加载该 note 的 body
  - 保存走 update 路径（见 #74）
  - 深链在卸载重装后若 uid 不存在，走 #39 僵尸 uid 路径（打开空 editor，保存返 NOT_FOUND）
- **Why**：SingleNote 的 uid deep-link 是 P6.1+ 的 feature；#37 / #53 已覆盖入口，此处专门覆盖 widget 路径

### 场景 79 · Widget legacy 条目点击打开 EditActivity with no extras **P1**

- **Given**：widget 显示一条 legacy entry（来源 `2026-04-23.md` 的 `## 10:00` 段），该条目没有 uid（legacy day-file 不是 SingleNote）
- **When**：用户点击该 legacy entry
- **Then**：
  - 跳转 EditActivity，intent 无 `EXTRA_NOTE_UID`
  - 编辑器打开时 prime 到今天或该日期的 day-file（具体取决于实现——当前是跳到"今天"）
  - 保存时走 `appendToday` 路径，追加一条新 entry 到今天的 day-file
  - 不尝试解析 legacy entry 的 `## HH:MM` 段做精细编辑
- **Why**：legacy day-file 的 entry 没有 uid，widget 点击时不能走 uid deep-link；这是一个"降级到 append"的合约

### 场景 80 · Widget 长按（预留） **P2**

- **Given**：MemoWidget 显示任意条目
- **When**：用户长按 widget 条目（> 500ms）
- **Then**：
  - 当前行为：长按不触发 app 内菜单（Glance 长按默认不拦截，交给系统 widget 上下文菜单：重新配置 / 移除）
  - 系统弹出"移除 widget" / "调整大小"等选项
  - app 层面无反应
  - 未来 P8.x 可考虑拦截长按弹自定义菜单（删除 / 问 AI 等），但当前版本锁定"交给系统"
- **Why**：预留场景。Glance 的 action 只处理 click 不处理 long-press；若未来实现要同步改此场景

## 额外主动发现（#81–#90）

### 场景 81 · 权限被拒绝后 widget 行为 **P1**

- **Given**：Android 13+，通知权限未授予（用户拒了 `POST_NOTIFICATIONS`）；桌面已添加 MemoWidget
- **When**：widget 加载、用户保存笔记、PushWorker 运行
- **Then**：
  - widget 本身渲染不依赖通知权限，正常显示笔记列表
  - `WidgetRefresher.refreshAll` 照常工作（与通知无关）
  - 若用户从 widget 点 "+" 进 EditActivity，保存后不弹通知但笔记保存成功
  - `QuickAddNotificationManager` 若在此刻 `enable()`，应优雅失败（Log warn，不崩）
  - logcat 可能有 `Notification permission not granted` warn，但无 FATAL
- **Why**：widget 与通知是解耦的两个 surface；权限缺失不应影响 widget；反之亦然

### 场景 82 · 网络断开时 widget 行为 **P1**

- **Given**：开飞行模式（`adb shell svc wifi disable; svc data disable`）；widget 显示 5 条本地笔记（Room 已 cache）
- **When**：用户点 widget 🔄 刷新按钮
- **Then**：
  - `MemoWidget.updateAll` 仍执行，从 Room 本地读最新 5 条
  - widget 内容不变（离线无新数据）
  - 不提示"网络错误"（widget 是 offline-first 的投影）
  - 若有 syncBanner 角标，可能显示"离线"提示（取决于实现）
  - 恢复网络后 PullWorker 拉到新笔记会自动再刷一次（见 #57）
- **Why**：widget 是 Room 的视图，不直连网络；断网时应完全可用（offline-first 合约在 widget 层也成立）

### 场景 83 · Widget 添加到桌面的首次初始化 **P0**

- **Given**：app 已装、已配 PAT、Room 有 10 条笔记；桌面还没有 MemoWidget
- **When**：用户长按桌面空白 → `小部件` → 选择 `Memo widget` → 拖到桌面
- **Then**：
  - widget 在 1–3 秒内完成首次渲染，显示前 10 条
  - 不出现"加载中"无限等待
  - 不显示 `Widget 加载失败` 或错误文本
  - 首次渲染后若 Room 有新数据，widget 会在下次 `updateAll` 时刷新
  - 添加时系统可能会调用 `AppWidgetProvider.onEnabled` / `onUpdate`，这些生命周期要正确响应
- **Why**：首次添加是用户对 widget 的第一印象；加载失败会让用户觉得"这 app 的 widget 是坏的"直接卸载

### 场景 84 · Widget 从桌面移除后行为（清理） **P2**

- **Given**：桌面有 MemoWidget；app 运行中
- **When**：用户长按 widget → 拖到"移除"区 → 松手
- **Then**：
  - widget 从桌面消失
  - 下次用户在 app 中保存笔记，`WidgetRefresher.refreshAll` 调用时 `GlanceAppWidgetManager.getGlanceIds()` 返回空列表
  - `updateAll` 内部 no-op 或吃掉 `GlanceId not found` 异常（见 #61）
  - 不影响 app 正常工作
  - 用户重新添加 widget 回桌面，`onEnabled` / `onUpdate` 重新触发，显示最新笔记
- **Why**：widget 的生命周期管理。Glance 的 runCatching 保护在这里是关键

### 场景 85 · 多个 widget 实例并存 **P1**

- **Given**：桌面同时拖了 3 个 MemoWidget 实例（主屏 1 个 2×2、副屏 1 个 4×4、另一屏 1 个 2×4）
- **When**：用户在 app 中保存一条新笔记
- **Then**：
  - `WidgetRefresher.refreshAll` 触发 `MemoWidget().updateAll(context)`
  - Glance 内部遍历所有 `GlanceId`，对 3 个实例各调一次 render
  - 3 个 widget 同步刷新，都显示新笔记
  - 每个实例根据自己的 size 独立布局（2×2 显示 3 行、4×4 显示 10+ 行）
  - 互不干扰、无 race condition
- **Why**：用户可能在不同屏 / 不同位置放多个 widget；刷新必须覆盖所有实例

### 场景 86 · 用户切换深色模式后 widget 颜色切换 **P1**

- **Given**：系统浅色模式；桌面 MemoWidget 显示笔记列表（浅色背景 + 深色文字）
- **When**：用户去系统设置切到深色模式（`adb shell "cmd uimode night yes"`）
- **Then**：
  - widget 在 1–3 秒内重新渲染（Glance 响应 `ACTION_CONFIGURATION_CHANGED`）
  - 背景变深色、文字变浅色
  - 刷新按钮、"+" 按钮的图标也切换到深色模式版本
  - 对比度仍满足可读性（WCAG AA）
  - 切回浅色模式也能正确切换
- **Why**：Material 3 的 theming 在 widget 层也要生效；不能只 app 切了 widget 还是浅色

### 场景 87 · 用户切换系统字体大小后 widget 文本适配 **P1**

- **Given**：widget 显示笔记列表；系统字体默认大小
- **When**：用户去系统设置切字体大小到"最大"（`adb shell settings put system font_scale 1.5`）
- **Then**：
  - widget 在下次渲染时文字变大
  - LazyColumn 高度自适应，每行可能只能显示 1 条标题（更少行数）
  - 文字不溢出、不裁切（用 ellipsis 截断长标题）
  - "+" 和 🔄 按钮尺寸跟随 scale 一起变大（若使用 dp 单位而非 sp，按钮可能不变但文字变大，两种都可接受）
  - 切回默认字体大小也正确恢复
- **Why**：a11y——视力不好的用户会开大字体；widget 文字必须 respect system font scale

### 场景 88 · Widget 长文本截断行为 **P1**

- **Given**：widget 显示一条笔记 body 长达 500 字（`"# 长标题\n\n正文 1\n正文 2..."`）
- **When**：widget 渲染这一行
- **Then**：
  - 列表行显示前 N 字符（如 40–60 字符），超出部分用 `...` 或 ellipsis 截断
  - 单行不跨 2 行（保持每条占固定高度）
  - 完整内容可通过点击 deep-link 进 EditActivity 查看
  - 多行 markdown（换行符 `\n`）被压缩成单行（或取第一行作为 title）
  - emoji / CJK 字符的截断不会破坏字符边界（不截半个 emoji）
- **Why**：笔记长度无上限，widget 行高有限；截断策略必须 graceful

### 场景 89 · Widget 中文 / emoji / 多行文本渲染 **P1**

- **Given**：widget 显示 3 条笔记，body 分别是：
  1. 纯中文 `今天下午要开会讨论 Q2 目标`
  2. 纯 emoji `🎉🎊🥳`
  3. 多行含 emoji `# 晚餐 🍕\n- [ ] 买披萨饼底\n- [ ] 买奶酪`
- **When**：widget 渲染
- **Then**：
  - 中文字符正确显示，不出现乱码、豆腐块
  - emoji 用系统字体正确渲染（`🎉🎊🥳` 显示为 emoji 而不是 □□□）
  - 多行 markdown 压缩成单行（或只显示第一行 `# 晚餐 🍕`，see #88）
  - 字体 fallback 链正确（CJK → 汉字字体，emoji → NotoColorEmoji）
  - 混合语言（中英文 + emoji）的行宽计算正确，不溢出
- **Why**：i18n + emoji 是中文用户常用场景；widget 必须和 app 一样支持；否则用户会看到"乱码 widget"

### 场景 90 · SingleNote pin 状态的 widget 显示 **P1**

- **Given**：仓库 5 条 SingleNote，其中 2 条已 pin（`isPinned=true`，front matter 含 `pinned: true`）
- **When**：widget 加载
- **Then**：
  - 2 条 pin 的排在最上面（排序：`isPinned DESC, date DESC, time DESC`）
  - 标题前显示 📌 视觉标记（见 #73）
  - 用户在 app 中 unpin 一条后，widget 在 1–2 秒内刷新
  - 刷新后该条目回落到按时间排序的位置，📌 消失
  - re-pin 后重新回到顶部，📌 重新出现
  - pin 的 front matter `pinned: true` 正确从 Room `isPinned` 列投影到 widget 显示
- **Why**：pin 是 P4.3 feature，P6.1 延伸到 SingleNote，P8 widget 新列表必须尊重这个状态；#9 / #18 / #56 / #73 的合约在 widget 层的最终落地

---

**文件更新**：2026-04-24（P8 BDD 扩展，追加 37 条 #54–#90）
**维护责任人**：每次新 feature 落地后，对应在这里追加一条 BDD 场景。
**总计**：90 条（原 53 + P8 新增 37）
