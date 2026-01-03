# 需求 C：AutoGLM 自动化控制与结果反馈接口规范

**目标**：将服务端（Agent）生成的原子化操作指令下发至手机端，由无障碍服务自动执行，并向系统反馈执行状态。

---

## C.1 控制指令下发（通过 WebSocket 或推送）
服务端生成的指令必须符合手机端 `AutoGLMService` 的解析格式。

- **下发指令格式 (JSON)**
```json
{
  "type": "control",
  "sessionId": "会话ID",
  "taskId": "任务序列ID",
  "data": {
    "action": "Launch|Tap|Type|Swipe|Back|Home|Double Tap|Long Press|Wait|Take_over",
    "app": "微信",                         // 仅用于 Launch
    "element": [500, 1000],                // 坐标，用于 Tap/Double Tap/Long Press
    "text": "搜索内容",                     // 用于 Type
    "start": [500, 1500],                  // 用于 Swipe 起点
    "end": [500, 500],                     // 用于 Swipe 终点
    "duration": 500,                       // 毫秒，用于 Swipe/Wait
    "duration_ms": 1000,                   // 用于 Long Press
    "message": "请点击登录按钮"              // 用于 Take_over 提示
  }
}
```

## C.2 执行结果上报接口：`POST /api/control/sdk-result`
- **用途**：客户端执行完上述 `action` 后，告知服务器执行是否成功，以便 Agent 进行下一步推理。

- **请求 (JSON)**
```json
{
  "sessionId": "会话ID",
  "taskId": "控制任务ID",
  "executionResult": {
    "status": "success|failed",
    "actionType": "Tap",
    "errorMessage": "未找到输入框焦点",      // 失败时携带
    "timestamp": 1700000000000
  }
}
```

- **后端 DTO 参考**
```java
public class ControlSdkResultRequest {
    private String sessionId;
    private String taskId;
    private ExecutionResult executionResult;

    public static class ExecutionResult {
        private String status;
        private String actionType;
        private String errorMessage;
        private long timestamp;
        // Getter/Setter...
    }
}
```

## C.3 客户端实现逻辑（Android）

### 1. 指令分发与执行
在 WebSocket 接收回调中，解析 `data` 字段并调用 `AutoGLMService`：
```java
if ("control".equals(json.getString("type"))) {
    JSONObject dataJson = json.getJSONObject("data");
    // 转换 JSON 为 Map
    Map<String, Object> actionMap = JsonUtils.toMap(dataJson); 
    
    AutoGLMService service = AutoGLMService.getInstance();
    if (service != null) {
        boolean success = service.executeAction(actionMap);
        // 上报执行结果
        reportResult(sessionId, taskId, (String)actionMap.get("action"), success);
    }
}
```

### 2. 指令映射表 (Mapping)
| 指令 (Action) | 对应功能 | 关键参数 |
| :--- | :--- | :--- |
| **Launch** | 启动 App | `app` (名称) |
| **Tap** | 模拟点击 | `element` ([x, y]) |
| **Type** | 输入文本 | `text` (字符串) |
| **Swipe** | 模拟滑动 | `start`, `end`, `duration` |
| **Back/Home** | 系统按键 | 无 |
| **Double Tap**| 模拟双击 | `element` |
| **Long Press**| 模拟长按 | `element`, `duration_ms` |
| **Wait** | 延迟等待 | `duration` |
| **Take_over** | 人工接管提示 | `message` (Toast内容) |

---

## C.4 注意事项
1. **权限依赖**：功能实现依赖于 `AccessibilityService`，调用前必须确认服务已在系统设置中开启。
2. **坐标体系**：`element` 等坐标参数应基于设备物理分辨率。
3. **App 识别**：`Launch` 指令依赖客户端 `AppRegistry` 进行名称到包名的转换。
