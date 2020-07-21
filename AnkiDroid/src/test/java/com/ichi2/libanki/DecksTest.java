package com.ichi2.libanki;

import com.ichi2.anki.RobolectricTest;
import com.ichi2.anki.exception.DeckRenameException;
import com.ichi2.utils.JSONObject;

import org.apache.http.util.Asserts;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.ichi2.testutils.AnkiAssert.assertEqualsArrayList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class DecksTest extends RobolectricTest {
    // Used in other class to populate decks.
    public static final String[] TEST_DECKS = {
            "scxipjiyozczaaczoawo",
            "cmxieunwoogyxsctnjmv::abcdefgh::ZYXW",
            "cmxieunwoogyxsctnjmv::INSBGDS",
    };


    @Test
    public void duplicateName() {
        Decks decks = getCol().getDecks();
        decks.load("{2: {\"name\": \"A\", \"id\":2}, 3: {\"name\": \"A\", \"id\":3}, 4: {\"name\": \"A::B\", \"id\":4}}", "{}");
        decks.checkIntegrity();
        JSONObject deckA = decks.byName("A");
        Asserts.notNull(deckA, "A deck with name \"A\" should still exists");
        assertThat("A deck with name \"A\" should have name \"A\"", deckA.getString("name"), is("A"));
        JSONObject deckAPlus = decks.byName("A+");
        Asserts.notNull(deckAPlus, "A deck with name \"A+\" should still exists");
    }


    @Test
    public void ensureDeckList() {
        Decks decks = getCol().getDecks();
        for (String deckName : TEST_DECKS) {
            addDeck(deckName);
        }
        JSONObject brokenDeck = decks.byName("cmxieunwoogyxsctnjmv::INSBGDS");
        Asserts.notNull(brokenDeck, "We should get deck with given name");
        // Changing the case. That could exists in an old collection or during sync.
        brokenDeck.put("name", "CMXIEUNWOOGYXSCTNJMV::INSBGDS");
        decks.save(brokenDeck);

        decks.childMap();
        for (JSONObject deck : decks.all()) {
            long did = deck.getLong("id");
            for (JSONObject parent : decks.parents(did)) {
                Asserts.notNull(parent, "Parent should not be null");
            }
        }
    }


    @Test
    public void trim() {
        assertThat(Decks.strip("A\nB C\t D"), is("A\nB C\t D"));
        assertThat(Decks.strip("\n A\n\t"), is("A"));
        assertThat(Decks.strip("Z::\n A\n\t::Y"), is("Z::A::Y"));
    }


    /******************
     ** Upstream tests*
     ******************/

    @Test
    public void test_basic() {
        Collection col = getCol();
        // we start with a standard col
        assertEquals(1, col.getDecks().allSortedNames().size());
        // it should have an id of 1
        assertNotNull(col.getDecks().name(1));
        // create a new col
        long parentId = col.getDecks().id("new deck");
        assertNotEquals(parentId, 0);
        assertEquals(2, col.getDecks().allSortedNames().size());
        // should get the same id
        assertEquals(parentId, (long) col.getDecks().id("new deck"));
        // we start with the default col selected
        assertEquals(1, col.getDecks().selected());
        assertEqualsArrayList(new Long[] {1L}, col.getDecks().active());
        // we can select a different col
        col.getDecks().select(parentId);
        assertEquals(parentId, col.getDecks().selected());
        assertEqualsArrayList(new Long[] {parentId}, col.getDecks().active());
        // let's create a child
        long childId = col.getDecks().id("new deck::child");
        col.getSched().reset();
        // it should have been added to the active list
        assertEquals(parentId, col.getDecks().selected());
        assertEqualsArrayList(new Long[] {parentId, childId}, col.getDecks().active());
        // we can select the child individually too
        col.getDecks().select(childId);
        assertEquals(childId, col.getDecks().selected());
        assertEqualsArrayList(new Long[] {childId}, col.getDecks().active());
        // parents with a different case should be handled correctly
        col.getDecks().id("ONE");
        Model m = col.getModels().current();
        m.put("did", col.getDecks().id("one::two"));
        col.getModels().save(m, false);
        Note n = col.newNote();
        n.setItem("Front", "abc");
        col.addNote(n);
    }


    @Test
    public void test_remove() {
        Collection col = getCol();
        // create a new col, and add a note/card to it
        long deck1 = col.getDecks().id("deck1");
        Note note = col.newNote();
        note.setItem("Front", "1");
        note.model().put("did", deck1);
        col.addNote(note);
        Card c = note.cards().get(0);
        assertEquals(deck1, c.getDid());
        assertEquals(1, col.cardCount());
        col.getDecks().rem(deck1);
        assertEquals(0, col.cardCount());
        // if we try to get it, we get the default
        assertEquals("[no deck]", col.getDecks().name(c.getDid()));
    }


    @Test
    public void test_rename() throws DeckRenameException {
        Collection col = getCol();
        long id = col.getDecks().id("hello::world");
        // should be able to rename into a completely different branch, creating
        // parents as necessary
        col.getDecks().rename(col.getDecks().get(id), "foo::bar");
        List<String> names = col.getDecks().allSortedNames();
        assertTrue(names.contains("foo"));
        assertTrue(names.contains("foo::bar"));
        assertFalse(names.contains("hello::world"));
        // create another col
        id = col.getDecks().id("tmp");
         /* TODO: do we want to follow upstream here ?
         // automatically adjusted if a duplicate name
         col.getDecks().rename(col.getDecks().get(id), "FOO");
         names =  col.getDecks().allSortedNames();
         assertTrue(names.contains("FOO+"));
         
          */
        // when renaming, the children should be renamed too
        col.getDecks().id("one::two::three");
        id = col.getDecks().id("one");
        col.getDecks().rename(col.getDecks().get(id), "yo");
        names = col.getDecks().allSortedNames();
        for (String n : new String[] {"yo", "yo::two", "yo::two::three"}) {
            assertTrue(names.contains(n));
        }
        // over filtered
        long filteredId = col.getDecks().newDyn("filtered");
        Deck filtered = col.getDecks().get(filteredId);
        long childId = col.getDecks().id("child");
        Deck child = col.getDecks().get(childId);
        assertThrows(DeckRenameException.class, () -> col.getDecks().rename(child, "filtered::child"));
        assertThrows(DeckRenameException.class, () -> col.getDecks().rename(child, "FILTERED::child"));
    }

    /** TODO: maybe implement. We don't drag and drop here anyway, so buggy implementation is okay
     @Test public void test_renameForDragAndDrop() throws DeckRenameException {
     // TODO: upstream does not return "default", remove it
     Collection col = getCol();

     long languages_did = col.getDecks().id("Languages");
     long chinese_did = col.getDecks().id("Chinese");
     long hsk_did = col.getDecks().id("Chinese::HSK");

     // Renaming also renames children
     col.getDecks().renameForDragAndDrop(chinese_did, languages_did);
     assertEqualsArrayList(new String [] {"Default", "Languages", "Languages::Chinese", "Languages::Chinese::HSK"}, col.getDecks().allSortedNames());

     // Dragging a col onto itself is a no-op
     col.getDecks().renameForDragAndDrop(languages_did, languages_did);
     assertEqualsArrayList(new String [] {"Default", "Languages", "Languages::Chinese", "Languages::Chinese::HSK"}, col.getDecks().allSortedNames());

     // Dragging a col onto its parent is a no-op
     col.getDecks().renameForDragAndDrop(hsk_did, chinese_did);
     assertEqualsArrayList(new String [] {"Default", "Languages", "Languages::Chinese", "Languages::Chinese::HSK"}, col.getDecks().allSortedNames());

     // Dragging a col onto a descendant is a no-op
     col.getDecks().renameForDragAndDrop(languages_did, hsk_did);
     // TODO: real problem to correct, even if we don't have drag and drop
     // assertEqualsArrayList(new String [] {"Default", "Languages", "Languages::Chinese", "Languages::Chinese::HSK"}, col.getDecks().allSortedNames());

     // Can drag a grandchild onto its grandparent.  It becomes a child
     col.getDecks().renameForDragAndDrop(hsk_did, languages_did);
     assertEqualsArrayList(new String [] {"Default", "Languages", "Languages::Chinese", "Languages::HSK"}, col.getDecks().allSortedNames());

     // Can drag a col onto its sibling
     col.getDecks().renameForDragAndDrop(hsk_did, chinese_did);
     assertEqualsArrayList(new String [] {"Default", "Languages", "Languages::Chinese", "Languages::Chinese::HSK"}, col.getDecks().allSortedNames());

     // Can drag a col back to the top level
     col.getDecks().renameForDragAndDrop(chinese_did, null);
     assertEqualsArrayList(new String [] {"Default", "Chinese", "Chinese::HSK", "Languages"}, col.getDecks().allSortedNames());

     // Dragging a top level col to the top level is a no-op
     col.getDecks().renameForDragAndDrop(chinese_did, null);
     assertEqualsArrayList(new String [] {"Default", "Chinese", "Chinese::HSK", "Languages"}, col.getDecks().allSortedNames());

     // decks are renamed if necessary«
     long new_hsk_did = col.getDecks().id("hsk");
     col.getDecks().renameForDragAndDrop(new_hsk_did, chinese_did);
     assertEqualsArrayList(new String [] {"Default", "Chinese", "Chinese::HSK", "Chinese::hsk+", "Languages"}, col.getDecks().allSortedNames());
     col.getDecks().rem(new_hsk_did);

     }
     */

}
