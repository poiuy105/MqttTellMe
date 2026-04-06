package com.example.server;

import java.io.Serializable;

public class MqttConfig implements Serializable {
    private static final long serialVersionUID = 1L;
    
    public static final String DEFAULT_SERVER_URI = "tcp://broker.emqx.io:1883";
    public static final int DEFAULT_KEEP_ALIVE = 60;
    public static final int DEFAULT_QOS = 1;
    public static final boolean DEFAULT_CLEAN_SESSION = false;
    public static final String DEFAULT_TOPIC_PREFIX = "tellme/";
    
    private String serverUri;
    private String clientId;
    private String username;
    private String password;
    private int keepAliveInterval;
    private boolean cleanSession;
    private int qos;
    private String topicPrefix;
    private boolean autoReconnect;
    private int connectionTimeout;
    
    public MqttConfig() {
        this.serverUri = DEFAULT_SERVER_URI;
        this.clientId = "tellme_" + System.currentTimeMillis();
        this.username = "";
        this.password = "";
        this.keepAliveInterval = DEFAULT_KEEP_ALIVE;
        this.cleanSession = DEFAULT_CLEAN_SESSION;
        this.qos = DEFAULT_QOS;
        this.topicPrefix = DEFAULT_TOPIC_PREFIX;
        this.autoReconnect = true;
        this.connectionTimeout = 30;
    }
    
    public String getServerUri() {
        return serverUri;
    }
    
    public void setServerUri(String serverUri) {
        this.serverUri = serverUri;
    }
    
    public String getClientId() {
        return clientId;
    }
    
    public void setClientId(String clientId) {
        this.clientId = clientId;
    }
    
    public String getUsername() {
        return username;
    }
    
    public void setUsername(String username) {
        this.username = username;
    }
    
    public String getPassword() {
        return password;
    }
    
    public void setPassword(String password) {
        this.password = password;
    }
    
    public int getKeepAliveInterval() {
        return keepAliveInterval;
    }
    
    public void setKeepAliveInterval(int keepAliveInterval) {
        this.keepAliveInterval = keepAliveInterval;
    }
    
    public boolean isCleanSession() {
        return cleanSession;
    }
    
    public void setCleanSession(boolean cleanSession) {
        this.cleanSession = cleanSession;
    }
    
    public int getQos() {
        return qos;
    }
    
    public void setQos(int qos) {
        this.qos = qos;
    }
    
    public String getTopicPrefix() {
        return topicPrefix;
    }
    
    public void setTopicPrefix(String topicPrefix) {
        this.topicPrefix = topicPrefix;
    }
    
    public boolean isAutoReconnect() {
        return autoReconnect;
    }
    
    public void setAutoReconnect(boolean autoReconnect) {
        this.autoReconnect = autoReconnect;
    }
    
    public int getConnectionTimeout() {
        return connectionTimeout;
    }
    
    public void setConnectionTimeout(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }
    
    public String getCommandTopic() {
        return topicPrefix + clientId + "/command";
    }
    
    public String getStatusTopic() {
        return topicPrefix + clientId + "/status";
    }
    
    public String getResponseTopic() {
        return topicPrefix + clientId + "/response";
    }
    
    public boolean hasCredentials() {
        return username != null && !username.isEmpty() && password != null;
    }
    
    @Override
    public String toString() {
        return "MqttConfig{" +
                "serverUri='" + serverUri + '\'' +
                ", clientId='" + clientId + '\'' +
                ", keepAliveInterval=" + keepAliveInterval +
                ", cleanSession=" + cleanSession +
                ", qos=" + qos +
                ", autoReconnect=" + autoReconnect +
                '}';
    }
}
