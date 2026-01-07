package com.example.test_android_dev.manager;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.example.test_android_dev.model.TaskState;
import com.google.gson.Gson;

/**
 * 任务状态管理器
 * 负责任务状态的持久化和恢复
 */
public class TaskStateManager {
    private static final String TAG = "TaskStateManager";
    private static final String PREFS_NAME = "autoglm_task_state";
    private static final String KEY_TASK_STATE = "task_state";
    private static final String KEY_HAS_INCOMPLETE_TASK = "has_incomplete_task";

    private static TaskStateManager instance;
    private Context context;
    private SharedPreferences prefs;
    private Gson gson;

    /**
     * 任务恢复回调接口
     */
    public interface TaskRecoveryCallback {
        void onTaskRecovered(TaskState state);
        void onTaskDiscarded();
    }

    private TaskStateManager() {
        gson = new Gson();
    }

    public static synchronized TaskStateManager getInstance() {
        if (instance == null) {
            instance = new TaskStateManager();
        }
        return instance;
    }

    /**
     * 初始化（需要在使用前调用）
     */
    public void init(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 保存任务状态
     */
    public synchronized void saveState(TaskState state) {
        if (prefs == null) {
            Log.e(TAG, "TaskStateManager未初始化");
            return;
        }

        try {
            String json = gson.toJson(state);
            prefs.edit()
                    .putString(KEY_TASK_STATE, json)
                    .putBoolean(KEY_HAS_INCOMPLETE_TASK, state.isRunning())
                    .apply();
            Log.d(TAG, "任务状态已保存: " + state.getTaskId());
        } catch (Exception e) {
            Log.e(TAG, "保存任务状态失败: " + e.getMessage());
        }
    }

    /**
     * 加载任务状态
     */
    public synchronized TaskState loadState() {
        if (prefs == null) {
            Log.e(TAG, "TaskStateManager未初始化");
            return null;
        }

        try {
            String json = prefs.getString(KEY_TASK_STATE, null);
            if (json != null) {
                TaskState state = gson.fromJson(json, TaskState.class);
                Log.d(TAG, "任务状态已加载: " + (state != null ? state.getTaskId() : "null"));
                return state;
            }
        } catch (Exception e) {
            Log.e(TAG, "加载任务状态失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 清除任务状态
     */
    public synchronized void clearState() {
        if (prefs == null) {
            Log.e(TAG, "TaskStateManager未初始化");
            return;
        }

        prefs.edit()
                .remove(KEY_TASK_STATE)
                .putBoolean(KEY_HAS_INCOMPLETE_TASK, false)
                .apply();
        Log.d(TAG, "任务状态已清除");
    }

    /**
     * 检查是否有未完成的任务
     */
    public synchronized boolean hasIncompleteTask() {
        if (prefs == null) {
            return false;
        }
        return prefs.getBoolean(KEY_HAS_INCOMPLETE_TASK, false);
    }

    /**
     * 提示用户恢复任务
     */
    public void promptTaskRecovery(Context activityContext, TaskRecoveryCallback callback) {
        if (!hasIncompleteTask()) {
            callback.onTaskDiscarded();
            return;
        }

        TaskState state = loadState();
        if (state == null) {
            clearState();
            callback.onTaskDiscarded();
            return;
        }

        new AlertDialog.Builder(activityContext)
                .setTitle("发现未完成的任务")
                .setMessage("上次的任务未完成：\n" + state.getTaskPrompt() + "\n\n是否继续执行？")
                .setPositiveButton("继续", (dialog, which) -> {
                    Log.d(TAG, "用户选择恢复任务");
                    callback.onTaskRecovered(state);
                })
                .setNegativeButton("放弃", (dialog, which) -> {
                    Log.d(TAG, "用户选择放弃任务");
                    clearState();
                    callback.onTaskDiscarded();
                })
                .setCancelable(false)
                .show();
    }

    /**
     * 更新任务步骤（便捷方法）
     */
    public void updateStep(TaskState state) {
        if (state != null) {
            state.incrementStep();
            saveState(state);
        }
    }

    /**
     * 标记任务开始
     */
    public void markTaskStarted(TaskState state) {
        if (state != null) {
            state.markRunning();
            saveState(state);
        }
    }

    /**
     * 标记任务停止
     */
    public void markTaskStopped(TaskState state) {
        if (state != null) {
            state.markStopped();
            clearState();
        }
    }
}
