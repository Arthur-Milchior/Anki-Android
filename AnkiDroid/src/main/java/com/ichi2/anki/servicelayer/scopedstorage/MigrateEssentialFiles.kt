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

package com.ichi2.anki.servicelayer.scopedstorage

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.ichi2.anki.CollectionHelper
import com.ichi2.libanki.Storage
import java.io.Closeable

open class MigrateEssentialFiles(
    private val context: Context,
) {

    /** Checks that the default collection (from [CollectionHelper.getCol]) can be opened */
    @VisibleForTesting
    open fun checkCollection() {
        CollectionHelper.getInstance().getCol(context) ?: throw IllegalStateException("collection could not be opened")
    }

    /**
     * Represents a locked collection. Unlocks the collection when [close] is called
     *
     * Usage:
     * ```kotlin
     * LockedCollection.createLockedInstance().use {
     *      // do something requiring the collection to be closed
     * } // collection is unlocked here
     * ```
     */
    class LockedCollection private constructor() : Closeable {
        companion object {
            /**
             * Locks the collection and creates an instance of [LockedCollection]
             * @see Storage.lockCollection
             */
            fun createLockedInstance(): LockedCollection {
                // In Java, file locking is per JVM and not per thread. This means that on macOS,
                // a collection can be opened even if the underlying .anki2 is locked. So we need to lock it
                // with a static
                Storage.lockCollection()
                return LockedCollection()
            }
        }

        /** Unlocks the collection */
        override fun close() {
            Storage.unlockCollection()
        }
    }

    /**
     * An exception which requires user action to resolve
     */
    abstract class UserActionRequiredException(message: String) : RuntimeException(message) {
        constructor() : this("")

        /**
         * The user must perform 'Check Database'
         */
        class CheckDatabaseException : UserActionRequiredException()
    }
}
