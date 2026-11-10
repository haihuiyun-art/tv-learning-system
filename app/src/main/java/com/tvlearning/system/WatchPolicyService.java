package com.tvlearning.system;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

public class WatchPolicyService extends Service {
    private static final int PORT = 8787;
    private static WatchPolicyService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PolicyStore store;
    private PolicyEngine engine;
    private AppRepository apps;
    private DeviceControlManager deviceControl;
    private LocalControlServer webServer;
    private String localAddress = "本地网页正在启动";
    private long lastLockLaunch;
    private long lastHomeLaunch;
    private int monitorTicks;
    private boolean screenOff;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                screenOff = true;
                engine.tick("");
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                screenOff = false;
                engine.onScreenRestored();
            }
        }
    };

    private final Runnable monitor = new Runnable() {
        @Override
        public void run() {
            try {
                deviceControl.syncProtection();
                String foreground = screenOff ? "" : apps.getForegroundPackage();
                engine.tick(foreground);
                monitorTicks++;
                if (webServer != null && monitorTicks % 5 == 0) {
                    updateLocalAddress();
                }
                PolicyStore.RuntimeState state = store.getRuntime();
                if (!screenOff) {
                    long now = SystemClock.elapsedRealtime();
                    if (PolicyEngine.isLockedState(state.state)) {
                        if (!getPackageName().equals(foreground) && !deviceControl.hasTemporaryAccess()
                                && now - lastLockLaunch >= 3000) {
                            showLock();
                            lastLockLaunch = now;
                        }
                    } else if (shouldBlockHome(foreground) && now - lastHomeLaunch >= 3000) {
                        showHome();
                        lastHomeLaunch = now;
                    }
                }
            } catch (RuntimeException e) {
                store.addLog("管控循环异常：" + e.getClass().getSimpleName());
            }
            handler.postDelayed(this, 1000);
        }
    };

    static void start(Context context) {
        Intent intent = new Intent(context, WatchPolicyService.class);
        try {
            context.startForegroundService(intent);
        } catch (RuntimeException e) {
            PolicyStore.get(context).addLog("管控服务启动被系统拒绝");
        }
    }

    static String getAddress() {
        return instance == null ? "本地管控服务未运行" : instance.localAddress;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        startForegroundNotification();
        store = PolicyStore.get(this);
        engine = PolicyEngine.get(this);
        apps = new AppRepository(this);
        deviceControl = DeviceControlManager.get(this);
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        screenOff = power != null && !power.isScreenOn();
        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(screenReceiver, screenFilter);
        }
        startWebServer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handler.removeCallbacks(monitor);
        handler.post(monitor);
        return START_STICKY;
    }

    private void startForegroundNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel("learning_guardian",
                getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
        Notification notification = new Notification.Builder(this, "learning_guardian")
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_text))
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
        startForeground(1, notification);
    }

    private void startWebServer() {
        try {
            webServer = new LocalControlServer(this, PORT);
            webServer.start(LocalControlServer.SOCKET_READ_TIMEOUT, false);
            updateLocalAddress();
            store.addLog("局域网家长网页已启动");
        } catch (IOException | RuntimeException e) {
            if (webServer != null) {
                webServer.stop();
                webServer = null;
            }
            localAddress = "本地网页启动失败：" + e.getClass().getSimpleName();
            store.addLog(localAddress);
        }
    }

    private void updateLocalAddress() {
        String ip = findLocalIpv4();
        localAddress = ip.isEmpty() ? "未找到局域网地址" : "http://" + ip + ":" + PORT;
    }

    private boolean shouldBlockHome(String foreground) {
        return store.isConfigured()
                && store.isProtectionEnabled()
                && apps.isDefaultHome()
                && apps.isHomePackage(foreground)
                && !engine.isOriginalHomeAllowed(foreground);
    }

    private void showHome() {
        Intent intent = new Intent(this, HomeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(intent);
            store.addLog("已阻止未经授权的原桌面入口");
        } catch (RuntimeException e) {
            store.addLog("系统阻止学习主页恢复");
        }
    }

    private void showLock() {
        Intent intent = new Intent(this, LockActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            store.addLog("系统阻止锁定界面启动");
        }
    }

    private static String findLocalIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (SocketException ignored) {
            return "";
        }
        return "";
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(monitor);
        try {
            unregisterReceiver(screenReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        if (webServer != null) {
            webServer.stop();
        }
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
