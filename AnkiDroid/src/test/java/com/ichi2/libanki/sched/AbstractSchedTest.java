/*
 Copyright (c) 2020 David Allison <davidallisongithub@gmail.com>

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU General Public License as published by the Free Software
 Foundation; either version 3 of the License, or (at your option) any later
 version.

 This program is distributed in the hope that it will be useful, but WITHOUT ANY
 WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 PARTICULAR PURPOSE. See the GNU General Public License for more details.

 You should have received a copy of the GNU General Public License along with
 this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.libanki.sched;

import com.ichi2.anki.RobolectricTest;
import com.ichi2.anki.exception.ConfirmModSchemaException;
import com.ichi2.async.CollectionTask;
import com.ichi2.async.TaskData;
import com.ichi2.async.TaskListener;
import com.ichi2.libanki.Card;
import com.ichi2.libanki.Collection;
import com.ichi2.libanki.Consts;
import com.ichi2.libanki.Deck;
import com.ichi2.libanki.DeckConfig;
import com.ichi2.libanki.Decks;
import com.ichi2.libanki.Model;
import com.ichi2.libanki.Models;
import com.ichi2.libanki.Note;
import com.ichi2.testutils.MockTime;
import com.ichi2.utils.JSONArray;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameter;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters;

import java.util.ArrayList;
import java.util.Arrays;

import timber.log.Timber;

import static com.ichi2.anki.AbstractFlashcardViewer.EASE_3;
import static com.ichi2.async.CollectionTask.TASK_TYPE.*;
import static com.ichi2.async.CollectionTask.nonTaskUndo;
import static com.ichi2.libanki.Consts.QUEUE_TYPE_LRN;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// Note: These tests can't be run individually but can from the class-level
// gradlew AnkiDroid:testDebug --tests "com.ichi2.libanki.sched.AbstractSchedTest.*"
@RunWith(ParameterizedRobolectricTestRunner.class)
public class AbstractSchedTest extends RobolectricTest {

    @Parameter
    public int schedVersion;

    @Parameters(name = "SchedV{0}")
    public static java.util.Collection<Object[]> initParameters() {
        // This does one run with schedVersion injected as 1, and one run as 2
        return Arrays.asList(new Object[][] { { 1 }, { 2 } });
    }

    @Before
    @Override
    public void setUp() {
        super.setUp();
        try {
            getCol().changeSchedulerVer(schedVersion);
        } catch (ConfirmModSchemaException e) {
            throw new RuntimeException("Could not change schedVer", e);
        }
    }

    @Test
    public void testUndoResetsCardCountsToCorrectValue() throws InterruptedException {
        // #6587
        addNoteUsingBasicModel("Hello", "World");

        AbstractSched sched = getCol().getSched();
        sched.reset();

        Card cardBeforeUndo = sched.getCard();
        int[] countsBeforeUndo = sched.counts();
        // Not shown in the UI, but there is a state where the card has been removed from the queue, but not answered
        // where the counts are decremented.
        assertThat(countsBeforeUndo, is(new int[] { 0, 0, 0 }));

        sched.answerCard(cardBeforeUndo, EASE_3);

        waitForTask(UNDO, 5000);

        int[] countsAfterUndo = sched.counts();

        assertThat("Counts after an undo should be the same as before an undo", countsAfterUndo, is(countsBeforeUndo));
    }

    @Test
    public void ensureUndoCorrectCounts() {
        Collection col = getCol();
        AbstractSched sched = col.getSched();
        Deck deck = col.getDecks().get(1);
        DeckConfig dconf = col.getDecks().getConf(1);
        dconf.getJSONObject("new").put("perDay", 10);
        JSONArray newCount = deck.getJSONArray("newToday");
        for (int i = 0; i < 20; i++) {
            Note note = col.newNote();
            note.setField(0, "a");
            col.addNote(note);
        }
        sched.reset();
        assertThat(col.cardCount(), is(20));
        assertThat(sched.newCount(), is(10));
        Card card = sched.getCard();
        assertThat(sched.newCount(), is(9));
        assertThat(sched.counts(card)[0], is(10));
        sched.answerCard(card, 3);
        sched.getCard();
        final boolean[] executed = {false};
        CollectionTask.launchCollectionTask(UNDO,
                new TaskListener() {
                    Card card;
                    @Override
                    public void onPreExecute() {

                    }

                    @Override
                    public void onProgressUpdate(TaskData data) {
                        card = data.getCard();
                    }


                    @Override
                    public void onPostExecute(TaskData result) {
                        assertThat(sched.newCount(), is(9));
                        assertThat(sched.counts(card)[0], is(10));
                        executed[0] = true;
                    }
                });
        waitForAsyncTasksToComplete();
        assertTrue(executed[0]);
    }

    @Test
    public void testCardQueue() {
        Collection col = getCol();
        SchedV2 sched = (SchedV2) col.getSched();
        SimpleCardQueue queue = new SimpleCardQueue(sched);
        assertThat(queue.size(), is(0));
        final int nbCard = 6;
        long[] cids = new long[nbCard];
        for (int i = 0; i < nbCard; i++) {
            Note note = addNoteUsingBasicModel("foo", "bar");
            Card card = note.firstCard();
            long cid = card.getId();
            cids[i] = cid;
            queue.add(cid);
        }
        assertThat(queue.size(), is(nbCard));
        assertEquals(cids[0], queue.removeFirstCard().getId());
        assertThat(queue.size(), is(nbCard - 1));
        queue.remove(cids[1]);
        assertThat(queue.size(), is(nbCard - 2));
        queue.remove(cids[3]);
        assertThat(queue.size(), is(nbCard - 3));
        assertEquals(cids[2], queue.removeFirstCard().getId());
        assertThat(queue.size(), is(nbCard - 4));
        assertEquals(cids[4], queue.removeFirstCard().getId());
        assertThat(queue.size(), is(nbCard - 5));
    }

    @Test
    public void siblingCorrectlyBuried() {
        // #6903
        Collection col = getCol();
        AbstractSched sched = col.getSched();
        Models models = col.getModels();
        DeckConfig dconf = col.getDecks().getConf(1);
        dconf.getJSONObject("new").put("bury", true);
        final int nbNote = 2;
        Note[] notes = new Note[nbNote];
        for (int i = 0; i < nbNote; i++) {
            Note note  = addNoteUsingBasicAndReversedModel("front", "back");
            notes[i] = note;
        }
        sched.reset();

        for (int i = 0; i < nbNote; i++) {
            Card card = sched.getCard();
            assertArrayEquals(new int[] {nbNote * 2 - i, 0, 0}, sched.counts(card));
            assertEquals(notes[i].firstCard().getId(), card.getId());
            assertEquals(Consts.QUEUE_TYPE_NEW, card.getQueue());
            sched.answerCard(card, sched.answerButtons(card));
        }

        Card card = sched.getCard();
        assertNull(card);
    }

    private class IncreaseToday {
        private final long aId, bId, cId, dId;
        private final Decks decks;
        private final AbstractSched sched;

        public IncreaseToday() {
            decks = getCol().getDecks();
            sched = getCol().getSched();
            aId = decks.id("A");
            bId = decks.id("A::B");
            cId = decks.id("A::B::C");
            dId = decks.id("A::B::D");
        }

        private void assertNewCountIs(String explanation, long did, int expected) {
            decks.select(did);
            sched.resetCounts();
            assertThat(explanation, sched.newCount(), is(expected));
        }

        private void increaseAndAssertNewCountsIs(String explanation, long did, int a, int b, int c, int d) {
            extendNew(did);
            assertNewCountsIs(explanation, a, b, c, d);
        }

        private void assertNewCountsIs(String explanation, int a, int b, int c, int d) {
            assertNewCountIs(explanation, aId, a);
            assertNewCountIs(explanation, bId, b);
            assertNewCountIs(explanation, cId, c);
            assertNewCountIs(explanation, dId, d);
        }

        private void extendNew(long did) {
            decks.select(did);
            sched.extendLimits(1, 0);
        }

        public void test() {
            Models models = getCol().getModels();

            DeckConfig dconf = decks.getConf(1);
            dconf.getJSONObject("new").put("perDay", 0);


            Model model = models.byName("Basic");
            for (long did : new long[]{cId, dId}) {
                // The note is added in model's did. So change model's did.
                model.put("did", did);
                for (int i = 0; i < 4; i++) {
                    addNoteUsingBasicModel("foo", "bar");
                }
            }

            assertNewCountsIs("All daily limits are 0", 0, 0, 0, 0);
            increaseAndAssertNewCountsIs("Adding a review in C add it in its parents too", cId, 1, 1, 1, 0);
            increaseAndAssertNewCountsIs("Adding a review in A add it in its children too", aId, 2, 2, 2, 1);
            increaseAndAssertNewCountsIs("Adding a review in B add it in its parents and children too", bId,3, 3, 3, 2);
            increaseAndAssertNewCountsIs("Adding a review in D add it in its parents too", dId, 4, 4, 3, 3);
            increaseAndAssertNewCountsIs("Adding a review in D add it in its parents too", dId, 5, 5, 3, 4);

            decks.select(cId);
            sched.reset();
            for (int i = 0; i < 3; i++) {
                Card card = sched.getCard();
                sched.answerCard(card, sched.answerButtons(card));
            }
            assertNewCountsIs("All cards from C are reviewed. Still 4 cards to review in D, but only two available because of A's limit.", 2, 2, 0, 2);

            increaseAndAssertNewCountsIs("Increasing the number of card in C increase it in its parents too. This allow for more review in any children, and in particular in D.", cId, 3, 3, 1, 3);
            // D increase because A's limit changed.
            // This means that increasing C, which is not related to D, can increase D
            // This follows upstream but is really counter-intuitive.


            increaseAndAssertNewCountsIs("Adding a review in C add it in its parents too, even if c has no more card. This allow one more card in d.", cId, 4, 4, 1, 4);
            /* I would have expected :
             increaseAndAssertNewCountsIs("", cId, 3, 3, 1, 3);
             But it seems that applying "increase c", while not actually increasing c (because there are no more new card)
             still increases A.

             Upstream, the number of new card to add is limited to the number of cards in the deck not already planned for today.
             So, to reproduce it, you either need to temporary add a card in A::B::C, increase today limit and delete the card
             or to do
```python
from aqt import mw
c = mw.col.decks.byName("A::B::C")
mw.col.sched.extendLimits(1, 0)
```
             */
        }
    }

    /** Those test may be unintuitive, but they follow upstream as close as possible. */
    @Test
    public void increaseToday() {
        new IncreaseToday().test();
    }

    private class LearnQueue {
        private Card card;

        private void assertEqualFirstField(String message, Card expected, Card obtained) {
            assertEquals(message, expected.note().getFields()[0], obtained.note().getFields()[0]);
        }


        private void assertLrnQueue(String message, Card... expectedCards) {
            LrnCardQueue lrnQueue = getCol().getSched().getLrnQueue();
            assertTrue(getCol().getSched().getHaveQueue());
            try {
                assertEquals(message, expectedCards.length, lrnQueue.size());
                for (int i = 0; i < expectedCards.length; ++i) {
                    assertEqualFirstField(message, expectedCards[i], lrnQueue.get(i).getCard());
                }
            } catch (AssertionError c) {
                Timber.d("Failing queue is %s.", lrnQueue);
                throw c;
            }
        }


        private void assertCounts(String message, int new_, int lrn, int rev) {
            AbstractSched sched = getCol().getSched();
            assertTrue(sched.getHaveCount());
            assertArrayEquals(message, new int[] {new_, lrn, rev}, sched.currentCounts());
        }


        private void assertCounts(String message, int new_, int lrn, int rev, Card card) {
            AbstractSched sched = getCol().getSched();
            assertArrayEquals(message, new int[] {new_, lrn, rev}, sched.counts(card));
        }


        private void noCount(String message) {
            assertFalse(message, getCol().getSched().getHaveCount());
        }


        private void noQueue(String message) {
            assertFalse(message, getCol().getSched().getHaveQueue());
        }


        private void answer(Card card, int button, int nbReps) {
            answer(card, button, nbReps, QUEUE_TYPE_LRN);
        }


        private void answer(Card card, int button, int nbReps, int queue_type) {
            AbstractSched sched = getCol().getSched();
            assertThat(card.getDue(), is(lessThanOrEqualTo(sched.getDayCutoff())));
            sched.answerCard(card, button);
            assertEquals(1001 * nbReps, card.getLeft());
            assertEquals(queue_type, card.getQueue());
            assertEquals(queue_type, card.getType());
        }


        private void noCardToDiscard() {
            assertNull(getCol().getSched().getCardNotToFetch());
        }


        private void noCurrentCard() {
            assertNull(getCol().getSched().getCurrentCard());
        }


        private void currentCard(String message, Card card) {
            assertEqualFirstField(message, card, getCol().getSched().getCurrentCard());
        }


        private void cardToDiscard(String message, Card card) {
            noQueue(message);
            assertEqualFirstField(message, card, getCol().getSched().getCardNotToFetch());
        }

        public void getCard(int firstField, int new_, int lrn, int rev, Card... expectedCards) {
            AbstractSched sched = getCol().getSched();
            card = sched.getCard();
            assertEquals(Integer.toString(firstField), card.note().getFields()[0]);
            assertArrayEquals(new int[]{new_, lrn, rev}, sched.counts(card));
            getCol().getSched().preloadNextCard();
            assertLrnQueue("", expectedCards);
        }


        public synchronized void dontRepeatCardInLearning() throws InterruptedException {
            Collection<MockTime> col = getCol();
            AbstractSched sched = col.getSched();
            MockTime time = col.getTime();
            Deck deck = col.getDecks().get(1);
            int nbLrnOne = (schedVersion == 1) ? 2 : 1;
            int goodButton = (schedVersion == 1) ? 2 : 3;
            DeckConfig dconf = col.getDecks().getConf(1);
            dconf.getJSONObject("new").put("bury", true);
            int steps[] = new int[] {1, 3};
            dconf.getJSONObject("new").put("delays", new JSONArray(steps));

            int[] itv = new int[steps.length];
            int[] delta = new int[steps.length];
            for (int i = 0; i < steps.length; i++) {
                itv[i] = steps[i] * 60;// normal interval
                delta[i] = itv[i] / 4;// Fuzziness is itv /4.
            }

            final int nbNote = 3;
            Note notes[] = new Note[nbNote];
            Card cardsFront[] = new Card[nbNote];
            long cids[] = new long[nbNote];
            for (int i = 0; i < nbNote; ++i) {
                if (i == nbNote - 1) {
                    notes[i] = addNoteUsingBasicModel(Integer.toString(i), "foo");
                } else {
                    notes[i] = addNoteUsingBasicAndReversedModel(Integer.toString(i), "foo");
                }
                cardsFront[i] = notes[i].firstCard();
                cids[i] = cardsFront[i].getId();
            }
            // Start, 0, 0, 0
            noCount("No count at start");
            noQueue("No queue at start");
            noCardToDiscard();
            col.reset();
            noCount("reset does not put counts");
            noQueue("Reset does not put queue");
            noCardToDiscard();


            // Get note 0 front, new, 5, 0, 0
            // Empty lrn queue
            getCard(0, nbNote * 2 -1, 0 , 0);

            // Answer good. 4, 1/1, 0 Due today, one step.
            // Lrn queue 0
            answer(card, goodButton, 1);
            assertCounts("Button good, so one step for the card moved.", nbNote * 2 - 2, 1, 0);
            assertLrnQueue("Card answered good should be in learning", cardsFront[0]);


            // Get note 1 front, new, 4, 1/1, 0
            // Lrn queue 0
            getCard(1, nbNote * 2 - 2, 1, 0, cardsFront[0]);

            // Answer again. 3, 2/3, 0. Due today, two steps.
            // Lrn queue 1, 0
            answer(card, 1, 2);
            assertCounts("Card answer again. Sched V1 counts two steps. Sched V2 counts one card.", nbNote * 2 - 3, nbLrnOne + 1, 0);
            assertLrnQueue("Card answered again, we'll see it in 1 minutes, so before card0 seen in 3 minutes (Because there are still new cards to review)", cardsFront[1], cardsFront[0]);
            //assertEquals(card.getDue(), answerTime + itv[0], delta[0]);


            // Get note 2 front, new, 3, 2/3, 0.
            // Lrn queue 1, 0
            getCard(2, 3, nbLrnOne + 1, 0, cardsFront[1], cardsFront[0]);
            time.addS(delta[1] + 1); // Ensure that even with maximal fuziness for card 0 and minimal here, card[2] is planned after card [0]

            // Answer good. Due today, one step. 2, 3/4, 0
            // Lrn queue 1, 0, 2
            answer(card, goodButton, 1);
            //assertEquals(card.getDue(), col.getTime().intTime() + itv[1], delta[1]);
            assertCounts("No new card anymore because sibling buried. Number changed while preloading card", 0, nbLrnOne + 1 + 1, 0);
            assertLrnQueue("Another good review, so behind last good review.", cardsFront[1], cardsFront[0], cardsFront[2]);


            // Get note 1 front, again. Lrn, 0, 3/4, 0.
            // Queue 0, 2
            getCard(1, 0, nbLrnOne + 1 + 1, 0, cardsFront[0], cardsFront[2]);

            // Answer again. Due today, two steps.
            // Lrn queue 1, 0, 2,  No more new/rev card, so card should appear in second position
            answer(card, 1, 2);
            assertCounts("No new card. Still the same number of learn card.", 0, nbLrnOne + 1 + 1, 0);
            assertLrnQueue("Since there are no other new/rev card, even if the card should be seen before note 0, we'll put it behind", cardsFront[0], cardsFront[1], cardsFront[2]);


            // Get note 0. Not yet due, but taken in advance as no due card.
            getCard(0, 0, nbLrnOne + 1 + 1, 0, cardsFront[1], cardsFront[2]);


            // Undo second review of note 1. No queue/count until asked
            card = nonTaskUndo(col);
            cardToDiscard("", card);
            currentCard("", card);
            assertEqualFirstField("The card just reviewed is undone.", cardsFront[1], card);
            noCount("");
            // Get count (to display in reviewer)
            assertCounts("Only card is learning", 0, nbLrnOne + 1 + 1, 0, card);
            noQueue("");
            assertTrue(sched.getHaveCount());


            // Answer again. Due today, two steps. Same as last second review
            // Lrn queue 1, 0, 2,  No more new/rev card, so card should appear in second position
            answer(card, 1, 2);
            assertCounts("No new card. Still the same number of learn card.", 0, 1 + nbLrnOne + 1, 0);
            noQueue("");
            noCardToDiscard();
            noCurrentCard();


            // Get note 0. Not yet due, but taken in advance as no due card.
            getCard(0, 0, 1 + nbLrnOne + 1, 0, cardsFront[1], cardsFront[2]);


            // Undo second review of note 1. No queue/count until asked
            card = nonTaskUndo(col);
            assertEqualFirstField("The card just reviewed is undone.", cardsFront[1], card);
            cardToDiscard("", card);
            currentCard("", card);
            noQueue("");
            noCount("");
            // Get count (to display in reviewer)
            assertCounts("Only card is learning", 0, nbLrnOne + 2, 0, card);
            noQueue("");
            assertTrue(sched.getHaveCount());

            // Undo first review of note 2. Card is new. No queue/count. 0, 2, 0
            card = nonTaskUndo(col);
            noQueue("");
            noCount("");
            noQueue("");
            assertEqualFirstField("The card previously reviewed is undone.", cardsFront[2], card);
            assertEquals(card.getQueue(), Consts.QUEUE_TYPE_NEW);
            assertEquals(card.getType(), Consts.CARD_TYPE_NEW);
            assertCounts("", 1, nbLrnOne + 1, 0, card);

            // Answer good, 0, 3, 0, still no queue
            answer(card, goodButton, 1);
            assertCounts("", 0, 1 + nbLrnOne + 1, 0);
            noQueue("");

            // get card 1 again, in learning, two steps.
            getCard(1, 0, nbLrnOne + 1 + 1, 0, cardsFront[0], cardsFront[2]);

            // Answer hard. 0, 3/4, 0
            answer(card, 1, 2);
            assertCounts("Counts recomputed", 0, 1 + nbLrnOne + 1, 0);
            assertLrnQueue("No new/rev, so even if its due date should be small, we move it later.", cardsFront[0], cardsFront[1], cardsFront[2]);
        }
    }
    @Test
    public void dontRepeatCardInLearning() throws InterruptedException {
        new LearnQueue().dontRepeatCardInLearning();
    }
}
