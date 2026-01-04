package com.example.test_android_dev;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import com.example.test_android_dev.asr.AsrEngine;
import com.example.test_android_dev.asr.AsrManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 封装语音输入/输出（ASR/TTS）
 * 支持中文语音识别和语音合成
 * 
 * ASR 支持多引擎：
 * 1. 讯飞语音识别（需配置 API 凭证，适用于所有手机）
 * 2. 系统语音识别（需要 Google 服务或国产手机自带服务）
 */
public class VoiceManager {

    private static final String TAG = "VoiceManager";
    private static VoiceManager instance;

    private TextToSpeech tts;
    private AsrManager asrManager;
    private VoiceCallback currentVoiceCallback;

    private boolean isTtsReady = false;
    private boolean isTtsInitializing = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Runnable> utteranceCallbacks = new ConcurrentHashMap<>();
    private final List<PendingUtterance> pendingUtterances = new ArrayList<>();

    private static final int MAX_TTS_TEXT_LENGTH = 3500;

    private VoiceManager() {
    }

    public static synchronized VoiceManager getInstance() {
        if (instance == null) {
            instance = new VoiceManager();
        }
        return instance;
    }


    public void init(Context context) {
        init(context, null);
    }
    
    /**
     * 初始化（带回调）
     */
    public void init(Context context, AsrManager.InitCallback asrCallback) {
        Log.d(TAG, "=== VoiceManager.init() 被调用 ===");
        if (tts == null && !isTtsInitializing) {
            isTtsInitializing = true;
            mainHandler.post(() -> initTtsWithFallback(context));
        }

        // 初始化 ASR 管理器（始终调用 init，AsrManager 内部会处理重复初始化）
        if (asrManager == null) {
            asrManager = AsrManager.getInstance();
        }
        // 始终调用 init，让 AsrManager 内部判断是否需要初始化
        asrManager.init(context, asrCallback);
        Log.d(TAG, "ASR 引擎: " + asrManager.getCurrentEngineName());
    }
    
    /**
     * 配置讯飞语音识别
     * 在讯飞开放平台注册获取凭证：https://www.xfyun.cn/
     */
    public void configureXunfeiAsr(String appId, String apiKey, String apiSecret) {
        if (asrManager != null) {
            asrManager.configureXunfei(appId, apiKey, apiSecret);
            Log.d(TAG, "讯飞 ASR 配置完成，当前引擎: " + asrManager.getCurrentEngineName());
        }
    }
    
    /**
     * 获取当前 ASR 引擎名称
     */
    public String getCurrentAsrEngineName() {
        return asrManager != null ? asrManager.getCurrentEngineName() : "未初始化";
    }
    
    /**
     * 检查 ASR 是否可用
     */
    public boolean isAsrAvailable() {
        return asrManager != null && asrManager.isAvailable();
    }

    private void initTtsWithFallback(Context context) {
        TextToSpeech tempTts = null;
        java.util.List<TextToSpeech.EngineInfo> engines = new java.util.ArrayList<>();

        try {
            tempTts = new TextToSpeech(context.getApplicationContext(), null);
            engines = tempTts.getEngines();
            Log.d(TAG, "---- 系统已安装的 TTS 引擎列表 ----");
            for (TextToSpeech.EngineInfo info : engines) {
                Log.d(TAG, "引擎名: " + info.label + " | 包名: " + info.name);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get TTS engines", e);
        } finally {
            if (tempTts != null) {
                tempTts.shutdown();
            }
        }

        String[] preferredEngines = getPreferredEngineOrder(engines);

        for (String engine : preferredEngines) {
            Log.i(TAG, "尝试初始化 TTS 引擎: " + engine);
            try {
                final String currentEngine = engine;
                tts = new TextToSpeech(context.getApplicationContext(), status -> {
                    if (status == TextToSpeech.SUCCESS) {
                        onTtsInitialized(currentEngine);
                    } else {
                        Log.e(TAG, currentEngine + " 初始化失败，错误码: " + status);
                    }
                }, engine);
                return;
            } catch (Exception e) {
                Log.e(TAG, engine + " 初始化异常", e);
            }
        }

        Log.i(TAG, "尝试初始化系统默认 TTS 引擎");
        try {
            tts = new TextToSpeech(context.getApplicationContext(), status -> {
                if (status == TextToSpeech.SUCCESS) {
                    onTtsInitialized("系统默认");
                } else {
                    Log.e(TAG, "系统默认引擎初始化失败，错误码: " + status);
                    onTtsInitializationFailed();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "系统默认引擎初始化异常", e);
            onTtsInitializationFailed();
        }
    }

    private void onTtsInitializationFailed() {
        Log.e(TAG, "所有TTS引擎初始化失败");
        isTtsInitializing = false;
        isTtsReady = false;
        synchronized (pendingUtterances) {
            pendingUtterances.clear();
        }
    }

    private String[] getPreferredEngineOrder(java.util.List<TextToSpeech.EngineInfo> engines) {
        java.util.List<String> engineNames = new java.util.ArrayList<>();

        for (TextToSpeech.EngineInfo engine : engines) {
            if (engine.name.contains("baidu") || engine.name.contains("iflytek") ||
                    engine.name.contains("huawei") || engine.name.contains("xiaomi") ||
                    engine.name.contains("mibrain")) {
                engineNames.add(engine.name);
            }
        }

        for (TextToSpeech.EngineInfo engine : engines) {
            if (engine.name.contains("google")) {
                engineNames.add(engine.name);
            }
        }

        for (TextToSpeech.EngineInfo engine : engines) {
            if (!engineNames.contains(engine.name)) {
                engineNames.add(engine.name);
            }
        }

        return engineNames.toArray(new String[0]);
    }

    private void onTtsInitialized(String engineName) {
        Log.i(TAG, "TTS 引擎初始化成功: " + engineName);
        int result = tts.setLanguage(Locale.CHINA);
        Log.d(TAG, "设置语言 Locale.CHINA 结果: " + result);
        if (result < 0) {
            result = tts.setLanguage(Locale.CHINESE);
            Log.d(TAG, "尝试 Locale.CHINESE 结果: " + result);
        }
        setupUtteranceListener();
        isTtsReady = true;
        isTtsInitializing = false;
        processPendingUtterances();
    }

    private void setupUtteranceListener() {
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                Log.d(TAG, "开始播报: " + utteranceId);
            }

            @Override
            public void onDone(String utteranceId) {
                Log.d(TAG, "播报完成: " + utteranceId);
                Runnable cb = utteranceCallbacks.remove(utteranceId);
                if (cb != null) mainHandler.post(cb);
            }

            @Override
            public void onError(String utteranceId) {
                Log.e(TAG, "播报错误: " + utteranceId);
                utteranceCallbacks.remove(utteranceId);
            }
        });
    }


    public void speak(String text) {
        speak(text, null);
    }

    public void speak(String text, Runnable onDone) {
        if (isTtsReady && tts != null) {
            executeSpeak(text, TextToSpeech.QUEUE_ADD, onDone);
        } else {
            synchronized (pendingUtterances) {
                pendingUtterances.add(new PendingUtterance(text, false, onDone));
            }
        }
    }

    public void speakImmediate(String text, Runnable onDone) {
        if (isTtsReady && tts != null) {
            executeSpeak(text, TextToSpeech.QUEUE_FLUSH, onDone);
        } else {
            synchronized (pendingUtterances) {
                pendingUtterances.removeIf(p -> p.isImmediate);
                pendingUtterances.add(new PendingUtterance(text, true, onDone));
            }
        }
    }

    public void speakImmediate(String text) {
        speakImmediate(text, null);
    }

    private void executeSpeak(String text, int queueMode, Runnable onDone) {
        if (text == null || text.trim().isEmpty()) {
            if (onDone != null) mainHandler.post(onDone);
            return;
        }

        if (text.length() > MAX_TTS_TEXT_LENGTH) {
            executeSpeakLongText(text, queueMode, onDone);
            return;
        }

        String uid = UUID.randomUUID().toString();
        if (onDone != null) utteranceCallbacks.put(uid, onDone);

        Bundle params = new Bundle();
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);

        mainHandler.post(() -> {
            int result = tts.speak(text, queueMode, params, uid);
            if (result != TextToSpeech.SUCCESS) {
                Log.e(TAG, "tts.speak 调用失败: " + result);
                utteranceCallbacks.remove(uid);
                if (onDone != null) mainHandler.post(onDone);
            }
        });
    }

    private void executeSpeakLongText(String text, int queueMode, Runnable onDone) {
        int queueModeForChunks = TextToSpeech.QUEUE_ADD;
        String[] sentences = text.split("(?<=[。！？])");
        if (sentences.length == 0 || (sentences.length == 1 && sentences[0].isEmpty())) {
            sentences = splitByCharacterLimit(text, MAX_TTS_TEXT_LENGTH);
        }

        for (int i = 0; i < sentences.length; i++) {
            String sentence = sentences[i].trim();
            if (sentence.isEmpty()) continue;
            String[] chunks = splitByCharacterLimit(sentence, MAX_TTS_TEXT_LENGTH);
            for (int j = 0; j < chunks.length; j++) {
                String chunk = chunks[j].trim();
                if (chunk.isEmpty()) continue;
                Runnable chunkCallback = (i == sentences.length - 1 && j == chunks.length - 1) ? onDone : null;
                executeSpeak(chunk, queueModeForChunks, chunkCallback);
                queueModeForChunks = TextToSpeech.QUEUE_ADD;
            }
        }
    }

    private String[] splitByCharacterLimit(String text, int maxLength) {
        if (text.length() <= maxLength) return new String[]{text};
        java.util.List<String> chunks = new java.util.ArrayList<>();
        for (int i = 0; i < text.length(); i += maxLength) {
            int end = Math.min(i + maxLength, text.length());
            chunks.add(text.substring(i, end));
        }
        return chunks.toArray(new String[0]);
    }

    private void processPendingUtterances() {
        synchronized (pendingUtterances) {
            for (PendingUtterance p : pendingUtterances) {
                executeSpeak(p.text, p.isImmediate ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, p.onDoneCallback);
            }
            pendingUtterances.clear();
        }
    }


    // ASR (Speech-to-Text) Section
    public interface VoiceCallback {
        void onResult(String text);

        void onError(String error);
    }
    
    /**
     * 检查是否正在监听
     */
    public boolean isListening() {
        return asrManager != null && asrManager.isListening();
    }

    public void startListening(VoiceCallback callback) {
        if (asrManager == null) {
            Log.e(TAG, "AsrManager not initialized.");
            if (callback != null) mainHandler.post(() -> callback.onError("语音识别服务未初始化"));
            return;
        }
        
        if (!asrManager.isAvailable()) {
            Log.e(TAG, "No ASR engine available.");
            if (callback != null) mainHandler.post(() -> callback.onError("语音识别服务不可用，请配置讯飞 ASR 或检查系统语音服务"));
            return;
        }
        
        this.currentVoiceCallback = callback;
        
        asrManager.startListening(new AsrEngine.AsrCallback() {
            @Override
            public void onResult(String text) {
                Log.i(TAG, "ASR Result: " + text);
                if (currentVoiceCallback != null) {
                    VoiceCallback cb = currentVoiceCallback;
                    currentVoiceCallback = null;
                    mainHandler.post(() -> cb.onResult(text));
                }
            }
            
            @Override
            public void onPartialResult(String partialText) {
                // 可以在这里处理实时识别结果
                Log.d(TAG, "ASR Partial: " + partialText);
            }
            
            @Override
            public void onError(String error) {
                Log.e(TAG, "ASR Error: " + error);
                if (currentVoiceCallback != null) {
                    VoiceCallback cb = currentVoiceCallback;
                    currentVoiceCallback = null;
                    mainHandler.post(() -> cb.onError(error));
                }
            }
        });
        
        Log.d(TAG, "startListening... 使用引擎: " + asrManager.getCurrentEngineName());
    }
    
    /**
     * 停止语音监听
     */
    public void stopListening() {
        Log.d(TAG, "stopListening 被调用");
        if (asrManager != null) {
            asrManager.stopListening();
        }
    }
    
    /**
     * 取消语音监听
     */
    public void cancelListening() {
        Log.d(TAG, "cancelListening 被调用");
        if (asrManager != null) {
            asrManager.cancel();
        }
        currentVoiceCallback = null;
    }

    public void destroy() {
        Log.d(TAG, "正在销毁 VoiceManager 资源");
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (asrManager != null) {
            asrManager.release();
        }
        instance = null;
    }

    private static class PendingUtterance {
        String text;
        boolean isImmediate;
        Runnable onDoneCallback;

        PendingUtterance(String t, boolean i, Runnable c) {
            text = t;
            isImmediate = i;
            onDoneCallback = c;
        }
    }
}