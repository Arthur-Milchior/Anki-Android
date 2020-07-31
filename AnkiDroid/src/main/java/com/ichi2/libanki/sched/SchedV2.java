/****************************************************************************************
 * Copyright (c) 2011 Norbert Nagold <norbert.nagold@gmail.com>                         *
 * Copyright (c) 2012 Kostas Spyropoulos <inigo.aldana@gmail.com>                       *
 * Copyright (c) 2013 Houssam Salem <houssam.salem.au@gmail.com>                        *
 * Copyright (c) 2018 Chris Williams <chris@chrispwill.com>                             *
 *                                                                                      *
 * This program is free software; you can redistribute it and/or modify it under        *
 * the terms of the GNU General private License as published by the Free Software       *
 * Foundation; either version 3 of the License, or (at your option) any later           *
 * version.                                                                             *
 *                                                                                      *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY      *
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A      *
 * PARTICULAR PURPOSE. See the GNU General private License for more details.            *
 *                                                                                      *
 * You should have received a copy of the GNU General private License along with        *
 * this program.  If not, see <http://www.gnu.org/licenses/>.                           *
 ****************************************************************************************/

package com.ichi2.libanki.sched;

import android.app.Activity;

import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteConstraintException;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.util.Pair;

import com.ichi2.anki.R;
import com.ichi2.async.CollectionTask;
import com.ichi2.libanki.Card;
import com.ichi2.libanki.Collection;
import com.ichi2.libanki.Consts;
import com.ichi2.libanki.Decks;
import com.ichi2.libanki.Note;
import com.ichi2.libanki.Utils;
import com.ichi2.libanki.Deck;
import com.ichi2.libanki.DeckConfig;

import com.ichi2.libanki.utils.SystemTime;
import com.ichi2.libanki.utils.Time;
import com.ichi2.utils.Assert;
import com.ichi2.utils.JSONArray;
import com.ichi2.utils.JSONException;
import com.ichi2.utils.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Random;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.sqlite.db.SupportSQLiteDatabase;
import timber.log.Timber;

import static com.ichi2.libanki.sched.AbstractSched.UnburyType.*;

@SuppressWarnings({"PMD.ExcessiveClassLength", "PMD.AvoidThrowingRawExceptionTypes","PMD.AvoidReassigningParameters",
                    "PMD.NPathComplexity","PMD.MethodNamingConventions","PMD.AvoidBranchingStatementAsLastInLoop",
                    "PMD.SwitchStmtsShouldHaveDefault","PMD.CollapsibleIfStatements","PMD.EmptyIfStmt"})
public class SchedV2 extends AbstractSched {



    // Not in libanki
    private static final int[] FACTOR_ADDITION_VALUES = { -150, 0, 150 };

    private String mName = "std2";
    /* The next card that will be sent to the reviewer. I.e. the result of a second call to getCard, which is not the
     * current card nor a sibling.
     */

    // Not in libAnki.
    private final Time mTime;

    /**
     * card types: 0=new, 1=lrn, 2=rev, 3=relrn
     * queue types: 0=new, 1=(re)lrn, 2=rev, 3=day (re)lrn,
     *   4=preview, -1=suspended, -2=sibling buried, -3=manually buried
     * revlog types: 0=lrn, 1=rev, 2=relrn, 3=early review
     * positive revlog intervals are in days (rev), negative in seconds (lrn)
     * odue/odid store original due/did when cards moved to filtered deck
     *
     */

    public SchedV2(Collection col) {
        this(col, new SystemTime());
    }

    /** we need a constructor as the constructor performs work
     * involving the dependency, so we can't use setter injection */
    @VisibleForTesting
    SchedV2(Collection col, Time time) {
        super();
        this.mTime = time;
        mCol = col;
        mQueueLimit = 50;
        mReportLimit = 99999;
        mDynReportLimit = 99999;
        mReps = 0;
        mToday = null;
        mHaveQueues = false;
        mLrnCutoff = 0;
        _updateCutoff();
    }

    protected long intTime() {
        return mTime.intTime();
    }
    protected double now() {
        return mTime.now();
    }

    public void answerCard(Card card, int ease) {
        mCol.log();
        discardCurrentCard();
        mCol.markReview(card);
        _burySiblings(card);

        _answerCard(card, ease);

        _updateStats(card, "time", card.timeTaken());
        card.setMod(intTime());
        card.setUsn(mCol.usn());
        card.flushSched();
    }


    public void _answerCard(Card card, int ease) {
        if (_previewingCard(card)) {
            _answerCardPreview(card, ease);
            return;
        }

        card.setReps(card.getReps() + 1);

        if (card.getQueue() == Consts.QUEUE_TYPE_NEW) {
            // came from the new queue, move to learning
            card.setQueue(Consts.QUEUE_TYPE_LRN);
            card.setType(Consts.CARD_TYPE_LRN);
            // init reps to graduation
            card.setLeft(_startingLeft(card));
            // update daily limit
            _updateStats(card, "new");
        }
        if (card.getQueue() == Consts.QUEUE_TYPE_LRN || card.getQueue() == Consts.QUEUE_TYPE_DAY_LEARN_RELEARN) {
            _answerLrnCard(card, ease);
        } else if (card.getQueue() == Consts.QUEUE_TYPE_REV) {
            _answerRevCard(card, ease);
            // Update daily limit
            _updateStats(card, "rev");
        } else {
            throw new RuntimeException("Invalid queue");
        }

        // once a card has been answered once, the original due date
        // no longer applies
        if (card.getODue() > 0) {
            card.setODue(0);
        }
    }


    public void _answerCardPreview(Card card, int ease) {
        if (ease == Consts.BUTTON_ONE) {
            // Repeat after delay
            card.setQueue(Consts.QUEUE_TYPE_PREVIEW);
            card.setDue(mTime.intTime() + _previewDelay(card));
            mLrnCount += 1;
        } else if (ease == Consts.BUTTON_TWO) {
            // Restore original card state and remove from filtered deck
            _restorePreviewCard(card);
            _removeFromFiltered(card);
        } else {
            // This is in place of the assert
            throw new RuntimeException("Invalid ease");
        }
    }


    public int[] counts(@NonNull Card card) {
        int[] counts = counts();
        int idx = countIdx(card);
        counts[idx] += 1;
        return counts;
    }


    public int countIdx(Card card) {
        if (card.getQueue() == Consts.QUEUE_TYPE_DAY_LEARN_RELEARN || card.getQueue() == Consts.QUEUE_TYPE_PREVIEW) {
            return Consts.QUEUE_TYPE_LRN;
        }
        return card.getQueue();
    }


    public int answerButtons(Card card) {
        DeckConfig conf = _cardConf(card);
        if (card.getODid() != 0 && !conf.getBoolean("resched")) {
            return 2;
        }
        return 4;
    }


    /**
     * Rev/lrn/time daily stats *************************************************
     * **********************************************
     */

    protected void _updateStats(Card card, String type) {
        _updateStats(card, type, 1);
    }





    public void extendLimits(int newc, int rev) {
        Deck cur = mCol.getDecks().current();
        ArrayList<Deck> decks = new ArrayList<>();
        decks.add(cur);
        decks.addAll(mCol.getDecks().parents(cur.getLong("id")));
        for (long did : mCol.getDecks().children(cur.getLong("id")).values()) {
            decks.add(mCol.getDecks().get(did));
        }
        for (Deck g : decks) {
            // add
            JSONArray ja = g.getJSONArray("newToday");
            ja.put(1, ja.getInt(1) - newc);
            ja = g.getJSONArray("revToday");
            ja.put(1, ja.getInt(1) - rev);
            mCol.getDecks().save(g);
        }
    }


    /**
     * Deck list **************************************************************** *******************************
     */

    public List<DeckDueTreeNode> deckDueList(CollectionTask collectionTask) {
        _checkDay();
        mCol.getDecks().checkIntegrity();
        ArrayList<Deck> decks = mCol.getDecks().allSorted();
        HashMap<String, Integer[]> lims = new HashMap<>();
        ArrayList<DeckDueTreeNode> data = new ArrayList<>();
        HashMap<Long, HashMap> childMap = mCol.getDecks().childMap();
        for (Deck deck : decks) {
            if (collectionTask != null && collectionTask.isCancelled()) {
                return null;
            }
            String deckName = deck.getString("name");
            String p = Decks.parent(deckName);
            // new
            int nlim = _deckNewLimitSingle(deck);
            Integer plim = null;
            if (!TextUtils.isEmpty(p)) {
                Integer[] parentLims = lims.get(p);
                // 'temporary for diagnosis of bug #6383'
                Assert.that(parentLims != null, "Deck %s is supposed to have parent %s. It has not be found.", deckName, p);
                nlim = Math.min(nlim, parentLims[0]);
                // reviews
                plim = parentLims[1];
            }
            int _new = _newForDeck(deck.getLong("id"), nlim);
            // learning
            int lrn = _lrnForDeck(deck.getLong("id"));
            // reviews
            int rlim = _deckRevLimitSingle(deck, plim);
            int rev = _revForDeck(deck.getLong("id"), rlim, childMap);
            // save to list
            data.add(new DeckDueTreeNode(mCol, deck.getString("name"), deck.getLong("id"), rev, lrn, _new));
            // add deck as a parent
            lims.put(deck.getString("name"), new Integer[]{nlim, rlim});
        }
        return data;
    }


    /**
     * Getting the next card ****************************************************
     * *******************************************
     */

    protected boolean dayLearnFirst() {
        return mCol.getConf().optBoolean("dayLearnFirst", false);
    }


    /**
     * New cards **************************************************************** *******************************
     */


    // Used as an argument for _walkingCount() in _resetNewCount() above
    @SuppressWarnings("unused")
    @Override
    protected int _cntFnNew(long did, int lim) {
        return mCol.getDb().queryScalar(
                "SELECT count() FROM (SELECT 1 FROM cards WHERE did = ? AND queue = " + Consts.QUEUE_TYPE_NEW + " LIMIT ?)",
                did, lim);
    }

    /**
     * Learning queues *********************************************************** ************************************
     */

    @Override
    protected boolean _updateLrnCutoff(boolean force) {
        long nextCutoff = mTime.intTime() + mCol.getConf().getInt("collapseTime");
        if (nextCutoff - mLrnCutoff > 60 || force) {
            mLrnCutoff = nextCutoff;
            return true;
        }
        return false;
    }


    private void _maybeResetLrn(boolean force) {
        if (_updateLrnCutoff(force)) {
            _resetLrn();
        }
    }


    protected void _resetLrnCount() {
        // sub-day
        mLrnCount = mCol.getDb().queryScalar(
                "SELECT count() FROM cards WHERE did IN " + _deckLimit()
                + " AND queue = " + Consts.QUEUE_TYPE_LRN + " AND due < ?", mLrnCutoff);

        // day
        mLrnCount += mCol.getDb().queryScalar(
                "SELECT count() FROM cards WHERE did IN " + _deckLimit() + " AND queue = " + Consts.QUEUE_TYPE_DAY_LEARN_RELEARN + " AND due <= ?",
                mToday);

        // previews
        mLrnCount += mCol.getDb().queryScalar(
                "SELECT count() FROM cards WHERE did IN " + _deckLimit() + " AND queue = " + Consts.QUEUE_TYPE_PREVIEW);
    }


    @Override
    protected void _resetLrn() {
        _updateLrnCutoff(true);
        _resetLrnCount();
        mLrnQueue.clear();
        mLrnDayQueue.clear();
        mLrnDids = mCol.getDecks().active();
    }


    // sub-day learning
    protected boolean _fillLrn() {
        return _fillLrn(mTime.intTime() + mCol.getConf().getLong("collapseTime"), "queue IN (" + Consts.QUEUE_TYPE_LRN + ", " + Consts.QUEUE_TYPE_PREVIEW);
    }
    protected boolean _fillLrn(long cutoff, String queueQuery) {
        if (mLrnCount == 0) {
            return false;
        }
        if (!mLrnQueue.isEmpty()) {
            return true;
        }
        Cursor cur = null;
        mLrnQueue.clear();
        try {
            /* Difference with upstream: Current card can't come in the queue.
             *
             * In standard usage, a card is not requested before the previous card is marked as reviewed. However, if we
             * decide to query a second card sooner, we don't want to get the same card a second time. This simulate
             * _getLrnCard which did remove the card from the queue. _sortIntoLrn will add the card back to the queue if
             * required when the card is reviewed.
             */
            cur = mCol.getDb().getDatabase().query(
                    "SELECT due, id FROM cards WHERE did IN " + _deckLimit() + " AND " + queueQuery + " AND due < ? AND id != ? LIMIT ?",
                    new Object[] { cutoff, currentCardId(), mReportLimit});
            while (cur.moveToNext()) {
                mLrnQueue.add(new LrnCard(cur.getLong(0), cur.getLong(1)));
            }
            // as it arrives sorted by did first, we need to sort it
            Collections.sort(mLrnQueue);
            return !mLrnQueue.isEmpty();
        } finally {
            if (cur != null && !cur.isClosed()) {
                cur.close();
            }
        }
    }


    @Override
    protected Card _getLrnCard(boolean collapse) {
        _maybeResetLrn(collapse && mLrnCount == 0);
        if (_fillLrn()) {
            double cutoff = now();
            if (collapse) {
                cutoff += mCol.getConf().getInt("collapseTime");
            }
            if (mLrnQueue.getFirst().getDue() < cutoff) {
                long id = mLrnQueue.remove().getId();
                Card card = mCol.getCard(id);
                // mLrnCount -= 1; see decrementCounts()
                return card;
            }
        }
        return null;
    }


    protected void _answerLrnCard(Card card, int ease) {
        JSONObject conf = _lrnConf(card);
        int type;
        if (card.getType() == Consts.CARD_TYPE_REV || card.getType() == Consts.CARD_TYPE_RELEARNING) {
            type = Consts.CARD_TYPE_REV;
        } else {
            type = 0;
        }

        // lrnCount was decremented once when card was fetched
        int lastLeft = card.getLeft();
        boolean leaving = false;

        // immediate graduate?
        if (ease == Consts.BUTTON_FOUR) {
            _rescheduleAsRev(card, conf, true);
            leaving = true;
        // next step?
        } else if (ease == Consts.BUTTON_THREE) {
            // graduation time?
            if ((card.getLeft() % 1000) - 1 <= 0) {
                _rescheduleAsRev(card, conf, false);
                leaving = true;
            } else {
                _moveToNextStep(card, conf);
            }
        } else if (ease == Consts.BUTTON_TWO) {
            _repeatStep(card, conf);
        } else {
            // move back to first step
            _moveToFirstStep(card, conf);
        }
        _logLrn(card, ease, conf, leaving, type, lastLeft);
    }


    protected void _updateRevIvlOnFail(Card card, JSONObject conf) {
        card.setLastIvl(card.getIvl());
        card.setIvl(_lapseIvl(card, conf));
    }


    private int _moveToFirstStep(Card card, JSONObject conf) {
        card.setLeft(_startingLeft(card));

        // relearning card?
        if (card.getType() == Consts.CARD_TYPE_RELEARNING) {
            _updateRevIvlOnFail(card, conf);
        }

        return _rescheduleLrnCard(card, conf);
    }


    private void _moveToNextStep(Card card, JSONObject conf) {
        // decrement real left count and recalculate left today
        int left = (card.getLeft() % 1000) - 1;
        card.setLeft(_leftToday(conf.getJSONArray("delays"), left) * 1000 + left);

        _rescheduleLrnCard(card, conf);
    }


    private void _repeatStep(Card card, JSONObject conf) {
        int delay = _delayForRepeatingGrade(conf, card.getLeft());
        _rescheduleLrnCard(card, conf, delay);
    }


    private int _rescheduleLrnCard(Card card, JSONObject conf) {
        return _rescheduleLrnCard(card, conf, null);
    }


    private int _rescheduleLrnCard(Card card, JSONObject conf, Integer delay) {
        // normal delay for the current step?
        if (delay == null) {
            delay = _delayForGrade(conf, card.getLeft());
        }
        card.setDue(mTime.intTime() + delay);

        // due today?
        if (card.getDue() < mDayCutoff) {
            // Add some randomness, up to 5 minutes or 25%
            int maxExtra = (int) Math.min(300, (int)(delay * 0.25));
            int fuzz = new Random().nextInt(maxExtra);
            card.setDue(Math.min(mDayCutoff - 1, card.getDue() + fuzz));
            card.setQueue(Consts.QUEUE_TYPE_LRN);
            if (card.getDue() < (mTime.intTime() + mCol.getConf().getInt("collapseTime"))) {
                mLrnCount += 1;
                // if the queue is not empty and there's nothing else to do, make
                // sure we don't put it at the head of the queue and end up showing
                // it twice in a row
                if (!mLrnQueue.isEmpty() && mRevCount == 0 && mNewCount == 0) {
                    long smallestDue = mLrnQueue.getFirst().getDue();
                    card.setDue(Math.max(card.getDue(), smallestDue + 1));
                }
                _sortIntoLrn(card.getDue(), card.getId());
            }
        } else {
            // the card is due in one or more days, so we need to use the day learn queue
            long ahead = ((card.getDue() - mDayCutoff) / 86400) + 1;
            card.setDue(mToday + ahead);
            card.setQueue(Consts.QUEUE_TYPE_DAY_LEARN_RELEARN);
        }
        return delay;
    }


    protected int _delayForGrade(JSONObject conf, int left) {
        left = left % 1000;
        try {
            double delay;
            JSONArray ja = conf.getJSONArray("delays");
            int len = ja.length();
            try {
                delay = ja.getDouble(len - left);
            } catch (JSONException e) {
                if (conf.getJSONArray("delays").length() > 0) {
                    delay = conf.getJSONArray("delays").getDouble(0);
                } else {
                    // user deleted final step; use dummy value
                    delay = 1.0;
                }
            }
            return (int) (delay * 60.0);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }


    private int  _delayForRepeatingGrade(JSONObject conf, int left) {
        // halfway between last and  next
        int delay1 = _delayForGrade(conf, left);
        int delay2;
        if (conf.getJSONArray("delays").length() > 1) {
            delay2 = _delayForGrade(conf, left - 1);
        } else {
            delay2 = delay1 * 2;
        }
        int avg = (delay1 + Math.max(delay1, delay2)) / 2;
        return avg;
    }


    protected JSONObject _lrnConf(Card card) {
        if (card.getType() == Consts.CARD_TYPE_REV || card.getType() == Consts.CARD_TYPE_RELEARNING) {
            return _lapseConf(card);
        } else {
            return _newConf(card);
        }
    }


    protected void _rescheduleAsRev(Card card, JSONObject conf, boolean early) {
        boolean lapse = (card.getType() == Consts.CARD_TYPE_REV || card.getType() == Consts.CARD_TYPE_RELEARNING);
        if (lapse) {
            _rescheduleGraduatingLapse(card, early);
        } else {
            _rescheduleNew(card, conf, early);
        }
        // if we were dynamic, graduating means moving back to the old deck
        if (card.getODid() != 0) {
            _removeFromFiltered(card);
        }
    }

    private void _rescheduleGraduatingLapse(Card card, boolean early) {
        if (early) {
            card.setIvl(card.getIvl() + 1);
        }
        card.setDue(mToday + card.getIvl());
        card.setQueue(Consts.QUEUE_TYPE_REV);
        card.setType(Consts.CARD_TYPE_REV);
    }


    protected int _startingLeft(Card card) {
        JSONObject conf;
        if (card.getType() == Consts.CARD_TYPE_RELEARNING) {
            conf = _lapseConf(card);
        } else {
            conf = _lrnConf(card);
        }
        int tot = conf.getJSONArray("delays").length();
        int tod = _leftToday(conf.getJSONArray("delays"), tot);
        return tot + tod * 1000;
    }


    /** the number of steps that can be completed by the day cutoff */
    protected int _leftToday(JSONArray delays, int left) {
        return _leftToday(delays, left, 0);
    }


    private int _leftToday(JSONArray delays, int left, long now) {
        if (now == 0) {
            now = mTime.intTime();
        }
        int ok = 0;
        int offset = Math.min(left, delays.length());
        for (int i = 0; i < offset; i++) {
            now += (int) (delays.getDouble(delays.length() - offset + i) * 60.0);
            if (now > mDayCutoff) {
                break;
            }
            ok = i;
        }
        return ok + 1;
    }


    private int _graduatingIvl(Card card, JSONObject conf, boolean early) {
        return _graduatingIvl(card, conf, early, true);
    }


    private int _graduatingIvl(Card card, JSONObject conf, boolean early, boolean fuzz) {
        if (card.getType() == Consts.CARD_TYPE_REV || card.getType() == Consts.CARD_TYPE_RELEARNING) {
            int bonus = early ? 1 : 0;
            return card.getIvl() + bonus;
        }
        int ideal;
        JSONArray ja;
        ja = conf.getJSONArray("ints");
        if (!early) {
            // graduate
            ideal = ja.getInt(0);
        } else {
            // early remove
            ideal = ja.getInt(1);
        }
        if (fuzz) {
            ideal = _fuzzedIvl(ideal);
        }
        return ideal;
    }


    /** Reschedule a new card that's graduated for the first time. */
    private void _rescheduleNew(Card card, JSONObject conf, boolean early) {
        card.setIvl(_graduatingIvl(card, conf, early));
        card.setDue(mToday + card.getIvl());
        card.setFactor(conf.getInt("initialFactor"));
        card.setType(Consts.CARD_TYPE_REV);
        card.setQueue(Consts.QUEUE_TYPE_REV);
    }


    private void _logLrn(Card card, int ease, JSONObject conf, boolean leaving, int type, int lastLeft) {
        int lastIvl = -(_delayForGrade(conf, lastLeft));
        int ivl = leaving ? card.getIvl() : -(_delayForGrade(conf, card.getLeft()));
        log(card.getId(), mCol.usn(), ease, ivl, lastIvl, card.getFactor(), card.timeTaken(), type);
    }


    private void log(long id, int usn, int ease, int ivl, int lastIvl, int factor, int timeTaken, int type) {
        try {
            mCol.getDb().execute("INSERT INTO revlog VALUES (?,?,?,?,?,?,?,?,?)",
                    now() * 1000, id, usn, ease, ivl, lastIvl, factor, timeTaken, type);
        } catch (SQLiteConstraintException e) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e1) {
                throw new RuntimeException(e1);
            }
            log(id, usn, ease, ivl, lastIvl, factor, timeTaken, type);
        }
    }


    private int _lrnForDeck(long did) {
        try {
            int cnt = mCol.getDb().queryScalar(
                    "SELECT count() FROM (SELECT null FROM cards WHERE did = ?"
                            + " AND queue = " + Consts.QUEUE_TYPE_LRN + " AND due < ?"
                            + " LIMIT ?)",
                    did, (intTime() + mCol.getConf().getInt("collapseTime")), mReportLimit);
            return cnt + mCol.getDb().queryScalar(
                    "SELECT count() FROM (SELECT null FROM cards WHERE did = ?"
                            + " AND queue = " + Consts.QUEUE_TYPE_DAY_LEARN_RELEARN + " AND due <= ?"
                            + " LIMIT ?)",
                    did, mToday, mReportLimit);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * Reviews ****************************************************************** *****************************
     */

    private int _currentRevLimit() {
        Deck d = mCol.getDecks().get(mCol.getDecks().selected(), false);
        return _deckRevLimitSingle(d);
    }


    protected int _deckRevLimitSingle(Deck d) {
        return _deckRevLimitSingle(d, null);
    }


    private int _deckRevLimitSingle(Deck d, Integer parentLimit) {
        // invalid deck selected?
        if (d == null) {
            return 0;
        }
        if (d.getInt("dyn") != 0) {
            return mDynReportLimit;
        }
        long did = d.getLong("id");
        DeckConfig c = mCol.getDecks().confForDid(did);
        int lim = Math.max(0, c.getJSONObject("rev").getInt("perDay") - d.getJSONArray("revToday").getInt(1));
        if (currentCardIsInQueueWithDeck(Consts.QUEUE_TYPE_REV, did)) {
            lim --;
        }

        if (parentLimit != null) {
            return Math.min(parentLimit, lim);
        } else if (!d.getString("name").contains("::")) {
            return lim;
        } else {
            for (Deck parent : mCol.getDecks().parents(did)) {
                // pass in dummy parentLimit so we don't do parent lookup again
                lim = Math.min(lim, _deckRevLimitSingle(parent, lim));
            }
            return lim;
        }
    }


    protected int _revForDeck(long did, int lim, HashMap<Long, HashMap> childMap) {
        List<Long> dids = mCol.getDecks().childDids(did, childMap);
        dids.add(0, did);
        lim = Math.min(lim, mReportLimit);
        return mCol.getDb().queryScalar("SELECT count() FROM (SELECT 1 FROM cards WHERE did in " + Utils.ids2str(dids) + " AND queue = " + Consts.QUEUE_TYPE_REV + " AND due <= ? LIMIT ?)",
                                        mToday, lim);
    }


    /** Same as _resetRev, but assume discardCard is currently in the reviewer and so don't conunt it.*/
    protected void _resetRev(@Nullable Card discardCard) {
        _resetRev();
        if (discardCard != null && discardCard.getQueue() == Consts.QUEUE_TYPE_REV) {
            mRevCount--;
        }
    }

    @Override
    protected void _resetRevCount() {
        int lim = _currentRevLimit();
        mRevCount = mCol.getDb().queryScalar("SELECT count() FROM (SELECT id FROM cards WHERE did in " + _deckLimit() + " AND queue = " + Consts.QUEUE_TYPE_REV + " AND due <= ? LIMIT ?)",
                                             mToday, lim);
    }


    @Override
    protected boolean _fillRev(boolean allowSibling) {
        if (!mRevQueue.isEmpty()) {
            return true;
        }
        if (mRevCount == 0) {
            return false;
        }
        int lim = Math.min(mQueueLimit, _currentRevLimit());
        SupportSQLiteDatabase db = mCol.getDb().getDatabase();
        if (lim != 0) {
            Cursor cur = null;
            mRevQueue.clear();
            // fill the queue with the current did
            try {
                /* Difference with upstream: we take current card into account.
                 *
                 * When current card is answered, the card is not due anymore, so does not belong to the queue.
                 * Furthermore, _burySiblings ensure that the siblings of the current cards are removed from the queue
                 * to ensure same day spacing. We simulate this action by ensuring that those siblings are not filled,
                 * except if we know there are cards and we didn't find any non-sibling card. This way, the queue is not
                 * empty if it should not be empty (important for the conditional belows), but the front of the queue
                 * contains distinct card.
                 */
                // fill the queue with the current did
                String idName = (allowSibling) ? "id": "nid";
                long id = (allowSibling) ? currentCardId(): currentCardNid();
                cur = db.query("SELECT id FROM cards WHERE did in " + _deckLimit() + " AND queue = " + Consts.QUEUE_TYPE_REV + " AND due <= ? AND " + idName + " != ?"
                               + " ORDER BY due, random()  LIMIT ?",
                               new Object[]{mToday, id, lim});
                while (cur.moveToNext()) {
                    mRevQueue.add(cur.getLong(0));
                }
            } finally {
                if (cur != null && !cur.isClosed()) {
                    cur.close();
                }
            }
            if (!mRevQueue.isEmpty()) {
                // preserve order
                // Note: libanki reverses mRevQueue and returns the last element in _getRevCard().
                // AnkiDroid differs by leaving the queue intact and returning the *first* element
                // in _getRevCard().
                return true;
            }
        }
        // since we didn't get a card and the count is non-zero, we
        // need to check again for any cards that were removed from
        // the queue but not buried
        _resetRev(mCurrentCard);
        return _fillRev(true);
    }


    /**
     * Answering a review card **************************************************
     * *********************************************
     */

    protected void _answerRevCard(Card card, int ease) {
        int delay = 0;
        boolean early = card.getODid() != 0 && (card.getODue() > mToday);
        int type = early ? 3 : 1;
        if (ease == Consts.BUTTON_ONE) {
            delay = _rescheduleLapse(card);
        } else {
            _rescheduleRev(card, ease, early);
        }
        _logRev(card, ease, delay, type);
    }


    protected int _rescheduleLapse(Card card) {
        JSONObject conf;
        conf = _lapseConf(card);
        card.setLapses(card.getLapses() + 1);
        card.setFactor(Math.max(1300, card.getFactor() - 200));
        int delay;
         boolean suspended = _checkLeech(card, conf) && card.getQueue() == Consts.QUEUE_TYPE_SUSPENDED;
        if (conf.getJSONArray("delays").length() != 0 && !suspended) {
            card.setType(Consts.CARD_TYPE_RELEARNING);
            delay = _moveToFirstStep(card, conf);
        } else {
            // no relearning steps
            _updateRevIvlOnFail(card, conf);
            _rescheduleAsRev(card, conf, false);
            // need to reset the queue after rescheduling
            if (suspended) {
                card.setQueue(Consts.QUEUE_TYPE_SUSPENDED);
            }
            delay = 0;
        }

        return delay;
    }


    private int _lapseIvl(Card card, JSONObject conf) {
        int ivl = Math.max(1, Math.max(conf.getInt("minInt"), (int)(card.getIvl() * conf.getDouble("mult"))));
        return ivl;
    }


    protected void _rescheduleRev(Card card, int ease, boolean early) {
        // update interval
        card.setLastIvl(card.getIvl());
        if (early) {
            _updateEarlyRevIvl(card, ease);
        } else {
            _updateRevIvl(card, ease);
        }

        // then the rest
        card.setFactor(Math.max(1300, card.getFactor() + FACTOR_ADDITION_VALUES[ease - 2]));
        card.setDue(mToday + card.getIvl());

        // card leaves filtered deck
        _removeFromFiltered(card);
    }


    protected void _logRev(Card card, int ease, int delay, int type) {
        log(card.getId(), mCol.usn(), ease, ((delay != 0) ? (-delay) : card.getIvl()), card.getLastIvl(),
                card.getFactor(), card.timeTaken(), type);
    }


    /**
     * Interval management ******************************************************
     * *****************************************
     */

    /**
     * Next interval for CARD, given EASE.
     */
    protected int _nextRevIvl(Card card, int ease, boolean fuzz) {
        long delay = _daysLate(card);
        JSONObject conf = _revConf(card);
        double fct = card.getFactor() / 1000.0;
        double hardFactor = conf.optDouble("hardFactor", 1.2);
        int hardMin;
        if (hardFactor > 1) {
            hardMin = card.getIvl();
        } else {
            hardMin = 0;
        }

        int ivl2 = _constrainedIvl(card.getIvl() * hardFactor, conf, hardMin, fuzz);
        if (ease == Consts.BUTTON_TWO) {
            return ivl2;
        }

        int ivl3 = _constrainedIvl((card.getIvl() + delay / 2) * fct, conf, ivl2, fuzz);
        if (ease == Consts.BUTTON_THREE) {
            return ivl3;
        }

        int ivl4 = _constrainedIvl((
                                    (card.getIvl() + delay) * fct * conf.getDouble("ease4")), conf, ivl3, fuzz);
        return ivl4;
    }

    protected int _fuzzedIvl(int ivl) {
        Pair<Integer, Integer> minMax = _fuzzIvlRange(ivl);
        // Anki's python uses random.randint(a, b) which returns x in [a, b] while the eq Random().nextInt(a, b)
        // returns x in [0, b-a), hence the +1 diff with libanki
        return (new Random().nextInt(minMax.second - minMax.first + 1)) + minMax.first;
    }


    protected int _constrainedIvl(double ivl, JSONObject conf, double prev, boolean fuzz) {
        int newIvl = (int) (ivl * conf.optDouble("ivlFct", 1));
        if (fuzz) {
            newIvl = _fuzzedIvl(newIvl);
        }

        newIvl = (int) Math.max(Math.max(newIvl, prev + 1), 1);
        newIvl = Math.min(newIvl, conf.getInt("maxIvl"));

        return newIvl;
    }


    /**
     * Number of days later than scheduled.
     */
    protected long _daysLate(Card card) {
        long due = card.getODid() != 0 ? card.getODue() : card.getDue();
        return Math.max(0, mToday - due);
    }


    protected void _updateRevIvl(Card card, int ease) {
        card.setIvl(_nextRevIvl(card, ease, true));
    }


    private void _updateEarlyRevIvl(Card card, int ease) {
        card.setIvl(_earlyReviewIvl(card, ease));
    }


    /** next interval for card when answered early+correctly */
    private int _earlyReviewIvl(Card card, int ease) {
        if (card.getODid() == 0 || card.getType() != Consts.CARD_TYPE_REV || card.getFactor() == 0) {
            throw new RuntimeException("Unexpected card parameters");
        }
        if (ease <= 1) {
            throw new RuntimeException("Ease must be greater than 1");
        }

        long elapsed = card.getIvl() - (card.getODue() - mToday);

        JSONObject conf = _revConf(card);

        double easyBonus = 1;
        // early 3/4 reviews shouldn't decrease previous interval
        double minNewIvl = 1;

        double factor;
        if (ease == Consts.BUTTON_TWO)  {
            factor = conf.optDouble("hardFactor", 1.2);
            // hard cards shouldn't have their interval decreased by more than 50%
            // of the normal factor
            minNewIvl = factor / 2;
        } else if (ease == 3) {
            factor = card.getFactor() / 1000;
        } else { // ease == 4
            factor = card.getFactor() / 1000;
            double ease4 = conf.getDouble("ease4");
            // 1.3 -> 1.15
            easyBonus = ease4 - (ease4 - 1)/2;
        }

        double ivl = Math.max(elapsed * factor, 1);

        // cap interval decreases
        ivl = Math.max(card.getIvl() * minNewIvl, ivl) * easyBonus;

        return _constrainedIvl(ivl, conf, 0, false);
    }


    /**
     * Dynamic deck handling ******************************************************************
     * *****************************
     */


    // Note: The original returns an integer result. We return List<Long> with that number to satisfy the
    // interface requirements. The result isn't used anywhere so this isn't a problem.
    public List<Long> rebuildDyn(long did) {
        if (did == 0) {
            did = mCol.getDecks().selected();
        }
        Deck deck = mCol.getDecks().get(did);
        if (deck.getInt("dyn") == 0) {
            Timber.e("error: deck is not a filtered deck");
            return null;
        }
        // move any existing cards back first, then fill
        emptyDyn(did);
        int cnt = _fillDyn(deck);
        if (cnt == 0) {
            return null;
        }
        // and change to our new deck
        mCol.getDecks().select(did);
        return Collections.singletonList((long)cnt);
    }


    private int _fillDyn(Deck deck) {
        int start = -100000;
        int total = 0;
        JSONArray terms;
        List<Long> ids;
        terms = deck.getJSONArray("terms");
        for (int i = 0; i < terms.length(); i++) {
            JSONArray term = terms.getJSONArray(i);
            String search = term.getString(0);
            int limit = term.getInt(1);
            int order = term.getInt(2);

            String orderlimit = _dynOrder(order, limit);
            if (!TextUtils.isEmpty(search.trim())) {
                search = String.format(Locale.US, "(%s)", search);
            }
            search = String.format(Locale.US, "%s -is:suspended -is:buried -deck:filtered", search);
            ids = mCol.findCards(search, orderlimit);
            if (ids.isEmpty()) {
                return total;
            }
            // move the cards over
            mCol.log(deck.getLong("id"), ids);
            _moveToDyn(deck.getLong("id"), ids, start + total);
            total += ids.size();
        }
        return total;
    }


    public void emptyDyn(long did, String lim) {
        if (lim == null) {
            lim = "did = " + did;
        }
        mCol.log(mCol.getDb().queryLongList("select id from cards where " + lim));

        mCol.getDb().execute(
                "update cards set did = odid, " + _restoreQueueSnippet() +
                ", due = (case when odue>0 then odue else due end), odue = 0, odid = 0, usn = ? where " + lim,
                mCol.usn());
    }


    /**
     * Generates the required SQL for order by and limit clauses, for dynamic decks.
     *
     * @param o deck["order"]
     * @param l deck["limit"]
     * @return The generated SQL to be suffixed to "select ... from ... order by "
     */
    protected String _dynOrder(int o, int l) {
        String t;
        switch (o) {
            case Consts.DYN_OLDEST:
                t = "c.mod";
                break;
            case Consts.DYN_RANDOM:
                t = "random()";
                break;
            case Consts.DYN_SMALLINT:
                t = "ivl";
                break;
            case Consts.DYN_BIGINT:
                t = "ivl desc";
                break;
            case Consts.DYN_LAPSES:
                t = "lapses desc";
                break;
            case Consts.DYN_ADDED:
                t = "n.id";
                break;
            case Consts.DYN_REVADDED:
                t = "n.id desc";
                break;
            case Consts.DYN_DUE:
                t = "c.due";
                break;
            case Consts.DYN_DUEPRIORITY:
                t = String.format(Locale.US,
                        "(case when queue=" + Consts.QUEUE_TYPE_REV + " and due <= %d then (ivl / cast(%d-due+0.001 as real)) else 100000+due end)",
                        mToday, mToday);
                break;
            default:
                // if we don't understand the term, default to due order
                t = "c.due";
                break;
        }
        return t + " limit " + l;
    }


    protected void _moveToDyn(long did, List<Long> ids, int start) {
        Deck deck = mCol.getDecks().get(did);
        ArrayList<Object[]> data = new ArrayList<>();
        int u = mCol.usn();
        int due = start;
        for (Long id : ids) {
            data.add(new Object[] {
                did, due, u, id
            });
            due += 1;
        }
        String queue = "";
        if (!deck.getBoolean("resched")) {
            queue = ", queue = " + Consts.QUEUE_TYPE_REV + "";
        }

        mCol.getDb().executeMany(
                "UPDATE cards SET odid = did, " +
                        "odue = due, did = ?, due = (case when due <= 0 then due else ? end), usn = ? " + queue + " WHERE id = ?", data);
    }


    private void _removeFromFiltered(Card card) {
        if (card.getODid() != 0) {
            card.setDid(card.getODid());
            card.setODue(0);
            card.setODid(0);
        }
    }


    private void _restorePreviewCard(Card card) {
        if (card.getODid() == 0) {
            throw new RuntimeException("ODid wasn't set");
        }

        card.setDue(card.getODue());

        // learning and relearning cards may be seconds-based or day-based;
        // other types map directly to queues
        if (card.getType() == Consts.CARD_TYPE_LRN || card.getType() == Consts.CARD_TYPE_RELEARNING) {
            if (card.getODue() > 1000000000) {
                card.setQueue(Consts.QUEUE_TYPE_LRN);
            } else {
                card.setQueue(Consts.QUEUE_TYPE_DAY_LEARN_RELEARN);
            }
        } else {
            card.setQueue(card.getType());
        }
    }


    /**
     * Leeches ****************************************************************** *****************************
     */

    /** Leech handler. True if card was a leech. */
    protected boolean _checkLeech(Card card, JSONObject conf) {
        int lf;
        lf = conf.getInt("leechFails");
        if (lf == 0) {
            return false;
        }
        // if over threshold or every half threshold reps after that
        if (card.getLapses() >= lf && (card.getLapses() - lf) % Math.max(lf / 2, 1) == 0) {
            // add a leech tag
            Note n = card.note();
            n.addTag("leech");
            n.flush();
            // handle
            if (conf.getInt("leechAction") == Consts.LEECH_SUSPEND) {
                card.setQueue(Consts.QUEUE_TYPE_SUSPENDED);
            }
            // notify UI
            if (mContextReference != null) {
                Activity context = mContextReference.get();
                leech(card, context);
            }
            return true;
        }
        return false;
    }


    /**
     * Tools ******************************************************************** ***************************
     */



    @Override
    protected JSONArray _newConfDelay(Card card) {
        return  mCol.getDecks().confForDid(card.getODid()).getJSONObject("new").getJSONArray("delays");
    }


    protected JSONObject _lapseConf(Card card) {
        DeckConfig conf = _cardConf(card);
        // normal deck
        if (card.getODid() == 0) {
            return conf.getJSONObject("lapse");
        }
        // dynamic deck; override some attributes, use original deck for others
        DeckConfig oconf = mCol.getDecks().confForDid(card.getODid());
        JSONObject dict = new JSONObject();
        // original deck
        dict.put("minInt", oconf.getJSONObject("lapse").getInt("minInt"));
        dict.put("leechFails", oconf.getJSONObject("lapse").getInt("leechFails"));
        dict.put("leechAction", oconf.getJSONObject("lapse").getInt("leechAction"));
        dict.put("mult", oconf.getJSONObject("lapse").getDouble("mult"));
        dict.put("delays", oconf.getJSONObject("lapse").getJSONArray("delays"));
        // overrides
        dict.put("resched", conf.getBoolean("resched"));
        return dict;
    }


    private boolean _previewingCard(Card card) {
        DeckConfig conf = _cardConf(card);

        return conf.getInt("dyn") != 0 && !conf.getBoolean("resched");
    }


    private int _previewDelay(Card card) {
        return _cardConf(card).optInt("previewDelay", 10) * 60;
    }


    /**
     * Daily cutoff ************************************************************* **********************************
     * This function uses GregorianCalendar so as to be sensitive to leap years, daylight savings, etc.
     */

    @Override
    protected void _updateCutoff() {
        Integer oldToday = mToday == null ? 0 : mToday;
        // days since col created
        mToday = _daysSinceCreation();
        // end of day cutoff
        mDayCutoff = _dayCutoff();
        if (oldToday != mToday) {
            mCol.log(mToday, mDayCutoff);
        }
        // update all daily counts, but don't save decks to prevent needless conflicts. we'll save on card answer
        // instead
        for (Deck deck : mCol.getDecks().all()) {
            update(deck);
        }
        // unbury if the day has rolled over
        int unburied = mCol.getConf().optInt("lastUnburied", 0);
        if (unburied < mToday) {
            unburyCards();
            mCol.getConf().put("lastUnburied", mToday);
        }
    }


    private long _dayCutoff() {
        int rolloverTime = mCol.getConf().optInt("rollover", 4);
        if (rolloverTime < 0) {
            rolloverTime = 24 + rolloverTime;
        }
        Calendar date = Calendar.getInstance();
        date.setTime(mTime.getCurrentDate());
        date.set(Calendar.HOUR_OF_DAY, rolloverTime);
        date.set(Calendar.MINUTE, 0);
        date.set(Calendar.SECOND, 0);
        date.set(Calendar.MILLISECOND, 0);
        Calendar today = Calendar.getInstance();
        today.setTime(mTime.getCurrentDate());
        if (date.before(today)) {
            date.add(Calendar.DAY_OF_MONTH, 1);
        }

        return date.getTimeInMillis() / 1000;
    }


    private int _daysSinceCreation() {
        Date startDate = new Date(mCol.getCrt() * 1000);
        Calendar c = Calendar.getInstance();
        c.setTime(startDate);
        c.set(Calendar.HOUR, mCol.getConf().optInt("rollover", 4));
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);

        return (int) ((mTime.time() - c.getTimeInMillis()) / 1000) / 86400;
    }


    protected void update(Deck g) {
        for (String t : new String[] { "new", "rev", "lrn", "time" }) {
            String key = t + "Today";
            JSONArray ja = g.getJSONArray(key);
            if (g.getJSONArray(key).getInt(0) != mToday) {
                ja.put(0, mToday);
                ja.put(1, 0);
            }
        }
    }


    public void _checkDay() {
        // check if the day has rolled over
        if (mTime.now() > mDayCutoff) {
            reset();
        }
    }


    /**
     * Deck finished state ******************************************************
     * *****************************************
     */




    public boolean haveBuriedSiblings() {
        return haveBuriedSiblings(mCol.getDecks().active());
    }


    private boolean haveBuriedSiblings(List<Long> allDecks) {
        // Refactored to allow querying an arbitrary deck
        String sdids = Utils.ids2str(allDecks);
        int cnt = mCol.getDb().queryScalar(
                "select 1 from cards where queue = " + Consts.QUEUE_TYPE_SIBLING_BURIED + " and did in " + sdids + " limit 1");
        return cnt != 0;
    }


    public boolean haveManuallyBuried() {
        return haveManuallyBuried(mCol.getDecks().active());
    }


    private boolean haveManuallyBuried(List<Long> allDecks) {
        // Refactored to allow querying an arbitrary deck
        String sdids = Utils.ids2str(allDecks);
        int cnt = mCol.getDb().queryScalar(
                "select 1 from cards where queue = " + Consts.QUEUE_TYPE_MANUALLY_BURIED + " and did in " + sdids + " limit 1");
        return cnt != 0;
    }


    public boolean haveBuried() {
        return haveManuallyBuried() || haveBuriedSiblings();
    }


    /**
     * Next time reports ********************************************************
     * ***************************************
     */


    /**
     * Return the next interval for CARD, in seconds.
     */
    public long nextIvl(Card card, int ease) {
        // preview mode?
        if (_previewingCard(card)) {
            if (ease == Consts.BUTTON_ONE) {
                return _previewDelay(card);
            }
            return 0;
        }
        // (re)learning?
        if (card.getQueue() == Consts.QUEUE_TYPE_NEW || card.getQueue() == Consts.QUEUE_TYPE_LRN || card.getQueue() == Consts.QUEUE_TYPE_DAY_LEARN_RELEARN) {
            return _nextLrnIvl(card, ease);
        } else if (ease == Consts.BUTTON_ONE) {
            // lapse
            JSONObject conf = _lapseConf(card);
            if (conf.getJSONArray("delays").length() > 0) {
                return (long) (conf.getJSONArray("delays").getDouble(0) * 60.0);
            }
            return _lapseIvl(card, conf) * 86400L;
        } else {
            // review
            boolean early = card.getODid() != 0 && (card.getODue() > mToday);
            if (early) {
                return _earlyReviewIvl(card, ease) * 86400L;
            } else {
                return _nextRevIvl(card, ease, false) * 86400L;
            }
        }
    }


    // this isn't easily extracted from the learn code
    protected long _nextLrnIvl(Card card, int ease) {
        if (card.getQueue() == Consts.QUEUE_TYPE_NEW) {
            card.setLeft(_startingLeft(card));
        }
        JSONObject conf = _lrnConf(card);
        if (ease == Consts.BUTTON_ONE) {
            // fail
            return _delayForGrade(conf, conf.getJSONArray("delays").length());
        } else if (ease == Consts.BUTTON_TWO) {
            return _delayForRepeatingGrade(conf, card.getLeft());
        } else if (ease == Consts.BUTTON_FOUR) {
            return _graduatingIvl(card, conf, true, false) * 86400L;
        } else { // ease == 3
            int left = card.getLeft() % 1000 - 1;
            if (left <= 0) {
                // graduate
                return _graduatingIvl(card, conf, false, false) * 86400L;
            } else {
                return _delayForGrade(conf, left);
            }
        }
    }


    /**
     * Suspending & burying ********************************************************** ********************************
     */

    /**
     * learning and relearning cards may be seconds-based or day-based;
     * other types map directly to queues
     */
    protected String _restoreQueueSnippet() {
        return "queue = (case when type in (" + Consts.CARD_TYPE_LRN + "," + Consts.CARD_TYPE_RELEARNING + ") then\n" +
                "  (case when (case when odue then odue else due end) > 1000000000 then 1 else " + Consts.QUEUE_TYPE_DAY_LEARN_RELEARN + " end)\n" +
                "else\n" +
                "  type\n" +
                "end)  ";
    }

    protected String queueIsBuriedSnippet() {
        return " queue in (" + Consts.QUEUE_TYPE_SIBLING_BURIED + ", " + Consts.QUEUE_TYPE_MANUALLY_BURIED + ") ";
    }

    /**
     * Suspend cards.
     */
    public void suspendCards(long[] ids) {
        mCol.log(ids);
        mCol.getDb().execute(
                "UPDATE cards SET queue = " + Consts.QUEUE_TYPE_SUSPENDED + ", mod = ?, usn = ? WHERE id IN "
                        + Utils.ids2str(ids),
                intTime(), mCol.usn());
    }


    /**
     * Unsuspend cards
     */
    public void unsuspendCards(long[] ids) {
        mCol.log(ids);
        mCol.getDb().execute(
                "UPDATE cards SET " + _restoreQueueSnippet() + ", mod = ?, usn = ?"
                        + " WHERE queue = " + Consts.QUEUE_TYPE_SUSPENDED + " AND id IN " + Utils.ids2str(ids),
                intTime(), mCol.usn());
    }


    public void buryCards(long[] cids) {
        buryCards(cids, true);
    }


    @Override
    public void buryCards(long[] cids, boolean manual) {
        int queue = manual ? Consts.QUEUE_TYPE_MANUALLY_BURIED : Consts.QUEUE_TYPE_SIBLING_BURIED;
        mCol.log(cids);
        mCol.getDb().execute("update cards set queue=?,mod=?,usn=? where id in " + Utils.ids2str(cids),
                queue, now(), mCol.usn());
    }


    /**
     * Unbury all buried cards in all decks
     */
    public void unburyCards() {
        mCol.log(mCol.getDb().queryLongList("select id from cards where " + queueIsBuriedSnippet()));
        mCol.getDb().execute("update cards set " + _restoreQueueSnippet() + " where " + queueIsBuriedSnippet());
    }


    public void unburyCardsForDeck() {
        unburyCardsForDeck(ALL);
    }


    public void unburyCardsForDeck(UnburyType type) {
        unburyCardsForDeck(type, null);
    }

    public void unburyCardsForDeck(UnburyType type, List<Long> allDecks) {
        String queue;
        switch (type) {
            case ALL :
                queue = queueIsBuriedSnippet();
                break;
            case MANUAL:
                queue = "queue = " + Consts.QUEUE_TYPE_MANUALLY_BURIED;
                break;
            case SIBLINGS:
                queue = "queue = " + Consts.QUEUE_TYPE_SIBLING_BURIED;
                break;
            default:
                throw new RuntimeException("unknown type");
        }

        String sids = Utils.ids2str(allDecks != null ? allDecks : mCol.getDecks().active());

        mCol.log(mCol.getDb().queryLongList("select id from cards where " + queue + " and did in " + sids));
        mCol.getDb().execute("update cards set mod=?,usn=?, " + _restoreQueueSnippet() + " where " + queue + " and did in " + sids,
                mTime.intTime(), mCol.usn());
    }


    /**
     * Bury all cards for note until next session.
     * @param nid The id of the targeted note.
     */
    public void buryNote(long nid) {
        long[] cids = Utils.collection2Array(mCol.getDb().queryLongList(
                "SELECT id FROM cards WHERE nid = ? AND queue >= " + Consts.CARD_TYPE_NEW, nid));
        buryCards(cids);
    }


    /**
     * Resetting **************************************************************** *******************************
     */


    /**
     * Put cards in review queue with a new interval in days (min, max).
     *
     * @param ids The list of card ids to be affected
     * @param imin the minimum interval (inclusive)
     * @param imax The maximum interval (inclusive)
     */
    public void reschedCards(long[] ids, int imin, int imax) {
        ArrayList<Object[]> d = new ArrayList<>();
        int t = mToday;
        long mod = mTime.intTime();
        Random rnd = new Random();
        for (long id : ids) {
            int r = rnd.nextInt(imax - imin + 1) + imin;
            d.add(new Object[] { Math.max(1, r), r + t, mCol.usn(), mod, Consts.STARTING_FACTOR, id });
        }
        remFromDyn(ids);
        mCol.getDb().executeMany(
                "update cards set type=" + Consts.CARD_TYPE_REV + ",queue=" + Consts.QUEUE_TYPE_REV + ",ivl=?,due=?,odue=0, " +
                        "usn=?,mod=?,factor=? where id=?", d);
        mCol.log(ids);
    }



    /**
     * Repositioning new cards **************************************************
     * *********************************************
     */




    public void sortCards(long[] cids, int start, int step, boolean shuffle, boolean shift) {
        String scids = Utils.ids2str(cids);
        long now = intTime();
        ArrayList<Long> nids = new ArrayList<>();
        for (long id : cids) {
            long nid = mCol.getDb().queryLongScalar("SELECT nid FROM cards WHERE id = ?",
                                                    new Object[] {id});
            if (!nids.contains(nid)) {
                nids.add(nid);
            }
        }
        if (nids.isEmpty()) {
            // no new cards
            return;
        }
        // determine nid ordering
        HashMap<Long, Long> due = new HashMap<>();
        if (shuffle) {
            Collections.shuffle(nids);
        }
        for (int c = 0; c < nids.size(); c++) {
            due.put(nids.get(c), (long) (start + c * step));
        }
        int high = start + step * (nids.size() - 1);
        // shift?
        if (shift) {
            int low = mCol.getDb().queryScalar(
                    "SELECT min(due) FROM cards WHERE due >= ? AND type = " + Consts.CARD_TYPE_NEW + " AND id NOT IN " + scids,
                    start);
            if (low != 0) {
                int shiftby = high - low + 1;
                mCol.getDb().execute(
                        "UPDATE cards SET mod = ?, usn = ?, due = due + ?"
                                + " WHERE id NOT IN " + scids + " AND due >= ? AND queue = " + Consts.QUEUE_TYPE_NEW,
                        now, mCol.usn(), shiftby, low);
            }
        }
        // reorder cards
        ArrayList<Object[]> d = new ArrayList<>();
        Cursor cur = null;
        try {
            cur = mCol.getDb().getDatabase()
                    .query("SELECT id, nid FROM cards WHERE type = " + Consts.CARD_TYPE_NEW + " AND id IN " + scids, null);
            while (cur.moveToNext()) {
                long nid = cur.getLong(1);
                d.add(new Object[] { due.get(nid), now, mCol.usn(), cur.getLong(0) });
            }
        } finally {
            if (cur != null && !cur.isClosed()) {
                cur.close();
            }
        }
        mCol.getDb().executeMany("UPDATE cards SET due = ?, mod = ?, usn = ? WHERE id = ?", d);
    }

    /**
     * for post-import
     */

    public void maybeRandomizeDeck(Long did) {
        if (did == null) {
            did = mCol.getDecks().selected();
        }
        DeckConfig conf = mCol.getDecks().confForDid(did);
        // in order due?
        if (conf.getJSONObject("new").getInt("order") == Consts.NEW_CARDS_RANDOM) {
            randomizeCards(did);
        }
    }


    /**
     * Changing scheduler versions **************************************************
     * *********************************************
     */

    private void _emptyAllFiltered() {
        mCol.getDb().execute("update cards set did = odid, queue = (case when type = " + Consts.CARD_TYPE_LRN + " then " + Consts.QUEUE_TYPE_NEW + " when type = " + Consts.CARD_TYPE_RELEARNING + " then " + Consts.QUEUE_TYPE_REV + " else type end), type = (case when type = " + Consts.CARD_TYPE_LRN + " then " + Consts.CARD_TYPE_NEW + " when type = " + Consts.CARD_TYPE_RELEARNING + " then " + Consts.CARD_TYPE_REV + " else type end), due = odue, odue = 0, odid = 0, usn = ? where odid != 0",
                             mCol.usn());
    }


    private void _removeAllFromLearning() {
        _removeAllFromLearning(2);
    }

    private void _removeAllFromLearning(int schedVer) {
        // remove review cards from relearning
        if (schedVer == 1) {
            mCol.getDb().execute("update cards set due = odue, queue = " + Consts.QUEUE_TYPE_REV + ", type = " + Consts.CARD_TYPE_REV + ", mod = ?, usn = ?, odue = 0 where queue in (" + Consts.QUEUE_TYPE_LRN + "," + Consts.QUEUE_TYPE_DAY_LEARN_RELEARN + ") and type in (" + Consts.CARD_TYPE_REV + "," + Consts.CARD_TYPE_RELEARNING + ")",
                                 mTime.intTime(), mCol.usn());
        } else {
            mCol.getDb().execute("update cards set due = ?+ivl, queue = " + Consts.QUEUE_TYPE_REV + ", type = " + Consts.CARD_TYPE_REV + ", mod = ?, usn = ?, odue = 0 where queue in (" + Consts.QUEUE_TYPE_LRN + "," + Consts.QUEUE_TYPE_DAY_LEARN_RELEARN + ") and type in (" + Consts.CARD_TYPE_REV + "," + Consts.CARD_TYPE_RELEARNING + ")",
                                 mToday, mTime.intTime(), mCol.usn());
        }


        // remove new cards from learning
        forgetCards(Utils.collection2Array(mCol.getDb().queryLongList("select id from cards where queue in (" + Consts.QUEUE_TYPE_LRN + "," + Consts.QUEUE_TYPE_DAY_LEARN_RELEARN + ")")));
    }


    // v1 doesn't support buried/suspended (re)learning cards
    private void _resetSuspendedLearning() {
        mCol.getDb().execute("update cards set type = (case when type = " + Consts.CARD_TYPE_LRN + " then " + Consts.CARD_TYPE_NEW + " when type in (" + Consts.CARD_TYPE_REV + ", " + Consts.CARD_TYPE_RELEARNING + ") then " + Consts.CARD_TYPE_REV + " else type end), due = (case when odue then odue else due end), odue = 0, mod = ?, usn = ? where queue < 0",
                             mTime.intTime(), mCol.usn());
    }


    // no 'manually buried' queue in v1
    private void _moveManuallyBuried() {
        mCol.getDb().execute("update cards set queue=" + Consts.QUEUE_TYPE_SIBLING_BURIED + ", mod=? where queue=" + Consts.QUEUE_TYPE_MANUALLY_BURIED,
                             mTime.intTime());
    }

    // adding 'hard' in v2 scheduler means old ease entries need shifting
    // up or down
    private void _remapLearningAnswers(String sql) {
        mCol.getDb().execute("update revlog set " + sql + " and type in (" + Consts.REVLOG_LRN + ", " + Consts.REVLOG_RELRN + ")");
    }

    public void moveToV1() {
        _emptyAllFiltered();
        _removeAllFromLearning();

        _moveManuallyBuried();
        _resetSuspendedLearning();
        _remapLearningAnswers("ease=ease-1 where ease in (" + Consts.BUTTON_THREE + "," + Consts.BUTTON_FOUR + ")");
    }


    public void moveToV2() {
        _emptyAllFiltered();
        _removeAllFromLearning(1);
        _remapLearningAnswers("ease=ease+1 where ease in (" + Consts.BUTTON_TWO + "," + Consts.BUTTON_THREE + ")");
    }


    /*
     * ***********************************************************
     * The methods below are not in LibAnki.
     * ***********************************************************
     */
    public boolean haveBuried(long did) {
        List<Long> all = new ArrayList<>(mCol.getDecks().children(did).values());
        all.add(did);
        return haveBuriedSiblings(all) || haveManuallyBuried(all);
    }

    public void unburyCardsForDeck(long did) {
        List<Long> all = new ArrayList<>(mCol.getDecks().children(did).values());
        all.add(did);
        unburyCardsForDeck(ALL, all);
    }


    public String getName() {
        return mName;
    }


    /**
     * Counts
     */


    /**
     * This is used when card is currently in the reviewer, to adapt the counts by removing this card from it.*/
    public void decrementCounts(@Nullable Card discardCard) {
        if (discardCard == null) {
            return;
        }
        @Consts.CARD_QUEUE int type = discardCard.getQueue();
        switch (type) {
        case Consts.QUEUE_TYPE_NEW:
            mNewCount--;
            break;
        case Consts.QUEUE_TYPE_LRN:
            mLrnCount --;
            break;
        case Consts.QUEUE_TYPE_REV:
            mRevCount--;
            break;
        case Consts.QUEUE_TYPE_DAY_LEARN_RELEARN:
            mLrnCount--;
            break;
        }
    }


    /**
     * Sorts a card into the lrn queue LIBANKI: not in libanki
     */
    protected void _sortIntoLrn(long due, long id) {
        Iterator<LrnCard> i = mLrnQueue.listIterator();
        int idx = 0;
        while (i.hasNext()) {
            if (i.next().getDue() > due) {
                break;
            } else {
                idx++;
            }
        }
        mLrnQueue.add(idx, new LrnCard(due, id));
    }
}
