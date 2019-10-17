package com.ichi2.utils;


import org.json.JSONException;
import org.json.JSONTokener;

public class JSONTokener_ extends JSONTokener {
    public JSONTokener_(String s) {
        super(s);
    }


    public Object nextValue() throws JSONException {
        char c = this.nextClean();
        this.back();

        switch (c) {
            case '{':
                return new JSONObject_(this);
            case '[':
                return new JSONArray_(this);
            default:
                return super.nextValue();
        }
    }
}
