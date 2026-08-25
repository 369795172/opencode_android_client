// Reverse-engineered from the vendor assistserver APK (RokidSpriteAssistServer)
// shipped on Rokid RG glasses. Method order MUST stay stable: AIDL transaction
// codes are positional, and the on-device service dispatches by code.
package com.rokid.os.sprite.tts;

import com.rokid.os.sprite.tts.ITtsListener;

interface ITtsServer {
    // arg0 = text to speak (server no-ops on empty text, verified from
    // TtsServerManager bytecode: TextUtils.isEmpty check on the first String),
    // arg1 = dedup/stop tag echoed back on the listener.
    void playTtsMsg(String text, String tag, in ITtsListener listener);
    void stopTtsPlay(String tag);
    void updateTtsParam(String param);
}
