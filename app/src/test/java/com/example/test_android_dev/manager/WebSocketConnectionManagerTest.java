package com.example.test_android_dev.manager;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * WebSocketConnectionManager 单元测试
 * 
 * Feature: background-keep-alive, Property 5: Exponential Backoff Reconnection
 * Validates: Requirements 2.3
 */
public class WebSocketConnectionManagerTest {

    /**
     * Property 5: Exponential Backoff Reconnection
     * 测试指数退避延迟计算
     * 
     * 对于任意重连尝试次数，延迟应遵循模式：1s, 2s, 4s, 8s, 然后上限30s
     */
    @Test
    public void testExponentialBackoffDelays() {
        // 验证指数退避序列
        assertEquals("第0次尝试应延迟1000ms", 
                1000, WebSocketConnectionManager.calculateReconnectDelay(0));
        assertEquals("第1次尝试应延迟2000ms", 
                2000, WebSocketConnectionManager.calculateReconnectDelay(1));
        assertEquals("第2次尝试应延迟4000ms", 
                4000, WebSocketConnectionManager.calculateReconnectDelay(2));
        assertEquals("第3次尝试应延迟8000ms", 
                8000, WebSocketConnectionManager.calculateReconnectDelay(3));
        assertEquals("第4次尝试应延迟30000ms（上限）", 
                30000, WebSocketConnectionManager.calculateReconnectDelay(4));
    }

    /**
     * 测试超出数组范围的尝试次数
     */
    @Test
    public void testExponentialBackoffBeyondLimit() {
        // 超出定义范围后应使用最大值
        assertEquals("第5次尝试应延迟30000ms", 
                30000, WebSocketConnectionManager.calculateReconnectDelay(5));
        assertEquals("第10次尝试应延迟30000ms", 
                30000, WebSocketConnectionManager.calculateReconnectDelay(10));
        assertEquals("第100次尝试应延迟30000ms", 
                30000, WebSocketConnectionManager.calculateReconnectDelay(100));
    }

    /**
     * 测试负数尝试次数（边界情况）
     */
    @Test
    public void testExponentialBackoffNegativeAttempt() {
        // 负数应返回第一个延迟值
        assertEquals("负数尝试应返回1000ms", 
                1000, WebSocketConnectionManager.calculateReconnectDelay(-1));
    }

    /**
     * 测试心跳间隔常量
     */
    @Test
    public void testHeartbeatIntervalConstant() {
        assertEquals("心跳间隔应为30秒", 
                30_000, WebSocketConnectionManager.HEARTBEAT_INTERVAL_MS);
    }

    /**
     * 测试心跳超时常量
     */
    @Test
    public void testHeartbeatTimeoutConstant() {
        assertEquals("心跳超时应为10秒", 
                10_000, WebSocketConnectionManager.HEARTBEAT_TIMEOUT_MS);
    }

    /**
     * 测试最大重连次数常量
     */
    @Test
    public void testMaxReconnectAttemptsConstant() {
        assertEquals("最大重连次数应为5", 
                5, WebSocketConnectionManager.MAX_RECONNECT_ATTEMPTS);
    }

    /**
     * Property: 延迟序列应单调递增直到上限
     */
    @Test
    public void testDelaySequenceMonotonicallyIncreasing() {
        long previousDelay = 0;
        for (int i = 0; i < WebSocketConnectionManager.RECONNECT_DELAYS_MS.length; i++) {
            long currentDelay = WebSocketConnectionManager.calculateReconnectDelay(i);
            assertTrue("延迟应单调递增: attempt " + i, currentDelay >= previousDelay);
            previousDelay = currentDelay;
        }
    }
}
