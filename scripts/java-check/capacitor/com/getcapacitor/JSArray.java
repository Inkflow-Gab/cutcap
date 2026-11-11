package com.getcapacitor;

import org.json.JSONArray;

/** Stub. See ../../README.md. Signatures match Capacitor 8. */
public class JSArray extends JSONArray {
    public JSArray() {
        super();
    }

    public JSArray(String json) throws org.json.JSONException {
        super(json);
    }

    public JSArray add(Object value) {
        super.put(value);
        return this;
    }
}
