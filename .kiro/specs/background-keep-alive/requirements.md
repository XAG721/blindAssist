# Requirements Document

## Introduction

本功能旨在解决AutoGLM Android应用在执行跨应用操作时的后台保持问题。当AutoGLM调起其他应用（如微信、美团等）时，主应用会进入后台，可能导致WebSocket连接断开、任务执行中断，从而无法继续与AutoGLM服务器通信，后续复杂操作无法执行。

## Glossary

- **Background_Keep_Alive_Service**: 后台保活服务，负责维持应用在后台时的运行状态
- **WebSocket_Connection_Manager**: WebSocket连接管理器，负责维护与服务器的长连接
- **Wake_Lock_Manager**: 唤醒锁管理器，防止设备在任务执行期间进入休眠
- **Foreground_Notification**: 前台通知，用于将服务提升为前台服务，防止被系统杀死
- **Heartbeat_Mechanism**: 心跳机制，定期发送心跳包以检测连接状态
- **Reconnection_Strategy**: 重连策略，在连接断开时自动尝试重新连接
- **Task_State_Manager**: 任务状态管理器，负责保存和恢复任务执行状态

## Requirements

### Requirement 1: 前台服务保活

**User Story:** As a user, I want the app to continue running in the background when other apps are launched, so that AutoGLM can complete complex multi-app tasks without interruption.

#### Acceptance Criteria

1. WHEN a task is started, THE Background_Keep_Alive_Service SHALL start as a foreground service with a visible notification
2. WHEN the foreground service is running, THE Foreground_Notification SHALL display the current task status and progress
3. WHEN a task is completed or stopped, THE Background_Keep_Alive_Service SHALL stop the foreground service and remove the notification
4. WHILE the foreground service is running, THE Background_Keep_Alive_Service SHALL maintain a process priority that prevents system termination
5. IF the system attempts to kill the service, THEN THE Background_Keep_Alive_Service SHALL return START_STICKY to request restart

### Requirement 2: WebSocket连接保持与重连

**User Story:** As a user, I want the WebSocket connection to remain stable and automatically reconnect if disconnected, so that communication with the AutoGLM server is not interrupted.

#### Acceptance Criteria

1. WHILE a task is running, THE WebSocket_Connection_Manager SHALL send heartbeat messages every 30 seconds
2. WHEN a heartbeat response is not received within 10 seconds, THE WebSocket_Connection_Manager SHALL mark the connection as unhealthy
3. WHEN the WebSocket connection is disconnected, THE Reconnection_Strategy SHALL attempt to reconnect using exponential backoff (1s, 2s, 4s, 8s, max 30s)
4. WHEN reconnection succeeds, THE WebSocket_Connection_Manager SHALL resume the current task from the last known state
5. IF reconnection fails after 5 attempts, THEN THE WebSocket_Connection_Manager SHALL notify the user and pause the task
6. WHEN the app transitions from background to foreground, THE WebSocket_Connection_Manager SHALL verify connection health and reconnect if necessary

### Requirement 3: 唤醒锁管理

**User Story:** As a user, I want the device to stay awake during task execution, so that long-running operations are not interrupted by device sleep.

#### Acceptance Criteria

1. WHEN a task is started, THE Wake_Lock_Manager SHALL acquire a partial wake lock
2. WHILE a task is running, THE Wake_Lock_Manager SHALL maintain the wake lock to prevent CPU sleep
3. WHEN a task is completed, stopped, or fails, THE Wake_Lock_Manager SHALL release the wake lock within 1 second
4. IF the wake lock is held for more than 30 minutes, THEN THE Wake_Lock_Manager SHALL log a warning and optionally notify the user
5. WHEN the app is destroyed unexpectedly, THE Wake_Lock_Manager SHALL ensure the wake lock is released to prevent battery drain

### Requirement 4: 任务状态持久化与恢复

**User Story:** As a user, I want my task progress to be saved, so that if the app is killed or crashes, I can resume from where I left off.

#### Acceptance Criteria

1. WHEN a task step is completed, THE Task_State_Manager SHALL persist the current task state to local storage
2. WHEN the app restarts after an unexpected termination, THE Task_State_Manager SHALL check for incomplete tasks
3. IF an incomplete task is found, THEN THE Task_State_Manager SHALL prompt the user to resume or discard the task
4. WHEN the user chooses to resume, THE Task_State_Manager SHALL restore the task state and continue execution
5. WHEN a task is explicitly stopped by the user, THE Task_State_Manager SHALL clear the persisted state

### Requirement 5: 连接状态监控与用户反馈

**User Story:** As a user, I want to be informed about the connection status and any issues, so that I can take appropriate action if needed.

#### Acceptance Criteria

1. WHILE a task is running, THE Foreground_Notification SHALL display the current connection status (connected/reconnecting/disconnected)
2. WHEN the connection status changes, THE Foreground_Notification SHALL update within 2 seconds
3. WHEN reconnection attempts are in progress, THE Foreground_Notification SHALL show the attempt count and next retry time
4. IF the connection is lost and cannot be restored, THEN THE Background_Keep_Alive_Service SHALL vibrate the device and show an alert notification
5. WHEN the user taps the notification, THE Background_Keep_Alive_Service SHALL bring the app to the foreground


