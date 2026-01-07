package com.example.test_android_dev.model;

/**
 * 连接状态详细信息类
 */
public class ConnectionStatus {
    private ConnectionState state;
    private long lastHeartbeatTime;
    private int reconnectAttempts;
    private String lastError;
    private long nextRetryTime;

    public ConnectionStatus() {
        this.state = ConnectionState.DISCONNECTED;
        this.lastHeartbeatTime = 0;
        this.reconnectAttempts = 0;
        this.lastError = null;
        this.nextRetryTime = 0;
    }

    // Getters
    public ConnectionState getState() { return state; }
    public long getLastHeartbeatTime() { return lastHeartbeatTime; }
    public int getReconnectAttempts() { return reconnectAttempts; }
    public String getLastError() { return lastError; }
    public long getNextRetryTime() { return nextRetryTime; }

    // Setters
    public void setState(ConnectionState state) { this.state = state; }
    public void setLastHeartbeatTime(long lastHeartbeatTime) { this.lastHeartbeatTime = lastHeartbeatTime; }
    public void setReconnectAttempts(int reconnectAttempts) { this.reconnectAttempts = reconnectAttempts; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public void setNextRetryTime(long nextRetryTime) { this.nextRetryTime = nextRetryTime; }

    /**
     * 重置重连计数
     */
    public void resetReconnectAttempts() {
        this.reconnectAttempts = 0;
        this.nextRetryTime = 0;
    }

    /**
     * 增加重连计数
     */
    public void incrementReconnectAttempts() {
        this.reconnectAttempts++;
    }

    /**
     * 更新心跳时间
     */
    public void updateHeartbeat() {
        this.lastHeartbeatTime = System.currentTimeMillis();
    }

    /**
     * 检查连接是否健康（心跳在10秒内）
     */
    public boolean isHealthy() {
        if (state != ConnectionState.CONNECTED) {
            return false;
        }
        long timeSinceLastHeartbeat = System.currentTimeMillis() - lastHeartbeatTime;
        return timeSinceLastHeartbeat < 10_000; // 10秒超时
    }

    @Override
    public String toString() {
        return "ConnectionStatus{" +
                "state=" + state +
                ", reconnectAttempts=" + reconnectAttempts +
                ", lastError='" + lastError + '\'' +
                '}';
    }
}
