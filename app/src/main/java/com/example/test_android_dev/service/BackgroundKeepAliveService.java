package com.example.test_android_dev.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.test_android_dev.MainActivity;
import com.example.test_android_dev.R;
import com.example.test_android_dev.model.ConnectionState;

/**
 * 后台保活服务
 * 负责维持应用在后台时的运行状态，显示前台通知
 */
public class BackgroundKeepAliveService extends Service {
    private static final String TAG = "KeepAliveService";

    // 通知相关常量
    private static final String CHANNEL_ID = "autoglm_task_channel";
    private static final String CHANNEL_NAME = "AutoGLM任务通知";
    private static final int NOTIFICATION_ID = 1001;

    // Action常量
    public static final String ACTION_START = "com.example.test_android_dev.START_TASK";
    public static final String ACTION_STOP = "com.example.test_android_dev.STOP_TASK";
    public static final String ACTION_UPDATE = "com.example.test_android_dev.UPDATE_NOTIFICATION";

    // Extra常量
    public static final String EXTRA_TASK_DESCRIPTION = "task_description";
    public static final String EXTRA_CONNECTION_STATE = "connection_state";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_RECONNECT_ATTEMPT = "reconnect_attempt";
    public static final String EXTRA_NEXT_RETRY_MS = "next_retry_ms";

    private static BackgroundKeepAliveService instance;
    private NotificationManager notificationManager;
    private String currentTaskDescription;
    private ConnectionState currentConnectionState = ConnectionState.DISCONNECTED;

    /**
     * 服务状态枚举
     */
    public enum ServiceState {
        IDLE, RUNNING, RECONNECTING, ERROR
    }

    private ServiceState serviceState = ServiceState.IDLE;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
        Log.d(TAG, "BackgroundKeepAliveService已创建");
    }

    public static BackgroundKeepAliveService getInstance() {
        return instance;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_STICKY;
        }

        String action = intent.getAction();
        if (action == null) {
            action = ACTION_START;
        }

        switch (action) {
            case ACTION_START:
                String taskDescription = intent.getStringExtra(EXTRA_TASK_DESCRIPTION);
                startForegroundTask(taskDescription);
                break;

            case ACTION_STOP:
                stopForegroundTask();
                break;

            case ACTION_UPDATE:
                handleUpdateNotification(intent);
                break;
        }

        // 返回START_STICKY，系统杀死服务后会尝试重启
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        Log.d(TAG, "BackgroundKeepAliveService已销毁");
    }

    /**
     * 创建通知渠道（Android 8.0+）
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW // 低重要性，不发出声音
            );
            channel.setDescription("AutoGLM任务执行状态通知");
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    /**
     * 启动前台任务
     */
    public void startForegroundTask(String taskDescription) {
        this.currentTaskDescription = taskDescription != null ? taskDescription : "执行任务中...";
        this.serviceState = ServiceState.RUNNING;

        Notification notification = buildNotification(
                "任务执行中",
                currentTaskDescription,
                false
        );

        startForeground(NOTIFICATION_ID, notification);
        Log.d(TAG, "前台服务已启动: " + currentTaskDescription);
    }

    /**
     * 停止前台任务
     */
    public void stopForegroundTask() {
        this.serviceState = ServiceState.IDLE;
        stopForeground(true);
        stopSelf();
        Log.d(TAG, "前台服务已停止");
    }

    /**
     * 更新通知
     */
    public void updateNotification(ServiceState state, String message) {
        this.serviceState = state;
        String title;
        boolean showProgress = false;

        switch (state) {
            case RUNNING:
                title = "任务执行中";
                break;
            case RECONNECTING:
                title = "正在重连...";
                showProgress = true;
                break;
            case ERROR:
                title = "连接错误";
                break;
            default:
                title = "AutoGLM";
        }

        Notification notification = buildNotification(title, message, showProgress);
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    /**
     * 更新连接状态显示
     */
    public void updateConnectionState(ConnectionState state) {
        this.currentConnectionState = state;
        String statusText = currentTaskDescription + "\n连接状态: " + state.getDisplayName();
        
        ServiceState serviceState = state == ConnectionState.RECONNECTING ? 
                ServiceState.RECONNECTING : ServiceState.RUNNING;
        
        updateNotification(serviceState, statusText);
    }

    /**
     * 显示重连通知
     */
    public void showReconnectingNotification(int attemptCount, long nextRetryMs) {
        String message = String.format("第%d次重连，%d秒后重试...", 
                attemptCount, nextRetryMs / 1000);
        updateNotification(ServiceState.RECONNECTING, message);
    }

    /**
     * 显示错误通知并振动
     */
    public void showErrorNotification(String errorMessage) {
        updateNotification(ServiceState.ERROR, errorMessage);
        vibrateDevice();
    }

    /**
     * 显示任务完成通知
     */
    public void showTaskCompleteNotification(String result) {
        Notification notification = buildNotification(
                "任务完成",
                result != null ? result : "任务已成功完成",
                false
        );
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    /**
     * 构建通知
     */
    private Notification buildNotification(String title, String content, boolean showProgress) {
        // 点击通知打开应用
        Intent contentIntent = new Intent(this, MainActivity.class);
        contentIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPendingIntent = PendingIntent.getActivity(
                this, 0, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // 停止任务按钮
        Intent stopIntent = new Intent(this, BackgroundKeepAliveService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(content)
                .setContentIntent(contentPendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPendingIntent);

        if (showProgress) {
            builder.setProgress(0, 0, true); // 不确定进度
        }

        // 显示连接状态
        if (currentConnectionState != null) {
            builder.setSubText(currentConnectionState.getDisplayName());
        }

        return builder.build();
    }

    /**
     * 振动设备
     */
    private void vibrateDevice() {
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(500);
            }
        }
    }

    /**
     * 处理更新通知的Intent
     */
    private void handleUpdateNotification(Intent intent) {
        String connectionStateStr = intent.getStringExtra(EXTRA_CONNECTION_STATE);
        String message = intent.getStringExtra(EXTRA_MESSAGE);
        int reconnectAttempt = intent.getIntExtra(EXTRA_RECONNECT_ATTEMPT, 0);
        long nextRetryMs = intent.getLongExtra(EXTRA_NEXT_RETRY_MS, 0);

        if (connectionStateStr != null) {
            try {
                ConnectionState state = ConnectionState.valueOf(connectionStateStr);
                updateConnectionState(state);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "无效的连接状态: " + connectionStateStr);
            }
        }

        if (reconnectAttempt > 0) {
            showReconnectingNotification(reconnectAttempt, nextRetryMs);
        } else if (message != null) {
            updateNotification(serviceState, message);
        }
    }

    // ==================== 静态辅助方法 ====================

    /**
     * 启动前台服务
     */
    public static void start(Context context, String taskDescription) {
        Intent intent = new Intent(context, BackgroundKeepAliveService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_TASK_DESCRIPTION, taskDescription);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /**
     * 停止前台服务
     */
    public static void stop(Context context) {
        Intent intent = new Intent(context, BackgroundKeepAliveService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    /**
     * 更新通知
     */
    public static void update(Context context, ConnectionState state) {
        Intent intent = new Intent(context, BackgroundKeepAliveService.class);
        intent.setAction(ACTION_UPDATE);
        intent.putExtra(EXTRA_CONNECTION_STATE, state.name());
        context.startService(intent);
    }
}
