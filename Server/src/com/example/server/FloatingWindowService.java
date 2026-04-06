package com.example.server;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

public class FloatingWindowService extends Service {
    private static final String TAG = "FloatingWindowService";
    
    private final IBinder binder = new LocalBinder();
    private final Object windowLock = new Object();
    
    private Handler mainHandler;
    private WindowManager windowManager;
    private View floatingView;
    private TextView tvText;
    private boolean isWindowShowing = false;
    
    public class LocalBinder extends Binder {
        FloatingWindowService getService() {
            return FloatingWindowService.this;
        }
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "FloatingWindowService created");
        mainHandler = new Handler(Looper.getMainLooper());
        createFloatingWindow();
    }
    
    private void createFloatingWindow() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        LayoutInflater inflater = LayoutInflater.from(this);
        floatingView = inflater.inflate(R.layout.floating_window, null);
        
        tvText = floatingView.findViewById(R.id.tv_text);
    }
    
    private boolean hasOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }
    
    public void showWindow() {
        Log.d(TAG, "showWindow called");
        
        if (!hasOverlayPermission()) {
            Log.w(TAG, "Cannot show window - overlay permission not granted");
            return;
        }
        
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (windowLock) {
                    try {
                        if (floatingView != null && !isWindowShowing) {
                            if (floatingView.getWindowToken() != null) {
                                Log.w(TAG, "Window already has token, skipping");
                                return;
                            }
                            
                            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                                getWindowWidth(),
                                WindowManager.LayoutParams.WRAP_CONTENT,
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O 
                                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
                                    : WindowManager.LayoutParams.TYPE_PHONE,
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | 
                                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                                PixelFormat.TRANSLUCENT
                            );
                            
                            params.gravity = Gravity.TOP | Gravity.END;
                            params.x = 16;
                            params.y = 16;
                            
                            windowManager.addView(floatingView, params);
                            isWindowShowing = true;
                            Log.d(TAG, "Floating window shown successfully");
                        }
                    } catch (SecurityException e) {
                        Log.e(TAG, "SecurityException when showing window: " + e.getMessage());
                    } catch (Exception e) {
                        Log.e(TAG, "Error showing floating window", e);
                    }
                }
            }
        });
    }
    
    public void hideWindow() {
        Log.d(TAG, "hideWindow called");
        
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (windowLock) {
                    try {
                        if (floatingView != null && isWindowShowing) {
                            if (floatingView.getWindowToken() != null) {
                                windowManager.removeView(floatingView);
                            }
                            isWindowShowing = false;
                            Log.d(TAG, "Floating window hidden successfully");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error hiding floating window", e);
                        isWindowShowing = false;
                    }
                }
            }
        });
    }
    
    public void updateText(String text) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (tvText != null) {
                    tvText.setText(text);
                }
            }
        });
    }
    
    private int getWindowWidth() {
        try {
            DisplayMetrics displayMetrics = new DisplayMetrics();
            if (windowManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.view.WindowMetrics windowMetrics = windowManager.getCurrentWindowMetrics();
                    return windowMetrics.getBounds().width() / 3;
                } else {
                    windowManager.getDefaultDisplay().getMetrics(displayMetrics);
                    return displayMetrics.widthPixels / 3;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting window width", e);
        }
        return 300;
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    
    @Override
    public void onDestroy() {
        Log.d(TAG, "FloatingWindowService destroying");
        try {
            if (isWindowShowing && floatingView != null && floatingView.getWindowToken() != null) {
                windowManager.removeView(floatingView);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error removing view in onDestroy", e);
        }
        isWindowShowing = false;
        super.onDestroy();
        Log.d(TAG, "FloatingWindowService destroyed");
    }
}
