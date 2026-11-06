package com.rc.testershot.service;

import android.annotation.TargetApi;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.rc.testershot.ui.TriggerActivity;

/** Quick Settings tile: one tap = screenshot (the shade collapses via the trampoline activity). */
@TargetApi(24)
public class ShotTileService extends TileService {
    @Override
    public void onStartListening() {
        Tile t = getQsTile();
        if (t != null) {
            t.setState(Tile.STATE_INACTIVE);
            t.updateTile();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onClick() {
        startActivityAndCollapse(TriggerActivity.intent(this, 0));
    }
}
