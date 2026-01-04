package com.example.test_android_dev.asr;

/**
 * ASR 语音识别引擎抽象接口
 * 支持多种语音识别服务的统一接口
 */
public interface AsrEngine {
    
    /**
     * ASR 识别结果回调
     */
    interface AsrCallback {
        void onResult(String text);
        void onPartialResult(String partialText);
        void onError(String error);
    }
    
    /**
     * 初始化引擎
     */
    void init();
    
    /**
     * 开始语音识别
     */
    void startRecognition(AsrCallback callback);
    
    /**
     * 发送音频数据（用于流式识别）
     */
    void feedAudioData(byte[] audioData);
    
    /**
     * 停止语音识别
     */
    void stopRecognition();
    
    /**
     * 取消语音识别
     */
    void cancel();
    
    /**
     * 是否正在识别
     */
    boolean isRecognizing();
    
    /**
     * 释放资源
     */
    void release();
    
    /**
     * 获取引擎名称
     */
    String getEngineName();
    
    /**
     * 检查引擎是否可用
     */
    boolean isAvailable();
}
