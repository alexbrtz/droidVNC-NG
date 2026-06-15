/*
 * DroidVNC-NG broadcast receiver that listens for boot-completed events
 * and starts MainService in turn.
 *
 * Author: Christian Beier <info@christianbeier.net>
 *
 * Copyright (C) 2020 Kitchen Armor.
 *
 * You can redistribute and/or modify this program under the terms of the
 * GNU General Public License version 2 as published by the Free Software
 * Foundation.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place Suite 330, Boston, MA 02111-1307, USA.
 */

package net.christianbeier.droidvnc_ng;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.preference.PreferenceManager;
import android.util.Log;


public class OnBootReceiver extends BroadcastReceiver {

    private static final String TAG = "OnBootReceiver";

    public void onReceive(Context context, Intent arg1) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(arg1.getAction())) return;

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (!prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_START_ON_BOOT, true)) {
            Log.i(TAG, "onReceive: configured NOT to start");
            return;
        }

        Log.i(TAG, "onReceive: starting service");

        // Ensure accessibility service setting persists across reboots
        execRoot("settings put secure enabled_accessibility_services "
                + "net.christianbeier.droidvnc_ng/.InputService");
        execRoot("settings put secure accessibility_enabled 1");

        // MainService handles the rest: skips permission dialogs if root is available
        // and auto-approves the MediaProjection dialog
        Intent intent = new Intent(context, MainService.class);
        intent.setAction(MainService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    private static void execRoot(String cmd) {
        try {
            Runtime.getRuntime().exec(new String[]{"su", "-c", cmd}).waitFor();
        } catch (Exception e) {
            Log.e(TAG, "execRoot: " + e);
        }
    }
}
