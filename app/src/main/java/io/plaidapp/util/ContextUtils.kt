package io.plaidapp.util

import android.content.Context
import android.support.annotation.StringRes
import android.widget.Toast

/**
 * Context Extension Functions
 * Created by pluu on 2017-08-26.
 */
fun Context.showToast(@StringRes resId: Int, duration: Int = Toast.LENGTH_LONG) =
        Toast.makeText(this, resId, duration).show()

fun Context.showToast(text: CharSequence, duration: Int = Toast.LENGTH_LONG) =
        Toast.makeText(this, text, duration).show()