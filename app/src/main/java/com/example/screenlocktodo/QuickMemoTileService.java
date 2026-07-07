package com.example.screenlocktodo;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class QuickMemoTileService extends TileService {
    @Override
    public void onStartListening() {
        super.onStartListening();
        if (getQsTile() != null) {
            getQsTile().setLabel(getString(R.string.quick_memo_short_label));
            getQsTile().setState(Tile.STATE_ACTIVE);
            getQsTile().updateTile();
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        Intent intent = new Intent(this, QuickMemoActivity.class)
                .setAction(QuickMemoActivity.ACTION_QUICK_MEMO_CANONICAL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this,
                    41,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            startActivityAndCollapse(pendingIntent);
        } else {
            startActivityAndCollapse(intent);
        }
    }
}
