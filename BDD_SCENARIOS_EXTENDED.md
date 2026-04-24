# BDD 扩展场景集 (P8.Ext)

本文件是核心 `BDD_SCENARIOS.md` 的**扩展矩阵**，目标通过正交维度组合覆盖 1000+ 条边界场景。

**编号规则**：`#200` 起步（给主文档 `#54-#199` 留余量），分 10 大节各 100 条。

**生成原则**：
- 用户视角描述（不写代码级断言）
- 每条至少 Given / When / Then 三行
- 可以是相似变体（如"空字符串"×"单字符"×"千字符"），但至少一个维度不同
- 所有场景都是当前实现 OR 合理期望（不是纯幻想）

---

## P8.Ext.A: 笔记 CRUD 边界 (#200-#299, 100 条)

### 场景 #200 · 创建笔记 · 空 body 被拒绝
Given 用户打开新建 Editor
When 不输入任何内容直接点保存
Then 提示 "内容不能为空" 且不创建文件

### 场景 #201 · 创建笔记 · 单字符 body
Given 用户打开新建 Editor
When 输入单个字符 "a" 并保存
Then 成功创建笔记，标题为 "a"

### 场景 #202 · 创建笔记 · 全空格 body
Given 用户打开新建 Editor
When 输入 "     "（全空格）并保存
Then 提示 "内容不能为空" 且不创建文件

### 场景 #203 · 创建笔记 · 千字符 body
Given 用户打开新建 Editor
When 粘贴 1000 字中文段落并保存
Then 成功保存，列表立即出现

### 场景 #204 · 创建笔记 · 万字符 body
Given 用户打开新建 Editor
When 粘贴 10000 字长文并保存
Then 成功保存，Editor 不卡顿

### 场景 #205 · 创建笔记 · 十万字符 body
Given 用户打开新建 Editor
When 粘贴 100000 字长文并保存
Then 保存成功或提示 "内容超过推荐长度，已保存"

### 场景 #206 · 创建笔记 · 纯 emoji body
Given 用户打开新建 Editor
When 输入 "🎉🎊🎈🎁🎂" 并保存
Then 成功保存，列表显示 emoji 标题

### 场景 #207 · 创建笔记 · 混合 emoji + 文字
Given 用户打开新建 Editor
When 输入 "今天🎉生日快乐🎂" 并保存
Then 成功保存，标题正确显示

### 场景 #208 · 创建笔记 · 日文 body
Given 用户打开新建 Editor
When 输入 "こんにちは世界" 并保存
Then 成功保存，列表正确显示日文

### 场景 #209 · 创建笔记 · 韩文 body
Given 用户打开新建 Editor
When 输入 "안녕하세요 세계" 并保存
Then 成功保存，列表正确显示韩文

### 场景 #210 · 创建笔记 · 阿拉伯文 body
Given 用户打开新建 Editor
When 输入 "مرحبا بالعالم" 并保存
Then 成功保存，列表 RTL 方向正确

### 场景 #211 · 创建笔记 · 希伯来文 body
Given 用户打开新建 Editor
When 输入 "שלום עולם" 并保存
Then 成功保存，列表 RTL 方向正确

### 场景 #212 · 创建笔记 · 俄文 body
Given 用户打开新建 Editor
When 输入 "Привет мир" 并保存
Then 成功保存，Cyrillic 字符正确

### 场景 #213 · 创建笔记 · 泰文 body
Given 用户打开新建 Editor
When 输入 "สวัสดีโลก" 并保存
Then 成功保存

### 场景 #214 · 创建笔记 · 繁体中文 body
Given 用户打开新建 Editor
When 输入 "你好，歡迎來到備忘錄" 并保存
Then 成功保存，繁体字符正确显示

### 场景 #215 · 创建笔记 · 包含换行
Given 用户打开新建 Editor
When 输入 "第一行\n第二行\n第三行" 并保存
Then 保存成功，预览取第一行

### 场景 #216 · 创建笔记 · CRLF 换行
Given 用户打开新建 Editor
When 输入含 \r\n 的内容并保存
Then 保存成功，CRLF 正规化为 LF

### 场景 #217 · 创建笔记 · Tab 缩进
Given 用户打开新建 Editor
When 输入包含 tab 的代码块并保存
Then 保存成功，tab 保留

### 场景 #218 · 创建笔记 · 纯数字 body
Given 用户打开新建 Editor
When 输入 "1234567890" 并保存
Then 成功保存，标题为数字

### 场景 #219 · 创建笔记 · Markdown 标题
Given 用户打开新建 Editor
When 输入 "# 一级标题\n内容" 并保存
Then 列表预览去掉 # 符号显示 "一级标题"

### 场景 #220 · 创建笔记 · Markdown 粗体
Given 用户打开新建 Editor
When 输入 "**粗体** 文字" 并保存
Then 保存成功，源码保留 **

### 场景 #221 · 创建笔记 · Markdown 代码块
Given 用户打开新建 Editor
When 输入 "```kotlin\nfun x()\n```" 并保存
Then 保存成功，代码块完整

### 场景 #222 · 创建笔记 · Markdown 列表
Given 用户打开新建 Editor
When 输入 "- item1\n- item2\n- item3" 并保存
Then 列表预览显示前 3 行用 " / " 连接

### 场景 #223 · 创建笔记 · Markdown 引用
Given 用户打开新建 Editor
When 输入 "> 引用文字" 并保存
Then 列表预览去掉 > 前缀

### 场景 #224 · 创建笔记 · Markdown 链接
Given 用户打开新建 Editor
When 输入 "[标题](https://example.com)" 并保存
Then 保存成功，链接源码保留

### 场景 #225 · 创建笔记 · 超长 URL
Given 用户打开新建 Editor
When 输入 2000 字符的 URL 并保存
Then 保存成功，不截断

### 场景 #226 · 创建笔记 · HTML 标签
Given 用户打开新建 Editor
When 输入 "<script>alert('xss')</script>" 并保存
Then 原样保存为纯文本，不执行

### 场景 #227 · 创建笔记 · SQL 注入尝试
Given 用户打开新建 Editor
When 输入 "'; DROP TABLE memo; --" 并保存
Then 原样保存为纯文本，DB 不受影响

### 场景 #228 · 创建笔记 · 路径穿越尝试
Given 用户打开新建 Editor
When 输入包含 "../../etc/passwd" 的 frontmatter path 字段
Then 被 sanitize，不能写到 notes/ 外

### 场景 #229 · 创建笔记 · 控制字符 \0
Given 用户打开新建 Editor
When 输入含 null character 的内容
Then 保存时被剥离或替换为空格

### 场景 #230 · 创建笔记 · Zero-width joiner emoji
Given 用户打开新建 Editor
When 输入 "👨‍👩‍👧‍👦"（家族 emoji）并保存
Then 保存成功，ZWJ 序列完整

### 场景 #231 · 创建笔记 · RTL override 字符
Given 用户打开新建 Editor
When 输入含 U+202E RLO 字符的内容
Then 保存成功，显示时不反转整屏

### 场景 #232 · 创建笔记 · 多语言混合
Given 用户打开新建 Editor
When 输入 "Hello 世界 مرحبا 🌍" 并保存
Then 保存成功，所有字符正确

### 场景 #233 · 创建笔记 · 纯表情符号标题
Given 用户打开新建 Editor
When 输入 "# 🎉" 并保存
Then 列表标题显示单个 emoji

### 场景 #234 · 创建笔记 · 连续三个 --- 分隔
Given 用户打开新建 Editor
When 输入 "---\npinned: true\n---\ncontent\n---\nmore" 并保存
Then 第一段 --- 识别为 frontmatter，后续保留原样

### 场景 #235 · 创建笔记 · frontmatter 无 body
Given 用户打开新建 Editor
When 输入仅 "---\npinned: true\n---" 并保存
Then 提示 "内容不能为空" 或保存空 body

### 场景 #236 · 创建笔记 · pinned frontmatter
Given 用户打开新建 Editor
When 输入 frontmatter 含 "pinned: true" 并保存
Then 保存后笔记自动置顶

### 场景 #237 · 创建笔记 · pinned: True 大写
Given 用户打开新建 Editor
When frontmatter 含 "pinned: True" 并保存
Then 识别为置顶 true

### 场景 #238 · 创建笔记 · pinned: 1 数字
Given 用户打开新建 Editor
When frontmatter 含 "pinned: 1" 并保存
Then 视为非严格 bool，按 false 处理 OR 忽略字段

### 场景 #239 · 创建笔记 · 重复 pinned 键
Given 用户打开新建 Editor
When frontmatter 含两个 "pinned:" 键并保存
Then 最后一个生效

### 场景 #240 · 编辑笔记 · 修改 body
Given 已有笔记 "旧内容"
When 在 Editor 修改为 "新内容" 并保存
Then 保存成功，列表立即反映

### 场景 #241 · 编辑笔记 · 清空 body
Given 已有笔记 "旧内容"
When 在 Editor 删光所有内容并保存
Then 提示 "内容不能为空" 或保留旧版本

### 场景 #242 · 编辑笔记 · 缩短 body
Given 已有笔记 100 字
When 修改为 10 字并保存
Then 文件大小减小

### 场景 #243 · 编辑笔记 · 扩展 body
Given 已有笔记 10 字
When 扩展到 10000 字并保存
Then 保存成功，不卡顿

### 场景 #244 · 编辑笔记 · 修改后退出不保存
Given 已有笔记 "旧内容"
When 修改内容后点返回键
Then 提示 "是否保存" 或自动草稿

### 场景 #245 · 编辑笔记 · 双击保存
Given 已打开笔记 Editor
When 快速双击保存按钮
Then 只执行一次保存，不重复

### 场景 #246 · 编辑笔记 · 三击保存
Given 已打开笔记 Editor
When 快速三击保存按钮
Then 仍只执行一次保存

### 场景 #247 · 编辑笔记 · 保存期间网络断开
Given 正在保存笔记
When 保存到本地成功后推送 GitHub 时网络断开
Then 本地保留 dirty=true，下次联网重试

### 场景 #248 · 编辑笔记 · 保存冲突
Given 另一设备已修改同一笔记
When 本设备提交修改
Then 进入冲突解决 UI

### 场景 #249 · 编辑笔记 · 修改置顶笔记
Given 置顶笔记 "重要事项"
When 修改内容并保存
Then 仍保持置顶状态

### 场景 #250 · 删除笔记 · 单笔记
Given 列表有 1 条笔记
When 滑动删除或长按删除
Then 列表变空，显示 empty state

### 场景 #251 · 删除笔记 · 多选删除
Given 列表有 5 条笔记
When 多选 3 条并删除
Then 列表剩 2 条

### 场景 #252 · 删除笔记 · 全选删除
Given 列表有 10 条笔记
When 全选并删除
Then 列表变空

### 场景 #253 · 删除笔记 · 删除置顶笔记
Given 有置顶笔记
When 删除该笔记
Then 置顶笔记消失

### 场景 #254 · 删除笔记 · 删除后撤销
Given 刚删除一条笔记
When 点击 "撤销" snackbar
Then 笔记恢复到列表

### 场景 #255 · 删除笔记 · 删除后 push 到 GitHub
Given 已删除笔记
When PushWorker 执行
Then GitHub 上对应文件被删除

### 场景 #256 · 删除笔记 · 删除时无网络
Given 无网络
When 删除笔记
Then 本地标记 tombstone，联网后同步

### 场景 #257 · 删除笔记 · 跨设备删除冲突
Given 设备 A 删除笔记，设备 B 修改同一笔记
When 两者同步
Then 进入冲突解决

### 场景 #258 · 置顶笔记 · 置顶单条
Given 列表有 5 条笔记
When 长按一条选择 "置顶"
Then 该笔记移到列表顶部

### 场景 #259 · 置顶笔记 · 取消置顶
Given 已置顶笔记
When 再次长按选择 "取消置顶"
Then 笔记回到时间排序位置

### 场景 #260 · 置顶笔记 · 同时置顶多条
Given 已置顶笔记 A、B
When 置顶 C
Then 置顶区按置顶时间倒序：C、B、A（或按原始逻辑）

### 场景 #261 · 置顶笔记 · 置顶 50 条
Given 列表有 100 条笔记
When 依次置顶 50 条
Then 所有 50 条都在顶部区

### 场景 #262 · 置顶笔记 · pinned frontmatter 同步到 GitHub
Given 置顶笔记
When PushWorker 执行
Then GitHub 上该文件 frontmatter 有 "pinned: true"

### 场景 #263 · 置顶笔记 · GitHub 端修改后 pull
Given GitHub 文件修改为 "pinned: true"
When PullWorker 拉取
Then 本地该笔记变为置顶

### 场景 #264 · 搜索笔记 · 搜索单关键词
Given 列表有笔记 "晚餐吃寿司"
When 搜索 "寿司"
Then 结果包含该笔记

### 场景 #265 · 搜索笔记 · 无匹配
Given 列表有笔记
When 搜索不存在的关键词
Then 显示 "没有匹配结果"

### 场景 #266 · 搜索笔记 · 空搜索
Given 列表有笔记
When 搜索框为空
Then 显示全部笔记

### 场景 #267 · 搜索笔记 · 搜索中英文混合
Given 笔记 "今天 meeting 很长"
When 搜索 "meeting"
Then 返回该笔记

### 场景 #268 · 搜索笔记 · 搜索 emoji
Given 笔记 "今天🎉"
When 搜索 "🎉"
Then 返回该笔记

### 场景 #269 · 搜索笔记 · 大小写不敏感
Given 笔记 "Meeting Room"
When 搜索 "meeting"
Then 返回该笔记

### 场景 #270 · 搜索笔记 · 支持部分匹配
Given 笔记 "寿司"
When 搜索 "寿"
Then 返回该笔记

### 场景 #271 · 搜索笔记 · 特殊字符
Given 笔记 "a.b.c"
When 搜索 "a.b"
Then 返回该笔记（. 作普通字符）

### 场景 #272 · 搜索笔记 · 搜索 frontmatter 不命中
Given 笔记 frontmatter 含 "author: Alice"
When 搜索 "Alice"
Then 不返回（只搜 body）或返回（按实现）

### 场景 #273 · 搜索笔记 · 搜索结果按相关度排序
Given 多条笔记含 "meeting"
When 搜索 "meeting"
Then 标题含 meeting 的排在前

### 场景 #274 · 搜索笔记 · 搜索 1000 条笔记
Given 列表有 1000 条笔记
When 搜索 "关键词"
Then 结果在 500ms 内返回

### 场景 #275 · 笔记详情 · 查看全文
Given 列表有笔记
When 点击进入 Editor
Then 显示完整 body

### 场景 #276 · 笔记详情 · 查看 metadata
Given 已打开笔记
When 查看 info 面板
Then 显示创建时间、修改时间、文件路径

### 场景 #277 · 笔记详情 · 分享笔记
Given 已打开笔记
When 点击分享
Then 调用系统分享 sheet

### 场景 #278 · 笔记详情 · 复制正文
Given 已打开笔记
When 全选复制
Then 内容在剪贴板

### 场景 #279 · 笔记详情 · 导出为 Markdown
Given 已打开笔记
When 点击导出
Then 生成 .md 文件到 Downloads

### 场景 #280 · 笔记列表 · 空态展示
Given 用户首次打开 app
When 进入 Notes tab
Then 显示空态插画 + "创建第一条笔记"

### 场景 #281 · 笔记列表 · 初次加载
Given app 冷启动
When 进入 Notes tab
Then 列表在 300ms 内显示

### 场景 #282 · 笔记列表 · 下拉刷新
Given 已在 Notes tab
When 下拉手势
Then 触发 PullWorker 手动同步

### 场景 #283 · 笔记列表 · 上拉加载更多
Given 列表有 100+ 条笔记
When 滚到底
Then 加载更早的笔记

### 场景 #284 · 笔记列表 · 滚动性能
Given 列表有 500 条笔记
When 快速滚动
Then 帧率不掉到 30fps 以下

### 场景 #285 · 笔记列表 · 时间分组
Given 列表有多天笔记
When 查看列表
Then 按天分组显示标题 "今天"/"昨天"/"本周"/"更早"

### 场景 #286 · 笔记列表 · 混合 legacy 与 single-note
Given 同时有 legacy day 条目和 single-note
When 查看列表
Then 按时间顺序混排，颜色区分（legacy 绿/single 紫）

### 场景 #287 · 笔记列表 · 置顶区与时间区
Given 有置顶笔记和普通笔记
When 查看列表
Then 置顶区在上，普通区在下，分隔明显

### 场景 #288 · 笔记列表 · 长按展开菜单
Given 列表条目
When 长按
Then 弹出 "编辑/删除/置顶/问 AI" 菜单

### 场景 #289 · 笔记列表 · 标签筛选
Given 笔记有标签 "work"
When 点击标签 chip
Then 只显示带该标签的笔记

### 场景 #290 · 笔记列表 · 日期筛选
Given 日历选中某天
When 返回列表
Then 只显示该天的笔记

### 场景 #291 · 笔记列表 · 组合筛选
Given 同时选标签和日期
When 查看列表
Then 交集结果

### 场景 #292 · 笔记列表 · 排序切换
Given 列表默认按时间
When 切换到按修改时间
Then 排序立即变化

### 场景 #293 · 笔记列表 · 视图密度切换
Given 列表默认紧凑
When 切换到宽松视图
Then 每条高度增加

### 场景 #294 · 笔记列表 · 卡片/列表切换
Given 列表为列表视图
When 切换到卡片视图
Then 每条变卡片样式

### 场景 #295 · 笔记列表 · 快速滚动条
Given 列表 500+ 条
When 滚动时
Then 右侧出现滚动指示条

### 场景 #296 · 笔记列表 · 搜索后清空
Given 搜索关键词显示少量结果
When 点击清空按钮
Then 恢复全部显示

### 场景 #297 · 笔记列表 · 空态点 + 按钮
Given 空态
When 点 + 按钮
Then 打开新建 Editor

### 场景 #298 · 笔记列表 · 加载中骨架屏
Given 首次加载
When 数据还没到
Then 显示骨架屏（不是空白）

### 场景 #299 · 笔记列表 · 错误态
Given DB 读取失败
When 查看列表
Then 显示 "加载失败，重试" 按钮

---

## P8.Ext.B: 同步机制 (#300-#399, 100 条)

### 场景 #300 · PullWorker · 首次同步
Given 首次配置 PAT
When 触发 pull
Then 拉取所有 notes/*.md

### 场景 #301 · PullWorker · 增量拉取
Given 已同步过，本地 ETag 有值
When 再次 pull
Then 只拉取变化的文件（使用 ETag）

### 场景 #302 · PullWorker · PullBudget 限流
Given 远端有 500 个变化
When pull 触发
Then 最多拉取 PullBudget.cap=150 个

### 场景 #303 · PullWorker · 超出 budget 记录 cursor
Given pull 达到 budget 上限
When 下次 pull
Then 从 cursor 继续

### 场景 #304 · PullWorker · 网络 403
Given PAT 权限不足
When pull 触发
Then ErrorCode.UNAUTHORIZED，SyncStatus 显示 "权限不足"

### 场景 #305 · PullWorker · 网络 401
Given PAT 过期
When pull 触发
Then ErrorCode.UNAUTHORIZED

### 场景 #306 · PullWorker · 网络 404
Given repo 不存在
When pull 触发
Then ErrorCode.NOT_FOUND，提示 "仓库不存在"

### 场景 #307 · PullWorker · 网络 5xx
Given GitHub 服务故障
When pull 触发
Then ErrorCode.SERVER，自动重试

### 场景 #308 · PullWorker · 网络超时
Given 请求超过 30s
When pull 触发
Then ErrorCode.TIMEOUT

### 场景 #309 · PullWorker · 弱网重试
Given 网络不稳定
When pull 触发
Then 指数退避重试 3 次

### 场景 #310 · PullWorker · 拉取后 widget 刷新
Given pull 成功拉取 5 个新笔记
When doWork 结束
Then widget 立即显示新笔记

### 场景 #311 · PullWorker · 冲突检测
Given 本地 dirty=true 且远端更新
When pull 触发
Then 进入冲突解决 UI

### 场景 #312 · PullWorker · 拉取超大文件
Given 远端有 10MB 笔记文件
When pull
Then 限制为 1MB 最大，超过提示

### 场景 #313 · PullWorker · 拉取非 md 文件
Given 仓库有 .txt / .png
When pull
Then 仅拉取 .md，忽略其他

### 场景 #314 · PullWorker · 拉取 frontmatter 格式错误
Given 远端文件 frontmatter 格式坏
When pull
Then fallback 为无 frontmatter 处理

### 场景 #315 · PullWorker · 时间戳解析失败
Given 远端文件名不符合 ISO 格式
When pull
Then 用 commit 时间作 fallback

### 场景 #316 · PullWorker · 拉取后 SyncStatusBus 广播
Given pull 完成
When doWork 结束
Then SyncStatusBus 发送 Success 事件

### 场景 #317 · PullWorker · 周期性调度
Given 用户启用自动同步
When 15 分钟一次
Then WorkManager 触发 pull

### 场景 #318 · PullWorker · 受 WiFi 约束
Given 用户选 "仅 WiFi 同步"
When 移动网络下
Then pull 不触发

### 场景 #319 · PullWorker · 受充电约束
Given 用户选 "仅充电时"
When 电池未充电
Then pull 不触发

### 场景 #320 · PushWorker · 本地 dirty 推送
Given 本地有 3 条 dirty 笔记
When push 触发
Then 3 个文件上传到 GitHub

### 场景 #321 · PushWorker · 单次 push 单文件
Given 仅 1 条 dirty
When push
Then 单文件 PUT 请求

### 场景 #322 · PushWorker · 批量 push 用 blob API
Given 10+ dirty
When push
Then 用 blob + tree + commit API 批量

### 场景 #323 · PushWorker · 推送后 dirty=false
Given 推送成功
When doWork 结束
Then 本地 dirty 清零

### 场景 #324 · PushWorker · 推送失败不清 dirty
Given 推送 5xx 失败
When doWork 结束
Then dirty 保持，下次重试

### 场景 #325 · PushWorker · 推送冲突
Given 远端 SHA 不匹配
When PUT 返回 409
Then 进入冲突解决

### 场景 #326 · PushWorker · 推送后 widget 刷新
Given push 成功
When doWork 结束
Then widget 显示 "已同步" 状态

### 场景 #327 · PushWorker · 推送 tombstone（删除）
Given 本地 tombstone 标记
When push
Then GitHub 对应文件 DELETE

### 场景 #328 · PushWorker · 推送带 frontmatter
Given 笔记 pinned=true
When push
Then GitHub 文件包含 frontmatter

### 场景 #329 · PushWorker · 推送 CRLF 正规化
Given 本地 LF
When push
Then GitHub 文件 LF（保持）

### 场景 #330 · 同步状态栏 · 空闲态
Given 无同步任务
When 查看 SyncStatusBar
Then 显示 "已同步"

### 场景 #331 · 同步状态栏 · 运行中
Given 同步运行中
When 查看
Then 显示 "同步中" 进度条

### 场景 #332 · 同步状态栏 · 成功
Given 同步刚完成
When 查看
Then 显示 "✓ 已同步 2 分钟前"

### 场景 #333 · 同步状态栏 · 失败
Given 同步失败
When 查看
Then 显示 "✕ 同步失败" + 错误原因

### 场景 #334 · 同步状态栏 · 未配置
Given 无 PAT
When 查看
Then 显示 "未配置 GitHub" + 跳配置按钮

### 场景 #335 · 同步状态栏 · 冲突态
Given 有冲突未解决
When 查看
Then 显示 "有冲突待解决" + 跳冲突 UI

### 场景 #336 · 手动同步 · 按钮触发 pull
Given SyncStatusBar 有同步按钮
When 点击
Then 立即触发 pull

### 场景 #337 · 手动同步 · 按钮触发 push
Given 有 dirty 笔记
When 点击同步按钮
Then 先 push 再 pull

### 场景 #338 · 手动同步 · 连续快速点击
Given 刚点过同步按钮
When 1 秒内再点
Then 忽略重复请求（debounce）

### 场景 #339 · 手动同步 · 网络不可用提示
Given 飞行模式
When 点同步按钮
Then snackbar "无网络连接"

### 场景 #340 · 自动同步 · 启用开关
Given 设置里切换 "自动同步" on
When 保存
Then 每 15 分钟 WorkManager 触发 pull

### 场景 #341 · 自动同步 · 关闭开关
Given 自动同步 on
When 切换为 off
Then WorkManager 取消调度

### 场景 #342 · 自动同步 · 调度频率
Given 用户选 30 分钟
When 保存
Then 周期改为 30 分钟

### 场景 #343 · 自动同步 · Boot 后恢复
Given 自动同步 on
When 设备重启
Then WorkManager 恢复调度

### 场景 #344 · 自动同步 · 受 Doze 影响
Given 设备进入 Doze
When 到达调度时间
Then 延后到下次 maintenance window

### 场景 #345 · 冲突解决 · 选择本地版本
Given 进入冲突 UI
When 选 "使用本地"
Then 本地版本 push 到远端

### 场景 #346 · 冲突解决 · 选择远端版本
Given 进入冲突 UI
When 选 "使用远端"
Then 远端版本覆盖本地

### 场景 #347 · 冲突解决 · 手动合并
Given 进入冲突 UI
When 选 "手动合并"
Then 打开合并编辑器（双栏 diff）

### 场景 #348 · 冲突解决 · 取消
Given 进入冲突 UI
When 选 "取消"
Then 保留 dirty，回退到列表

### 场景 #349 · 冲突解决 · 多文件冲突
Given 3 个文件冲突
When 逐个解决
Then 进度条 "1/3 → 3/3"

### 场景 #350 · Push 权限检测 · PAT 读权限
Given PAT 只有 contents:read
When push 触发
Then ErrorCode.UNAUTHORIZED "PAT 无写权限"

### 场景 #351 · Push 权限检测 · PAT 写权限
Given PAT 有 contents:write
When push 触发
Then 成功

### 场景 #352 · Push 权限检测 · organization SSO 要求
Given PAT 未授权 org SSO
When push 触发
Then ErrorCode.UNAUTHORIZED "需要授权 SSO"

### 场景 #353 · Repo 验证 · branch 不存在
Given 配置的 branch 不存在
When pull 触发
Then 创建 branch 或提示

### 场景 #354 · Repo 验证 · repo 为 private 无权限
Given repo 为 private 且 PAT 无权限
When 连接
Then ErrorCode.NOT_FOUND

### 场景 #355 · Repo 验证 · repo name 含特殊字符
Given repo 名 "my-notes"
When 连接
Then URL encode 正确

### 场景 #356 · 同步配置保存 · owner 字段
Given 填写 owner
When 保存
Then DataStore 持久化

### 场景 #357 · 同步配置保存 · repo 字段
Given 填写 repo
When 保存
Then DataStore 持久化

### 场景 #358 · 同步配置保存 · PAT 加密
Given 填写 PAT
When 保存
Then EncryptedSharedPreferences 存储

### 场景 #359 · 同步配置保存 · branch 默认 main
Given 不填 branch
When 保存
Then 默认 "main"

### 场景 #360 · 同步配置保存 · path 默认 notes/
Given 不填 path
When 保存
Then 默认 "notes/"

### 场景 #361 · 同步配置读取 · 加密 PAT 解密
Given 已保存加密 PAT
When 读取
Then 正确解密返回明文

### 场景 #362 · 同步配置读取 · 应用重启后保留
Given 已保存配置
When app 重启
Then 配置仍存在

### 场景 #363 · 同步配置 · isConfigured 切换
Given 配置不完整（缺 PAT）
When 填写 PAT
Then isConfigured 切为 true

### 场景 #364 · 同步配置 · 清除所有配置
Given 已配置
When 用户点 "清除"
Then 所有字段清空，isConfigured=false

### 场景 #365 · 同步配置 · PAT 格式校验
Given 填写 "not-a-token"
When 保存
Then 提示 "PAT 格式不正确"

### 场景 #366 · 同步配置 · owner 字符校验
Given owner 含空格
When 保存
Then 提示 "owner 格式不正确"

### 场景 #367 · 同步配置 · repo 字符校验
Given repo 含 `/`
When 保存
Then 提示 "repo 格式不正确"

### 场景 #368 · 同步配置 · 测试连接按钮
Given 填写完整
When 点 "测试连接"
Then 调 GET /user，显示用户名

### 场景 #369 · 同步配置 · 测试连接失败
Given PAT 无效
When 点 "测试连接"
Then snackbar "PAT 无效"

### 场景 #370 · 网络监听 · 离线转在线自动同步
Given 离线状态，有 dirty
When 网络恢复
Then 自动触发 push

### 场景 #371 · 网络监听 · 在线转离线
Given 在线
When 切换飞行模式
Then 正在同步的任务优雅取消

### 场景 #372 · 网络监听 · WiFi 切换到移动网络
Given WiFi 限制开
When 切换移动
Then 暂停同步

### 场景 #373 · 网络监听 · VPN 连接
Given 用户开 VPN
When 同步
Then 正常走 VPN 路由

### 场景 #374 · 网络监听 · 代理环境
Given 企业代理
When 同步
Then 通过系统代理配置

### 场景 #375 · 并发 · 多 worker 互斥
Given PullWorker 运行中
When 再触发 push
Then push 排队等待

### 场景 #376 · 并发 · WorkManager unique
Given 已有同名 worker 排队
When enqueue 同名
Then KEEP 或 REPLACE 按策略

### 场景 #377 · 并发 · 多笔记并发写
Given 同时创建 3 个新笔记
When 都保存
Then PathLocker 保证串行化，3 个文件全部写入

### 场景 #378 · 并发 · 同文件并发写
Given 两线程写同一文件
When 并发
Then PathLocker 保证顺序，后写覆盖先写

### 场景 #379 · 端到端同步 · 设备 A 写，设备 B 拉
Given 两设备配置同 repo
When A 保存，B pull
Then B 看到 A 的笔记

### 场景 #380 · 端到端同步 · A 删，B 拉
Given A 删除笔记
When A push，B pull
Then B 对应笔记消失

### 场景 #381 · 端到端同步 · A 置顶，B 拉
Given A 置顶笔记
When A push，B pull
Then B 该笔记显示置顶

### 场景 #382 · 端到端同步 · A/B 同时改
Given A、B 同时修改同一笔记
When 都 push
Then 一方进入冲突解决

### 场景 #383 · 端到端同步 · 三设备分叉
Given A、B、C 都修改同一笔记
When push 顺序 A→B→C
Then B、C 依次进入冲突

### 场景 #384 · Rate limit · GitHub 403 rate limited
Given pull 多次后命中 rate limit
When doWork
Then ErrorCode.RATE_LIMITED，退避重试

### 场景 #385 · Rate limit · 显示 reset 时间
Given rate limited
When 查看状态栏
Then 显示 "将在 X 分钟后恢复"

### 场景 #386 · 大仓库 · 10000 个文件
Given 远端 repo 有 10000 个 .md
When pull
Then PullBudget 限流，首次只拉 150，后续渐进拉

### 场景 #387 · 大仓库 · 目录嵌套
Given notes/2024/01/.../x.md
When pull
Then 所有嵌套 md 被发现

### 场景 #388 · 增量算法 · ETag 匹配
Given 远端 ETag 未变
When pull
Then 304 Not Modified，跳过解析

### 场景 #389 · 增量算法 · commit SHA 对比
Given 本地记住 last SHA
When pull
Then 对比 SHA 决定增量范围

### 场景 #390 · 增量算法 · tree API 差分
Given 本地 tree SHA
When pull
Then compareTrees 只返回变化文件

### 场景 #391 · 初始化 · 空 repo
Given repo 全新无 notes/
When pull
Then 返回空列表，本地为空

### 场景 #392 · 初始化 · notes/ 目录不存在
Given repo 有内容但无 notes/ 目录
When pull
Then 自动创建 notes/ 并 push 一个 .gitkeep

### 场景 #393 · 迁移 · 从 legacy 到 single-note
Given 已有 YYYY-MM-DD.md
When 开启 single-note 模式
Then 提供 "迁移向导" 按钮

### 场景 #394 · 迁移 · 迁移进度
Given 50 个 legacy 条目
When 点 "迁移"
Then 显示进度条 "1/50 → 50/50"

### 场景 #395 · 迁移 · 迁移失败回滚
Given 迁移中途 crash
When 重启
Then 回滚到迁移前状态

### 场景 #396 · 迁移 · 迁移后保留原文件
Given 迁移成功
When 查看 repo
Then 原 YYYY-MM-DD.md 保留（不删）

### 场景 #397 · 同步日志 · 记录最近 50 次
Given 同步运行了 100 次
When 查看日志
Then 显示最近 50 次

### 场景 #398 · 同步日志 · 成功/失败分类
Given 查看日志
When 筛选 "失败"
Then 只显示失败次

### 场景 #399 · 同步日志 · 清理旧日志
Given 日志超过 30 天
When 定期清理
Then 30 天前记录被删

---

## P8.Ext.C: Widget 交互矩阵 (#400-#499, 100 条)

### 场景 #400 · MemoWidget · 添加到桌面
Given 桌面无 widget
When 长按桌面选 Memo widget
Then widget 添加成功

### 场景 #401 · MemoWidget · 首次显示
Given 刚添加 widget
When 等 Glance 加载
Then 显示最多 20 条最近笔记

### 场景 #402 · MemoWidget · 未配置态显示
Given PAT 未配置
When 查看 widget
Then 显示 "先打开 app 配置 GitHub PAT"

### 场景 #403 · MemoWidget · 空态显示
Given 已配置但无笔记
When 查看 widget
Then 显示 "还没有备忘，点 + 开始"

### 场景 #404 · MemoWidget · 满载 20 条
Given 有 20 条笔记
When 查看 widget
Then 全部 20 条可滚动

### 场景 #405 · MemoWidget · 超过 20 条
Given 有 100 条笔记
When 查看 widget
Then 只显示最近 20 条

### 场景 #406 · MemoWidget · 滚动列表
Given widget 20 条
When 向下滑动
Then 列表滚动显示更多

### 场景 #407 · MemoWidget · Resize 到 2x2
Given widget 3x3
When 缩小到 2x2
Then 显示更少行，仍可滚动

### 场景 #408 · MemoWidget · Resize 到 4x4
Given widget 3x3
When 放大到 4x4
Then 显示更多行一屏内

### 场景 #409 · MemoWidget · Resize 到 5x2
Given widget 3x3
When 变形为 5x2
Then 宽变长，行数变少

### 场景 #410 · MemoWidget · Resize 保留数据
Given widget 已加载
When resize
Then 不重新请求数据

### 场景 #411 · MemoWidget · 点击条目打开 Editor
Given widget 显示笔记 X
When 点 X 行
Then 打开 EditActivity with X.uid

### 场景 #412 · MemoWidget · 点击 "+ New" 按钮
Given widget 已显示
When 点右上 +
Then 打开新建 Editor

### 场景 #413 · MemoWidget · 点击刷新按钮
Given widget 已显示
When 点 🔄
Then MemoWidget.updateAll 触发

### 场景 #414 · MemoWidget · 刷新按钮过程
Given 点 🔄
When 刷新中
Then 可选显示 loading 指示

### 场景 #415 · MemoWidget · 刷新按钮失败
Given 网络故障
When 点 🔄
Then 显示旧数据（不崩）

### 场景 #416 · MemoWidget · 未配置态点任意处
Given 未配置
When 点 widget 任意位置
Then 打开 MainActivity

### 场景 #417 · MemoWidget · 未配置态的 + 按钮
Given 未配置
When 点 widget + 按钮
Then 打开 MainActivity（而非 Editor）

### 场景 #418 · MemoWidget · 未配置态 🔄 按钮
Given 未配置
When 点 🔄
Then 无效果或提示 "请先配置"

### 场景 #419 · MemoWidget · 条目显示 MM/DD HH:mm 前缀
Given widget 有条目
When 渲染
Then 每行开头 "MM/DD HH:mm"

### 场景 #420 · MemoWidget · 条目显示标题
Given 笔记有 # 标题
When widget 渲染
Then 显示去 # 的标题

### 场景 #421 · MemoWidget · 条目显示 body 预览
Given 笔记无标题
When widget 渲染
Then 显示首非空行

### 场景 #422 · MemoWidget · 条目长文本截断
Given body 很长
When widget 渲染
Then 单行截断 ellipsis

### 场景 #423 · MemoWidget · 置顶标识
Given 笔记 pinned=true
When widget 渲染
Then 行首有 📌 或特殊背景

### 场景 #424 · MemoWidget · 仓库模式颜色
Given SingleNote
When widget 渲染
Then 紫色 accent

### 场景 #425 · MemoWidget · Legacy 条目颜色
Given legacy day
When widget 渲染
Then 绿色 accent（或统一）

### 场景 #426 · MemoWidget · 深色模式
Given 系统深色
When widget 渲染
Then 深色背景 + 亮文本

### 场景 #427 · MemoWidget · 浅色模式
Given 系统浅色
When widget 渲染
Then 亮背景 + 深文本

### 场景 #428 · MemoWidget · Material You 取色（P8.1）
Given Android 12+
When widget 渲染
Then 用 dynamic color

### 场景 #429 · MemoWidget · 文本字号跟随系统
Given 系统字号大
When widget 渲染
Then 文本放大

### 场景 #430 · MemoWidget · 字号大时行数减少
Given 字号超大
When widget 3x3
Then 行数自动减少

### 场景 #431 · MemoWidget · 多实例并存
Given 桌面有 2 个 Memo widget
When 都添加
Then 各自独立刷新

### 场景 #432 · MemoWidget · 删除一个实例
Given 2 个 widget
When 删除一个
Then 另一个不受影响

### 场景 #433 · MemoWidget · 桌面切换
Given widget 在桌面 1
When 切到桌面 2
Then 滑回桌面 1 看到 widget

### 场景 #434 · MemoWidget · 自动刷新 - 创建新笔记
Given widget 显示
When app 内创建新笔记
Then widget 自动刷新

### 场景 #435 · MemoWidget · 自动刷新 - 编辑笔记
Given widget 显示
When 编辑现有笔记
Then widget 显示新内容

### 场景 #436 · MemoWidget · 自动刷新 - 删除笔记
Given widget 显示
When 删除笔记
Then widget 列表移除该条

### 场景 #437 · MemoWidget · 自动刷新 - 置顶
Given widget 显示
When 置顶笔记
Then 该笔记移到顶部

### 场景 #438 · MemoWidget · 自动刷新 - PullWorker
Given PullWorker 拉取到新笔记
When doWork 完成
Then widget 自动刷新

### 场景 #439 · MemoWidget · 自动刷新 - PushWorker
Given PushWorker 推送成功
When doWork 完成
Then widget 反映同步完成状态

### 场景 #440 · MemoWidget · 自动刷新 - 配置切换
Given 未配置
When 保存 PAT
Then widget 从 prompt 变笔记列表

### 场景 #441 · MemoWidget · 自动刷新 debounce
Given 连续 5 次保存
When 1 秒内完成
Then widget 只刷新 1-2 次

### 场景 #442 · MemoWidget · 自动刷新失败不崩
Given MemoWidget.updateAll 抛异常
When WidgetRefresher 调
Then runCatching 吃异常，写路径不失败

### 场景 #443 · MemoWidget · 冷启动恢复
Given app 冷启动
When 初始化完成
Then widget 反映最新状态

### 场景 #444 · MemoWidget · AI 问答不触发刷新
Given AI 对话完成
When 不修改笔记
Then widget 不刷新

### 场景 #445 · TodayWidget · 添加到桌面
Given 桌面无 Today widget
When 长按桌面选 Today
Then widget 添加

### 场景 #446 · TodayWidget · 显示今天 events
Given 今天有 3 个 events
When 查看 widget
Then 列出 3 个

### 场景 #447 · TodayWidget · 显示今天 memos
Given 今天有 5 条 memo
When 查看 widget
Then 列出 5 条

### 场景 #448 · TodayWidget · events + memos 混合
Given 今天 events 3 + memos 5
When 查看
Then 按时间顺序混排

### 场景 #449 · TodayWidget · 跨日自动更新
Given 昨天 23:59 查看 widget
When 跨到 00:00
Then widget 显示新一天的内容

### 场景 #450 · TodayWidget · 空态
Given 今天无事
When 查看
Then 显示 "今天没有安排"

### 场景 #451 · TodayWidget · 未配置态
Given 未配置 PAT
When 查看
Then 显示 "先打开 app 配置"

### 场景 #452 · TodayWidget · 点击 event
Given widget 显示 event
When 点 event
Then 跳 Calendar tab 显示该 event

### 场景 #453 · TodayWidget · 点击 memo
Given widget 显示 memo
When 点 memo
Then 跳 Editor 打开该 memo

### 场景 #454 · TodayWidget · 刷新按钮
Given widget 显示
When 点 🔄
Then TodayWidget.updateAll

### 场景 #455 · TodayWidget · + 按钮
Given widget 显示
When 点 +
Then 打开新建 Editor（今天）

### 场景 #456 · TodayWidget · resize 4x2
Given 默认 4x2
When resize
Then 仍 4x2 可轻微 flex

### 场景 #457 · TodayWidget · 自动刷新 - 新增 event
Given 有 TodayWidget
When app 内新增 event
Then widget 自动刷新

### 场景 #458 · TodayWidget · 自动刷新 - 编辑 event
Given 有 TodayWidget
When 编辑 event
Then widget 显示新 event

### 场景 #459 · TodayWidget · 自动刷新 - 新增 memo
Given 有 TodayWidget
When 新增 memo
Then widget 刷新

### 场景 #460 · TodayWidget · 混 legacy + single
Given 今天混合
When 查看
Then 顺序合理

### 场景 #461 · TodayWidget · 显示日期标题
Given widget 顶部
When 渲染
Then 显示 "4月24日 星期四"

### 场景 #462 · TodayWidget · 深色模式
Given 系统深色
When 渲染
Then 深色主题

### 场景 #463 · TodayWidget · 多实例并存
Given 两个 Today widget
When 都添加
Then 各自独立

### 场景 #464 · Widget · 桌面主题切换联动
Given Launcher 换主题
When 重绘
Then widget 适配新背景

### 场景 #465 · Widget · Launcher 横屏
Given 设备横屏
When widget 显示
Then 布局自适应

### 场景 #466 · Widget · Launcher 分屏
Given Launcher 分屏模式
When widget 布局
Then 宽度适配分屏宽

### 场景 #467 · Widget · Launcher folder 内
Given widget 在 folder
When 打开 folder
Then 仍可交互

### 场景 #468 · Widget · 桌面页缩略图
Given 桌面 overview
When 查看缩略图
Then widget 有内容渲染

### 场景 #469 · Widget · 锁屏 widget（Android 16）
Given Android 16 锁屏 widget
When 启用
Then 锁屏显示 Memo widget

### 场景 #470 · Widget · 全面屏指示灯处
Given 全面屏刘海
When widget 在顶部
Then 避让刘海

### 场景 #471 · Widget · 小屏手机
Given 屏幕 5.5 英寸
When 3x3 widget
Then 文本不溢出

### 场景 #472 · Widget · 平板大屏
Given 屏幕 10 英寸
When widget 占格相同
Then 内容居中不拉伸

### 场景 #473 · Widget · 折叠屏展开
Given 折叠屏展开
When widget 重布局
Then 利用更大空间

### 场景 #474 · Widget · 可折叠设备折叠
Given 折叠屏折叠
When widget 收缩
Then 自适应窄屏

### 场景 #475 · Widget · 点击后切换回桌面
Given widget 点击打开 app
When 返回
Then widget 状态保持

### 场景 #476 · Widget · 应用被系统清后
Given widget 在桌面但进程被杀
When 点 widget
Then 冷启动 app 正确路由

### 场景 #477 · Widget · App 更新后
Given widget 在桌面
When app 版本更新
Then widget 自动升级不丢数据

### 场景 #478 · Widget · App 卸载
Given widget 在桌面
When 卸载 app
Then widget 自动从桌面移除

### 场景 #479 · Widget · 清除数据
Given 已配置
When 在设置中清除 app 数据
Then widget 回到未配置态

### 场景 #480 · Widget · 主 widget + today widget 同时存在
Given 两个 widget 都添加
When 编辑笔记
Then 两个都刷新

### 场景 #481 · Widget · 性能 - 渲染耗时
Given 20 条笔记
When Glance 渲染
Then 低于 500ms

### 场景 #482 · Widget · 性能 - 内存占用
Given widget 运行
When 监控
Then 内存 < 10MB

### 场景 #483 · Widget · 性能 - 电量占用
Given widget 刷新 100 次
When 监控
Then 电量影响 < 2%

### 场景 #484 · Widget · 刷新频率限制
Given WidgetRefresher debounce 400ms
When 连续 10 次 refreshAll
Then updateAll 调用 ≤ 3 次

### 场景 #485 · Widget · 权限 · 不需 internet 权限
Given widget 渲染
When 显示
Then 不请求网络权限（只读 DB）

### 场景 #486 · Widget · 权限 · 深链权限
Given 点 widget 条目
When 跳 EditActivity
Then 带正确 uid extra

### 场景 #487 · Widget · 防御 · uid 不存在
Given 点 widget 条目但 uid 已被删
When 跳 Editor
Then Editor 显示 "笔记不存在" 并返回

### 场景 #488 · Widget · 防御 · corrupt 数据
Given DB 损坏
When widget 请求
Then 回退到空态而非崩溃

### 场景 #489 · Widget · 无障碍 · talkback
Given 开启 talkback
When 焦点在 widget 条目
Then 朗读 "MM/DD HH:mm 标题"

### 场景 #490 · Widget · 无障碍 · content description
Given widget 按钮
When talkback 朗读
Then 读 "刷新" / "新建"

### 场景 #491 · Widget · 无障碍 · 大字号
Given 系统字号最大
When widget 渲染
Then 文本可读不溢出

### 场景 #492 · Widget · 无障碍 · 高对比度
Given 系统高对比度
When widget 渲染
Then 文本对比度达标

### 场景 #493 · Widget · 无障碍 · reduce motion
Given 系统 reduce motion
When widget 刷新
Then 无过渡动画

### 场景 #494 · Widget · 国际化 · RTL 布局
Given 系统阿拉伯语
When widget 渲染
Then 按钮位置镜像

### 场景 #495 · Widget · 国际化 · 英文字符串
Given 系统英文
When widget 渲染
Then "No memo" / "Refresh" 等英文

### 场景 #496 · Widget · 国际化 · 日文字符串
Given 系统日文
When widget 渲染
Then 日文字符串

### 场景 #497 · Widget · 国际化 · 韩文字符串
Given 系统韩文
When widget 渲染
Then 韩文字符串

### 场景 #498 · Widget · 配置界面（P8.1 展望）
Given widget picker
When 点击 widget
Then 可选 "显示 10/20/40 条" 配置（未来）

### 场景 #499 · Widget · 展望：inline 编辑（P9）
Given widget 内点笔记
When 快速输入
Then inline 添加 quick memo（未来）

---

## P8.Ext.D: AI 问答边界 (#500-#599, 100 条)

### 场景 #500 · AI · 未配置态入口不可点
Given AI 未配置
When 进入 AI Chat
Then 显示 "请先配置 API Key" + 跳设置

### 场景 #501 · AI · 已配置态问答
Given AI 已配置
When 输入 "你好"
Then AI 回复问候

### 场景 #502 · AI · 配置 URL 格式校验
Given 设置里输入 "not-a-url"
When 保存
Then 提示 "URL 格式不正确"

### 场景 #503 · AI · 配置 URL 默认值
Given 未填 URL
When 保存
Then 默认 "https://api.openai.com/v1"

### 场景 #504 · AI · 配置 model 字段
Given 输入 "gpt-4o"
When 保存
Then DataStore 持久化

### 场景 #505 · AI · 配置 apiKey 加密
Given 输入 apiKey
When 保存
Then EncryptedSharedPreferences 存储

### 场景 #506 · AI · 配置 apiKey 遮掩
Given 已保存
When 查看设置
Then 显示 "sk-****...****"

### 场景 #507 · AI · apiKey 显示/隐藏 toggle
Given apiKey 输入框
When 点眼睛图标
Then 明文/密文切换

### 场景 #508 · AI · 测试连接按钮
Given 填写完整
When 点 "测试"
Then 发送 1 token 请求，返回成功

### 场景 #509 · AI · 测试失败
Given apiKey 错误
When 点测试
Then snackbar "401 Unauthorized"

### 场景 #510 · AI · FLAG_SECURE 生效
Given AI 设置页
When 截屏
Then 截屏内容为黑屏（保护 apiKey）

### 场景 #511 · AI · 聊天 · 单条消息
Given 空对话
When 发 1 条
Then AI 回复，列表 2 条

### 场景 #512 · AI · 聊天 · 多轮对话
Given 已 5 轮
When 发新消息
Then AI 记忆前文上下文

### 场景 #513 · AI · 聊天 · 发送空消息
Given 输入框为空
When 点发送
Then 按钮 disabled 或不响应

### 场景 #514 · AI · 聊天 · 发送超长消息
Given 输入 10000 字
When 发送
Then 正常发送（或分段）

### 场景 #515 · AI · 聊天 · 显示时间戳
Given 消息列表
When 渲染
Then 每条有 HH:mm 时间

### 场景 #516 · AI · 聊天 · 区分用户/AI 气泡
Given 消息列表
When 渲染
Then 用户右侧、AI 左侧

### 场景 #517 · AI · 聊天 · 滚动到最新
Given 新消息到达
When 渲染
Then 自动滚到底

### 场景 #518 · AI · 聊天 · 错误消息显示
Given 网络失败
When 发送
Then 显示错误气泡 + 重试按钮

### 场景 #519 · AI · 聊天 · 错误后重试
Given 错误气泡
When 点重试
Then 重发同消息

### 场景 #520 · AI · 聊天 · 发送中 loading
Given 刚发送
When 等响应
Then 显示打字指示 "..."

### 场景 #521 · AI · 聊天 · 响应期间再发
Given 正在等 AI 响应
When 再点发送
Then 禁用或排队

### 场景 #522 · AI · 聊天 · 响应超时
Given AI 30s 未响应
When 超时
Then 显示 "请求超时" 错误

### 场景 #523 · AI · 聊天 · 取消请求
Given 正在等响应
When 点取消
Then 放弃请求

### 场景 #524 · AI · 聊天 · 清空对话
Given 多轮对话
When 点 "清空"
Then 对话清零

### 场景 #525 · AI · Context 模式 NONE
Given 对话设 NONE
When 发送消息
Then 不附带笔记上下文

### 场景 #526 · AI · Context 模式 CURRENT_NOTE
Given 从笔记 X 进入 AI
When 发送消息
Then 附带 X.body 作 system prompt

### 场景 #527 · AI · Context 模式 ALL_NOTES
Given 设 ALL_NOTES
When 发送
Then 附带前 N 条笔记 body

### 场景 #528 · AI · Context 切换
Given 对话中
When 切换 mode chip
Then 下次消息用新 mode

### 场景 #529 · AI · Context 字符预算
Given ALL_NOTES 超过 15000 字
When 构建 prompt
Then 截断至 15000 字

### 场景 #530 · AI · 错误 · 401 UNAUTHORIZED
Given apiKey 错
When 发送
Then ErrorCode.UNAUTHORIZED "API Key 无效"

### 场景 #531 · AI · 错误 · 403 FORBIDDEN
Given apiKey 权限不足
When 发送
Then ErrorCode.UNAUTHORIZED

### 场景 #532 · AI · 错误 · 429 RATE_LIMITED
Given rate limit
When 发送
Then 显示 "请求过于频繁"（P7.1 改进为专属 mapping）

### 场景 #533 · AI · 错误 · 500 server error
Given AI 服务故障
When 发送
Then 显示 "AI 服务不可用"

### 场景 #534 · AI · 错误 · 5xx 通用
Given 502/503/504
When 发送
Then 统一显示 "AI 服务不可用"

### 场景 #535 · AI · 错误 · 网络错误
Given 飞行模式
When 发送
Then ErrorCode.NETWORK "网络连接失败"

### 场景 #536 · AI · 错误 · 不泄露 apiKey
Given 5xx 错误
When 错误消息显示
Then 不包含 apiKey 或 body

### 场景 #537 · AI · 错误 · 不泄露笔记内容
Given AI 返回错误含笔记片段
When 错误消息显示
Then 只显示 generic message

### 场景 #538 · AI · 入口 · AI tab
Given 底部 tab
When 点 AI
Then 打开空 AI Chat（NONE 模式）

### 场景 #539 · AI · 入口 · 笔记长按 "问 AI"
Given 笔记长按菜单
When 选 "问 AI"
Then 进 AI Chat with noteUid (CURRENT_NOTE 模式)

### 场景 #540 · AI · 入口 · 设置中跳
Given 设置页 AI 块
When 点 "立即对话"
Then 跳 AI Chat

### 场景 #541 · AI · 入口 · deep link
Given 分享出 AI 链接
When 点开
Then 恢复上次对话

### 场景 #542 · AI · 自定义 provider · OpenAI
Given URL 填 api.openai.com
When 发送
Then 走 /v1/chat/completions

### 场景 #543 · AI · 自定义 provider · DeepSeek
Given URL api.deepseek.com
When 发送
Then 走 /v1/chat/completions

### 场景 #544 · AI · 自定义 provider · Azure
Given URL 含 azure
When 发送
Then 走 Azure chat API

### 场景 #545 · AI · 自定义 provider · ollama
Given URL localhost:11434
When 发送
Then 走 ollama /api/chat

### 场景 #546 · AI · 自定义 provider · OpenRouter
Given URL openrouter.ai
When 发送
Then 成功

### 场景 #547 · AI · Provider 切换清空对话
Given 换 URL
When 保存
Then 旧对话保留但新对话用新 provider

### 场景 #548 · AI · 多模型选择
Given provider 支持多模型
When 对话
Then 用 model 字段指定

### 场景 #549 · AI · 响应 · 单段文本
Given 问 "2+2"
When AI 响应
Then 气泡显示 "4"

### 场景 #550 · AI · 响应 · markdown 格式
Given AI 返回含 **bold**
When 渲染
Then 粗体展示

### 场景 #551 · AI · 响应 · 代码块
Given AI 返回 ```python
When 渲染
Then 代码块样式 + 复制按钮

### 场景 #552 · AI · 响应 · 列表
Given AI 返回 "- a\n- b"
When 渲染
Then 显示 bullet list

### 场景 #553 · AI · 响应 · 链接
Given AI 返回含 URL
When 渲染
Then URL 可点击

### 场景 #554 · AI · 响应 · 多段
Given AI 返回 3 段
When 渲染
Then 分段显示

### 场景 #555 · AI · 响应 · emoji
Given AI 返回含 emoji
When 渲染
Then emoji 正确显示

### 场景 #556 · AI · 响应 · 长文本截断
Given AI 返回 5000 字
When 渲染
Then 显示 "展开" 按钮

### 场景 #557 · AI · 响应 · 展开后
Given 点展开
When 渲染
Then 全文显示

### 场景 #558 · AI · 响应 · 复制单条
Given 消息气泡
When 长按
Then 菜单 "复制"

### 场景 #559 · AI · 响应 · 复制整段对话
Given 对话列表
When 长按标题
Then 菜单 "复制全部对话"

### 场景 #560 · AI · 展望：流式响应（P8.1）
Given AI SSE 响应
When 流式到达
Then 逐字显示

### 场景 #561 · AI · 展望：持久化对话（P8.1）
Given app 重启
When 进 AI
Then 恢复上次对话

### 场景 #562 · AI · 展望：对话分支（P9）
Given 同一消息
When 重新生成
Then 分支历史

### 场景 #563 · AI · 展望：应用 AI 修改到笔记（P8.1）
Given AI 回复建议
When 点 "应用到笔记"
Then 笔记 body 被替换

### 场景 #564 · AI · 隐私 · 不上传敏感字段
Given 笔记 frontmatter 有 api_key
When 发送到 AI
Then 剥离敏感 key 字段

### 场景 #565 · AI · 隐私 · 未授权不上传
Given 用户未同意上传
When 尝试发送
Then 首次提示用户同意

### 场景 #566 · AI · 隐私 · 退出清内存
Given 对话含敏感
When 退出 app
Then 内存中对话清零

### 场景 #567 · AI · 费用 · 显示预估
Given 发送前
When 输入长消息
Then 显示 "约消耗 X tokens"（可选）

### 场景 #568 · AI · 费用 · 本次对话累计
Given 对话
When 查看 info
Then 显示 "累计 X tokens"

### 场景 #569 · AI · 费用 · 月度统计
Given 月初累计
When 查看
Then 显示 "本月 Y tokens"（可选）

### 场景 #570 · AI · 不显示系统 prompt
Given CURRENT_NOTE 模式
When 查看对话
Then system prompt 不显示在 UI

### 场景 #571 · AI · 切换笔记后 context 变化
Given 正在 CURRENT_NOTE 对话
When 返回列表选另一笔记进 AI
Then context 换为新笔记

### 场景 #572 · AI · 聊天 · 发送后输入框清空
Given 输入消息
When 点发送
Then 输入框清空

### 场景 #573 · AI · 聊天 · 粘贴发送
Given 剪贴板有文本
When 粘贴到输入框
Then 可编辑后发送

### 场景 #574 · AI · 聊天 · 键盘发送键
Given 输入完
When 按键盘 enter
Then 发送（或换行，按配置）

### 场景 #575 · AI · 聊天 · Shift+Enter 换行
Given 输入中
When Shift+Enter
Then 换行不发送

### 场景 #576 · AI · 聊天 · 输入框自适应高度
Given 输入多行
When 展开
Then 输入框自动变高（最多 5 行）

### 场景 #577 · AI · 聊天 · 历史消息加载
Given 对话很长
When 滚到顶
Then 加载更早消息（如持久化后）

### 场景 #578 · AI · 聊天 · 空对话态
Given 首次进 AI Chat
When 无消息
Then 显示 "向 AI 提问关于你的笔记"

### 场景 #579 · AI · 聊天 · 建议 prompt
Given 空对话
When 显示建议
Then 列 3-5 个预设 prompt

### 场景 #580 · AI · 聊天 · 点建议 prompt
Given 建议列表
When 点一条
Then 自动填入输入框

### 场景 #581 · AI · Context chip · 显示当前模式
Given 模式 CURRENT_NOTE
When 查看 chip
Then "基于当前笔记" 高亮

### 场景 #582 · AI · Context chip · 切 NONE
Given chip 列表
When 点 "不带上下文"
Then 切 NONE

### 场景 #583 · AI · Context chip · 切 ALL_NOTES
Given chip 列表
When 点 "基于所有笔记"
Then 切 ALL_NOTES，加载笔记

### 场景 #584 · AI · Context chip · disable 态
Given 无当前笔记
When chip 渲染
Then "基于当前笔记" disabled

### 场景 #585 · AI · Context · 笔记删除后退化
Given CURRENT_NOTE 模式，笔记被删
When 发送
Then 自动切 NONE + 提示

### 场景 #586 · AI · 错误 · body 格式错误
Given AI 返回非 JSON
When 解析
Then 显示 "响应格式错误"

### 场景 #587 · AI · 错误 · choices 空
Given AI 返回 choices=[]
When 解析
Then 显示 "空响应"

### 场景 #588 · AI · 错误 · stream 中断
Given 流式响应中断
When 处理
Then 保留已收到部分 + 错误提示

### 场景 #589 · AI · 配置保护 · apiKey 不进 log
Given 日志输出
When 查看
Then log 不含 apiKey 明文

### 场景 #590 · AI · 配置保护 · error 不含 apiKey
Given 请求失败
When 错误 toast
Then 不含 apiKey

### 场景 #591 · AI · 配置保护 · crash report 不含 apiKey
Given crash
When 上传 crash
Then apiKey 被剥离

### 场景 #592 · AI · 请求 header · Authorization
Given 已配置
When 发送
Then Header "Authorization: Bearer <key>"

### 场景 #593 · AI · 请求 header · User-Agent
Given 发送
When header
Then "User-Agent: MemoApp/0.12.0-p8"

### 场景 #594 · AI · 请求 body · messages 结构
Given 多轮
When 构建 body
Then messages=[{role:system,content:...},{role:user,...},{role:assistant,...},...]

### 场景 #595 · AI · 请求 body · stream=false
Given P7 版本
When 构建 body
Then stream:false

### 场景 #596 · AI · 请求 body · 无 max_tokens
Given 默认
When 构建
Then 不传 max_tokens（用 provider 默认）

### 场景 #597 · AI · 请求 body · temperature 默认
Given 默认
When 构建
Then 不传 temperature（用 provider 默认）

### 场景 #598 · AI · 未来：RAG embedding（P9）
Given 笔记 embedding 库
When 发送
Then RAG 检索最相关笔记作 context

### 场景 #599 · AI · 未来：本地模型（P9）
Given 用户选本地模型
When 发送
Then 走 on-device LLM，不联网

---

## P8.Ext.E: 日历与标签 (#600-#699, 100 条)

### 场景 #600 · 日历 · 默认月视图
Given 进入 Calendar tab
When 默认
Then 显示当月

### 场景 #601 · 日历 · 切换月
Given 月视图
When 左滑/右滑
Then 切换到上/下月

### 场景 #602 · 日历 · 跳到今天
Given 已切到其他月
When 点 "今天"
Then 回到当月当天

### 场景 #603 · 日历 · 长按日期
Given 月视图
When 长按某天
Then 弹出 "查看该天笔记"

### 场景 #604 · 日历 · 选中日期
Given 月视图
When 点某天
Then 高亮选中，下方显示该天笔记

### 场景 #605 · 日历 · 有笔记的日期标记
Given 某天有笔记
When 月视图
Then 该天有小点 indicator

### 场景 #606 · 日历 · 有多条笔记的日期
Given 某天 5 条
When 月视图
Then 用更大/多点表示

### 场景 #607 · 日历 · 当天高亮
Given 月视图
When 渲染
Then 今天有特殊边框

### 场景 #608 · 日历 · 周视图切换
Given 月视图
When 切到周视图
Then 显示本周 7 天

### 场景 #609 · 日历 · 日视图切换
Given 月视图
When 切到日视图
Then 显示今天所有笔记列表

### 场景 #610 · 日历 · 年视图
Given 月视图
When 上滑到年
Then 12 宫格年视图

### 场景 #611 · 日历 · 周首日设置
Given 设置周一为首日
When 渲染
Then 每周从周一开始

### 场景 #612 · 日历 · events 显示
Given 某天有 event
When 选中
Then 列出该天 events

### 场景 #613 · 日历 · 跨天 event
Given event 持续 3 天
When 月视图
Then 3 天都显示 event 标记

### 场景 #614 · 日历 · event 颜色区分
Given 不同标签的 event
When 渲染
Then 不同颜色

### 场景 #615 · 日历 · 添加 event
Given 日视图
When 点 + 按钮
Then 打开 event 编辑

### 场景 #616 · 日历 · 编辑 event
Given event 显示
When 点击
Then 打开编辑界面

### 场景 #617 · 日历 · 删除 event
Given event 显示
When 长按删除
Then event 移除

### 场景 #618 · 日历 · event 提醒
Given event 设提醒
When 到时间
Then 通知

### 场景 #619 · 日历 · 重复 event
Given event 设每周
When 保存
Then 生成重复系列

### 场景 #620 · 日历 · 跨月显示
Given 上月末/下月初
When 月视图
Then 邻月日期灰色显示

### 场景 #621 · 日历 · 农历显示
Given 设置开启农历
When 渲染
Then 每格显示农历

### 场景 #622 · 日历 · 节假日标记
Given 设置开启
When 渲染
Then 节假日有特殊标

### 场景 #623 · 日历 · 加载性能
Given 5000 条笔记跨 10 年
When 切换月
Then 切换流畅不卡

### 场景 #624 · 日历 · 搜索
Given 日历有搜索
When 输入关键词
Then 高亮匹配日期

### 场景 #625 · 日历 · 导出
Given 月视图
When 点导出
Then 导出 ics 或图片

### 场景 #626 · 日历 · 分享月视图
Given 月视图
When 点分享
Then 生成图片分享

### 场景 #627 · 日历 · 打印
Given 月视图
When 点打印
Then 系统打印 sheet

### 场景 #628 · 日历 · 滑动切换手势敏感度
Given 设置调节
When 滑动
Then 按敏感度触发

### 场景 #629 · 日历 · 无笔记日期样式
Given 该天无笔记
When 渲染
Then 无 indicator 小点

### 场景 #630 · 标签 · 笔记添加标签
Given 新建笔记
When 添加 "work"
Then 笔记含标签

### 场景 #631 · 标签 · 笔记多标签
Given 新建
When 添加 "work"、"urgent"
Then 笔记含 2 标签

### 场景 #632 · 标签 · 编辑时加标签
Given 已有笔记
When 添加标签
Then 立即同步

### 场景 #633 · 标签 · 删除标签
Given 笔记有标签
When 移除
Then 笔记无此标签

### 场景 #634 · 标签 · 标签自动补全
Given 输入 "wo"
When 补全
Then 显示 "work"

### 场景 #635 · 标签 · 标签颜色
Given 标签 work
When 用户设红色
Then 标签显示红色

### 场景 #636 · 标签 · 标签列表
Given 标签 tab
When 进入
Then 列表所有标签

### 场景 #637 · 标签 · 标签使用次数
Given 标签列表
When 查看
Then "work(12)"

### 场景 #638 · 标签 · 标签搜索
Given 标签列表
When 搜索
Then 过滤标签

### 场景 #639 · 标签 · 标签排序
Given 标签列表
When 切换排序
Then 按字母/使用频率

### 场景 #640 · 标签 · 点击标签看笔记
Given 标签列表
When 点标签
Then 筛选出该标签笔记

### 场景 #641 · 标签 · 重命名标签
Given 标签 "work"
When 重命名 "工作"
Then 所有笔记标签同步改

### 场景 #642 · 标签 · 合并标签
Given 标签 "work"、"工作"
When 选合并
Then 合为一个

### 场景 #643 · 标签 · 删除标签
Given 标签 "work"
When 删除
Then 所有笔记移除该标签

### 场景 #644 · 标签 · 标签空列表
Given 无标签
When 进 tab
Then 空态 "还没有标签"

### 场景 #645 · 标签 · 中文标签
Given 输入 "工作"
When 保存
Then 正常

### 场景 #646 · 标签 · emoji 标签
Given 输入 "🔥"
When 保存
Then 正常

### 场景 #647 · 标签 · 超长标签
Given 输入 100 字符
When 保存
Then 截断或警告

### 场景 #648 · 标签 · 含空格标签
Given "work stuff"
When 保存
Then 保留空格

### 场景 #649 · 标签 · 含特殊字符
Given "work-urgent"
When 保存
Then 正常

### 场景 #650 · 标签 · 标签同步到 frontmatter
Given 笔记加标签
When 保存
Then frontmatter 含 tags:[work]

### 场景 #651 · 标签 · 从 frontmatter 读标签
Given pull 文件
When 解析
Then 标签恢复

### 场景 #652 · 标签 · Obsidian 兼容（#tag）
Given body 含 "#work"
When 解析
Then 识别为标签

### 场景 #653 · 标签 · Obsidian 嵌套标签（#a/b）
Given body 含 "#work/urgent"
When 解析
Then 嵌套标签树

### 场景 #654 · 标签 · 标签统计图表
Given 标签列表
When 查看 "统计"
Then 饼图/条形图

### 场景 #655 · 标签 · 批量打标签
Given 选中多条笔记
When 选 "打标签"
Then 批量添加

### 场景 #656 · 标签 · 批量移除标签
Given 选中多条
When 选 "移除 work 标签"
Then 批量移除

### 场景 #657 · 标签 · 快捷标签栏
Given 编辑笔记
When 打开底栏
Then 显示最常用 5 标签

### 场景 #658 · 标签 · 自动建议标签
Given 输入笔记内容
When AI 建议
Then 显示 "建议 #work" 按钮（未来）

### 场景 #659 · 标签 · 导出标签清单
Given 标签 tab
When 点导出
Then 导出 JSON

### 场景 #660 · 标签 · 导入标签清单
Given JSON 文件
When 导入
Then 标签恢复

### 场景 #661 · 日历-标签联动 · 按标签过滤日历
Given 日历
When 筛标签 work
Then 只显示带 work 的日期

### 场景 #662 · 日历-标签联动 · 日期 + 标签
Given 组合筛选
When 查看
Then 交集

### 场景 #663 · 日历 · 今日点击进日视图
Given 月视图
When 双击今天
Then 进日视图

### 场景 #664 · 日历 · 日视图时间轴
Given 日视图
When 渲染
Then 24 小时时间轴，笔记按时间定位

### 场景 #665 · 日历 · 时间轴拖动笔记
Given 日视图时间轴
When 拖动笔记
Then 改变时间

### 场景 #666 · 日历 · 时间轴缩放
Given 时间轴
When 双指缩放
Then 时间粒度放大/缩小

### 场景 #667 · 日历 · 时区切换适应
Given 切时区
When 日历渲染
Then 按新时区

### 场景 #668 · 日历 · 夏令时跳变
Given DST 生效
When 该天渲染
Then 23 小时 or 25 小时

### 场景 #669 · 日历 · 跨年显示
Given 12 月切到 1 月
When 滑动
Then 年标题变更

### 场景 #670 · 日历 · 本年 heatmap
Given 年视图
When 渲染
Then 每天颜色深浅按笔记数（类似 GitHub）

### 场景 #671 · 日历 · 连续打卡计数
Given 连续 30 天记笔记
When 查看 stats
Then 显示 "🔥 连续 30 天"

### 场景 #672 · 日历 · 缺勤提示
Given 昨天无笔记
When 今天打开
Then 提示 "昨天没记，今天继续？"

### 场景 #673 · 日历 · 月统计
Given 月底
When 查看月统计
Then 显示 "本月 50 条笔记"

### 场景 #674 · 日历 · 年统计
Given 年底
When 查看
Then "今年 1200 条"

### 场景 #675 · 日历 · 导出月 PDF
Given 月视图
When 点导出 PDF
Then 生成月历 PDF

### 场景 #676 · 日历 · 月打印预览
Given 月视图
When 点打印
Then 预览打印样式

### 场景 #677 · 日历 · widget 显示今天日期
Given Today widget
When 渲染
Then 顶部 "4 月 24 日"

### 场景 #678 · 日历 · 周视图切换动画
Given 月视图
When 切周视图
Then 平滑过渡动画

### 场景 #679 · 日历 · 深色模式
Given 系统深色
When 日历渲染
Then 深色主题

### 场景 #680 · 日历 · RTL 布局
Given 阿拉伯语
When 渲染
Then 日期方向镜像

### 场景 #681 · 日历 · 无障碍朗读
Given talkback
When 焦点在日期
Then 朗读 "4 月 24 日，有 3 条笔记"

### 场景 #682 · 日历 · 无障碍导航
Given talkback
When 滑动切换
Then 朗读 "上一月" / "下一月"

### 场景 #683 · 日历 · Google Calendar 导入（P9）
Given 授权 Google 日历
When 同步
Then 拉入 Google 事件

### 场景 #684 · 日历 · Google Calendar 导出（P9）
Given 笔记标记为 event
When 导出
Then 推到 Google 日历

### 场景 #685 · 日历 · 订阅日历链接
Given 输入 ics URL
When 订阅
Then 显示订阅事件

### 场景 #686 · 日历 · 本地 ics 导入
Given 选 .ics 文件
When 导入
Then 事件加入

### 场景 #687 · 日历 · 多日历视图（P9）
Given 个人 + 工作日历
When 查看
Then 叠加显示

### 场景 #688 · 日历 · 日历配色切换
Given 设置
When 选配色
Then 日历主题换

### 场景 #689 · 日历 · 日期点击 haptic
Given 设置开 haptic
When 点日期
Then 轻振动反馈

### 场景 #690 · 日历 · 快速跳转日期
Given 日历顶部标题
When 点
Then 弹日期选择器

### 场景 #691 · 日历 · 关键日期标记
Given 生日 / 纪念日
When 设置
Then 日历特殊 icon

### 场景 #692 · 日历 · 提醒整合
Given event 有提醒
When 到时间
Then Push 通知

### 场景 #693 · 日历 · 重复 event 编辑
Given 重复 event
When 编辑某次
Then 选 "仅此次" / "所有"

### 场景 #694 · 日历 · event 拖动改日期
Given event 显示
When 长按拖动
Then 改到新日期

### 场景 #695 · 日历 · event 改颜色
Given event
When 编辑颜色
Then 立即更新

### 场景 #696 · 日历 · event 附件
Given event 编辑
When 加附件
Then 支持图片/链接

### 场景 #697 · 日历 · event 共享
Given event
When 分享
Then 生成邀请链接（未来）

### 场景 #698 · 日历 · 日历备份
Given 设置
When 点备份
Then 导出 events 数据

### 场景 #699 · 日历 · 日历还原
Given 备份文件
When 还原
Then events 恢复
