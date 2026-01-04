package com.example.test_android_dev;

/**
 * App configuration and feature flags.
 */
public class Config {

    /**
     * Master switch for mock mode.
     * If true, the app will not make real network calls but will use fake data generators.
     * This is useful for testing UI and logic flows without a backend.
     * Set to false to connect to a real backend server.
     */
    public static final boolean MOCK_MODE = false; // Set to false for real backend testing

    /**
     * Debug mode switch for UI.
     * If true, shows the developer test interface (manual text input).
     * If false, shows the user voice interface (press-to-talk button).
     * Set to false for production builds.
     */
    public static final boolean DEBUG_MODE = false; // Set to true for development testing
    
    // ==================== 讯飞语音识别配置 ====================
    // 在讯飞开放平台注册获取: https://www.xfyun.cn/
    // 创建应用后，在控制台获取以下三个参数
    // 注意：这些是敏感信息，生产环境建议从服务器获取或使用更安全的存储方式
    
    /**
     * 讯飞 APPID
     * 在讯飞开放平台创建应用后获取
     */
    public static final String XUNFEI_APP_ID = "";
    
    /**
     * 讯飞 APIKey
     * 在讯飞开放平台应用管理中获取
     */
    public static final String XUNFEI_API_KEY = "";
    
    /**
     * 讯飞 APISecret
     * 在讯飞开放平台应用管理中获取
     */
    public static final String XUNFEI_API_SECRET = "";
}
