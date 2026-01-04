# 语音输入完整流程分析

## 1. 用户按住按钮（ACTION_DOWN）

**文件**: `MainActivity.java`

```
MainActivity.setupVoiceUI() 
  → voiceButton.setOnTouchListener()
  → ACTION_DOWN 事件
  → isButtonPressed = true
  → startVoiceRecognition()
```

### startVoiceRecognition() 流程：
1. 设置 UI 状态：`voiceButton.setText("正在听...")`
2. 创建 `VoiceCallback` 回调
3. 调用 `VoiceManager.getInstance().startListening(callback)`

---

## 2. 语音识别开始

**文件**: `VoiceManager.java` → `AsrManager.java` → `VoskAsrEngine.java`

```
VoiceManager.startListening()
  → AsrManager.startListening()
  → VoskAsrEngine.startRecognition()
  → SpeechService.startListening()  // Vosk 开始录音
```

### Vosk 引擎工作：
- 使用 `SpeechService` 自动录音（16kHz 采样率）
- 实时识别，回调 `onPartialResult()` 显示中间结果
- 等待用户松开按钮

---

## 3. 用户松开按钮（ACTION_UP）

**文件**: `MainActivity.java`

```
ACTION_UP 事件
  → isRecognizing = true
  → voiceButton.setText("识别中...")
  → VoiceManager.stopListening()
  → VoskAsrEngine.stopRecognition()
  → SpeechService.stop()  // 停止录音
  → startRecognitionTimeout()  // 启动 8 秒超时保护
```

---

## 4. 识别完成

**文件**: `VoskAsrEngine.java`

```
SpeechService 回调:
  → onFinalResult(hypothesis)
  → 解析 JSON: {"text": "请打开微信"}
  → callback.onResult(text)
  → VoiceManager 回调
  → MainActivity.currentVoiceCallback.onResult(text)
  → cancelRecognitionTimeout()
  → resetButtonState()
  → handleVoiceResult(text)
```

---

## 5. 处理识别结果

**文件**: `MainActivity.java`

```java
handleVoiceResult("请打开微信")
  1. TTS 播报: "好的，正在处理您的指令: 请打开微信"
  2. 更新 UI: statusText.setText("执行中: 请打开微信")
  3. 检查无障碍服务是否开启
  4. 获取屏幕尺寸
  5. 调用 AgentManager.startTask(text, width, height)
```

---

## 6. 启动 Agent 任务（与后端通信）

**文件**: `AgentManager.java`

```java
AgentManager.startTask("请打开微信", 1080, 2400)
  1. 设置 isTaskRunning = true
  2. 保存屏幕尺寸（用于坐标转换）
  3. connectWebSocket(taskPrompt)
     → 连接 WebSocket: ws://localhost:8090/ws/agent
```

### WebSocket 连接成功后：
```java
onOpen()
  → captureAndSend(true, "请打开微信")
  → AccessibilityScreenshotManager.capture()
  → 截图成功后 sendInit()
  → 发送 JSON 到服务器:
     {
       "type": "init",
       "task": "请打开微信",
       "screenshot": "base64图片数据",
       "screen_info": "Android Screen"
     }
```

---

## 7. 接收服务器指令并执行

**文件**: `AgentManager.java`

```java
onMessage(text)  // 收到服务器返回的 JSON
  → handleServerMessage(text)
  → 解析 JSON:
     {
       "finished": false,
       "action": {
         "action": "Tap",
         "element": [500, 800],  // 相对坐标 (0-1000)
         "message": "点击微信图标"
       }
     }
  
  → 坐标转换: [500, 800] → [540px, 1920px]
  → AutoGLMService.executeAction(actionMap)
  → 执行点击操作
  → 等待 UI 响应 (1-5秒)
  → captureAndSend(false, null)  // 截图并发送下一帧
  → sendStep(base64Image)
  → 发送 JSON:
     {
       "type": "step",
       "screenshot": "新的base64图片",
       "screen_info": "Step Screen"
     }
```

### 循环执行直到任务完成：
```
服务器返回 → 执行动作 → 截图 → 发送 → 服务器返回 → ...
```

### 任务完成：
```json
{
  "finished": true,
  "action": {
    "message": "已成功打开微信"
  }
}
```
→ 显示 Toast
→ stopTask()
→ 关闭 WebSocket

---

## 8. 错误处理

### 识别错误：
```
VoskAsrEngine.onError()
  → VoiceManager 回调
  → MainActivity.onError(error)
  → resetButtonState()
  → TTS 播报错误提示
```

### 网络错误：
```
WebSocket.onFailure()
  → isTaskRunning = false
  → 日志记录
```

### 超时保护：
```
8秒后未收到识别结果
  → recognitionTimeoutRunnable 触发
  → cancelListening()
  → resetButtonState()
  → TTS: "识别超时，请重试"
```

---

## 前后端通信状态

### ✅ 已实现的功能：
1. **WebSocket 连接**: `ws://localhost:8090/ws/agent`
2. **初始化帧发送**: `type: init` + 任务描述 + 截图
3. **步骤帧发送**: `type: step` + 截图
4. **动作执行**: 解析服务器指令并执行（点击、滑动、输入等）
5. **坐标转换**: 相对坐标 (0-1000) → 绝对像素坐标
6. **任务完成检测**: `finished: true` 时停止任务

### ⚠️ 需要配置的部分：
1. **服务器地址**: 
   - 当前配置: `ws://localhost:8090/ws/agent`
   - 需要改为实际服务器 IP，例如: `ws://192.168.1.100:8090/ws/agent`
   
2. **NetworkClient 未使用**:
   - `NetworkClient.java` 中的 HTTP 接口未被调用
   - 当前只使用 `AgentManager` 的 WebSocket 通信

### 🔧 建议修改：

**修改服务器地址**（在 `AgentManager.java` 第 23 行）：
```java
// 修改前
private static final String SERVER_URL = "ws://localhost:8090/ws/agent";

// 修改后（替换为你的服务器 IP）
private static final String SERVER_URL = "ws://192.168.1.100:8090/ws/agent";
```

---

## 完整流程图

```
用户按住按钮
    ↓
开始录音（Vosk）
    ↓
实时识别（显示中间结果）
    ↓
用户松开按钮
    ↓
停止录音，等待最终结果
    ↓
识别完成："请打开微信"
    ↓
TTS 播报确认
    ↓
启动 Agent 任务
    ↓
连接 WebSocket
    ↓
截图 + 发送初始化帧
    ↓
┌─────────────────────┐
│ 服务器返回动作指令   │
│  ↓                  │
│ 执行动作（点击/滑动）│
│  ↓                  │
│ 等待 UI 响应        │
│  ↓                  │
│ 截图 + 发送步骤帧   │
│  ↓                  │
└─────────────────────┘
    ↓ (循环直到完成)
任务完成
    ↓
显示结果 + 关闭连接
```

---

## 总结

**当前状态**: 
- ✅ 语音识别（Vosk 离线）正常工作
- ✅ 前后端通信架构完整
- ⚠️ 需要配置正确的服务器地址
- ⚠️ 需要确保后端服务运行在 `8090` 端口

**测试建议**:
1. 启动后端服务器
2. 修改 `AgentManager.SERVER_URL` 为实际 IP
3. 重新编译安装应用
4. 测试完整流程
