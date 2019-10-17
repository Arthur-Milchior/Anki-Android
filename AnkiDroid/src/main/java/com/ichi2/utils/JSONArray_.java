package com.ichi2.utils;

import com.ichi2.libanki.Collection;
import com.ichi2.utils.JSONObject_;


import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Array;

public class JSONArray_ extends JSONArray {
    public JSONArray_() {
        super();
    }

    /*
    public JSONArray_(Collection copyFrom) {
        super(copyFrom);
    }

     */

    public JSONArray_(JSONArray_ copyFrom) {
        for (int i = 0; i < copyFrom.length(); i++) {
            put_(i, copyFrom.get_(i));
        }
    }


    /**
     * This method simply change the type.
     *
     * See the comment of objectToObject_ to read about the problems met here.
     *
     * @param ar Actually a JSONArray_
     * @return the same element as input. But considered as a JSONArray.
     */
    public static JSONArray_ arrayToArray_(JSONArray ar){
        assert(ar instanceof JSONArray_);
        return (JSONArray_) ar;
    }

    public JSONArray_(JSONTokener_ x) {
        this();
        try {
            if (x.nextClean() != '[') {
                throw x.syntaxError("A JSONArray_ text must start with '['");
            }

            char nextChar = x.nextClean();
            if (nextChar == 0) {
                // array is unclosed. No ']' found, instead EOF
                throw x.syntaxError("Expected a ',' or ']'");
            }
            if (nextChar != ']') {
                x.back();
                for (; ; ) {
                    if (x.nextClean() == ',') {
                        x.back();
                        put(JSONObject_.NULL);
                    } else {
                        x.back();
                        put(x.nextValue());
                    }
                    switch (x.nextClean()) {
                        case 0:
                            // array is unclosed. No ']' found, instead EOF
                            throw x.syntaxError("Expected a ',' or ']'");
                        case ',':
                            nextChar = x.nextClean();
                            if (nextChar == 0) {
                                // array is unclosed. No ']' found, instead EOF
                                throw x.syntaxError("Expected a ',' or ']'");
                            }
                            if (nextChar == ']') {
                                return;
                            }
                            x.back();
                            break;
                        case ']':
                            return;
                        default:
                            throw x.syntaxError("Expected a ',' or ']'");
                    }
                }
            }
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    public JSONArray_(String source) {
        this(new JSONTokener_(source));
    }

    public JSONArray_(Object array) {
        this();
        if (array.getClass().isArray()) {
            int length = Array.getLength(array);
            for (int i = 0; i < length; i += 1) {
                this.put(Array.get(array, i));
            }
        } else {
            throw new JSONException_(
                    "JSONArray_ initial value should be a string or collection or array.");
        }
    }

    public JSONArray_ put_(double value) {
        try {
            super.put(value);
            return this;
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    public JSONArray_ put_(int index, boolean value) {
        try {
            super.put(index, value);
            return this;
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    public JSONArray_ put_(int index, double value) {
        try {
            super.put(index, value);
            return this;
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    public JSONArray_ put_(int index, int value) {
        try {
            super.put(index, value);
            return this;
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    public JSONArray_ put_(int index, long value) {
        try {
            super.put(index, value);
            return this;
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    public JSONArray_ put_(int index, Object value) {
        try {
            super.put(index, value);
            return this;
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    public Object get_(int index) {
        try {
            return super.get(index);
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    public boolean getBoolean_(int index) {
        try {
            return super.getBoolean(index);
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    public boolean optBoolean_(int index, boolean fallback) {
        return super.optBoolean(index, fallback);
    }

    public double getDouble_(int index) {
        try {
            return super.getDouble(index);
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    public int getInt_(int index) {
        try {
            return super.getInt(index);
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    public long getLong_(int index) {
        try {
            return super.getLong(index);
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    public String getString_(int index) {
        try {
            return super.getString(index);
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    public JSONArray_ getJSONArray(int index) throws JSONException {
        return arrayToArray_(super.getJSONArray(index));
    }

    public JSONObject_ getJSONObject(int index) throws JSONException {
        return JSONObject_.objectToObject_(super.getJSONObject(index));
    }

    private JSONArray getJSONArrayNoCatch(int index) {
        try {
            return super.getJSONArray(index);
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    private JSONObject getJSONObjectNoCatch(int index) {
        try {
            return super.getJSONObject(index);
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    public JSONArray_ getJSONArray_(int index) {
        return arrayToArray_(getJSONArrayNoCatch(index));
    }

    public JSONObject_ getJSONObject_(int index) {
        return JSONObject_.objectToObject_(getJSONObjectNoCatch(index));
    }

    /*
    public JSONObject getJSONObject(int index) {
        try {
            return super.getJSONObject(index);
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    public JSONObject toJSONObject_(JSONArray_ names) {
        try {
            return super.toJSONObject(names);
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }
    */

    public String join_(String separator) {
        try {
            return super.join(separator);
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }

    public String toString(int indentSpaces) {
        try {
            return super.toString(indentSpaces);
        } catch (JSONException e) {
            throw new JSONException_(e);
        }
    }
}
