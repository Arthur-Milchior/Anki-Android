/*
 *  Copyright (c) 2022 David Allison <davidallisongithub@gmail.com>
 *
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.anki.exception

import timber.log.Timber

/**
 * An exception wrapper signifying that the operation throwing the wrapped exception may be retried
 * @param inner wrapped exception
 */
class RetryableException(private val inner: Throwable) : java.lang.RuntimeException(inner) {
    companion object {
        /**
         * Retries the provided action if a [RetryableException] is thrown
         */
        fun <T> retryOnce(action: (() -> T)): T {
            return try {
                action.invoke()
            } catch (e: RetryableException) {
                Timber.w(e, "Found retryable exception, retrying")
                try {
                    action.invoke()
                } catch (e: RetryableException) {
                    Timber.w(e, "action was retried once, throwing")
                    throw e.inner
                }
            }
        }
    }
}
