package com.ichi2.libanki.sched;

import com.ichi2.libanki.Card;
import com.ichi2.libanki.Collection;

class LrnCard extends Card.Cache implements Comparable<LrnCard> {
    private final long mDue;
    public LrnCard(Collection col, long due, long cid) {
        super(col, cid);
        mDue = due;
    }

    public long getDue () {
        return mDue;
    }

    @Override
    public int compareTo(LrnCard o) {
        return Long.compare(mDue, o.mDue);
    }

    // For testing/debugging only.
    @Override
    public String toString() {
        return "(id: " + getId() +", due: " + getDue() +" 1rst field: " + getCard().note().getFields()[0] + ")";
    }
}
