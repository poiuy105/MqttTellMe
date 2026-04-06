package com.example.server;

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
    private static final int REQUEST_CODE_OVERLAY_PERMISSION = 1001;
    
    private TextView tvClientMsg;
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
    
    private boolean isWaitingForPermission = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        SharedPreferences prefs = getSharedPreferences("ServerPrefs", MODE_PRIVATE);
        int startCount = prefs.getInt("startCount", 0);
        
        Log.d(TAG, "onCreate: startCount=" + startCount);
        
        if (startCount == 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                isWaitingForPermission = true;
                requestOverlayPermission();
                return;
            }
            
            initializeFirstRun();
        } else {
            Log.d(TAG, "Not first start, starting service and finishing activity");
            startServerService();
            finish();
        }
    }
    
    private void requestOverlayPermission() {
        Log.d(TAG, "Requesting overlay permission");
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + getPackageName()));
        startActivityForResult(intent, REQUEST_CODE_OVERLAY_PERMISSION);
    }
    
    private void initializeFirstRun() {
        Log.d(TAG, "Initializing first run");
        
        setContentView(R.layout.activity_main);
        initViews();
        loadMqttConfig();
        setupMqttReceiver();
        
        setupMqttConnectButton();
        setupClearButton();
        
        startServerService();
        
        SharedPreferences prefs = getSharedPreferences("ServerPrefs", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt("startCount", 1);
        editor.apply();
        
        Log.d(TAG, "First run initialization completed");
    }
    
    private void startServerService() {
        Intent serviceIntent = new Intent(this, ServerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        Log.d(TAG, "ServerService started");
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_CODE_OVERLAY_PERMISSION) {
            isWaitingForPermission = false;
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    Log.d(TAG, "Overlay permission granted");
                    initializeFirstRun();
                } else {
                    Log.w(TAG, "Overlay permission denied");
                    Toast.makeText(this, "悬浮窗权限被拒绝，部分功能可能无法正常使用", Toast.LENGTH_LONG).show();
                    
                    setContentView(R.layout.activity_main);
                    initViews();
                    loadMqttConfig();
                    setupMqttReceiver();
                    
                    setupMqttConnectButton();
                    setupClearButton();
                    
                    appendLog("警告: 悬浮窗权限被拒绝，弹窗功能将无法使用");
                    
                    startServerService();
                    
                    SharedPreferences prefs = getSharedPreferences("ServerPrefs", MODE_PRIVATE);
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putInt("startCount", 1);
                    editor.apply();
                }
            } else {
                initializeFirstRun();
            }
        }
    }
    
    private void initViews() {
        tvClientMsg = (TextView) findViewById(R.id.textViewClientMessage);
        
        editMqttServerUri = (EditText) findViewById(R.id.editMqttServerUri);
        editMqttClientId = (EditText) findViewById(R.id.editMqttClientId);
        editMqttUsername = (EditText) findViewById(R.id.editMqttUsername);
        editMqttPassword = (EditText) findViewById(R.id.editMqttPassword);
        textMqttStatus = (TextView) findViewById(R.id.textMqttStatus);
        textMqttTopicInfo = (TextView) findViewById(R.id.textMqttTopicInfo);
        btnMqttConnect = (Button) findViewById(R.id.btnMqttConnect);
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
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mqttReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mqttReceiver, filter);
        }
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
    
    private void setupClearButton() {
        Button clear = (Button) findViewById(R.id.button1);
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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }
    
    @Override
    protected void onDestroy() {
        if (mqttReceiver != null) {
            try {
                unregisterReceiver(mqttReceiver);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Receiver not registered: " + e.getMessage());
            }
        }
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        super.onDestroy();
    }
}
