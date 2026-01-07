package com.example.test_android_dev.manager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.test_android_dev.App;
import com.example.test_android_dev.model.ConnectionState;
import com.example.test_android_dev.model.TaskState;
import com.example.test_android_dev.service.AutoGLMService;
import com.example.test_android_dev.service.BackgroundKeepAliveService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

public class AgentManager {
    private static final String TAG = "AgentManager";
    private static final String SERVER_URL = "ws://localhost:8090/ws/agent";

    private static AgentManager instance;
    private final Gson gson = new Gson();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WebSocketConnectionManager connectionManager;
    private WakeLockManager wakeLockManager;
    private TaskStateManager taskStateManager;

    private TaskState currentTask;
    private boolean isTaskRunning = false;
    private int screenWidth;
    private int screenHeight;
    private Context appContext;

    private AgentManager() {
        connectionManager = WebSocketConnectionManager.getInstance();
        wakeLockManager = WakeLockManager.getInstance();
        taskStateManager = TaskStateManager.getInstance();
    }

    public static synchronized AgentManager getInstance() {
        if (instance == null) {
            instance = new AgentManager();
        }
        return instance;
    }


    public void init(Context context) {
        this.appContext = context.getApplicationContext();
        wakeLockManager.init(appContext);
        taskStateManager.init(appContext);
    }

    public void startTask(String taskPrompt, int width, int height) {
        Log.d(TAG, "startTask: " + taskPrompt);
        if (isTaskRunning) {
            Log.w(TAG, "任务已在运行中");
            return;
        }

        this.screenWidth = width;
        this.screenHeight = height;
        this.isTaskRunning = true;

        currentTask = new TaskState(taskPrompt, width, height);
        currentTask.markRunning();
        taskStateManager.saveState(currentTask);

        wakeLockManager.acquire(heldMs -> Log.w(TAG, "唤醒锁超时: " + heldMs));

        if (appContext != null) {
            BackgroundKeepAliveService.start(appContext, taskPrompt);
        }

        connectWebSocket(taskPrompt);
    }

    public void stopTask() {
        Log.d(TAG, "停止任务");
        isTaskRunning = false;
        connectionManager.disconnect();
        wakeLockManager.release();

        if (currentTask != null) {
            taskStateManager.markTaskStopped(currentTask);
            currentTask = null;
        }

        if (appContext != null) {
            BackgroundKeepAliveService.stop(appContext);
        }
    }

    public void resumeTask(TaskState savedTask) {
        this.currentTask = savedTask;
        this.screenWidth = savedTask.getScreenWidth();
        this.screenHeight = savedTask.getScreenHeight();
        this.isTaskRunning = true;

        currentTask.markRunning();
        taskStateManager.saveState(currentTask);
        wakeLockManager.acquire(null);

        if (appContext != null) {
            BackgroundKeepAliveService.start(appContext, savedTask.getTaskPrompt());
        }
        connectWebSocket(savedTask.getTaskPrompt());
    }

    public void checkAndReconnectIfNeeded() {
        if (isTaskRunning) {
            connectionManager.checkAndReconnectIfNeeded();
        }
    }

    public boolean hasIncompleteTask() {
        return taskStateManager.hasIncompleteTask();
    }

    public void promptTaskRecovery(Context ctx, TaskStateManager.TaskRecoveryCallback cb) {
        taskStateManager.promptTaskRecovery(ctx, cb);
    }

    public TaskState getCurrentTask() { return currentTask; }
    public boolean isTaskRunning() { return isTaskRunning; }


    private void connectWebSocket(String initTask) {
        connectionManager.connect(SERVER_URL, new WebSocketConnectionManager.ConnectionCallback() {
            @Override
            public void onConnected() {
                updateServiceState(ConnectionState.CONNECTED);
                captureAndSend(true, initTask);
            }

            @Override
            public void onDisconnected(String reason) {
                updateServiceState(ConnectionState.DISCONNECTED);
            }

            @Override
            public void onReconnecting(int attempt, long nextRetryMs) {
                updateServiceState(ConnectionState.RECONNECTING);
                BackgroundKeepAliveService svc = BackgroundKeepAliveService.getInstance();
                if (svc != null) svc.showReconnectingNotification(attempt, nextRetryMs);
            }

            @Override
            public void onReconnectFailed() {
                BackgroundKeepAliveService svc = BackgroundKeepAliveService.getInstance();
                if (svc != null) svc.showErrorNotification("连接失败");
                isTaskRunning = false;
            }

            @Override
            public void onMessage(String message) {
                handleServerMessage(message);
            }

            @Override
            public void onConnectionStateChanged(ConnectionState state) {
                updateServiceState(state);
            }
        });
    }

    private void updateServiceState(ConnectionState state) {
        BackgroundKeepAliveService svc = BackgroundKeepAliveService.getInstance();
        if (svc != null) svc.updateConnectionState(state);
    }

    public void sendInit(String task, String base64Image) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "init");
        json.addProperty("task", task);
        json.addProperty("screenshot", base64Image);
        json.addProperty("screen_info", "Android Screen");
        connectionManager.send(gson.toJson(json));
    }

    private void sendStep(String base64Image) {
        if (!isTaskRunning) return;
        JsonObject json = new JsonObject();
        json.addProperty("type", "step");
        json.addProperty("screenshot", base64Image);
        json.addProperty("screen_info", "Step Screen");
        connectionManager.send(gson.toJson(json));
    }


    private void handleServerMessage(String text) {
        try {
            JsonObject response = gson.fromJson(text, JsonObject.class);

            if (response.has("finished") && response.get("finished").getAsBoolean()) {
                String msg = "任务完成";
                if (response.has("action") && response.getAsJsonObject("action").has("message")) {
                    msg = response.getAsJsonObject("action").get("message").getAsString();
                }
                showToast(msg);
                BackgroundKeepAliveService svc = BackgroundKeepAliveService.getInstance();
                if (svc != null) svc.showTaskCompleteNotification(msg);
                stopTask();
                return;
            }

            if (response.has("action")) {
                JsonObject actionJson = response.getAsJsonObject("action");
                Map<String, Object> actionMap = parseActionJsonToMap(actionJson);

                AutoGLMService service = AutoGLMService.getInstance();
                if (service == null) {
                    showToast("请开启无障碍服务");
                    stopTask();
                    return;
                }

                service.executeAction(actionMap);
                if (currentTask != null) taskStateManager.updateStep(currentTask);

                long waitTime = 2000;
                String actionType = (String) actionMap.get("action");
                if ("Tap".equals(actionType)) waitTime = 1000;
                if ("Launch".equals(actionType)) waitTime = 5000;
                if ("Type".equals(actionType)) waitTime = 3000;

                Thread.sleep(waitTime);
                captureAndSend(false, null);
            }
        } catch (Exception e) {
            Log.e(TAG, "处理指令异常", e);
        }
    }

    private void captureAndSend(boolean isInit, String taskPrompt) {
        if (!isTaskRunning) return;
        AutoGLMService service = AutoGLMService.getInstance();
        if (service == null) {
            stopTask();
            return;
        }

        AccessibilityScreenshotManager.getInstance().capture(service,
            new AccessibilityScreenshotManager.ScreenshotCallback() {
                @Override
                public void onSuccess(String base64) {
                    if (isInit) sendInit(taskPrompt, base64);
                    else sendStep(base64);
                }

                @Override
                public void onFailure(String error) {
                    Log.e(TAG, "截图失败: " + error);
                }
            });
    }


    private Map<String, Object> parseActionJsonToMap(JsonObject json) {
        Map<String, Object> map = new HashMap<>();
        map.put("action", json.get("action").getAsString());

        if (json.has("element")) {
            int[] p = convertCoordinates(json.get("element"));
            map.put("element", java.util.Arrays.asList(p[0], p[1]));
        }
        if (json.has("start")) {
            int[] p = convertCoordinates(json.get("start"));
            map.put("start", java.util.Arrays.asList(p[0], p[1]));
        }
        if (json.has("end")) {
            int[] p = convertCoordinates(json.get("end"));
            map.put("end", java.util.Arrays.asList(p[0], p[1]));
        }
        if (json.has("text")) map.put("text", json.get("text").getAsString());
        if (json.has("app")) map.put("app", json.get("app").getAsString());
        if (json.has("message")) map.put("message", json.get("message").getAsString());
        if (json.has("duration")) {
            JsonElement d = json.get("duration");
            if (d.isJsonPrimitive() && d.getAsJsonPrimitive().isNumber()) {
                map.put("duration", d.getAsInt());
            } else {
                map.put("duration", d.getAsString());
            }
        }
        if (json.has("duration_ms")) map.put("duration_ms", json.get("duration_ms").getAsInt());
        return map;
    }

    private int[] convertCoordinates(JsonElement element) {
        try {
            if (element.isJsonArray()) {
                JsonArray arr = element.getAsJsonArray();
                int absX = (int) (arr.get(0).getAsInt() / 1000.0f * screenWidth);
                int absY = (int) (arr.get(1).getAsInt() / 1000.0f * screenHeight);
                return new int[]{absX, absY};
            }
        } catch (Exception e) {
            Log.e(TAG, "坐标解析错误", e);
        }
        return new int[]{0, 0};
    }

    private void showToast(String msg) {
        mainHandler.post(() -> android.widget.Toast.makeText(
            App.getContext(), msg, android.widget.Toast.LENGTH_SHORT).show());
    }
}
