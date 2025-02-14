/*
 *  Copyright (c) 2021 David Allison <davidallisongithub@gmail.com>
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

package com.ichi2.libanki

import android.os.Bundle
import android.os.Parcelable
import anki.backend.GeneratedBackend
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/*
 * We can't use private typealiases until
 * https://youtrack.jetbrains.com/issue/KT-24700 is fixed
 */

fun GeneratedBackend.getDeckLegacy(did: DeckId) = getDeckLegacy(did.id)

fun GeneratedBackend.getDeckConfigsForUpdate(did: DeckId) = getDeckConfigsForUpdate(did.id)

fun GeneratedBackend.setCurrentDeck(did: DeckId) = setCurrentDeck(did.id)

fun GeneratedBackend.customStudyDefaults(did: DeckId) = customStudyDefaults(did.id)

fun Bundle.getDid() = DeckId(getLong("did"))

@JvmInline
@Parcelize
@Serializable(with = DeckIdAsLongSerializer::class)
value class DeckId(
    val id: Long,
) : Parcelable {
    companion object {
        val ZERO = DeckId(0L)
        val ALL_DECKS_ID = ZERO
        val DEFAULT_DECK_ID = DeckId(1L)
        val NOT_FOUND_DECK_ID = DeckId(-1L)
    }

    fun isZero() = id == 0L
}

object DeckIdAsLongSerializer : KSerializer<DeckId> {
    // Serial names of descriptors should be unique, this is why we advise including app package in the name.
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("my.app.DeckId", PrimitiveKind.LONG)

    override fun serialize(
        encoder: Encoder,
        value: DeckId,
    ) {
        encoder.encodeLong(value.id)
    }

    override fun deserialize(decoder: Decoder): DeckId = DeckId(decoder.decodeLong())
}

internal typealias CardId = Long
internal typealias DeckConfigId = Long
internal typealias NoteId = Long
internal typealias NoteTypeId = Long

/**
 * The number of non-leap seconds which have elapsed since the
 * [Unix Epoch](https://en.wikipedia.org/wiki/Unix_time) (00:00:00 UTC on 1 January 1970)
 *
 * See: [https://www.epochconverter.com/](https://www.epochconverter.com/)
 *
 * example: 6 February 2024 19:15:49 -> `1707246949`
 */
typealias EpochSeconds = Long
