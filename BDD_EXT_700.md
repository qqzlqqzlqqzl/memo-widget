### 场景 #700 · 打开设置页
Given 用户在主界面
When 点击右上角设置图标
Then 进入设置页并显示所有配置项

### 场景 #701 · 从侧边栏打开设置
Given 用户打开侧边栏
When 点击"设置"菜单项
Then 设置页以全屏方式打开

### 场景 #702 · 返回键关闭设置页
Given 用户在设置页
When 按系统返回键
Then 设置页关闭并回到上一界面

### 场景 #703 · 左上角箭头关闭设置
Given 用户在设置页
When 点击左上角返回箭头
Then 设置页关闭

### 场景 #704 · 设置项滚动浏览
Given 设置页有大量配置项
When 用户上下滑动列表
Then 所有设置项可流畅滚动显示

### 场景 #705 · 设置搜索框输入关键字
Given 用户在设置页
When 在搜索框输入"PAT"
Then 只显示包含 PAT 的设置项

### 场景 #706 · 设置搜索无结果
Given 用户在设置页搜索
When 输入不存在的关键字"xyz123"
Then 显示"未找到相关设置"提示

### 场景 #707 · 清空搜索框
Given 搜索框已有内容
When 用户点击清除按钮
Then 搜索框清空并恢复显示所有设置项

### 场景 #708 · 设置分组折叠
Given 设置页按类别分组
When 用户点击分组标题
Then 该分组的子项折叠隐藏

### 场景 #709 · 设置分组展开
Given 设置分组已折叠
When 用户再次点击分组标题
Then 子项展开显示

### 场景 #710 · 设置项点击高亮
Given 用户浏览设置页
When 手指按下某个设置项
Then 该项背景短暂变灰表示按压反馈

### 场景 #711 · 设置项带开关切换
Given 设置项为布尔开关
When 用户点击开关
Then 开关状态切换并即时保存

### 场景 #712 · 设置项修改后立即生效
Given 用户修改字号设置
When 保存后返回主界面
Then 所有文字以新字号显示

### 场景 #713 · 设置页旋转屏幕
Given 用户在设置页
When 旋转设备到横屏
Then 设置页自适应横屏布局

### 场景 #714 · 设置项带副标题描述
Given 设置项有说明文字
When 用户查看该项
Then 主标题下方显示灰色副标题说明

### 场景 #715 · 设置项右侧箭头指示
Given 设置项可进入子页面
When 用户查看该项
Then 右侧显示 > 箭头图标

### 场景 #716 · 设置项当前值预览
Given 设置项有当前选中值
When 用户查看该项
Then 右侧显示当前值文本

### 场景 #717 · 设置页整体主题跟随系统
Given 系统切换深色模式
When 用户打开设置页
Then 设置页自动以深色主题显示

### 场景 #718 · 设置页首次打开引导
Given 用户首次安装应用后打开设置
When 进入设置页
Then 显示新手引导气泡指向关键项

### 场景 #719 · 设置页版本号展示
Given 用户滚动到设置页底部
When 查看底部信息区
Then 显示当前应用版本号

### 场景 #720 · PAT 设置项入口点击
Given 用户在设置页
When 点击"GitHub PAT"设置项
Then 进入 PAT 配置子页面

### 场景 #721 · PAT 输入框可见
Given 用户进入 PAT 配置页
When 查看界面
Then 显示密码样式的 PAT 输入框

### 场景 #722 · PAT 输入遮蔽显示
Given 用户输入 PAT 字符
When 字符输入到输入框
Then 字符以圆点遮蔽显示

### 场景 #723 · PAT 明文切换按钮
Given PAT 输入框内容被遮蔽
When 用户点击眼睛图标
Then PAT 内容切换为明文显示

### 场景 #724 · PAT 明文二次点击遮蔽
Given PAT 以明文显示
When 用户再次点击眼睛图标
Then PAT 重新遮蔽为圆点

### 场景 #725 · PAT 粘贴输入
Given PAT 输入框为空
When 用户长按输入框选择粘贴
Then 剪贴板中的 token 填入输入框

### 场景 #726 · PAT 空值保存拦截
Given PAT 输入框为空
When 用户点击保存按钮
Then 显示"PAT 不能为空"错误提示

### 场景 #727 · PAT 格式校验
Given 用户输入格式错误的 PAT
When 点击保存
Then 显示"PAT 格式不正确"错误提示

### 场景 #728 · PAT 保存成功
Given 用户输入有效 PAT
When 点击保存按钮
Then 显示"PAT 已保存"提示并返回设置页

### 场景 #729 · PAT 测试连接按钮
Given 用户在 PAT 配置页
When 点击"测试连接"按钮
Then 发起 API 请求验证 token

### 场景 #730 · PAT 测试成功
Given PAT 有效且网络正常
When 测试连接
Then 显示"连接成功"绿色提示

### 场景 #731 · PAT 测试失败 401
Given PAT 无效或已过期
When 测试连接
Then 显示"认证失败，请检查 PAT"错误

### 场景 #732 · PAT 测试失败网络错误
Given 网络断开
When 测试 PAT 连接
Then 显示"网络错误，请检查连接"

### 场景 #733 · PAT 加密存储
Given 用户保存 PAT
When 查看本地存储
Then PAT 以加密方式写入 EncryptedSharedPreferences

### 场景 #734 · PAT 权限范围读取
Given 用户保存了 PAT
When 测试连接成功
Then 显示该 PAT 拥有的 scope 列表

### 场景 #735 · PAT 权限不足警告
Given PAT 缺少 repo 权限
When 测试连接
Then 显示"权限不足，需要 repo scope"警告

### 场景 #736 · PAT 过期时间显示
Given PAT 有过期日期
When 用户查看 PAT 详情
Then 显示"过期时间：2026-12-31"

### 场景 #737 · PAT 即将过期提醒
Given PAT 距离过期少于 7 天
When 用户打开应用
Then 顶部横幅提示"PAT 即将过期"

### 场景 #738 · PAT 已过期提示
Given PAT 已经过期
When 用户尝试同步
Then 显示"PAT 已过期，请重新配置"

### 场景 #739 · 组织 SSO 授权流程
Given PAT 属于启用 SSO 的组织
When 用户测试连接
Then 显示"需要为该 PAT 授权 SSO"提示

### 场景 #740 · SSO 授权跳转浏览器
Given 用户点击 SSO 授权按钮
When 应用响应
Then 外部浏览器打开 GitHub SSO 授权页面

### 场景 #741 · PAT 删除操作
Given 用户在 PAT 配置页
When 点击"删除 PAT"按钮
Then 弹出确认对话框

### 场景 #742 · PAT 删除确认
Given 删除确认对话框已显示
When 用户点击"确认删除"
Then PAT 从存储中清除

### 场景 #743 · PAT 删除后同步失效
Given PAT 已删除
When 用户触发同步
Then 显示"请先配置 PAT"提示

### 场景 #744 · PAT 重新输入覆盖旧值
Given 已有旧 PAT
When 用户输入新 PAT 并保存
Then 旧 PAT 被覆盖为新值

### 场景 #745 · PAT 字符数限制
Given 用户输入超长 PAT
When 字符数超过 256
Then 输入框阻止继续输入

### 场景 #746 · PAT 保存按钮禁用态
Given PAT 输入框为空
When 用户查看保存按钮
Then 按钮显示灰色不可点击状态

### 场景 #747 · PAT 保存按钮启用
Given 用户输入有效内容到 PAT 框
When 输入完成
Then 保存按钮变为可点击状态

### 场景 #748 · PAT 配置页取消未保存
Given 用户修改了 PAT 但未保存
When 点击返回
Then 弹出"放弃修改？"确认对话框

### 场景 #749 · PAT 用户名关联显示
Given PAT 测试成功
When 用户查看状态
Then 显示关联的 GitHub 用户名

### 场景 #750 · PAT 复制到剪贴板禁止
Given 用户长按已保存的 PAT 字段
When 尝试复制
Then 复制功能被禁用以保护 token

### 场景 #751 · AI 配置项入口
Given 用户在设置页
When 点击"AI 功能配置"项
Then 进入 AI 配置子页面

### 场景 #752 · AI Provider 切换下拉菜单
Given 用户在 AI 配置页
When 点击 Provider 选择器
Then 显示 OpenAI/Claude/Gemini/自定义 选项

### 场景 #753 · 选择 OpenAI Provider
Given Provider 下拉菜单已展开
When 用户选择"OpenAI"
Then 配置页显示 OpenAI 相关字段

### 场景 #754 · 选择 Claude Provider
Given Provider 下拉菜单已展开
When 用户选择"Claude"
Then 配置页显示 Claude 相关字段

### 场景 #755 · 自定义 Provider URL
Given 用户选择自定义 Provider
When 查看配置字段
Then 显示 Base URL 输入框

### 场景 #756 · AI Base URL 输入
Given 用户在自定义 Provider 下
When 输入 "https://api.example.com/v1"
Then URL 保存到配置

### 场景 #757 · AI Base URL 格式校验
Given 用户输入不含协议头的 URL
When 点击保存
Then 显示"URL 必须以 https:// 开头"错误

### 场景 #758 · AI model 输入框
Given 用户在 AI 配置页
When 查看 model 字段
Then 显示文本输入框和常用 model 建议

### 场景 #759 · AI model 预设选项
Given 用户点击 model 输入框
When 焦点获取
Then 弹出 gpt-4o/claude-3-5-sonnet 等建议列表

### 场景 #760 · 选择预设 model
Given model 建议列表已显示
When 用户点击"gpt-4o"
Then model 字段填充该值

### 场景 #761 · 自定义 model 名称
Given 用户在 model 字段
When 手动输入"my-custom-model"
Then 输入被接受并保存

### 场景 #762 · AI apiKey 输入
Given 用户在 AI 配置页
When 查看 API Key 字段
Then 显示密码样式输入框

### 场景 #763 · AI apiKey 遮蔽
Given 用户输入 apiKey
When 字符输入到框中
Then 以圆点遮蔽显示

### 场景 #764 · AI apiKey 加密存储
Given 用户保存 apiKey
When 查看本地存储
Then apiKey 使用 EncryptedSharedPreferences 加密

### 场景 #765 · AI 配置测试调用
Given 用户填写完整 AI 配置
When 点击"测试 AI"按钮
Then 发起 chat/completions 测试请求

### 场景 #766 · AI 测试成功
Given AI 配置有效
When 测试调用
Then 显示"AI 调用成功"和模型返回内容

### 场景 #767 · AI 测试失败 401
Given apiKey 无效
When 测试 AI
Then 显示"API Key 无效"错误

### 场景 #768 · AI 测试失败 404
Given Base URL 路径错误
When 测试 AI
Then 显示"接口不存在，请检查 URL"错误

### 场景 #769 · AI 切换 Provider 清空旧值
Given 用户从 OpenAI 切换到 Claude
When 确认切换
Then 询问是否保留旧 apiKey

### 场景 #770 · AI 温度参数滑块
Given 用户在 AI 高级配置
When 查看温度设置
Then 显示 0.0-2.0 滑块和当前值

### 场景 #771 · 主题设置入口
Given 用户在设置页
When 点击"外观主题"项
Then 进入主题选择页

### 场景 #772 · 浅色主题选择
Given 用户在主题选择页
When 点击"浅色"选项
Then 应用切换为浅色主题并打勾

### 场景 #773 · 深色主题选择
Given 用户在主题选择页
When 点击"深色"选项
Then 应用切换为深色主题并打勾

### 场景 #774 · 跟随系统主题
Given 用户在主题选择页
When 点击"跟随系统"选项
Then 主题随系统设置自动切换

### 场景 #775 · 主题切换即时预览
Given 用户在主题选择页
When 选择不同主题
Then 设置页本身立即应用新主题

### 场景 #776 · 字号设置入口
Given 用户在设置页
When 点击"字号"项
Then 进入字号选择页

### 场景 #777 · 字号小号选择
Given 用户在字号页
When 选择"小"
Then 全应用文字使用 12sp 基准字号

### 场景 #778 · 字号中号选择
Given 用户在字号页
When 选择"中"
Then 全应用文字使用 14sp 基准字号

### 场景 #779 · 字号大号选择
Given 用户在字号页
When 选择"大"
Then 全应用文字使用 18sp 基准字号

### 场景 #780 · 字号特大号选择
Given 用户在字号页
When 选择"特大"
Then 全应用文字使用 22sp 基准字号

### 场景 #781 · 字号实时预览条
Given 用户在字号选择页
When 拖动字号滑块
Then 顶部示例文字实时随之变化

### 场景 #782 · 语言设置入口
Given 用户在设置页
When 点击"语言"项
Then 进入语言选择页

### 场景 #783 · 切换为简体中文
Given 用户在语言页
When 选择"简体中文"
Then 全应用文案切换为简中

### 场景 #784 · 切换为 English
Given 用户在语言页
When 选择"English"
Then 全应用文案切换为英文

### 场景 #785 · 切换为日本語
Given 用户在语言页
When 选择"日本語"
Then 全应用文案切换为日文

### 场景 #786 · 语言跟随系统
Given 用户在语言页
When 选择"跟随系统"
Then 语言随系统 locale 自动变化

### 场景 #787 · 语言切换重启提示
Given 用户切换语言
When 选择新语言
Then 提示"需要重启应用以完全生效"

### 场景 #788 · 字体族选择
Given 用户在外观设置页
When 点击"字体"
Then 显示系统默认/衬线/等宽 选项

### 场景 #789 · 强调色自定义
Given 用户在主题配置
When 点击"强调色"
Then 显示调色板供选择

### 场景 #790 · 清除缓存数据
Given 用户在设置页
When 点击"清除缓存"
Then 弹出确认对话框显示缓存大小

### 场景 #791 · 确认清除缓存
Given 清除缓存确认框已显示
When 用户点击"确认"
Then 缓存被清空并提示"已释放 25MB"

### 场景 #792 · 清除所有数据
Given 用户在设置页
When 点击"清除所有数据"
Then 弹出红色警告对话框

### 场景 #793 · 清除所有数据二次确认
Given 清除所有数据警告已显示
When 用户点击"确认删除"
Then 再次弹出二次确认对话框

### 场景 #794 · 清除所有数据最终执行
Given 二次确认对话框已显示
When 用户点击"我确认"
Then 所有本地笔记和配置被删除

### 场景 #795 · 导出数据为 JSON
Given 用户在设置页
When 点击"导出数据"
Then 生成 JSON 文件并调起分享菜单

### 场景 #796 · 导入数据选择文件
Given 用户在设置页
When 点击"导入数据"
Then 打开系统文件选择器

### 场景 #797 · 导入数据合并模式
Given 用户选择了导入文件
When 弹出合并策略对话框
Then 显示"覆盖/合并/追加"三选项

### 场景 #798 · 自动备份开关
Given 用户在设置页
When 开启"自动备份到云端"
Then 每日凌晨 3 点自动触发备份

### 场景 #799 · 关于页版本信息
Given 用户在设置页
When 点击"关于"项
Then 显示版本号、构建号、开源协议和反馈入口

