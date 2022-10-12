/****************************************************************************************
 * Copyright (c) 2020 Arthur Milchior <arthur@milchior.fr>                              *
 *                                                                                      *
 * This program is free software; you can redistribute it and/or modify it under        *
 * the terms of the GNU General Public License as published by the Free Software        *
 * Foundation; either version 3 of the License, or (at your option) any later           *
 * version.                                                                             *
 *                                                                                      *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY      *
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A      *
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.             *
 *                                                                                      *
 * You should have received a copy of the GNU General Public License along with         *
 * this program.  If not, see <http://www.gnu.org/licenses/>.                           *
 ****************************************************************************************/

package com.ichi2.async.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.ichi2.anki.AnkiDroidApp
import timber.log.Timber

private const val NOTIFICATION_CHANNEL_ID = "progress"
private const val NOTIFICATION_ID = 64

open class ProgressService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    // TODO data object
    sealed interface Progress {
        object NoProgress : Progress
        object Indeterminate : Progress
        data class Determinate(val ratio: Float) : Progress
    }

    @MainThread
    fun update(icon: Int, title: CharSequence, text: CharSequence, progress: Progress) {
        limiter.post {
            Timber.d("ProgressService.update(progress = $progress)")
            startForeground(NOTIFICATION_ID, makeNotification(icon, title, text, progress))
        }
    }

    private fun makeNotification(icon: Int, title: CharSequence, text: CharSequence, progress: Progress) =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .apply {
                when (progress) {
                    is Progress.NoProgress -> {}
                    is Progress.Indeterminate -> setProgress(0, 0, true)
                    is Progress.Determinate -> setProgress(100, (progress.ratio * 100).toInt(), false)
                }
            }
            .build()

    // Not strictly necessary but posting a lot of notifications may take toll on the CPU.
    // By default you can post notifications updates at the rate of roughly 5 per second,
    // realistically, probably much lower.
    // See DEFAULT_MAX_NOTIFICATION_ENQUEUE_RATE here and the relevant rate algorithm here:
    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/services/core/java/com/android/server/notification/NotificationManagerService.java
    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/services/core/java/com/android/server/notification/RateEstimator.java
    private val limiter = DelayingLimiter(250)

    companion object {
        init {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                makeNotificationChannel()
            }
        }

        @RequiresApi(Build.VERSION_CODES.O)
        private fun makeNotificationChannel() {
            val notificationManager = AnkiDroidApp.instance
                .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Progress",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                setSound(null, null)
                enableVibration(false)
                enableLights(true)
            }

            notificationManager.createNotificationChannel(channel)
        }
    }
}
