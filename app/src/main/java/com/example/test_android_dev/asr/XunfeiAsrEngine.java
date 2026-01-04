package com.example.test_android_dev.asr;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * 讯飞语音识别引擎
 * 使用讯飞开放平台 WebSocket 流式语音识别 API
 * 
 * 使用前需要在讯飞开放平台注册并获取 APPID、APIKey、APISecret
 * https://www.xfyun.cn/
 */
public class XunfeiAsrEngine implements AsrEngine {
    
    private static final String TAG = "XunfeiAsrEngine";
    
    // 讯飞 API 配置 - 需要替换为实际的值
    private String appId;
    private String apiKey;
    private String apiSecret;
    
    private static final String HOST_URL = "wss://iat-api.xfyun.cn/v2/iat";
    
    // 音频参数
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int FRAME_SIZE = 1280; // 每帧音频大小 (40ms)
    private static final int FRAME_INTERVAL = 40; // 帧间隔 ms
    
    private OkHttpClient okHttpClient;
    private WebSocket webSocket;
    private AudioRecord audioRecord;
    private AsrCallback currentCallback;
    private boolean isRecognizing = false;
    private boolean isConfigured = false;
    
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService audioExecutor;
    
    private StringBuilder resultBuilder = new StringBuilder();
    
    public XunfeiAsrEngine() {
        okHttpClient = new OkHttpClient.Builder()
                .build();
    }
    
    /**
     * 配置讯飞 API 凭证
     */
    public void configure(String appId, String apiKey, String apiSecret) {
        this.appId = appId;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.isConfigured = (appId != null && !appId.isEmpty() 
                && apiKey != null && !apiKey.isEmpty() 
                && apiSecret != null && !apiSecret.isEmpty());
        Log.d(TAG, "讯飞 ASR 配置完成, isConfigured=" + isConfigured);
    }
    
    @Override
    public void init() {
        if (audioExecutor == null || audioExecutor.isShutdown()) {
            audioExecutor = Executors.newSingleThreadExecutor();
        }
        Log.d(TAG, "XunfeiAsrEngine 初始化完成");
    }
    
    @Override
    public void startRecognition(AsrCallback callback) {
        if (!isConfigured) {
            if (callback != null) {
                callback.onError("讯飞 ASR 未配置，请先设置 APPID、APIKey、APISecret");
            }
            return;
        }
        
        if (isRecognizing) {
            Log.w(TAG, "已在识别中");
            return;
        }
        
        this.currentCallback = callback;
        this.isRecognizing = true;
        this.resultBuilder = new StringBuilder();
        
        audioExecutor.execute(this::connectAndStartRecording);
    }
    
    private void connectAndStartRecording() {
        try {
            String url = buildAuthUrl();
            Log.d(TAG, "连接讯飞 WebSocket...");
            
            Request request = new Request.Builder()
                    .url(url)
                    .build();
            
            webSocket = okHttpClient.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    Log.d(TAG, "WebSocket 连接成功");
                    startAudioRecording();
                }
                
                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    handleServerMessage(text);
                }
                
                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    Log.e(TAG, "WebSocket 连接失败", t);
                    isRecognizing = false;
                    stopAudioRecording();
                    if (currentCallback != null) {
                        mainHandler.post(() -> currentCallback.onError("连接失败: " + t.getMessage()));
                    }
                }
                
                @Override
                public void onClosed(WebSocket webSocket, int code, String reason) {
                    Log.d(TAG, "WebSocket 关闭: " + reason);
                    isRecognizing = false;
                    stopAudioRecording();
                }
            });
            
        } catch (Exception e) {
            Log.e(TAG, "启动识别失败", e);
            isRecognizing = false;
            if (currentCallback != null) {
                mainHandler.post(() -> currentCallback.onError("启动识别失败: " + e.getMessage()));
            }
        }
    }
    
    private void startAudioRecording() {
        try {
            int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            bufferSize = Math.max(bufferSize, FRAME_SIZE * 4);
            
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
            );
            
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new RuntimeException("AudioRecord 初始化失败");
            }
            
            audioRecord.startRecording();
            Log.d(TAG, "开始录音");
            
            // 发送音频数据
            audioExecutor.execute(this::sendAudioLoop);
            
        } catch (Exception e) {
            Log.e(TAG, "启动录音失败", e);
            isRecognizing = false;
            if (currentCallback != null) {
                mainHandler.post(() -> currentCallback.onError("录音失败: " + e.getMessage()));
            }
        }
    }
    
    private void sendAudioLoop() {
        byte[] buffer = new byte[FRAME_SIZE];
        int frameIndex = 0;
        
        try {
            while (isRecognizing && audioRecord != null 
                    && audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                
                int bytesRead = audioRecord.read(buffer, 0, buffer.length);
                if (bytesRead > 0) {
                    sendAudioFrame(buffer, bytesRead, frameIndex);
                    frameIndex++;
                }
                
                Thread.sleep(FRAME_INTERVAL);
            }
            
            // 发送结束帧
            sendEndFrame();
            
        } catch (InterruptedException e) {
            Log.d(TAG, "录音线程中断");
        } catch (Exception e) {
            Log.e(TAG, "发送音频数据异常", e);
        }
    }
    
    private void sendAudioFrame(byte[] audioData, int length, int frameIndex) {
        try {
            JSONObject frame = new JSONObject();
            
            // common 参数（仅首帧）
            if (frameIndex == 0) {
                JSONObject common = new JSONObject();
                common.put("app_id", appId);
                frame.put("common", common);
                
                // business 参数（仅首帧）
                JSONObject business = new JSONObject();
                business.put("language", "zh_cn");
                business.put("domain", "iat");
                business.put("accent", "mandarin");
                business.put("vad_eos", 3000); // 静音检测时间
                business.put("dwa", "wpgs"); // 动态修正
                frame.put("business", business);
            }
            
            // data 参数
            JSONObject data = new JSONObject();
            data.put("status", frameIndex == 0 ? 0 : 1); // 0=首帧, 1=中间帧
            data.put("format", "audio/L16;rate=16000");
            data.put("encoding", "raw");
            
            byte[] frameData = new byte[length];
            System.arraycopy(audioData, 0, frameData, 0, length);
            data.put("audio", Base64.encodeToString(frameData, Base64.NO_WRAP));
            
            frame.put("data", data);
            
            if (webSocket != null) {
                webSocket.send(frame.toString());
            }
            
        } catch (Exception e) {
            Log.e(TAG, "构建音频帧失败", e);
        }
    }
    
    private void sendEndFrame() {
        try {
            JSONObject frame = new JSONObject();
            JSONObject data = new JSONObject();
            data.put("status", 2); // 2=结束帧
            data.put("format", "audio/L16;rate=16000");
            data.put("encoding", "raw");
            data.put("audio", "");
            frame.put("data", data);
            
            if (webSocket != null) {
                webSocket.send(frame.toString());
                Log.d(TAG, "发送结束帧");
            }
        } catch (Exception e) {
            Log.e(TAG, "发送结束帧失败", e);
        }
    }
    
    private void handleServerMessage(String message) {
        try {
            JSONObject json = new JSONObject(message);
            int code = json.optInt("code", -1);
            
            if (code != 0) {
                String errorMsg = json.optString("message", "未知错误");
                Log.e(TAG, "服务器返回错误: " + code + " - " + errorMsg);
                if (currentCallback != null) {
                    mainHandler.post(() -> currentCallback.onError(errorMsg));
                }
                return;
            }
            
            JSONObject data = json.optJSONObject("data");
            if (data != null) {
                int status = data.optInt("status", -1);
                JSONObject result = data.optJSONObject("result");
                
                if (result != null) {
                    String text = parseResult(result);
                    if (!text.isEmpty()) {
                        // 动态修正模式下，需要处理 pgs 字段
                        String pgs = result.optString("pgs", "");
                        if ("rpl".equals(pgs)) {
                            // 替换模式，清空之前的结果
                            JSONArray rg = result.optJSONArray("rg");
                            if (rg != null && rg.length() >= 2) {
                                // 简化处理：直接用新结果
                                resultBuilder = new StringBuilder(text);
                            }
                        } else {
                            resultBuilder.append(text);
                        }
                        
                        if (currentCallback != null) {
                            String partialResult = resultBuilder.toString();
                            mainHandler.post(() -> currentCallback.onPartialResult(partialResult));
                        }
                    }
                }
                
                // status=2 表示识别结束
                if (status == 2) {
                    Log.d(TAG, "识别结束");
                    isRecognizing = false;
                    String finalResult = resultBuilder.toString().trim();
                    if (currentCallback != null) {
                        if (finalResult.isEmpty()) {
                            mainHandler.post(() -> currentCallback.onError("未听到语音输入"));
                        } else {
                            mainHandler.post(() -> currentCallback.onResult(finalResult));
                        }
                    }
                    closeWebSocket();
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "解析服务器消息失败", e);
        }
    }
    
    private String parseResult(JSONObject result) {
        StringBuilder sb = new StringBuilder();
        try {
            JSONArray ws = result.optJSONArray("ws");
            if (ws != null) {
                for (int i = 0; i < ws.length(); i++) {
                    JSONObject wsItem = ws.getJSONObject(i);
                    JSONArray cw = wsItem.optJSONArray("cw");
                    if (cw != null && cw.length() > 0) {
                        sb.append(cw.getJSONObject(0).optString("w", ""));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "解析识别结果失败", e);
        }
        return sb.toString();
    }
    
    @Override
    public void feedAudioData(byte[] audioData) {
        // 讯飞引擎自己录音，不需要外部喂数据
    }
    
    @Override
    public void stopRecognition() {
        Log.d(TAG, "停止识别");
        isRecognizing = false;
        stopAudioRecording();
        // 不立即关闭 WebSocket，等待服务器返回最终结果
    }
    
    @Override
    public void cancel() {
        Log.d(TAG, "取消识别");
        isRecognizing = false;
        stopAudioRecording();
        closeWebSocket();
        currentCallback = null;
    }
    
    private void stopAudioRecording() {
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "停止录音异常", e);
            }
            audioRecord = null;
        }
    }
    
    private void closeWebSocket() {
        if (webSocket != null) {
            try {
                webSocket.close(1000, "正常关闭");
            } catch (Exception e) {
                Log.e(TAG, "关闭 WebSocket 异常", e);
            }
            webSocket = null;
        }
    }
    
    @Override
    public boolean isRecognizing() {
        return isRecognizing;
    }
    
    @Override
    public void release() {
        cancel();
        if (audioExecutor != null && !audioExecutor.isShutdown()) {
            audioExecutor.shutdown();
        }
    }
    
    @Override
    public String getEngineName() {
        return "讯飞语音识别";
    }
    
    @Override
    public boolean isAvailable() {
        return isConfigured;
    }
    
    /**
     * 构建鉴权 URL
     */
    private String buildAuthUrl() throws Exception {
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        String date = sdf.format(new Date());
        
        String signatureOrigin = "host: iat-api.xfyun.cn\n"
                + "date: " + date + "\n"
                + "GET /v2/iat HTTP/1.1";
        
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec spec = new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(spec);
        byte[] signatureSha = mac.doFinal(signatureOrigin.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.encodeToString(signatureSha, Base64.NO_WRAP);
        
        String authorizationOrigin = String.format(
                "api_key=\"%s\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"%s\"",
                apiKey, signature);
        String authorization = Base64.encodeToString(authorizationOrigin.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        
        return HOST_URL + "?"
                + "authorization=" + URLEncoder.encode(authorization, "UTF-8")
                + "&date=" + URLEncoder.encode(date, "UTF-8")
                + "&host=iat-api.xfyun.cn";
    }
}
