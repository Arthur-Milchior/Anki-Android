package com.ichi2.utils;

/**
 * Each method similar to the methods in JSONObjects. Name changed to add a _,
 * and it throws JSONException_ instead of JSONException.
 * Furthermore, it returns JSONObject_ and JSONArray_
 *
 */

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Map;

public class JSONObject_ extends JSONObject {

    public static final Object NULL = JSONObject.NULL;

    public JSONObject_() {
        super();
    }

    /** similar to JSONObject_, but throw an exception potentially */
    public JSONObject_(String s, boolean throwing) throws JSONException {
        super(s);
    }

    public JSONObject_(Map copyFrom) {
        super(copyFrom);
    }

    // original code from https://github.com/stleary/JSON-java/blob/master/JSONObject_.java
    // super() must be first instruction, thus it can't be in a try, and the exception can't be catched
    public JSONObject_(JSONTokener_ x) {
        this();
        try {
            char c;
            String key;

            if (x.nextClean() != '{') {
                throw x.syntaxError("A JSONObject_ text must begin with '{'");
            }
            for (; ; ) {
                c = x.nextClean();
                switch (c) {
                    case 0:
                        throw x.syntaxError("A JSONObject_ text must end with '}'");
                    case '}':
                        return;
                    default:
                        x.back();
                        key = x.nextValue().toString();
                }

                // The key is followed by ':'.

                c = x.nextClean();
                if (c != ':') {
                    throw x.syntaxError("Expected a ':' after a key");
                }

                // Use syntaxError(..) to include error location

                if (key != null) {
                    // Check if key exists
                    if (this.opt(key) != null) {
                        // key already exists
                        throw x.syntaxError("Duplicate key \"" + key + "\"");
                    }
                    // Only add value if non-null
                    Object value = x.nextValue();
                    if (value != null) {
                        this.put(key, value);
                    }
                }

                // Pairs are separated by ','.

                switch (x.nextClean()) {
                    case ';':
                    case ',':
                        if (x.nextClean() == '}') {
                            return;
                        }
                        x.back();
                        break;
                    case '}':
                        return;
                    default:
                        throw x.syntaxError("Expected a ',' or '}'");
                }
            }
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    public JSONObject_(String source) {
        this(new JSONTokener_(source));
    }

    public JSONObject_(JSONObject copyFrom) {
        this();
        Iterator<String> it = copyFrom.keys();
        try {
        while (it.hasNext()) {
            String key = it.next();
                put(key, copyFrom.get(key));
        }
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }


    /**
     * Change type from JSONObject to JSONObject_.
     *
     * Assuming the whole code use only JSONObject_, JSONArray_ and JSONTokener_,
     * there should be no instance of JSONObject or JSONArray which is not a JSONObject_ or JSONArray_.
     *
     * In theory, it would be easy to create a JSONObject_ similar to a JSONObject. It would suffices to iterate over key and add them here. But this would create two distinct objects, and update here would not be reflected in the initial object. So we must avoid this method.
     * Since the actual map in JSONObject is private, the child class can't edit it directly and can't access it. Which means that there is no easy way to create a JSONObject_ with the same underlying map. Unless the JSONObject was saved in a variable here. Which would entirely defeat the purpose of inheritence.
     *
     * @param obj A json object
     * @return Exactly the same object, with a different type.
     */
    public static JSONObject_ objectToObject_(JSONObject obj){
        assert(obj instanceof JSONObject_);
        return (JSONObject_) obj;
    }


    /*
    public JSONObject_(JSONObject_ copyFrom, String[] names) {
        this(names.length);
        for (int i = 0; i < names.length; i += 1) {
            try {
                this.putOnce(names[i], jo.opt(names[i]));
            } catch (Exception ignore) {
            }
        }
    }*/

    public JSONObject_ put_(String name, boolean value) {
        try {
            super.put(name, value);
            return this;
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    public JSONObject_ put_(String name, double value) {
        try {
            super.put(name, value);
            return this;
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    public JSONObject_ put_(String name, int value) {
        try {
            super.put(name, value);
            return this;
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    public JSONObject_ put_(String name, long value) {
        try {
            super.put(name, value);
            return this;
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    public JSONObject_ put_(String name, Object value) {
        try {
            super.put(name, value);
            return this;
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    public JSONObject_ putOpt_(String name, Object value) {
        try {
            super.putOpt(name, value);
            return this;
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    public JSONObject_ accumulate_(String name, Object value) {
        try {
            super.accumulate(name, value);
            return this;
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    public Object get_(String name) {
        try {
            return super.get(name);
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    public boolean getBoolean_(String name) {
        try {
            return super.getBoolean(name);
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    public double getDouble_(String name) {
        try {
            return super.getDouble(name);
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    public int getInt_(String name) {
        try {
            return super.getInt(name);
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    public long getLong_(String name) {
        try {
            return super.getLong(name);
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    public String getString_(String name) {
        try {
            return super.getString(name);
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }


    private JSONArray getJSONArrayNoCatch(String name) {
        try {
            return super.getJSONArray(name);
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    private JSONObject getJSONObjectNoCatch(String name) {
        try {
            return super.getJSONObject(name);
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    public JSONArray_ getJSONArray_(String name) {
        return JSONArray_.arrayToArray_(getJSONArrayNoCatch(name));
    }

    public JSONArray_ getJSONArray(String name) throws JSONException {
        return JSONArray_.arrayToArray_(super.getJSONArray(name));
    }

    public JSONObject_ getJSONObject_(String name) {
        return objectToObject_(getJSONObjectNoCatch(name));
    }

    public JSONObject_ getJSONObject(String name) throws JSONException {
        return objectToObject_(super.getJSONObject(name));
    }

    public JSONArray_ toJSONArray_(JSONArray_ names) {
        try {
            return JSONArray_.arrayToArray_(super.toJSONArray(names));
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    public static String numberToString(Number number) {
        return JSONObject.numberToString(number);
    }

    public JSONArray_ names() {
        return JSONArray_.arrayToArray_(super.names());
    }

    public JSONArray_ optJSONArray_(String name) {
        return JSONArray_.arrayToArray_(optJSONArray(name));
    }

    public JSONObject_ optJSONObject_(String name) {
        return JSONObject_.objectToObject_(optJSONObject(name));
    }
}
