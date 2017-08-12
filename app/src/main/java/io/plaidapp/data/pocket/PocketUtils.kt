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

@file:JvmName("PocketUtils")

package io.plaidapp.data.pocket

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Adapted from https://github.com/Pocket/Pocket-AndroidWear-SDK/blob/master/library/src/com
 * /pocket/util/PocketUtil.java
 */

private val PACKAGE = "com.ideashower.readitlater.pro"
private val MIME_TYPE = "text/plain"
private val EXTRA_SOURCE_PACKAGE = "source"
private val EXTRA_TWEET_STATUS_ID = "tweetStatusId"

@JvmOverloads
fun addToPocket(context: Context,
                url: String,
                tweetStatusId: String? = null) {
    context.startActivity(Intent(Intent.ACTION_SEND).apply {
        `package` = PACKAGE
        type = MIME_TYPE
        putExtra(Intent.EXTRA_TEXT, url)
        if (!tweetStatusId.isNullOrEmpty()) {
            putExtra(EXTRA_TWEET_STATUS_ID, tweetStatusId)
        }
        putExtra(EXTRA_SOURCE_PACKAGE, context.packageName)
    })
}

fun isPocketInstalled(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo(PACKAGE, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } != null
