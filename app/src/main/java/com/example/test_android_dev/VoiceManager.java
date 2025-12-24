package com.example.test_android_dev;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 封装语音输入/输出（ASR/TTS）
 * 已针对无谷歌框架机型进行适配：
 * 1. 自动枚举可用 TTS 引擎，优先选择系统内置引擎
 * 2. 增强初始化容错逻辑
 */
public class VoiceManager {
    private static final String TAG = "VoiceManager";
    private static VoiceManager instance;
    private TextToSpeech tts;
    private SpeechRecognizer speechRecognizer;
    private boolean isTtsReady = false;
    private boolean isTtsInitializing = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Runnable> utteranceCallbacks = new ConcurrentHashMap<>();
    private final ArrayList<PendingUtterance> pendingUtterances = new ArrayList<>();

    private static class PendingUtterance {
        final String text;
        final boolean isImmediate;
        final Runnable onDoneCallback;

        PendingUtterance(String text, boolean isImmediate, Runnable onDoneCallback) {
            this.text = text;
            this.isImmediate = isImmediate;
            this.onDoneCallback = onDoneCallback;
        }
    }

    private VoiceManager() {}

    public static synchronized VoiceManager getInstance() {
        if (instance == null) {
            instance = new VoiceManager();
        }
        return instance;
    }

    public void init(Context context) {
        if (tts != null || isTtsInitializing) {
            return;
        }
        isTtsInitializing = true;

        // 适配无谷歌框架机型：尝试寻找系统内置引擎（如小米、华为、三星自带引擎）
        String preferredEngine = null;
        try {
            TextToSpeech tempTts = new TextToSpeech(context, status -> {});
            String defaultEngine = tempTts.getDefaultEngine();
            if (defaultEngine != null && !defaultEngine.equals("com.google.android.tts")) {
                preferredEngine = defaultEngine;
            }
            tempTts.shutdown();
        } catch (Exception e) {
            Log.e(TAG, "Error finding preferred engine", e);
        }

        tts = new TextToSpeech(context, status -> {
            isTtsInitializing = false;
            if (status == TextToSpeech.SUCCESS) {
                // 检查是否支持中文
                int result = tts.setLanguage(Locale.CHINESE);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "Chinese is not supported on this engine, trying English.");
                    tts.setLanguage(Locale.ENGLISH);
                }
                isTtsReady = true;
                processPendingUtterances();
            } else {
                Log.e(TAG, "TTS Initialization failed!");
            }
        }, preferredEngine);

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) { }

            @Override
            public void onDone(String utteranceId) {
                if (utteranceId == null) return;
                Runnable callback = utteranceCallbacks.remove(utteranceId);
                if (callback != null) {
                    mainHandler.post(callback);
                }
            }

            @Override
            public void onError(String utteranceId) {
                if (utteranceId != null) {
                    utteranceCallbacks.remove(utteranceId);
                }
            }
        });

        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
        }
    }

    private void processPendingUtterances() {
        synchronized (pendingUtterances) {
            for (PendingUtterance utterance : pendingUtterances) {
                if (utterance.isImmediate) {
                    speakImmediateInternal(utterance.text, utterance.onDoneCallback);
                } else {
                    speakInternal(utterance.text, utterance.onDoneCallback);
                }
            }
            pendingUtterances.clear();
        }
    }

    public void speak(String text) {
        speak(text, null);
    }

    public void speak(String text, Runnable onDoneCallback) {
        if (isTtsReady && tts != null) {
            speakInternal(text, onDoneCallback);
        } else {
            synchronized (pendingUtterances) {
                pendingUtterances.add(new PendingUtterance(text, false, onDoneCallback));
            }
        }
    }

    public void speakImmediate(String text) {
        speakImmediate(text, null);
    }

    public void speakImmediate(String text, Runnable onDoneCallback) {
        if (isTtsReady && tts != null) {
            speakImmediateInternal(text, onDoneCallback);
        } else {
            synchronized (pendingUtterances) {
                pendingUtterances.clear();
                pendingUtterances.add(new PendingUtterance(text, true, onDoneCallback));
            }
        }
    }

    private void speakInternal(String text, Runnable onDoneCallback) {
        final String utteranceId = UUID.randomUUID().toString();
        if (onDoneCallback != null) {
            utteranceCallbacks.put(utteranceId, onDoneCallback);
        }
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId);
    }

    private void speakImmediateInternal(String text, Runnable onDoneCallback) {
        final String utteranceId = UUID.randomUUID().toString();
        if (onDoneCallback != null) {
            utteranceCallbacks.put(utteranceId, onDoneCallback);
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
    }

    public interface VoiceCallback {
        void onResult(String text);
        void onError(String error);
    }

    public void startListening(final VoiceCallback callback) {
        if (speechRecognizer == null) {
            if (callback != null) callback.onError("Speech recognizer not initialized.");
            return;
        }

        mainHandler.post(() -> speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onError(int error) {
                if (callback != null) callback.onError("ASR Error: " + error);
            }
            @Override
            public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    if (callback != null) callback.onResult(matches.get(0));
                } else {
                    if (callback != null) callback.onError("No speech input");
                }
            }
            @Override public void onPartialResults(Bundle partialResults) { }
            @Override public void onEvent(int eventType, Bundle params) { }
        }));

        mainHandler.post(() -> {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE.toString());
            speechRecognizer.startListening(intent);
        });
    }

    private String getErrorText(int errorCode) {
        return "Error: " + errorCode;
    }
}