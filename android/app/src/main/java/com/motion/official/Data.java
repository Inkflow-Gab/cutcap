package com.motion.official;

import android.util.Base64;

/** Base64 for the bridge. Android's own decoder wins over a hand-rolled loop. */
final class Data {

    private Data() {}

    static String encode(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    /** Accepts both alphabets so a payload from any platform side decodes. */
    static byte[] decode(String text) {
        if (text == null) return new byte[0];
        return Base64.decode(text, Base64.DEFAULT);
    }
}
