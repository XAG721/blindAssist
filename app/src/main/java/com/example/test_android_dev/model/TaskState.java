package com.example.test_android_dev.model;

import java.io.Serializable;
import java.util.UUID;

/**
 * 任务状态数据类
 * 用于持久化和恢复任务执行状态
 */
public class TaskState implements Serializable {
    private static final long serialVersionUID = 1L;

    private String taskId;           // 任务唯一标识
    private String taskPrompt;       // 用户指令
    private int currentStep;         // 当前步骤
    private long startTime;          // 开始时间
    private long lastUpdateTime;     // 最后更新时间
    private int screenWidth;         // 屏幕宽度
    private int screenHeight;        // 屏幕高度
    private boolean isRunning;       // 是否正在运行

    public TaskState() {
        this.taskId = UUID.randomUUID().toString();
        this.currentStep = 0;
        this.startTime = System.currentTimeMillis();
        this.lastUpdateTime = this.startTime;
        this.isRunning = false;
    }

    public TaskState(String taskPrompt, int screenWidth, int screenHeight) {
        this();
        this.taskPrompt = taskPrompt;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
    }

    // Getters
    public String getTaskId() { return taskId; }
    public String getTaskPrompt() { return taskPrompt; }
    public int getCurrentStep() { return currentStep; }
    public long getStartTime() { return startTime; }
    public long getLastUpdateTime() { return lastUpdateTime; }
    public int getScreenWidth() { return screenWidth; }
    public int getScreenHeight() { return screenHeight; }
    public boolean isRunning() { return isRunning; }

    // Setters
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public void setTaskPrompt(String taskPrompt) { this.taskPrompt = taskPrompt; }
    public void setCurrentStep(int currentStep) { this.currentStep = currentStep; }
    public void setStartTime(long startTime) { this.startTime = startTime; }
    public void setLastUpdateTime(long lastUpdateTime) { this.lastUpdateTime = lastUpdateTime; }
    public void setScreenWidth(int screenWidth) { this.screenWidth = screenWidth; }
    public void setScreenHeight(int screenHeight) { this.screenHeight = screenHeight; }
    public void setRunning(boolean running) { this.isRunning = running; }

    /**
     * 更新任务步骤
     */
    public void incrementStep() {
        this.currentStep++;
        this.lastUpdateTime = System.currentTimeMillis();
    }

    /**
     * 标记任务开始运行
     */
    public void markRunning() {
        this.isRunning = true;
        this.lastUpdateTime = System.currentTimeMillis();
    }

    /**
     * 标记任务停止
     */
    public void markStopped() {
        this.isRunning = false;
        this.lastUpdateTime = System.currentTimeMillis();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TaskState taskState = (TaskState) o;
        return currentStep == taskState.currentStep &&
                startTime == taskState.startTime &&
                screenWidth == taskState.screenWidth &&
                screenHeight == taskState.screenHeight &&
                isRunning == taskState.isRunning &&
                taskId.equals(taskState.taskId) &&
                (taskPrompt != null ? taskPrompt.equals(taskState.taskPrompt) : taskState.taskPrompt == null);
    }

    @Override
    public int hashCode() {
        int result = taskId.hashCode();
        result = 31 * result + (taskPrompt != null ? taskPrompt.hashCode() : 0);
        result = 31 * result + currentStep;
        return result;
    }

    @Override
    public String toString() {
        return "TaskState{" +
                "taskId='" + taskId + '\'' +
                ", taskPrompt='" + taskPrompt + '\'' +
                ", currentStep=" + currentStep +
                ", isRunning=" + isRunning +
                '}';
    }
}
