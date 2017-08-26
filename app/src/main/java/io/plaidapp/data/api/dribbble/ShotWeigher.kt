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

package io.plaidapp.data.api.dribbble

import io.plaidapp.data.PlaidItemSorting
import io.plaidapp.data.api.dribbble.model.Shot

/**
 * Utility class for applying weights to a group of [Shot]s for sorting. Weighs shots relative
 * to the most liked shot in the group.
 */
class ShotWeigher : PlaidItemSorting.PlaidItemGroupWeigher<Shot> {

    override fun weigh(items: List<Shot>) {
        val maxLikes = items
                .map { it.likes_count.toFloat() }
                .max()
                ?: 0f

        for (shot in items) {
            val weight = 1f - shot.likes_count.toFloat() / maxLikes
            shot.weight = shot.page + weight
        }
    }

}
