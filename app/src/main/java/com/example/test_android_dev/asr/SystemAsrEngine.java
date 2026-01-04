package com.example.test_android_dev.asr;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;

/**
 * 系统原生 ASR 引擎
 * 使用 Android 系统自带的 SpeechRecognizer
 * 在有 Google 服务或国产手机自带语音服务时可用
 */
public class SystemAsrEngine implements AsrEngine {
    
    private static final String TAG = "SystemAsrEngine";
    
    private final Context context;
    private SpeechRecognizer speechRecognizer;
    private AsrCallback currentCallback;
    private volatile boolean isRecognizing = false;
    private volatile boolean isPendingStop = false;
    private volatile boolean isCancelled = false; // 标记是否被用户取消
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // 超时任务
    private Runnable timeoutRunnable;
    
    // 防止识别器繁忙
    private long lastStopTime = 0;
    private static final long MIN_RESTART_INTERVAL = 300;
    
    public SystemAsrEngine(Context context) {
        this.context = context.getApplicationContext();
    }
    
    @Override
    public void init() {
        createRecognizer();
    }
    
    /**
     * 创建或重建 SpeechRecognizer
     */
    private void createRecognizer() {
        if (!isAvailable()) {
            Log.w(TAG, "语音识别服务不可用");
            return;
        }
        
        mainHandler.post(() -> {
            try {
                // 先销毁旧的
                if (speechRecognizer != null) {
                    try {
                        speechRecognizer.destroy();
                    } catch (Exception e) {
                        Log.e(TAG, "销毁旧 SpeechRecognizer 异常", e);
                    }
                }
                
                // 创建新的
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
                speechRecognizer.setRecognitionListener(recognitionListener);
                Log.d(TAG, "SpeechRecognizer 创建完成");
            } catch (Exception e) {
                Log.e(TAG, "创建 SpeechRecognizer 失败", e);
                speechRecognizer = null;
            }
        });
    }
    
    @Override
    public void startRecognition(AsrCallback callback) {
        // 检查是否需要等待上次停止完成
        long timeSinceLastStop = System.currentTimeMillis() - lastStopTime;
        if (timeSinceLastStop < MIN_RESTART_INTERVAL) {
            long waitTime = MIN_RESTART_INTERVAL - timeSinceLastStop;
            Log.d(TAG, "等待 " + waitTime + "ms 后开始识别");
            mainHandler.postDelayed(() -> doStartRecognition(callback), waitTime);
            return;
        }
        
        doStartRecognition(callback);
    }
    
    private void doStartRecognition(AsrCallback callback) {
        if (speechRecognizer == null) {
            // 尝试重新创建
            createRecognizer();
            // 延迟启动
            mainHandler.postDelayed(() -> {
                if (speechRecognizer == null) {
                    if (callback != null) {
                        callback.onError("语音识别服务未初始化");
                    }
                } else {
                    startRecognitionInternal(callback);
                }
            }, 200);
            return;
        }
        
        startRecognitionInternal(callback);
    }
    
    private void startRecognitionInternal(AsrCallback callback) {
        if (isRecognizing || isPendingStop) {
            Log.w(TAG, "已在识别中或正在停止，先强制重置");
            destroyAndRecreate();
            mainHandler.postDelayed(() -> startRecognitionInternal(callback), 300);
            return;
        }
        
        this.currentCallback = callback;
        this.isRecognizing = true;
        this.isPendingStop = false;
        this.isCancelled = false;
        
        mainHandler.post(() -> {
            try {
                Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINA.toString());
                intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
                intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                speechRecognizer.startListening(intent);
                Log.d(TAG, "开始语音识别");
            } catch (Exception e) {
                Log.e(TAG, "启动语音识别失败", e);
                forceReset();
                if (callback != null) {
                    callback.onError("启动语音识别失败: " + e.getMessage());
                }
            }
        });
    }
    
    @Override
    public void feedAudioData(byte[] audioData) {
        // 系统 ASR 不支持手动喂数据，它自己录音
    }
    
    @Override
    public void stopRecognition() {
        Log.d(TAG, "stopRecognition 被调用, isRecognizing=" + isRecognizing);
        
        // 取消之前的超时任务
        cancelTimeoutTask();
        
        if (!isRecognizing) {
            Log.d(TAG, "未在识别中，忽略停止请求");
            return;
        }
        
        isPendingStop = true;
        
        mainHandler.post(() -> {
            if (speechRecognizer == null) {
                forceReset();
                return;
            }
            
            try {
                // 立即停止录音并销毁识别器
                speechRecognizer.stopListening();
                Log.d(TAG, "已调用 stopListening");
                
                // 立即销毁识别器以停止录音，但保留回调等待结果
                // 注意：这会触发 onResults 或 onError
                mainHandler.postDelayed(() -> {
                    if (speechRecognizer != null) {
                        try {
                            // 不要在这里销毁，让系统处理完结果
                            Log.d(TAG, "等待识别结果...");
                        } catch (Exception e) {
                            Log.e(TAG, "处理异常", e);
                        }
                    }
                }, 100);
                
                // 设置超时，如果 5 秒内没有结果就强制取消
                timeoutRunnable = () -> {
                    if (isRecognizing || isPendingStop) {
                        Log.w(TAG, "识别超时，强制重置");
                        AsrCallback cb = currentCallback;
                        currentCallback = null;
                        destroyAndRecreate();
                        if (cb != null) {
                            cb.onError("识别超时");
                        }
                    }
                };
                mainHandler.postDelayed(timeoutRunnable, 5000);
            } catch (Exception e) {
                Log.e(TAG, "停止识别异常", e);
                destroyAndRecreate();
            }
        });
    }
    
    @Override
    public void cancel() {
        Log.d(TAG, "cancel 被调用, isRecognizing=" + isRecognizing);
        
        // 取消超时任务
        cancelTimeoutTask();
        
        // 标记为已取消
        isCancelled = true;
        
        // 清除回调，防止后续回调
        currentCallback = null;
        
        // 销毁并重建识别器，这是唯一能确保完全停止的方法
        destroyAndRecreate();
        
        Log.d(TAG, "cancel 完成");
    }
    
    /**
     * 取消超时任务
     */
    private void cancelTimeoutTask() {
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }
    
    /**
     * 销毁并重建 SpeechRecognizer
     * 这是确保完全停止录音的唯一可靠方法
     */
    private void destroyAndRecreate() {
        Log.d(TAG, "destroyAndRecreate 开始");
        forceReset();
        
        mainHandler.post(() -> {
            try {
                if (speechRecognizer != null) {
                    speechRecognizer.cancel();
                    speechRecognizer.destroy();
                    speechRecognizer = null;
                    Log.d(TAG, "SpeechRecognizer 已销毁");
                }
            } catch (Exception e) {
                Log.e(TAG, "销毁 SpeechRecognizer 异常", e);
                speechRecognizer = null;
            }
            
            // 延迟重建
            mainHandler.postDelayed(() -> {
                try {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
                    speechRecognizer.setRecognitionListener(recognitionListener);
                    Log.d(TAG, "SpeechRecognizer 已重建");
                } catch (Exception e) {
                    Log.e(TAG, "重建 SpeechRecognizer 失败", e);
                }
            }, 100);
        });
    }
    
    private void forceReset() {
        isRecognizing = false;
        isPendingStop = false;
        lastStopTime = System.currentTimeMillis();
    }
    
    @Override
    public boolean isRecognizing() {
        return isRecognizing;
    }
    
    @Override
    public void release() {
        cancelTimeoutTask();
        forceReset();
        currentCallback = null;
        isCancelled = false;
        
        if (speechRecognizer != null) {
            mainHandler.post(() -> {
                try {
                    speechRecognizer.cancel();
                    speechRecognizer.destroy();
                } catch (Exception e) {
                    Log.e(TAG, "销毁 SpeechRecognizer 异常", e);
                }
                speechRecognizer = null;
            });
        }
    }
    
    @Override
    public String getEngineName() {
        return "系统语音识别";
    }
    
    @Override
    public boolean isAvailable() {
        return SpeechRecognizer.isRecognitionAvailable(context);
    }
    
    private final RecognitionListener recognitionListener = new RecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle params) {
            Log.d(TAG, "onReadyForSpeech - 准备接收语音");
        }

        @Override
        public void onBeginningOfSpeech() {
            Log.d(TAG, "onBeginningOfSpeech - 检测到用户开始说话");
        }

        @Override
        public void onRmsChanged(float rmsdB) {
            // 音量变化，不打印避免日志过多
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
            // 只在录音中且未停止时打印，避免日志过多
            if (isRecognizing && !isPendingStop && !isCancelled && buffer != null && buffer.length > 0) {
                Log.v(TAG, "onBufferReceived - 收到音频数据: " + buffer.length + " 字节");
            }
        }

        @Override
        public void onEndOfSpeech() {
            Log.d(TAG, "onEndOfSpeech - 检测到用户停止说话，开始识别...");
        }

        @Override
        public void onError(int error) {
            String errorMsg = getErrorText(error);
            Log.e(TAG, "ASR Error: " + errorMsg + " (code=" + error + ")");
            
            cancelTimeoutTask();
            forceReset();
            
            // 如果已取消或回调已清除，不回调
            if (isCancelled || currentCallback == null) {
                Log.d(TAG, "已取消或回调已清除，忽略错误");
                isCancelled = false;
                return;
            }
            
            AsrCallback cb = currentCallback;
            currentCallback = null;
            cb.onError(errorMsg);
        }

        @Override
        public void onResults(Bundle results) {
            Log.d(TAG, "onResults - 收到识别结果");
            
            cancelTimeoutTask();
            forceReset();
            
            // 如果已取消，忽略结果
            if (isCancelled) {
                Log.d(TAG, "已取消，忽略结果");
                isCancelled = false;
                return;
            }
            
            ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches != null && !matches.isEmpty()) {
                String text = matches.get(0);
                Log.i(TAG, "识别结果: " + text);
                if (currentCallback != null) {
                    AsrCallback cb = currentCallback;
                    currentCallback = null;
                    cb.onResult(text);
                }
            } else {
                if (currentCallback != null) {
                    AsrCallback cb = currentCallback;
                    currentCallback = null;
                    cb.onError("未听到语音输入");
                }
            }
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            if (isCancelled || currentCallback == null) {
                return;
            }
            ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches != null && !matches.isEmpty()) {
                currentCallback.onPartialResult(matches.get(0));
            }
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }
    };
    
    private String getErrorText(int errorCode) {
        switch (errorCode) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "音频录制错误";
            case SpeechRecognizer.ERROR_CLIENT:
                return "客户端错误";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "权限不足";
            case SpeechRecognizer.ERROR_NETWORK:
                return "网络错误";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "网络超时";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "未匹配到结果";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "识别器繁忙";
            case SpeechRecognizer.ERROR_SERVER:
                return "服务器错误";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "未检测到语音输入";
            default:
                return "未知错误";
        }
    }
}
