/***************************************************************************************
 * Copyright (c) 2012 Norbert Nagold <norbert.nagold@gmail.com>                         *
 * Copyright (c) 2014 Timothy Rae <perceptualchaos2@gmail.com>                          *
 *                                                                                      *
 * This program is free software; you can redistribute it and/or modify it under        *
 * the terms of the GNU General Public License as published by the Free Software        *
 * Foundation; either version 3 of the License, or (at your option) any later           *
 * version.                                                                             *
 *                                                                                      *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY      *
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A      *
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.             *
 *                                                                                      *
 * You should have received a copy of the GNU General Public License along with         *
 * this program.  If not, see <http://www.gnu.org/licenses/>.                           *
 ****************************************************************************************/

package com.ichi2.libanki.sync;

import com.ichi2.anki.exception.UnknownHttpResponseException;
import com.ichi2.async.Connection;
import com.ichi2.libanki.Consts;
import com.ichi2.libanki.Utils;
import com.ichi2.utils.VersionUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;

@SuppressWarnings({"PMD.AvoidThrowingRawExceptionTypes","PMD.MethodNamingConventions",
        "deprecation"}) // tracking HTTP transport change in github already
public class RemoteServer extends HttpSyncer {

    public RemoteServer(Connection con, String hkey) {
        super(hkey, con);
    }


    /** Returns hkey or none if user/pw incorrect. */
    @Override
    public org.apache.http.HttpResponse hostKey(String user, String pw) throws UnknownHttpResponseException {
        try {
            mPostVars = new HashMap<>();
            JSONObject_ jo = new JSONObject_();
            jo.put("u", user);
            jo.put("p", pw);
            return super.req("hostKey", super.getInputStream(Utils.jsonToString(jo)));
        } catch (JSONException e) {
            return null;
        }
    }


    @Override
    public org.apache.http.HttpResponse meta() throws UnknownHttpResponseException {
            mPostVars = new HashMap<>();
            mPostVars.put("k", mHKey);
            mPostVars.put("s", mSKey);
            JSONObject_ jo = new JSONObject_();
            jo.put_("v", Consts.SYNC_VER);
            jo.put_("cv",
                    String.format(Locale.US, "ankidroid,%s,%s", VersionUtils.getPkgVersionName(), Utils.platDesc()));
            return super.req("meta", super.getInputStream(Utils.jsonToString(jo)));
    }


    @Override
    public JSONObject_ applyChanges(JSONObject_ kw) throws UnknownHttpResponseException {
        return parseDict(_run("applyChanges", kw));
    }


    @Override
    public JSONObject_ start(JSONObject_ kw) throws UnknownHttpResponseException {
        return parseDict(_run("start", kw));
    }


    @Override
    public JSONObject_ chunk() throws UnknownHttpResponseException {
        JSONObject_ co = new JSONObject_();
        return parseDict(_run("chunk", co));
    }


    @Override
    public void applyChunk(JSONObject_ sech) throws UnknownHttpResponseException {
        _run("applyChunk", sech);
    }


    @Override
    public JSONObject_ sanityCheck2(JSONObject_ client) throws UnknownHttpResponseException {
        return parseDict(_run("sanityCheck2", client));
    }

    @Override
    public long finish() throws UnknownHttpResponseException {
        return parseLong(_run("finish", new JSONObject_()));
    }

    @Override
    public void abort() throws UnknownHttpResponseException {
        _run("abort", new JSONObject_());
    }

    /** Python has dynamic type deduction, but we don't, so return String **/
    private String _run(String cmd, JSONObject_ data) throws UnknownHttpResponseException {
        org.apache.http.HttpResponse ret = super.req(cmd, super.getInputStream(Utils.jsonToString(data)));
        try {
            return super.stream2String(ret.getEntity().getContent());
        } catch (IllegalStateException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Note: these conversion helpers aren't needed in libanki as type deduction occurs automatically there **/
    private JSONObject_ parseDict(String s) {
            if (!s.equalsIgnoreCase("null") && s.length() != 0) {
                return new JSONObject_(s);
            } else {
                return new JSONObject_();
            }
    }

    private long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
