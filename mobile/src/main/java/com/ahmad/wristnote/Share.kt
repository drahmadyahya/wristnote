package com.ahmad.wristnote

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Sends note text to Google Keep / Samsung Notes via their share screen. */
object Share {
    const val KEEP = "com.google.android.keep"
    const val SAMSUNG_NOTES = "com.samsung.android.app.notes"

    fun installed(ctx: Context, pkg: String) = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    fun intent(text: String, pkg: String?): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (pkg != null) setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun targets(ctx: Context): List<Pair<String, String>> = listOfNotNull(
        if (installed(ctx, SAMSUNG_NOTES)) "Samsung Notes" to SAMSUNG_NOTES else null,
        if (installed(ctx, KEEP)) "Keep" to KEEP else null,
    )
}
