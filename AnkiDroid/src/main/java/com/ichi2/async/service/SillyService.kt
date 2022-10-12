/****************************************************************************************
 * Copyright (c) 2022 Oakkitten                                                         *
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

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.ichi2.anki.R

class SillyService : ProgressService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        update(R.drawable.ic_clock, "Updating stuff", "Phase one", Progress.Indeterminate)

        (5000L..15000).forEach { tick ->
            runOnMain(delay = tick) {
                val progress = tick / 15000f
                val percents = (progress * 100).toInt()
                update(R.drawable.ic_clock, "Updating stuff", "Phase two: $percents%", Progress.Determinate(tick / 15000f))
            }
        }

        runOnMain(delay = 16000) {
            // Or NoProgress, but completed progress notifications have a better chance surviving throttling maybe
            // https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-10.0.0_r46/services/core/java/com/android/server/notification/NotificationManagerService.java#5020
            update(R.drawable.ic_clock, "Updating stuff", "Done!", Progress.Determinate(1f))

            // Wait a little before closing so that the user can enjoy staring at 100%
            runOnMain(delay = 3000) { stopSelf() }
        }

        // Return START_NOT_STICKY to make the system
        // automatically restart the service after killing it
        return START_NOT_STICKY
    }
}

fun Activity.startSillyService() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(Intent(this, SillyService::class.java))
    } else {
        startService(Intent(this, SillyService::class.java))
    }
}

private fun runOnMain(delay: Long, block: () -> Unit) {
    Handler(Looper.getMainLooper()).postDelayed(block, delay)
}
