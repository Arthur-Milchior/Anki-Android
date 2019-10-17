package com.ichi2.anki.services;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.ichi2.anki.CollectionHelper;
import com.ichi2.libanki.Collection;
import com.ichi2.utils.JSONObject_;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Calendar;

public class BootService extends BroadcastReceiver {

    /**
     * This service is also run when the app is started (from {@link com.ichi2.anki.AnkiDroidApp},
     * so we need to make sure that it isn't run twice.
     */
    private static boolean sWasRun = false;


    @Override
    public void onReceive(Context context, Intent intent) {
        if (sWasRun) {
            return;
        }
        if (!CollectionHelper.hasStorageAccessPermission(context)) {
            return;
        }

        scheduleDeckReminder(context);
        scheduleNotification(context);
        sWasRun = true;
    }

    private void scheduleDeckReminder(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        for (JSONObject_ deck : CollectionHelper.getInstance().getCol(context).getDecks().all()) {
            Collection col = CollectionHelper.getInstance().getCol(context);
            if (col.getDecks().isDyn(deck.getLong_("id"))) {
                continue;
            }
            final long deckConfigurationId = deck.getLong_("conf");
            final JSONObject_ deckConfiguration = col.getDecks().getConf(deckConfigurationId);

            if (deckConfiguration.has("reminder")) {
                final JSONObject_ reminder = deckConfiguration.getJSONObject_("reminder");

                if (reminder.getBoolean_("enabled")) {
                    final PendingIntent reminderIntent = PendingIntent.getBroadcast(
                            context,
                            (int) deck.getLong_("id"),
                            new Intent(context, ReminderService.class).putExtra(ReminderService.EXTRA_DECK_ID,
                                    deck.getLong_("id")),
                            0
                    );
                    final Calendar calendar = Calendar.getInstance();

                    calendar.set(Calendar.HOUR_OF_DAY, reminder.getJSONArray_("time").getInt_(0));
                    calendar.set(Calendar.MINUTE, reminder.getJSONArray_("time").getInt_(1));
                    calendar.set(Calendar.SECOND, 0);

                    alarmManager.setRepeating(
                            AlarmManager.RTC_WAKEUP,
                            calendar.getTimeInMillis(),
                            AlarmManager.INTERVAL_DAY,
                            reminderIntent
                    );
                }
            }
        }
    }

    public static void scheduleNotification(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        if (Integer.parseInt(sp.getString("minimumCardsDueForNotification", "1000001")) <= 1000000) {
            return;
        }

        final Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, sp.getInt("dayOffset", 0));
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        final PendingIntent notificationIntent =
                PendingIntent.getBroadcast(context, 0, new Intent(context, NotificationService.class), 0);
        alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                calendar.getTimeInMillis(),
                AlarmManager.INTERVAL_DAY,
                notificationIntent
        );
    }
}
