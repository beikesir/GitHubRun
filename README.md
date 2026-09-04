# 局域网快传 v3.1

电脑（浏览器扩展）↔ 手机（Android App）在同一局域网内互传文本与图片。

## v3.1 更新

### 浏览器端：面板改为手动唤起
- **默认隐藏**：打开新页面或刷新不再自动弹出面板，不干扰正常浏览
- **两种唤起方式**（任选其一）
  - 点扩展图标 → 弹窗底部「打开/收起页面面板」
  - 快捷键 `Alt+Shift+L`（可在 `chrome://extensions/shortcuts` 里改）
- **收起**：面板右上角 ✕
- 仍可在配置里勾选「新消息自动展开面板」，按需恢复自动弹出
- 浏览器内部页（chrome://、扩展商店等）无法注入脚本，点击时会提示

### 安卓端：完成设备身份注入
- `LanShareApp` 启动时把 `DeviceIdentity.sender()` 注入全局连接管理器
  → 此后每条发出的消息都携带 `sender{id,name}`，同频道内可区分发送者
- 配置对话框新增「设备名」输入框；留空则恢复默认机型名
- 改名即时生效，无需重连

## v3.0 更新（第二批）
- **端到端加密**：设了口令的频道，消息用 AES-GCM 加密，中继只搬运密文
- **设备身份**：消息带发送者名，🔒 标记加密消息，口令不符显示「无法解密」
- **中继持久化**：历史落盘 `.relay-data/history.json`，重启不丢；新客户端补发最近 50 条（最多 5 张图）
- **频道鉴权与限流**：口令校验、单条上限 8MB、60 秒内最多 100 条
- **安卓端**：图片全屏查看（双指缩放/拖动）、长按保存相册或复制文本、频道发现

## v2.0 重大更新

### 浏览器端：从油猴改为浏览器扩展（MV3）
**为什么要改**：油猴脚本在每个标签页注入一份，开几个标签就有几个实例、
几条 WebSocket 连接 → 消息重复、状态不一致、历史不共享。

改为扩展后，连接上移到 **Service Worker 后台全局唯一**：

```
标签页1 ┐
标签页2 ├─ content.js (纯UI) ──port──┐
弹窗    ┘                            ├─ background.js ── 唯一一条 WebSocket ── 中继
                                     ┘
```

- **单例连接**：无论开多少标签页，只有一条 WebSocket
- **消息共享**：所有标签页看到同一份历史；历史存 chrome.storage
- **桌面通知**：新消息系统通知，无需打开面板
- **可靠下载**：用 chrome.downloads 替代 GM_download，兼容所有浏览器

### 安卓端：修复第一批痛点
1. **单例连接**：`LanShareApp` 持有唯一 WebSocketManager，界面与分享共用
   → 修掉「分享的图变成他人消息」
2. **图片毫秒命名**：`lan_20260903_143025_123.jpg`，同秒多图不再覆盖
3. **前台服务 + 通知**：`RelayService` 常驻保活，后台也能收消息并弹通知；
   收到图片自动存相册（Pictures/LanShare）

## 目录结构

```
extension/          浏览器扩展（MV3）
  manifest.json
  background.js     单例核心：连接/路由/存储/通知/下载
  content.js        页面面板（纯 UI，无连接）
  content.css
  popup.html/js/css 工具栏弹窗
  icon16/48/128.png

app/src/main/       Android 应用
  java/.../LanShareApp.kt       Application 单例
  java/.../WebSocketManager.kt  连接管理（多监听器）
  java/.../RelayService.kt      前台服务 + 通知 + 存图
  java/.../MainActivity.kt      聊天界面
  java/.../ShareActivity.kt     系统分享入口
  java/.../DeviceIdentity.kt    设备身份（唯一 ID + 可自定义名称）
  java/.../CryptoHelper.kt      AES-GCM 加解密（PBKDF2 派生）
  java/.../GallerySaver.kt      图片存入相册
  java/.../ImageViewerActivity.kt 全屏查看（手势缩放）

relay/
  relay-standalone.js  中继（零依赖，修复大消息）
  启动中继.bat / .command
```

## 使用

### 电脑端
1. 启动中继：双击 `relay/启动中继.bat`
2. 加载扩展：Chrome → `chrome://extensions` → 开「开发者模式」
   → 「加载已解压的扩展程序」→ 选 `extension` 目录
3. 点工具栏图标 → 配置 `ws://电脑IP:8080`
4. 页面右下有面板；也可点弹窗「在页面打开面板」

### 手机端
1. GitHub Actions 构建 APK（上传本目录，跑 Build Debug APK）
2. 安装后打开 → 配置中继地址与聊天室
3. 允许通知权限（后台收消息必需）
4. 相册/截屏 → 分享 → 「局域网快传」→ 图片进默认聊天室

## 协议（三端一致）

```json
{"action":"join","data":"0000"}
{"action":"leave","data":"0000"}
{"channel":"0000","type":"text","text":"你好"}
{"channel":"0000","type":"image","data":"data:image/jpeg;base64,..."}
```

## 已知不足
- 消息历史仅存本地（扩展存 chrome.storage，App 存内存），中继重启后不回溯
- 无鉴权，频道号可被枚举；建议仅家庭/可信网络使用
- 扩展依赖 Service Worker 保活，浏览器完全关闭后需重开


---

# v3.0 第二批能力

## 中继
- **历史持久化**：消息写入 `.relay-data/history.json`（每频道 300 条 / 8MB 上限），
  每 5 秒防抖落盘，退出强制保存；**重启中继后历史仍在**
- **频道口令**：`.relay-data/channels.json` 配置，口令错误返回 `auth_failed`
- **流量治理**：单条上限（默认 8MB，可 `--limit` 调）、100 条/60 秒限流、
  接收缓冲 64MB 保护、优雅关闭
- **频道发现**：`GET /channels` 返回频道列表与受保护标记

## 浏览器扩展
- 配置支持**频道口令**，加入时自动携带；口令错误弹窗重输
- 收到服务端补发的历史后**去重合并**（按内容+时间容差 2 秒），按时间正序插入
- 聊天室面板新增「发现」按钮，列出可发现频道，🔒 需口令的会弹窗索取
- 新增「新消息时自动展开面板」开关

## 安卓
- **全屏查看**：点图片打开查看器，双指缩放、拖动平移、双击复位（自实现手势，无第三方库）
  —— 大图通过 `ImageHolder` 内存传递，规避 Intent 1MB 限制导致的 `TransactionTooLargeException`
- **长按保存**：长按图片 → 保存到相册 `Pictures/LanShare`，文件名含毫秒
- **长按复制**：长按文字气泡 → 复制到剪贴板
- **频道发现**：点「发现」从中继拉取频道列表，🔒 频道弹窗输入口令
- 图片保存逻辑统一到 `GallerySaver`，Android 9 以下会广播触发媒体库扫描

## 已知不足（第三批待办）
- 扩展 Service Worker 仍依赖浏览器存活，浏览器完全关闭后收不到消息
- 无端到端加密，`ws://` 明文在不可信网络有嗅探风险
- 历史无清理策略，长期运行 `history.json` 需手动维护
- 多设备同频道无身份区分，无法分辨消息来自哪台设备
