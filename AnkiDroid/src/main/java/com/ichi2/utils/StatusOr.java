/****************************************************************************************
 * Copyright (c) 2020 Arthur Milchior <arthur@milchior.fr>                              *
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

package com.ichi2.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Represents either a computation which failed, or the computed value. The value should be non null.
 * @param <U> The value of a succesful computation
 */
public class StatusOr<U> implements Status {
    /**
     * The computed value in case of success. Null in case of failure
     */
    public final @Nullable U value;

    public boolean success() {
        return value != null;
    }
    public static StatusOr FALSE = new StatusOr();

    private StatusOr() {
        value = null;
    }
    public StatusOr(@NonNull U value) {
        this.value = value;
    }
}
