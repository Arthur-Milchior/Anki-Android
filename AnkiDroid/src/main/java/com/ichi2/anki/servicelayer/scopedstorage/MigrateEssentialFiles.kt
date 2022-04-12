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
import android.provider.Settings.Global.putString
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CollectionHelper
import com.ichi2.anki.exception.RetryableException
import com.ichi2.anki.servicelayer.*
import com.ichi2.anki.servicelayer.ScopedStorageService.PREF_MIGRATION_DESTINATION
import com.ichi2.anki.servicelayer.ScopedStorageService.PREF_MIGRATION_SOURCE
import com.ichi2.annotations.NeedsTest
import com.ichi2.compat.CompatHelper
import com.ichi2.libanki.Collection
import com.ichi2.libanki.Storage
import timber.log.Timber
import java.io.Closeable
import java.io.File

open class MigrateEssentialFiles(
    private val context: Context,
    private val sourceDirectory: AnkiDroidDirectory,
    private val destinationDirectory: ScopedAnkiDroidDirectory
) {
    /**
     * Copies (not moves) the [essential files][listEssentialFiles] to [destinationDirectory]
     *
     * Then opens a collection at the new location, and updates "deckPath" there.
     *
     * @throws IllegalStateException Migration in progress
     * @throws IllegalStateException [destinationDirectory] is not empty
     * @throws UserActionRequiredException.MissingEssentialFileException if an essential file does not exist
     * @throws UserActionRequiredException.CheckDatabaseException if 'Check Database' needs to be done first
     * @throws IllegalStateException If a lock cannot be acquired on the collection
     *
     * After:
     *
     * [PREF_MIGRATION_SOURCE] contains the [AnkiDroidDirectory] with the remaining items to move ([sourceDirectory])
     * [PREF_MIGRATION_DESTINATION] contains an [AnkiDroidDirectory] with the copied collection.anki2/media ([destinationDirectory])
     * "deckPath" now points to the new location of the collection in private storage
     * [ScopedStorageService.UserDataMigrationPreferences.migrationInProgress] returns `true`
     */
    fun execute() {
        if (ScopedStorageService.userMigrationIsInProgress(context)) {
            throw IllegalStateException("Migration is already in progress")
        }

        val destinationPath = destinationDirectory.path

        ensureFolderIsEmpty(destinationPath)

        // ensure the current collection is the one in sourcePath
        ensurePathIsCurrentCollectionPath(sourceDirectory)

        // Throws MissingEssentialFileException if the files we need to copy don't exist
        ensureEssentialFilesExist(sourceDirectory)

        // Close the collection before we lock the files.
        // ensureCollectionNotCorrupted is not compatible with an already open collection
        closeCollection()

        // Race Condition! - The collection could be opened here before locking (maybe by API?).
        // This is resolved as a RetryableException is thrown if the collection is open

        // open the collection directly and ensure it's not corrupted (must be closed and not locked)
        ensureCollectionNotCorrupted(sourceDirectory.getCollectionAnki2Path())

        // Lock the collection & journal, to ensure that nobody can open/corrupt it
        // Also ensures the collection may not be opened
        lockCollection().use {
            // Copy essential files to new location. Guaranteed to be empty
            for (file in iterateEssentialFiles(sourceDirectory)) {
                copyTopLevelFile(file, destinationPath)
            }
        }

        val destinationCollectionAnki2Path = destinationPath.getCollectionAnki2Path()

        // Open the collection in the new location, checking for corruption
        ensureCollectionNotCorrupted(destinationCollectionAnki2Path)

        // set the preferences to the new deck path + checks CollectionHelper
        // sets migration variables (migrationIsInProgress will be true)
        updatePreferences(destinationPath)
    }

    /**
     * Ensures that all files in [listEssentialFiles] exist
     * @throws UserActionRequiredException.MissingEssentialFileException if a file does not exist
     */
    @NeedsTest("untested")
    private fun ensureEssentialFilesExist(sourcePath: AnkiDroidDirectory) {
        for (file in iterateEssentialFiles(sourcePath)) {
            if (!file.exists()) {
                throw UserActionRequiredException.MissingEssentialFileException(file)
            }
        }
    }

    /**
     * Copies [file] to [destinationDirectory], retaining the same filename
     */
    fun copyTopLevelFile(file: File, destinationDirectory: AnkiDroidDirectory) {
        val destinationPath = File(destinationDirectory.directory, file.name).path
        Timber.i("Migrating essential file: '${file.name}'")
        Timber.d("Copying '$file' to '$destinationPath'")
        CompatHelper.compat.copyFile(file.path, destinationPath)
    }

    /**
     * Updates preferences after a successful "essential files" migration.
     * After changing the preferences, we validate them
     */
    private fun updatePreferences(destinationPath: AnkiDroidDirectory) {
        val prefs = AnkiDroidApp.getSharedPrefs(context)

        // keep the old values in case we need to restore them
        val oldPrefValues = listOf(PREF_MIGRATION_SOURCE, PREF_MIGRATION_DESTINATION, "deckPath")
            .associateWith { prefs.getString(it, null) }

        prefs.edit {
            // specify that a migration is in progress
            putString(PREF_MIGRATION_SOURCE, sourceDirectory.directory.absolutePath)
            putString(PREF_MIGRATION_DESTINATION, destinationPath.directory.absolutePath)
            putString("deckPath", destinationPath.directory.absolutePath)
        }

        // open the collection in the new location - data is now migrated
        try {
            checkCollection()
        } catch (e: Throwable) {
            // if we can't open the migrated collection, revert the preference change so the user
            // can still use their collection.
            Timber.w("error opening new collection, restoring old values")
            prefs.edit {
                oldPrefValues.forEach {
                    putString(it.key, it.value)
                }
            }
            throw e
        }
    }

    /**
     * Ensures that [directory] is empty
     * @throws IllegalStateException if [directory] is not empty
     */
    private fun ensureFolderIsEmpty(directory: AnkiDroidDirectory) {
        val listFiles = directory.listFiles()

        if (listFiles.any()) {
            throw IllegalStateException("destination was non-empty '$directory'")
        }
    }

    /** Checks that the default collection (from [CollectionHelper.getCol]) can be opened */
    @VisibleForTesting
    open fun checkCollection() {
        CollectionHelper.getInstance().getCol(context) ?: throw IllegalStateException("collection could not be opened")
    }

    /**
     * Ensures that the collection is closed.
     * This will temporarily open the collection during the operation if it was already closed
     */
    private fun closeCollection() {
        val instance = CollectionHelper.getInstance()
        // this opens col if it wasn't closed
        val col = instance.getCol(context)
        col.close()
    }

    /**
     * Ensures that the provided [path] represents the current AnkiDroid collection ([CollectionHelper.getCol])
     */
    private fun ensurePathIsCurrentCollectionPath(path: AnkiDroidDirectory) {
        val currentCollectionFilePath = getCurrentCollectionPath()
        if (path.directory.canonicalPath != currentCollectionFilePath.directory.canonicalPath) {
            throw IllegalStateException("paths did not match: '$path' and '$currentCollectionFilePath' (Collection)")
        }
    }

    /** Converts the current AnkiDroid collection path to an [AnkiDroidDirectory] instance */
    private fun getCurrentCollectionPath(): AnkiDroidDirectory {
        val collectionAnki2Path = File(CollectionHelper.getCollectionPath(context))
        // happy with the !! here: the parent of the AnkiDroid file is a directory
        return AnkiDroidDirectory.createInstance(File(collectionAnki2Path.canonicalPath).parent!!)!!
    }

    /**
     * Locks the collection and returns a [Closeable] when the closeable is closed,
     * the collection is unlocked
     *
     * @throws IllegalStateException Collection is openable after lock acquired
     */
    private fun lockCollection(): Closeable {
        return createLockedCollection().also {
            // Since we locked the files, we want to ensure that the collection can no longer be opened
            try {
                ensureCollectionNotOpenable()
            } catch (e: Exception) {
                Timber.w(e, "collection was openable")
                it.close()
                throw e
            }
        }
    }

    /**
     * Locks the collection and returns a [LockedCollection] which allows the collection to be unlocked
     */
    @VisibleForTesting
    fun createLockedCollection() = LockedCollection.createLockedInstance()

    /**
     * Check that the collection is not openable. This is expected to be called after the collection is locked, to check whether it was correctly locked.
     * We must check it because improperly locked collections may lead to database corruption. (copying may mean the DB is out of sync with the journal)
     * If the collection is openable or open, close it.
     * @throws RetryableException ([IllegalStateException]) if the collection was openable
     */
    private fun ensureCollectionNotOpenable() {
        val lockedCollection: Collection?
        try {
            lockedCollection = CollectionHelper.getInstance().getCol(context)
        } catch (e: Exception) {
            Timber.i("Expected exception thrown: ", e)
            return
        }

        // Unexpected: collection was opened. Close it and report an error.
        // Note: it shouldn't be null - a null value infers a new collection can't be created
        // or if the storage media is removed
        try {
            lockedCollection?.close()
        } catch (e: Exception) {
        }

        throw RetryableException(IllegalStateException("Collection not locked correctly"))
    }

    /**
     * Given the path to a `collection.anki2` which is not open, ensures the collection is usable
     *
     * Otherwise: throws an exception
     *
     * @throws UserActionRequiredException.CheckDatabaseException If "check database" is required
     *
     * This may also fail for the following, less likely reasons:
     * * Collection is already open
     * * Collection directory does not exist
     * * Collection directory is not writable
     * * Error opening collection
     */
    open fun ensureCollectionNotCorrupted(path: CollectionFilePath) {
        var result: Collection? = null
        var exceptionInFinally: Exception? = null
        try {
            // Store the collection in `result` so we can close it in the `finally`
            // this can throw [StorageAccessException]: locked or invalid
            result = CollectionHelper.getInstance().getColFromPath(path, context)
            if (!result.basicCheck()) {
                throw UserActionRequiredException.CheckDatabaseException()
            }
        } finally {
            // this can throw, which ruins the stack trace if the above block threw
            try {
                result?.close()
            } catch (ex: Exception) {
                Timber.w("exception thrown closing database", ex)
                exceptionInFinally = ex
            }
        }

        // If close() threw in the finally {}, we want to abort.
        if (exceptionInFinally != null) {
            throw exceptionInFinally
        }
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
     * A file, or group of files which must be migrated synchronously while the collection is closed
     * This is either because the file is vital for when a collection is reopened in a new location
     * Or because it is immediately created and may cause a conflict
     */
    abstract class EssentialFile {
        abstract fun getFiles(sourceDirectory: String): List<File>

        fun spaceRequired(sourceDirectory: String): NumberOfBytes {
            return getFiles(sourceDirectory).sumOf { it.length() }
        }
    }

    /**
     * A SQLite database, which contains both a database and a journal
     * @see EssentialFile
     */
    internal class SqliteDb(val fileName: String) : EssentialFile() {
        override fun getFiles(sourceDirectory: String): List<File> {
            val list = mutableListOf(File(sourceDirectory, fileName))
            val journal = File(sourceDirectory, journalName)
            if (journal.exists()) {
                list.add(journal)
            }
            return list
        }

        // guaranteed to be + "-journal": https://www.sqlite.org/tempfiles.html
        private val journalName = "$fileName-journal"
    }

    /** A standard file which may not exist */
    class OptionalFile(val fileName: String) : EssentialFile() {
        override fun getFiles(sourceDirectory: String): List<File> {
            val file = File(sourceDirectory, fileName)
            return if (!file.exists()) {
                emptyList()
            } else {
                listOf(file)
            }
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

        /**
         * The user must determine why essential files don't exist
         */
        class MissingEssentialFileException(val file: File) : UserActionRequiredException("missing essential file: ${file.name}")
    }

    companion object {
        /**
         * Lists the files to be moved by [MigrateEssentialFiles]
         */
        internal fun listEssentialFiles(): List<EssentialFile> {
            return listOf(
                SqliteDb("collection.anki2"), // Anki collection
                SqliteDb("collection.media.ad.db2"), // media database + journal
                OptionalFile(".nomedia"), // written immediately
                OptionalFile("collection.log") // written immediately and conflicts
            )
        }

        /**
         * A collection of [File] objects to be moved by [MigrateEssentialFiles]
         */
        private fun iterateEssentialFiles(sourcePath: AnkiDroidDirectory) =
            listEssentialFiles().flatMap { it.getFiles(sourcePath.directory.canonicalPath) }
    }
}
