package com.example.test_android_dev.manager;

import com.example.test_android_dev.model.TaskState;
import com.google.gson.Gson;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * TaskStateManager 单元测试
 * 
 * Feature: background-keep-alive, Property 6: Task State Persistence Round-Trip
 * Validates: Requirements 4.1, 4.4
 * 
 * 注意：完整的SharedPreferences测试需要在Android环境中运行（androidTest）
 * 这里测试的是序列化/反序列化逻辑
 */
public class TaskStateManagerTest {

    private Gson gson;

    @Before
    public void setUp() {
        gson = new Gson();
    }

    /**
     * Property 6: Task State Persistence Round-Trip
     * 测试TaskState序列化和反序列化的一致性
     */
    @Test
    public void testTaskStateSerializationRoundTrip() {
        // 创建原始TaskState
        TaskState original = new TaskState("打开微信发送消息", 1080, 2400);
        original.setCurrentStep(5);
        original.markRunning();

        // 序列化
        String json = gson.toJson(original);
        assertNotNull("序列化结果不应为null", json);

        // 反序列化
        TaskState restored = gson.fromJson(json, TaskState.class);
        assertNotNull("反序列化结果不应为null", restored);

        // 验证关键字段一致
        assertEquals("taskId应一致", original.getTaskId(), restored.getTaskId());
        assertEquals("taskPrompt应一致", original.getTaskPrompt(), restored.getTaskPrompt());
        assertEquals("currentStep应一致", original.getCurrentStep(), restored.getCurrentStep());
        assertEquals("screenWidth应一致", original.getScreenWidth(), restored.getScreenWidth());
        assertEquals("screenHeight应一致", original.getScreenHeight(), restored.getScreenHeight());
        assertEquals("isRunning应一致", original.isRunning(), restored.isRunning());
    }

    /**
     * 测试空TaskState的序列化
     */
    @Test
    public void testEmptyTaskStateSerialization() {
        TaskState original = new TaskState();
        
        String json = gson.toJson(original);
        TaskState restored = gson.fromJson(json, TaskState.class);

        assertNotNull("taskId不应为null", restored.getTaskId());
        assertEquals("currentStep应为0", 0, restored.getCurrentStep());
        assertFalse("isRunning应为false", restored.isRunning());
    }

    /**
     * 测试TaskState的equals方法
     */
    @Test
    public void testTaskStateEquals() {
        TaskState state1 = new TaskState("测试任务", 1080, 2400);
        TaskState state2 = new TaskState("测试任务", 1080, 2400);

        // 不同taskId的对象不相等
        assertNotEquals("不同taskId的对象不应相等", state1, state2);

        // 序列化后反序列化的对象应相等
        String json = gson.toJson(state1);
        TaskState restored = gson.fromJson(json, TaskState.class);
        assertEquals("序列化往返后应相等", state1, restored);
    }

    /**
     * 测试incrementStep方法
     */
    @Test
    public void testIncrementStep() {
        TaskState state = new TaskState("测试任务", 1080, 2400);
        assertEquals("初始step应为0", 0, state.getCurrentStep());

        state.incrementStep();
        assertEquals("增加后step应为1", 1, state.getCurrentStep());

        state.incrementStep();
        state.incrementStep();
        assertEquals("多次增加后step应为3", 3, state.getCurrentStep());
    }

    /**
     * 测试markRunning和markStopped方法
     */
    @Test
    public void testRunningState() {
        TaskState state = new TaskState("测试任务", 1080, 2400);
        assertFalse("初始应为非运行状态", state.isRunning());

        state.markRunning();
        assertTrue("markRunning后应为运行状态", state.isRunning());

        state.markStopped();
        assertFalse("markStopped后应为非运行状态", state.isRunning());
    }

    /**
     * Property 7: Task State Cleanup on Stop
     * 测试任务停止时状态应被清理
     * 
     * Feature: background-keep-alive, Property 7: Task State Cleanup on Stop
     * Validates: Requirements 4.5
     * 
     * 注意：完整测试需要在Android环境中运行
     * 这里验证TaskState的停止状态标记逻辑
     */
    @Test
    public void testTaskStateCleanupOnStop() {
        TaskState state = new TaskState("测试任务", 1080, 2400);
        state.markRunning();
        assertTrue("任务应处于运行状态", state.isRunning());

        // 模拟停止任务
        state.markStopped();
        assertFalse("停止后任务应处于非运行状态", state.isRunning());
    }
}
