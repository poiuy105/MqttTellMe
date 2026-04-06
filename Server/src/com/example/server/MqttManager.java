package com.example.server;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

public class MqttManager implements MqttCallback {
    private static final String TAG = "MqttManager";
    private static final String PREFS_NAME = "MqttPrefs";
    
    public static final String ACTION_MQTT_CONNECTED = "com.example.server.MQTT_CONNECTED";
    public static final String ACTION_MQTT_DISCONNECTED = "com.example.server.MQTT_DISCONNECTED";
    public static final String ACTION_MQTT_MESSAGE_RECEIVED = "com.example.server.MQTT_MESSAGE_RECEIVED";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_TOPIC = "topic";
    
    private Context context;
    private MqttAndroidClient mqttClient;
    private MqttConfig config;
    private MqttConnectionListener connectionListener;
    private MqttMessageListener messageListener;
    private boolean isConnecting = false;
    
    public interface MqttConnectionListener {
        void onConnected();
        void onDisconnected();
        void onConnectionFailed(Throwable cause);
    }
    
    public interface MqttMessageListener {
        void onMessageReceived(String topic, String payload);
    }
    
    public MqttManager(Context context) {
        this.context = context.getApplicationContext();
        this.config = loadConfig();
    }
    
    public MqttManager(Context context, MqttConfig config) {
        this.context = context.getApplicationContext();
        this.config = config != null ? config : loadConfig();
    }
    
    public void setConnectionListener(MqttConnectionListener listener) {
        this.connectionListener = listener;
    }
    
    public void setMessageListener(MqttMessageListener listener) {
        this.messageListener = listener;
    }
    
    public MqttConfig getConfig() {
        return config;
    }
    
    public void setConfig(MqttConfig config) {
        this.config = config;
        saveConfig(config);
    }
    
    public void connect() {
        if (mqttClient != null && mqttClient.isConnected()) {
            Log.d(TAG, "Already connected");
            return;
        }
        
        if (isConnecting) {
            Log.d(TAG, "Connection in progress");
            return;
        }
        
        isConnecting = true;
        
        String clientId = config.getClientId();
        mqttClient = new MqttAndroidClient(context, config.getServerUri(), clientId);
        mqttClient.setCallback(this);
        
        MqttConnectOptions options = new MqttConnectOptions();
        options.setKeepAliveInterval(config.getKeepAliveInterval());
        options.setConnectionTimeout(config.getConnectionTimeout());
        options.setCleanSession(config.isCleanSession());
        options.setAutomaticReconnect(config.isAutoReconnect());
        
        if (config.hasCredentials()) {
            options.setUserName(config.getUsername());
            options.setPassword(config.getPassword().toCharArray());
        }
        
        Log.d(TAG, "Connecting to MQTT broker: " + config.getServerUri());
        
        try {
            mqttClient.connect(options, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    Log.d(TAG, "MQTT connected successfully");
                    isConnecting = false;
                    subscribeToTopics();
                    notifyConnectionStatus(true);
                }
                
                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Log.e(TAG, "MQTT connection failed", exception);
                    isConnecting = false;
                    notifyConnectionFailed(exception);
                }
            });
        } catch (MqttException e) {
            Log.e(TAG, "MQTT connection error", e);
            isConnecting = false;
            notifyConnectionFailed(e);
        }
    }
    
    private void subscribeToTopics() {
        if (mqttClient == null || !mqttClient.isConnected()) {
            Log.w(TAG, "Cannot subscribe - not connected");
            return;
        }
        
        String commandTopic = config.getCommandTopic();
        Log.d(TAG, "Subscribing to topic: " + commandTopic);
        
        try {
            mqttClient.subscribe(commandTopic, config.getQos(), null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    Log.d(TAG, "Subscribed to topic: " + commandTopic);
                }
                
                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Log.e(TAG, "Failed to subscribe to topic: " + commandTopic, exception);
                }
            });
        } catch (MqttException e) {
            Log.e(TAG, "Subscription error", e);
        }
    }
    
    public void disconnect() {
        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                mqttClient.disconnect(null, new IMqttActionListener() {
                    @Override
                    public void onSuccess(IMqttToken asyncActionToken) {
                        Log.d(TAG, "Disconnected successfully");
                        notifyConnectionStatus(false);
                    }
                    
                    @Override
                    public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                        Log.e(TAG, "Disconnect failed", exception);
                    }
                });
            } catch (MqttException e) {
                Log.e(TAG, "Disconnect error", e);
            }
        }
        
        if (mqttClient != null) {
            mqttClient.unregisterResources();
            mqttClient = null;
        }
    }
    
    public boolean isConnected() {
        return mqttClient != null && mqttClient.isConnected();
    }
    
    public void publish(String topic, String payload) {
        if (mqttClient == null || !mqttClient.isConnected()) {
            Log.w(TAG, "Cannot publish - not connected");
            return;
        }
        
        try {
            MqttMessage message = new MqttMessage(payload.getBytes());
            message.setQos(config.getQos());
            mqttClient.publish(topic, message);
            Log.d(TAG, "Published to " + topic + ": " + payload);
        } catch (MqttException e) {
            Log.e(TAG, "Publish error", e);
        }
    }
    
    public void publishStatus(String status) {
        publish(config.getStatusTopic(), status);
    }
    
    public void publishResponse(String messageId, String response) {
        publish(config.getResponseTopic(), "{\"message_id\":\"" + messageId + "\",\"response\":\"" + response + "\"}");
    }
    
    @Override
    public void connectionLost(Throwable cause) {
        Log.w(TAG, "MQTT connection lost", cause);
        notifyConnectionStatus(false);
    }
    
    @Override
    public void messageArrived(String topic, MqttMessage message) {
        String payload = new String(message.getPayload());
        Log.d(TAG, "Message received on " + topic + ": " + payload);
        
        if (messageListener != null) {
            messageListener.onMessageReceived(topic, payload);
        }
    }
    
    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        Log.d(TAG, "Message delivery complete");
    }
    
    private void notifyConnectionStatus(boolean connected) {
        if (connectionListener != null) {
            if (connected) {
                connectionListener.onConnected();
            } else {
                connectionListener.onDisconnected();
            }
        }
        
        Intent intent = new Intent(connected ? ACTION_MQTT_CONNECTED : ACTION_MQTT_DISCONNECTED);
        context.sendBroadcast(intent);
    }
    
    private void notifyConnectionFailed(Throwable cause) {
        if (connectionListener != null) {
            connectionListener.onConnectionFailed(cause);
        }
    }
    
    public void saveConfig(MqttConfig config) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("serverUri", config.getServerUri());
        editor.putString("clientId", config.getClientId());
        editor.putString("username", config.getUsername());
        editor.putString("password", config.getPassword());
        editor.putInt("keepAlive", config.getKeepAliveInterval());
        editor.putBoolean("cleanSession", config.isCleanSession());
        editor.putInt("qos", config.getQos());
        editor.putString("topicPrefix", config.getTopicPrefix());
        editor.putBoolean("autoReconnect", config.isAutoReconnect());
        editor.putInt("connectionTimeout", config.getConnectionTimeout());
        editor.apply();
        Log.d(TAG, "MQTT config saved");
    }
    
    private MqttConfig loadConfig() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        MqttConfig config = new MqttConfig();
        
        String serverUri = prefs.getString("serverUri", null);
        if (serverUri != null) {
            config.setServerUri(serverUri);
            config.setClientId(prefs.getString("clientId", config.getClientId()));
            config.setUsername(prefs.getString("username", ""));
            config.setPassword(prefs.getString("password", ""));
            config.setKeepAliveInterval(prefs.getInt("keepAlive", MqttConfig.DEFAULT_KEEP_ALIVE));
            config.setCleanSession(prefs.getBoolean("cleanSession", MqttConfig.DEFAULT_CLEAN_SESSION));
            config.setQos(prefs.getInt("qos", MqttConfig.DEFAULT_QOS));
            config.setTopicPrefix(prefs.getString("topicPrefix", MqttConfig.DEFAULT_TOPIC_PREFIX));
            config.setAutoReconnect(prefs.getBoolean("autoReconnect", true));
            config.setConnectionTimeout(prefs.getInt("connectionTimeout", 30));
        }
        
        Log.d(TAG, "MQTT config loaded: " + config);
        return config;
    }
    
    public void release() {
        disconnect();
        connectionListener = null;
        messageListener = null;
    }
}
