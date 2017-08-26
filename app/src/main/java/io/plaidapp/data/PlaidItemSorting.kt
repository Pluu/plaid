/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.plaidapp.data

import java.util.*

/**
 * Classes related to sorting [PlaidItem]s.
 */
class PlaidItemSorting {

    /**
     * A comparator that compares [PlaidItem]s based on their `weight` attribute.
     */
    class PlaidItemComparator : Comparator<PlaidItem> {

        override fun compare(lhs: PlaidItem, rhs: PlaidItem): Int {
            return java.lang.Float.compare(lhs.weight, rhs.weight)
        }
    }

    /**
     * Interface for weighing a group of [PlaidItem]s
     */
    interface PlaidItemGroupWeigher<T : PlaidItem> {
        fun weigh(items: List<T>)
    }

    /**
     * Applies a weight to a group of [PlaidItem]s according to their natural order.
     */
    class NaturalOrderWeigher : PlaidItemGroupWeigher<PlaidItem> {

        override fun weigh(items: List<PlaidItem>) {
            val step = 1f / items.size.toFloat()
            for (i in items.indices) {
                val item = items[i]
                item.weight = item.page + i.toFloat() * step
            }
        }
    }
}
