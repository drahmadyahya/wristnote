package com.ahmad.wristnote

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit

/**
 * Sends a short answer/brief to the watch. Uses a Data Layer item, so it is
 * delivered even if the watch reconnects later. Call off the main thread.
 */
object WatchLink {
    const val PATH = "/nerdly/answer"

    fun send(ctx: Context, title: String, body: String, kind: String) {
        val now = System.currentTimeMillis()
        val req = PutDataMapRequest.create("$PATH/$now").apply {
            dataMap.putString("title", title)
            dataMap.putString("body", body)
            dataMap.putString("kind", kind)
            dataMap.putLong("time", now)
        }.asPutDataRequest().setUrgent()
        try {
            Tasks.await(Wearable.getDataClient(ctx).putDataItem(req), 10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w("Nerdly", "could not reach the watch", e)
        }
    }
}
