package com.example.test_android_dev.asr;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Vosk 离线语音识别引擎
 * 
 * 使用前需要：
 * 1. 在 build.gradle 添加依赖: implementation 'com.alphacephei:vosk-android:0.3.47'
 * 2. 下载中文模型并放到 assets 目录
 *    模型下载: https://alphacephei.com/vosk/models
 *    推荐: vosk-model-small-cn-0.22 (约50MB)
 *    
 * 支持的目录结构：
 * - assets/model-cn/vosk-model-small-cn-0.22/  (下载后直接放入)
 * - assets/model-cn/  (解压后的内容直接放入)
 */
public class VoskAsrEngine implements AsrEngine {
    
    private static final String TAG = "VoskAsrEngine";
    // 支持多种模型路径
    private static final String[] MODEL_PATHS = {
            "model-cn/vosk-model-small-cn-0.22",  // 下载后直接放入的格式
            "model-cn",                            // 解压后内容直接放入的格式
            "vosk-model-small-cn-0.22"            // 直接放在 assets 根目录
    };
    private String actualModelPath = null;
    private static final int SAMPLE_RATE = 16000;
    
    private final Context context;
    private Model model;
    private SpeechService speechService;
    private AsrCallback currentCallback;
    private boolean isRecognizing = false;
    private boolean isModelLoaded = false;
    private boolean isModelLoading = false;
    private boolean modelFileExists = false; // 模型文件是否存在
    
    // 模型加载状态回调
    private ModelLoadCallback modelLoadCallback;
    
    public interface ModelLoadCallback {
        void onLoadStart();
        void onLoadProgress(String message);
        void onLoadComplete();
        void onLoadFailed(String error);
    }
    
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    
    public VoskAsrEngine(Context context) {
        this.context = context.getApplicationContext();
        // 检查模型文件是否存在
        this.modelFileExists = isModelAvailable(context);
        Log.d(TAG, "=== VoskAsrEngine 构造函数 ===");
        Log.d(TAG, "modelFileExists = " + modelFileExists);
    }
    
    @Override
    public void init() {
        Log.d(TAG, "=== init() 被调用 ===");
        Log.d(TAG, "init() 状态: isModelLoaded=" + isModelLoaded + ", isModelLoading=" + isModelLoading + ", modelFileExists=" + modelFileExists);
        
        if (isModelLoaded || isModelLoading) {
            Log.d(TAG, "init() 跳过: 模型已加载或正在加载");
            return;
        }
        
        // 查找可用的模型路径
        actualModelPath = findModelPath();
        Log.d(TAG, "findModelPath() 返回: " + actualModelPath);
        
        if (actualModelPath == null) {
            Log.e(TAG, "未找到 Vosk 模型文件，检查 assets 目录");
            // 打印所有尝试的路径
            for (String path : MODEL_PATHS) {
                Log.d(TAG, "尝试路径: " + path + " -> " + isValidModelPath(context, path));
            }
            if (modelLoadCallback != null) {
                mainHandler.post(() -> modelLoadCallback.onLoadFailed("未找到语音模型文件"));
            }
            return;
        }
        
        isModelLoading = true;
        Log.i(TAG, "=== 开始加载 Vosk 模型: " + actualModelPath + " ===");
        
        if (modelLoadCallback != null) {
            mainHandler.post(() -> {
                modelLoadCallback.onLoadStart();
                modelLoadCallback.onLoadProgress("正在解压语音模型...");
            });
        }
        
        executor.execute(() -> {
            try {
                // 清理可能损坏的旧模型文件
                File modelDir = new File(context.getFilesDir(), "model");
                Log.d(TAG, "模型目标目录: " + modelDir.getAbsolutePath());
                Log.d(TAG, "目标目录是否存在: " + modelDir.exists());
                
                if (modelDir.exists()) {
                    Log.d(TAG, "清理旧模型目录...");
                    deleteRecursive(modelDir);
                    Log.d(TAG, "旧模型目录已清理");
                }
                
                Log.d(TAG, "=== 开始调用 StorageService.unpack() ===");
                Log.d(TAG, "源路径: " + actualModelPath);
                Log.d(TAG, "目标名称: model");
                
                // 从 assets 解压模型到内部存储
                StorageService.unpack(context, actualModelPath, "model",
                        (model) -> {
                            Log.d(TAG, "=== StorageService.unpack() 成功回调 ===");
                            Log.d(TAG, "model 对象: " + (model != null ? "非空" : "null"));
                            this.model = model;
                            isModelLoaded = true;
                            isModelLoading = false;
                            Log.i(TAG, "Vosk 模型加载成功！isModelLoaded=" + isModelLoaded);
                            if (modelLoadCallback != null) {
                                mainHandler.post(() -> modelLoadCallback.onLoadComplete());
                            }
                        },
                        (exception) -> {
                            Log.e(TAG, "=== StorageService.unpack() 失败回调 ===");
                            Log.e(TAG, "异常类型: " + exception.getClass().getName());
                            Log.e(TAG, "异常消息: " + exception.getMessage());
                            isModelLoading = false;
                            isModelLoaded = false;
                            Log.e(TAG, "Vosk 模型加载失败: " + exception.getMessage(), exception);
                            if (modelLoadCallback != null) {
                                mainHandler.post(() -> modelLoadCallback.onLoadFailed("模型加载失败: " + exception.getMessage()));
                            }
                        });
            } catch (Exception e) {
                isModelLoading = false;
                isModelLoaded = false;
                Log.e(TAG, "Vosk 初始化异常: " + e.getMessage(), e);
                if (modelLoadCallback != null) {
                    mainHandler.post(() -> modelLoadCallback.onLoadFailed("初始化异常: " + e.getMessage()));
                }
            }
        });
    }
    
    /**
     * 查找可用的模型路径
     */
    private String findModelPath() {
        for (String path : MODEL_PATHS) {
            if (isValidModelPath(context, path)) {
                Log.d(TAG, "找到模型路径: " + path);
                return path;
            }
        }
        return null;
    }
    
    /**
     * 检查模型路径是否有效（包含必要的模型文件）
     */
    private static boolean isValidModelPath(Context context, String path) {
        try {
            String[] files = context.getAssets().list(path);
            if (files == null || files.length == 0) {
                return false;
            }
            // 检查是否包含 Vosk 模型的关键文件/目录
            for (String file : files) {
                if (file.equals("am") || file.equals("conf") || file.equals("graph") || 
                    file.equals("final.mdl") || file.equals("mfcc.conf")) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }
    
    @Override
    public void startRecognition(AsrCallback callback) {
        Log.d(TAG, "=== startRecognition() 被调用 ===");
        Log.d(TAG, "startRecognition() 状态: isModelLoaded=" + isModelLoaded + ", isModelLoading=" + isModelLoading + ", model=" + (model != null ? "非空" : "null"));
        
        // 如果模型还在加载，等待加载完成
        if (isModelLoading) {
            Log.d(TAG, "模型正在加载中，等待...");
            waitForModelAndStart(callback, 10); // 最多等待 10 次（约 5 秒）
            return;
        }
        
        if (!isModelLoaded || model == null) {
            Log.e(TAG, "startRecognition() 失败: 模型未加载! isModelLoaded=" + isModelLoaded + ", model=" + (model != null ? "非空" : "null"));
            if (callback != null) {
                callback.onError("离线语音模型未加载");
            }
            return;
        }
        
        startRecognitionInternal(callback);
    }
    
    /**
     * 等待模型加载完成后开始识别
     */
    private void waitForModelAndStart(AsrCallback callback, int retryCount) {
        if (retryCount <= 0) {
            if (callback != null) {
                callback.onError("模型加载超时");
            }
            return;
        }
        
        mainHandler.postDelayed(() -> {
            if (isModelLoaded && model != null) {
                startRecognitionInternal(callback);
            } else if (isModelLoading) {
                waitForModelAndStart(callback, retryCount - 1);
            } else {
                if (callback != null) {
                    callback.onError("模型加载失败");
                }
            }
        }, 500);
    }
    
    private void startRecognitionInternal(AsrCallback callback) {
        if (isRecognizing) {
            Log.w(TAG, "已在识别中");
            return;
        }
        
        this.currentCallback = callback;
        this.isRecognizing = true;
        
        try {
            Recognizer recognizer = new Recognizer(model, SAMPLE_RATE);
            speechService = new SpeechService(recognizer, SAMPLE_RATE);
            
            speechService.startListening(new RecognitionListener() {
                @Override
                public void onPartialResult(String hypothesis) {
                    try {
                        JSONObject json = new JSONObject(hypothesis);
                        String partial = json.optString("partial", "");
                        if (!partial.isEmpty()) {
                            AsrCallback cb = currentCallback;
                            if (cb != null) {
                                mainHandler.post(() -> cb.onPartialResult(partial));
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "解析部分结果失败", e);
                    }
                }
                
                @Override
                public void onResult(String hypothesis) {
                    try {
                        JSONObject json = new JSONObject(hypothesis);
                        String text = json.optString("text", "");
                        Log.d(TAG, "识别结果: " + text);
                        
                        if (!text.isEmpty()) {
                            AsrCallback cb = currentCallback;
                            if (cb != null) {
                                mainHandler.post(() -> cb.onResult(text));
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "解析结果失败", e);
                    }
                }
                
                @Override
                public void onFinalResult(String hypothesis) {
                    isRecognizing = false;
                    try {
                        JSONObject json = new JSONObject(hypothesis);
                        String text = json.optString("text", "");
                        Log.d(TAG, "最终结果: " + text);
                        
                        if (currentCallback != null) {
                            AsrCallback cb = currentCallback;
                            currentCallback = null;
                            if (!text.isEmpty()) {
                                mainHandler.post(() -> cb.onResult(text));
                            } else {
                                mainHandler.post(() -> cb.onError("未听到语音输入"));
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "解析最终结果失败", e);
                        if (currentCallback != null) {
                            AsrCallback cb = currentCallback;
                            currentCallback = null;
                            mainHandler.post(() -> cb.onError("识别失败"));
                        }
                    }
                }
                
                @Override
                public void onError(Exception exception) {
                    isRecognizing = false;
                    Log.e(TAG, "Vosk 识别错误", exception);
                    if (currentCallback != null) {
                        AsrCallback cb = currentCallback;
                        currentCallback = null;
                        mainHandler.post(() -> cb.onError("识别错误: " + exception.getMessage()));
                    }
                }
                
                @Override
                public void onTimeout() {
                    isRecognizing = false;
                    Log.d(TAG, "Vosk 识别超时");
                    if (currentCallback != null) {
                        AsrCallback cb = currentCallback;
                        currentCallback = null;
                        mainHandler.post(() -> cb.onError("未检测到语音输入"));
                    }
                }
            });
            
            Log.d(TAG, "Vosk 开始识别");
            
        } catch (Exception e) {
            isRecognizing = false;
            Log.e(TAG, "启动 Vosk 识别失败", e);
            if (callback != null) {
                callback.onError("启动识别失败: " + e.getMessage());
            }
        }
    }
    
    @Override
    public void feedAudioData(byte[] audioData) {
        // Vosk SpeechService 自己录音，不需要外部喂数据
    }
    
    @Override
    public void stopRecognition() {
        Log.d(TAG, "停止 Vosk 识别");
        if (speechService != null) {
            speechService.stop();
        }
        isRecognizing = false;
    }
    
    @Override
    public void cancel() {
        Log.d(TAG, "取消 Vosk 识别");
        if (speechService != null) {
            speechService.cancel();
            speechService.shutdown();
            speechService = null;
        }
        isRecognizing = false;
        currentCallback = null;
    }
    
    @Override
    public boolean isRecognizing() {
        return isRecognizing;
    }
    
    @Override
    public void release() {
        cancel();
        if (model != null) {
            model.close();
            model = null;
        }
        isModelLoaded = false;
        executor.shutdown();
    }
    
    @Override
    public String getEngineName() {
        return "Vosk离线识别";
    }
    
    @Override
    public boolean isAvailable() {
        // 只要模型文件存在就认为可用（模型会在首次使用时加载）
        // 如果模型正在加载或已加载，也认为可用
        boolean available = modelFileExists || isModelLoading || isModelLoaded;
        Log.d(TAG, "isAvailable() 返回: " + available + " (modelFileExists=" + modelFileExists + ", isModelLoading=" + isModelLoading + ", isModelLoaded=" + isModelLoaded + ")");
        return available;
    }
    
    /**
     * 检查模型是否已加载完成
     */
    public boolean isModelReady() {
        return isModelLoaded && model != null;
    }
    
    /**
     * 检查模型是否正在加载
     */
    public boolean isModelLoading() {
        return isModelLoading;
    }
    
    /**
     * 设置模型加载回调
     */
    public void setModelLoadCallback(ModelLoadCallback callback) {
        this.modelLoadCallback = callback;
        // 如果已经加载完成，立即回调
        if (isModelLoaded) {
            if (callback != null) {
                mainHandler.post(callback::onLoadComplete);
            }
        } else if (isModelLoading) {
            if (callback != null) {
                mainHandler.post(() -> {
                    callback.onLoadStart();
                    callback.onLoadProgress("正在加载语音模型...");
                });
            }
        }
    }
    
    /**
     * 检查模型文件是否存在于 assets
     */
    public static boolean isModelAvailable(Context context) {
        Log.d(TAG, "=== isModelAvailable() 检查开始 ===");
        for (String path : MODEL_PATHS) {
            Log.d(TAG, "检查路径: " + path);
            boolean valid = isValidModelPathStatic(context, path);
            Log.d(TAG, "路径 " + path + " 有效: " + valid);
            if (valid) {
                Log.d(TAG, "找到可用的 Vosk 模型: " + path);
                return true;
            }
        }
        Log.w(TAG, "未找到 Vosk 模型");
        return false;
    }
    
    private static boolean isValidModelPathStatic(Context context, String path) {
        try {
            String[] files = context.getAssets().list(path);
            Log.d(TAG, "assets.list('" + path + "') 返回: " + (files != null ? files.length + " 个文件" : "null"));
            if (files != null && files.length > 0) {
                StringBuilder sb = new StringBuilder();
                for (String f : files) {
                    sb.append(f).append(", ");
                }
                Log.d(TAG, "文件列表: " + sb.toString());
            }
            
            if (files == null || files.length == 0) {
                return false;
            }
            // 检查是否包含 Vosk 模型的关键文件/目录
            for (String file : files) {
                if (file.equals("am") || file.equals("conf") || file.equals("graph") || 
                    file.equals("final.mdl") || file.equals("mfcc.conf")) {
                    Log.d(TAG, "找到关键文件/目录: " + file);
                    return true;
                }
            }
            Log.d(TAG, "路径 " + path + " 不包含 Vosk 模型关键文件");
            return false;
        } catch (IOException e) {
            Log.e(TAG, "检查路径 " + path + " 时出错: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 递归删除目录
     */
    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        fileOrDirectory.delete();
    }
}
