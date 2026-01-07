# Implementation Plan: Background Keep-Alive

## Overview

本实现计划将后台保活功能分解为可执行的编码任务，按照依赖关系排序，确保每个步骤都能增量验证。

## Tasks

- [x] 1. 创建基础设施和数据模型
  - [x] 1.1 创建TaskState数据类
    - 定义任务状态字段：taskId, taskPrompt, currentStep, startTime, lastUpdateTime, screenWidth, screenHeight, isRunning
    - 实现Serializable接口用于持久化
    - _Requirements: 4.1_

  - [x] 1.2 创建ConnectionState枚举和ConnectionStatus类
    - 定义连接状态：DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING
    - 实现ConnectionStatus包含状态、最后心跳时间、重连次数、错误信息
    - _Requirements: 2.1, 2.2_

- [x] 2. 实现WakeLockManager
  - [x] 2.1 创建WakeLockManager类
    - 实现acquire()方法获取PARTIAL_WAKE_LOCK
    - 实现release()方法释放唤醒锁
    - 实现isHeld()和getHeldDuration()方法
    - 添加30分钟超时警告回调
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

  - [x] 2.2 编写WakeLockManager属性测试
    - **Property 2: Wake Lock Lifecycle Consistency**
    - **Validates: Requirements 3.1, 3.2, 3.3**

- [x] 3. 实现TaskStateManager
  - [x] 3.1 创建TaskStateManager类
    - 使用SharedPreferences实现saveState()方法
    - 实现loadState()方法
    - 实现clearState()方法
    - 实现hasIncompleteTask()方法
    - _Requirements: 4.1, 4.2, 4.4, 4.5_

  - [x] 3.2 编写TaskStateManager属性测试
    - **Property 6: Task State Persistence Round-Trip**
    - **Validates: Requirements 4.1, 4.4**

  - [x] 3.3 编写TaskStateManager清理属性测试
    - **Property 7: Task State Cleanup on Stop**
    - **Validates: Requirements 4.5**

- [x] 4. 实现WebSocketConnectionManager
  - [x] 4.1 创建WebSocketConnectionManager类基础结构
    - 定义ConnectionCallback接口
    - 实现connect()和disconnect()方法
    - 实现send()方法
    - 实现getConnectionState()方法
    - _Requirements: 2.1, 2.4_

  - [x] 4.2 实现心跳机制
    - 使用ScheduledExecutorService实现30秒间隔心跳
    - 实现心跳响应超时检测（10秒）
    - 实现isHealthy()方法
    - _Requirements: 2.1, 2.2_

  - [x] 4.3 实现指数退避重连策略
    - 实现scheduleReconnect()方法
    - 实现指数退避延迟计算（1s, 2s, 4s, 8s, max 30s）
    - 实现最大重试次数限制（5次）
    - _Requirements: 2.3, 2.5_

  - [x] 4.4 编写指数退避属性测试
    - **Property 5: Exponential Backoff Reconnection**
    - **Validates: Requirements 2.3**

- [x] 5. Checkpoint - 确保所有测试通过
  - 运行所有单元测试和属性测试
  - 确保无编译错误
  - 如有问题请询问用户

- [x] 6. 实现BackgroundKeepAliveService
  - [x] 6.1 创建BackgroundKeepAliveService类
    - 继承Service
    - 创建通知渠道（Android 8.0+）
    - 实现startForegroundTask()方法
    - 实现stopForegroundTask()方法
    - 实现onStartCommand()返回START_STICKY
    - _Requirements: 1.1, 1.3, 1.5_

  - [x] 6.2 实现通知更新功能
    - 实现updateNotification()方法
    - 显示任务状态和连接状态
    - 添加停止任务的Action按钮
    - 实现点击通知打开应用
    - _Requirements: 1.2, 5.1, 5.3, 5.5_

  - [x] 6.3 实现错误通知和振动
    - 实现showErrorNotification()方法
    - 添加振动提醒
    - _Requirements: 5.4_

- [x] 7. 集成到AgentManager
  - [x] 7.1 重构AgentManager使用WebSocketConnectionManager
    - 替换原有WebSocket逻辑
    - 集成心跳和重连机制
    - 添加连接状态回调
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.6_

  - [x] 7.2 集成WakeLockManager
    - 在startTask()中获取唤醒锁
    - 在stopTask()中释放唤醒锁
    - _Requirements: 3.1, 3.3_

  - [x] 7.3 集成TaskStateManager
    - 在任务步骤完成时保存状态
    - 在任务停止时清理状态
    - _Requirements: 4.1, 4.5_

  - [x] 7.4 集成BackgroundKeepAliveService
    - 在startTask()中启动前台服务
    - 在stopTask()中停止前台服务
    - 更新通知显示连接状态
    - _Requirements: 1.1, 1.3, 5.1, 5.2_

- [x] 8. 更新AndroidManifest和权限
  - [x] 8.1 添加必要权限和服务声明
    - 添加FOREGROUND_SERVICE权限
    - 添加WAKE_LOCK权限
    - 添加VIBRATE权限
    - 声明BackgroundKeepAliveService
    - _Requirements: 1.1, 3.1, 5.4_

- [x] 9. 实现任务恢复功能
  - [x] 9.1 在MainActivity中添加任务恢复检查
    - 在onCreate中检查未完成任务
    - 显示恢复对话框
    - 实现恢复和放弃逻辑
    - _Requirements: 4.2, 4.3, 4.4_

- [x] 10. Final Checkpoint - 完整功能验证
  - 确保所有测试通过
  - 验证前台服务正常工作
  - 验证WebSocket重连机制
  - 如有问题请询问用户

## Notes

- All tasks are required for comprehensive implementation
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties
- Unit tests validate specific examples and edge cases
