# 局域网快传 v3.3

电脑（浏览器扩展）↔ 手机（Android App）在同一局域网内互传文本、图片与任意格式文件。

## 版本记录

### v3.3（本次发布）

三端版本号已统一：中继 `v3.3`／扩展 `3.3.0`／安卓 `3.3`（versionCode 11）。

**修复**

1. **安卓端构建失败：引用私有平台资源**（本次唯一改动点）
   - 报错：`activity_main.xml` 引用 `@android:drawable/ic_menu_attachment`，
     `ic_menu_*` / `ic_dialog_*` / `screen_background_dark_transparent` 均为
     **Android 私有资源**，不允许应用直接引用。
   - 影响范围：全项目共 **7 处**同类引用（主界面 6 处 + 图片查看器 2 处中的重复项），
     构建器只会先撞上第一处，逐处修会连续失败多次。已**一次性全部替换**。
   - 修复：新增 7 个自有矢量图标 `ic_search` / `ic_channels` / `ic_settings` /
     `ic_gallery` / `ic_attach_file` / `ic_send` / `ic_close`，
     以及 `bg_dark_transparent` 半透明背景。均为 24×24 vector，
     配合布局原有的 `android:tint` 自动上色，比位图更清晰。
   - 语义对应：`ic_channels` 用「#」标签形状（呼应数字频道），`ic_attach_file` 用回形针（文件）。

**校验**

- 资源引用：全 `res` 目录扫描，**私有资源 0 处**，`@drawable/` 引用**全部可解析**。
- XML 合法性：全部资源文件 minidom 解析通过。
- 符号核对：`R.drawable` / `R.layout` 在 Kotlin 中的引用全部存在；
  跨文件成员引用已自动化扫描（3 处告警经人工核实为检测正则误报，符号均真实定义）。
- 三端版本号一致性已核对。

## 版本记录

### v3.2

三端版本号已统一：中继 `v3.2`／扩展 `3.2.0`／安卓 `3.2`（versionCode 10）。

**修复**

1. **安卓端连接状态反复横跳**（反馈的实测问题）
   - 根因：`connect()` 仅在「已连接/正在连接」时复用连接。当处于「失败后退避等待」期间，
     外部（界面恢复、前台服务重启）一调用就重置退避计数并强制重建连接，
     表现为状态在「已连接 ↔ 连接失败」间不停切换。
   - 修复：目标未变时，只要「已连接/正在连接/已排重连」任一成立即复用，且**仅在切换地址或频道时**才重置退避。
   - 同时给状态广播加了**去重**：内容相同的状态不再重复推送，消除瞬态抖动。
2. **补齐缺失的 `sendText()`**：界面调用了该方法而网络层并未定义，属编译级缺陷，已按
   `sendImage`/`sendFile` 的同一套三段式（构造负载 → 发送 → 本地回显）补齐。

**能力校验**

- 中继文件传输端到端测试 **7 项全通过**：文件名、MIME、大小、base64 内容完整一致，
  且新客户端加入时历史补发能还原文件名。
- 大消息（200KB）传输测试通过。
- 三端 JS / XML / YAML 语法校验全通过；跨文件符号引用已自动化核对。

### v3.1

- 中继：历史持久化、频道口令鉴权、32MB 单条上限（为传文件准备）、频道发现接口。

### v3.0

- 浏览器端改为扩展（MV3）单例；弹窗即完整界面；安卓前台服务与通知。

## 界面入口

- **电脑**：点浏览器工具栏的扩展图标，弹出**完整聊天界面**（消息、发图、频道、配置全在弹窗内）。
  - 不再向网页注入任何面板，不干扰正常浏览。
- **手机**：打开 App 即聊天界面；相册/截屏 → 分享 → 「局域网快传」直达默认聊天室。

## v2.0 架构：浏览器端改为扩展（MV3）

油猴脚本在每个标签页各注入一份独立实例，开 N 个标签就有 N 条 WebSocket 连接，
导致消息重复、状态不一致、历史不共享。

改为扩展后，连接上移到 Service Worker，**全局唯一**：

```
弹窗（popup）── port ──┐
                        ├─ background.js ── 唯一 WebSocket ── 中继
（可扩展多个 UI）──────┘
```

- 单例连接：无论开多少标签页，只有一条连接
- 消息共享：所有 UI 看到同一份历史（chrome.storage）
- 桌面通知 + chrome.downloads 可靠落盘

## v3.0 本次变更

### 1. 浏览器端：页面面板已移除
删除 content script，不再注入任何网页。全部功能收进扩展弹窗：
聊天、发图、频道管理（进入/切换/离开/发现）、配置（地址/设备名/口令/开关）、图片灯箱。

### 2. 安卓端：修复「同一消息重复显示」
**根因**：`doConnect()` 每次直接覆盖 `ws` 字段却不关闭旧连接；
而 `MainActivity`、`RelayService`、`ShareActivity` 各自触发建连，
最多同时存在 3 条连接且都加入了同一频道 —— 中继给每条连接各广播一次，于是消息重复。

修复：
- `doConnect()` 建连前先关闭旧连接
- 新增 `connecting` 单飞标志，并发调用只建一条
- `connect()` 同地址同频道则复用，不重建
- `ensureConnected()` / `isActive()` 收敛判断，三处入口统一
- 防御性去重：按服务端消息 ID 丢弃重复投递

### 3. 安卓端：界面升级
- 建立 `colors.xml` / `themes.xml` 统一深色主题（Tokyo Night 配色）
- 气泡改为带「尾巴」的不对称圆角，文字颜色随气泡背景自适应
- 顶栏改为圆形图标按钮，输入框圆角化，发送改为圆形按钮
- 图片消息圆角裁切，通知改用应用图标并统一主题色
- 连接状态条按状态着色（绿=已连接）

### 4. 中继：补齐持久化
- **消息 ID**：每条消息分配稳定 ID，客户端据此去重（重连补发不再重复渲染）
- **修复口令持久化**：此前的 `saveState()` 只写 `history.json`，
  导致 `channelRules`（频道口令）从未落盘、重启即丢。现两个文件都写，且采用临时文件 + 重命名原子写
- **新增 `setpassword` 认领入口**：此前 `setChannelPassword` 无任何调用点，
  口令功能实际不生效。现在客户端设口令时会向中继认领，口令不一致的客户端将被拒绝加入
- 新增 `GET /history?channel=xxxx` 按需拉取历史
- 历史回补时保留消息 ID 与发送者信息，重启后历史不丢

## 目录结构

```
extension/          浏览器扩展（MV3）
  background.js     单例核心：连接/去重/存储/通知/下载
  popup.html/js/css 弹窗 = 完整聊天界面
  crypto.js         AES-GCM 端到端加密
  manifest.json

app/src/main/       Android 应用
  LanShareApp.kt       Application 单例（唯一连接）
  WebSocketManager.kt  连接管理（单飞/去重/历史解析）
  RelayService.kt      前台服务 + 通知 + 图片存相册
  MainActivity.kt      聊天界面
  ShareActivity.kt     系统分享入口
  res/values/          配色与主题

relay/
  relay-standalone.js  中继（零依赖）
  启动中继.bat / .command
```

## 快速开始

### 电脑端
1. 双击 `relay/启动中继.bat`
2. Chrome → `chrome://extensions` → 开发者模式 → 加载已解压的扩展程序 → 选 `extension/`
3. 点工具栏图标 → ⚙️ 配置 `ws://本机IP:8080`

### 手机端
1. 上传本目录到 GitHub，Actions 构建 Debug APK
2. 安装 → 配置地址与聊天室 → 允许通知
3. 相册/截屏 → 分享 → 「局域网快传」

## 协议

```json
{"action":"join","data":"0000","password":"可选"}
{"action":"leave","data":"0000"}
{"action":"setpassword","data":"1234","password":"新口令"}
{"id":"消息ID","channel":"0000","type":"text","text":"你好","sender":{"id":"x","name":"设备A"}}
{"id":"消息ID","channel":"0000","type":"image","data":"data:image/jpeg;base64,..."}
{"id":"消息ID","channel":"0000","type":"encrypted","nonce":"...","data":"...","sender":{...}}
```

中继补发历史：`{"action":"history","channel":"0000","messages":[...]}`

## 已知不足
- 消息历史存在中继本地磁盘，未做跨设备同步备份
- 无账号体系，频道号仍需手动告知对方
- 扩展弹窗关闭后 Service Worker 可能被浏览器休眠（有 alarms 保活，但非 100%）
- 安卓端图片长按菜单较简单，未做多选与批量转发
