package com.ichi2.libanki;

import android.content.res.Resources;
import android.database.Cursor;
import android.icu.util.Calendar;
import android.util.Pair;

import com.ichi2.anki.RobolectricTest;
import com.ichi2.anki.exception.ConfirmModSchemaException;
import com.ichi2.anki.exception.DeckRenameException;
import com.ichi2.libanki.sched.AbstractSched;
import com.ichi2.libanki.sched.Sched;
import com.ichi2.testutils.AnkiAssert;
import com.ichi2.utils.Assert;
import com.ichi2.utils.JSONArray;
import com.ichi2.utils.JSONObject;

import org.apache.http.util.Asserts;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import timber.log.Timber;

import static com.ichi2.libanki.CollectionUtils.getLastListElement;
import static com.ichi2.libanki.Consts.*;
import static com.ichi2.libanki.Utils.intTime;
import static com.ichi2.libanki.Utils.stripHTML;
import static com.ichi2.testutils.AnkiAssert.assertEqualsArrayList;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class CardTest extends RobolectricTest {
    /******************
     ** Upstream tests*
     ******************/
    
    @Test
    public void test_delete(){
        Collection col = getCol();
        Note note = col.newNote();
        note.setItem("Front","1");
        note.setItem("Back","2");
        col.addNote(note);
        long cid = note.cards().get(0).getId();
        col.reset();
        col.getSched().answerCard(col.getSched().getCard(), 2);
        col.remove_cards_and_orphaned_notes(Arrays.asList(new Long [] {cid}));
        assertEquals(0, col.cardCount());
        assertEquals(0, col.noteCount());
        assertEquals(0, col.getDb().queryScalar("select count() from notes"));
        assertEquals(0, col.getDb().queryScalar("select count() from cards"));
        assertEquals(2, col.getDb().queryScalar("select count() from graves"));
    }

    @Test
    public void test_misc_cards(){
        Collection col = getCol();
        Note note = col.newNote();
        note.setItem("Front","1");
        note.setItem("Back","2");
        col.addNote(note);
        Card c = note.cards().get(0);
        long id = col.getModels().current().getLong("id");
        assertEquals(0, c.template().getInt("ord"));
    }

    @Test
    public void test_genrem(){
        Collection col = getCol();
        Note note = col.newNote();
        note.setItem("Front","1");
        note.setItem("Back","");
        col.addNote(note);
        assertEquals(1, note.numberOfCards());
        Model m = col.getModels().current();
        Models mm = col.getModels();
        // adding a new template should automatically create cards
        JSONObject t = mm.newTemplate("rev");
        t.put("qfmt", "{{Front}}");
        t.put("afmt", "");
        mm.addTemplateModChanged(m, t);
        mm.save(m, true);
        assertEquals(2, note.numberOfCards());
        // if the template is changed to remove cards, they'll be removed
        t = m.getJSONArray("tmpls").getJSONObject(1);
        t.put("qfmt", "{{Back}}");
        mm.save(m, true);
        List<Long> rep = col.emptyCids();
        col.remove_cards_and_orphaned_notes(rep);
        assertEquals(1, note.numberOfCards());
        // if we add to the note, a card should be automatically generated
        note.load();
        note.setItem("Back","1");
        note.flush();
        assertEquals(2, note.numberOfCards());
    }

    @Test
    public void test_gendeck(){
        Collection col = getCol();
        Model cloze = col.getModels().byName("Cloze");
        col.getModels().setCurrent(cloze);
        Note note = col.newNote();
        note.setItem("Text","{{c1::one}}");
        col.addNote(note);
        assertEquals(1, col.cardCount());
        assertEquals(1, note.cards().get(0).getDid());
        // set the model to a new default col
        long newId = col.getDecks().id("new");
        cloze.put("did", newId);
        col.getModels().save(cloze, false);
        // a newly generated card should share the first card's col
        note.setItem("Text","{{c2::two}}");
        note.flush();
        assertEquals(1, note.cards().get(1).getDid());
        // and same with multiple cards
        note.setItem("Text","{{c3::three}}");
        note.flush();
        assertEquals(1, note.cards().get(2).getDid());
        // if one of the cards is in a different col, it should revert to the
        // model default
        Card c = note.cards().get(1);
        c.setDid(newId);
        c.flush();
        note.setItem("Text","{{c4::four}}");
        note.flush();
        assertEquals(newId, note.cards().get(3).getDid());
    }
}
