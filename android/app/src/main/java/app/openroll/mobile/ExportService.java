package app.openroll.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps the app eligible to finish an export while the person is elsewhere.
 *
 * The rendering happens in the WebView, so this service exists only to hold the
 * mediaProcessing foreground state Android grants: without it the system may pause the
 * WebView the moment the screen goes off, and an hour long export would fail at minute
 * fifty-nine. It starts when an export starts and stops the moment the queue empties.
 */
public class ExportService extends Service {

    private static final String CHANNEL = "openroll-exports";
    private static final int ID = 41;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Exports", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String text = intent != null && intent.getStringExtra("text") != null ? intent.getStringExtra("text") : "Rendering your video";
        Notification notification = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("OpenRoll")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
        } else {
            startForeground(ID, notification);
        }
        // Not sticky: a killed process has no export to resume.
        return START_NOT_STICKY;
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        // Android 15 caps mediaProcessing at six hours a day; give the slot back politely.
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Asks for the foreground slot. This is a keep-alive, never a prerequisite: if Android
     * refuses the start (a background start, the daily mediaProcessing budget, a vendor's own
     * policy) the render must still run while the app is on screen, so the failure is swallowed
     * and the export continues without the guarantee.
     */
    static void begin(Context context, String text) {
        Intent intent = new Intent(context, ExportService.class).putExtra("text", text);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (RuntimeException refused) {
            // ForegroundServiceStartNotAllowedException and friends.
        }
    }

    static void end(Context context) {
        try {
            context.stopService(new Intent(context, ExportService.class));
        } catch (RuntimeException ignored) {
        }
    }
}
