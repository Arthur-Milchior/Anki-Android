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

import com.ichi2.anki.model.Directory
import com.ichi2.anki.servicelayer.scopedstorage.MigrateUserData.Operation
import com.ichi2.testutils.*
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.instanceOf
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.spy
import timber.log.Timber
import java.io.File

/**
 * Test for [MoveDirectory]
 */
class MoveDirectoryTest {

    private val executionContext: MockMigrationContext = MockMigrationContext()

    @Test
    fun test_success_integration_test_recursive() {
        val source = createDirectory().withTempFile("tmp.txt")
        source.createTransientDirectory("more files").withTempFile("tmp-2.txt")
        val destinationFile = generateDestinationDirectoryRef()
        executionContext.attemptRename = false

        executeAll(MoveDirectory(source, destinationFile))

        assertThat("source directory should not exist", source.exists(), equalTo(false))
        assertThat("destination directory should exist", destinationFile.exists(), equalTo(true))
        assertThat("file should be copied", File(destinationFile, "tmp.txt").exists(), equalTo(true))

        val subdirectory = File(destinationFile, "more files")
        assertThat("subdir was copied", subdirectory.exists(), equalTo(true))
        assertThat("subdir file was copied", File(subdirectory, "tmp-2.txt").exists(), equalTo(true))
    }

    @Test
    fun test_fast_rename() {
        val source = createDirectory().withTempFile("tmp.txt")
        val destinationFile = generateDestinationDirectoryRef()
        executionContext.attemptRename = true

        val ret = MoveDirectory(source, destinationFile).executeOne()

        assertThat("fast rename returns no operations", ret, empty())
        assertThat("source directory should not exist", source.exists(), equalTo(false))
        assertThat("destination directory should exist", destinationFile.exists(), equalTo(true))
        assertThat("file should be copied", File(destinationFile, "tmp.txt").exists(), equalTo(true))
    }

    @Test
    fun failed_rename_avoids_further_renames() {
        // This is a performance optimization,
        val source = createDirectory().withTempFile("tmp.txt")
        val destinationFile = generateDestinationDirectoryRef()
        var renameCalled = 0

        executionContext.logExceptions = true
        // don't actually move the directory, or we'd have a problem
        val moveDirectory = spy(MoveDirectory(source, destinationFile)) {
            doAnswer { renameCalled++; return@doAnswer false }.`when`(it).rename(any(), any())
        }

        assertThat("rename was true", executionContext.attemptRename, equalTo(true))

        moveDirectory.executeOne()

        assertThat("rename is now false", executionContext.attemptRename, equalTo(false))
        assertThat("rename was called", renameCalled, equalTo(1))

        moveDirectory.executeOne()

        assertThat("rename was not called again", renameCalled, equalTo(1))

        assertThat(executionContext.exceptions, hasSize(0))
        assertThat("source was not copied", File(source.directory, "tmp.txt").exists(), equalTo(true))
    }

    @Test
    fun a_move_failure_is_not_fatal() {
        val source = createDirectory()
            .withTempFile("foo.txt")
            .withTempFile("bar.txt")
            .withTempFile("baz.txt")

        val destinationFile = generateDestinationDirectoryRef()

        // Use variables as we don't know which file will be returned in the middle from listFiles()
        var beforeFileName = ""
        var failedFileName = "" // ensure the second file fails
        var afterFileName = ""
        executionContext.attemptRename = false
        executionContext.logExceptions = true
        var movesProcessed = 0
        val operation = spy(MoveDirectory(source, destinationFile)) {
            doAnswer<Operation?> { op ->
                val x = op.callRealMethod()

                if (x is MoveFile) {
                    when (movesProcessed++) {
                        0 -> beforeFileName = x.sourceFile.file.name
                        1 -> {
                            failedFileName = x.sourceFile.file.name
                            return@doAnswer FailMove()
                        }
                        2 -> afterFileName = x.sourceFile.file.name
                        else -> throw IllegalStateException("only 3 expected")
                    }
                }

                return@doAnswer x as Operation?
            }.`when`(it).toMoveOperation(any(), any())
        }
        executeAll(operation)

        assertThat(executionContext.exceptions, hasSize(2))
        executionContext.exceptions[0].run {
            assertThat(this, instanceOf(TestException::class.java))
        }
        executionContext.exceptions[1].run {
            assertThat(this.message, containsString("directory was not empty"))
        }

        assertThat("fail was not copied", File(source.directory, failedFileName).exists(), equalTo(true))
        assertThat("file before failure was copied", File(source.directory, beforeFileName).exists(), equalTo(false))
        assertThat("file after failure was copied", File(source.directory, afterFileName).exists(), equalTo(false))
    }

    @Test
    fun empty_directory_is_deleted() {
        val source = createDirectory()
        val destinationFile = generateDestinationDirectoryRef()

        executionContext.attemptRename = false

        executeAll(MoveDirectory(source, destinationFile))

        assertThat("source was deleted", source.directory.exists(), equalTo(false))
    }

    @Test
    fun empty_directory_is_deleted_rename() {
        val source = createDirectory()
        val destinationFile = generateDestinationDirectoryRef()

        executionContext.attemptRename = true

        executeAll(MoveDirectory(source, destinationFile))

        assertThat("source was deleted", source.directory.exists(), equalTo(false))
    }

    /** Helper function: executes an [Operation] and all sub-operations */
    private fun executeAll(op: MoveDirectory) {
        val l = ArrayDeque<Operation>()
        l.addFirst(op)
        while (l.any()) {
            val head = l.removeFirst()
            Timber.d("executing $head")
            this.executionContext.execSafe(head) {
                l.addAll(0, head.executeOne())
            }
        }
    }

    /** Creates an empty TMP directory to place the output files in */
    private fun generateDestinationDirectoryRef() = createDirectory().deleteForFileRef()

    private fun createDirectory(): Directory =
        Directory.createInstance(createTransientDirectory())!!

    private fun Operation.executeOne(): List<Operation> = this.execute(executionContext)
}

/** A move operation which fails */
class FailMove : Operation() {
    override fun execute(context: MigrateUserData.MigrationContext): List<Operation> {
        throw TestException("should fail but not crash")
    }
}
