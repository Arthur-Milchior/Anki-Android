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

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

// Limit updates by posting runnables at the specified intervals on the main thread.
// Only the last posted runnable is run, others are skipped.
internal class DelayingLimiter(
    private val interval: Long,
) {
    private val handler by lazy {
        Handler(
            Looper.myLooper()
                ?: throw IllegalStateException("No looper in thread " + Thread.currentThread())
        )
    }

    private var lastRunAt = -1L
    private var block: (() -> Unit)? = null

    fun post(block: () -> Unit) {
        val timeSinceLastRun = SystemClock.elapsedRealtime() - lastRunAt
        val tickScheduled = this.block != null

        this.block = block

        if (timeSinceLastRun >= interval) {
            tick()
        } else if (!tickScheduled) {
            handler.postDelayed(::tick, interval - timeSinceLastRun)
        }
    }

    private fun tick() {
        block?.let {
            it()
            lastRunAt = SystemClock.elapsedRealtime()
            block = null
        }
    }
}
