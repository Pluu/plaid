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

package io.plaidapp.data.api.designernews

import io.plaidapp.data.PlaidItemSorting
import io.plaidapp.data.api.designernews.model.Story

/**
 * Utility class for applying weights to a group of [Story]s for sorting. Weighs stories
 * relative to the most upvoted & commented stories in the group.
 */
class StoryWeigher : PlaidItemSorting.PlaidItemGroupWeigher<Story> {
    override fun weigh(items: List<Story>) {
        var maxVotes = 0f
        var maxComments = 0f
        for (story in items) {
            maxVotes = Math.max(maxVotes, story.vote_count.toFloat())
            maxComments = Math.max(maxComments, story.comment_count.toFloat())
        }
        for (story in items) {
            val weight = 1f - (story.comment_count.toFloat() / maxComments + story.vote_count.toFloat() / maxVotes) / 2f
            story.weight = story.page + weight
        }
    }

}
