/****************************************************************************************
 * Copyright (c) 2012 Kostas Spyropoulos <inigo.aldana@gmail.com>                       *
 * Copyright (c) 2014 Houssam Salem <houssam.salem.au@gmail.com>                        *
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

import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;

import com.ichi2.anki.AnkiDroidApp;
import com.ichi2.anki.exception.MediaSyncException;
import com.ichi2.anki.exception.UnknownHttpResponseException;
import com.ichi2.async.Connection;
import com.ichi2.libanki.Collection;
import com.ichi2.libanki.Consts;
import com.ichi2.libanki.Utils;
import com.ichi2.utils.VersionUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;

import timber.log.Timber;

@SuppressWarnings({"PMD.AvoidThrowingRawExceptionTypes","PMD.MethodNamingConventions",
        "deprecation"}) // tracking HTTP transport change in github already
public class RemoteMediaServer extends HttpSyncer {

    private Collection mCol;


    public RemoteMediaServer(Collection col, String hkey, Connection con) {
        super(hkey, con);
        mCol = col;
    }


    @Override
    public String syncURL() {
        // Allow user to specify custom sync server
        SharedPreferences userPreferences = AnkiDroidApp.getSharedPrefs(AnkiDroidApp.getInstance());
        if (userPreferences!= null && userPreferences.getBoolean("useCustomSyncServer", false)) {
            Uri mediaSyncBase = Uri.parse(userPreferences.getString("syncMediaUrl", Consts.SYNC_MEDIA_BASE));
            return mediaSyncBase.toString() + "/";
        }
        // Usual case
        return Consts.SYNC_MEDIA_BASE;
    }


    public JSONObject_ begin() throws UnknownHttpResponseException, MediaSyncException {
        try {
            mPostVars = new HashMap<>();
            mPostVars.put("k", mHKey);
            mPostVars.put("v",
                    String.format(Locale.US, "ankidroid,%s,%s", VersionUtils.getPkgVersionName(), Utils.platDesc()));

            org.apache.http.HttpResponse resp = super.req("begin", super.getInputStream(Utils.jsonToString(new JSONObject_())));
            JSONObject_ jresp = new JSONObject_(super.stream2String(resp.getEntity().getContent()));
            JSONObject_ ret = _dataOnly(jresp, JSONObject_.class);
            mSKey = ret.getString_("sk");
            return ret;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    // args: lastUsn
    public JSONArray_ mediaChanges(int lastUsn) throws UnknownHttpResponseException, MediaSyncException {
        try {
            mPostVars = new HashMap<>();
            mPostVars.put("sk", mSKey);

            org.apache.http.HttpResponse resp = super.req("mediaChanges",
                    super.getInputStream(Utils.jsonToString(new JSONObject_().put_("lastUsn", lastUsn))));
            JSONObject_ jresp = new JSONObject_(super.stream2String(resp.getEntity().getContent()));
            return _dataOnly(jresp, JSONArray_.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * args: files
     * <br>
     * This method returns a ZipFile with the OPEN_DELETE flag, ensuring that the file on disk will
     * be automatically deleted when the stream is closed.
     */
    public ZipFile downloadFiles(List<String> top) throws UnknownHttpResponseException {
        try {
            org.apache.http.HttpResponse resp;
            resp = super.req("downloadFiles",
                    super.getInputStream(Utils.jsonToString(new JSONObject_().put_("files", new JSONArray_(top)))));
            String zipPath = mCol.getPath().replaceFirst("collection\\.anki2$", "tmpSyncFromServer.zip");
            // retrieve contents and save to file on disk:
            super.writeToFile(resp.getEntity().getContent(), zipPath);
            return new ZipFile(new File(zipPath), ZipFile.OPEN_READ | ZipFile.OPEN_DELETE);
        } catch (IOException e) {
            Timber.e(e, "Failed to download requested media files");
            throw new RuntimeException(e);
        }
    }


    public JSONArray_ uploadChanges(File zip) throws UnknownHttpResponseException, MediaSyncException {
        try {
            // no compression, as we compress the zip file instead
            org.apache.http.HttpResponse resp = super.req("uploadChanges", new FileInputStream(zip), 0);
            JSONObject_ jresp = new JSONObject_(super.stream2String(resp.getEntity().getContent()));
            return _dataOnly(jresp, JSONArray_.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    // args: local
    public String mediaSanity(int lcnt) throws UnknownHttpResponseException, MediaSyncException {
        try {
            org.apache.http.HttpResponse resp = super.req("mediaSanity",
                    super.getInputStream(Utils.jsonToString(new JSONObject_().put_("local", lcnt))));
            JSONObject_ jresp = new JSONObject_(super.stream2String(resp.getEntity().getContent()));
            return _dataOnly(jresp, String.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * Returns the "data" element from the JSON response from the server, or throws an exception if there is a value in
     * the "err" element.
     * <p>
     * The python counterpart to this method is flexible with type coercion; the type of object returned is decided by
     * the content of the "data" element, and there are several such types in the various server responses. Java
     * requires us to specifically choose a type to convert to, so we need an additional parameter (returnType) to
     * specify the type we expect.
     * 
     * @param resp The JSON response from the server
     * @param returnType The type to coerce the 'data' element to.
     * @return The "data" element from the HTTP response from the server. The type of object returned is determined by
     *         returnType.
     */
    @SuppressWarnings("unchecked")
    private <T> T _dataOnly(JSONObject_ resp, Class<T> returnType) throws MediaSyncException {
            if (!TextUtils.isEmpty(resp.optString("err"))) {
                String err = resp.getString_("err");
                mCol.log("error returned: " + err);
                throw new MediaSyncException("SyncError:" + err);
            }
            if (returnType == String.class) {
                return (T) resp.getString_("data");
            } else if (returnType == JSONObject_.class) {
                return (T) resp.getJSONObject_("data");
            } else if (returnType == JSONArray_.class) {
                return (T) resp.getJSONArray_("data");
            }
            throw new RuntimeException("Did not specify a valid type for the 'data' element in resopnse");
    }
}
