package com.getcapacitor;

import org.json.JSONObject;

/** Stub. See ../../README.md. Signatures match Capacitor 8. */
public class JSObject extends JSONObject {
    public JSObject() {
        super();
    }

    public JSObject(String json) throws org.json.JSONException {
        super(json);
    }

    public JSObject put(String key, boolean value) {
        try {
            super.put(key, value);
        } catch (org.json.JSONException ignored) {
        }
        return this;
    }

    public JSObject put(String key, int value) {
        try {
            super.put(key, value);
        } catch (org.json.JSONException ignored) {
        }
        return this;
    }

    public JSObject put(String key, long value) {
        try {
            super.put(key, value);
        } catch (org.json.JSONException ignored) {
        }
        return this;
    }

    public JSObject put(String key, double value) {
        try {
            super.put(key, value);
        } catch (org.json.JSONException ignored) {
        }
        return this;
    }

    public JSObject put(String key, Object value) {
        try {
            super.put(key, value);
        } catch (org.json.JSONException ignored) {
        }
        return this;
    }

    public JSObject put(String key, String value) {
        try {
            super.put(key, value);
        } catch (org.json.JSONException ignored) {
        }
        return this;
    }
}
