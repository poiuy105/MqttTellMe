package com.example.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

public class ServerService extends Service {
    private static final String TAG = "ServerService";
    
    private TextToSpeech textToSpeech;
    private boolean ttsInitialized = false;
    private boolean isRunning = false;
    
    private FloatingWindowService floatingWindowService;
    private boolean isFloatingWindowBound = false;
    private final Object serviceLock = new Object();
    private Handler mainHandler;
    
    private MqttManager mqttManager;
    private boolean mqttEnabled = true;
    private boolean httpFallbackEnabled = true;
    
    private ServerSocket serverSocket;
    private static final int DEFAULT_HTTP_PORT = 1234;
    private int currentHttpPort = DEFAULT_HTTP_PORT;
    
    private ExecutorService httpExecutor;
    
    private ServiceConnection floatingWindowConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "FloatingWindowService connected");
            synchronized (serviceLock) {
                FloatingWindowService.LocalBinder binder = (FloatingWindowService.LocalBinder) service;
                floatingWindowService = binder.getService();
                isFloatingWindowBound = true;
            }
        }
        
        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "FloatingWindowService disconnected");
            synchronized (serviceLock) {
                floatingWindowService = null;
                isFloatingWindowBound = false;
            }
        }
    };
    
    private static final String NOTIFICATION_CHANNEL_ID = "server_service_channel";
    private static final int NOTIFICATION_ID = 1001;
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");
        
        mainHandler = new Handler(Looper.getMainLooper());
        httpExecutor = Executors.newCachedThreadPool();
        
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        
        initTextToSpeech();
        
        Intent floatingWindowIntent = new Intent(this, FloatingWindowService.class);
        try {
            startService(floatingWindowIntent);
            bindService(floatingWindowIntent, floatingWindowConnection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            Log.e(TAG, "Error starting/binding FloatingWindowService", e);
        }
        
        loadSettings();
        
        if (mqttEnabled) {
            initMqtt();
        }
        
        if (httpFallbackEnabled) {
            startHttpServer();
        }
    }
    
    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences("ServerPrefs", MODE_PRIVATE);
        mqttEnabled = prefs.getBoolean("mqttEnabled", true);
        httpFallbackEnabled = prefs.getBoolean("httpFallbackEnabled", true);
        currentHttpPort = prefs.getInt("httpPort", DEFAULT_HTTP_PORT);
    }
    
    private void initTextToSpeech() {
        try {
            textToSpeech = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    if (status == TextToSpeech.SUCCESS) {
                        Log.d(TAG, "TextToSpeech initialized successfully");
                        ttsInitialized = true;
                        setupTTSListener();
                    } else {
                        Log.e(TAG, "TextToSpeech initialization failed with status: " + status);
                        ttsInitialized = false;
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error initializing TextToSpeech", e);
            ttsInitialized = false;
        }
    }
    
    private void setupTTSListener() {
        if (textToSpeech != null) {
            textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    Log.d(TAG, "TTS started - utteranceId: " + utteranceId);
                }
                
                @Override
                public void onDone(String utteranceId) {
                    Log.d(TAG, "TTS completed - utteranceId: " + utteranceId);
                    
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            hideFloatingWindow();
                        }
                    }, 2000);
                }
                
                @Override
                public void onError(String utteranceId) {
                    Log.e(TAG, "TTS error - utteranceId: " + utteranceId);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            hideFloatingWindow();
                        }
                    });
                }
            });
            Log.d(TAG, "TTS UtteranceProgressListener set up successfully");
        }
    }
    
    private void initMqtt() {
        Log.d(TAG, "Initializing MQTT client");
        
        try {
            mqttManager = new MqttManager(this);
            mqttManager.setConnectionListener(new MqttManager.MqttConnectionListener() {
                @Override
                public void onConnected() {
                    Log.d(TAG, "MQTT connected");
                    updateNotification();
                    if (mqttManager != null && mqttManager.getConfig() != null) {
                        mqttManager.publishStatus("{\"status\":\"online\",\"client_id\":\"" + mqttManager.getConfig().getClientId() + "\"}");
                    }
                }
                
                @Override
                public void onDisconnected() {
                    Log.d(TAG, "MQTT disconnected");
                    updateNotification();
                }
                
                @Override
                public void onConnectionFailed(Throwable cause) {
                    Log.e(TAG, "MQTT connection failed", cause);
                    updateNotification();
                }
            });
            
            mqttManager.setMessageListener(new MqttManager.MqttMessageListener() {
                @Override
                public void onMessageReceived(String topic, String payload) {
                    Log.d(TAG, "MQTT message received: " + payload);
                    processPayload(payload);
                }
            });
            
            mqttManager.connect();
        } catch (Exception e) {
            Log.e(TAG, "Error initializing MQTT", e);
        }
    }
    
    private void showFloatingWindow(String text) {
        Log.d(TAG, "showFloatingWindow called with text: " + text);
        synchronized (serviceLock) {
            if (isFloatingWindowBound && floatingWindowService != null) {
                floatingWindowService.updateText(text);
                floatingWindowService.showWindow();
            } else {
                Log.w(TAG, "FloatingWindowService not bound, cannot show window");
            }
        }
    }
    
    private void hideFloatingWindow() {
        Log.d(TAG, "hideFloatingWindow called");
        synchronized (serviceLock) {
            if (isFloatingWindowBound && floatingWindowService != null) {
                floatingWindowService.hideWindow();
            } else {
                Log.w(TAG, "FloatingWindowService not bound, cannot hide window");
            }
        }
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Post Tell Me Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Background service for Post Tell Me");
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
    
    private String getDeviceIpAddress() {
        String ipAddress = "127.0.0.1";
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }
                
                java.util.Enumeration<java.net.InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress inetAddress = addresses.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress.getHostAddress().indexOf(':') < 0) {
                        ipAddress = inetAddress.getHostAddress();
                        if (networkInterface.getName().toLowerCase().contains("wlan") ||
                            networkInterface.getName().toLowerCase().contains("wifi")) {
                            return ipAddress;
                        }
                    }
                }
            }
        } catch (java.net.SocketException e) {
            Log.e(TAG, "Error getting IP address", e);
        }
        return ipAddress;
    }
    
    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 
            0, 
            notificationIntent, 
            PendingIntent.FLAG_IMMUTABLE
        );
        
        StringBuilder contentText = new StringBuilder();
        
        if (mqttManager != null && mqttManager.isConnected()) {
            contentText.append("MQTT: ").append(mqttManager.getConfig().getClientId());
        } else if (mqttEnabled) {
            contentText.append("MQTT: Connecting...");
        }
        
        if (httpFallbackEnabled) {
            if (contentText.length() > 0) {
                contentText.append(" | ");
            }
            contentText.append("HTTP: ").append(getDeviceIpAddress()).append(":").append(currentHttpPort);
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Post Tell Me")
                .setContentText(contentText.toString())
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
        } else {
            return new Notification.Builder(this)
                .setContentTitle("Post Tell Me")
                .setContentText(contentText.toString())
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
        }
    }
    
    public void updateNotification() {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, createNotification());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating notification", e);
        }
    }
    
    private void startHttpServer() {
        isRunning = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket(currentHttpPort);
                    Log.d(TAG, "HTTP Server started on port " + currentHttpPort);
                    
                    while (isRunning) {
                        try {
                            Socket socket = serverSocket.accept();
                            Log.d(TAG, "HTTP Client connected");
                            httpExecutor.execute(new HttpHandler(socket));
                        } catch (IOException e) {
                            if (isRunning) {
                                Log.e(TAG, "Error accepting HTTP client connection", e);
                            }
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error starting HTTP server", e);
                }
            }
        }).start();
    }
    
    private void stopHttpServer() {
        isRunning = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing HTTP server socket", e);
            }
            serverSocket = null;
        }
        if (httpExecutor != null) {
            httpExecutor.shutdown();
        }
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service started");
        
        if (intent != null) {
            if (intent.hasExtra("mqttServerUri")) {
                String serverUri = intent.getStringExtra("mqttServerUri");
                String clientId = intent.getStringExtra("mqttClientId");
                String username = intent.getStringExtra("mqttUsername");
                String password = intent.getStringExtra("mqttPassword");
                
                MqttConfig config = mqttManager != null ? mqttManager.getConfig() : new MqttConfig();
                config.setServerUri(serverUri);
                if (clientId != null && !clientId.isEmpty()) {
                    config.setClientId(clientId);
                }
                config.setUsername(username != null ? username : "");
                config.setPassword(password != null ? password : "");
                
                if (mqttManager != null) {
                    mqttManager.disconnect();
                    mqttManager.setConfig(config);
                    mqttManager.connect();
                } else {
                    mqttManager = new MqttManager(this, config);
                    initMqtt();
                }
            }
            
            if (intent.hasExtra("httpPort")) {
                int newPort = intent.getIntExtra("httpPort", currentHttpPort);
                if (newPort != currentHttpPort) {
                    stopHttpServer();
                    currentHttpPort = newPort;
                    startHttpServer();
                    updateNotification();
                }
            }
            
            if (intent.hasExtra("reconnect")) {
                if (mqttManager != null && !mqttManager.isConnected()) {
                    mqttManager.connect();
                }
            }
        }
        
        return START_STICKY;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service destroyed");
        
        isRunning = false;
        
        synchronized (serviceLock) {
            if (isFloatingWindowBound) {
                try {
                    unbindService(floatingWindowConnection);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Service not registered when trying to unbind: " + e.getMessage());
                }
                isFloatingWindowBound = false;
            }
        }
        
        stopHttpServer();
        
        if (mqttManager != null) {
            try {
                mqttManager.publishStatus("{\"status\":\"offline\"}");
                mqttManager.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing MQTT manager", e);
            }
            mqttManager = null;
        }
        
        if (textToSpeech != null) {
            try {
                textToSpeech.stop();
                textToSpeech.shutdown();
            } catch (Exception e) {
                Log.e(TAG, "Error shutting down TTS", e);
            }
            textToSpeech = null;
            ttsInitialized = false;
        }
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    private void processPayload(String payload) {
        try {
            JSONObject jsonObject = new JSONObject(payload);
            if (jsonObject.has("tts") && jsonObject.getBoolean("tts")) {
                if (jsonObject.has("txt")) {
                    String text = jsonObject.getString("txt");
                    if (!text.isEmpty() && ttsInitialized && textToSpeech != null) {
                        int volume = 40;
                        if (jsonObject.has("volume")) {
                            try {
                                volume = jsonObject.getInt("volume");
                                volume = Math.max(0, Math.min(100, volume));
                            } catch (Exception e) {
                                Log.w(TAG, "Invalid volume value");
                            }
                        }
                        
                        String channel = "stereo";
                        if (jsonObject.has("channel")) {
                            channel = jsonObject.getString("channel").toLowerCase();
                            if (!channel.equals("left") && !channel.equals("right") && !channel.equals("stereo")) {
                                channel = "stereo";
                            }
                        }
                        
                        float volumeFloat = volume / 100.0f;
                        
                        float pan = 0.0f;
                        switch (channel) {
                            case "left":
                                pan = -1.0f;
                                break;
                            case "right":
                                pan = 1.0f;
                                break;
                            default:
                                pan = 0.0f;
                                break;
                        }
                        
                        Bundle params = new Bundle();
                        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volumeFloat);
                        params.putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, pan);
                        
                        String utteranceId = "tts_" + System.currentTimeMillis();
                        
                        showFloatingWindow(text);
                        
                        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId);
                    } else {
                        Log.w(TAG, "Cannot speak - TTS not ready or text is empty");
                    }
                }
            }
        } catch (JSONException e) {
            Log.d(TAG, "Received non-JSON payload");
        } catch (Exception e) {
            Log.e(TAG, "Error processing payload", e);
        }
    }
    
    private class HttpHandler implements Runnable {
        private final Socket socket;
        
        HttpHandler(Socket socket) {
            this.socket = socket;
        }
        
        @Override
        public void run() {
            try {
                InputStream is = socket.getInputStream();
                OutputStream os = socket.getOutputStream();
                
                HttpRequestInfo requestInfo = parseHttpRequest(is);
                
                String result = null;
                if (requestInfo.isPost && requestInfo.contentLength > 0) {
                    result = readPostPayload(is, requestInfo.contentLength);
                }
                
                sendHttpResponse(os);
                
                socket.close();
                
                if (result != null) {
                    Log.d(TAG, "Received HTTP POST payload: " + result);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            processPayload(result);
                        }
                    });
                }
            } catch (IOException e) {
                Log.e(TAG, "Error handling HTTP client connection", e);
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error in HttpHandler", e);
            }
        }
        
        private HttpRequestInfo parseHttpRequest(InputStream is) throws IOException {
            HttpRequestInfo info = new HttpRequestInfo();
            StringBuilder headerLine = new StringBuilder();
            int b;
            boolean firstLine = true;
            
            while ((b = is.read()) != -1) {
                if (b == '\r') {
                    int next = is.read();
                    if (next == '\n') {
                        String line = headerLine.toString();
                        headerLine.setLength(0);
                        
                        if (line.isEmpty()) {
                            break;
                        }
                        
                        if (firstLine) {
                            if (line.startsWith("POST ")) {
                                info.isPost = true;
                            }
                            firstLine = false;
                        } else if (line.toLowerCase().startsWith("content-length:")) {
                            try {
                                info.contentLength = Integer.parseInt(line.substring(15).trim());
                            } catch (NumberFormatException e) {
                                info.contentLength = 0;
                            }
                        }
                    } else if (next != -1) {
                        headerLine.append('\r');
                        headerLine.append((char) next);
                    }
                } else {
                    headerLine.append((char) b);
                }
            }
            
            return info;
        }
        
        private String readPostPayload(InputStream is, int contentLength) throws IOException {
            if (contentLength <= 0) {
                return "";
            }
            
            byte[] buffer = new byte[contentLength];
            int totalRead = 0;
            
            while (totalRead < contentLength) {
                int read = is.read(buffer, totalRead, contentLength - totalRead);
                if (read == -1) break;
                totalRead += read;
            }
            
            return new String(buffer, 0, totalRead, "UTF-8");
        }
        
        private void sendHttpResponse(OutputStream os) throws IOException {
            String response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/plain; charset=UTF-8\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n" +
                    "\r\n";
            os.write(response.getBytes("UTF-8"));
            os.flush();
        }
    }
    
    private class HttpRequestInfo {
        boolean isPost = false;
        int contentLength = 0;
    }
}
