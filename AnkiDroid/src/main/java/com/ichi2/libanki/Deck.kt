/****************************************************************************************
 * Copyright (c) 2020 Arthur Milchior <arthur@milchior.fr>                             *
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

package com.ichi2.libanki

import androidx.annotation.VisibleForTesting
import com.ichi2.libanki.utils.getLongOrNull
import com.ichi2.utils.JSONObjectHolder
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONObject.NULL

@JvmInline
value class Deck(
    @VisibleForTesting override val jsonObject: JSONObject,
) : JSONObjectHolder {
    /**
     * Creates a deck object form a json string
     */
    constructor(json: String) : this(JSONObject(json))

    /**
     * Whether this deck is a filtered deck.
     */
    var isFiltered: Boolean
        get() = jsonObject.getInt("dyn") != 0

        @VisibleForTesting
        set(value) {
            jsonObject.put("dyn", if (value) 1 else 0)
        }

    /**
     * Whether this deck is a normal deck. That is, not a filtered deck.
     */
    var isNormal: Boolean
        get() = !isFiltered

        @VisibleForTesting
        set(value) {
            isFiltered = !value
        }

    /**
     * The name of the deck. Mutable. If you want a way to persistently represents this deck, use [id] instead.
     */
    var name: String
        get() = jsonObject.getString("name")
        set(value) {
            jsonObject.put("name", value)
        }

    /**
     * If this deck as subdecks, whether those subdecks should be collapsed in the desktop card browser.
     * Not used in ankidroid at the moment.
     */
    var browserCollapsed: Boolean
        get() = jsonObject.getBoolean("browserCollapsed")
        set(value) {
            jsonObject.put("browserCollapsed", value)
        }

    /**
     * If this deck as subdecks, whether those subdecks should be collapsed in the deck picker.
     */
    var collapsed: Boolean
        get() = jsonObject.getBoolean("collapsed")
        set(value) {
            jsonObject.put("collapsed", value)
        }

    /**
     * The id of the deck. Should be globally unique
     * (created as a timestamp, very small chance of collision between two different decks from different users)
     */
    var id: DeckId
        get() = jsonObject.getLong("id")
        set(value) {
            jsonObject.put("id", value)
        }

    /**
     * The id of the deck option.
     */
    var conf: DeckConfigId
        get() {
            val value = jsonObject.optLong("conf")
            return if (value > 0) value else 1
        }
        set(value) {
            jsonObject.put("conf", value)
        }

    /**
     * A string explaining what can be found in this deck.
     */
    var description: String
        get() = jsonObject.optString("desc")
        set(value) {
            jsonObject.put("desc", value)
        }

    var noteTypeId: NoteTypeId?
        get() = jsonObject.getLongOrNull("mid")
        set(value) {
            jsonObject.put("mid", value)
        }

    var resched: Boolean
        get() = jsonObject.getBoolean("resched")
        set(value) {
            jsonObject.put("resched", value)
        }

    var previewAgainSecs: Int
        get() = jsonObject.getInt("previewAgainSecs")
        set(value) {
            jsonObject.put("previewAgainSecs", value)
        }
    var previewHardSecs: Int
        get() = jsonObject.getInt("previewHardSecs")
        set(value) {
            jsonObject.put("previewHardSecs", value)
        }
    var previewGoodSecs: Int
        get() = jsonObject.getInt("previewGoodSecs")
        set(value) {
            jsonObject.put("previewGoodSecs", value)
        }

    /**
     * An array of string. The i-th string correspond to the number of second/minute/hour or day for the i-th learning steps.
     * See https://docs.ankiweb.net/deck-options.html#learning-steps
     */
    var delays: JSONArray?
        get() = jsonObject.optJSONArray("delays")
        set(value) {
            val value =
                if (value == null) {
                    NULL
                } else {
                    value
                }
            jsonObject.put("delays", value)
        }

    fun removeEmpty() {
        jsonObject.remove("empty")
    }

    /**
     * The options configuring which cards are shown in a filtered deck.
     * See https://docs.ankiweb.net/filtered-decks.html
     */
    @JvmInline
    value class Term(
        val array: JSONArray,
    ) {
        constructor(search: String, limit: Int, order: Int) : this(JSONArray(listOf(search, limit, order))) {}

        /**
         Only cards satisfying this search query are shown.
         */
        var search: String
            get() = array.getString(0)
            set(value) {
                array.put(0, value)
            }

        /**
         * At most this number of cards are shown.
         */
        var limit: Int
            get() = array.getInt(1)
            set(value) {
                array.put(1, value)
            }

        /**
         * The order in which cards are shown. See https://docs.ankiweb.net/filtered-decks.html#order.
         */
        var order: Int
            get() = array.getInt(2)
            set(value) {
                array.put(2, value)
            }

        override fun toString(): String = array.toString()
    }

    /**
     * The options deciding which cards are shown in a filtered deck.
     */
    val firstFilter: Term
        get() = Term(terms.getJSONArray(0))

    /**
     * A second option to add more cards to the filtered deck.
     */
    var secondFilter: Term?
        get() = terms.optJSONArray(1)?.let { Term(it) }
        set(value) {
            if (value == null) {
                terms.remove(1)
            } else {
                terms.put(1, value?.array)
            }
        }

    /**
     * The array of filters. Only for filtered decks.
     */
    @VisibleForTesting
    var terms: JSONArray
        get() = jsonObject.getJSONArray("terms")
        set(value) {
            jsonObject.put("terms", value)
        }
}
