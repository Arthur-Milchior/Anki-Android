/***************************************************************************************
 * Copyright (c) 2011 Norbert Nagold <norbert.nagold@gmail.com>                         *
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

import android.database.Cursor;
import android.database.SQLException;


import com.ichi2.anki.AnkiDroidApp;
import com.ichi2.anki.R;
import com.ichi2.anki.exception.UnknownHttpResponseException;
import com.ichi2.async.Connection;
import com.ichi2.libanki.Collection;
import com.ichi2.libanki.Consts;
import com.ichi2.libanki.Utils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import timber.log.Timber;

@SuppressWarnings({"deprecation", // tracking HTTP transport change in github already
                    "PMD.ExcessiveClassLength","PMD.AvoidThrowingRawExceptionTypes","PMD.AvoidReassigningParameters",
                    "PMD.NPathComplexity","PMD.MethodNamingConventions","PMD.ExcessiveMethodLength",
                    "PMD.SwitchStmtsShouldHaveDefault","PMD.EmptyIfStmt","PMD.SingularField"})
public class Syncer {
    // Mapping of column type names to Cursor types for API < 11
    public static final int TYPE_NULL = 0;
    public static final int TYPE_INTEGER = 1;
    public static final int TYPE_FLOAT = 2;
    public static final int TYPE_STRING = 3;
    public static final int TYPE_BLOB = 4;

    private Collection mCol;
    private HttpSyncer mServer;
    private long mRMod;
    //private long mRScm;
    private int mMaxUsn;
    private long mLMod;
    //private long mLScm;
    private int mMinUsn;
    private boolean mLNewer;
    private JSONObject_ mRChg;
    private String mSyncMsg;

    private LinkedList<String> mTablesLeft;
    private Cursor mCursor;


    public Syncer(Collection col, HttpSyncer server) {
        mCol = col;
        mServer = server;
    }


    /** Returns 'noChanges', 'fullSync', 'success', etc */
    public Object[] sync() throws UnknownHttpResponseException {
        return sync(null);
    }


    @SuppressWarnings("deprecation") // tracking HTTP transport change in github already
    public Object[] sync(Connection con) throws UnknownHttpResponseException {
        mSyncMsg = "";
        // if the deck has any pending changes, flush them first and bump mod time
        mCol.save();
        // step 1: login & metadata
        org.apache.http.HttpResponse ret = mServer.meta();
        if (ret == null) {
            return null;
        }
        int returntype = ret.getStatusLine().getStatusCode();
        if (returntype == 403) {
            return new Object[] { "badAuth" };
        }
        try {
            mCol.getDb().getDatabase().beginTransaction();
            try {
                Timber.i("Sync: getting meta data from server");
                JSONObject_ rMeta = new JSONObject_(mServer.stream2String(ret.getEntity().getContent()));
                mCol.log("rmeta", rMeta);
                mSyncMsg = rMeta.getString_("msg");
                if (!rMeta.getBoolean_("cont")) {
                    // Don't add syncMsg; it can be fetched by UI code using the accessor
                    return new Object[] { "serverAbort" };
                } else {
                    // don't abort, but ui should show messages after sync finishes
                    // and require confirmation if it's non-empty
                }
                throwExceptionIfCancelled(con);
                long rscm = rMeta.getLong_("scm");
                int rts = rMeta.getInt_("ts");
                mRMod = rMeta.getLong_("mod");
                mMaxUsn = rMeta.getInt_("usn");
                // skip uname, AnkiDroid already stores and shows it
                Timber.i("Sync: building local meta data");
                JSONObject_ lMeta = meta();
                mCol.log("lmeta", lMeta);
                mLMod = lMeta.getLong_("mod");
                mMinUsn = lMeta.getInt_("usn");
                long lscm = lMeta.getLong_("scm");
                int lts = lMeta.getInt_("ts");

                long diff = Math.abs(rts - lts);
                if (diff > 300) {
                    mCol.log("clock off");
                    return new Object[] { "clockOff", diff };
                }
                if (mLMod == mRMod) {
                    Timber.i("Sync: no changes - returning");
                    mCol.log("no changes");
                    return new Object[] { "noChanges" };
                } else if (lscm != rscm) {
                    Timber.i("Sync: full sync necessary - returning");
                    mCol.log("schema diff");
                    return new Object[] { "fullSync" };
                }
                mLNewer = mLMod > mRMod;
                // step 1.5: check collection is valid
                if (!mCol.basicCheck()) {
                    mCol.log("basic check");
                    return new Object[] { "basicCheckFailed" };
                }
                throwExceptionIfCancelled(con);
                // step 2: deletions
                publishProgress(con, R.string.sync_deletions_message);

                Timber.i("Sync: collection removed data");
                JSONObject_ lrem = removed();
                JSONObject_ o = new JSONObject_();
                o.put_("minUsn", mMinUsn);
                o.put_("lnewer", mLNewer);
                o.put_("graves", lrem);

                Timber.i("Sync: sending and receiving removed data");
                JSONObject_ rrem = mServer.start(o);
                Timber.i("Sync: applying removed data");
                throwExceptionIfCancelled(con);
                remove(rrem);
                // ... and small objects
                publishProgress(con, R.string.sync_small_objects_message);

                Timber.i("Sync: collection small changes");
                JSONObject_ lchg = changes();
                JSONObject_ sch = new JSONObject_();
                sch.put_("changes", lchg);

                Timber.i("Sync: sending and receiving small changes");
                JSONObject_ rchg = mServer.applyChanges(sch);
                throwExceptionIfCancelled(con);
                Timber.i("Sync: merging small changes");
                mergeChanges(lchg, rchg);
                // step 3: stream large tables from server
                publishProgress(con, R.string.sync_download_chunk);
                while (true) {
                    throwExceptionIfCancelled(con);
                    Timber.i("Sync: downloading chunked data");
                    JSONObject_ chunk = mServer.chunk();
                    mCol.log("server chunk", chunk);
                    Timber.i("Sync: applying chunked data");
                    applyChunk(chunk);
                    if (chunk.getBoolean_("done")) {
                        break;
                    }
                }
                // step 4: stream to server
                publishProgress(con, R.string.sync_upload_chunk);
                while (true) {
                    throwExceptionIfCancelled(con);
                    Timber.i("Sync: collecting chunked data");
                    JSONObject_ chunk = chunk();
                    mCol.log("client chunk", chunk);
                    JSONObject_ sech = new JSONObject_();
                    sech.put_("chunk", chunk);
                    Timber.i("Sync: sending chunked data");
                    mServer.applyChunk(sech);
                    if (chunk.getBoolean_("done")) {
                        break;
                    }
                }
                // step 5: sanity check
                JSONObject_ c = sanityCheck();
                JSONObject_ sanity = mServer.sanityCheck2(c);
                if (sanity == null || !"ok".equals(sanity.optString("status", "bad"))) {
                    mCol.log("sanity check failed", c, sanity);
                    return new Object[] { "sanityCheckError", null };
                }
                // finalize
                publishProgress(con, R.string.sync_finish_message);
                Timber.i("Sync: sending finish command");
                long mod = mServer.finish();
                if (mod == 0) {
                    return new Object[] { "finishError" };
                }
                Timber.i("Sync: finishing");
                finish(mod);

                publishProgress(con, R.string.sync_writing_db);
                mCol.getDb().getDatabase().setTransactionSuccessful();
            } finally {
                mCol.getDb().getDatabase().endTransaction();
            }
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } catch (OutOfMemoryError e) {
            AnkiDroidApp.sendExceptionReport(e, "Syncer-sync");
            return new Object[] { "OutOfMemoryError" };
        } catch (IOException e) {
            AnkiDroidApp.sendExceptionReport(e, "Syncer-sync");
            return new Object[] { "IOException" };
        }
        return new Object[] { "success" };
    }


    private void publishProgress(Connection con, int id) {
        if (con != null) {
            con.publishProgress(id);
        }
    }


    public JSONObject_ meta() {
        JSONObject_ j = new JSONObject_();
        j.put_("mod", mCol.getMod());
        j.put_("scm", mCol.getScm());
        j.put_("usn", mCol.getUsnForSync());
        j.put_("ts", Utils.intTime());
        j.put_("musn", 0);
        j.put_("msg", "");
        j.put_("cont", true);
        return j;
    }


    /** Bundle up small objects. */
    public JSONObject_ changes() {
        JSONObject_ o = new JSONObject_();
        try {
            o.put_("models", getModels());
            o.put_("decks", getDecks());
            o.put_("tags", getTags());
            if (mLNewer) {
                o.put_("conf", getConf());
                o.put_("crt", mCol.getCrt());
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return o;
    }


    public JSONObject_ applyChanges(JSONObject_ changes) {
        mRChg = changes;
        JSONObject_ lchg = changes();
        // merge our side before returning
        mergeChanges(lchg, mRChg);
        return lchg;
    }


    public void mergeChanges(JSONObject_ lchg, JSONObject_ rchg) {
        try {
            // then the other objects
            mergeModels(rchg.getJSONArray_("models"));
            mergeDecks(rchg.getJSONArray_("decks"));
            mergeTags(rchg.getJSONArray_("tags"));
            if (rchg.has("conf")) {
                mergeConf(rchg.getJSONObject_("conf"));
            }
            // this was left out of earlier betas
            if (rchg.has("crt")) {
                mCol.setCrt(rchg.getLong_("crt"));
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        prepareToChunk();
    }


    public JSONObject_ sanityCheck() {
        JSONObject_ result = new JSONObject_();
        try {
            if (mCol.getDb().queryScalar("SELECT count() FROM cards WHERE nid NOT IN (SELECT id FROM notes)") != 0) {
                Timber.e("Sync - SanityCheck: there are cards without mother notes");
                result.put_("client", "missing notes");
                return result;
            }
            if (mCol.getDb().queryScalar("SELECT count() FROM notes WHERE id NOT IN (SELECT DISTINCT nid FROM cards)") != 0) {
                Timber.e("Sync - SanityCheck: there are notes without cards");
                result.put_("client", "missing cards");
                return result;
            }
            if (mCol.getDb().queryScalar("SELECT count() FROM cards WHERE usn = -1") != 0) {
                Timber.e("Sync - SanityCheck: there are unsynced cards");
                result.put_("client", "cards had usn = -1");
                return result;
            }
            if (mCol.getDb().queryScalar("SELECT count() FROM notes WHERE usn = -1") != 0) {
                Timber.e("Sync - SanityCheck: there are unsynced notes");
                result.put_("client", "notes had usn = -1");
                return result;
            }
            if (mCol.getDb().queryScalar("SELECT count() FROM revlog WHERE usn = -1") != 0) {
                Timber.e("Sync - SanityCheck: there are unsynced revlogs");
                result.put_("client", "revlog had usn = -1");
                return result;
            }
            if (mCol.getDb().queryScalar("SELECT count() FROM graves WHERE usn = -1") != 0) {
                Timber.e("Sync - SanityCheck: there are unsynced graves");
                result.put_("client", "graves had usn = -1");
                return result;
            }
            for (JSONObject_ g : mCol.getDecks().all()) {
                if (g.getInt_("usn") == -1) {
                    Timber.e("Sync - SanityCheck: unsynced deck: " + g.getString_("name"));
                    result.put_("client", "deck had usn = -1");
                    return result;
                }
            }
            for (Map.Entry<String, Integer> tag : mCol.getTags().allItems()) {
                if (tag.getValue() == -1) {
                    Timber.e("Sync - SanityCheck: there are unsynced tags");
                    result.put_("client", "tag had usn = -1");
                    return result;
                }
            }
            boolean found = false;
            for (JSONObject_ m : mCol.getModels().all()) {
                if (mCol.getServer()) {
                    // the web upgrade was mistakenly setting usn
                    if (m.getInt_("usn") < 0) {
                        m.put_("usn", 0);
                        found = true;
                    }
                } else {
                    if (m.getInt_("usn") == -1) {
                        Timber.e("Sync - SanityCheck: unsynced model: " + m.getString_("name"));
                        result.put_("client", "model had usn = -1");
                        return result;
                    }
                }
            }
            if (found) {
                mCol.getModels().save();
            }
            mCol.getSched().reset();
            // check for missing parent decks
            mCol.getSched().deckDueList();
            // return summary of deck
            JSONArray_ ja = new JSONArray_();
            JSONArray_ sa = new JSONArray_();
            for (int c : mCol.getSched().counts()) {
                sa.put_(c);
            }
            ja.put(sa);
            ja.put(mCol.getDb().queryScalar("SELECT count() FROM cards"));
            ja.put(mCol.getDb().queryScalar("SELECT count() FROM notes"));
            ja.put(mCol.getDb().queryScalar("SELECT count() FROM revlog"));
            ja.put(mCol.getDb().queryScalar("SELECT count() FROM graves"));
            ja.put(mCol.getModels().all().size());
            ja.put(mCol.getDecks().all().size());
            ja.put(mCol.getDecks().allConf().size());
            result.put("client", ja);
            return result;
        } catch (JSONException e) {
            Timber.e(e, "Syncer.sanityCheck()");
            throw new RuntimeException(e);
        }
    }


    // private Map<String, Object> sanityCheck2(JSONArray_ client) {
    // Object server = sanityCheck();
    // Map<String, Object> result = new HashMap<String, Object>();
    // if (client.equals(server)) {
    // result.put__("status", "ok");
    // } else {
    // result.put__("status", "bad");
    // result.put__("c", client);
    // result.put__("s", server);
    // }
    // return result;
    // }

    private String usnLim() {
        if (mCol.getServer()) {
            return "usn >= " + mMinUsn;
        } else {
            return "usn = -1";
        }
    }


    public long finish() {
        return finish(0);
    }


    private long finish(long mod) {
        if (mod == 0) {
            // server side; we decide new mod time
            mod = Utils.intTime(1000);
        }
        mCol.setLs(mod);
        mCol.setUsnAfterSync(mMaxUsn + 1);
        // ensure we save the mod time even if no changes made
        mCol.getDb().setMod(true);
        mCol.save(null, mod);
        return mod;
    }


    /**
     * Chunked syncing ********************************************************************
     */

    private void prepareToChunk() {
        mTablesLeft = new LinkedList<>();
        mTablesLeft.add("revlog");
        mTablesLeft.add("cards");
        mTablesLeft.add("notes");
        mCursor = null;
    }


    private Cursor cursorForTable(String table) {
        String lim = usnLim();
        if ("revlog".equals(table)) {
            return mCol
                    .getDb()
                    .getDatabase()
                    .query(
                            String.format(Locale.US,
                                    "SELECT id, cid, %d, ease, ivl, lastIvl, factor, time, type FROM revlog WHERE %s",
                                    mMaxUsn, lim), null);
        } else if ("cards".equals(table)) {
            return mCol
                    .getDb()
                    .getDatabase()
                    .query(
                            String.format(
                                    Locale.US,
                                    "SELECT id, nid, did, ord, mod, %d, type, queue, due, ivl, factor, reps, lapses, left, odue, odid, flags, data FROM cards WHERE %s",
                                    mMaxUsn, lim), null);
        } else {
            return mCol
                    .getDb()
                    .getDatabase()
                    .query(
                            String.format(
                                    Locale.US,
                                    "SELECT id, guid, mid, mod, %d, tags, flds, '', '', flags, data FROM notes WHERE %s",
                                    mMaxUsn, lim), null);
        }
    }


    private List<Integer> columnTypesForQuery(String table) {
        if ("revlog".equals(table)) {
            return Arrays.asList(TYPE_INTEGER, TYPE_INTEGER, TYPE_INTEGER, TYPE_INTEGER, TYPE_INTEGER, TYPE_INTEGER,
                    TYPE_INTEGER, TYPE_INTEGER, TYPE_INTEGER);
        } else if ("cards".equals(table)) {
            return Arrays.asList(TYPE_INTEGER, TYPE_INTEGER, TYPE_INTEGER, TYPE_INTEGER, TYPE_INTEGER, TYPE_INTEGER,
                    TYPE_INTEGER, TYPE_INTEGER, TYPE_INTEGER, TYPE_INTEGER, TYPE_INTEGER, TYPE_INTEGER, TYPE_INTEGER,
                    TYPE_INTEGER, TYPE_INTEGER, TYPE_INTEGER, TYPE_INTEGER, TYPE_STRING);
        } else {
            return Arrays.asList(TYPE_INTEGER, TYPE_STRING, TYPE_INTEGER, TYPE_INTEGER, TYPE_INTEGER, TYPE_STRING,
                    TYPE_STRING, TYPE_STRING, TYPE_STRING, TYPE_INTEGER, TYPE_STRING);
        }
    }


    public JSONObject_ chunk() {
        JSONObject_ buf = new JSONObject_();
        try {
            buf.put_("done", false);
            int lim = 250;
            List<Integer> colTypes = null;
            while (!mTablesLeft.isEmpty() && lim > 0) {
                String curTable = mTablesLeft.getFirst();
                if (mCursor == null) {
                    mCursor = cursorForTable(curTable);
                }
                colTypes = columnTypesForQuery(curTable);
                JSONArray_ rows = new JSONArray_();
                int count = mCursor.getColumnCount();
                int fetched = 0;
                while (mCursor.moveToNext()) {
                    JSONArray_ r = new JSONArray_();
                    for (int i = 0; i < count; i++) {
                        switch (colTypes.get(i)) {
                            case TYPE_STRING:
                                r.put(mCursor.getString(i));
                                break;
                            case TYPE_FLOAT:
                                r.put_(mCursor.getDouble(i));
                                break;
                            case TYPE_INTEGER:
                                r.put_(mCursor.getLong(i));
                                break;
                        }
                    }
                    rows.put(r);
                    if (++fetched == lim) {
                        break;
                    }
                }
                if (fetched != lim) {
                    // table is empty
                    mTablesLeft.removeFirst();
                    mCursor.close();
                    mCursor = null;
                    // if we're the client, mark the objects as having been sent
                    if (!mCol.getServer()) {
                        mCol.getDb().execute("UPDATE " + curTable + " SET usn=" + mMaxUsn + " WHERE usn=-1");
                    }
                }
                buf.put_(curTable, rows);
                lim -= fetched;
            }
            if (mTablesLeft.isEmpty()) {
                buf.put_("done", true);
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return buf;
    }


    public void applyChunk(JSONObject_ chunk) {
        try {
            if (chunk.has("revlog")) {
                mergeRevlog(chunk.getJSONArray_("revlog"));
            }
            if (chunk.has("cards")) {
                mergeCards(chunk.getJSONArray_("cards"));
            }
            if (chunk.has("notes")) {
                mergeNotes(chunk.getJSONArray_("notes"));
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }            
    }


    /**
     * Deletions ********************************************************************
     */

    private JSONObject_ removed() {
        JSONArray_ cards = new JSONArray_();
        JSONArray_ notes = new JSONArray_();
        JSONArray_ decks = new JSONArray_();
        Cursor cur = null;
        try {
            cur = mCol
                    .getDb()
                    .getDatabase()
                    .query(
                            "SELECT oid, type FROM graves WHERE usn"
                                    + (mCol.getServer() ? (" >= " + mMinUsn) : (" = -1")), null);
            while (cur.moveToNext()) {
                int type = cur.getInt(1);
                switch (type) {
                    case Consts.REM_CARD:
                        cards.put_(cur.getLong(0));
                        break;
                    case Consts.REM_NOTE:
                        notes.put_(cur.getLong(0));
                        break;
                    case Consts.REM_DECK:
                        decks.put_(cur.getLong(0));
                        break;
                }
            }
        } finally {
            if (cur != null && !cur.isClosed()) {
                cur.close();
            }
        }
        if (!mCol.getServer()) {
            mCol.getDb().execute("UPDATE graves SET usn=" + mMaxUsn + " WHERE usn=-1");
        }
        JSONObject_ o = new JSONObject_();
        try {
            o.put_("cards", cards);
            o.put_("notes", notes);
            o.put_("decks", decks);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return o;
    }


    public JSONObject_ start(int minUsn, boolean lnewer, JSONObject_ graves) {
        mMaxUsn = mCol.getUsnForSync();
        mMinUsn = minUsn;
        mLNewer = !lnewer;
        JSONObject_ lgraves = removed();
        remove(graves);
        return lgraves;
    }


    private void remove(JSONObject_ graves) {
        // pretend to be the server so we don't set usn = -1
        boolean wasServer = mCol.getServer();
        mCol.setServer(true);
        try {
            // notes first, so we don't end up with duplicate graves
            mCol._remNotes(Utils.jsonArrayToLongArray(graves.getJSONArray_("notes")));
            // then cards
            mCol.remCards(Utils.jsonArrayToLongArray(graves.getJSONArray_("cards")), false);
            // and decks
            JSONArray_ decks = graves.getJSONArray_("decks");
            for (int i = 0; i < decks.length(); i++) {
                mCol.getDecks().rem(decks.getLong_(i), false, false);
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        mCol.setServer(wasServer);
    }


    /**
     * Models ********************************************************************
     */

    private JSONArray_ getModels() {
        JSONArray_ result = new JSONArray_();
        try {
            if (mCol.getServer()) {
                for (JSONObject_ m : mCol.getModels().all()) {
                    if (m.getInt_("usn") >= mMinUsn) {
                        result.put(m);
                    }
                }
            } else {
                for (JSONObject_ m : mCol.getModels().all()) {
                    if (m.getInt_("usn") == -1) {
                        m.put_("usn", mMaxUsn);
                        result.put(m);
                    }
                }
                mCol.getModels().save();
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return result;
    }


    private void mergeModels(JSONArray_ rchg) {
        for (int i = 0; i < rchg.length(); i++) {
            try {
                JSONObject_ r = rchg.getJSONObject_(i);
                JSONObject_ l;
                l = mCol.getModels().get(r.getLong_("id"));
                // if missing locally or server is newer, update
                if (l == null || r.getLong_("mod") > l.getLong_("mod")) {
                    mCol.getModels().update(r);
                }
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        }
    }


    /**
     * Decks ********************************************************************
     */

    private JSONArray_ getDecks() {
        JSONArray_ result = new JSONArray_();
        try {
            if (mCol.getServer()) {
                JSONArray_ decks = new JSONArray_();
                for (JSONObject_ g : mCol.getDecks().all()) {
                    if (g.getInt_("usn") >= mMinUsn) {
                        decks.put(g);
                    }
                }
                JSONArray_ dconfs = new JSONArray_();
                for (JSONObject_ g : mCol.getDecks().allConf()) {
                    if (g.getInt_("usn") >= mMinUsn) {
                        dconfs.put(g);
                    }
                }
                result.put(decks);
                result.put(dconfs);
            } else {
                JSONArray_ decks = new JSONArray_();
                for (JSONObject_ g : mCol.getDecks().all()) {
                    if (g.getInt_("usn") == -1) {
                        g.put_("usn", mMaxUsn);
                        decks.put(g);
                    }
                }
                JSONArray_ dconfs = new JSONArray_();
                for (JSONObject_ g : mCol.getDecks().allConf()) {
                    if (g.getInt_("usn") == -1) {
                        g.put_("usn", mMaxUsn);
                        dconfs.put(g);
                    }
                }
                mCol.getDecks().save();
                result.put(decks);
                result.put(dconfs);
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return result;
    }


    private void mergeDecks(JSONArray_ rchg) {
        try {
            JSONArray_ decks = rchg.getJSONArray_(0);
            for (int i = 0; i < decks.length(); i++) {
                JSONObject_ r = decks.getJSONObject_(i);
                JSONObject_ l = mCol.getDecks().get(r.getLong_("id"), false);
                // if missing locally or server is newer, update
                if (l == null || r.getLong_("mod") > l.getLong_("mod")) {
                    mCol.getDecks().update(r);
                }
            }
            JSONArray_ confs = rchg.getJSONArray_(1);
            for (int i = 0; i < confs.length(); i++) {
                JSONObject_ r = confs.getJSONObject_(i);
                JSONObject_ l = mCol.getDecks().getConf(r.getLong_("id"));
                // if missing locally or server is newer, update
                if (l == null || r.getLong_("mod") > l.getLong_("mod")) {
                    mCol.getDecks().updateConf(r);
                }
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * Tags ********************************************************************
     */

    private JSONArray_ getTags() {
        JSONArray_ result = new JSONArray_();
        if (mCol.getServer()) {
            for (Map.Entry<String, Integer> t : mCol.getTags().allItems()) {
                if (t.getValue() >= mMinUsn) {
                    result.put(t.getKey());
                }
            }
        } else {
            for (Map.Entry<String, Integer> t : mCol.getTags().allItems()) {
                if (t.getValue() == -1) {
                    String tag = t.getKey();
                    mCol.getTags().add(t.getKey(), mMaxUsn);
                    result.put(tag);
                }
            }
            mCol.getTags().save();
        }
        return result;
    }


    private void mergeTags(JSONArray_ tags) {
        ArrayList<String> list = new ArrayList<>();
        for (int i = 0; i < tags.length(); i++) {
            try {
                list.add(tags.getString_(i));
            } catch (JSONException e) {
            throw new RuntimeException(e);
            }
        }
        mCol.getTags().register(list, mMaxUsn);
    }


    /**
     * Cards/notes/revlog ********************************************************************
     */

    private void mergeRevlog(JSONArray_ logs) {
        for (int i = 0; i < logs.length(); i++) {
            try {
                mCol.getDb().execute("INSERT OR IGNORE INTO revlog VALUES (?,?,?,?,?,?,?,?,?)",
                        Utils.jsonArray2Objects(logs.getJSONArray_(i)));
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }

    }


    private ArrayList<Object[]> newerRows(JSONArray_ data, String table, int modIdx) {
        long[] ids = new long[data.length()];
        try {
            for (int i = 0; i < data.length(); i++) {
                ids[i] = data.getJSONArray_(i).getLong_(0);
            }
            HashMap<Long, Long> lmods = new HashMap<>();
            Cursor cur = null;
            try {
                cur = mCol
                        .getDb()
                        .getDatabase()
                        .query(
                                "SELECT id, mod FROM " + table + " WHERE id IN " + Utils.ids2str(ids) + " AND "
                                        + usnLim(), null);
                while (cur.moveToNext()) {
                    lmods.put(cur.getLong(0), cur.getLong(1));
                }
            } finally {
                if (cur != null && !cur.isClosed()) {
                    cur.close();
                }
            }
            ArrayList<Object[]> update = new ArrayList<>();
            for (int i = 0; i < data.length(); i++) {
                JSONArray_ r = data.getJSONArray_(i);
                if (!lmods.containsKey(r.getLong_(0)) || lmods.get(r.getLong_(0)) < r.getLong_(modIdx)) {
                    update.add(Utils.jsonArray2Objects(r));
                }
            }
            mCol.log(table, data);
            return update;
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }


    private void mergeCards(JSONArray_ cards) {
        for (Object[] r : newerRows(cards, "cards", 4)) {
            mCol.getDb().execute("INSERT OR REPLACE INTO cards VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", r);
        }
    }


    private void mergeNotes(JSONArray_ notes) {
        for (Object[] n : newerRows(notes, "notes", 4)) {
            mCol.getDb().execute("INSERT OR REPLACE INTO notes VALUES (?,?,?,?,?,?,?,?,?,?,?)", n);
            mCol.updateFieldCache(new long[]{Long.valueOf(((Number) n[0]).longValue())});
        }
    }


    public String getSyncMsg() {
        return mSyncMsg;
    }


    /**
     * Col config ********************************************************************
     */

    private JSONObject_ getConf() {
        return mCol.getConf();
    }


    private void mergeConf(JSONObject_ conf) {
        mCol.setConf(conf);
    }

    /**
     * If the user asked to cancel the sync then we just throw a Runtime exception which should be gracefully handled
     * @param con
     */
    private void throwExceptionIfCancelled(Connection con) {
        if (Connection.getIsCancelled()) {
            Timber.i("Sync was cancelled");
            publishProgress(con, R.string.sync_cancelled);
            try {
                mServer.abort();
            } catch (UnknownHttpResponseException e) {
            }
            throw new RuntimeException("UserAbortedSync");
        }
    }

}
