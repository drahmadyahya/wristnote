package com.ahmad.wristnote

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: pull down the shade from anywhere and start recording. */
class RecordTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; label = getString(R.string.record_short); updateTile() }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val intent = VoiceActivity.intent(this)
        val go = {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                @Suppress("DEPRECATION") startActivityAndCollapse(intent)
            }
        }
        if (isLocked) unlockAndRun { go() } else go()
    }
}
