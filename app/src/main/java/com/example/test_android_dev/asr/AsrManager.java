package com.example.test_android_dev.asr;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * ASR 语音识别管理器
 * 统一管理多个 ASR 引擎，自动选择可用的引擎
 * 
 * 优先级：
 * 1. Vosk 离线识别（无需网络，需要下载模型）
 * 2. 系统语音识别（国产手机自带服务或 Google 服务，需要网络）
 * 3. 讯飞语音识别（需要配置 API 凭证，作为兜底方案）
 */
public class AsrManager {
    
    private static final String TAG = "AsrManager";
    private static AsrManager instance;
    
    private final List<AsrEngine> engines = new ArrayList<>();
    private AsrEngine currentEngine;
    private boolean isInitialized = false;
    
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // 状态管理
    private boolean isListening = false;
    private long lastStartTime = 0;
    private static final long DEBOUNCE_INTERVAL = 500;
    
    // 初始化状态回调
    private InitCallback initCallback;
    
    public interface InitCallback {
        void onInitStart();
        void onInitProgress(String message);
        void onInitComplete();
        void onInitFailed(String error);
    }
    
    private AsrManager() {
    }
    
    public static synchronized AsrManager getInstance() {
        if (instance == null) {
            instance = new AsrManager();
        }
        return instance;
    }
    
    /**
     * 初始化 ASR 管理器
     * @param context 应用上下文
     */
    public void init(Context context) {
        init(context, null);
    }
    
    /**
     * 初始化 ASR 管理器（带回调）
     * @param context 应用上下文
     * @param callback 初始化状态回调
     */
    public void init(Context context, InitCallback callback) {
        Log.d(TAG, "=== AsrManager.init() 被调用 ===");
        Log.d(TAG, "isInitialized = " + isInitialized);
        
        if (isInitialized) {
            Log.d(TAG, "AsrManager 已初始化，跳过");
            if (callback != null) {
                mainHandler.post(callback::onInitComplete);
            }
            return;
        }
        
        this.initCallback = callback;
        
        Log.d(TAG, "初始化 AsrManager");
        if (callback != null) {
            mainHandler.post(callback::onInitStart);
        }
        
        // 优先添加 Vosk 离线引擎（无需网络）
        boolean voskAvailable = VoskAsrEngine.isModelAvailable(context);
        Log.d(TAG, "检查 Vosk 模型是否可用: " + voskAvailable);
        
        if (voskAvailable) {
            VoskAsrEngine voskEngine = new VoskAsrEngine(context);
            
            // 设置 Vosk 加载回调
            voskEngine.setModelLoadCallback(new VoskAsrEngine.ModelLoadCallback() {
                @Override
                public void onLoadStart() {
                    if (initCallback != null) {
                        initCallback.onInitProgress("正在初始化离线语音引擎...");
                    }
                }
                
                @Override
                public void onLoadProgress(String message) {
                    if (initCallback != null) {
                        initCallback.onInitProgress(message);
                    }
                }
                
                @Override
                public void onLoadComplete() {
                    Log.d(TAG, "Vosk 引擎加载完成");
                    selectBestEngine();
                    if (initCallback != null) {
                        initCallback.onInitComplete();
                    }
                }
                
                @Override
                public void onLoadFailed(String error) {
                    Log.w(TAG, "Vosk 引擎加载失败: " + error);
                    selectBestEngine();
                    // 即使 Vosk 失败，只要有其他引擎可用就算成功
                    if (initCallback != null) {
                        if (currentEngine != null) {
                            initCallback.onInitComplete();
                        } else {
                            initCallback.onInitFailed("没有可用的语音识别引擎");
                        }
                    }
                }
            });
            
            Log.d(TAG, "准备调用 voskEngine.init()");
            voskEngine.init();
            Log.d(TAG, "voskEngine.init() 调用完成");
            engines.add(voskEngine);
            Log.d(TAG, "Vosk 离线引擎已添加到列表");
        } else {
            Log.w(TAG, "Vosk 模型不存在，跳过离线引擎");
        }
        
        // 添加系统引擎（需要网络）
        SystemAsrEngine systemEngine = new SystemAsrEngine(context);
        systemEngine.init();
        engines.add(systemEngine);
        Log.d(TAG, "系统 ASR 引擎已添加");
        
        // 添加讯飞引擎作为兜底
        XunfeiAsrEngine xunfeiEngine = new XunfeiAsrEngine();
        xunfeiEngine.init();
        engines.add(xunfeiEngine);
        Log.d(TAG, "讯飞 ASR 引擎已添加");
        
        // 选择默认引擎
        selectBestEngine();
        
        isInitialized = true;
        Log.d(TAG, "AsrManager 初始化完成，当前引擎: " + 
                (currentEngine != null ? currentEngine.getEngineName() : "无"));
        
        // 如果没有 Vosk 或 Vosk 已经加载完成，直接回调
        if (!voskAvailable) {
            if (callback != null) {
                mainHandler.post(callback::onInitComplete);
            }
        }
    }
    
    /**
     * 配置讯飞 ASR
     */
    public void configureXunfei(String appId, String apiKey, String apiSecret) {
        for (AsrEngine engine : engines) {
            if (engine instanceof XunfeiAsrEngine) {
                ((XunfeiAsrEngine) engine).configure(appId, apiKey, apiSecret);
                break;
            }
        }
        // 重新选择最佳引擎
        selectBestEngine();
    }
    
    /**
     * 选择最佳可用引擎
     */
    private void selectBestEngine() {
        currentEngine = null;
        for (AsrEngine engine : engines) {
            boolean available = engine.isAvailable();
            Log.d(TAG, "检查引擎 " + engine.getEngineName() + " 是否可用: " + available);
            if (available) {
                currentEngine = engine;
                Log.d(TAG, "选择引擎: " + engine.getEngineName());
                break;
            }
        }
        
        if (currentEngine == null) {
            Log.w(TAG, "没有可用的 ASR 引擎");
        }
    }
    
    /**
     * 尝试使用备用引擎（跳过指定的引擎）
     */
    private AsrEngine getFallbackEngine(AsrEngine skipEngine) {
        for (AsrEngine engine : engines) {
            if (engine != skipEngine && engine.isAvailable()) {
                return engine;
            }
        }
        return null;
    }
    
    /**
     * 尝试使用备用引擎
     */
    private AsrEngine getFallbackEngine() {
        return getFallbackEngine(currentEngine);
    }
    
    /**
     * 手动设置使用的引擎
     */
    public void setEngine(String engineName) {
        for (AsrEngine engine : engines) {
            if (engine.getEngineName().equals(engineName) && engine.isAvailable()) {
                currentEngine = engine;
                Log.d(TAG, "手动设置引擎: " + engineName);
                return;
            }
        }
        Log.w(TAG, "引擎不可用: " + engineName);
    }
    
    /**
     * 获取当前引擎名称
     */
    public String getCurrentEngineName() {
        return currentEngine != null ? currentEngine.getEngineName() : "无";
    }
    
    /**
     * 检查是否有可用的 ASR 引擎
     */
    public boolean isAvailable() {
        return currentEngine != null && currentEngine.isAvailable();
    }
    
    /**
     * 检查是否正在监听
     */
    public boolean isListening() {
        return isListening;
    }
    
    /**
     * 开始语音识别
     */
    public void startListening(AsrEngine.AsrCallback callback) {
        // 防抖检查
        long now = System.currentTimeMillis();
        if (now - lastStartTime < DEBOUNCE_INTERVAL) {
            Log.w(TAG, "startListening 被防抖拦截");
            return;
        }
        
        if (isListening) {
            Log.w(TAG, "已在监听状态，先停止");
            stopListeningInternal();
        }
        
        if (currentEngine == null || !currentEngine.isAvailable()) {
            selectBestEngine();
            if (currentEngine == null) {
                if (callback != null) {
                    mainHandler.post(() -> callback.onError("没有可用的语音识别服务"));
                }
                return;
            }
        }
        
        lastStartTime = now;
        isListening = true;
        
        Log.d(TAG, "开始语音识别，使用引擎: " + currentEngine.getEngineName());
        
        // 记录已尝试的引擎，避免重复降级
        final List<AsrEngine> triedEngines = new ArrayList<>();
        triedEngines.add(currentEngine);
        
        // 包装回调，确保状态正确更新，并支持自动降级
        AsrEngine.AsrCallback wrappedCallback = new AsrEngine.AsrCallback() {
            @Override
            public void onResult(String text) {
                isListening = false;
                if (callback != null) {
                    callback.onResult(text);
                }
            }
            
            @Override
            public void onPartialResult(String partialText) {
                if (callback != null) {
                    callback.onPartialResult(partialText);
                }
            }
            
            @Override
            public void onError(String error) {
                // 如果应该尝试备用引擎
                if (shouldTryFallback(error)) {
                    // 找一个还没尝试过的引擎
                    AsrEngine fallback = null;
                    for (AsrEngine engine : engines) {
                        if (!triedEngines.contains(engine) && engine.isAvailable()) {
                            fallback = engine;
                            break;
                        }
                    }
                    
                    if (fallback != null) {
                        Log.w(TAG, "引擎失败(" + error + ")，尝试备用引擎: " + fallback.getEngineName());
                        triedEngines.add(fallback);
                        currentEngine = fallback;
                        lastStartTime = 0; // 重置防抖
                        fallback.startRecognition(this);
                        return;
                    }
                }
                
                // 没有更多备用引擎，返回错误
                isListening = false;
                if (callback != null) {
                    callback.onError(error);
                }
            }
        };
        
        currentEngine.startRecognition(wrappedCallback);
    }
    
    /**
     * 判断是否应该尝试备用引擎
     */
    private boolean shouldTryFallback(String error) {
        if (error == null) return false;
        return error.contains("网络") || 
               error.contains("Network") || 
               error.contains("服务器") ||
               error.contains("Server") ||
               error.contains("超时") ||
               error.contains("Timeout") ||
               error.contains("模型未加载") ||
               error.contains("模型加载");
    }
    
    /**
     * 停止语音识别
     */
    public void stopListening() {
        Log.d(TAG, "stopListening 被调用");
        stopListeningInternal();
    }
    
    private void stopListeningInternal() {
        if (currentEngine != null) {
            currentEngine.stopRecognition();
        }
        isListening = false;
    }
    
    /**
     * 取消语音识别
     */
    public void cancel() {
        Log.d(TAG, "cancel 被调用");
        if (currentEngine != null) {
            currentEngine.cancel();
        }
        isListening = false;
    }
    
    /**
     * 获取所有引擎信息
     */
    public List<String> getAvailableEngines() {
        List<String> available = new ArrayList<>();
        for (AsrEngine engine : engines) {
            if (engine.isAvailable()) {
                available.add(engine.getEngineName());
            }
        }
        return available;
    }
    
    /**
     * 释放资源
     */
    public void release() {
        for (AsrEngine engine : engines) {
            engine.release();
        }
        engines.clear();
        currentEngine = null;
        isInitialized = false;
        isListening = false;
    }
}
