<!--
BDD_SCENARIOS.md — memo-widget 的 Behaviour-Driven Development 场景清单（P6 扩展版）。
受众：QA / 接手 AI agent / 回归测试负责人。
目的：把 happy path 之外的真实用户场景、历史 bug、边界条件收敛成一份可照着跑的清单。
每条场景都标注了 Priority（P0/P1/P2），以及 Why —— 说明哪个 bug 曾经在这里失手、或者哪个 feature 的关键路径。

配套 P0 场景的真机回归结果见 `/tmp/bdd_p6/` 下的截图与 session 总结。
-->

# BDD Scenarios — Memo Widget (P6 扩展)

> 28 个场景。P0=必跑回归（每次 release 前），P1=推荐跑（触及关键 feature 时跑），P2=探索式/已知限制。
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

**文件位置**：`/Users/aria-score-00/AndroidProjects/memo-widget/BDD_SCENARIOS.md`
**最近更新**：2026-04-22（P6 BDD 扩展）
**维护责任人**：每次新 feature 落地后，对应在这里追加一条 BDD 场景（规则：只要 touch 了一个 `.kt`/`.xml`，BDD 就要新增或修订）。
