package com.example.test_android_dev.manager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

/**
 * 唤醒锁管理器
 * 防止设备在任务执行期间进入休眠状态
 */
public class WakeLockManager {
    private static final String TAG = "WakeLockManager";
    private static final String WAKE_LOCK_TAG = "AutoGLM:TaskWakeLock";
    private static final long MAX_WAKE_LOCK_DURATION_MS = 30 * 60 * 1000; // 30分钟
    private static final long WARNING_CHECK_INTERVAL_MS = 60 * 1000; // 每分钟检查一次

    private static WakeLockManager instance;
    private PowerManager.WakeLock wakeLock;
    private Context context;
    private long acquireTime;
    private Handler warningHandler;
    private Runnable warningRunnable;
    private WakeLockCallback callback;

    /**
     * 唤醒锁回调接口
     */
    public interface WakeLockCallback {
        void onWakeLockWarning(long heldDurationMs);
    }

    private WakeLockManager() {
        warningHandler = new Handler(Looper.getMainLooper());
    }

    public static synchronized WakeLockManager getInstance() {
        if (instance == null) {
            instance = new WakeLockManager();
        }
        return instance;
    }

    /**
     * 初始化（需要在使用前调用）
     */
    public void init(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * 获取唤醒锁
     * @param callback 超时警告回调
     */
    public synchronized void acquire(WakeLockCallback callback) {
        if (context == null) {
            Log.e(TAG, "WakeLockManager未初始化，请先调用init()");
            return;
        }

        this.callback = callback;

        if (wakeLock != null && wakeLock.isHeld()) {
            Log.w(TAG, "唤醒锁已持有，跳过重复获取");
            return;
        }

        try {
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        WAKE_LOCK_TAG
                );
                wakeLock.acquire();
                acquireTime = System.currentTimeMillis();
                Log.d(TAG, "唤醒锁已获取");

                // 启动超时警告检查
                startWarningCheck();
            }
        } catch (Exception e) {
            Log.e(TAG, "获取唤醒锁失败: " + e.getMessage());
        }
    }

    /**
     * 释放唤醒锁
     */
    public synchronized void release() {
        stopWarningCheck();

        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
                long heldDuration = System.currentTimeMillis() - acquireTime;
                Log.d(TAG, "唤醒锁已释放，持有时长: " + heldDuration + "ms");
            } catch (Exception e) {
                Log.e(TAG, "释放唤醒锁失败: " + e.getMessage());
            }
        }
        wakeLock = null;
        callback = null;
    }

    /**
     * 检查唤醒锁是否被持有
     */
    public synchronized boolean isHeld() {
        return wakeLock != null && wakeLock.isHeld();
    }

    /**
     * 获取唤醒锁持有时长
     */
    public synchronized long getHeldDuration() {
        if (!isHeld()) {
            return 0;
        }
        return System.currentTimeMillis() - acquireTime;
    }

    /**
     * 启动超时警告检查
     */
    private void startWarningCheck() {
        stopWarningCheck();
        warningRunnable = new Runnable() {
            @Override
            public void run() {
                checkWarning();
                warningHandler.postDelayed(this, WARNING_CHECK_INTERVAL_MS);
            }
        };
        warningHandler.postDelayed(warningRunnable, WARNING_CHECK_INTERVAL_MS);
    }

    /**
     * 停止超时警告检查
     */
    private void stopWarningCheck() {
        if (warningRunnable != null) {
            warningHandler.removeCallbacks(warningRunnable);
            warningRunnable = null;
        }
    }

    /**
     * 检查是否需要发出警告
     */
    private void checkWarning() {
        long heldDuration = getHeldDuration();
        if (heldDuration >= MAX_WAKE_LOCK_DURATION_MS) {
            Log.w(TAG, "唤醒锁持有超过30分钟: " + heldDuration + "ms");
            if (callback != null) {
                callback.onWakeLockWarning(heldDuration);
            }
        }
    }

    /**
     * 强制释放（用于异常情况）
     */
    public synchronized void forceRelease() {
        stopWarningCheck();
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
            } catch (Exception e) {
                Log.e(TAG, "强制释放唤醒锁失败: " + e.getMessage());
            } finally {
                wakeLock = null;
                callback = null;
            }
        }
    }
}
