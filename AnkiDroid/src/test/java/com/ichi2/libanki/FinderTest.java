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

package com.ichi2.libanki;

import android.util.Pair;

import com.ichi2.anki.RobolectricTest;
import com.ichi2.libanki.sched.SchedV2;
import com.ichi2.utils.JSONObject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import timber.log.Timber;

import static com.ichi2.libanki.CollectionUtils.getLastListElement;
import static com.ichi2.libanki.Consts.*;
import static com.ichi2.testutils.AnkiAssert.assertEqualsArrayList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class FinderTest extends RobolectricTest {

    @Test
    @Config(qualifiers = "en")
    public void searchForBuriedReturnsManuallyAndSiblingBuried() {
        final String searchQuery = "is:buried";

        SchedV2 sched = upgradeToSchedV2();  //needs to be first

        enableBurySiblings();
        super.addNoteUsingModelName("Basic (and reversed card)", "Front", "Back");
        Card toAnswer = sched.getCard();

        //act
        Card siblingBuried = burySiblings(sched, toAnswer);
        Card manuallyBuriedCard = buryManually(sched, toAnswer.getId());

        //perform the search
        List<Long> buriedCards = new Finder(getCol()).findCards(searchQuery, false);

        //assert
        assertThat("A manually buried card should be returned", buriedCards, hasItem(manuallyBuriedCard.getId()));
        assertThat("A sibling buried card should be returned", buriedCards, hasItem(siblingBuried.getId()));
        assertThat("sibling and manually buried should be the only cards returned", buriedCards, hasSize(2));
    }


    private void enableBurySiblings() {
        getCol().getDecks().allConf().get(0).getJSONObject("new").put("bury", true);
    }


    @NonNull
    private Card burySiblings(SchedV2 sched, Card toManuallyBury) {
        sched.answerCard(toManuallyBury, 1);
        Card siblingBuried = new Note(getCol(), toManuallyBury.getNid()).cards().get(1);
        assertThat(siblingBuried.getQueue(), is(Consts.QUEUE_TYPE_SIBLING_BURIED));
        return siblingBuried;
    }


    @NonNull
    private Card buryManually(SchedV2 sched, long id) {
        sched.buryCards(new long[] { id }, true);
        Card manuallyBuriedCard = new Card(getCol(), id);
        assertThat(manuallyBuriedCard.getQueue(), is(Consts.QUEUE_TYPE_MANUALLY_BURIED));
        return manuallyBuriedCard;
    }
    /*****************
     ** Upstream test*
     *****************/
    
    public boolean isNearCutoff() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour >= 2 && hour < 4;
    }
    
    @Test
    public void test_findCards(){
        Collection col = getCol();
        Note note = col.newNote();
        note.setItem("Front","dog");
        note.setItem("Back","cat");
        note.addTag("monkey animal_1 * %");
        col.addNote(note);
        long n1id = note.getId();
        long firstCardId = note.cards().get(0).getId();
        note = col.newNote();
        note.setItem("Front","goats are fun");
        note.setItem("Back","sheep");
        note.addTag("sheep goat horse animal11");
        col.addNote(note);
        long n2id = note.getId();
        note = col.newNote();
        note.setItem("Front","cat");
        note.setItem("Back","sheep");
        col.addNote(note);
        Card catCard = note.cards().get(0);
        Model m = col.getModels().current();
        m = col.getModels().copy(m);
        Models mm = col.getModels();
        JSONObject t = mm.newTemplate("Reverse");
        t.put("qfmt", "{{Back}}");
        t.put("afmt", "{{Front}}");
        mm.addTemplateModChanged(m, t);
        mm.save(m);
        note = col.newNote();
        note.setItem("Front","test");
        note.setItem("Back","foo bar");
        col.addNote(note);
        col.save();
        List<Long> latestCardIds = note.cids();
        // tag searches
        assertEquals(5, col.findCards("tag:*").size());
        assertEquals(1, col.findCards("tag:\\*").size());
        assertEquals(5, col.findCards("tag:%").size());
        assertEquals(1, col.findCards("tag:\\%").size());
        assertEquals(2, col.findCards("tag:animal_1").size());
        assertEquals(1, col.findCards("tag:animal\\_1").size());
        assertEquals(0, col.findCards("tag:donkey").size());
        assertEquals(1, col.findCards("tag:sheep").size());
        assertEquals(1, col.findCards("tag:sheep tag:goat").size());
        assertEquals(0, col.findCards("tag:sheep tag:monkey").size());
        assertEquals(1, col.findCards("tag:monkey").size());
        assertEquals(1, col.findCards("tag:sheep -tag:monkey").size());
        assertEquals(4, col.findCards("-tag:sheep").size());
        col.getTags().bulkAdd(col.getDb().longList("select id from notes"), "foo bar");
        assertEquals(5, col.findCards("tag:foo").size());
        assertEquals(5, col.findCards("tag:bar").size());
        col.getTags().bulkRem(col.getDb().longList("select id from notes"), "foo");
        assertEquals(0, col.findCards("tag:foo").size());
        assertEquals(5, col.findCards("tag:bar").size());
        // text searches
        assertEquals(2, col.findCards("cat").size());
        assertEquals(1, col.findCards("cat -dog").size());
        assertEquals(1, col.findCards("cat -dog").size());
        assertEquals(1, col.findCards("are goats").size());
        assertEquals(0, col.findCards("\"are goats\"").size());
        assertEquals(1, col.findCards("\"goats are\"").size());
        // card states
        Card c = note.cards().get(0);
        c.setQueue(QUEUE_TYPE_REV);
        c.setType(CARD_TYPE_REV);
        assertEquals(0, col.findCards("is:review").size());
        c.flush();
        assertEqualsArrayList((new Long[] {c.getId()}), col.findCards("is:review"));
        assertEquals(0, col.findCards("is:due").size());
        c.setDue(0);
        c.setQueue(QUEUE_TYPE_REV);
        c.flush();
        assertEqualsArrayList((new Long [] {c.getId()}), col.findCards("is:due"));
        assertEquals(4, col.findCards("-is:due").size());
        c.setQueue(QUEUE_TYPE_SUSPENDED);
        // ensure this card gets a later mod time
        c.flush();
        col.getDb().execute("update cards set mod = mod + 1 where id = ?", new Object[] {c.getId()});
        assertEqualsArrayList((new Long [] {c.getId()}), col.findCards("is:suspended"));
        // nids
        assertEquals(0, col.findCards("nid:54321").size());
        assertEquals(2, col.findCards("nid:"+note.getId()).size());
        assertEquals(2, col.findCards("nid:"+n1id+","+n2id).size());
        // templates
        assertEquals(0, col.findCards("card:foo").size());
        assertEquals(4, col.findCards("\"card:card 1\"").size());
        assertEquals(1, col.findCards("card:reverse").size());
        assertEquals(4, col.findCards("card:1").size());
        assertEquals(1, col.findCards("card:2").size());
        // fields
        assertEquals(1, col.findCards("front:dog").size());
        assertEquals(4, col.findCards("-front:dog").size());
        assertEquals(0, col.findCards("front:sheep").size());
        assertEquals(2, col.findCards("back:sheep").size());
        assertEquals(3, col.findCards("-back:sheep").size());
        assertEquals(0, col.findCards("front:do").size());
        assertEquals(5, col.findCards("front:*").size());
        // ordering
        col.getConf().put("sortType", "noteCrt");
        col.flush();
        assertTrue(latestCardIds.contains(getLastListElement(col.findCards("front:*", true))));
        assertTrue(latestCardIds.contains(getLastListElement(col.findCards("", true))));
        
        col.getConf().put("sortType", "noteFld");
        col.flush();
        assertEquals(catCard.getId(), (long) col.findCards("", true).get(0));
        assertTrue(latestCardIds.contains(getLastListElement(col.findCards("", true))));
        col.getConf().put("sortType", "cardMod");
        col.flush();
        assertTrue(latestCardIds.contains(getLastListElement(col.findCards("", true))));
        assertEquals(firstCardId, (long) col.findCards("", true).get(0));
        col.getConf().put("sortBackwards", true);
        col.flush();
        assertTrue(latestCardIds.contains(col.findCards("", true).get(0)));
        /* TODO: Port BuiltinSortKind
           assertEquals(firstCardId,
           col.findCards("", BuiltinSortKind.CARD_DUE, reverse=false).get(0)
           );
           assertNotEquals(firstCardId,
           col.findCards("", BuiltinSortKind.CARD_DUE, reverse=true).get(0));
        */
        // model
        assertEquals(3, col.findCards("note:basic").size());
        assertEquals(2, col.findCards("-note:basic").size());
        assertEquals(5, col.findCards("-note:foo").size());
        // col
        assertEquals(5, col.findCards("deck:default").size());
        assertEquals(0, col.findCards("-deck:default").size());
        assertEquals(5, col.findCards("-deck:foo").size());
        assertEquals(5, col.findCards("deck:def*").size());
        assertEquals(5, col.findCards("deck:*EFAULT").size());
        assertEquals(0, col.findCards("deck:*cefault").size());
        // full search
        note = col.newNote();
        note.setItem("Front","hello<b>world</b>");
        note.setItem("Back","abc");
        col.addNote(note);
        // as it's the sort field, it matches
        assertEquals(2, col.findCards("helloworld").size());
        // assertEquals(, col.findCards("helloworld", full=true).size())2 This is commented upstream
        // if we put it on the back, it won't
        String note_front = note.getItem("Front");
        String note_back = note.getItem("Back");
        note.setItem("Front", note_back);
        note.setItem("Back", note_front);
        note.flush();
        assertEquals(0, col.findCards("helloworld").size());
        // Those lines are commented above
        // assertEquals(, col.findCards("helloworld", full=true).size())2
        // assertEquals(, col.findCards("back:helloworld", full=true).size())2
        // searching for an invalid special tag should not error
        // TODO: ensure the search fail
        //  assertThrows(Exception.class, () -> col.findCards("is:invalid").size());
        // should be able to limit to parent col, no children
        long id = col.getDb().queryLongScalar("select id from cards limit 1");
        col.getDb().execute(
                            "update cards set did = ? where id = ?", new Object[] {col.getDecks().id("Default::Child"), id});
        col.save();
        assertEquals(7, col.findCards("deck:default").size());
        assertEquals(1, col.findCards("deck:default::child").size());
        assertEquals(6, col.findCards("deck:default -deck:default::*").size());
        // properties
        id = col.getDb().queryLongScalar("select id from cards limit 1");
        col.getDb().execute(
                            "update cards set queue=2, ivl=10, reps=20, due=30, factor=2200 where id = ?",
                            new Object[]{id}
                            );
        assertEquals(1, col.findCards("prop:ivl>5").size());
        assertTrue(col.findCards("prop:ivl<5").size() > 1);
        assertEquals(1, col.findCards("prop:ivl>=5").size());
        assertEquals(0, col.findCards("prop:ivl=9").size());
        assertEquals(1, col.findCards("prop:ivl=10").size());
        assertTrue(col.findCards("prop:ivl!=10").size() > 1);
        assertEquals(1, col.findCards("prop:due>0").size());
        // due dates should work
        assertEquals(0, col.findCards("prop:due=29").size());
        assertEquals(1, col.findCards("prop:due=30").size());
        // ease factors
        assertEquals(0, col.findCards("prop:ease=2.3").size());
        assertEquals(1, col.findCards("prop:ease=2.2").size());
        assertEquals(1, col.findCards("prop:ease>2").size());
        assertTrue(col.findCards("-prop:ease>2").size() > 1);
        // recently failed
        if (! isNearCutoff()) {
            assertEquals(0, col.findCards("rated:1:1").size());
            assertEquals(0, col.findCards("rated:1:2").size());
            c = col.getSched().getCard();
            col.getSched().answerCard(c, 2);
            assertEquals(0, col.findCards("rated:1:1").size());
            assertEquals(1, col.findCards("rated:1:2").size());
            c = col.getSched().getCard();
            col.getSched().answerCard(c, 1);
            assertEquals(1, col.findCards("rated:1:1").size());
            assertEquals(1, col.findCards("rated:1:2").size());
            assertEquals(2, col.findCards("rated:1").size());
            assertEquals(0, col.findCards("rated:0:2").size());
            assertEquals(1, col.findCards("rated:2:2").size());
            // added
            assertEquals(0, col.findCards("added:0").size());
            col.getDb().execute("update cards set id = id - 86400*1000 where id = ?", new Object[] {id});
            assertEquals(col.cardCount() -1, col.findCards("added:1").size());
            assertEquals(col.cardCount() , col.findCards("added:2").size());
        } else {
            Timber.w("some find tests disabled near cutoff");
        }
        // empty field
        assertEquals(0, col.findCards("front:").size());
        note = col.newNote();
        note.setItem("Front","");
        note.setItem("Back","abc2");
        assertEquals(1, col.addNote(note));
        assertEquals(1, col.findCards("front:").size());
        // OR searches and nesting
        assertEquals(2, col.findCards("tag:monkey or tag:sheep").size());
        assertEquals(2, col.findCards("(tag:monkey OR tag:sheep)").size());
        assertEquals(6, col.findCards("-(tag:monkey OR tag:sheep)").size());
        assertEquals(2, col.findCards("tag:monkey or (tag:sheep sheep)").size());
        assertEquals(1, col.findCards("tag:monkey or (tag:sheep octopus)").size());
        // flag
        // Todo: ensure it fails
        // assertThrows(Exception.class, () -> col.findCards("flag:12"));
    }

    @Test
    public void test_findReplace(){
        Collection col = getCol();
        Note note = col.newNote();
        note.setItem("Front","foo");
        note.setItem("Back","bar");
        col.addNote(note);
        Note note2 = col.newNote();
        note2.setItem("Front","baz");
        note2.setItem("Back","foo");
        col.addNote(note2);
        List<Long> nids = Arrays.asList(new Long [] {note.getId(), note2.getId()});
        // should do nothing
        assertEquals(0, col.findReplace(nids, "abc", "123"));
        // global replace
        assertEquals(2, col.findReplace(nids, "foo", "qux"));
        note.load();
        assertEquals("qux", note.getItem("Front"));
        note2.load();
        assertEquals("qux", note2.getItem("Back"));
        // single field replace
        assertEquals(1, col.findReplace(nids, "qux", "foo", "Front"));
        note.load();
        assertEquals("foo", note.getItem("Front"));
        note2.load();
        assertEquals("qux", note2.getItem("Back"));
        // regex replace
        assertEquals(0, col.findReplace(nids, "B.r", "reg"));
        note.load();
        assertNotEquals("reg", note.getItem("Back"));
        assertEquals(1, col.findReplace(nids, "B.r", "reg", true));
        note.load();
        assertEquals(note.getItem("Back"), "reg");
    }

    @Test
    public void test_findDupes(){
        Collection col = getCol();
        Note note = col.newNote();
        note.setItem("Front","foo");
        note.setItem("Back","bar");
        col.addNote(note);
        Note note2 = col.newNote();
        note2.setItem("Front","baz");
        note2.setItem("Back","bar");
        col.addNote(note2);
        Note note3 = col.newNote();
        note3.setItem("Front","quux");
        note3.setItem("Back","bar");
        col.addNote(note3);
        Note note4 = col.newNote();
        note4.setItem("Front","quuux");
        note4.setItem("Back","nope");
        col.addNote(note4);
        List<Pair<String, List<Long>>> r = col.findDupes("Back");
        Pair<String, List<Long>> r0 = r.get(0);
        assertEquals("bar", r0.first);
        assertEquals(3, r0.second.size());
        // valid search
        r = col.findDupes("Back", "bar");
        r0 = r.get(0);
        assertEquals("bar", r0.first);
        assertEquals(3, r0.second.size());
        // excludes everything
        r = col.findDupes("Back", "invalid");
        assertEquals(0, r.size());
        // front isn't dupe
        assertEquals(0, col.findDupes("Front").size());
    }
    
}
