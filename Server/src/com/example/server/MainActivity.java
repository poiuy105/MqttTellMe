package com.example.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.HashMap;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    
    private TextView tvClientMsg;
    private EditText tvServerPort;
    private TextView tvServerIP;
    private int SERVER_PORT;
    private String SERVER_IP;
    Button clear;
    private TextToSpeech textToSpeech;
    
    private EditText editMqttServerUri;
    private EditText editMqttClientId;
    private EditText editMqttUsername;
    private EditText editMqttPassword;
    private TextView textMqttStatus;
    private TextView textMqttTopicInfo;
    private Button btnMqttConnect;
    
    private BroadcastReceiver mqttReceiver;
    private MqttConfig mqttConfig;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        SharedPreferences prefs = getSharedPreferences("ServerPrefs", MODE_PRIVATE);
        int startCount = prefs.getInt("startCount", 0);
        
        if (startCount == 0) {
            checkFloatingWindowPermission();
        }
        
        if (startCount == 0) {
            setContentView(R.layout.activity_main);
            initViews();
            loadMqttConfig();
            setupMqttReceiver();
            
            String detectedIp = getDeviceIpAddress();
            tvServerIP.setText(detectedIp);
            tvServerPort.setText("1234");
            
            setupPortConfirmButton();
            setupMqttConnectButton();
            setupClearButton();
        } else {
            Log.d(TAG, "Not first start, finishing activity");
            Intent serviceIntent = new Intent(this, ServerService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            finish();
            return;
        }
        
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt("startCount", startCount + 1);
        editor.apply();
        
        if (startCount == 0) {
            Intent serviceIntent = new Intent(this, ServerService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Log.d(TAG, "ServerService started");
        }
        
        if (startCount == 0) {
            textToSpeech = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    if (status == TextToSpeech.SUCCESS) {
                        Log.d(TAG, "TextToSpeech initialized successfully");
                    }
                }
            });
        }
        
        Log.d(TAG, "Server service started, UI ready");
    }
    
    private void initViews() {
        tvClientMsg = (TextView) findViewById(R.id.textViewClientMessage);
        tvServerPort = (EditText) findViewById(R.id.textViewServerPort);
        tvServerIP = (TextView) findViewById(R.id.textViewServerIP);
        
        editMqttServerUri = (EditText) findViewById(R.id.editMqttServerUri);
        editMqttClientId = (EditText) findViewById(R.id.editMqttClientId);
        editMqttUsername = (EditText) findViewById(R.id.editMqttUsername);
        editMqttPassword = (EditText) findViewById(R.id.editMqttPassword);
        textMqttStatus = (TextView) findViewById(R.id.textMqttStatus);
        textMqttTopicInfo = (TextView) findViewById(R.id.textMqttTopicInfo);
        btnMqttConnect = (Button) findViewById(R.id.btnMqttConnect);
        clear = (Button) findViewById(R.id.button1);
    }
    
    private void loadMqttConfig() {
        mqttConfig = new MqttConfig();
        
        SharedPreferences prefs = getSharedPreferences("MqttPrefs", MODE_PRIVATE);
        String serverUri = prefs.getString("serverUri", null);
        if (serverUri != null) {
            mqttConfig.setServerUri(serverUri);
            mqttConfig.setClientId(prefs.getString("clientId", mqttConfig.getClientId()));
            mqttConfig.setUsername(prefs.getString("username", ""));
            mqttConfig.setPassword(prefs.getString("password", ""));
        }
        
        editMqttServerUri.setText(mqttConfig.getServerUri());
        editMqttClientId.setText(mqttConfig.getClientId());
        editMqttUsername.setText(mqttConfig.getUsername());
        editMqttPassword.setText(mqttConfig.getPassword());
        
        updateTopicInfo();
    }
    
    private void updateTopicInfo() {
        String clientId = editMqttClientId.getText().toString().trim();
        if (clientId.isEmpty()) {
            clientId = mqttConfig.getClientId();
        }
        textMqttTopicInfo.setText("Subscribe to: tellme/" + clientId + "/command");
    }
    
    private void setupMqttReceiver() {
        mqttReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                Log.d(TAG, "Received broadcast: " + action);
                
                if (MqttManager.ACTION_MQTT_CONNECTED.equals(action)) {
                    textMqttStatus.setText(getString(R.string.mqtt_status_connected));
                    textMqttStatus.setTextColor(0xFF66BB66);
                    btnMqttConnect.setText(getString(R.string.mqtt_disconnect));
                    appendLog("MQTT Connected");
                } else if (MqttManager.ACTION_MQTT_DISCONNECTED.equals(action)) {
                    textMqttStatus.setText(getString(R.string.mqtt_status_disconnected));
                    textMqttStatus.setTextColor(0xFFFF6666);
                    btnMqttConnect.setText(getString(R.string.mqtt_connect));
                    appendLog("MQTT Disconnected");
                } else if (MqttManager.ACTION_MQTT_MESSAGE_RECEIVED.equals(action)) {
                    String topic = intent.getStringExtra(MqttManager.EXTRA_TOPIC);
                    String message = intent.getStringExtra(MqttManager.EXTRA_MESSAGE);
                    appendLog("MQTT Message: " + message);
                }
            }
        };
        
        IntentFilter filter = new IntentFilter();
        filter.addAction(MqttManager.ACTION_MQTT_CONNECTED);
        filter.addAction(MqttManager.ACTION_MQTT_DISCONNECTED);
        filter.addAction(MqttManager.ACTION_MQTT_MESSAGE_RECEIVED);
        registerReceiver(mqttReceiver, filter);
    }
    
    private void setupMqttConnectButton() {
        btnMqttConnect.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                String currentText = btnMqttConnect.getText().toString();
                
                if (currentText.equals(getString(R.string.mqtt_disconnect))) {
                    textMqttStatus.setText(getString(R.string.mqtt_status_disconnected));
                    textMqttStatus.setTextColor(0xFFFF6666);
                    btnMqttConnect.setText(getString(R.string.mqtt_connect));
                    appendLog("MQTT Disconnect requested");
                } else {
                    String serverUri = editMqttServerUri.getText().toString().trim();
                    String clientId = editMqttClientId.getText().toString().trim();
                    String username = editMqttUsername.getText().toString().trim();
                    String password = editMqttPassword.getText().toString();
                    
                    if (serverUri.isEmpty()) {
                        Toast.makeText(MainActivity.this, "Please enter MQTT Server URI", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    if (clientId.isEmpty()) {
                        clientId = "tellme_" + System.currentTimeMillis();
                        editMqttClientId.setText(clientId);
                    }
                    
                    textMqttStatus.setText(getString(R.string.mqtt_status_connecting));
                    textMqttStatus.setTextColor(0xFFFFAA00);
                    
                    Intent serviceIntent = new Intent(MainActivity.this, ServerService.class);
                    serviceIntent.putExtra("mqttServerUri", serverUri);
                    serviceIntent.putExtra("mqttClientId", clientId);
                    serviceIntent.putExtra("mqttUsername", username);
                    serviceIntent.putExtra("mqttPassword", password);
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent);
                    } else {
                        startService(serviceIntent);
                    }
                    
                    appendLog("MQTT Connect requested: " + serverUri);
                    updateTopicInfo();
                }
            }
        });
    }
    
    private void setupPortConfirmButton() {
        Button btnConfirmPort = (Button) findViewById(R.id.buttonConfirmPort);
        if (btnConfirmPort != null) {
            btnConfirmPort.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    String portStr = tvServerPort.getText().toString().trim();
                    if (!portStr.isEmpty()) {
                        try {
                            int newPort = Integer.parseInt(portStr);
                            if (newPort > 0 && newPort <= 65535) {
                                SharedPreferences.Editor editor = getSharedPreferences("ServerPrefs", MODE_PRIVATE).edit();
                                editor.putInt("httpPort", newPort);
                                editor.apply();
                                
                                Intent restartIntent = new Intent(MainActivity.this, ServerService.class);
                                restartIntent.putExtra("httpPort", newPort);
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    startForegroundService(restartIntent);
                                } else {
                                    startService(restartIntent);
                                }
                                
                                appendLog("HTTP Port changed to: " + newPort);
                                Log.d(TAG, "Port changed to: " + newPort);
                            } else {
                                tvServerPort.setError("Port must be 1-65535");
                            }
                        } catch (NumberFormatException e) {
                            tvServerPort.setError("Invalid port number");
                        }
                    }
                }
            });
        }
    }
    
    private void setupClearButton() {
        clear.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                tvClientMsg.setText("");
            }
        });
    }
    
    private void appendLog(String message) {
        String current = tvClientMsg.getText().toString();
        String timestamp = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        String newLog = "[" + timestamp + "] " + message + "\n" + current;
        if (newLog.length() > 5000) {
            newLog = newLog.substring(0, 5000);
        }
        tvClientMsg.setText(newLog);
    }

    public String getDeviceIpAddress() {
        String ipAddress = "127.0.0.1";
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }
                
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress inetAddress = addresses.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress.getHostAddress().indexOf(':') < 0) {
                        ipAddress = inetAddress.getHostAddress();
                        Log.d(TAG, "Found IP: " + ipAddress + " on interface: " + networkInterface.getName());
                        if (networkInterface.getName().toLowerCase().contains("wlan") ||
                            networkInterface.getName().toLowerCase().contains("wifi")) {
                            return ipAddress;
                        }
                    }
                }
            }
        } catch (SocketException e) {
            Log.e(TAG, "Error getting IP address", e);
        }
        return ipAddress;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    private void checkFloatingWindowPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, 1001);
            }
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    Log.d(TAG, "Floating window permission granted");
                } else {
                    Log.d(TAG, "Floating window permission denied");
                }
            }
        }
    }
    
    @Override
    protected void onDestroy() {
        if (mqttReceiver != null) {
            unregisterReceiver(mqttReceiver);
        }
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        super.onDestroy();
    }
}
