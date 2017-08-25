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

package io.plaidapp.data.api.designernews.model

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize
import java.util.*

/**
 * Models a comment on a designer news story.
 */
@Parcelize
class Comment(val id: Long,
              val body: String?,
              val body_html: String?,
              val created_at: Date?,
              val depth: Int,
              var vote_count: Int = 0,
              val user_id: Long,
              val user_display_name: String?,
              val user_portrait_url: String?,
              val user_job: String?,
              val comments: List<Comment>?) : Parcelable {

    // TODO move this to a decorator
    var upvoted: Boolean? = null

    class Builder {
        private var id: Long = 0
        private var body: String? = null
        private var body_html: String? = null
        private var created_at: Date? = null
        private var depth: Int = 0
        private var vote_count: Int = 0
        private var user_id: Long = 0
        private var user_display_name: String? = null
        private var user_portrait_url: String? = null
        private var user_job: String? = null

        fun setId(id: Long): Builder =
                apply { this.id = id }

        fun setBody(body: String): Builder =
                apply { this.body = body }

        fun setBodyHtml(body_html: String): Builder =
                apply { this.body_html = body_html }

        fun setCreatedAt(created_at: Date): Builder =
                apply { this.created_at = created_at }

        fun setDepth(depth: Int): Builder =
                apply { this.depth = depth }

        fun setVoteCount(vote_count: Int): Builder =
                apply { this.vote_count = vote_count }

        fun setUserId(user_id: Long): Builder =
                apply { this.user_id = user_id }

        fun setUserDisplayName(user_display_name: String): Builder =
                apply { this.user_display_name = user_display_name }

        fun setUserPortraitUrl(user_portrait_url: String): Builder =
                apply { this.user_portrait_url = user_portrait_url }

        fun setUserJob(user_job: String): Builder =
                apply { this.user_job = user_job }

        fun build(): Comment {
            return Comment(id, body, body_html, created_at, depth, vote_count, user_id,
                    user_display_name, user_portrait_url, user_job, null)
        }
    }

}
