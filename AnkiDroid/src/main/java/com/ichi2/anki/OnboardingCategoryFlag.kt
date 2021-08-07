/****************************************************************************************
 *                                                                                      *
 * Copyright (c) 2021 Shridhar Goel <shridhar.goel@gmail.com>                           *
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

interface OnboardingCategoryFlag {

    /**
     * Constant used to represent preference key for screens.
     * Values once defined should not be changed.
     */
    fun getCategoryName(): String

    companion object {
        /** All enums implementing OnboardingCategoryFlag should call this method */
        fun <T> addCategory(category: Array<T>) where T : Enum<T>, T : OnboardingCategoryFlag {
            category.forEach { OnboardingUtils.Companion.addFeature(it) }
        }
    }
}
