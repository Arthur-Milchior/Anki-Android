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

import androidx.annotation.VisibleForTesting
import com.ichi2.anki.model.Directory
import com.ichi2.anki.model.DiskFile
import com.ichi2.anki.servicelayer.scopedstorage.MigrateUserData.MigrationContext
import timber.log.Timber
import java.io.File
import java.nio.file.Files.createDirectory

data class MoveDirectory(val source: Directory, val destination: File) : MigrateUserData.Operation() {
    override fun execute(context: MigrateUserData.MigrationContext): List<MigrateUserData.Operation> {

        // This seems unlikely to happen. Both paths need to be on the same mount point
        // We use directory.exists() to ensure that this operation doesn't occur twice. renameTo
        // is likely to be expensive, so we use the creation of the target directory to mark
        // that the rename previously failed
        if (context.attemptRename && !destination.exists() && rename(source, destination)) {
            Timber.d("successfully renamed '$source' to '$destination'")
            return operationCompleted()
        } else {
            context.attemptRename = false
        }

        Timber.d("creating folder '$destination'")
        if (!createDirectory(destination)) {
            context.reportError(this, IllegalStateException("Could not create '$destination'"))
            return operationCompleted()
        }

        val destinationDirectory = Directory.createInstance(destination)
        if (destinationDirectory == null) {
            context.reportError(this, IllegalStateException("Could not create '$destination'"))
            return operationCompleted()
        }

        val moveOperations = source.listFiles()
            .mapNotNull { toMoveOperation(it, context) }
        return moveOperations + DeleteEmptyDirectory(source)
    }

    @VisibleForTesting
    internal fun toMoveOperation(sourceFile: File, context: MigrateUserData.MigrationContext): MigrateUserData.Operation? {
        // since the file comes from listFiles(), we assume .exists() returns true
        return when {
            sourceFile.isFile -> {
                val fileToCreate = DiskFile.createInstanceUnsafe(sourceFile)
                MoveFile(fileToCreate, File(destination, sourceFile.name))
            }
            sourceFile.isDirectory -> {
                val directory = Directory.createInstanceUnsafe(sourceFile)
                MoveDirectory(directory, File(destination, sourceFile.name))
            }
            else -> {
                context.reportError(this, IllegalStateException("File was neither file nor directory '${sourceFile.canonicalPath}'"))
                null
            }
        }
    }
    companion object {
        /** Creates a directory if it doesn't already exist */
        @VisibleForTesting
        fun createDirectory(directory: File) = directory.exists() || directory.mkdirs()

        @VisibleForTesting
        internal fun rename(source: Directory, destination: File) = source.renameTo(destination)
    }
}
