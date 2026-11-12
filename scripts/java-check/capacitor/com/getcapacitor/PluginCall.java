package com.getcapacitor;

/** Stub. See ../../README.md. Signatures match Capacitor 8. */
public class PluginCall {
    public String getPluginId() {
        return "";
    }

    public String getCallbackId() {
        return "";
    }

    public String getMethodName() {
        return "";
    }

    public JSObject getData() {
        return null;
    }

    public String getString(String name) {
        return null;
    }

    public String getString(String name, String defaultValue) {
        return defaultValue;
    }

    public Integer getInt(String name) {
        return null;
    }

    public Integer getInt(String name, Integer defaultValue) {
        return defaultValue;
    }

    public Long getLong(String name) {
        return null;
    }

    public Long getLong(String name, Long defaultValue) {
        return defaultValue;
    }

    public Float getFloat(String name) {
        return null;
    }

    public Double getDouble(String name) {
        return null;
    }

    public Boolean getBoolean(String name) {
        return null;
    }

    public Boolean getBoolean(String name, Boolean defaultValue) {
        return defaultValue;
    }

    public JSObject getObject(String name) {
        return null;
    }

    public JSObject getObject(String name, JSObject defaultValue) {
        return defaultValue;
    }

    public JSArray getArray(String name) {
        return null;
    }

    public void resolve(JSObject data) {}

    public void resolve() {}

    public void reject(String msg) {}

    public void reject(String msg, String code, Exception ex, JSObject data) {}

    public void successCallback(PluginResult successResult) {}
}
