package com.dev.Tvivo

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** The provisional product name has one resource source. */
object AppIdentity {
    fun name(context: Context): String = context.getString(R.string.app_name)

    @Composable
    fun name(): String = stringResource(R.string.app_name)

    fun personalLibrary(context: Context): String = "My ${name(context)}"

    @Composable
    fun personalLibrary(): String = "My ${name()}"
}
