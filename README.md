# 局域网快传 v2.1

电脑（浏览器扩展）↔ 手机（Android App）在同一局域网内互传文本与图片。

## 面板打开方式（重要）

**页面面板默认隐藏，不会随页面加载或刷新自动弹出。**
只有用户主动触发才会出现，三种方式：

| 方式 | 操作 |
|---|---|
| 扩展弹窗 | 点工具栏图标 → 「打开/收起页面面板」 |
| 快捷键 | `Alt + Shift + L` |
| 关闭 | 面板标题栏 `✕` 按钮 |

> 浏览器内部页面（`chrome://`、扩展商店等）无法注入脚本，
> 在此类页面点按钮会提示"此页面无法打开面板"，属正常现象。

如需「收到新消息时自动展开面板」，可在扩展弹窗的配置中勾选
**新消息自动展开面板**（默认关闭）。

## v2.0 架构：浏览器端改为扩展（MV3）

油猴脚本在每个标签页各注入一份独立实例，开 N 个标签就有 N 条
WebSocket 连接 → 消息重复、状态不一致、历史不共享。

改为扩展后，连接上移到 Service Worker，全局唯一：

```
标签页1 ┐
标签页2 ├─ content.js（纯 UI）── port ──┐
弹窗    ┘                                ├─ background.js ── 唯一 WebSocket ── 中继
                                         ┘
```

- **单例连接**：无论开多少标签页，只有一条 WebSocket
- **消息共享**：所有标签页看到同一份历史（存 chrome.storage）
- **桌面通知**：新消息系统通知，无需打开面板
- **可靠下载**：chrome.downloads 替代 GM_download

## 目录结构

```
extension/          浏览器扩展（MV3）
  manifest.json     commands: Alt+Shift+L
  background.js     单例核心：连接/路由/存储/通知/下载
  content.js        页面面板（纯 UI，默认隐藏）
  content.css
  popup.html/js/css 工具栏弹窗
  icon16/48/128.png

app/src/main/       Android 应用
  LanShareApp.kt       Application 单例（唯一连接）
  WebSocketManager.kt  连接管理（多监听器：收/发/状态）
  RelayService.kt      前台服务 + 通知 + 图片存相册
  MainActivity.kt      聊天界面
  ShareActivity.kt     系统分享入口

relay/
  relay-standalone.js  中继（零依赖，修复大消息分片）
  启动中继.bat / .command
```

## 快速开始

### 电脑端
1. 双击 `relay/启动中继.bat` 启动中继
2. Chrome → `chrome://extensions` → 开「开发者模式」
   → 「加载已解压的扩展程序」→ 选 `extension` 目录
3. 点工具栏图标 → 配置 `ws://电脑IP:8080`

### 手机端
1. 上传本目录到 GitHub，Actions 跑 Build Debug APK
2. 安装 → 配置中继地址与聊天室 → 允许通知权限
3. 相册/截屏 → 分享 → 「局域网快传」→ 图片进默认聊天室

## 协议（三端一致）

```json
{"action":"join","data":"0000"}
{"action":"leave","data":"0000"}
{"channel":"0000","type":"text","text":"你好"}
{"channel":"0000","type":"image","data":"data:image/jpeg;base64,..."}
```

## 已知不足
- 消息历史仅存本地，中继重启后不回溯
- 无鉴权，频道号可被枚举；建议仅家庭/可信网络使用
- 扩展依赖 Service Worker 保活，浏览器完全关闭后需重开
