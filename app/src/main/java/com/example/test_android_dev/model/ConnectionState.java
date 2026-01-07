package com.example.test_android_dev.model;

/**
 * WebSocket连接状态枚举
 */
public enum ConnectionState {
    DISCONNECTED("已断开"),
    CONNECTING("连接中"),
    CONNECTED("已连接"),
    RECONNECTING("重连中");

    private final String displayName;

    ConnectionState(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
