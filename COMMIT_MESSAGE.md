feat: 重构ASR多引擎架构并修复按住说话交互逻辑

本次提交全面重构了语音识别模块，实现了多引擎自动切换架构，并彻底修复了"按住说话"按钮的交互问题。

## 主要变更

### 1. 多引擎ASR架构 (app/src/main/java/com/example/test_android_dev/asr/)
- 新增 `AsrEngine.java` 接口，定义统一的ASR引擎规范
- 新增 `AsrManager.java` 管理器，实现引擎自动选择和切换
- 新增 `VoskAsrEngine.java` 离线语音识别引擎
- 新增 `SystemAsrEngine.java` 系统原生语音识别引擎
- 新增 `XunfeiAsrEngine.java` 讯飞WebSocket流式语音识别引擎

引擎优先级：Vosk离线 → 系统ASR → 讯飞ASR

### 2. Vosk离线语音识别集成
- 添加 `vosk-android:0.3.47` 依赖 (app/build.gradle)
- 支持完全离线的中文语音识别（无需网络，保护隐私）
- 智能检测多种模型目录结构
- 模型异步加载机制，首次使用时自动等待

### 3. 按住说话交互重构 (MainActivity.java)
修复的核心问题：
- ✅ 松开按钮后仍持续监听
- ✅ 快速点击导致"识别器繁忙"
- ✅ 识别中仍可点击导致状态混乱
- ✅ TTS语音累积播放

新增状态管理：
- `isButtonPressed`：按钮是否被按下（正在录音）
- `isRecognizing`：是否正在识别（已停止录音，等待结果）
- 识别超时机制（10秒）

交互流程：
```
按下 → 开始录音 → 显示"正在听..."
松开 → 停止录音 → 显示"识别中..." → 等待结果
结果 → 重置状态 → 显示"按住说话"
```

### 4. 系统ASR增强 (SystemAsrEngine.java)
- 添加详细的音频接收日志（onBufferReceived、onRmsChanged等）
- 添加5秒识别超时机制
- 改进网络错误提示和处理
- 防止"识别器繁忙"错误（最小重启间隔800ms）

### 5. 讯飞ASR实现 (XunfeiAsrEngine.java)
- 完整的WebSocket流式识别实现
- 支持实时部分结果（onPartialResult）
- 动态修正模式（wpgs）支持
- 完善的鉴权机制（HmacSHA256签名）
- 自动音频录制和帧发送（40ms/帧）

### 6. VoiceManager重构
- 从直接使用 `SpeechRecognizer` 改为使用 `AsrManager`
- 新增 `configureXunfeiAsr()` 方法
- 新增 `getCurrentAsrEngineName()` 方法
- 新增 `isAsrAvailable()` 方法
- 新增 `cancelListening()` 方法

### 7. 配置项新增 (Config.java)
添加讯飞ASR配置项：
- `XUNFEI_APP_ID`
- `XUNFEI_API_KEY`
- `XUNFEI_API_SECRET`

## 使用说明

### Vosk离线模型配置（推荐）
1. 下载中文模型：https://alphacephei.com/vosk/models
   推荐：vosk-model-small-cn-0.22（约50MB）
2. 将模型放置到以下任一位置：
   - app/src/main/assets/model-cn/vosk-model-small-cn-0.22/
   - app/src/main/assets/model-cn/（解压后内容直接放入）

### 讯飞ASR配置（可选）
1. 注册讯飞开放平台：https://www.xfyun.cn/
2. 在 Config.java 中填入凭证

### 系统ASR（自动可用）
- 国产手机自带语音服务
- 需要网络连接
- 无需额外配置

## 技术亮点

- **多引擎架构**：统一接口，易于扩展新引擎
- **自动降级**：网络故障时自动切换到离线引擎
- **状态机管理**：严格的状态转换，避免竞态条件
- **防抖机制**：防止快速点击导致的错误
- **异步加载**：模型加载不阻塞UI线程
- **详细日志**：完整的调试信息，便于问题诊断

## 测试建议

1. 测试Vosk离线识别（无网络环境）
2. 测试系统ASR（有网络环境）
3. 测试按住说话交互（快速点击、长按、识别中点击）
4. 测试网络切换时的引擎自动降级
5. 测试不同手机型号的兼容性

## Breaking Changes

无破坏性变更，向后兼容。

## 相关Issue

解决了以下问题：
- 松开按钮后仍持续监听
- 快速点击导致"识别器繁忙"
- 识别中仍可点击导致状态混乱
- TTS语音累积播放
- 系统ASR需要网络但无提示
- 麦克风权限正常但无法识别
- 模型加载时机不当导致引擎不可用
