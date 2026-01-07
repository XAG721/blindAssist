package com.example.test_android_dev.manager;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.media.ToneGenerator;
import android.media.AudioManager;
import android.util.Log;

/**
 * 提示音管理器
 * 负责播放按住说话开始和结束时的提示音
 */
public class SoundManager {
    private static final String TAG = "SoundManager";
    
    private static SoundManager instance;
    private ToneGenerator toneGenerator;
    private boolean isInitialized = false;
    
    // 音调持续时间（毫秒）
    private static final int START_TONE_DURATION = 100;  // 开始录音提示音
    private static final int STOP_TONE_DURATION = 150;   // 结束录音提示音
    
    private SoundManager() {}
    
    public static synchronized SoundManager getInstance() {
        if (instance == null) {
            instance = new SoundManager();
        }
        return instance;
    }
    
    /**
     * 初始化音效管理器
     */
    public void init(Context context) {
        if (isInitialized) {
            return;
        }
        
        try {
            // 使用媒体音量流创建ToneGenerator
            toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 80); // 80%音量
            isInitialized = true;
            Log.d(TAG, "SoundManager 初始化成功");
        } catch (Exception e) {
            Log.e(TAG, "SoundManager 初始化失败", e);
        }
    }
    
    /**
     * 播放开始录音提示音（上升音调，表示开始）
     */
    public void playStartTone() {
        if (!isInitialized || toneGenerator == null) {
            Log.w(TAG, "SoundManager 未初始化");
            return;
        }
        
        try {
            // 使用DTMF音调，清脆的"嘟"声
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, START_TONE_DURATION);
            Log.d(TAG, "播放开始录音提示音");
        } catch (Exception e) {
            Log.e(TAG, "播放开始提示音失败", e);
        }
    }
    
    /**
     * 播放结束录音提示音（下降音调，表示结束）
     */
    public void playStopTone() {
        if (!isInitialized || toneGenerator == null) {
            Log.w(TAG, "SoundManager 未初始化");
            return;
        }
        
        try {
            // 使用不同的音调表示结束
            toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, STOP_TONE_DURATION);
            Log.d(TAG, "播放结束录音提示音");
        } catch (Exception e) {
            Log.e(TAG, "播放结束提示音失败", e);
        }
    }
    
    /**
     * 释放资源
     */
    public void release() {
        if (toneGenerator != null) {
            try {
                toneGenerator.release();
            } catch (Exception e) {
                Log.e(TAG, "释放ToneGenerator失败", e);
            }
            toneGenerator = null;
        }
        isInitialized = false;
        Log.d(TAG, "SoundManager 已释放");
    }
}
