# P8 发版前 Smoke Test 清单

**执行方式**：人工手动，按顺序跑一遍；任一条勾不上就阻塞发版。
**设备建议**：至少一台中端真机（Android 10+）+ 一台平板 / 大屏模拟器。
**耗时预估**：熟练操作约 40-50 分钟。

---

## 1. Widget（桌面小组件）

- [ ] Given 桌面长按空白区域，When 从 widget 列表添加 Memo Widget，Then widget 出现在桌面且渲染不崩溃
- [ ] Given 桌面上已有 widget 且列表非空，When 点击某一条笔记条目,Then 正确跳转到 Editor 并加载该笔记内容
- [ ] Given 桌面上已有 widget,When 点击顶部「+」按钮，Then 打开 Editor 空白页、标题和正文都是空
- [ ] Given 桌面上已有 widget，When 点击「🔄」同步按钮，Then 3 秒内看到 Toast 提示（同步成功或失败文案），且 widget 内容刷新
- [ ] Given widget 已放置在桌面，When 长按并 resize 到 2×2，Then 条目数量减少但布局不错位、文字不截断乱码
- [ ] Given widget 已放置在桌面，When 长按并 resize 到 4×4，Then 显示更多条目、标题与预览文本正常换行
- [ ] Given 应用从未配置（首次安装，未进入 Settings），When 添加 widget 到桌面，Then 显示「未配置」占位视图 + 「去配置」入口按钮
- [ ] Given 应用已配置但笔记库为空（0 条笔记），When 查看 widget，Then 显示「空态」提示文案（例如「还没有笔记，点 + 新建」）
- [ ] Given widget 的「🔄」在网络断开时被点击，When 观察反馈，Then Toast 明确提示「网络不可用」而不是静默无反应
- [ ] Given 桌面已有 widget，When 杀掉 App 进程后再次点击 widget 条目，Then 仍能冷启动 Editor 并正确加载该笔记

## 2. Notes CRUD（笔记增删改查 + 置顶）

- [ ] Given 主界面笔记列表，When 点击「+ 新建」输入标题和正文后返回，Then 列表顶部出现新笔记、时间戳为当前时间
- [ ] Given 列表中存在一条笔记，When 点击进入并修改正文后保存，Then 列表中该条的预览文本同步更新
- [ ] Given 列表中存在一条笔记，When 长按或滑动触发删除并确认，Then 该条从列表消失，且重新进入 App 后仍已删除
- [ ] Given 列表中存在一条笔记，When 对其执行「置顶」操作，Then 该条移动到列表最顶部且显示置顶标识（图钉图标等）
- [ ] Given 列表顶部有置顶笔记，When 对其执行「取消置顶」，Then 该条回归按时间排序的正常位置、置顶标识消失
- [ ] Given 存在多条置顶笔记，When 查看列表，Then 置顶区按最近置顶时间排序、普通区按修改时间排序、两区之间视觉分隔清晰
- [ ] Given 新建笔记只填了标题、正文为空，When 保存并返回，Then 列表能正常显示、进入后标题正确、正文为空不崩
- [ ] Given 新建笔记只填了正文、标题为空，When 保存并返回，Then 列表显示兜底标题（例如正文首行或「无标题」）

## 3. 同步（Sync / Git 或云端）

- [ ] Given 应用首次配置 PAT + 仓库地址完成，When 触发首次 pull，Then 远端已有笔记全部拉到本地并显示在列表
- [ ] Given 本地有未同步的修改，When 手动触发 push，Then 同步状态变为「同步中」→「已同步」，远端仓库能看到最新提交
- [ ] Given 同一条笔记在本地和远端被不同设备改过（人为造冲突），When 触发同步，Then 出现冲突解决 UI 或明确的冲突提示，不会静默丢失数据
- [ ] Given 设备处于飞行模式，When 触发同步，Then 显示「网络错误」错误态而不是 crash，错误条带有「重试」按钮
- [ ] Given PAT 已过期或填错，When 触发同步，Then 显示「认证失败 / 401」错误态，引导用户去 Settings 重新配置
- [ ] Given 同步过程中切到后台又切回来，When 观察 UI，Then 同步状态恢复正常、不会卡在「同步中」死循环
- [ ] Given 远端仓库地址错误（404），When 触发同步，Then 显示明确的「仓库不存在」错误文案

## 4. Editor（编辑器）

- [ ] Given 在 Editor 中修改了内容但未保存，When 按物理返回键，Then 弹出「未保存，是否放弃更改？」确认对话框
- [ ] Given 未保存提示弹出，When 选择「放弃」，Then 退回列表页、改动丢弃、原内容保留
- [ ] Given 未保存提示弹出，When 选择「保存」，Then 改动写入、列表页同步更新、时间戳刷新
- [ ] Given 在 Editor 中完成编辑，When 点击顶部「保存」按钮，Then 短暂 Toast / 视觉反馈「已保存」，按钮变灰或页面返回
- [ ] Given Editor 打开一条笔记，When 点击删除按钮，Then 弹出「确认删除？」二次确认，不会一键删
- [ ] Given 删除确认弹窗，When 选择「取消」，Then 笔记仍然存在、编辑器停留原页
- [ ] Given 删除确认弹窗，When 选择「删除」，Then 返回列表页且该笔记已消失
- [ ] Given Editor 界面打开，When 使用系统截屏（电源+音量下），Then 截屏结果为黑屏（FLAG_SECURE 生效）
- [ ] Given Editor 界面打开，When 按 Recent Apps 任务切换键查看缩略图，Then 缩略图为黑屏或遮罩、内容不可见
- [ ] Given 输入超长文本（>5000 字），When 保存并重新打开，Then 内容完整、滚动流畅不卡顿

## 5. AI 问答

- [ ] Given 用户从未配置 AI（无 API Key），When 打开 AI 问答 Tab，Then 显示「未配置」引导页 + 「去配置」按钮、不会直接报错
- [ ] Given 在 Settings 中填入 API Key 和 endpoint，When 点击「测试连接」，Then 成功时显示「连接成功」、模型名回显
- [ ] Given API Key 故意填错，When 点击「测试连接」，Then 显示明确的「认证失败」文案、不会 crash
- [ ] Given AI 问答支持三种 context（全部笔记 / 当前笔记 / 无 context），When 切换到「全部笔记」，Then 提问能引用到跨多条笔记的内容
- [ ] Given 切换到「当前笔记」context，When 提问，Then 回答只引用当前打开的那条笔记
- [ ] Given 切换到「无 context」，When 提问，Then 回答像普通 Chat 不引用任何本地笔记
- [ ] Given 快速连续发送多条提问触发 429，When 观察错误态，Then 显示「请求过多，请稍后再试」提示而非白屏
- [ ] Given 网络在回答流式输出中途中断，When 观察 UI，Then 已输出的部分保留、末尾显示「连接中断，重试」按钮

## 6. UI（通用体验）

- [ ] Given 主界面有多个 Tab（笔记 / AI / 设置），When 在某 Tab 滚动到中间后切到其他 Tab 再切回来，Then 滚动位置保持不动
- [ ] Given 系统处于浅色模式，When 进入 App 并切换系统为深色模式，Then App 立即切到深色主题、无需重启、文字对比度达标可读
- [ ] Given 系统处于深色模式切回浅色，When 观察所有页面，Then 主界面 / Editor / Settings / AI 全部正常切换、无残留深色元素
- [ ] Given 系统字号设为最大（无障碍字号放大 130% / 150%），When 浏览中文笔记列表，Then 文字不重叠、卡片高度自适应、按钮不被截断
- [ ] Given 系统字号最大，When 打开 Editor 编辑中文长文，Then 输入框可滚动、光标不丢失、工具栏不被挤出屏幕
- [ ] Given 在笔记列表中切换到 AI Tab 并产生一段长回答，When 再切回列表 Tab 再切回 AI Tab，Then AI 对话历史和滚动位置保留
- [ ] Given 横屏使用平板，When 查看主界面，Then 布局合理（双栏或放大版）不会只用左半屏
- [ ] Given 拉起系统分屏（上下各半屏），When 操作 App，Then 功能可用、不出现越界或遮挡
- [ ] Given 开启「开发者选项 → 显示布局边界」，When 快速翻阅所有主要页面，Then 没有明显错位、元素不跑出屏幕边缘

---

## 收尾

- [ ] 清单全部打勾后，在 Release Notes 中记录执行人、时间、设备型号、系统版本
- [ ] 任一条失败：先开 issue 定级（P0 阻塞 / P1 可上但需补丁 / P2 后续跟进），再决定是否放行 P8
