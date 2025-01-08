@file:Suppress("DEPRECATION") // #3625 android.preference deprecation

/****************************************************************************************
 * Copyright (c) 2009 Casey Link <unnamedrambler@gmail.com>                             *
 * Copyright (c) 2012 Norbert Nagold <norbert.nagold@gmail.com>                         *
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

package com.ichi2.anki

import android.content.SharedPreferences
import android.os.Bundle
import android.preference.CheckBoxPreference
import android.preference.EditTextPreference
import android.preference.ListPreference
import android.preference.Preference
import android.preference.PreferenceCategory
import androidx.core.content.edit
import com.ichi2.anki.analytics.UsageAnalytics
import com.ichi2.annotations.NeedsTest
import com.ichi2.libanki.Collection
import com.ichi2.libanki.Deck.Term
import com.ichi2.preferences.StepsPreference.Companion.convertFromJSON
import com.ichi2.preferences.StepsPreference.Companion.convertToJSON
import com.ichi2.themes.Themes
import com.ichi2.ui.AppCompatPreferenceActivity
import com.ichi2.utils.stringIterable
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber

@NeedsTest("construction + onCreate - do this after converting to fragment-based preferences.")
class FilteredDeckOptions :
    AppCompatPreferenceActivity<FilteredDeckOptions.DeckPreferenceHack>(),
    SharedPreferences.OnSharedPreferenceChangeListener {
    private var allowCommit = true

    // TODO: not anymore used in libanki?
    private val dynExamples =
        arrayOf(
            null,
            "{'search'=\"is:new\", 'resched'=False, 'steps'=\"1\", 'order'=5}",
            "{'search'=\"added:1\", 'resched'=False, 'steps'=\"1\", 'order'=5}",
            "{'search'=\"rated:1:1\", 'order'=4}",
            "{'search'=\"prop:due<=2\", 'order'=6}",
            "{'search'=\"is:due tag:TAG\", 'order'=6}",
            "{'search'=\"is:due\", 'order'=3}",
            "{'search'=\"\", 'steps'=\"1 10 20\", 'order'=0}",
        )

    inner class DeckPreferenceHack : AppCompatPreferenceActivity<DeckPreferenceHack>.AbstractPreferenceHack() {
        val hasSecondFilter: Boolean
            get() = deck.secondFilter != null

        override fun cacheValues() {
            Timber.d("cacheValues()")
            deck.firstFilter.apply {
                values["search"] = search
                values["limit"] = limit.toString()
                values["order"] = order.toString()
            }
            deck.secondFilter?.apply {
                values["search_2"] = search
                values["limit_2"] = limit.toString()
                values["order_2"] = order.toString()
            }
            val delays = deck.delays
            if (delays != null) {
                values["steps"] = convertFromJSON(delays)
                values["stepsOn"] = java.lang.Boolean.toString(true)
            } else {
                values["steps"] = "1 10"
                values["stepsOn"] = java.lang.Boolean.toString(false)
            }
            values["resched"] = java.lang.Boolean.toString(deck.resched)
            values["previewAgainSecs"] = deck.previewAgainSecs.toString()
            values["previewHardSecs"] = deck.previewHardSecs.toString()
            values["previewGoodSecs"] = deck.previewGoodSecs.toString()
        }

        inner class Editor : AppCompatPreferenceActivity<DeckPreferenceHack>.AbstractPreferenceHack.Editor() {
            override fun commit(): Boolean {
                Timber.d("commit() changes back to database")
                for ((key, value) in update.valueSet()) {
                    Timber.i("Change value for key '%s': %s", key, value)
                    deck.secondFilter?.apply {
                        when (key) {
                            "search_2" -> {
                                search = value as String
                            }
                            "limit_2" -> {
                                limit = (value as String).toInt()
                            }
                            "order_2" -> {
                                order = (value as String).toInt()
                            }
                        }
                    }
                    when (key) {
                        "search" -> {
                            deck.firstFilter.search = value as String
                        }

                        "limit" -> {
                            deck.firstFilter.limit = (value as String).toInt()
                        }
                        "order" -> {
                            deck.firstFilter.order = (value as String).toInt()
                        }
                        "resched" -> {
                            deck.resched = value as Boolean
                        }
                        "previewAgainSecs" -> {
                            deck.previewAgainSecs = value as Int
                        }
                        "previewHardSecs" -> {
                            deck.previewHardSecs = value as Int
                        }
                        "previewGoodSecs" -> {
                            deck.previewGoodSecs = value as Int
                        }
                        "stepsOn" -> {
                            val on = value as Boolean
                            if (on) {
                                val steps = convertToJSON(values["steps"]!!)
                                if (steps!!.length() > 0) {
                                    deck.delays = steps
                                }
                            } else {
                                deck.delays = null
                            }
                        }
                        "steps" -> {
                            deck.delays = convertToJSON((value as String))
                        }
                        "preset" -> {
                            val i: Int = (value as String).toInt()
                            if (i > 0) {
                                val presetValues = JSONObject(dynExamples[i]!!)
                                val arr = presetValues.names() ?: continue
                                for (name in arr.stringIterable()) {
                                    if ("steps" == name) {
                                        update.put("stepsOn", true)
                                    }
                                    if ("resched" == name) {
                                        update.put(name, presetValues.getBoolean(name))
                                        values[name] = java.lang.Boolean.toString(presetValues.getBoolean(name))
                                    } else {
                                        update.put(name, presetValues.getString(name))
                                        values[name] = presetValues.getString(name)
                                    }
                                }
                                update.put("preset", "0")
                                commit()
                            }
                        }
                    }
                }

                // save deck
                try {
                    col.decks.save(deck)
                } catch (e: RuntimeException) {
                    Timber.e(e, "RuntimeException on saving deck")
                    CrashReportService.sendExceptionReport(e, "FilteredDeckOptionsSaveDeck")
                    setResult(DeckPicker.RESULT_DB_ERROR)
                    finish()
                }

                // make sure we refresh the parent cached values
                cacheValues()
                updateSummaries()

                // and update any listeners
                for (listener in listeners) {
                    listener.onSharedPreferenceChanged(this@DeckPreferenceHack, null)
                }
                return true
            }
        }

        override fun edit(): Editor = Editor()

        init {
            cacheValues()
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.setTheme(this)
        Themes.setLegacyActionBar(this)
        super.onCreate(savedInstanceState)
        UsageAnalytics.sendAnalyticsScreenView(this)
        if (!isColInitialized()) {
            return
        }
        val extras = intent.extras
        deck = if (extras?.containsKey("did") == true) {
            col.decks.get(extras.getLong("did"))
        } else {
            null
        } ?: col.decks.current()
        registerExternalStorageListener()
        if (deck.isNormal) {
            Timber.w("Deck is not a dyn deck")
            finish()
            return
        } else {
            pref = DeckPreferenceHack()
            pref.registerOnSharedPreferenceChangeListener(this)
            extras?.getString("search")?.let { search ->
                pref.edit {
                    putString("search", search)
                }
            }
            addPreferences(col)
            buildLists()
            updateSummaries()
        }

        // Set the activity title to include the name of the deck
        var title = resources.getString(R.string.deckpreferences_title)
        if (title.contains("XXX")) {
            title =
                try {
                    title.replace("XXX", deck.name)
                } catch (e: JSONException) {
                    Timber.w(e)
                    title.replace("XXX", "???")
                }
        }
        this.title = title

        // Add a home button to the actionbar
        supportActionBar?.setHomeButtonEnabled(true)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    @Suppress("deprecation") // Tracked as #5019 on github: addPreferencesFromResource
    private fun addPreferences(col: Collection) {
        addPreferencesFromResource(R.xml.cram_deck_options)
        if (col.schedVer() != 1) {
            Timber.d("sched v2: removing filtered deck custom study steps")
            // getPreferenceScreen.removePreference didn't return true, so remove from the category
            setupSecondFilterListener()
            setupPreviewDelaysListener()
            val category = findPreference("studyOptions") as PreferenceCategory
            removePreference(category, "stepsOn")
            removePreference(category, "steps")
        }
    }

    @Suppress("deprecation") // Tracked as #5019 on github: findPreference
    private fun removePreference(category: PreferenceCategory?, key: String) {
        val preference = findPreference(key)
        if (category == null || preference == null) {
            Timber.w("Failed to remove preference '%s'", key)
            return
        }
        val result = category.removePreference(preference)
        if (!result) {
            Timber.w("Failed to remove preference '%s'", key)
        }
    }

    override fun closeWithResult() {
        if (prefChanged) {
            // Rebuild the filtered deck if a setting has changed
            try {
                col.sched.rebuildDyn(deck.id)
            } catch (e: JSONException) {
                Timber.e(e)
            }
        }
        finish()
    }

    @Suppress("deprecation") // conversion to fragments tracked in github as #5019
    override fun updateSummaries() {
        allowCommit = false
        // for all text preferences, set summary as current database value
        val keys: Set<String> = pref.values.keys
        for (key in keys) {
            val pref = findPreference(key)
            val value: String? =
                if (pref == null) {
                    continue
                } else if (pref is CheckBoxPreference) {
                    continue
                } else if (pref is ListPreference) {
                    val entry = pref.entry
                    entry?.toString() ?: ""
                } else {
                    this.pref.getString(key, "")
                }
            // update value for EditTexts
            if (pref is EditTextPreference) {
                pref.text = value
            }
            // update summary
            if (!this.pref.summaries.containsKey(key)) {
                val s = pref.summary
                this.pref.summaries[key] = if (s != null) pref.summary.toString() else null
            }
            val summ = this.pref.summaries[key]
            if (summ != null && summ.contains("XXX")) {
                pref.summary = summ.replace("XXX", value!!)
            } else {
                pref.summary = value
            }
        }
        allowCommit = true
    }

    @Suppress("deprecation") // Tracked as #5019 on github
    private fun buildLists() {
        val newOrderPref = findPreference("order") as ListPreference
        val newOrderPrefSecond = findPreference("order_2") as ListPreference
        newOrderPref.setEntries(R.array.cram_deck_conf_order_labels)
        newOrderPref.setEntryValues(R.array.cram_deck_conf_order_values)
        newOrderPref.value = pref.getString("order", "0")
        newOrderPrefSecond.setEntries(R.array.cram_deck_conf_order_labels)
        newOrderPrefSecond.setEntryValues(R.array.cram_deck_conf_order_values)
        if (pref.hasSecondFilter) {
            newOrderPrefSecond.value = pref.getString("order_2", "5")
        }
    }

    @Suppress("deprecation")
    private fun setupSecondFilterListener() {
        val secondFilterSign = findPreference("filterSecond") as CheckBoxPreference
        val secondFilter = findPreference("secondFilter") as PreferenceCategory
        if (pref.hasSecondFilter) {
            secondFilter.isEnabled = true
            secondFilterSign.isChecked = true
        }
        secondFilterSign.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any? ->
                if (newValue !is Boolean) {
                    return@OnPreferenceChangeListener true
                }
                if (!newValue) {
                    deck.secondFilter = null
                    secondFilter.isEnabled = false
                } else {
                    secondFilter.isEnabled = true
                    /**Link to the defaults used in AnkiDesktop
                     * <https://github.com/ankitects/anki/blob/1b15069b248a8f86f9bd4b3c66a9bfeab8dfb2b8/qt/aqt/filtered_deck.py#L148-L149>
                     */
                    deck.secondFilter = Term("", 20, 5)
                    val newOrderPrefSecond = findPreference("order_2") as ListPreference
                    newOrderPrefSecond.value = "5"
                }
                true
            }
    }

    @Suppress("deprecation")
    private fun setupPreviewDelaysListener() {
        val reschedPref = findPreference(getString(R.string.filtered_deck_resched_key)) as CheckBoxPreference
        val delaysPrefCategory = findPreference(getString(R.string.filtered_deck_previewDelays_key)) as PreferenceCategory
        delaysPrefCategory.isEnabled = !reschedPref.isChecked
        reschedPref.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any? ->
                if (newValue !is Boolean) {
                    return@OnPreferenceChangeListener true
                }
                delaysPrefCategory.isEnabled = !newValue
                true
            }
    }
}
