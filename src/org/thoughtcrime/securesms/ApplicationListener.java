/*
 * Copyright (C) 2013 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms;

import android.app.Application;

import com.codebutler.android_websockets.WebSocketClient.Listener;
import org.thoughtcrime.securesms.crypto.PRNGFixes;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.websocket.PushService;

/**
 * Will be called once when the TextSecure process is created.
 *
 * We're using this as an insertion point to patch up the Android PRNG disaster.
 *
 * @author Moxie Marlinspike
 */
public class ApplicationListener extends Application {

  @Override
  public void onCreate() {
    PRNGFixes.apply();
    if(TextSecurePreferences.isPushRegistered(getApplicationContext())
       && !TextSecurePreferences.isGcmRegistered(getApplicationContext()))
            startService(PushService.startIntent(this.getApplicationContext()));
  }

}
