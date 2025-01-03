/*
 Copyright (c) 2020 David Allison <davidallisongithub@gmail.com>

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU General Public License as published by the Free Software
 Foundation; either version 3 of the License, or (at your option) any later
 version.

 This program is distributed in the hope that it will be useful, but WITHOUT ANY
 WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 PARTICULAR PURPOSE. See the GNU General Public License for more details.

 You should have received a copy of the GNU General Public License along with
 this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.ichi2.anki

import androidx.annotation.CheckResult
import com.ichi2.libanki.SoundOrVideoTag
import com.ichi2.libanki.TemplateManager.TemplateRenderContext.TemplateRenderOutput
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.Test

class CardBrowserNonAndroidTest {
    @Test
    fun soundIsStrippedCorrectly() {
        val output = formatWithFilenamesStripped("aou[anki:play:a:0]aou")
        assertThat(output, equalTo("aou aou"))
    }

    @Test
    fun soundIsRetainedWithoutTag() {
        val output = formatWithFilenamesRetained("aou[anki:play:a:0]aou")
        assertThat(output, equalTo("aou foo.mp3 aou"))
    }

    @Test
    fun imageIsStrippedCorrectly() {
        val output = formatWithFilenamesStripped("""aou<img src="test.jpg">aou""")
        assertThat(output, equalTo("aou aou"))
    }

    @Test
    fun imageIsRetainedWithNoHtml() {
        val output = formatWithFilenamesRetained("""aou<img src="test.jpg">aou""")
        assertThat(output, equalTo("aou test.jpg aou"))
    }

    @CheckResult
    private fun formatWithFilenamesRetained(input: String): String =
        CardBrowser.formatQAInternal(
            input,
            TemplateRenderOutput(
                questionText = input,
                answerText = input,
                questionAvTags = listOf(SoundOrVideoTag("foo.mp3")),
                answerAvTags = listOf(SoundOrVideoTag("foo.mp3")),
                css = "",
            ),
            true,
        )

    @CheckResult
    private fun formatWithFilenamesStripped(input: String): String =
        CardBrowser.formatQAInternal(
            input,
            TemplateRenderOutput(
                questionText = input,
                answerText = input,
                questionAvTags = listOf(SoundOrVideoTag("foo.mp3")),
                answerAvTags = listOf(SoundOrVideoTag("foo.mp3")),
                css = "",
            ),
            false,
        )
}
