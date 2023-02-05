/*
 *  Copyright (c) 2022 Brayan Oliveira <brayandso.dev@gmail.com>
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
package com.ichi2.utils

import java.lang.reflect.Field

inline fun <reified T> getInstanceFromClassName(javaClassName: String): T {
    return Class.forName(javaClassName).newInstance() as T
}

/**
 * @param clazz Java class to get the field
 * @param fieldName name of the field
 * @return a [Field] object with `isAccessible` set to true
 */
fun getJavaFieldAsAccessible(clazz: Class<*>, fieldName: String): Field {
    return clazz.getDeclaredField(fieldName).apply {
        isAccessible = true
    }
}
