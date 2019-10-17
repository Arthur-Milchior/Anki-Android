package com.ichi2.utils;


import org.json.JSONException;

/**
	Similar to JSONException in meaning, but unchecked */
public class JSONException_ extends RuntimeException {

	private JSONException exc = null;

	public JSONException_(String s) {
		super(s);
	}

	public JSONException_() {
		super();
	}

	public JSONException_(Throwable e) {
		super(e);
	}

	public JSONException_(JSONException e) {
		super(e);
		exc = e;
	}

	public JSONException asException() {
		if (exc!=null) {
			return exc;
		} else {
			return new JSONException(toString());
		}
	}

	public void throwAsException() throws JSONException {
		throw asException();
	}
}
