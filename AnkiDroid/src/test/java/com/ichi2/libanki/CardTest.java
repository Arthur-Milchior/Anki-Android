package com.ichi2.libanki;

import com.ichi2.anki.RobolectricTest;
import com.ichi2.anki.exception.ConfirmModSchemaException;
import com.ichi2.utils.JSONArray;
import com.ichi2.utils.JSONObject;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.hamcrest.Matchers.hasItemInArray;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class CardTest extends RobolectricTest {
    /******************
     ** autogenerated from https://github.com/ankitects/anki/blob/2c73dcb2e547c44d9e02c20a00f3c52419dc277b/pylib/tests/test_cards.py*
     ******************/

    @Test
    public void test_delete() {
        Collection col = getCol();
        Note note = col.newNote();
        note.setItem("Front", "1");
        note.setItem("Back", "2");
        col.addNote(note);
        long cid = note.cards().get(0).getId();
        col.reset();
        col.getSched().answerCard(col.getSched().getCard(), 2);
        col.remCards(Arrays.asList(cid));
        assertEquals(0, col.cardCount());
        assertEquals(0, col.noteCount());
        assertEquals(0, col.getDb().queryScalar("select count() from notes"));
        assertEquals(0, col.getDb().queryScalar("select count() from cards"));
        assertEquals(2, col.getDb().queryScalar("select count() from graves"));
    }


    @Test
    public void test_misc_cards() {
        Collection col = getCol();
        Note note = col.newNote();
        note.setItem("Front", "1");
        note.setItem("Back", "2");
        col.addNote(note);
        Card c = note.cards().get(0);
        long id = col.getModels().current().getLong("id");
        assertEquals(0, c.template().getInt("ord"));
    }


    @Test
    public void test_genrem() {
        Collection col = getCol();
        Note note = col.newNote();
        note.setItem("Front", "1");
        note.setItem("Back", "");
        col.addNote(note);
        assertEquals(1, note.numberOfCards());
        Model m = col.getModels().current();
        Models mm = col.getModels();
        // adding a new template should automatically create cards
        JSONObject t = Models.newTemplate("rev");
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
        col.remCards(rep);
        assertEquals(1, note.numberOfCards());
        // if we add to the note, a card should be automatically generated
        note.load();
        note.setItem("Back", "1");
        note.flush();
        assertEquals(2, note.numberOfCards());
    }


    @Test
    public void test_gendeck() {
        Collection col = getCol();
        Model cloze = col.getModels().byName("Cloze");
        col.getModels().setCurrent(cloze);
        Note note = col.newNote();
        note.setItem("Text", "{{c1::one}}");
        col.addNote(note);
        assertEquals(1, col.cardCount());
        assertEquals(1, note.cards().get(0).getDid());
        // set the model to a new default col
        long newId = col.getDecks().id("new");
        cloze.put("did", newId);
        col.getModels().save(cloze, false);
        // a newly generated card should share the first card's col
        note.setItem("Text", "{{c2::two}}");
        note.flush();
        assertEquals(1, note.cards().get(1).getDid());
        // and same with multiple cards
        note.setItem("Text", "{{c3::three}}");
        note.flush();
        assertEquals(1, note.cards().get(2).getDid());
        // if one of the cards is in a different col, it should revert to the
        // model default
        Card c = note.cards().get(1);
        c.setDid(newId);
        c.flush();
        note.setItem("Text", "{{c4::four}}");
        note.flush();
        assertEquals(newId, note.cards().get(3).getDid());
    }

    @Test
    public void test_gen_or() throws ConfirmModSchemaException {
        Collection col = getCol();
        Models models = col.getModels();
        Model model = models.byName("Basic");
        JSONArray flds = model.getJSONArray("flds");
        models.renameField(model, flds.getJSONObject(0), "A");
        models.renameField(model, flds.getJSONObject(1), "B");
        JSONObject fld2 = models.newField("C");
        fld2.put("ord", null);
        models.addField(model, fld2);

        JSONArray tmpls = model.getJSONArray("tmpls");
        tmpls.getJSONObject(0).put("qfmt", "{{A}}{{B}}{{C}}");
        // ensure first card is always generated,
        // because at last one card is generated
        JSONObject tmpl = models.newTemplate("AND_OR");
        tmpl.put("qfmt", "        {{A}}    {{#B}}        {{#C}}            {{B}}        {{/C}}    {{/B}}");
        models.addTemplate(model, tmpl);

        models.save(model);
        models.setCurrent(model);

        Note note = col.newNote();
        note.setItem("A", "foo");
        col.addNote(note);
        assertNoteOrdinalAre(note, new Integer[]{0, 1});

        note = col.newNote();
        note.setItem("B", "foo");
        note.setItem("C", "foo");
        col.addNote(note);
        assertNoteOrdinalAre(note, new Integer[]{0, 1});

        note = col.newNote();
        note.setItem("B", "foo");
        col.addNote(note);
        assertNoteOrdinalAre(note, new Integer[]{0});

        note = col.newNote();
        note.setItem("C", "foo");
        col.addNote(note);
        assertNoteOrdinalAre(note, new Integer[]{0});

        note = col.newNote();
        note.setItem("A", "foo");
        note.setItem("B", "foo");
        note.setItem("C", "foo");
        col.addNote(note);
        assertNoteOrdinalAre(note, new Integer[]{0, 1});

        note = col.newNote();
        col.addNote(note);
        assertNoteOrdinalAre(note, new Integer[]{0});
        // First card is generated if no other card
    }

    @Test
    public void test_gen_not() throws ConfirmModSchemaException {
        Collection col = getCol();
        Models models = col.getModels();
        Model model = models.byName("Basic");
        JSONArray flds = model.getJSONArray("flds");
        JSONArray tmpls = model.getJSONArray("tmpls");

        models.renameField(model, flds.getJSONObject(0), "First");
        models.renameField(model, flds.getJSONObject(1), "Front");
        JSONObject fld2 = models.newField("AddIfEmpty");
        fld2.put("name", "AddIfEmpty");
        models.addField(model, fld2);

        // ensure first card is always generated,
        // because at last one card is generated
        tmpls.getJSONObject(0).put("qfmt", "{{AddIfEmpty}}{{Front}}{{First}}");
        JSONObject tmpl = models.newTemplate("NOT");
        tmpl.put("qfmt", "    {{^AddIfEmpty}}        {{Front}}    {{/AddIfEmpty}}    ");

        models.addTemplate(model, tmpl);

        models.save(model);
        models.setCurrent(model);

        Note note = col.newNote();
        note.setItem("First", "foo");
        note.setItem("AddIfEmpty", "foo");
        note.setItem("Front", "foo");
        col.addNote(note);
        assertNoteOrdinalAre(note, new Integer[]{0});

        note = col.newNote();
        note.setItem("First", "foo");
        note.setItem("AddIfEmpty", "foo");
        col.addNote(note);
        assertNoteOrdinalAre(note, new Integer[]{0});

        note = col.newNote();
        note.setItem("First", "foo"); // ensure first note generated
        col.addNote(note);
        assertNoteOrdinalAre(note, new Integer[]{0});

        note = col.newNote();
        note.setItem("First", "foo");
        note.setItem("Front", "foo");
        col.addNote(note);
        assertNoteOrdinalAre(note, new Integer[]{0, 1});
    }

    private void assertNoteOrdinalAre(Note note, Integer[] ords) {
        ArrayList<Card> cards = note.cards();
        assumeThat(cards.size(), is(ords.length));
        for (Card card : cards) {
            Integer ord = card.getOrd();
            assumeThat(ords, hasItemInArray(ord));
        }
    }

}
