### 场景 #900 · 两设备同改 - A 先 push B 再 pull
Given 设备 A 和设备 B 登录同一账号且显示同一条备忘"买牛奶"
When 设备 A 修改为"买酸奶"并完成 push 到云端，5 秒后设备 B 触发 pull
Then 设备 B 最终显示"买酸奶"，不出现冲突提示

### 场景 #901 · 两设备同改 - 同时 push 后到者覆盖
Given 设备 A 和设备 B 同时离线编辑同一条备忘
When 两台设备几乎同时联网并触发 push，A 在 T 时刻、B 在 T+50ms 时刻
Then 云端保留 B 的版本，A 下次 pull 时看到 B 的内容并收到"已被其他设备更新"的轻提示

### 场景 #902 · 两设备同改 - A push 中途 B pull 到半成品
Given 设备 A 正在上传长备忘内容，设备 B 同一时刻触发 pull
Then 设备 B 要么拿到 A 更新前的完整旧版本，要么拿到 A 更新后的完整新版本，永远不会拿到半截内容

### 场景 #903 · 两设备同改 - 网络分区下各自编辑
Given 设备 A 在地铁断网，设备 B 在家里在线，两台设备从同一基线开始
When A 离线编辑并缓存到本地队列，B 在线编辑并 push 成功，30 分钟后 A 恢复网络
Then A 的本地队列与云端最新版本合并，差异部分以 diff 形式让用户确认

### 场景 #904 · 两设备同改 - A 删除 B 修改
Given 设备 A 删除备忘 X，设备 B 在不知道的情况下修改同一条 X
When 两台设备在 10 秒内分别同步
Then 系统优先保留 B 的修改版本，并向 A 弹出"该备忘已被其他设备更新，是否恢复"

### 场景 #905 · 两设备同改 - 相同字段冲突
Given 设备 A 把标题改为"周一购物"，设备 B 把同一标题改为"周末购物"
When 两边都 push 成功
Then 云端保留后到的版本，冲突记录写入日志，用户首页显示一次"标题已被其他设备修改"提示

### 场景 #906 · 两设备同改 - 不同字段非冲突合并
Given 设备 A 修改标题，设备 B 修改正文，从同一基线分别 push
Then 云端合并后同时保留 A 的新标题和 B 的新正文，无冲突提示

### 场景 #907 · 两设备同改 - 乐观锁版本号校验失败
Given 设备 A 持有版本号 v5 并 push，B 也持有 v5 并稍晚 push
When 云端发现 B 提交时版本号已是 v6
Then 云端拒绝 B 的 push，B 自动拉取最新版本并提示用户重新编辑

### 场景 #908 · 两设备同改 - 时钟偏差下的顺序判断
Given 设备 A 时钟比云端快 3 分钟，设备 B 时钟准确，两台几乎同时编辑
When 分别 push 到云端
Then 云端以服务器接收时间为准排序而非设备本地时间，避免时钟快的设备总是"胜出"

### 场景 #909 · 两设备同改 - 同时删除幂等处理
Given 设备 A 和设备 B 同时删除同一条备忘
When 两个删除请求几乎同时到达云端
Then 云端以幂等方式处理，两台设备最终都看到"已删除"且不报错

### 场景 #910 · 多线程并发 - 同时点两次保存按钮
Given 用户处于编辑页面，点击了保存按钮两次（间隔 50ms）
When 两个协程分别尝试写入本地数据库
Then 只产生一条数据库记录，第二次写入因乐观锁或去重键被合并

### 场景 #911 · 多线程并发 - 保存与删除同时触发
Given 备忘 X 正在后台 save，用户在列表页同时点击删除
When 保存协程写到一半时删除请求到达
Then PathLocker 串行化两个操作，最终状态为"已删除"，保存操作在返回时感知到目标已删除并中止

### 场景 #912 · 多线程并发 - 两个 Widget 同时写同一备忘
Given 桌面两个 Widget 实例显示同一备忘
When 用户在两个 Widget 里几乎同时切换勾选状态
Then 数据库最终状态与最后一次点击一致，两个 Widget 都刷新为一致视图

### 场景 #913 · 多线程并发 - 多协程同时 scan 数据库
Given 3 个后台协程同时触发"全量扫描重建索引"
Then 只有一个协程真正执行，其余协程等待结果并复用，避免重复 IO

### 场景 #914 · 多线程并发 - 写入与备份同时进行
Given 用户正在编辑备忘，同时 WorkManager 触发了每日备份
Then 备份使用事务快照读取，用户的写入不会导致备份文件损坏

### 场景 #915 · 多线程并发 - 主线程与 IO 线程竞态
Given 主线程正在读取最新的备忘列表渲染 RecyclerView，IO 线程正在写入新条目
Then 通过 Flow 冷流或 LiveData 保证 UI 得到一致快照，不出现半更新的列表

### 场景 #916 · 多线程并发 - 多协程同时删除不同备忘
Given 用户长按批量选择 5 条备忘并点击删除
When 5 个删除协程并发执行
Then 5 条全部删除成功或全部失败（事务回滚），不出现部分删除的中间态

### 场景 #917 · 多线程并发 - 并发写入触发 WAL 冲突
Given SQLite 数据库处于 WAL 模式，同一时刻有 3 个写入事务
Then 第一个拿到写锁，其余排队等待，最终 3 个事务都成功提交

### 场景 #918 · 多线程并发 - 协程作用域交叉取消
Given 两个协程分别属于不同 Scope，其中一个被取消
Then 被取消协程不影响另一个协程的正常运行，共享的数据库连接正确释放

### 场景 #919 · 多线程并发 - 同时点击保存与返回
Given 用户点击保存按钮后立即点击返回键
Then 保存协程完成后才真正销毁 Activity，不出现"保存一半就退出"的情况

### 场景 #920 · 快速连续操作 - 双击保存按钮
Given 用户在 100ms 内双击保存按钮
Then Debounce 机制吞掉第二次点击，只触发一次保存

### 场景 #921 · 快速连续操作 - 三击新建按钮
Given 用户在 150ms 内连续点击"新建备忘"按钮 3 次
Then 只创建 1 条新备忘而非 3 条

### 场景 #922 · 快速连续操作 - 10 连击删除
Given 列表中有一条备忘，用户 500ms 内点击它的删除按钮 10 次
Then 备忘被删除且删除后的点击显示 toast "该备忘已删除"而非多次调用后端

### 场景 #923 · 快速连续操作 - 连续切换勾选
Given 用户在 Widget 上 300ms 内连续点击勾选框 8 次
Then 节流到 2 次实际状态切换，最终状态与第 8 次点击一致

### 场景 #924 · 快速连续操作 - 双击编辑进入两次页面
Given 用户双击某条备忘（间隔 80ms）
Then 只进入编辑页面一次，第二次点击被 navigation 去重逻辑吞掉

### 场景 #925 · 快速连续操作 - 快速滑动切换
Given 用户在两个备忘标签之间以每秒 5 次的频率切换
Then UI 不崩溃，最终显示用户停止切换时所在的标签

### 场景 #926 · 快速连续操作 - 连续旋转屏幕
Given 用户在 1 秒内旋转屏幕 4 次（横→竖→横→竖）
Then Activity 正确重建，用户数据不丢失，最终方向与最后一次旋转一致

### 场景 #927 · 快速连续操作 - 同步按钮连击
Given 用户点击"立即同步"按钮 5 次
Then 第一次触发真实 pull，后续 4 次在 2 秒内被 throttle 忽略并显示"同步中"

### 场景 #928 · 快速连续操作 - 快速编辑回删
Given 用户在 200ms 内打字、回删、再打字同一字符 10 次
Then 自动保存协程只在用户停止输入 500ms 后才触发，不频繁写盘

### 场景 #929 · 快速连续操作 - 手势滑动删除再撤销
Given 用户右滑删除后立即点击撤销
When 两次操作间隔 150ms
Then 撤销成功，备忘回到列表，不触发任何云端 delete 请求

### 场景 #930 · 跨日零点切换 - 正好 23:59:59 编辑完成
Given 用户在 23:59:59.500 保存一条备忘
When 保存完成时已经到 00:00:00.200 次日
Then 备忘的创建日期记录为点击保存时刻的日期（23:59 当天）而非完成时刻（次日）

### 场景 #931 · 跨日零点切换 - 零点自动刷新今日列表
Given "今日"过滤视图显示今天的备忘，系统时钟跨过零点
Then UI 自动刷新为新一天的空列表，前一天的备忘移入"昨天"

### 场景 #932 · 跨日零点切换 - 跨日编辑会话
Given 用户 23:50 开始编辑，在 00:05 才点击保存
Then 保存时刻的日期用于"最后修改时间"，创建时间保留 23:50 的原始值

### 场景 #933 · 跨日零点切换 - 定时提醒跨零点
Given 用户设置提醒在"每天 00:00"
Then 提醒在真实的 00:00:00 触发一次，不重复触发两次（零点前 999ms 和零点后 1ms）

### 场景 #934 · 时区切换 - 用户飞行从北京到纽约
Given 备忘创建于北京时间 2026-04-24 14:00 （UTC 06:00）
When 用户飞到纽约，系统时区自动切换为 EDT（UTC-4）
Then 备忘时间戳显示为 2026-04-24 02:00 EDT，底层 UTC 不变

### 场景 #935 · 时区切换 - 手动修改设备时区
Given 用户在设置中手动从 UTC+8 切换到 UTC+0
Then 所有备忘时间标签立即重新渲染为新时区，历史数据不被改写

### 场景 #936 · 时区切换 - 跨时区协作共享
Given 设备 A 在东京（UTC+9）创建备忘，设备 B 在伦敦（UTC+0）同步查看
Then A 看到"10:00 创建"，B 看到"01:00 创建"，云端存储为同一 UTC 时间

### 场景 #937 · 时区切换 - 半时区国家（印度 UTC+5:30）
Given 用户设备时区为 Asia/Kolkata (UTC+5:30)
Then 所有时间显示准确包含 30 分钟偏移，不四舍五入到整小时

### 场景 #938 · 时区切换 - 时区切换与定时同步冲突
Given WorkManager 设置每天 03:00 同步，用户从 UTC+8 切到 UTC+0
Then 下一次同步根据新时区重新计算，不会在旧时区的 03:00 触发

### 场景 #939 · DST 夏令时 - 春季跳过 02:00-03:00
Given 美东 2026-03-08 02:00 时钟跳到 03:00
When 用户在 01:30 设置提醒 "02:30 触发"
Then 提醒不丢失，在 03:30 触发（跳过的 1 小时后）

### 场景 #940 · DST 夏令时 - 秋季重复 01:00-02:00
Given 美东 2026-11-01 02:00 时钟回拨到 01:00
When 用户的提醒设置在 01:30
Then 提醒只触发一次，使用 UTC 时间戳去重

### 场景 #941 · DST 夏令时 - DST 起止日创建备忘
Given 用户在 DST 切换日当天中午 12:00 创建备忘
Then 备忘时间戳使用 UTC 存储，本地显示不受当日偏移 1 小时影响

### 场景 #942 · DST 夏令时 - 不遵守 DST 地区（日本）
Given 设备时区为 Asia/Tokyo（日本不实行 DST）
Then 即使其他时区在 DST 切换，日本用户时间显示完全不受影响

### 场景 #943 · DST 夏令时 - 巴西废除 DST
Given 设备时区为 America/Sao_Paulo（2019 年后废除 DST）
Then 即使 tz database 有历史 DST 规则，2020 年后的时间计算不再应用 DST

### 场景 #944 · DST 夏令时 - 跨 DST 边界的排序
Given 备忘 A 创建于 DST 切换前 01:30，备忘 B 创建于切换后"重复"的 01:30
Then 列表按 UTC 时间正确排序 A 在 B 之前，不出现时间错乱

### 场景 #945 · 时区切换 - Chatham 群岛 UTC+12:45
Given 用户设备时区为 Pacific/Chatham (UTC+12:45)
Then 时间显示正确应用 45 分钟偏移

### 场景 #946 · 跨日零点切换 - 阿富汗 UTC+4:30 零点
Given 用户在喀布尔（UTC+4:30）
When 本地时间跨过 00:00
Then "今日"列表按喀布尔本地日期刷新而非 UTC 日期

### 场景 #947 · 闰秒 - 2026-06-30 插入正闰秒
Given 国际时间服务在 2026-06-30T23:59:59 插入 1 秒（变成 23:59:60）
When 用户在该秒内创建备忘
Then 时间戳不重复，使用 NTP 同步后的单调时钟保证唯一性

### 场景 #948 · 闰秒 - 负闰秒处理
Given 未来某次插入负闰秒，23:59:58 直接跳到 00:00:00
Then 定时器不漏触发，基于 UTC 单调时间而非 wall clock 的调度器正常工作

### 场景 #949 · 闰秒 - 闰秒期间的排序稳定性
Given 两条备忘分别创建于闰秒前后 100ms
Then 列表排序正确，不因闰秒注入导致时间戳相等

### 场景 #950 · 闰年 2/29 - 在 2028-02-29 创建备忘
Given 当前日期 2028-02-29（闰年）
When 用户创建备忘并设置"每年今日提醒"
Then 下一年（2029）非闰年时，提醒自动顺延到 2029-02-28 或 2029-03-01（按配置）

### 场景 #951 · 闰年 2/29 - 百年闰年规则
Given 2100 年（非闰年，因为能被 100 整除但不能被 400 整除）
Then 日期选择器不允许选择 2100-02-29，若从旧备忘继承该日期则自动归一化

### 场景 #952 · 闰年 2/29 - 2/29 周年提醒
Given 用户在 2024-02-29 创建"结婚纪念日"提醒
Then 2025/2026/2027 的 2/28 或 3/1 触发，2028-02-29 准时触发

### 场景 #953 · Y2K38 - 32 位时间戳溢出
Given 当前时间接近 2038-01-19 03:14:07 UTC
Then 应用使用 64 位 Long 存储时间戳，不溢出为负数

### 场景 #954 · Y2K38 - 超过 2038 的未来提醒
Given 用户设置"2040-01-01 提醒我"
Then 提醒正确存储和触发，不因 32 位溢出丢失

### 场景 #955 · Y2K38 - 旧数据库字段升级
Given 旧版本数据库使用 Int32 时间戳
Then 升级脚本将所有字段迁移到 Int64，无数据丢失

### 场景 #956 · WorkManager 调度竞争 - pull 和 push 同时到达窗口
Given WorkManager 设置每 15 分钟 pull，用户手动触发 push，两者在同一秒内触发
Then 通过共享的同步 Mutex 串行化，先 pull 再 push 或反之，不同时操作云端

### 场景 #957 · WorkManager 调度竞争 - 重复入队同名任务
Given 代码两处同时 enqueueUniqueWork 名称相同的任务
Then 使用 ExistingWorkPolicy.KEEP 确保只执行一次

### 场景 #958 · WorkManager 调度竞争 - 任务链并发
Given 用户触发 backup 链式任务，同时定时 sync 任务被触发
Then 两个独立任务链不互相阻塞，使用不同的 Tag 区分

### 场景 #959 · WorkManager 调度竞争 - 电量不足延迟执行
Given 定时任务要求 BatteryNotLow，设备电量低
Then 任务进入延迟队列，电量恢复后按入队顺序执行，不出现重复触发

### 场景 #960 · WorkManager 调度竞争 - OneTime 和 Periodic 冲突
Given 同一 UniqueName 先入队 Periodic 再入队 OneTime 使用 REPLACE
Then OneTime 执行完后 Periodic 不再继续，符合 REPLACE 语义

### 场景 #961 · WorkManager 调度竞争 - 进程被杀后恢复
Given WorkManager 任务执行中进程被系统杀死
Then 进程恢复后任务从头重试，最多重试 10 次（exponential backoff）

### 场景 #962 · WorkManager 调度竞争 - Doze 模式下的维护窗口
Given 设备进入 Doze 深度睡眠
Then 所有 WorkManager 任务延迟到下一个维护窗口统一执行，不互相抢占

### 场景 #963 · WorkManager 调度竞争 - 多个 Constraints 同时满足
Given 任务要求 CONNECTED + UNMETERED + CHARGING
Then 只有三者同时满足时才触发，任一条件变化立即暂停

### 场景 #964 · WorkManager 调度竞争 - 并发 enqueue 导致 DB 锁
Given 100 个并发 enqueue 调用同时发生
Then WorkManager 内部 SQLite 串行化处理，100 个任务全部入队成功

### 场景 #965 · WorkManager 调度竞争 - pull 和定时清理冲突
Given 定时清理任务正在删除已归档数据，此时 pull 任务到达要拉取新数据
Then 清理完成后才开始 pull，或通过分区锁允许两者并行

### 场景 #966 · Coroutine 取消 - Activity 销毁时正在写数据库
Given 用户在编辑页 onSave，数据库写入协程刚启动，用户立即按返回销毁 Activity
Then 使用 viewModelScope，Activity 销毁时 VM 仍持有直到写入完成；或使用 GlobalScope 保证关键写入不中断

### 场景 #967 · Coroutine 取消 - 网络请求中途取消
Given 正在向云端 push 数据，用户取消操作（如按返回键）
Then OkHttp 请求被 cancel，不完整数据不写入云端，本地标记为"待重试"

### 场景 #968 · Coroutine 取消 - 取消后资源释放
Given 协程持有数据库 Cursor，协程被取消
Then finally 块执行 Cursor.close()，无资源泄漏

### 场景 #969 · Coroutine 取消 - 并发子协程级联取消
Given 父协程启动 3 个子协程，父协程被取消
Then 3 个子协程全部取消，无孤儿协程

### 场景 #970 · Coroutine 取消 - supervisorScope 隔离失败
Given 父作用域使用 supervisorScope，其中一个子协程抛异常
Then 其他子协程不受影响继续执行

### 场景 #971 · Coroutine 取消 - NonCancellable 保护关键段
Given 协程在 withContext(NonCancellable) 块内写数据
Then 即使外层被取消，NonCancellable 块内代码仍执行完

### 场景 #972 · Coroutine 取消 - 取消时长耗时操作
Given 协程正在执行 30 秒的大文件压缩
When 用户点击取消
Then 协程在下一个 ensureActive 检查点立即退出，不等 30 秒完成

### 场景 #973 · Coroutine 取消 - 取消信号在暂停函数中传播
Given 协程调用 delay(5000)，此时被取消
Then delay 立即抛 CancellationException，不等 5 秒

### 场景 #974 · Coroutine 取消 - Flow 收集取消
Given UI 正在 collect Flow，Activity 销毁
Then 通过 repeatOnLifecycle(STARTED) 自动取消 collect，不泄漏

### 场景 #975 · Coroutine 取消 - 协程取消与异常传播
Given 协程被取消的同时抛出业务异常
Then CancellationException 优先被抑制，业务异常正确上报

### 场景 #976 · PathLocker 并发 - 同一路径的两个写入
Given 两个协程尝试写同一文件路径
Then PathLocker 让第二个等待第一个完成，文件内容保持一致

### 场景 #977 · PathLocker 并发 - 不同路径并行写入
Given 两个协程写两个不同路径
Then PathLocker 允许并行，不相互阻塞

### 场景 #978 · PathLocker 并发 - 路径规范化后相同
Given 路径 "/data/note.txt" 和 "/data/./note.txt"
Then PathLocker 规范化后识别为同一资源并串行化

### 场景 #979 · PathLocker 并发 - 大小写敏感平台差异
Given 同一路径使用不同大小写 "/Data/Note.txt" vs "/data/note.txt"
Then 在大小写敏感的 ext4 视为不同，在大小写不敏感的 APFS 视为相同，按平台语义锁定

### 场景 #980 · PathLocker 并发 - 锁超时释放
Given 一个持锁协程死锁或卡住超过 30 秒
Then PathLocker 记录日志并强制释放，避免无限阻塞其他请求

### 场景 #981 · PathLocker 并发 - 锁重入
Given 同一协程已持有 path A 的锁，再次请求 path A
Then 可重入获取，无死锁

### 场景 #982 · PathLocker 并发 - 父子路径锁层级
Given 协程 X 持有 "/data/" 的写锁，协程 Y 请求 "/data/note.txt"
Then 明确的层级规则：要么父锁覆盖子路径，要么各自独立，按文档定义行为

### 场景 #983 · PathLocker 并发 - 进程崩溃锁释放
Given 持锁进程崩溃
Then 锁通过 WeakReference 或进程死亡检测自动释放，下次启动不残留

### 场景 #984 · Debounce / throttle - 自动保存 debounce 500ms
Given 用户连续打字，每个按键间隔 100ms
Then 自动保存仅在用户停止打字 500ms 后触发一次

### 场景 #985 · Debounce / throttle - 搜索 throttle 300ms
Given 用户在搜索框连续输入
Then 最多每 300ms 发起一次搜索请求，减少数据库查询压力

### 场景 #986 · Debounce / throttle - 点击按钮 throttle 1 秒
Given 用户连续点击"同步"按钮
Then 1 秒内只执行一次，其余点击提示"正在同步，请稍候"

### 场景 #987 · Debounce / throttle - 滚动事件 throttle 16ms
Given 用户快速滚动列表
Then 滚动回调 throttle 到每帧（~16ms）一次，保证 60fps 不卡顿

### 场景 #988 · Debounce / throttle - debounce 后首次立即触发 (leading edge)
Given 使用 leading + trailing debounce
When 用户第一次点击
Then 立即触发一次，之后的点击收敛到静默期结束后再触发一次

### 场景 #989 · Debounce / throttle - 同步请求 throttle 防重复
Given 两台设备同时快速多次触发同步
Then 客户端 throttle 保证每台设备 2 秒内最多发起一次同步请求

### 场景 #990 · Debounce / throttle - debounce 在取消时清理定时器
Given debounce 等待期间 ViewModel 被清理
Then 待触发的定时器被取消，不在销毁后才触发

### 场景 #991 · Debounce / throttle - 网络重试指数退避
Given 首次 push 失败
Then 1s、2s、4s、8s… 逐次重试，最大间隔 60s，符合指数退避策略

### 场景 #992 · Debounce / throttle - 提醒通知合并 throttle
Given 1 分钟内有 5 个备忘提醒同时触发
Then 合并为一个"您有 5 个新提醒"通知，而非 5 个单独通知轰炸

### 场景 #993 · Debounce / throttle - Widget 刷新 throttle
Given 数据库 1 秒内变化 10 次
Then Widget 只刷新 2 次（每 500ms 最多一次）而非 10 次，节省电量

### 场景 #994 · 异步 UI 状态竞态 - 加载中断
Given 列表显示 loading，用户立即下拉刷新
Then 新请求取代旧请求，旧请求的回调被忽略，不覆盖新数据

### 场景 #995 · 异步 UI 状态竞态 - 快速切换页面
Given 用户在 A 页发起异步请求，立即切换到 B 页
Then A 页请求结果到达时不刷新 B 页 UI，避免跨页渲染

### 场景 #996 · 异步 UI 状态竞态 - Flow collect 竞态
Given UI 同时 collect 两个 Flow，两者几乎同时发射
Then combine 或 zip 正确合并，UI 显示一致快照而非交错值

### 场景 #997 · 异步 UI 状态竞态 - StateFlow 初始值覆盖
Given StateFlow 初始值为 Loading，异步加载完成后发射 Success
When UI 订阅时机在 Loading 和 Success 之间
Then UI 先看到 Loading 瞬间闪烁然后 Success，或通过 distinctUntilChanged 跳过重复

### 场景 #998 · 异步 UI 状态竞态 - 键盘弹出与列表加载同时
Given 用户点击搜索框键盘弹出，同时列表异步返回新数据
Then 列表高度正确适应键盘，不出现键盘覆盖最后一项或列表跳动

### 场景 #999 · 异步 UI 状态竞态 - 多次 setState 合并
Given ViewModel 在 100ms 内连续 6 次更新 StateFlow
Then Compose 重组被合并为最多 1-2 次，不渲染 6 次中间态
