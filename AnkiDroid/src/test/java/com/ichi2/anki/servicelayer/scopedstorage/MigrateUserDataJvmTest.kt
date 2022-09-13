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

import android.content.SharedPreferences
import com.ichi2.anki.servicelayer.ScopedStorageService
import com.ichi2.anki.servicelayer.scopedstorage.MigrateUserData.MissingDirectoryException
import com.ichi2.anki.servicelayer.scopedstorage.MigrateUserDataJvmTest.SourceType.*
import com.ichi2.testutils.createTransientDirectory
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A test for [MigrateUserData] which does not require Robolectric
 */
class MigrateUserDataJvmTest {

    companion object {
        private lateinit var sourceDir: String
        private lateinit var destDir: String
        private lateinit var missingDir: String

        @BeforeClass
        @JvmStatic // required for @BeforeClass
        fun initClass() {
            sourceDir = createTransientDirectory().canonicalPath
            destDir = createTransientDirectory().canonicalPath
            missingDir = createTransientDirectory().also { it.delete() }.canonicalPath
        }
    }

    @Test
    fun valid_instance_if_directories_exist() {
        val preferences = getScopedStorageMigrationPreferences(source = VALID_DIR, destination = VALID_DIR)
        val data = MigrateUserData.createInstance(preferences)

        assertNotNull(data, "a valid task instance should be created")

        assertEquals(data.source.directory.canonicalPath, sourceDir)
        assertEquals(data.destination.directory.canonicalPath, destDir)
    }

    @Test
    fun no_instance_if_not_migrating() {
        val preferences = getScopedStorageMigrationPreferences(source = NOT_SET, destination = NOT_SET)
        val data = MigrateUserData.createInstance(preferences)
        assertNull(data, "a valid task instance should not be created as we are not migrating")
    }

    @Test
    fun error_if_settings_are_bad() {
        val preferences = getScopedStorageMigrationPreferences(source = NOT_SET, destination = VALID_DIR)
        val exception = assertFailsWith<IllegalStateException> { MigrateUserData.createInstance(preferences) }

        assertEquals(exception.message, "Expected either all or no migration directories set. 'migrationSourcePath': ''; 'migrationDestinationPath': '$destDir'")
    }

    @Test
    fun error_if_source_does_not_exist() {
        val preferences = getScopedStorageMigrationPreferences(source = MISSING_DIR, destination = VALID_DIR)
        val exception = assertFailsWith<MissingDirectoryException> { MigrateUserData.createInstance(preferences) }
        assertEquals(exception.directories.single().file.canonicalPath, missingDir)
    }

    @Test
    fun error_if_destination_does_not_exist() {
        val preferences = getScopedStorageMigrationPreferences(source = VALID_DIR, destination = MISSING_DIR)
        val exception = assertFailsWith<MissingDirectoryException> { MigrateUserData.createInstance(preferences) }
        assertEquals(exception.directories.single().file.canonicalPath, missingDir)
    }

    private fun getScopedStorageMigrationPreferences(source: SourceType, destination: SourceType): SharedPreferences {
        return mock {
            on { getString(ScopedStorageService.PREF_MIGRATION_SOURCE, "") } doReturn
                when (source) {
                    VALID_DIR -> sourceDir
                    MISSING_DIR -> missingDir
                    NOT_SET -> ""
                }
            on { getString(ScopedStorageService.PREF_MIGRATION_DESTINATION, "") } doReturn
                when (destination) {
                    VALID_DIR -> destDir
                    MISSING_DIR -> missingDir
                    NOT_SET -> ""
                }
        }
    }

    enum class SourceType {
        NOT_SET,
        MISSING_DIR,
        VALID_DIR
    }
}
