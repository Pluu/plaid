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

package io.plaidapp.data.api.producthunt

import io.plaidapp.data.PlaidItemSorting
import io.plaidapp.data.api.producthunt.model.Post

/**
 * Utility class for applying weights to a group of [Post]s for sorting. Weighs posts relative
 * to the most upvoted & commented hunts in the group.
 */
class PostWeigher : PlaidItemSorting.PlaidItemGroupWeigher<Post> {

    override fun weigh(items: List<Post>) {
        var maxVotes = 0f
        var maxComments = 0f
        for (post in items) {
            maxVotes = Math.max(maxVotes, post.votes_count.toFloat())
            maxComments = Math.max(maxComments, post.comments_count.toFloat())
        }
        for (post in items) {
            val weight = 1f - (post.comments_count.toFloat() / maxComments + post.votes_count.toFloat() / maxVotes) / 2f
            post.weight = post.page + weight
        }
    }

}
