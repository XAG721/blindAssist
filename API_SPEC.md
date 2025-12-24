## BlindAssist 第一版接口文档（按 3 个需求分组）

本文档按 **三个核心需求** 分组设计接口，方便分配给三个组员独立开发：

1. **需求 A：客户端 → 服务端：上传语音 / prompt / 图像帧**
2. **需求 B：服务端稳定接收并处理语音与图像**
3. **需求 C：AutoGLM 控制 SDK 结果回传到客户端**

每个需求中，分别列出：要做的接口、后端改动、Android 改动和建议负责人范围。

---

### 一、需求 A：客户端上传语音 / prompt / 一定帧数的图像

> 目标：让客户端能把“用户语音（或处理好的 prompt）+ 图像帧”可靠发送到后端。  
> 适合分配给 **客户端网络+采集组员**。

#### A.1 语音 / prompt 上传接口

- **接口：`POST /api/voice/command`**（扩展现有接口）
- **用途**：上传语音识别得到的文本 + 可选原始音频，让服务端做意图分类 / prompt 生成。

- **请求（JSON）**

```json
{
  "text": "客户端 ASR 输出的文本，如“帮我导航去最近的药店”",
  "audioMeta": {
    "format": "pcm|wav|opus",
    "sampleRate": 16000,
    "durationMs": 1200
  },
  "audioDataBase64": "可选，原始音频Base64",
  "clientContext": "可选，设备型号/方言/场景描述等"
}
```

- **响应（JSON）**

```json
{
  "feature": "NAVIGATION|OBSTACLE_AVOIDANCE|QA_VOICE|OCR|SCENE_DESCRIPTION|CONTROL_SDK|UNKNOWN",
  "detail": "解析补充，如“导航到药店”",
  "prompt": "可选，为大模型准备的 prompt 文本"
}
```

##### A.1.1 客户端需要做什么（Android）

- 在 `NetworkClient` 中新增或完善方法，例如：

```java
public void sendVoiceText(
    String text,
    @Nullable byte[] audioBytes,
    NetworkCallback<String> callback
);
```

- 将 `text` + `audioBytes` 打包为上述 JSON，调用 `/api/voice/command`；
- 在 `FeatureRouter` 中调用此方法，实现“语音 → 文本 → 服务器意图分类”流程。

##### A.1.2 需要关注的点

- 文本长度、编码（UTF-8）；
- 可选上传 `audioDataBase64`，前期可以先只传 `text`，后面再补音频。

---

#### A.2 图像上传（单帧 / 多帧）

> 适合做 **客户端图像采集 + 上传的组员**。

##### A.2.1 单帧上传：`POST /api/vision/frame`

- **用途**：上传当前摄像头单帧，用于 OCR / 场景描述 / 一次性视觉分析。
- **请求**
  - Header:
    - `Content-Type: application/octet-stream`
  - Query 参数：
    - `sceneType`: `ocr|scene_description|obstacle_avoidance|general`
    - `frameSeq`: 可选，帧序号
  - Body:
    - JPEG/PNG 二进制数据

- **响应**

```json
{
  "status": "ok",
  "frameId": "服务器生成的帧ID",
  "message": "状态说明"
}
```

##### A.2.2 多帧 / 小批上传：`POST /api/vision/frames`

- **用途**：一次提交多帧，适合需要短序列的场景（简单避障/动作识别等）。
- **请求**
  - Header:
    - `Content-Type: multipart/form-data`
  - 表单字段：
    - `frames`: 多个图像文件字段；
    - `meta`: JSON 字符串，示例：

```json
{
  "sceneType": "obstacle_avoidance",
  "startSeq": 1,
  "count": 5
}
```

- **响应**

```json
{
  "status": "ok",
  "frameIds": ["id1", "id2", "id3"],
  "message": "frames received"
}
```

##### A.2.3 客户端需要做什么（Android）

- 在 `ImageCaptureManager` 中：
  - 实现单帧采集：`captureSingleFrame(ImageFrameCallback cb)`，返回 `byte[]`。
  - 实现小批采集（可选）：循环调用采集方法并组装为 List<byte[]>。

- 在 `NetworkClient` 中新增：

```java
public void uploadVisionFrame(
    byte[] imageBytes,
    String sceneType,
    int frameSeq,
    NetworkCallback<String> callback
);

public void uploadVisionFrames(
    List<byte[]> frames,
    String sceneType,
    int startSeq,
    NetworkCallback<String> callback
);
```

- 在 `FeatureRouter` 中：
  - OCR 模式：采集单帧 → `uploadVisionFrame(sceneType="ocr")`；
  - 场景描述模式：采集单帧 → `uploadVisionFrame(sceneType="scene_description")`。

---

#### A.3 图像流上传（可选：WebSocket）

- **接口**：`WS /ws/vision-stream`
- **用途**：持续上传图像帧流（例如避障）。

- **连接初始化（文本消息）**

```json
{
  "type": "init",
  "sceneType": "obstacle_avoidance|scene_description|ocr|general",
  "sessionId": "string",
  "startSeq": 1
}
```

- **之后客户端持续发送二进制帧**：每帧一个 `BinaryMessage`。

##### A.3.1 客户端需要做什么

- 在 `NetworkClient` 中新增：

```java
public interface VisionStreamListener {
    void onConnected();
    void onInstruction(String json);
    void onError(Throwable t);
}

public void connectVisionStream(
    String sceneType,
    String sessionId,
    VisionStreamListener listener
);

public void sendVisionStreamFrame(byte[] frame, int frameSeq);

public void closeVisionStream();
```

- 在 `ImageCaptureManager` 中：
  - 实现 `startVideoStream()` 定期采集帧，并调用 `sendVisionStreamFrame`。

---

### 二、需求 B：服务端稳定接收并处理语音和图像

> 目标：在服务端保证语音/图像数据能被正确接收、排队和交给后续模块（大模型、AutoGLM 等）。  
> 适合分配给 **后端接口+存储/队列组员**。

#### B.1 接收语音 / prompt：扩展 Voice 模块

- 已有接口：`POST /api/voice/command`
  - 由 **需求 A** 客户端上传；
  - 本组需要：
    1. 扩展 `VoiceCommandRequest` DTO，增加 `audioMeta`、`audioDataBase64`；
    2. 在 `VoiceController` 中使用新 DTO；
    3. 在 `IntentClassificationService` 中：
       - 对 `text` 做意图分类；
       - 可选：对 `audioDataBase64` 做 ASR/重判；
       - 构造 `prompt` 字段。

#### B.2 接收单帧 / 多帧图像：新增 VisionFrameController

- **后端新建 `VisionFrameController`**

```java
@RestController
@RequestMapping("/api/vision")
public class VisionFrameController {

    @PostMapping(
        value = "/frame",
        consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE
    )
    public VisionFrameAckResponse uploadFrame(
        @RequestBody byte[] imageBytes,
        @RequestParam String sceneType,
        @RequestParam(required = false) Integer frameSeq
    ) {
        // TODO: 校验、生成 frameId、入队或存储
        VisionFrameAckResponse resp = new VisionFrameAckResponse();
        resp.setStatus("ok");
        resp.setFrameId("frame-" + System.currentTimeMillis());
        resp.setMessage("frame received");
        return resp;
    }

    @PostMapping(
        value = "/frames",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public VisionFrameAckResponse uploadFrames(
        @RequestPart("frames") List<MultipartFile> frames,
        @RequestPart(value = "meta", required = false) VisionFramesMeta meta
    ) {
        // TODO: 处理多帧，生成多个 frameId，入队
        VisionFrameAckResponse resp = new VisionFrameAckResponse();
        resp.setStatus("ok");
        resp.setFrameId("batch-" + System.currentTimeMillis());
        resp.setMessage("frames received: " + frames.size());
        return resp;
    }
}
```

- DTO 需要：

```java
public class VisionFrameAckResponse {
    private String status;
    private String frameId;
    private String message;
    // getter/setter
}

public class VisionFramesMeta {
    private String sceneType;
    private Integer startSeq;
    private Integer count;
    // getter/setter
}
```

#### B.3 接收图像流（WebSocket）：VisionStreamHandler

- **新增配置**

```java
@Configuration
@EnableWebSocket
public class VisionWebSocketConfig implements WebSocketConfigurer {

    private final VisionStreamHandler visionStreamHandler;

    public VisionWebSocketConfig(VisionStreamHandler visionStreamHandler) {
        this.visionStreamHandler = visionStreamHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(visionStreamHandler, "/ws/vision-stream")
                .setAllowedOrigins("*");
    }
}
```

- **新增 Handler**

```java
@Component
public class VisionStreamHandler extends BinaryWebSocketHandler {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        session.sendMessage(new TextMessage("vision stream connected"));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // 解析 init 消息，记录 sceneType / sessionId 等
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        byte[] bytes = message.getPayload().array();
        // TODO: 将图像帧入队到 VisionStreamService / 队列中
        Map<String, Object> ack = Map.of(
                "type", "ack",
                "message", "frame received"
        );
        session.sendMessage(new TextMessage(mapper.writeValueAsString(ack)));
    }
}
```

- 可选新增 `VisionStreamService` 负责：
  - 保存帧数据（内存/文件/消息队列）；
  - 调用视觉模型 / 多模态大模型 / AutoGLM。

---

### 三、需求 C：AutoGLM 控制 SDK 结果回传到客户端

> 目标：把 AutoGLM 控制 SDK 生成的“操作指令/控制轨迹”汇入到系统，最终能被客户端消费和执行。  
> 适合分配给 **集成 AutoGLM + 控制协议的组员**。

#### C.1 控制结果上报接口：`POST /api/control/sdk-result`

- **用途**：
  - AutoGLM 所在环境（可能在服务端，也可能在独立服务）把控制结构化结果回报到后端；
  - 客户端如果本地执行了自动控制，也可以把结果报告上来。

- **请求（JSON）**

```json
{
  "sessionId": "会话ID",
  "taskId": "控制任务ID",
  "result": {
    "action": "open_app|click|swipe|navigate|speak|other",
    "params": {
      "x": 100,
      "y": 200,
      "text": "要输入/朗读的文本",
      "extra": "其他参数"
    },
    "raw": {
      "any": "AutoGLM 原始输出，用于调试/回放"
    }
  }
}
```

- **响应**

```json
{
  "status": "ok",
  "message": "received"
}
```

- **后端 Controller（新建）**

```java
@RestController
@RequestMapping("/api/control")
public class ControlSdkController {

    @PostMapping("/sdk-result")
    public Map<String, String> receiveResult(@RequestBody ControlSdkResultRequest req) {
        // TODO: 存储/转发到消息队列，供客户端或监控系统消费
        return Map.of("status", "ok", "message", "received");
    }
}
```

- **对应 DTO：`ControlSdkResultRequest`**

```java
public class ControlSdkResultRequest {
    private String sessionId;
    private String taskId;
    private ControlResult result;

    public static class ControlResult {
        private String action;
        private Map<String, Object> params;
        private Map<String, Object> raw;
        // getter/setter
    }
    // getter/setter
}
```

#### C.2 控制结果推送到客户端（可选：WebSocket）

有两种方案：

- **方案 1：客户端主动轮询**
  - 定期调用类似 `GET /api/control/pending?sessionId=xxx`；
  - 服务端返回待执行的指令列表，客户端执行后再回报结果。

- **方案 2：通过 WebSocket 推送（推荐）**
  - 在 `/ws/vision-stream` 或单独 `/ws/control` 上推送：

```json
{
  "type": "control",
  "sessionId": "xxx",
  "taskId": "yyy",
  "action": "click",
  "params": { "x": 100, "y": 200 }
}
```

- 客户端在 `VisionStreamListener.onInstruction(String json)` 或类似回调中解析，并调用 AutoGLM SDK 或自身 UI 控制模块执行。

#### C.3 客户端需要做什么（Android）

- 在 `NetworkClient` 中新增：

```java
public void sendControlSdkResult(
    String sessionId,
    String taskId,
    String action,
    Map<String, Object> params,
    Map<String, Object> raw,
    NetworkCallback<String> callback
);
```

- 在 WebSocket 监听里：
  - 当收到 `type=control` 消息时，解析 `action` / `params`，交给 AutoGLM SDK 或本地控制层执行；
  - 执行完成后再调用 `sendControlSdkResult` 上报结果（是否成功、耗时等）。

---

### 四、按需求分配给组员的建议

- **组员 1（客户端网络 + 语音图像上传）——负责需求 A**
  - Android：
    - 补全 `NetworkClient` 中语音/图像相关方法；
    - 在 `ImageCaptureManager` 中实现单帧/多帧/流式采集；
    - 在 `FeatureRouter` 中串起“语音 → command 接口”、“图像 → vision 接口”。

- **组员 2（后端接口 + 数据接收与排队）——负责需求 B**
  - Spring Boot：
    - 扩展 `VoiceCommandRequest/Response`、`VoiceController`、`IntentClassificationService`；
    - 新建 `VisionFrameController` 及对应 DTO；
    - 新建 `VisionWebSocketConfig` 和 `VisionStreamHandler`，配合 `VisionStreamService` 处理帧数据。

- **组员 3（AutoGLM 集成 + 控制协议）——负责需求 C**
  - 后端：
    - 新建 `ControlSdkController` 与 `ControlSdkResultRequest`；
    - 视需要增加控制队列 / 订阅机制；
  - 客户端：
    - 在 `NetworkClient` 中实现控制结果上报方法；
    - 在 WebSocket 回调中解析 `type=control`，对接 AutoGLM 控制 SDK。

这样三个需求彼此解耦，每个组员只要按本组文档里的接口和类去实现即可。 



