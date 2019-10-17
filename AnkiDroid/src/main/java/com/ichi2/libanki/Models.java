/****************************************************************************************
 * Copyright (c) 2009 Daniel Svärd <daniel.svard@gmail.com>                             *
 * Copyright (c) 2010 Rick Gruber-Riemer <rick@vanosten.net>                            *
 * Copyright (c) 2011 Norbert Nagold <norbert.nagold@gmail.com>                         *
 * Copyright (c) 2011 Kostas Spyropoulos <inigo.aldana@gmail.com>                       *
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

package com.ichi2.libanki;

import android.content.ContentValues;
import android.database.Cursor;
import android.util.Pair;

import com.ichi2.anki.exception.ConfirmModSchemaException;
import com.ichi2.utils.JSONArray_;
import com.ichi2.utils.JSONObject_;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings({"PMD.ExcessiveClassLength", "PMD.AvoidThrowingRawExceptionTypes","PMD.AvoidReassigningParameters",
        "PMD.NPathComplexity","PMD.MethodNamingConventions",
        "PMD.SwitchStmtsShouldHaveDefault","PMD.CollapsibleIfStatements","PMD.EmptyIfStmt"})
public class Models {
    private static final Pattern fClozePattern1 = Pattern.compile("\\{\\{[^}]*?cloze:(?:[^}]?:)*(.+?)\\}\\}");
    private static final Pattern fClozePattern2 = Pattern.compile("<%cloze:(.+?)%>");
    private static final Pattern fClozeOrdPattern = Pattern.compile("(?si)\\{\\{c(\\d+)::.+?\\}\\}");

    public static final String defaultModel =
              "{'sortf': 0, "
            + "'did': 1, "
            + "'latexPre': \""
            + "\\\\documentclass[12pt]{article}\\n"
            + "\\\\special{papersize=3in,5in}\\n"
            + "\\\\usepackage[utf8]{inputenc}\\n"
            + "\\\\usepackage{amssymb,amsmath}\\n"
            + "\\\\pagestyle{empty}\\n"
            + "\\\\setlength{\\\\parindent}{0in}\\n"
            + "\\\\begin{document}\\n"
            + "\", "
            + "'latexPost': \"\\\\end{document}\", "
            + "'mod': 0, "
            + "'usn': 0, "
            + "'vers': [], " // FIXME: remove when other clients have caught up
            + "'type': "
            + Consts.MODEL_STD
            + ", "
            + "'css': \".card {\\n"
            + " font-family: arial;\\n"
            + " font-size: 20px;\\n"
            + " text-align: center;\\n"
            + " color: black;\\n"
            + " background-color: white;\\n"
            + "}\""
            + "}";

    private static final String defaultField = "{'name': \"\", " + "'ord': null, " + "'sticky': False, " +
    // the following alter editing, and are used as defaults for the template wizard
            "'rtl': False, " + "'font': \"Arial\", " + "'size': 20, " +
            // reserved for future use
            "'media': [] }";

    private static final String defaultTemplate = "{'name': \"\", " + "'ord': null, " + "'qfmt': \"\", "
            + "'afmt': \"\", " + "'did': null, " + "'bqfmt': \"\"," + "'bafmt': \"\"," + "'bfont': \"Arial\"," +
            "'bsize': 12 }";

    // /** Regex pattern used in removing tags from text before diff */
    // private static final Pattern sFactPattern = Pattern.compile("%\\([tT]ags\\)s");
    // private static final Pattern sModelPattern = Pattern.compile("%\\(modelTags\\)s");
    // private static final Pattern sTemplPattern = Pattern.compile("%\\(cardModel\\)s");

    private Collection mCol;
    private boolean mChanged;
    private HashMap<Long, JSONObject_> mModels;

    // BEGIN SQL table entries
    private int mId;
    private String mName = "";
    //private long mCrt = Utils.intTime();
    //private long mMod = Utils.intTime();
    //private JSONObject_ mConf;
    //private String mCss = "";
    //private JSONArray_ mFields;
    //private JSONArray_ mTemplates;
    // BEGIN SQL table entries

    // private Decks mDeck;
    // private DB mDb;
    //
    /** Map for compiled Mustache Templates */
    //private Map<String, Template> mCmpldTemplateMap = new HashMap<>();


    //
    // /** Map for convenience and speed which contains FieldNames from current model */
    // private TreeMap<String, Integer> mFieldMap = new TreeMap<String, Integer>();
    //
    // /** Map for convenience and speed which contains Templates from current model */
    // private TreeMap<Integer, JSONObject_> mTemplateMap = new TreeMap<Integer, JSONObject_>();
    //
    // /** Map for convenience and speed which contains the CSS code related to a Template */
    // private HashMap<Integer, String> mCssTemplateMap = new HashMap<Integer, String>();
    //
    // /**
    // * The percentage chosen in preferences for font sizing at the time when the css for the CardModels related to
    // this
    // * Model was calculated in prepareCSSForCardModels.
    // */
    // private transient int mDisplayPercentage = 0;
    // private boolean mNightMode = false;

    /**
     * Saving/loading registry
     * ***********************************************************************************************
     */

    public Models(Collection col) {
        mCol = col;
    }


    /**
     * Load registry from JSON.
     */
    public void load(String json) {
        mChanged = false;
        mModels = new HashMap<>();
        JSONObject_ modelarray = new JSONObject_(json);
        JSONArray_ ids = modelarray.names();
        if (ids != null) {
            for (int i = 0; i < ids.length(); i++) {
                String id = ids.getString_(i);
                JSONObject_ o = modelarray.getJSONObject_(id);
                mModels.put(o.getLong_("id"), o);
            }
        }
    }


    /**
     * Mark M modified if provided, and schedule registry flush.
     */
    public void save() {
        save(null, false);
    }


    public void save(JSONObject_ m) {
        save(m, false);
    }

    /**
     * Save a model
     * @param m model to save
     * @param templates flag which (when true) re-generates the cards for each note which uses the model
     */
    public void save(JSONObject_ m, boolean templates) {
        if (m != null && m.has("id")) {
                m.put_("mod", Utils.intTime());
                m.put_("usn", mCol.usn());
                // TODO: fix empty id problem on _updaterequired (needed for model adding)
                if (m.getLong_("id") != 0) {
                    _updateRequired(m);
                }
                if (templates) {
                    _syncTemplates(m);
                }
        }
        mChanged = true;
        // The following hook rebuilds the tree in the Anki Desktop browser -- we don't need it
        // runHook("newModel")
    }


    /**
     * Flush the registry if any models were changed.
     */
    public void flush() {
        if (mChanged) {
            JSONObject_ array = new JSONObject_();
                for (Map.Entry<Long, JSONObject_> o : mModels.entrySet()) {
                    array.put_(Long.toString(o.getKey()), o.getValue());
                }
            ContentValues val = new ContentValues();
            val.put("models", Utils.jsonToString(array));
            mCol.getDb().update("col", val);
            mChanged = false;
        }
    }


    /**
     * Retrieving and creating models
     * ***********************************************************************************************
     */

    /**
     * Get current model.
     * @return The JSONObject_ of the model, or null if not found in the deck and in the configuration.
     */
    public JSONObject_ current() {
        return current(true);
    }

    /**
     * Get current model.
     * @param forDeck If true, it tries to get the deck specified in deck by mid, otherwise or if the former is not
     *                found, it uses the configuration`s field curModel.
     * @return The JSONObject_ of the model, or null if not found in the deck and in the configuration.
     */
    public JSONObject_ current(boolean forDeck) {
        JSONObject_ m = null;
        if (forDeck) {
            m = get(mCol.getDecks().current().optLong("mid", -1));
        }
        if (m == null) {
            m = get(mCol.getConf().optLong("curModel", -1));
        }
        if (m == null) {
            if (!mModels.isEmpty()) {
                m = mModels.values().iterator().next();
            }
        }
        return m;
    }


    public void setCurrent(JSONObject_ m) {
        mCol.getConf().put_("curModel", m.get_("id"));
        mCol.setMod();
    }


    /** get model with ID, or none. */
    public JSONObject_ get(long id) {
        if (mModels.containsKey(id)) {
            return mModels.get(id);
        } else {
            return null;
        }
    }


    /** get all models */
    public ArrayList<JSONObject_> all() {
        ArrayList<JSONObject_> models = new ArrayList<>();
        for (JSONObject_ jsonObject : mModels.values()) {
            models.add(jsonObject);
        }
        return models;
    }


    /** get model with NAME. */
    public JSONObject_ byName(String name) {
        for (JSONObject_ m : mModels.values()) {
                if (m.getString_("name").equals(name)) {
                    return m;
                }
        }
        return null;
    }


    /** Create a new model, save it in the registry, and return it. */
	// Called `new` in Anki's code. New is a reserved word in java,
	// not in python. Thus the method has to be renamed.
    public JSONObject_ newModel(String name) {
        // caller should call save() after modifying
        JSONObject_ m;
            m = new JSONObject_(defaultModel);
            m.put_("name", name);
            m.put_("mod", Utils.intTime());
            m.put_("flds", new JSONArray_());
            m.put_("tmpls", new JSONArray_());
            m.put_("tags", new JSONArray_());
            m.put_("id", 0);
        return m;
    }


    /** Delete model, and all its cards/notes. 
     * @throws ConfirmModSchemaException */
    public void rem(JSONObject_ m) throws ConfirmModSchemaException {
        mCol.modSchema(true);
            long id = m.getLong_("id");
            boolean current = current().getLong_("id") == id;
            // delete notes/cards
            mCol.remCards(Utils.arrayList2array(mCol.getDb().queryColumn(Long.class,
                    "SELECT id FROM cards WHERE nid IN (SELECT id FROM notes WHERE mid = " + id + ")", 0)));
            // then the model
            mModels.remove(id);
            save();
            // GUI should ensure last model is not deleted
            if (current) {
                setCurrent(mModels.values().iterator().next());
            }
    }


    public void add(JSONObject_ m) {
        _setID(m);
        update(m);
        setCurrent(m);
        save(m);
    }


    /** Add or update an existing model. Used for syncing and merging. */
    public void update(JSONObject_ m) {
            mModels.put(m.getLong_("id"), m);
        // mark registry changed, but don't bump mod time
        save();
    }


    private void _setID(JSONObject_ m) {
        long id = Utils.intTime(1000);
        while (mModels.containsKey(id)) {
            id = Utils.intTime(1000);
        }
            m.put_("id", id);
    }


    public boolean have(long id) {
        return mModels.containsKey(id);
    }


    public long[] ids() {
        Iterator<Long> it = mModels.keySet().iterator();
        long[] ids = new long[mModels.size()];
        int i = 0;
        while (it.hasNext()) {
            ids[i] = it.next();
            i++;
        }
        return ids;
    }


    /**
     * Tools ***********************************************************************************************
     */

    /** Note ids for M */
    public ArrayList<Long> nids(JSONObject_ m) {
            return mCol.getDb().queryColumn(Long.class, "SELECT id FROM notes WHERE mid = " + m.getLong_("id"), 0);
    }

    /**
     * Number of notes using m
     * @param m The model to the count the notes of.
     * @return The number of notes with that model.
     */
    public int useCount(JSONObject_ m) {
            return mCol.getDb().queryScalar("select count() from notes where mid = " + m.getLong_("id"));
    }

    /**
     * Number of notes using m
     * @param m The model to the count the notes of.
     * @param ord The index of the card template
     * @return The number of notes with that model.
     */
    public int tmplUseCount(JSONObject_ m, int ord) {
            return mCol.getDb().queryScalar("select count() from cards, notes where cards.nid = notes.id and notes.mid = " + m.getLong_("id") + " and cards.ord = " + ord);
    }

    /**
     * Copying ***********************************************************************************************
     */

    /** Copy, save and return. */
    public JSONObject_ copy(JSONObject_ m) {
        JSONObject_ m2 = null;
            m2 = new JSONObject_(Utils.jsonToString(m));
            m2.put_("name", m2.getString_("name") + " copy");
        add(m2);
        return m2;
    }


    /**
     * Fields ***********************************************************************************************
     */

    public JSONObject_ newField(String name) {
        JSONObject_ f;
            f = new JSONObject_(defaultField);
            f.put_("name", name);
        return f;
    }


    /** "Mapping of field name -> (ord, field). */
    public Map<String, Pair<Integer, JSONObject_>> fieldMap(JSONObject_ m) {
        JSONArray_ ja;
        ja = m.getJSONArray_("flds");
        // TreeMap<Integer, String> map = new TreeMap<Integer, String>();
        Map<String, Pair<Integer, JSONObject_>> result = new HashMap<>();
        for (int i = 0; i < ja.length(); i++) {
            JSONObject_ f = ja.getJSONObject_(i);
            result.put(f.getString_("name"), new Pair<>(f.getInt_("ord"), f));
        }
        return result;
    }


    public ArrayList<String> fieldNames(JSONObject_ m) {
        JSONArray_ ja;
        ja = m.getJSONArray_("flds");
        ArrayList<String> names = new ArrayList<>();
        for (int i = 0; i < ja.length(); i++) {
            names.add(ja.getJSONObject_(i).getString_("name"));
        }
        return names;

    }


    public int sortIdx(JSONObject_ m) {
            return m.getInt_("sortf");
    }


    public void setSortIdx(JSONObject_ m, int idx) throws ConfirmModSchemaException{
            mCol.modSchema(true);
            m.put_("sortf", idx);
            mCol.updateFieldCache(Utils.toPrimitive(nids(m)));
            save(m);
    }


    public void addField(JSONObject_ m, JSONObject_ field) throws ConfirmModSchemaException {
        // only mod schema if model isn't new
            if (m.getLong_("id") != 0) {
                mCol.modSchema(true);
            }
            JSONArray_ ja = m.getJSONArray_("flds");
            ja.put(field);
            m.put_("flds", ja);
            _updateFieldOrds(m);
            save(m);
            _transformFields(m, new TransformFieldAdd());

    }

    class TransformFieldAdd implements TransformFieldVisitor {
        @Override
        public String[] transform(String[] fields) {
            String[] f = new String[fields.length + 1];
            System.arraycopy(fields, 0, f, 0, fields.length);
            f[fields.length] = "";
            return f;
        }
    }


    public void remField(JSONObject_ m, JSONObject_ field) throws ConfirmModSchemaException {
        mCol.modSchema(true);
        JSONArray_ ja = m.getJSONArray_("flds");
        JSONArray_ ja2 = new JSONArray_();
        int idx = -1;
        for (int i = 0; i < ja.length(); ++i) {
            if (field.equals(ja.getJSONObject_(i))) {
                idx = i;
                continue;
            }
            ja2.put(ja.get_(i));
        }
        m.put_("flds", ja2);
        int sortf = m.getInt_("sortf");
        if (sortf >= m.getJSONArray_("flds").length()) {
            m.put_("sortf", sortf - 1);
        }
        _updateFieldOrds(m);
        _transformFields(m, new TransformFieldDelete(idx));
        if (idx == sortIdx(m)) {
            // need to rebuild
            mCol.updateFieldCache(Utils.toPrimitive(nids(m)));
        }
        renameField(m, field, null);

    }

    class TransformFieldDelete implements TransformFieldVisitor {
        private int idx;


        public TransformFieldDelete(int _idx) {
            idx = _idx;
        }


        @Override
        public String[] transform(String[] fields) {
            ArrayList<String> fl = new ArrayList<>(Arrays.asList(fields));
            fl.remove(idx);
            return fl.toArray(new String[fl.size()]);
        }
    }


    public void moveField(JSONObject_ m, JSONObject_ field, int idx) throws ConfirmModSchemaException {
        mCol.modSchema(true);
        JSONArray_ ja = m.getJSONArray_("flds");
        ArrayList<JSONObject_> l = new ArrayList<>();
        int oldidx = -1;
        for (int i = 0; i < ja.length(); ++i) {
            l.add(ja.getJSONObject_(i));
            if (field.equals(ja.getJSONObject_(i))) {
                oldidx = i;
                if (idx == oldidx) {
                    return;
                }
            }
        }
        // remember old sort field
        String sortf = Utils.jsonToString(m.getJSONArray_("flds").getJSONObject_(m.getInt_("sortf")));
        // move
        l.remove(oldidx);
        l.add(idx, field);
        m.put_("flds", new JSONArray_(l));
        // restore sort field
        ja = m.getJSONArray_("flds");
        for (int i = 0; i < ja.length(); ++i) {
            if (Utils.jsonToString(ja.getJSONObject_(i)).equals(sortf)) {
                m.put_("sortf", i);
                break;
            }
        }
        _updateFieldOrds(m);
        save(m);
        _transformFields(m, new TransformFieldMove(idx, oldidx));

    }

    class TransformFieldMove implements TransformFieldVisitor {
        private int idx;
        private int oldidx;


        public TransformFieldMove(int _idx, int _oldidx) {
            idx = _idx;
            oldidx = _oldidx;
        }


        @Override
        public String[] transform(String[] fields) {
            String val = fields[oldidx];
            ArrayList<String> fl = new ArrayList<>(Arrays.asList(fields));
            fl.remove(oldidx);
            fl.add(idx, val);
            return fl.toArray(new String[fl.size()]);
        }
    }


    public void renameField(JSONObject_ m, JSONObject_ field, String newName) throws ConfirmModSchemaException {
        mCol.modSchema(true);
        String pat = String.format("\\{\\{([^{}]*)([:#^/]|[^:#/^}][^:}]*?:|)%s\\}\\}",
                Pattern.quote(field.getString_("name")));
        if (newName == null) {
            newName = "";
        }
        String repl = "{{$1$2" + newName + "}}";

        JSONArray_ tmpls = m.getJSONArray_("tmpls");
        for (int i = 0; i < tmpls.length(); ++i) {
            JSONObject_ t = new JSONObject_(tmpls.getJSONObject_(i));
            for (String fmt : new String[] { "qfmt", "afmt" }) {
                if (!"".equals(newName)) {
                    t.put_(fmt, t.getString_(fmt).replaceAll(pat, repl));
                } else {
                    t.put_(fmt, t.getString_(fmt).replaceAll(pat, ""));
                }
            }
        }
        field.put_("name", newName);
        save(m);
    }


    public void _updateFieldOrds(JSONObject_ m) {
        JSONArray_ ja;
        ja = m.getJSONArray_("flds");
        for (int i = 0; i < ja.length(); i++) {
            JSONObject_ f = ja.getJSONObject_(i);
            f.put_("ord", i);
        }
    }

    interface TransformFieldVisitor {
        public String[] transform(String[] fields);
    }


    public void _transformFields(JSONObject_ m, TransformFieldVisitor fn) {
        // model hasn't been added yet?
        if (m.getLong_("id") == 0) {
            return;
        }
        ArrayList<Object[]> r = new ArrayList<>();
        Cursor cur = null;

        try {
            cur = mCol.getDb().getDatabase()
                    .query("select id, flds from notes where mid = " + m.getLong_("id"), null);
            while (cur.moveToNext()) {
                r.add(new Object[] {
                        Utils.joinFields(fn.transform(Utils.splitFields(cur.getString(1)))),
                        Utils.intTime(), mCol.usn(), cur.getLong(0) });
            }
        } finally {
            if (cur != null) {
                cur.close();
            }
        }
        mCol.getDb().executeMany("update notes set flds=?,mod=?,usn=? where id = ?", r);
    }


    /**
     * Templates ***********************************************************************************************
     */

    public JSONObject_ newTemplate(String name) {
        JSONObject_ t;
        t = new JSONObject_(defaultTemplate);
        t.put_("name", name);
        return t;
    }


    /** Note: should col.genCards() afterwards. 
     * @throws ConfirmModSchemaException */
    public void addTemplate(JSONObject_ m, JSONObject_ template) throws ConfirmModSchemaException {
        if (m.getLong_("id") != 0) {
            mCol.modSchema(true);
        }
        JSONArray_ ja = m.getJSONArray_("tmpls");
        ja.put(template);
        m.put_("tmpls", ja);
        _updateTemplOrds(m);
        save(m);
    }


    /**
     * Removing a template
     *
     * @return False if removing template would leave orphan notes.
     * @throws ConfirmModSchemaException 
     */
    public boolean remTemplate(JSONObject_ m, JSONObject_ template) throws ConfirmModSchemaException {
        assert (m.getJSONArray_("tmpls").length() > 1);
        // find cards using this template
        JSONArray_ ja = m.getJSONArray_("tmpls");
        int ord = -1;
        for (int i = 0; i < ja.length(); ++i) {
            if (ja.get_(i).equals(template)) {
                ord = i;
                break;
            }
        }
        String sql = "select c.id from cards c, notes f where c.nid=f.id and mid = " +
                m.getLong_("id") + " and ord = " + ord;
        long[] cids = Utils.toPrimitive(mCol.getDb().queryColumn(Long.class, sql, 0));
        // all notes with this template must have at least two cards, or we could end up creating orphaned notes
        sql = "select nid, count() from cards where nid in (select nid from cards where id in " +
                Utils.ids2str(cids) + ") group by nid having count() < 2 limit 1";
        if (mCol.getDb().queryScalar(sql) != 0) {
            return false;
        }
        // ok to proceed; remove cards
        mCol.modSchema(true);
        mCol.remCards(cids);
        // shift ordinals
        mCol.getDb()
                .execute(
                        "update cards set ord = ord - 1, usn = ?, mod = ? where nid in (select id from notes where mid = ?) and ord > ?",
                        new Object[] { mCol.usn(), Utils.intTime(), m.getLong_("id"), ord });
        JSONArray_ tmpls = m.getJSONArray_("tmpls");
        JSONArray_ ja2 = new JSONArray_();
        for (int i = 0; i < tmpls.length(); ++i) {
            if (template.equals(tmpls.getJSONObject_(i))) {
                continue;
            }
            ja2.put(tmpls.get_(i));
        }
        m.put_("tmpls", ja2);
        _updateTemplOrds(m);
        save(m);
        return true;
    }


    public void _updateTemplOrds(JSONObject_ m) {
        JSONArray_ ja;
        ja = m.getJSONArray_("tmpls");
        for (int i = 0; i < ja.length(); i++) {
            JSONObject_ f = ja.getJSONObject_(i);
            f.put_("ord", i);
        }
    }


    public void moveTemplate(JSONObject_ m, JSONObject_ template, int idx) {
        JSONArray_ ja = m.getJSONArray_("tmpls");
        int oldidx = -1;
        ArrayList<JSONObject_> l = new ArrayList<>();
        HashMap<Integer, Integer> oldidxs = new HashMap<>();
        for (int i = 0; i < ja.length(); ++i) {
            if (ja.get_(i).equals(template)) {
                oldidx = i;
                if (idx == oldidx) {
                    return;
                }
            }
            JSONObject_ t = ja.getJSONObject_(i);
            oldidxs.put(t.hashCode(), t.getInt_("ord"));
            l.add(t);
        }
        l.remove(oldidx);
        l.add(idx, template);
        m.put_("tmpls", new JSONArray_(l));
        _updateTemplOrds(m);
        // generate change map - We use StringBuilder
        StringBuilder sb = new StringBuilder();
        ja = m.getJSONArray_("tmpls");
        for (int i = 0; i < ja.length(); ++i) {
            JSONObject_ t = ja.getJSONObject_(i);
            sb.append("when ord = ").append(oldidxs.get(t.hashCode())).append(" then ").append(t.getInt_("ord"));
            if (i != ja.length() - 1) {
                sb.append(" ");
            }
        }
        // apply
        save(m);
        mCol.getDb().execute("update cards set ord = (case " + sb.toString() +
                " end),usn=?,mod=? where nid in (select id from notes where mid = ?)",
                new Object[] { mCol.usn(), Utils.intTime(), m.getLong_("id") });
    }

    @SuppressWarnings("PMD.UnusedLocalVariable") // unused upstream as well
    private void _syncTemplates(JSONObject_ m) {
        ArrayList<Long> rem = mCol.genCards(Utils.arrayList2array(nids(m)));
    }


    /**
     * Model changing ***********************************************************************************************
     */

    /**
     * Change a model
     * @param m The model to change.
     * @param nids The list of notes that the change applies to.
     * @param newModel For replacing the old model with another one. Should be self if the model is not changing
     * @param fmap Map for switching fields. This is ord->ord and there should not be duplicate targets
     * @param cmap Map for switching cards. This is ord->ord and there should not be duplicate targets
     * @throws ConfirmModSchemaException 
     */
    public void change(JSONObject_ m, long[] nids, JSONObject_ newModel, Map<Integer, Integer> fmap, Map<Integer, Integer> cmap) throws ConfirmModSchemaException {
        mCol.modSchema(true);
        assert (newModel.getLong_("id") == m.getLong_("id")) || (fmap != null && cmap != null);
        if (fmap != null) {
            _changeNotes(nids, newModel, fmap);
        }
        if (cmap != null) {
            _changeCards(nids, m, newModel, cmap);
        }
        mCol.genCards(nids);
    }

    private void _changeNotes(long[] nids, JSONObject_ newModel, Map<Integer, Integer> map) {
        List<Object[]> d = new ArrayList<>();
        int nfields;
        long mid;
        nfields = newModel.getJSONArray_("flds").length();
        mid = newModel.getLong_("id");
        Cursor cur = null;
        try {
            cur = mCol.getDb().getDatabase().query(
                    "select id, flds from notes where id in ".concat(Utils.ids2str(nids)), null);
            while (cur.moveToNext()) {
                long nid = cur.getLong(0);
                String[] flds = Utils.splitFields(cur.getString(1));
                Map<Integer, String> newflds = new HashMap<>();

                for (Integer old : map.keySet()) {
                    newflds.put(map.get(old), flds[old]);
                }
                List<String> flds2 = new ArrayList<>();
                for (int c = 0; c < nfields; ++c) {
                    if (newflds.containsKey(c)) {
                        flds2.add(newflds.get(c));
                    } else {
                        flds2.add("");
                    }
                }
                String joinedFlds = Utils.joinFields(flds2.toArray(new String[flds2.size()]));
                d.add(new Object[] { joinedFlds, mid, Utils.intTime(), mCol.usn(), nid });
            }
        } finally {
            if (cur != null) {
                cur.close();
            }
        }
        mCol.getDb().executeMany("update notes set flds=?,mid=?,mod=?,usn=? where id = ?", d);
        mCol.updateFieldCache(nids);
    }

    private void _changeCards(long[] nids, JSONObject_ oldModel, JSONObject_ newModel, Map<Integer, Integer> map) {
        List<Object[]> d = new ArrayList<>();
        List<Long> deleted = new ArrayList<>();
        Cursor cur = null;
        int omType;
        int nmType;
        int nflds;
        omType = oldModel.getInt_("type");
        nmType = newModel.getInt_("type");
        nflds = newModel.getJSONArray_("tmpls").length();
        try {
            cur = mCol.getDb().getDatabase().query(
                    "select id, ord from cards where nid in ".concat(Utils.ids2str(nids)), null);
            while (cur.moveToNext()) {
                // if the src model is a cloze, we ignore the map, as the gui doesn't currently
                // support mapping them
                Integer newOrd;
                long cid = cur.getLong(0);
                int ord = cur.getInt(1);
                if (omType == Consts.MODEL_CLOZE) {
                    newOrd = cur.getInt(1);
                    if (nmType != Consts.MODEL_CLOZE) {
                        // if we're mapping to a regular note, we need to check if
                        // the destination ord is valid
                        if (nflds <= ord) {
                            newOrd = null;
                        }
                    }
                } else {
                    // mapping from a regular note, so the map should be valid
                    newOrd = map.get(ord);
                }
                if (newOrd != null) {
                    d.add(new Object[] { newOrd, mCol.usn(), Utils.intTime(), cid });
                } else {
                    deleted.add(cid);
                }
            }
        } finally {
            if (cur != null) {
                cur.close();
            }
        }
        mCol.getDb().executeMany("update cards set ord=?,usn=?,mod=? where id=?", d);
        mCol.remCards(Utils.toPrimitive(deleted));
    }

    /**
     * Schema hash ***********************************************************************************************
     */

    /** Return a hash of the schema, to see if models are compatible. */
    public String scmhash(JSONObject_ m) {
        String s = "";
        JSONArray_ flds = m.getJSONArray_("flds");
        for (int i = 0; i < flds.length(); ++i) {
            s += flds.getJSONObject_(i).getString_("name");
        }
        JSONArray_ tmpls = m.getJSONArray_("tmpls");
        for (int i = 0; i < tmpls.length(); ++i) {
            JSONObject_ t = tmpls.getJSONObject_(i);
            s += t.getString_("name");
       }
        return Utils.checksum(s);
    }


    /**
     * Required field/text cache
     * ***********************************************************************************************
     */

    private void _updateRequired(JSONObject_ m) {
        if (m.getInt_("type") == Consts.MODEL_CLOZE) {
            // nothing to do
            return;
        }
        JSONArray_ req = new JSONArray_();
        ArrayList<String> flds = new ArrayList<>();
        JSONArray_ fields;
        fields = m.getJSONArray_("flds");
        for (int i = 0; i < fields.length(); i++) {
            flds.add(fields.getJSONObject_(i).getString_("name"));
        }
        JSONArray_ templates = m.getJSONArray_("tmpls");
        for (int i = 0; i < templates.length(); i++) {
            JSONObject_ t = templates.getJSONObject_(i);
            Object[] ret = _reqForTemplate(m, flds, t);
            JSONArray_ r = new JSONArray_();
            r.put(t.getInt_("ord"));
            r.put(ret[0]);
            r.put(ret[1]);
            req.put(r);
        }
        m.put_("req", req);
    }

    @SuppressWarnings("PMD.UnusedLocalVariable") // 'String f' is unused upstream as well
    private Object[] _reqForTemplate(JSONObject_ m, ArrayList<String> flds, JSONObject_ t) {
        ArrayList<String> a = new ArrayList<>();
        ArrayList<String> b = new ArrayList<>();
        for (String f : flds) {
            a.add("ankiflag");
            b.add("");
        }
        Object[] data;
        data = new Object[] {1L, 1L, m.getLong_("id"), 1L, t.getInt_("ord"), "",
                Utils.joinFields(a.toArray(new String[a.size()])), 0};
        String full = mCol._renderQA(data).get("q");
        data = new Object[] {1L, 1L, m.getLong_("id"), 1L, t.getInt_("ord"), "",
                Utils.joinFields(b.toArray(new String[b.size()])), 0};
        String empty = mCol._renderQA(data).get("q");
        // if full and empty are the same, the template is invalid and there is no way to satisfy it
        if (full.equals(empty)) {
            return new Object[] { "none", new JSONArray_(), new JSONArray_() };
        }
        String type = "all";
        JSONArray_ req = new JSONArray_();
        ArrayList<String> tmp = new ArrayList<>();
        for (int i = 0; i < flds.size(); i++) {
            tmp.clear();
            tmp.addAll(a);
            tmp.set(i, "");
            data[6] = Utils.joinFields(tmp.toArray(new String[tmp.size()]));
            // if no field content appeared, field is required
            if (!mCol._renderQA(data).get("q").contains("ankiflag")) {
                req.put(i);
            }
        }
        if (req.length() > 0) {
            return new Object[] { type, req };
        }
        // if there are no required fields, switch to any mode
        type = "any";
        req = new JSONArray_();
        for (int i = 0; i < flds.size(); i++) {
            tmp.clear();
            tmp.addAll(b);
            tmp.set(i, "1");
            data[6] = Utils.joinFields(tmp.toArray(new String[tmp.size()]));
            // if not the same as empty, this field can make the card non-blank
            if (!mCol._renderQA(data).get("q").equals(empty)) {
                req.put(i);
            }
        }
        return new Object[] { type, req };
    }


    /** Given a joined field string, return available template ordinals */
    public ArrayList<Integer> availOrds(JSONObject_ m, String flds) {
        if (m.getInt_("type") == Consts.MODEL_CLOZE) {
            return _availClozeOrds(m, flds);
        }
        String[] fields = Utils.splitFields(flds);
        for (String f : fields) {
            f = f.trim();
        }
        ArrayList<Integer> avail = new ArrayList<>();
        JSONArray_ reqArray = m.getJSONArray_("req");
        for (int i = 0; i < reqArray.length(); i++) {
            JSONArray_ sr = reqArray.getJSONArray_(i);

            int ord = sr.getInt_(0);
            String type = sr.getString_(1);
            JSONArray_ req = sr.getJSONArray_(2);

            if ("none".equals(type)) {
                // unsatisfiable template
                continue;
            } else if ("all".equals(type)) {
                // AND requirement?
                boolean ok = true;
                for (int j = 0; j < req.length(); j++) {
                    int idx = req.getInt_(j);
                    if (fields[idx] == null || fields[idx].length() == 0) {
                        // missing and was required
                        ok = false;
                        break;
                    }
                }
                if (!ok) {
                    continue;
                }
            } else if ("any".equals(type)) {
                // OR requirement?
                boolean ok = false;
                for (int j = 0; j < req.length(); j++) {
                    int idx = req.getInt_(j);
                    if (fields[idx] != null && fields[idx].length() != 0) {
                        // missing and was required
                        ok = true;
                        break;
                    }
                }
                if (!ok) {
                    continue;
                }
            }
            avail.add(ord);
        }
        return avail;
    }


    public ArrayList<Integer> _availClozeOrds(JSONObject_ m, String flds) {
        return _availClozeOrds(m, flds, true);
    }


    public ArrayList<Integer> _availClozeOrds(JSONObject_ m, String flds, boolean allowEmpty) {
        String[] sflds = Utils.splitFields(flds);
        Map<String, Pair<Integer, JSONObject_>> map = fieldMap(m);
        Set<Integer> ords = new HashSet<>();
        List<String> matches = new ArrayList<>();
        Matcher mm;
        mm = fClozePattern1.matcher(m.getJSONArray_("tmpls").getJSONObject_(0).getString_("qfmt"));
        while (mm.find()) {
            matches.add(mm.group(1));
        }
        mm = fClozePattern2.matcher(m.getJSONArray_("tmpls").getJSONObject_(0).getString_("qfmt"));
        while (mm.find()) {
            matches.add(mm.group(1));
        }
        for (String fname : matches) {
            if (!map.containsKey(fname)) {
                continue;
            }
            int ord = map.get(fname).first;
            mm = fClozeOrdPattern.matcher(sflds[ord]);
            while (mm.find()) {
                ords.add(Integer.parseInt(mm.group(1)) - 1);
            }
        }
        if (ords.contains(-1)) {
            ords.remove(-1);
        }
        if (ords.isEmpty() && allowEmpty) {
            // empty clozes use first ord
            return new ArrayList<>(Arrays.asList(new Integer[]{0}));
        }
        return new ArrayList<>(ords);
    }


    /**
     * Sync handling ***********************************************************************************************
     */

    public void beforeUpload() {
        for (JSONObject_ m : all()) {
            m.put_("usn", 0);
        }
        save();
    }


    /**
     * Routines from Stdmodels.py
     * *
     * @throws ConfirmModSchemaException **********************************************************************************************
     */

    public static JSONObject_ addBasicModel(Collection col) throws ConfirmModSchemaException {
        return addBasicModel(col, "Basic");
    }


    public static JSONObject_ addBasicModel(Collection col, String name) throws ConfirmModSchemaException {
        Models mm = col.getModels();
        JSONObject_ m = mm.newModel(name);
        JSONObject_ fm = mm.newField("Front");
        mm.addField(m, fm);
        fm = mm.newField("Back");
        mm.addField(m, fm);
        JSONObject_ t = mm.newTemplate("Card 1");
        t.put_("qfmt", "{{Front}}");
        t.put_("afmt", "{{FrontSide}}\n\n<hr id=answer>\n\n{{Back}}");
        mm.addTemplate(m, t);
        mm.add(m);
        return m;
    }

    /* Forward & Reverse */

    public static JSONObject_ addForwardReverse(Collection col) throws ConfirmModSchemaException {
    	String name = "Basic (and reversed card)";
        Models mm = col.getModels();
        JSONObject_ m = addBasicModel(col);
        m.put_("name", name);
        JSONObject_ t = mm.newTemplate("Card 2");
        t.put_("qfmt", "{{Back}}");
        t.put_("afmt", "{{FrontSide}}\n\n<hr id=answer>\n\n{{Front}}");
        mm.addTemplate(m, t);
        return m;
    }


    /* Forward & Optional Reverse */

    public static JSONObject_ addForwardOptionalReverse(Collection col) throws ConfirmModSchemaException {
    	String name = "Basic (optional reversed card)";
        Models mm = col.getModels();
        JSONObject_ m = addBasicModel(col);
        m.put_("name", name);
        JSONObject_ fm = mm.newField("Add Reverse");
        mm.addField(m, fm);
        JSONObject_ t = mm.newTemplate("Card 2");
        t.put_("qfmt", "{{#Add Reverse}}{{Back}}{{/Add Reverse}}");
        t.put_("afmt", "{{FrontSide}}\n\n<hr id=answer>\n\n{{Front}}");
        mm.addTemplate(m, t);
        return m;
    }


    public static JSONObject_ addClozeModel(Collection col) throws ConfirmModSchemaException {
        Models mm = col.getModels();
        JSONObject_ m = mm.newModel("Cloze");
        m.put_("type", Consts.MODEL_CLOZE);
        String txt = "Text";
        JSONObject_ fm = mm.newField(txt);
        mm.addField(m, fm);
        fm = mm.newField("Extra");
        mm.addField(m, fm);
        JSONObject_ t = mm.newTemplate("Cloze");
        String fmt = "{{cloze:" + txt + "}}";
        m.put_("css", m.getString_("css") + ".cloze {" + "font-weight: bold;" + "color: blue;" + "}");
        t.put_("qfmt", fmt);
        t.put_("afmt", fmt + "<br>\n{{Extra}}");
        mm.addTemplate(m, t);
        mm.add(m);
        return m;
    }


    /**
     * Other stuff NOT IN LIBANKI
     * ***********************************************************************************************
     */

    public void setChanged() {
        mChanged = true;
    }


    public HashMap<Long, HashMap<Integer, String>> getTemplateNames() {
        HashMap<Long, HashMap<Integer, String>> result = new HashMap<>();
        for (JSONObject_ m : mModels.values()) {
            JSONArray_ templates;
            templates = m.getJSONArray_("tmpls");
            HashMap<Integer, String> names = new HashMap<>();
            for (int i = 0; i < templates.length(); i++) {
                JSONObject_ t = templates.getJSONObject_(i);
                names.put(t.getInt_("ord"), t.getString_("name"));
            }
            result.put(m.getLong_("id"), names);
        }
        return result;
    }


    /**
     * @return the ID
     */
    public int getId() {
        return mId;
    }


    /**
     * @return the name
     */
    public String getName() {
        return mName;
    }


    public HashMap<Long, JSONObject_> getModels() {
        return mModels;
    }

    /** Validate model entries. */
	public boolean validateModel() {
        for (Entry<Long, JSONObject_> longJSONObject_Entry : mModels.entrySet()) {
            if (!validateBrackets(longJSONObject_Entry.getValue())) {
                return false;
            }
        }
		return true;
	}

	/** Check if there is a right bracket for every left bracket. */
	private boolean validateBrackets(JSONObject_ value) {
		String s = value.toString();
		int count = 0;
		boolean inQuotes = false;
		char[] ar = s.toCharArray();
		for (int i = 0; i < ar.length; i++) {
			char c = ar[i];
			// if in quotes, do not count
			if (c == '"' && (i == 0 || (ar[i-1] != '\\'))) {
				inQuotes = !inQuotes;
				continue;
			}
			if (inQuotes) {
				continue;
			}
			switch(c) {
			case '{':
				count++;
				break;
			case '}':
				count--;
				if (count < 0) {
					return false;
				}
				break;
			}
		}
		return (count == 0);
	}
}
