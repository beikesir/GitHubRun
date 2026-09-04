# 局域网快传 v3.0

电脑（浏览器扩展）↔ 手机（Android App）在同一局域网内互传文本与图片。

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
