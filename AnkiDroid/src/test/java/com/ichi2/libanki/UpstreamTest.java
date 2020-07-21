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
public class UpstreamTest extends RobolectricTest {

    /*****************
     ** Cards        *
     *****************/
    
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
    /*****************
     ** Collection   *
     *****************/

    /*TODO
      @Test
      public void test_create_open(){
      (fd, path) = tempfile.mkstemp(suffix=".anki2", prefix="test_attachNew");
      try {
      os.close(fd);
      os.unlink(path);
      } catch (OSError) {
      }
      Collection col = aopen(path);
      // for open()
      String newPath = col.getPath();
      long newMod = col.getMod();
      col.close();

      // reopen
      col = aopen(newPath);
      assertEquals(newMod, col.getMod());
      col.close();

      // non-writeable dir
      if (isWin) {
      String dir = "c:\root.anki2";
      } else {
      String dir = "/attachroot.anki2";
      }
      assertException(Exception, lambda: aopen(dir));
      // reuse tmp file from before, test non-writeable file
      os.chmod(newPath, 0);
      assertException(Exception, lambda: aopen(newPath));
      os.chmod(newPath, 0o666);
      os.unlink(newPath);
      } */

    @Test
    public void test_noteAddDelete(){
        Collection col = getCol();
        // add a note
        Note note = col.newNote();
        note.setItem("Front","one");
        note.setItem("Back","two");
        int n = col.addNote(note);
        assertEquals(1, n);
        // test multiple cards - add another template
        Model m = col.getModels().current();
        Models mm = col.getModels();
        JSONObject t = mm.newTemplate("Reverse");
        t.put("qfmt", "{{Back}}");
        t.put("afmt", "{{Front}}");
        mm.addTemplateModChanged(m, t);
        mm.save(m, true); // todo: remove true which is not upstream
        assertEquals(2, col.cardCount());
        // creating new notes should use both cards
        note = col.newNote();
        note.setItem("Front","three");
        note.setItem("Back","four");
        n = col.addNote(note);
        assertEquals(2, n);
        assertEquals(4, col.cardCount());
        // check q/a generation
        Card c0 = note.cards().get(0);
        assertTrue(c0.q().contains("three"));
        // it should not be a duplicate
        assertEquals(note.dupeOrEmpty(), Note.DupeOrEmpty.CORRECT);
        // now let's make a duplicate
        Note note2 = col.newNote();
        note2.setItem("Front","one");
        note2.setItem("Back","");
        assertNotEquals(note2.dupeOrEmpty(), Note.DupeOrEmpty.CORRECT);
        // empty first field should not be permitted either
        note2.setItem("Front"," ");
        assertNotEquals(note2.dupeOrEmpty(), Note.DupeOrEmpty.CORRECT);
    }

    @Test
    @Ignore("I don't understand this csum")
    public void test_fieldChecksum(){
        Collection col = getCol();
        Note note = col.newNote();
        note.setItem("Front","new");
        note.setItem("Back","new2");
        col.addNote(note);
        assertEquals(0xc2a6b03f, col.getDb().queryLongScalar("select csum from notes"));
        // changing the val should change the checksum
        note.setItem("Front","newx");
        note.flush();
        assertEquals(0x302811ae, col.getDb().queryLongScalar("select csum from notes"));
    }

    @Test
    public void test_addDelTags(){
        Collection col = getCol();
        Note note = col.newNote();
        note.setItem("Front","1");
        col.addNote(note);
        Note note2 = col.newNote();
        note2.setItem("Front","2");
        col.addNote(note2);
        // adding for a given id
        col.getTags().bulkAdd(Arrays.asList(new Long[] {note.getId()}), "foo");
        note.load();
        note2.load();
        assertTrue(note.getTags().contains("foo"));
        assertFalse(note2.getTags().contains("foo"));
        // should be canonified
        col.getTags().bulkAdd(Arrays.asList(new Long [] {note.getId()}), "foo aaa");
        note.load();
        assertEquals("aaa", note.getTags().get(0));
        assertEquals(2, note.getTags().size());
    }

    @Test
    public void test_timestamps(){
        Collection col = getCol();
        int stdModelSize = StdModels.stdModels.length;
        assertEquals(col.getModels().all().size(), stdModelSize);
        for (int i = 0; i < 100; i++) {
            StdModels.basicModel.add(col);
        }
        assertEquals(col.getModels().all().size(), 100 + stdModelSize);
    }

    @Test
    @Ignore("What is anki:play")
    public void test_furigana(){
        Collection col = getCol();
        Models mm = col.getModels();
        Model m = mm.current();
        // filter should work
        m.getJSONArray("tmpls").getJSONObject(0).put("qfmt", "{{kana:Front}}");
        mm.save(m);
        Note n = col.newNote();
        n.setItem("Front", "foo[abc]");
        col.addNote(n);
        Card c = n.cards().get(0);
        assertTrue(c.q().endsWith("abc"));
        // and should avoid sound
        n.setItem("Front", "foo[sound:abc.mp3]");
        n.flush();
        String question = c.q(true);
        assertTrue("Question «" + question +"» does not contains «anki:play».", question.contains("anki:play"));
        // it shouldn't throw an error while people are editing
        m.getJSONArray("tmpls").getJSONObject(0).put("qfmt", "{{kana:}}");
        mm.save(m);
        c.q(true);
    }

    /*
      @Test
      public void test_translate(){
      Collection col = getCol();
      no_uni = without_unicode_isolation;

      assertEqual("Front template has a problem:",
      col.tr(TR.CARD_TEMPLATE_RENDERING_FRONT_SIDE_PROBLEM)
     );
      assertEquals("1 review", no_ucol.getSched().nextIvl(col.tr(TR.STATISTICS_REVIEWS, reviews=1)));
      assertEquals("2 reviews", no_ucol.getSched().nextIvl(col.tr(TR.STATISTICS_REVIEWS, reviews=2)));
      }

      @Test
      public void test_db_named_args(capsys):
      sql = "select a, 2+:test5 from b where arg =:foo and x = :test5";
      args = new Objet [] {};
      kwargs = dict(test5=5, foo="blah");

      s, a = emulate_named_args(sql, args, kwargs);
      assertEquals("select a, 2+?1 from b where arg =?2 and x = ?1", s);
      assertE(new Object [] {5, "blah"}, a);

      // swallow the warning
      _ = capsys.readouterr();
      }

    */
    /*****************
     ** Decks        *
     *****************/

    @Test
    public void test_basic(){
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
        assertEqualsArrayList(new Long [] {1L}, col.getDecks().active());
        // we can select a different col
        col.getDecks().select(parentId);
        assertEquals(parentId, col.getDecks().selected());
        assertEqualsArrayList(new Long [] {parentId}, col.getDecks().active());
        // let's create a child
        long childId = col.getDecks().id("new deck::child");
        col.getSched().reset();
        // it should have been added to the active list
         assertEquals(parentId, col.getDecks().selected());
        assertEqualsArrayList(new Long [] {parentId, childId}, col.getDecks().active());
         // we can select the child individually too
         col.getDecks().select(childId);
         assertEquals(childId, col.getDecks().selected());
        assertEqualsArrayList(new Long [] {childId}, col.getDecks().active());
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
     public void test_remove(){
         Collection col = getCol();
         // create a new col, and add a note/card to it
         long deck1 = col.getDecks().id("deck1");
         Note note = col.newNote();
         note.setItem("Front","1");
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
         List<String> names =  col.getDecks().allSortedNames();
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
         names =  col.getDecks().allSortedNames();
         for (String n: new String[] {"yo", "yo::two", "yo::two::three"}) {
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
     @Test
     public void test_renameForDragAndDrop() throws DeckRenameException {
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

     /*****************
      ** Exporting    *
      *****************/
    private Collection setup1(){
        Collection col = getCol();
        Note note = col.newNote();
        note.setItem("Front","foo");
        note.setItem("Back","bar<br>");
        note.setTagsFromStr("tag, tag2");
        col.addNote(note);
        // with a different col
        note = col.newNote();
        note.setItem("Front","baz");
        note.setItem("Back","qux");
        note.model().put("did", col.getDecks().id("new col"));
        col.addNote(note);
        return col;
    }

    /*//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////// */
    

    /* TODO
       @Test
       public void test_export_anki(){
       Collection col = setup1();
       // create a new col with its own conf to test conf copying
       long did = col.getDecks().id("test");
       Deck dobj = col.getDecks().get(did);
       long confId = col.getDecks().add_config_returning_id("newconf");
       DeckConfig conf = col.getDecks().getConf(confId);
       conf.getJSONObject("new").put("perDay", 5);
       col.getDecks().save(conf);
       col.getDecks().setConf(dobj, confId);
       // export
       AnkiPackageExporter e = AnkiExporter(col);
       fd, newname = tempfile.mkstemp(prefix="ankitest", suffix=".anki2");
       newname = str(newname);
       os.close(fd);
       os.unlink(newname);
       e.exportInto(newname);
       // exporting should not have changed conf for original deck
       conf = col.getDecks().confForDid(did);
       assertNotEquals(conf.getLong("id") != 1);
       // connect to new deck
       Collection col2 = aopen(newname);
       assertEquals(2, col2.cardCount());
       // as scheduling was reset, should also revert decks to default conf
       long did = col2.getDecks().id("test", create=false);
       assertTrue(did);
       conf2 = col2.getDecks().confForDid(did);
       assertTrue(conf2.getJSONObject("new").put("perDay",= 20));
       Deck dobj = col2.getDecks().get(did);
       // conf should be 1
       assertTrue(dobj.put("conf",= 1));
       // try again, limited to a deck
       fd, newname = tempfile.mkstemp(prefix="ankitest", suffix=".anki2");
       newname = str(newname);
       os.close(fd);
       os.unlink(newname);
       e.setDid(1);
       e.exportInto(newname);
       col2 = aopen(newname);
       assertEquals(1, col2.cardCount());
       }
       
       @Test
       public void test_export_ankipkg(){
       Collection col = setup1();
       // add a test file to the media folder
       with open(os.path.join(col.getMedia().dir(), "今日.mp3"), "w") as note:
       note.write("test");
       Note n = col.newNote();
       n.setItem("Front", "[sound:今日.mp3]");
       col.addNote(n);
       AnkiPackageExporter e = AnkiPackageExporter(col);
       fd, newname = tempfile.mkstemp(prefix="ankitest", suffix=".apkg");
       String newname = str(newname);
       os.close(fd);
       os.unlink(newname);
       e.exportInto(newname);
       }
    

       @errorsAfterMidnight
       @Test
       public void test_export_anki_due(){
       Collection col = setup1();
       Collection col = getCol();
       Note note = col.newNote();
       note.setItem("Front","foo");
       col.addNote(note);
       col.crt -= 86400 * 10;
       col.flush();
       col.getSched().reset();
       Card c = col.getSched().getCard();
       col.getSched().answerCard(c, 3);
       col.getSched().answerCard(c, 3);
       // should have ivl of 1, due on day 11
       assertEquals(1, c.getIvl());
       assertEquals(11, c.getDue());
       assertEquals(10, col.getSched().getToday());
       assertEquals(1, c.getDue() - col.getSched().getToday());
       // export
       AnkiPackageExporter e = AnkiExporter(col);
       e.includeSched = true;
       fd, newname = tempfile.mkstemp(prefix="ankitest", suffix=".anki2");
       String newname = str(newname);
       os.close(fd);
       os.unlink(newname);
       e.exportInto(newname);
       // importing into a new deck, the due date should be equivalent
       col2 = getCol();
       imp = Anki2Importer(col2, newname);
       imp.run();
       c = col2.getCard(c.getId());
       col2.getSched().reset();
       assertEquals(1, c.getDue() - col2.getSched().getToday());
       }
       
       @Test
       public void test_export_textcard(){
       //     Collection col = setup1()
       //     e = TextCardExporter(col)
       //     Note note = unicode(tempfile.mkstemp(prefix="ankitest")[1])
       //     os.unlink(note)
       //     e.exportInto(note)
       //     e.includeTags = true
       //     e.exportInto(note)
       
       
       }
       
       @Test
       public void test_export_textnote(){
       Collection col = setup1();
       e = TextNoteExporter(col);
       fd, Note note = tempfile.mkstemp(prefix="ankitest");
       Note note = str(note);
       os.close(fd);
       os.unlink(note);
       e.exportInto(note);
       with open(note) as file:
       assertEquals("foo\tbar<br>\ttag tag2\n", file.readline());
       e.includeTags = false;
       e.includeHTML = false;
       e.exportInto(note);
       with open(note) as file:
       assertEquals("foo\tbar\n", file.readline());
       }
       
       @Test
       public void test_exporters(){
       assertTrue(str(exporters()).contains("*.apkg"));
       
    */
    /*****************
     ** Find         *
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
                            "update cards set long did = ? where long id = ?", new Object[] {col.getDecks().id("Default::Child"), id});
        col.save();
        assertEquals(7, col.findCards("deck:default").size());
        assertEquals(1, col.findCards("deck:default::child").size());
        assertEquals(6, col.findCards("deck:default -deck:default::*").size());
        // properties
        id = col.getDb().queryLongScalar("select id from cards limit 1");
        col.getDb().execute(
                            "update cards set queue=2, ivl=10, reps=20, due=30, factor=2200 where long id = ?",
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
            col.getDb().execute("update cards set long id = id - 86400*1000 where long id = ?", new Object[] {id});
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
        assertThrows(Exception.class, () -> col.findCards("flag:12"));
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
    
    /*****************
     ** Importing    *
     *****************/
    /*
      private void clear_tempfile(tf) {
      ;
      " https://stackoverflow.com/questions/23212435/permission-denied-to-write-to-my-temporary-file ";
      try {
      tf.close();
      os.unlink(tf.name);
      } catch () {
      }
      }
      
      @Test
      public void test_anki2_mediadupes(){
      Collection col = getCol();
      // add a note that references a sound
      Note n = tmp.newNote();
      n.setItem("Front", "[sound:foo.mp3]");
      mid = n.model().getLong("id");
      col.addNote(n);
      // add that sound to media folder
      with open(os.path.join(col.getMedia().dir(), "foo.mp3"), "w") as note:
      note.write("foo");
      col.close();
      // it should be imported correctly into an empty deck
      Collection empty = getCol();
      Anki2Importer imp = Anki2Importer(empty, col.getPath());
      imp.run();
      assertEqualsArrayList(new String [] {"foo.mp3"}, os.listdir(empty.getMedia().dir()));
      // and importing again will not duplicate, as the file content matches
      empty.remove_cards_and_orphaned_notes(empty.getDb().longList("select id from cards"));
      Anki2Importer imp = Anki2Importer(empty, col.getPath());
      imp.run();
      assertEqualsArrayList(new String [] {"foo.mp3"}, os.listdir(empty.getMedia().dir()));
      Note n = empty.getNote(empty.getDb().queryLongScalar("select id from notes"));
      assertTrue(n.fields[0].contains("foo.mp3"));
      // if the local file content is different, and import should trigger a
      // rename
      empty.remove_cards_and_orphaned_notes(empty.getDb().longList("select id from cards"));
      with open(os.path.join(empty.getMedia().dir(), "foo.mp3"), "w") as note:
      note.write("bar");
      Anki2Importer imp = Anki2Importer(empty, col.getPath());
      imp.run();
      assertEqualsArrayList(new String [] {"foo.mp3", "foo_"+mid+".mp3"}, sorted(os.listdir(empty.getMedia().dir())));
      Note n = empty.getNote(empty.getDb().queryLongScalar("select id from notes"));
      assertTrue(n.fields[0].contains("_"));
      // if the localized media file already exists, we rewrite the note and
      // media
      empty.remove_cards_and_orphaned_notes(empty.getDb().longList("select id from cards"));
      with open(os.path.join(empty.getMedia().dir(), "foo.mp3"), "w") as note:
      note.write("bar");
      Anki2Importer imp = Anki2Importer(empty, col.getPath());
      imp.run();
      assertEqualsArrayList(new String [] {"foo.mp3", "foo_"+mid+".mp3" }, sorted(os.listdir(empty.getMedia().dir())));
      assertEqualsArrayList(new String [] {"foo.mp3", "foo_"+mid+".mp3"}, sorted(os.listdir(empty.getMedia().dir())));
      Note n = empty.getNote(empty.getDb().queryLongScalar("select id from notes"));
      assertTrue(n.fields[0].contains("_"));
      }
      
      @Test
      public void test_apkg(){
      Collection col = getCol();
      String apkg = str(os.path.join(testDir, "support/media.apkg"));
      AnkiPackageImporter imp = AnkiPackageImporter(col, apkg);
      assertEqualsArrayList(new String [] {}, os.listdir(col.getMedia().dir()));
      imp.run();
      assertEqualsArrayList(new String [] {"foo.wav"}, os.listdir(col.getMedia().dir()));
      // importing again should be idempotent in terms of media
      col.remove_cards_and_orphaned_notes(col.getDb().longList("select id from cards"));
      AnkiPackageImporter imp = AnkiPackageImporter(col, apkg);
      imp.run();
      assertEqualsArrayList(new String [] {"foo.wav"}, os.listdir(col.getMedia().dir()));
      // but if the local file has different data, it will rename
      col.remove_cards_and_orphaned_notes(col.getDb().longList("select id from cards"));
      with open(os.path.join(col.getMedia().dir(), "foo.wav"), "w") as note:
      note.write("xyz");
      imp = AnkiPackageImporter(col, apkg);
      imp.run();
      assertEquals(2, os.listdir(col.getMedia().dir()).size());
      }
      
      @Test
      public void test_anki2_diffmodel_templates(){
      // different from the above as this one tests only the template text being
      // changed, not the number of cards/fields
      Collection dst = getCol();
      // import the first version of the model
      Collection col = getUpgradeDeckPath("diffmodeltemplates-1.apkg");
      AnkiPackageImporter imp = AnkiPackageImporter(dst, col);
      imp.dupeOnSchemaChange = true;
      imp.run();
      // then the version with updated template
      Collection col = getUpgradeDeckPath("diffmodeltemplates-2.apkg");
      imp = AnkiPackageImporter(dst, col);
      imp.dupeOnSchemaChange = true;
      imp.run();
      // collection should contain the note we imported
      assertEquals(1, dst.noteCount());
      // the front template should contain the text added in the 2nd package
      tlong cid = dst.findCards("")[0]  // only 1 note in collection
      tNote note = dst.getCard(tcid).note();
      assertTrue(tnote.cards().get(0).template().getString("qfmt").contains("Changed Front Template"));
      }
      
      @Test
      public void test_anki2_updates(){
      // create a new empty deck
      dst = getCol();
      Collection col = getUpgradeDeckPath("update1.apkg");
      AnkiPackageImporter imp = AnkiPackageImporter(dst, col);
      imp.run();
      assertEquals(0, imp.dupes);
      assertEquals(1, imp.added);
      assertEquals(0, imp.updated);
      // importing again should be idempotent
      imp = AnkiPackageImporter(dst, col);
      imp.run();
      assertEquals(1, imp.dupes);
      assertEquals(0, imp.added);
      assertEquals(0, imp.updated);
      // importing a newer note should update
      assertEquals(1, dst.noteCount());
      assertTrue(dst.getDb().queryLongScalar("select flds from notes").startswith("hello"));
      Collection col = getUpgradeDeckPath("update2.apkg");
      imp = AnkiPackageImporter(dst, col);
      imp.run();
      assertEquals(0, imp.dupes);
      assertEquals(0, imp.added);
      assertEquals(1, imp.updated);
      assertEquals(1, dst.noteCount());
      assertTrue(dst.getDb().queryLongScalar("select flds from notes").startswith("goodbye"));
      }
      
      @Test
      public void test_csv(){
      Collection col = getCol();
      file = str(os.path.join(testDir, "support/text-2fields.txt"));
      i = TextImporter(col, file);
      i.initMapping();
      i.run();
      // four problems - too many & too few fields, a missing front, and a
      // duplicate entry
      assertEquals(5, i.log.size());
      assertEquals(5, i.total);
      // if we run the import again, it should update instead
      i.run();
      assertEquals(10, i.log.size());
      assertEquals(5, i.total);
      // but importing should not clobber tags if they're unmapped
      Note n = col.getNote(col.getDb().queryLongScalar("select id from notes"));
      n.addTag("test");
      n.flush();
      i.run();
      n.load();
      assertEqualsArrayList(new String [] {"test"}, n.tags);
      // if add-only mode, count will be 0
      i.importMode = 1;
      i.run();
      assertEquals(0, i.total);
      // and if dupes mode, will reimport everything
      assertEquals(5, col.cardCount());
      i.importMode = 2;
      i.run();
      // includes repeated field
      assertEquals(6, i.total);
      assertEquals(11, col.cardCount());
      col.close();
      }
      
      @Test
      public void test_csv2(){
      Collection col = getCol();
      Models mm = col.getModels();
      Model m = mm.current();
      Note note = mm.newField("Three");
      mm.addField(m, note);
      mm.save(m);
      Note n = col.newNote();
      n.setItem("Front", "1");
      n.setItem("Back", "2");
      n.setItem("Three", "3");
      col.addNote(n);
      // an update with unmapped fields should not clobber those fields
      file = str(os.path.join(testDir, "support/text-update.txt"));
      TextImporter i = TextImporter(col, file);
      i.initMapping();
      i.run();
      n.load();
      assertTrue(n.setItem("Front",= "1"));
      assertTrue(n.setItem("Back",= "x"));
      assertTrue(n.setItem("Three",= "3"));
      col.close();
      }
      
      @Test
      public void test_tsv_tag_modified(){
      Collection col = getCol();
      Models mm = col.getModels();
      Model m = mm.current();
      Note note = mm.newField("Top");
      mm.addField(m, note);
      mm.save(m);
      Note n = col.newNote();
      n.setItem("Front", "1");
      n.setItem("Back", "2");
      n.setItem("Top", "3");
      n.addTag("four");
      col.addNote(n);
      
      // https://stackoverflow.com/questions/23212435/permission-denied-to-write-to-my-temporary-file
      with NamedTemporaryFile(mode="w", delete=false) as tf:
      tf.write("1\tb\tc\n");
      tf.flush();
      TextImporter i = TextImporter(col, tf.name);
      i.initMapping();
      i.tagModified = "boom";
      i.run();
      clear_tempfile(tf);
      
      n.load();
      assertTrue(n.setItem("Front",= "1"));
      assertTrue(n.setItem("Back",= "b"));
      assertTrue(n.setItem("Top",= "c"));
      assertTrue(n.getTags().contains("four"));
      assertTrue(n.getTags().contains("boom"));
      assertEquals(2, n.getTags().size());
      assertEquals(1, i.updateCount);
      
      col.close();
      }
      
      @Test
      public void test_tsv_tag_multiple_tags(){
      Collection col = getCol();
      Models mm = col.getModels();
      Model m = mm.current();
      Note note = mm.newField("Top");
      mm.addField(m, note);
      mm.save(m);
      Note n = col.newNote();
      n.setItem("Front", "1");
      n.setItem("Back", "2");
      n.setItem("Top", "3");
      n.addTag("four");
      n.addTag("five");
      col.addNote(n);
      
      // https://stackoverflow.com/questions/23212435/permission-denied-to-write-to-my-temporary-file
      with NamedTemporaryFile(mode="w", delete=false) as tf:
      tf.write("1\tb\tc\n");
      tf.flush();
      TextImporter i = TextImporter(col, tf.name);
      i.initMapping();
      i.tagModified = "five six";
      i.run();
      clear_tempfile(tf);
      
      n.load();
      assertTrue(n.setItem("Front",= "1"));
      assertTrue(n.setItem("Back",= "b"));
      assertTrue(n.setItem("Top",= "c"));
      assertEquals(list(sorted(new String [] {"four", "five", "six"}, list(sorted(n.getTags())))));
      
      col.close();
      }
      
      @Test
      public void test_csv_tag_only_if_modified(){
      Collection col = getCol();
      Models mm = col.getModels();
      Model m = mm.current();
      Note note = mm.newField("Left");
      mm.addField(m, note);
      mm.save(m);
      Note n = col.newNote();
      n.setItem("Front", "1");
      n.setItem("Back", "2");
      n.setItem("Left", "3");
      col.addNote(n);
      
      // https://stackoverflow.com/questions/23212435/permission-denied-to-write-to-my-temporary-file
      with NamedTemporaryFile(mode="w", delete=false) as tf:
      tf.write("1,2,3\n");
      tf.flush();
      TextImporter i = TextImporter(col, tf.name);
      i.initMapping();
      i.tagModified = "right";
      i.run();
      clear_tempfile(tf);
      
      n.load();
      assertEqualsArrayList(new String [] {}, n.tags);
      assertEquals(0, i.updateCount);
      
      col.close();
      }
      
      @pytest.mark.filterwarnings("ignore:Using or importing the ABCs")
      @Test
      public void test_supermemo_xml_01_unicode(){
      Collection col = getCol();
      String file = str(os.path.join(testDir, "support/supermemo1.xml"));
      SupermemoXmlImporter i = SupermemoXmlImporter(col, file);
      // i.META.logToStdOutput = true
      i.run();
      assertEquals(1, i.total);
      long cid = col.getDb().queryLongScalar("select id from cards");
      Card c = col.getCard(cid);
      // Applies A Factor-to-E Factor conversion
      assertEquals(2879, c.getFactor());
      assertEquals(7, c.getReps());
      col.close();
      }
      
      @Test
      public void test_mnemo(){
      Collection col = getCol();
      String file = str(os.path.join(testDir, "support/mnemo.getDb()"));
      MnemosyneImporter i = MnemosyneImporter(col, file);
      i.run();
      assertEquals(7, col.cardCount());
      assertTrue(col.getTags().all().contains("a_longer_tag"));
      assertEquals(1, col.getDb().queryScalar("select count() from cards where type = 0"));
      col.close()
      }
    */
    /*****************
     ** Flags        *
     *****************/
    
    @Test
    public void test_flags(){
        Collection col = getCol();
        Note n = col.newNote();
        n.setItem("Front", "one");
        n.setItem("Back", "two");
        int cnt = col.addNote(n);
        Card c = n.cards().get(0);
        
        // make sure higher bits are preserved
        int origBits = 0b101 << 3;
        c.setFlag(origBits);
        c.flush();
        // no flags to start with
        assertEquals(0, c.userFlag());
        assertEquals(1, col.findCards("flag:0").size());
        assertEquals(0, col.findCards("flag:1").size());
        // set flag 2
        col.setUserFlag(2, new long [] {c.getId()});
        c.load();
        assertEquals(2, c.userFlag());
        // assertEquals(origBits, c.flags & origBits);TODO: create direct access to real flag value
        assertEquals(0, col.findCards("flag:0").size());
        assertEquals(1, col.findCards("flag:2").size());
        assertEquals(0, col.findCards("flag:3").size());
        // change to 3
        col.setUserFlag(3, new long [] {c.getId()});
        c.load();
        assertEquals(3, c.userFlag());
        // unset
        col.setUserFlag(0, new long [] {c.getId()});
        c.load();
        assertEquals(0, c.userFlag());
        
        // should work with Cards method as well
        c.setUserFlag(2);
        assertEquals(2, c.userFlag());
        c.setUserFlag(3);
        assertEquals(3, c.userFlag());
        c.setUserFlag(0);
        assertEquals(0, c.userFlag());
    }
    /*****************
     ** Media        *
     *****************/
    // copying files to media folder
    
    /* TODO: media
       @Test
       public void test_add(){
       Collection col = getCol();
       String dir = tempfile.mkdtemp(prefix="anki");
       String path = os.path.join(dir, "foo.jpg");
       with open(path, "w") as note:
       note.write("hello");
       // new file, should preserve name
       assertEquals("foo.jpg", col.getMedia().addFile(path));
       // adding the same file again should not create a duplicate
       assertEquals("foo.jpg", col.getMedia().addFile(path));
       // but if it has a different sha1, it should
       with open(path, "w") as note:
       note.write("world");
       assertEquals("foo-7c211433f02071597741e6ff5a8ea34789abbf43.jpg", col.getMedia().addFile(path));
       } */
    
    @Test
    public void test_strings(){
        Collection col = getCol();
        long mid = col.getModels().current().getLong("id");
        assertEquals(0, col.getMedia().filesInStr(mid, "aoeu").size());
        assertEqualsArrayList(new String [] {"foo.jpg"}, col.getMedia().filesInStr(mid, "aoeu<img src='foo.jpg'>ao"));
        assertEqualsArrayList(new String [] {"foo.jpg"}, col.getMedia().filesInStr(mid, "aoeu<img src='foo.jpg' style='test'>ao"));
        assertEqualsArrayList(new String [] {"foo.jpg", "bar.jpg"}, col.getMedia().filesInStr(mid, "aoeu<img src='foo.jpg'><img src=\"bar.jpg\">ao"));
        assertEqualsArrayList(new String [] {"foo.jpg"}, col.getMedia().filesInStr(mid, "aoeu<img src=foo.jpg style=bar>ao"));
        assertEqualsArrayList(new String [] {"one", "two"}, col.getMedia().filesInStr(mid, "<img src=one><img src=two>"));
        assertEqualsArrayList(new String [] {"foo.jpg"}, col.getMedia().filesInStr(mid, "aoeu<img src=\"foo.jpg\">ao"));
        assertEqualsArrayList(new String[] {"foo.jpg", "fo"},
                              col.getMedia().filesInStr(mid, "aoeu<img src=\"foo.jpg\"><img class=yo src=fo>ao"));
        assertEqualsArrayList(new String [] {"foo.mp3"}, col.getMedia().filesInStr(mid, "aou[sound:foo.mp3]aou"));
        assertEquals("aoeu", col.getMedia().strip("aoeu"));
        assertEquals("aoeuaoeu", col.getMedia().strip("aoeu[sound:foo.mp3]aoeu"));
        assertEquals("aoeu", col.getMedia().strip("a<img src=yo>oeu"));
        assertEquals("aoeu", col.getMedia().escapeImages("aoeu"));
        assertEquals("<img src='http://foo.com'>", col.getMedia().escapeImages("<img src='http://foo.com'>"));
        assertEquals("<img src=\"foo%20bar.jpg\">", col.getMedia().escapeImages("<img src=\"foo bar.jpg\">"));
    }
    
    /** TODO: file
        @Test
        public void test_deckIntegration(){
        Collection col = getCol();
        // create a media dir
        col.getMedia().dir();
        // put a file into it
        file = str(os.path.join(testDir, "support/fake.png"));
        col.getMedia().addFile(file);
        // add a note which references it
        Note note = col.newNote();
        note.setItem("Front","one");
        note.setItem("Back","<img src='fake.png'>");
        col.addNote(note);
        // and one which references a non-existent file
        Note note = col.newNote();
        note.setItem("Front","one");
        note.setItem("Back","<img src='fake2.png'>");
        col.addNote(note);
        // and add another file which isn't used
        with open(os.path.join(col.getMedia().dir(), "foo.jpg"), "w") as note:
        note.write("test");
        // check media
        ret = col.getMedia().check();
        assertEqualsArrayList(new String [] {"fake2.png"}, ret.missing);
        assertEqualsArrayList(new String [] {"foo.jpg"}, ret.unused);
        }
    */
    /*****************
     ** Models       *
     *****************/
    
    @Test
    public void test_modelDelete() throws ConfirmModSchemaException {
        Collection col = getCol();
        Note note = col.newNote();
        note.setItem("Front","1");
        note.setItem("Back","2");
        col.addNote(note);
        assertEquals(1, col.cardCount());
        col.getModels().rem(col.getModels().current());
        assertEquals(0, col.cardCount());
    }
    
    @Test
    public void test_modelCopy(){
        Collection col = getCol();
        Model m = col.getModels().current();
        Model m2 = col.getModels().copy(m);
        assertEquals("Basic copy", m2.getString("name"));
        assertNotEquals(m2.getLong("id"), m.getLong("id"));
        assertEquals(2, m2.getJSONArray("flds").length());
        assertEquals(2, m.getJSONArray("flds").length());
        assertEquals(m.getJSONArray("flds").length(), m2.getJSONArray("flds").length());
        assertEquals(1, m.getJSONArray("tmpls").length());
        assertEquals(1, m2.getJSONArray("tmpls").length());
        assertEquals(col.getModels().scmhash(m), col.getModels().scmhash(m2));
    }
    
    @Test
    public void test_fields() throws ConfirmModSchemaException {
        Collection col = getCol();
        Note note = col.newNote();
        note.setItem("Front","1");
        note.setItem("Back","2");
        col.addNote(note);
        Model m = col.getModels().current();
        // make sure renaming a field updates the templates
        col.getModels().renameField(m, m.getJSONArray("flds").getJSONObject(0), "NewFront");
        assertTrue(m.getJSONArray("tmpls").getJSONObject(0).getString("qfmt").contains("{{NewFront}}"));
        String h = col.getModels().scmhash(m);
        // add a field
        JSONObject field = col.getModels().newField("foo");
        col.getModels().addField(m, field);
        assertArrayEquals(new String [] {"1", "2", ""}, col.getNote(col.getModels().nids(m).get(0)).getFields());
        assertNotEquals(h, col.getModels().scmhash(m));
        // rename it
        field = m.getJSONArray("flds").getJSONObject(2);
        col.getModels().renameField(m, field, "bar");
        assertEquals("", col.getNote(col.getModels().nids(m).get(0)).getItem("bar"));
        // delete back
        col.getModels().remField(m, m.getJSONArray("flds").getJSONObject(1));
        assertArrayEquals(new String [] {"1", ""}, col.getNote(col.getModels().nids(m).get(0)).getFields());
        // move 0 -> 1
        col.getModels().moveField(m, m.getJSONArray("flds").getJSONObject(0), 1);
        assertArrayEquals(new String [] {"", "1"}, col.getNote(col.getModels().nids(m).get(0)).getFields());
        // move 1 -> 0
        col.getModels().moveField(m, m.getJSONArray("flds").getJSONObject(1), 0);
        assertArrayEquals(new String [] {"1", ""}, col.getNote(col.getModels().nids(m).get(0)).getFields());
        // add another and put in middle
        field = col.getModels().newField("baz");
        col.getModels().addField(m, field);
        note = col.getNote(col.getModels().nids(m).get(0));
        note.setItem("baz","2");
        note.flush();
        assertArrayEquals(new String [] {"1", "", "2"}, col.getNote(col.getModels().nids(m).get(0)).getFields());
        // move 2 -> 1
        col.getModels().moveField(m, m.getJSONArray("flds").getJSONObject(2), 1);
        assertArrayEquals(new String [] {"1", "2", ""}, col.getNote(col.getModels().nids(m).get(0)).getFields());
        // move 0 -> 2
        col.getModels().moveField(m, m.getJSONArray("flds").getJSONObject(0), 2);
        assertArrayEquals(new String [] {"2", "", "1"}, col.getNote(col.getModels().nids(m).get(0)).getFields());
        // move 0 -> 1
        col.getModels().moveField(m, m.getJSONArray("flds").getJSONObject(0), 1);
        assertArrayEquals(new String [] {"", "2", "1"}, col.getNote(col.getModels().nids(m).get(0)).getFields());
    }
    
    @Test
    public void test_templates() throws ConfirmModSchemaException {
        Collection col = getCol();
        Model m = col.getModels().current();
        Models mm = col.getModels();
        JSONObject t = mm.newTemplate("Reverse");
        t.put("qfmt", "{{Back}}");
        t.put("afmt", "{{Front}}");
        mm.addTemplateModChanged(m, t);
        mm.save(m);
        Note note = col.newNote();
        note.setItem("Front","1");
        note.setItem("Back","2");
        col.addNote(note);
        assertEquals(2, col.cardCount());
        List<Card> cards =  note.cards();
        assertEquals(2, cards.size());
        Card c = cards.get(0);
        Card c2 = cards.get(1);
        // first card should have first ord
        assertEquals(0, c.getOrd());
        assertEquals(1, c2.getOrd());
        // switch templates
        col.getModels().moveTemplate(m, c.template(), 1);
        c.load();
        c2.load();
        assertEquals(1, c.getOrd());
        assertEquals(0, c2.getOrd());
        // removing a template should delete its cards
        col.getModels().remTemplate(m, m.getJSONArray("tmpls").getJSONObject(0));
        assertEquals(1, col.cardCount());
        // and should have updated the other cards' ordinals
        c = note.cards().get(0);
        assertEquals(0, c.getOrd());
        assertEquals("1", stripHTML(c.q()));
        // it shouldn't be possible to orphan notes by removing templates
        t = mm.newTemplate("template name");
        mm.addTemplateModChanged(m, t);
        col.getModels().remTemplate(m, m.getJSONArray("tmpls").getJSONObject(0));
        assertEquals(0,
                     col.getDb().queryLongScalar(
                                                 "select count() from cards where nid not in (select id from notes)"));
    }
    
    @Test
    public void test_cloze_ordinals() throws ConfirmModSchemaException {
        Collection col = getCol();
        col.getModels().setCurrent(col.getModels().byName("Cloze"));
        Model m = col.getModels().current();
        Models mm = col.getModels();
        
        // We replace the default Cloze template
        JSONObject t = mm.newTemplate("ChainedCloze");
        t.put("qfmt", "{{text:cloze:Text}}");
        t.put("afmt", "{{text:cloze:Text}}");
        mm.addTemplateModChanged(m, t);
        mm.save(m);
        col.getModels().remTemplate(m, m.getJSONArray("tmpls").getJSONObject(0));
        
        Note note = col.newNote();
        note.setItem("Text","{{c1::firstQ::firstA}}{{c2::secondQ::secondA}}");
        col.addNote(note);
        assertEquals(2, col.cardCount());
        List<Card> cards =  note.cards();
        assertEquals(2, cards.size());
        Card c = cards.get(0);
        Card c2 = cards.get(1);
        // first card should have first ord
        assertEquals(0, c.getOrd());
        assertEquals(1, c2.getOrd());
    }
    
    @Test
    public void test_text(){
        Collection col = getCol();
        Model m = col.getModels().current();
        m.getJSONArray("tmpls").getJSONObject(0).put("qfmt", "{{text:Front}}");
        col.getModels().save(m);
        Note note = col.newNote();
        note.setItem("Front","hello<b>world");
        col.addNote(note);
        assertTrue(note.cards().get(0).q().contains("helloworld"));
    }
    
    @Test
    public void test_cloze(){
        Collection col = getCol();
        col.getModels().setCurrent(col.getModels().byName("Cloze"));
        Note note = col.newNote();
        assertEquals("Cloze", note.model().getString("name"));
        // a cloze model with no clozes is not empty
        note.setItem("Text","nothing");
        assertNotEquals(0, col.addNote(note));
        // try with one cloze
        note = col.newNote();
        note.setItem("Text","hello {{c1::world}}");
        assertEquals(1, col.addNote(note));
        assertTrue(note.cards().get(0).q().contains("hello <span class=cloze>[...]</span>"));
        assertTrue(note.cards().get(0).a().contains("hello <span class=cloze>world</span>"));
        // and with a comment
        note = col.newNote();
        note.setItem("Text","hello {{c1::world::typical}}");
        assertEquals(1, col.addNote(note));
        assertTrue(note.cards().get(0).q().contains("<span class=cloze>[typical]</span>"));
        assertTrue(note.cards().get(0).a().contains("<span class=cloze>world</span>"));
        // and with 2 clozes
        note = col.newNote();
        note.setItem("Text","hello {{c1::world}} {{c2::bar}}");
        assertEquals(2, col.addNote(note));
        List<Card> cards =  note.cards();
        assertEquals(2, cards.size());
        Card c1 = cards.get(0);
        Card c2 = cards.get(1);
        assertTrue(c1.q().contains("<span class=cloze>[...]</span> bar"));
        assertTrue(c1.a().contains("<span class=cloze>world</span> bar"));
        assertTrue(c2.q().contains("world <span class=cloze>[...]</span>"));
        assertTrue(c2.a().contains("world <span class=cloze>bar</span>"));
        // if there are multiple answers for a single cloze, they are given in a
        // list
        note = col.newNote();
        note.setItem("Text","a {{c1::b}} {{c1::c}}");
        assertEquals(1, col.addNote(note));
        assertTrue(note.cards().get(0).a().contains("<span class=cloze>b</span> <span class=cloze>c</span>"));
        // if we add another cloze, a card should be generated
        int cnt = col.cardCount();
        note.setItem("Text","{{c2::hello}} {{c1::foo}}");
        note.flush();
        assertEquals(cnt + 1, col.cardCount());
        // 0 or negative indices are not supported
        note.setItem("Text","{{c0::zero}} {{c-1:foo}}");
        note.flush();
        assertEquals(2, note.numberOfCards());
    }
    
    @Test
    public void test_cloze_mathjax(){
        Collection col = getCol();
        col.getModels().setCurrent(col.getModels().byName("Cloze"));
        Note note = col.newNote();
        note.setItem("Text", "{{c1::ok}} \\(2^2\\) {{c2::not ok}} \\(2^{{c3::2}}\\) \\(x^3\\) {{c4::blah}} {{c5::text with \\(x^2\\) jax}}");
        assertNotEquals(0, col.addNote(note));
        assertEquals(5, note.numberOfCards());
        assertTrue(note.cards().get(0).q().contains("class=cloze"));
        assertTrue(note.cards().get(1).q().contains("class=cloze"));
        assertFalse(note.cards().get(2).q().contains("class=cloze"));
        assertTrue(note.cards().get(3).q().contains("class=cloze"));
        assertTrue(note.cards().get(4).q().contains("class=cloze"));
        
        note = col.newNote();
        note.setItem("Text","\\(a\\) {{c1::b}} \\[ {{c1::c}} \\]");
        assertNotEquals(0, col.addNote(note));
        assertEquals(1, note.numberOfCards());
        String question = note.cards().get(0).q();
        assertTrue("Question «"+question+"» does not end correctly", question.endsWith("\\(a\\) <span class=cloze>[...]</span> \\[ [...] \\]"));
    }
    
    @Test
    public void test_typecloze(){
        Collection col = getCol();
        Model m = col.getModels().byName("Cloze");
        col.getModels().setCurrent(m);
        m.getJSONArray("tmpls").getJSONObject(0).put("qfmt", "{{cloze:Text}}{{type:cloze:Text}}");
        col.getModels().save(m);
        Note note = col.newNote();
        note.setItem("Text","hello {{c1::world}}");
        col.addNote(note);
        assertTrue(note.cards().get(0).q().contains("[[type:cloze:Text]]"));
    }
    
    @Test
    public void test_chained_mods() throws ConfirmModSchemaException {
        Collection col = getCol();
        col.getModels().setCurrent(col.getModels().byName("Cloze"));
        Model m = col.getModels().current();
        Models mm = col.getModels();
        
        // We replace the default Cloze template
        JSONObject t = mm.newTemplate("ChainedCloze");
        t.put("qfmt", "{{cloze:text:Text}}");
        t.put("afmt", "{{cloze:text:Text}}");
        mm.addTemplateModChanged(m, t);
        mm.save(m);
        col.getModels().remTemplate(m, m.getJSONArray("tmpls").getJSONObject(0));
        
        Note note = col.newNote();
        String q1 = "<span style=\"color:red\">phrase</span>";
        String a1 = "<b>sentence</b>";
        String q2 = "<span style=\"color:red\">en chaine</span>";
        String a2 = "<i>chained</i>";
        note.setItem("Text","This {{c1::"+q1+"::"+a1+"}} demonstrates {{c1::"+q2+"::"+a2+"}} clozes.");
        assertEquals(1, col.addNote(note));
        String question = note.cards().get(0).q();
        /* TODO: chained modifier
        assertTrue("Question «"+question+"» does not contain the expected string", question.contains("This <span class=cloze>[sentence]</span> demonstrates <span class=cloze>[chained]</span> clozes.")
                   );
        assertTrue(note.cards().get(0).a().contains("This <span class=cloze>phrase</span> demonstrates <span class=cloze>en chaine</span> clozes."
                                                    ));

         */
    }
    
    @Test
    public void test_modelChange() throws ConfirmModSchemaException {
        Collection col = getCol();
        Model cloze = col.getModels().byName("Cloze");
        // enable second template and add a note
        Model m = col.getModels().current();
        Models mm = col.getModels();
        JSONObject t = mm.newTemplate("Reverse");
        t.put("qfmt", "{{Back}}");
        t.put("afmt", "{{Front}}");
        mm.addTemplateModChanged(m, t);
        mm.save(m);
        Model basic = m;
        Note note = col.newNote();
        note.setItem("Front","note");
        note.setItem("Back","b123");
        col.addNote(note);
        // switch fields
        Map<Integer, Integer> map = new HashMap<>();
        map.put(0, 1);
        map.put(1, 0);
        col.getModels().change(basic,new long [] {note.getId()}, basic, map, null);
        note.load();
        assertEquals("b123", note.getItem("Front"));
        assertEquals("note", note.getItem("Back"));
        // switch cards
        Card c0 = note.cards().get(0);
        Card c1 = note.cards().get(1);
        assertTrue(c0.q().contains("b123"));
        assertTrue(c1.q().contains("note"));
        assertEquals(0, c0.getOrd());
        assertEquals(1, c1.getOrd());
        col.getModels().change(basic,new long []{note.getId()}, basic, null, map);
        note.load();
        c0.load();
        c1.load();
        assertTrue(c0.q().contains("note"));
        assertTrue(c1.q().contains("b123"));
        assertEquals(1, c0.getOrd());
        assertEquals(0, c1.getOrd());
        // .cards() returns cards in order
        assertEquals(c1.getId(), note.cards().get(0).getId());
        // delete first card
        map = new HashMap<>();
        map.put(0, null);
        map.put(1, 1);
        // if (isWin) {
        //     // The low precision timer on Windows reveals a race condition
        //     time.sleep(0.05);
        // }
        col.getModels().change(basic,new long [] {note.getId()}, basic, null, map);
        note.load();
        c0.load();
        // the card was deleted
        // but we have two cards, as a new one was generated
        assertEquals(2, note.numberOfCards());
        // an unmapped field becomes blank
        assertEquals("b123", note.getItem("Front"));
        assertEquals("note", note.getItem("Back"));
        col.getModels().change(basic,new long [] {note.getId()}, basic, map, null);
        note.load();
        assertEquals("", note.getItem("Front"));
        assertEquals("note", note.getItem("Back"));
        // another note to try model conversion
        note = col.newNote();
        note.setItem("Front","f2");
        note.setItem("Back","b2");
        col.addNote(note);
        // counts = col.getModels().all_use_counts();
        // Using older version of the test
        assertEquals(2, col.getModels().useCount(basic));
        assertEquals(0, col.getModels().useCount(cloze));
        // Identity map
        map = new HashMap<>();
        map.put(0, 0);
        map.put(1, 1);
        col.getModels().change(basic,new long []{note.getId()}, cloze, map, map);
        note.load();
        assertEquals("f2", note.getItem("Text"));
        assertEquals(2, note.numberOfCards());
        // back the other way, with deletion of second ord
        col.getModels().remTemplate(basic, basic.getJSONArray("tmpls").getJSONObject(1));
        assertEquals(2, col.getDb().queryScalar("select count() from cards where nid = ?", new Object[] {note.getId()}));
        map = new HashMap<>();
        map.put(0, 0);
        col.getModels().change(cloze,new long []{note.getId()}, basic, map, map);
        assertEquals(1, col.getDb().queryScalar("select count() from cards where nid = ?", new Object[] {note.getId()}));
    }
    
    
    private void reqSize(Model model) {
        if (model.getInt("type") == MODEL_CLOZE) {
            return;
        }
        assertEquals(model.getJSONArray("req").length(), model.getJSONArray("tmpls").length());
    }
    
    @Test
    public void test_req(){
        
        Collection col = getCol();
        Models mm = col.getModels();
        Model basic = mm.byName("Basic");
        assertTrue(basic.has("req"));
        reqSize(basic);
        JSONArray r = basic.getJSONArray("req").getJSONArray(0);
        assertEquals(0, r.getInt(0));
        assertTrue(Arrays.asList(new String[]{"any", "all"}).contains(r.getString(1)));
        assertEquals(1, r.getJSONArray(2).length());
        assertEquals(0, r.getJSONArray(2).getInt(0));
        
        Model opt = mm.byName("Basic (optional reversed card)");
        reqSize(opt);
        
        r = opt.getJSONArray("req").getJSONArray(0);
        assertTrue(Arrays.asList(new String[]{"any", "all"}).contains(r.getString(1)));
        assertEquals(1, r.getJSONArray(2).length());
        assertEquals(0, r.getJSONArray(2).getInt(0));
        
        
        assertEquals(new JSONArray("[1,\"all\",[1,2]]"), opt.getJSONArray("req").getJSONArray(1));
        
        // testing any
        opt.getJSONArray("tmpls").getJSONObject(1).put("qfmt", "{{Back}}{{Add Reverse}}");
        mm.save(opt, true);
        assertEquals(new JSONArray("[1, \"any\", [1, 2]]"), opt.getJSONArray("req").getJSONArray(1));
        // testing null
        opt.getJSONArray("tmpls").getJSONObject(1).put("qfmt", "{{^Add Reverse}}{{/Add Reverse}}");
        mm.save(opt, true);
        assertEquals(new JSONArray("[1, \"none\", []]"), opt.getJSONArray("req").getJSONArray(1));
        
        opt = mm.byName("Basic (type in the answer)");
        reqSize(opt);
        r = opt.getJSONArray("req").getJSONArray(0);
        assertTrue(Arrays.asList(new String[]{"any", "all"}).contains(r.getString(1)));
        // TODO: Port anki@4e33775ed4346ef136ece6ef5efec5ba46057c6b
        assertEquals(new JSONArray("[0]"), r.getJSONArray(2));
    }
    
    /*****************
     ** SchedV1      *
     *****************/
    private Collection getColV1() throws ConfirmModSchemaException {
        Collection col = getCol();
        col.changeSchedulerVer(1);
        return col;
    }
    
    public void test_new_v1()  throws Exception {
        Collection col = getColV1();
        col.reset();
        assertEquals(0, col.getSched().newDue());
        // add a note
        Note note = col.newNote();
        note.setItem("Front","one");
        note.setItem("Back","two");
        col.addNote(note);
        col.reset();
        // assertEquals(1, col.getSched().newCount); get access of new count
        // fetch it
        Card c = col.getSched().getCard();
        assertNotNull(c);
        assertEquals(QUEUE_TYPE_NEW, c.getQueue());
        assertEquals(CARD_TYPE_NEW, c.getType());
        // if we answer it, it should become a learn card
        long t = intTime();
        col.getSched().answerCard(c, 1);
        assertEquals(QUEUE_TYPE_LRN, c.getQueue());
        assertEquals(CARD_TYPE_LRN, c.getType());
        assertTrue(c.getDue() >= t);
        
        // disabled for now, as the learn fudging makes this randomly fail
        // // the default order should ensure siblings are not seen together, and
        // // should show all cards
        // Model m = col.getModels().current(); Models mm = col.getModels()
        // JSONObject t = mm.newTemplate("Reverse")
        // t['qfmt'] = "{{Back}}"
        // t['afmt'] = "{{Front}}"
        // mm.addTemplateModChanged(m, t)
        // mm.save(m)
        // note = col.newNote()
        // note['Front'] = u"2"; note['Back'] = u"2"
        // col.addNote(note)
        // note = col.newNote()
        // note['Front'] = u"3"; note['Back'] = u"3"
        // col.addNote(note)
        // col.reset()
        // qs = ("2", "3", "2", "3")
        // for (int n = 0; n < 4; n++) {
        //     c = col.getSched().getCard()
        //     assertTrue(qs[n] in c.q())
        //     col.getSched().answerCard(c, 2)
        // }
    }
    
    @Test
    public void test_newLimits_V1()  throws Exception {
        Collection col = getColV1();
        // add some notes
        long deck2 = col.getDecks().id("Default::foo");
        Note note;
        for (int i =0; i < 30; i++) {
            note = col.newNote();
            note.setItem("Front", Integer.toString(i));
            if (i>4) {
                note.model().put("did", deck2);
            }
            col.addNote(note);
        }
        // give the child deck a different configuration
        long c2 = col.getDecks().confId("new conf");
        col.getDecks().setConf(col.getDecks().get(deck2), c2);
        col.reset();
        // both confs have defaulted to a limit of 20
        // assertEquals(20, col.getSched().newCount); TODO: newCount getter
        // first card we get comes from parent
        Card c = col.getSched().getCard();
        assertEquals(1, c.getDid());
        // limit the parent to 10 cards, meaning we get 10 in total
        DeckConfig conf1 = col.getDecks().confForDid(1);
        conf1.getJSONObject("new").put("perDay", 10);
        col.getDecks().save(conf1);
        col.reset();
        //assertEquals(10, col.getSched().newCount);TODO: newCount getter
        // if we limit child to 4, we should get 9
        DeckConfig conf2 = col.getDecks().confForDid(deck2);
        conf2.getJSONObject("new").put("perDay", 4);
        col.getDecks().save(conf2);
        col.reset();
        //assertEquals(9, col.getSched().newCount);TODO: newCount getter
    }
    
    @Test
    public void test_newBoxes_v1()  throws Exception {
        Collection col = getColV1();
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        col.reset();
        Card c = col.getSched().getCard();
        DeckConfig conf = col.getSched()._cardConf(c);
        conf.getJSONObject("new").put("delays", new JSONArray(new double [] {1, 2, 3, 4, 5}));
        col.getDecks().save(conf);
        col.getSched().answerCard(c, 2);
        // should handle gracefully
        conf.getJSONObject("new").put("delays", new JSONArray(new double [] {1}));
        col.getDecks().save(conf);
        col.getSched().answerCard(c, 2);
    }
    
    @Test
    public void test_learnV1()  throws Exception {
        Collection col = getColV1();
        // add a note
        Note note = col.newNote();
        note.setItem("Front","one");
        note.setItem("Back","two");
        col.addNote(note);
        // set as a learn card and rebuild queues
        col.getDb().execute("update cards set queue=0, type=0");
        col.reset();
        // sched.getCard should return it, since it's due in the past
        Card c = col.getSched().getCard();
        assertNotNull(c);
        DeckConfig conf = col.getSched()._cardConf(c);
        conf.getJSONObject("new").put("delays", new JSONArray(new double [] {0.5, 3, 10}));
        col.getDecks().save(conf);
        // fail it
        col.getSched().answerCard(c, 1);
        // it should have three reps left to graduation
        assertEquals(3, c.getLeft() % 1000);
        assertEquals(3 , c.getLeft() / 1000);
        // it should be due in 30 seconds
        long t = Math.round(c.getDue() - Utils.now());
        assertTrue(t >= 25 && t <= 40);
        // pass it once
        col.getSched().answerCard(c, 2);
        // it should be due in 3 minutes
        assertEquals(Math.round(c.getDue() - Utils.now()),  179, 1);
        assertEquals(2, c.getLeft() % 1000);
        assertEquals(2, c.getLeft() / 1000);
        // check log is accurate
        Cursor log = col.getDb().getDatabase().query("select * from revlog order by id desc");
        assertTrue(log.moveToFirst());
        assertEquals(2, log.getInt(3));
        assertEquals(-180, log.getInt(4));
        assertEquals(-30, log.getInt(5));
        // pass again
        col.getSched().answerCard(c, 2);
        // it should be due in 10 minutes
        assertEquals(c.getDue() - Utils.now(), 599, 1);
        assertEquals(1, c.getLeft() % 1000);
        assertEquals(1 , c.getLeft() / 1000);
        // the next pass should graduate the card
        assertEquals(QUEUE_TYPE_LRN, c.getQueue());
        assertEquals(CARD_TYPE_LRN, c.getType());
        col.getSched().answerCard(c, 2);
        assertEquals(QUEUE_TYPE_REV, c.getQueue());
        assertEquals(CARD_TYPE_REV, c.getType());
        // should be due tomorrow, with an interval of 1
        assertEquals(col.getSched().getToday() + 1, c.getDue());
        assertEquals(1, c.getIvl());
        // or normal removal
        c.setType(CARD_TYPE_NEW);
        c.setQueue(QUEUE_TYPE_LRN);
        col.getSched().answerCard(c, 3);
        assertEquals(CARD_TYPE_REV, c.getType());
        assertEquals(QUEUE_TYPE_REV, c.getQueue());
        assertTrue(checkRevIvl(col, c, 4));
        // revlog should have been updated each time
        assertEquals(5, col.getDb().queryScalar("select count() from revlog where type = 0"));
        // now failed card handling
        c.setType(CARD_TYPE_REV);
        c.setQueue(QUEUE_TYPE_LRN);
        c.setODue(123);
        col.getSched().answerCard(c, 3);
        assertEquals(123, c.getDue());
        assertEquals(CARD_TYPE_REV, c.getType());
        assertEquals(QUEUE_TYPE_REV, c.getQueue());
        // we should be able to remove manually, too
        c.setType(CARD_TYPE_REV);
        c.setQueue(QUEUE_TYPE_LRN);
        c.setODue(321);
        c.flush();
        ((Sched)col.getSched()).removeLrn();
        c.load();
        assertEquals(QUEUE_TYPE_REV, c.getQueue());
        assertEquals(321, c.getDue());
    }
    
    @Test
    public void test_learn_collapsedV1() throws Exception{
        Collection col = getColV1();
        // add 2 notes
        Note note = col.newNote();
        note.setItem("Front","1");
        col.addNote(note);
        note = col.newNote();
        note.setItem("Front","2");
        col.addNote(note);
        // set as a learn card and rebuild queues
        col.getDb().execute("update cards set queue=0, type=0");
        col.reset();
        // should get '1' first
        Card c = col.getSched().getCard();
        assertTrue(c.q().endsWith("1"));
        // pass it so it's due in 10 minutes
        col.getSched().answerCard(c, 2);
        // get the other card
        c = col.getSched().getCard();
        assertTrue(c.q().endsWith("2"));
        // fail it so it's due in 1 minute
        col.getSched().answerCard(c, 1);
        // we shouldn't get the same card again
        c = col.getSched().getCard();
        assertFalse(c.q().endsWith("2"));
    }
    
    @Test
    public void test_learn_dayV1() throws Exception{
        Collection col = getColV1();
        // add a note
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        col.getSched().reset();
        Card c = col.getSched().getCard();
        DeckConfig conf = col.getSched()._cardConf(c);
        conf.getJSONObject("new").put("delays", new JSONArray(new double [] {1, 10, 1440, 2880}));
        col.getDecks().save(conf);
        // pass it
        col.getSched().answerCard(c, 2);
        // two reps to graduate, 1 more today
        assertEquals(3, c.getLeft() % 1000);
        assertEquals(1 , c.getLeft() / 1000);
        assertArrayEquals(new int[]{0, 1, 0}, col.getSched().counts());
        c = col.getSched().getCard();
        
        assertEquals(86400, col.getSched().nextIvl(c, 2));
        // answering it will place it in queue 3
        col.getSched().answerCard(c, 2);
        assertEquals(col.getSched().getToday() + 1, c.getDue());
        assertEquals(CARD_TYPE_RELEARNING, c.getQueue());
        assertNull(col.getSched().getCard());
        // for testing, move it back a day
        c.setDue(c.getDue() - 1);
        c.flush();
        col.reset();
        assertArrayEquals(new int[]{0, 1, 0}, col.getSched().counts());
        c = col.getSched().getCard();
        // nextIvl should work
        assertEquals(86400 * 2, col.getSched().nextIvl(c, 2));
        // if we fail it, it should be back in the correct queue
        col.getSched().answerCard(c, 1);
        assertEquals(QUEUE_TYPE_LRN, c.getQueue());
        col.undo();
        col.reset();
        c = col.getSched().getCard();
        col.getSched().answerCard(c, 2);
        // simulate the passing of another two days
        c.setDue(c.getDue() - 2);
        c.flush();
        col.reset();
        // the last pass should graduate it into a review card
        assertEquals(86400, col.getSched().nextIvl(c, 2));
        col.getSched().answerCard(c, 2);
        assertEquals(CARD_TYPE_REV, c.getType());
        assertEquals(QUEUE_TYPE_REV, c.getQueue());
        // if the lapse step is tomorrow, failing it should handle the counts
        // correctly
        c.setDue(0);
        c.flush();
        col.reset();
        assertArrayEquals(new int[]{0, 0, 1}, col.getSched().counts());
        conf = col.getSched()._cardConf(c);
        conf.getJSONObject("lapse").put("delays", new JSONArray(new double [] {1440}));
        col.getDecks().save(conf);
        c = col.getSched().getCard();
        col.getSched().answerCard(c, 1);
        assertEquals(CARD_TYPE_RELEARNING, c.getQueue());
        assertArrayEquals(new int[]{0, 0, 0}, col.getSched().counts());
    }
    
    @Test
    public void test_reviewsV1() throws Exception{
        Collection col = getColV1();
        // add a note
        Note note = col.newNote();
        note.setItem("Front","one");
        note.setItem("Back","two");
        col.addNote(note);
        // set the card up as a review card, due 8 days ago
        Card c = note.cards().get(0);
        c.setType(CARD_TYPE_REV);
        c.setQueue(QUEUE_TYPE_REV);
        c.setDue(col.getSched().getToday() - 8);
        c.setFactor(STARTING_FACTOR);
        c.setReps(3);
        c.setLapses(1);
        c.setIvl(100);
        c.startTimer();
        c.flush();
        // save it for later use as well
        Card cardcopy = c.clone();
        // failing it should put it in the learn queue with the default options
        ////////////////////////////////////////////////////////////////////////////////////////////////////
        // different delay to new
        col.reset();
        DeckConfig conf = col.getSched()._cardConf(c);
        conf.getJSONObject("lapse").put("delays", new JSONArray(new double [] {2, 20}));
        col.getDecks().save(conf);
        col.getSched().answerCard(c, 1);
        assertEquals(QUEUE_TYPE_LRN, c.getQueue());
        // it should be due tomorrow, with an interval of 1
        assertEquals(col.getSched().getToday()+1, c.getODue());
        assertEquals(1, c.getIvl());
        // but because it's in the learn queue, its current due time should be in
        // the future
        assertTrue(c.getDue() >= Utils.now());
        assertTrue((c.getDue() - Utils.now()) > 118);
        // factor should have been decremented
        assertEquals(2300, c.getFactor());
        // check counters
        assertEquals(2, c.getLapses());
        assertEquals(4, c.getReps());
        // check ests.
        
        assertEquals(120, col.getSched().nextIvl(c, 1));
        assertEquals(20 * 60, col.getSched().nextIvl(c, 2));
        // try again with an ease of 2 instead
        ////////////////////////////////////////////////////////////////////////////////////////////////////
        c = cardcopy.clone();
        c.flush();
        col.getSched().answerCard(c, 2);
        assertEquals(QUEUE_TYPE_REV, c.getQueue());
        // the new interval should be (100 + 8/4) * 1.2 = 122
        assertTrue(checkRevIvl(col, c, 122));
        assertEquals(col.getSched().getToday()+ c.getIvl(), c.getDue());
        // factor should have been decremented
        assertEquals(2350, c.getFactor());
        // check counters
        assertEquals(1, c.getLapses());
        assertEquals(4, c.getReps());
        // ease 3
        ////////////////////////////////////////////////////////////////////////////////////////////////////
        c = cardcopy.clone();
        c.flush();
        col.getSched().answerCard(c, 3);
        // the new interval should be (100 + 8/2) * 2.5 = 260
        assertTrue(checkRevIvl(col, c, 260));
        assertEquals(col.getSched().getToday()+ c.getIvl(), c.getDue());
        // factor should have been left alone
        assertEquals(STARTING_FACTOR, c.getFactor());
        // ease 4
        ////////////////////////////////////////////////////////////////////////////////////////////////////
        c = cardcopy.clone();
        c.flush();
        col.getSched().answerCard(c, 4);
        // the new interval should be (100 + 8) * 2.5 * 1.3 = 351
        assertTrue(checkRevIvl(col, c, 351));
        assertEquals(col.getSched().getToday()+ c.getIvl(), c.getDue());
        // factor should have been increased
        assertEquals(2650, c.getFactor());
    }
    
    private String without_unicode_isolation(String s) {
        return s.replace("\u2068", "").replace("\u2069", "");
    }
    
    @Test
    public void test_button_spacingV1() throws Exception{
        Collection col = getColV1();
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        // 1 day ivl review card due now
        Card c = note.cards().get(0);
        c.setType(CARD_TYPE_REV);
        c.setQueue(QUEUE_TYPE_REV);
        c.setDue(col.getSched().getToday());
        c.setReps(1);
        c.setIvl(1);
        c.startTimer();
        c.flush();
        col.reset();
        // Upstream, there is no space in 2d
        assertEquals("2 d", without_unicode_isolation(col.getSched().nextIvlStr(getTargetContext(), c, 2)));
        assertEquals("3 d", without_unicode_isolation(col.getSched().nextIvlStr(getTargetContext(), c, 3)));
        assertEquals("4 d", without_unicode_isolation(col.getSched().nextIvlStr(getTargetContext(), c, 4)));
    }
    
    @Test
    public void test_overdue_lapseV1() throws Exception {
        // disabled in commit anki@3069729776990980f34c25be66410e947e9d51a2
        return;
        /*
          Collection col = getColV1();
          // add a note
          Note note = col.newNote();
          note.setItem("Front","one");
          col.addNote(note);
          // simulate a review that was lapsed and is now due for its normal review
          Card c = note.cards().get(0);
          c.setType(CARD_TYPE_REV);
          c.setQueue(QUEUE_TYPE_LRN);
          c.setDue(-1);
          c.setODue(-1);
          c.setFactor(STARTING_FACTOR);
          c.setLeft(2002);
          c.setIvl(0);
          c.flush();
          // checkpoint
          col.save();
          col.getSched().reset();
          assertArrayEquals(new int[]{0, 2, 0}, col.getSched().counts());
          c = col.getSched().getCard();
          col.getSched().answerCard(c, 3);
          // it should be due tomorrow
          assertEquals(col.getSched().getToday()+ 1, c.getDue());
          // revert to before
          /* rollback
          col.rollback();
          // with the default settings, the overdue card should be removed from the
          // learning queue
          col.getSched().reset();
          assertArrayEquals(new int[]{0, 0, 1}, col.getSched().counts());
        */
    }
    
    @Test
    public void test_finishedV1() throws Exception {
        Collection col = getColV1();
        // nothing due
        assertTrue(col.getSched().finishedMsg(getTargetContext()).toString().contains("Congratulations"));
        assertFalse(col.getSched().finishedMsg(getTargetContext()).toString().contains("limit"));
        Note note = col.newNote();
        note.setItem("Front","one");
        note.setItem("Back","two");
        col.addNote(note);
        // have a new card
        assertTrue(col.getSched().finishedMsg(getTargetContext()).toString().contains("new cards available"));
        // turn it into a review
        col.reset();
        Card c = note.cards().get(0);
        c.startTimer();
        col.getSched().answerCard(c, 3);
        // nothing should be due tomorrow, as it's due in a week
        assertTrue(col.getSched().finishedMsg(getTargetContext()).toString().contains("Congratulations"));
        assertFalse(col.getSched().finishedMsg(getTargetContext()).toString().contains("limit"));
    }
    
    @Test
    public void test_nextIvlV1() throws Exception {
        Collection col = getColV1();
        Note note = col.newNote();
        note.setItem("Front","one");
        note.setItem("Back","two");
        col.addNote(note);
        col.reset();
        DeckConfig conf = col.getDecks().confForDid(1);
        conf.getJSONObject("new").put("delays", new JSONArray(new double [] {0.5, 3, 10}));
        conf.getJSONObject("lapse").put("delays", new JSONArray(new double [] {1, 5, 9}));
        col.getDecks().save(conf);
        Card c = col.getSched().getCard();
        // new cards
        ////////////////////////////////////////////////////////////////////////////////////////////////////
        
        assertEquals(30, col.getSched().nextIvl(c, 1));
        assertEquals(180, col.getSched().nextIvl(c, 2));
        assertEquals(4 * 86400, col.getSched().nextIvl(c, 3));
        col.getSched().answerCard(c, 1);
        // cards in learning
        ////////////////////////////////////////////////////////////////////////////////////////////////////
        assertEquals(30, col.getSched().nextIvl(c, 1));
        assertEquals(180, col.getSched().nextIvl(c, 2));
        assertEquals(4 * 86400, col.getSched().nextIvl(c, 3));
        col.getSched().answerCard(c, 2);
        assertEquals(30, col.getSched().nextIvl(c, 1));
        assertEquals(600, col.getSched().nextIvl(c, 2));
        assertEquals(4 * 86400, col.getSched().nextIvl(c, 3));
        col.getSched().answerCard(c, 2);
        // normal graduation is tomorrow
        assertEquals(1 * 86400, col.getSched().nextIvl(c, 2));
        assertEquals(4 * 86400, col.getSched().nextIvl(c, 3));
        // lapsed cards
        ////////////////////////////////////////////////////////////////////////////////////////////////////
        c.setType(CARD_TYPE_REV);
        c.setIvl(100);
        c.setFactor(STARTING_FACTOR);
        assertEquals(60, col.getSched().nextIvl(c, 1));
        assertEquals(100 * 86400, col.getSched().nextIvl(c, 2));
        assertEquals(100 * 86400, col.getSched().nextIvl(c, 3));
        // review cards
        ////////////////////////////////////////////////////////////////////////////////////////////////////
        c.setQueue(QUEUE_TYPE_REV);
        c.setIvl(100);
        c.setFactor(STARTING_FACTOR);
        // failing it should put it at 60s
        assertEquals(60, col.getSched().nextIvl(c, 1));
        // or 1 day if relearn is false
        conf.getJSONObject("lapse").put("delays", new JSONArray(new double [] {}));
        col.getDecks().save(conf);
        assertEquals(1 * 86400, col.getSched().nextIvl(c, 1));
        // (* 100 1.2 86400)10368000.0
        assertEquals(10368000, col.getSched().nextIvl(c, 2));
        // (* 100 2.5 86400)21600000.0
        assertEquals(21600000, col.getSched().nextIvl(c, 3));
        // (* 100 2.5 1.3 86400)28080000.0
        assertEquals(28080000, col.getSched().nextIvl(c, 4));
        // TODO: upstream is 10.8, try to understand the difference
        assertEquals("10.7 mo", without_unicode_isolation(col.getSched().nextIvlStr(getTargetContext(), c, 4)));
    }
    
    @Test
    public void test_misc() throws Exception {
        Collection col = getColV1();
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        Card c = note.cards().get(0);
        // burying
        col.getSched().buryNote(c.getNid());
        col.reset();
        assertNull(col.getSched().getCard());
        col.getSched().unburyCards();
        col.reset();
        assertNotNull(col.getSched().getCard());
    }
    
    @Test
    public void test_suspendV1() throws Exception {
        Collection col = getColV1();
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        Card c = note.cards().get(0);
        // suspending
        col.reset();
        assertNotNull(col.getSched().getCard());
        col.getSched().suspendCards(new long [] {c.getId()});
        col.reset();
        assertNull(col.getSched().getCard());
        // unsuspending
        col.getSched().unsuspendCards(new long [] {c.getId()});
        col.reset();
        assertNotNull(col.getSched().getCard());
        // should cope with rev cards being relearnt
        c.setDue(0);
        c.setIvl(100);
        c.setType(CARD_TYPE_REV);
        c.setQueue(QUEUE_TYPE_REV);
        c.flush();
        col.reset();
        c = col.getSched().getCard();
        col.getSched().answerCard(c, 1);
        assertTrue(c.getDue() >= Utils.now());
        assertEquals(QUEUE_TYPE_LRN, c.getQueue());
        assertEquals(CARD_TYPE_REV, c.getType());
        col.getSched().suspendCards(new long [] {c.getId()});
        col.getSched().unsuspendCards(new long [] {c.getId()});
        c.load();
        assertEquals(QUEUE_TYPE_REV, c.getQueue());
        assertEquals(CARD_TYPE_REV, c.getType());
        assertEquals(1, c.getDue());
        // should cope with cards in cram decks
        c.setDue(1);
        c.flush();
        col.getDecks().newDyn("tmp");
        col.getSched().rebuildDyn();
        c.load();
        assertNotEquals(1, c.getDue());
        assertNotEquals(1, c.getDid());
        col.getSched().suspendCards(new long [] {c.getId()});
        c.load();
        assertEquals(1, c.getDue());
        assertEquals(1, c.getDid());
    }
    
    @Test
    public void test_cram() throws Exception {
        Collection col = getColV1();
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        Card c = note.cards().get(0);
        c.setIvl(100);
        c.setQueue(CARD_TYPE_REV);
        c.setType(QUEUE_TYPE_REV);
        // due in 25 days, so it's been waiting 75 days
        c.setDue(col.getSched().getToday() + 25);
        c.setMod(1);
        c.setFactor(STARTING_FACTOR);
        c.startTimer();
        c.flush();
        col.reset();
        assertArrayEquals(new int[]{0, 0, 0}, col.getSched().counts());
        Card cardcopy = c.clone();
        // create a dynamic deck and refresh it
        long did = col.getDecks().newDyn("Cram");
        col.getSched().rebuildDyn(did);
        col.reset();
        // should appear as new in the deck list
        // todo: which sort
        // and should appear in the counts
        assertArrayEquals(new int[]{1, 0, 0}, col.getSched().counts());
        // grab it and check estimates
        c = col.getSched().getCard();
        assertEquals(2, col.getSched().answerButtons(c));
        assertEquals(600, col.getSched().nextIvl(c, 1));
        assertEquals(138 * 60 * 60 * 24, col.getSched().nextIvl(c, 2));
        Deck cram = col.getDecks().get(did);
        cram.put("delays", new JSONArray(new double [] {1, 10}));
        col.getDecks().save(cram);
        assertEquals(3, col.getSched().answerButtons(c));
        assertEquals(60, col.getSched().nextIvl(c, 1));
        assertEquals(600, col.getSched().nextIvl(c, 2));
        assertEquals(138 * 60 * 60 * 24, col.getSched().nextIvl(c, 3));
        col.getSched().answerCard(c, 2);
        // elapsed time was 75 days
        // factor = 2.5+1.2/2 = 1.85
        // int(75*1.85) = 138
        assertEquals(138, c.getIvl());
        assertEquals(138, c.getODue());
        assertEquals(QUEUE_TYPE_LRN, c.getQueue());
        // should be logged as a cram rep
        assertEquals(3, col.getDb().queryLongScalar("select type from revlog order by id desc limit 1"));
        // check ivls again
        assertEquals(60, col.getSched().nextIvl(c, 1));
        assertEquals(138 * 60 * 60 * 24, col.getSched().nextIvl(c, 2));
        assertEquals(138 * 60 * 60 * 24, col.getSched().nextIvl(c, 3));
        // when it graduates, due is updated
        c = col.getSched().getCard();
        col.getSched().answerCard(c, 2);
        assertEquals(138, c.getIvl());
        assertEquals(138, c.getDue());
        assertEquals(QUEUE_TYPE_REV, c.getQueue());
        // and it will have moved back to the previous deck
        assertEquals(1, c.getDid());
        // cram the deck again
        col.getSched().rebuildDyn(did);
        col.reset();
        c = col.getSched().getCard();
        // check ivls again - passing should be idempotent
        assertEquals(60, col.getSched().nextIvl(c, 1));
        assertEquals(600, col.getSched().nextIvl(c, 2));
        assertEquals(138 * 60 * 60 * 24, col.getSched().nextIvl(c, 3));
        col.getSched().answerCard(c, 2);
        assertEquals(138, c.getIvl());
        assertEquals(138, c.getODue());
        // fail
        col.getSched().answerCard(c, 1);
        assertEquals(60, col.getSched().nextIvl(c, 1));
        assertEquals(600, col.getSched().nextIvl(c, 2));
        assertEquals(86400, col.getSched().nextIvl(c, 3));
        // delete the deck, returning the card mid-study
        col.getDecks().rem(col.getDecks().selected());
        assertEquals(1, col.getSched().deckDueTree().size());
        c.load();
        assertEquals(1, c.getIvl());
        assertEquals(col.getSched().getToday()+ 1, c.getDue());
        // make it due
        col.reset();
        assertArrayEquals(new int[]{0, 0, 0}, col.getSched().counts());
        c.setDue(-5);
        c.setIvl(100);
        c.flush();
        col.reset();
        assertArrayEquals(new int[]{0, 0, 1}, col.getSched().counts());
        // cram again
        did = col.getDecks().newDyn("Cram");
        col.getSched().rebuildDyn(did);
        col.reset();
        assertArrayEquals(new int[]{0, 0, 1}, col.getSched().counts());
        c.load();
        assertEquals(4, col.getSched().answerButtons(c));
        // add a sibling so we can test minSpace, etc
        Card c2 = c.clone();
        c2.setId(0);
        c2.setOrd(1);
        c2.setDue(325);
        c2.flush();
        // should be able to answer it
        c = col.getSched().getCard();
        col.getSched().answerCard(c, 4);
        // it should have been moved back to the original deck
        assertEquals(1, c.getDid());
    }
    
    @Test
    public void test_cram_rem() throws Exception {
        Collection col = getColV1();
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        long oldDue = note.cards().get(0).getDue();
        long did = col.getDecks().newDyn("Cram");
        col.getSched().rebuildDyn(did);
        col.reset();
        Card c = col.getSched().getCard();
        col.getSched().answerCard(c, 2);
        // answering the card will put it in the learning queue
        assertEquals(QUEUE_TYPE_LRN, c.getQueue());
        assertEquals(CARD_TYPE_LRN, c.getType());
        assertNotEquals(c.getDue(), oldDue);
        // if we terminate cramming prematurely it should be set back to new
        col.getSched().emptyDyn(did);
        c.load();
        assertEquals(QUEUE_TYPE_NEW, c.getQueue());
        assertEquals(CARD_TYPE_NEW, c.getType());
        assertEquals(oldDue, c.getDue());
    }
    
    @Test
    public void test_cram_resched() throws Exception {
        // add card
        Collection col = getColV1();
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        // cram deck
        long did = col.getDecks().newDyn("Cram");
        Deck cram = col.getDecks().get(did);
        cram.put("resched", false);
        col.getDecks().save(cram);
        col.getSched().rebuildDyn(did);
        col.reset();
        // graduate should return it to new
        Card c = col.getSched().getCard();
        
        assertEquals(60, col.getSched().nextIvl(c, 1));
        assertEquals(600, col.getSched().nextIvl(c, 2));
        assertEquals(0, col.getSched().nextIvl(c, 3));
        assertEquals("(end)", col.getSched().nextIvlStr(getTargetContext(), c, 3));
        col.getSched().answerCard(c, 3);
        assertEquals(CARD_TYPE_NEW, c.getType());
        assertEquals(QUEUE_TYPE_NEW,  c.getQueue());
        // undue reviews should also be unaffected
        c.setIvl(100);
        c.setQueue(CARD_TYPE_REV);
        c.setType(QUEUE_TYPE_REV);
        c.setDue(col.getSched().getToday() + 25);
        c.setFactor(STARTING_FACTOR);
        c.flush();
        Card cardcopy = c.clone();
        col.getSched().rebuildDyn(did);
        col.reset();
        c = col.getSched().getCard();
        assertEquals(600, col.getSched().nextIvl(c, 1));
        assertEquals(0, col.getSched().nextIvl(c, 2));
        assertEquals(0, col.getSched().nextIvl(c, 3));
        col.getSched().answerCard(c, 2);
        assertEquals(100, c.getIvl());
        assertEquals(col.getSched().getToday()+ 25, c.getDue());
        // check failure too
        c = cardcopy;
        c.flush();
        col.getSched().rebuildDyn(did);
        col.reset();
        c = col.getSched().getCard();
        col.getSched().answerCard(c, 1);
        col.getSched().emptyDyn(did);
        c.load();
        assertEquals(100, c.getIvl());
        assertEquals(col.getSched().getToday()+ 25, c.getDue());
        // fail+grad early
        c = cardcopy;
        c.flush();
        col.getSched().rebuildDyn(did);
        col.reset();
        c = col.getSched().getCard();
        col.getSched().answerCard(c, 1);
        col.getSched().answerCard(c, 3);
        col.getSched().emptyDyn(did);
        c.load();
        assertEquals(100, c.getIvl());
        assertEquals(col.getSched().getToday()+ 25, c.getDue());
        // due cards - pass
        c = cardcopy;
        c.setDue(-25);
        c.flush();
        col.getSched().rebuildDyn(did);
        col.reset();
        c = col.getSched().getCard();
        col.getSched().answerCard(c, 3);
        col.getSched().emptyDyn(did);
        c.load();
        assertEquals(100, c.getIvl());
        assertEquals(-25, c.getDue());
        // fail
        c = cardcopy;
        c.setDue(-25);
        c.flush();
        col.getSched().rebuildDyn(did);
        col.reset();
        c = col.getSched().getCard();
        col.getSched().answerCard(c, 1);
        col.getSched().emptyDyn(did);
        c.load();
        assertEquals(100, c.getIvl());
        assertEquals(-25, c.getDue());
        // fail with normal grad
        c = cardcopy;
        c.setDue(-25);
        c.flush();
        col.getSched().rebuildDyn(did);
        col.reset();
        c = col.getSched().getCard();
        col.getSched().answerCard(c, 1);
        col.getSched().answerCard(c, 3);
        c.load();
        assertEquals(100, c.getIvl());
        assertEquals(-25, c.getDue());
        // lapsed card pulled into cram
        // col.getSched()._cardConf(c)['lapse']['mult']=0.5
        // col.getSched().answerCard(c, 1)
        // col.getSched().rebuildDyn(did)
        // col.reset()
        // c = col.getSched().getCard()
        // col.getSched().answerCard(c, 2)
        // print c.__dict__
    }
    
    @Test
    public void test_ordcycleV1() throws Exception {
        Collection col = getColV1();
        // add two more templates and set second active
        Model m = col.getModels().current();
        Models mm = col.getModels();
        JSONObject t = mm.newTemplate("Reverse");
        t.put("qfmt", "{{Back}}");
        t.put("afmt", "{{Front}}");
        mm.addTemplateModChanged(m, t);
        t = mm.newTemplate("f2");
        t.put("qfmt", "{{Front}}");
        t.put("afmt", "{{Back}}");
        mm.addTemplateModChanged(m, t);
        mm.save(m);
        // create a new note; it should have 3 cards
        Note note = col.newNote();
        note.setItem("Front","1");
        note.setItem("Back","1");
        col.addNote(note);
        assertEquals(3, col.cardCount());
        col.reset();
        // ordinals should arrive in order
        AbstractSched sched = col.getSched();
        Card c = sched.getCard();
        sched.answerCard(c, sched.answerButtons(c)-1); // not upstream. But we are not expecting multiple getCard without review
        assertEquals(0, c.getOrd());
        c = sched.getCard();
        sched.answerCard(c, sched.answerButtons(c)-1); // not upstream. But we are not expecting multiple getCard without review
        assertEquals(1, c.getOrd());
        c = sched.getCard();
        sched.answerCard(c, sched.answerButtons(c)-1); // not upstream. But we are not expecting multiple getCard without review
        assertEquals(2, c.getOrd());
    }
    
    @Test
    public void test_counts_idxV1() throws Exception {
        Collection col = getColV1();
        Note note = col.newNote();
        note.setItem("Front","one");
        note.setItem("Back","two");
        col.addNote(note);
        col.reset();
        assertArrayEquals(new int[]{1, 0, 0}, col.getSched().counts());
        Card c = col.getSched().getCard();
        // counter's been decremented but idx indicates 1
        assertArrayEquals(new int[]{0, 0, 0}, col.getSched().counts());
        assertEquals(0, col.getSched().countIdx(c));
        // answer to move to learn queue
        col.getSched().answerCard(c, 1);
        assertArrayEquals(new int[]{0, 2, 0}, col.getSched().counts());
        // fetching again will decrement the count
        c = col.getSched().getCard();
        assertArrayEquals(new int[]{0, 0, 0}, col.getSched().counts());
        assertEquals(1, col.getSched().countIdx(c));
        // answering should add it back again
        col.getSched().answerCard(c, 1);
        assertArrayEquals(new int[]{0, 2, 0}, col.getSched().counts());
    }
    
    @Test
    public void test_repCountsV1() throws Exception {
        Collection col = getColV1();
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        col.reset();
        // lrnReps should be accurate on pass/fail
        assertArrayEquals(new int[]{1, 0, 0}, col.getSched().counts());
        col.getSched().answerCard(col.getSched().getCard(), 1);
        assertArrayEquals(new int[]{0, 2, 0}, col.getSched().counts());
        col.getSched().answerCard(col.getSched().getCard(), 1);
        assertArrayEquals(new int[]{0, 2, 0}, col.getSched().counts());
        col.getSched().answerCard(col.getSched().getCard(), 2);
        assertArrayEquals(new int[]{0, 1, 0}, col.getSched().counts());
        col.getSched().answerCard(col.getSched().getCard(), 1);
        assertArrayEquals(new int[]{0, 2, 0}, col.getSched().counts());
        col.getSched().answerCard(col.getSched().getCard(), 2);
        assertArrayEquals(new int[]{0, 1, 0}, col.getSched().counts());
        col.getSched().answerCard(col.getSched().getCard(), 2);
        assertArrayEquals(new int[]{0, 0, 0}, col.getSched().counts());
        note = col.newNote();
        note.setItem("Front","two");
        col.addNote(note);
        col.reset();
        // initial pass should be correct too
        col.getSched().answerCard(col.getSched().getCard(), 2);
        assertArrayEquals(new int[]{0, 1, 0}, col.getSched().counts());
        col.getSched().answerCard(col.getSched().getCard(), 1);
        assertArrayEquals(new int[]{0, 2, 0}, col.getSched().counts());
        col.getSched().answerCard(col.getSched().getCard(), 3);
        assertArrayEquals(new int[]{0, 0, 0}, col.getSched().counts());
        // immediate graduate should work
        note = col.newNote();
        note.setItem("Front","three");
        col.addNote(note);
        col.reset();
        col.getSched().answerCard(col.getSched().getCard(), 3);
        assertArrayEquals(new int[]{0, 0, 0}, col.getSched().counts());
        // and failing a review should too
        note = col.newNote();
        note.setItem("Front","three");
        col.addNote(note);
        Card c = note.cards().get(0);
        c.setType(CARD_TYPE_REV);
        c.setQueue(QUEUE_TYPE_REV);
        c.setDue(col.getSched().getToday());
        c.flush();
        col.reset();
        assertArrayEquals(new int[]{0, 0, 1}, col.getSched().counts());
        col.getSched().answerCard(col.getSched().getCard(), 1);
        assertArrayEquals(new int[]{0, 1, 0}, col.getSched().counts());
    }
    
    @Test
    public void test_timingV1() throws Exception {
        Collection col = getColV1();
        // add a few review cards, due today
        for (int i=0; i < 5; i++) {
            Note note = col.newNote();
            note.setItem("Front", "num" + i);
            col.addNote(note);
            Card c = note.cards().get(0);
            c.setType(CARD_TYPE_REV);
            c.setQueue(QUEUE_TYPE_REV);
            c.setDue(0);
            c.flush();
        }
        // fail the first one
        col.reset();
        Card c = col.getSched().getCard();
        // set a a fail delay of 4 seconds
        DeckConfig conf = col.getSched()._cardConf(c);
        conf.getJSONObject("lapse").getJSONArray("delays").put(0, 1 / 15.0);
        col.getDecks().save(conf);
        col.getSched().answerCard(c, 1);
        // the next card should be another review
        c = col.getSched().getCard();
        assertEquals(QUEUE_TYPE_REV, c.getQueue());
        /* TODO time
        // but if we wait for a few seconds, the failed card should come back
        orig_time = time.time;
        
        def adjusted_time():
        return orig_time() + 5;
        
        time.time = adjusted_time;
        c = col.getSched().getCard();
        assertEquals(QUEUE_TYPE_LRN, c.getQueue());
        time.time = orig_time;
        
        */
    }
    
    @Test
    public void test_collapseV1() throws Exception {
        Collection col = getColV1();
        // add a note
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        col.reset();
        // test collapsing
        Card c = col.getSched().getCard();
        col.getSched().answerCard(c, 1);
        c = col.getSched().getCard();
        col.getSched().answerCard(c, 3);
        assertNull(col.getSched().getCard());
    }
    
    @Test
    public void test_deckDueV1() throws Exception {
        Collection col = getColV1();
        // add a note with default deck
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        // and one that's a child
        note = col.newNote();
        note.setItem("Front","two");
        long default1 = col.getDecks().id("Default::1");
        note.model().put("did", default1);
        col.addNote(note);
        // make it a review card
        Card c = note.cards().get(0);
        c.setQueue(QUEUE_TYPE_REV);
        c.setDue(0);
        c.flush();
        // add one more with a new deck
        note = col.newNote();
        note.setItem("Front","two");
        JSONObject foobar = note.model().put("did", col.getDecks().id("foo::bar"));
        col.addNote(note);
        // and one that's a sibling
        note = col.newNote();
        note.setItem("Front","three");
        JSONObject foobaz = note.model().put("did", col.getDecks().id("foo::baz"));
        col.addNote(note);
        col.reset();
        assertEquals(5, col.getDecks().allSortedNames().size());
        AbstractSched.DeckDueTreeNode tree = col.getSched().deckDueTree().get(0);
        assertEquals("Default", tree.getLastDeckNameComponent());
        // sum of child and parent
        assertEquals(1, tree.getDid());
        assertEquals(1, tree.getRevCount());
        assertEquals(1, tree.getNewCount());
        // child count is just review
        AbstractSched.DeckDueTreeNode child = tree.getChildren().get(0);
        assertEquals("1", child.getLastDeckNameComponent());
        assertEquals(default1, child.getDid());
        assertEquals(1, child.getRevCount());
        assertEquals(0, child.getNewCount());
        // code should not fail if a card has an invalid deck
        c.setDid(12345);
        c.flush();
        col.getSched().deckDueTree();
    }
    
    @Test
    public void test_deckFlowV1() throws Exception {
        Collection col = getColV1();
        // add a note with default deck
        Note note = col.newNote();
        note.setItem("Front", "one");
        col.addNote(note);
        // and one that's a child
        note = col.newNote();
        note.setItem("Front", "two");
        JSONObject default1 = note.model().put("did", col.getDecks().id("Default::2"));
        col.addNote(note);
        // and another that's higher up
        note = col.newNote();
        note.setItem("Front", "three");
        default1 = note.model().put("did", col.getDecks().id("Default::1"));
        col.addNote(note);
        // should get top level one first, then ::1, then ::2
        col.reset();
        assertArrayEquals(new int[] {3, 0, 0}, col.getSched().counts());
        for (String i : new String[] {"one", "three", "two"}) {
            Card c = col.getSched().getCard();
            assertEquals(c.note().getItem("Front"), i);
            col.getSched().answerCard(c, 2);
        }
    }
    
    @Test
    public void test_reorderV1() throws Exception {
        Collection col = getColV1();
        // add a note with default deck
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        Note note2 = col.newNote();
        note2.setItem("Front","two");
        col.addNote(note2);
        assertEquals(2, note2.cards().get(0).getDue());
        boolean found = false;
        // 50/50 chance of being reordered
        for (int i=0; i < 20; i++) {
            col.getSched().randomizeCards(1);
            if (note.cards().get(0).getDue() != note.getId()) {
                found = true;
                break;
            }
        }
        assertTrue(found);
        col.getSched().orderCards(1);
        assertEquals(1, note.cards().get(0).getDue());
        // shifting
        Note note3 = col.newNote();
        note3.setItem("Front","three");
        col.addNote(note3);
        Note note4 = col.newNote();
        note4.setItem("Front","four");
        col.addNote(note4);
        assertEquals(1, note.cards().get(0).getDue());
        assertEquals(2, note2.cards().get(0).getDue());
        assertEquals(3, note3.cards().get(0).getDue());
        assertEquals(4, note4.cards().get(0).getDue());
        /* todo sortCard
           col.getSched().sortCards(new long [] {note3.cards().get(0).getId(), note4.cards().get(0).getId()}, start=1, shift=true);
           assertEquals(3, note.cards().get(0).getDue());
           assertEquals(4, note2.cards().get(0).getDue());
           assertEquals(1, note3.cards().get(0).getDue());
           assertEquals(2, note4.cards().get(0).getDue());
           
        */
    }
    
    @Test
    public void test_forgetV1() throws Exception {
        Collection col = getColV1();
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        Card c = note.cards().get(0);
        c.setQueue(QUEUE_TYPE_REV);
        c.setType(CARD_TYPE_REV);
        c.setIvl(100);
        c.setDue(0);
        c.flush();
        col.reset();
        assertArrayEquals(new int[]{0, 0, 1}, col.getSched().counts());
        col.getSched().forgetCards(new long [] {c.getId()});
        col.reset();
        assertArrayEquals(new int[]{1, 0, 0}, col.getSched().counts());
    }
    
    @Test
    public void test_reschedV1() throws Exception {
        Collection col = getColV1();
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        Card c = note.cards().get(0);
        col.getSched().reschedCards(new long [] {c.getId()}, 0, 0);
        c.load();
        assertEquals(col.getSched().getToday(), c.getDue());
        assertEquals(1, c.getIvl());
        assertEquals(CARD_TYPE_REV, c.getType());
        assertEquals(QUEUE_TYPE_REV, c.getQueue());
        col.getSched().reschedCards(new long [] {c.getId()}, 1, 1);
        c.load();
        assertEquals(col.getSched().getToday()+ 1, c.getDue());
        assertEquals(+1, c.getIvl());
    }
    
    @Test
    public void test_norelearnV1() throws Exception {
        Collection col = getColV1();
        // add a note
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        Card c = note.cards().get(0);
        c.setType(CARD_TYPE_REV);
        c.setQueue(QUEUE_TYPE_REV);
        c.setDue(0);
        c.setFactor(STARTING_FACTOR);
        c.setReps(3);
        c.setLapses(1);
        c.setIvl(100);
        c.startTimer();
        c.flush();
        col.reset();
        col.getSched().answerCard(c, 1);
        col.getSched()._cardConf(c).getJSONObject("lapse").put("delays", new JSONArray(new double [] {}));
        col.getSched().answerCard(c, 1);
    }
    
    @Test
    public void test_failmultV1()   throws Exception {
        Collection col = getColV1();
        Note note = col.newNote();
        note.setItem("Front","one");
        note.setItem("Back","two");
        col.addNote(note);
        Card c = note.cards().get(0);
        c.setType(CARD_TYPE_REV);
        c.setQueue(QUEUE_TYPE_REV);
        c.setIvl(100);
        c.setDue(col.getSched().getToday() - c.getIvl());
        c.setFactor(STARTING_FACTOR);
        c.setReps(3);
        c.setLapses(1);
        c.startTimer();
        c.flush();
        DeckConfig conf = col.getSched()._cardConf(c);
        conf.getJSONObject("lapse").put("mult", 0.5);
        col.getDecks().save(conf);
        c = col.getSched().getCard();
        col.getSched().answerCard(c, 1);
        assertEquals(50, c.getIvl());
        col.getSched().answerCard(c, 1);
        assertEquals(25, c.getIvl());
    }
    /*****************
     ** SchedV2      *
     *****************/
    private Collection getColV2() throws Exception{
        Collection col = getCol();
        col.changeSchedulerVer(2);
        return col;
    }
    
    @Test
    public void test_clock() throws Exception  {
        Collection col = getColV2();
        if ((col.getSched().getDayCutoff() - intTime()) < 10 * 60) {
            throw new Exception("Unit tests will fail around the day rollover.");
        }
    }
    
    private boolean checkRevIvl(Collection col, Card c, int targetIvl) {
        Pair<Integer, Integer> min_max = col.getSched()._fuzzIvlRange(targetIvl);
        return min_max.first <= c.getIvl() && c.getIvl() <= min_max.second;
    }
    
    @Test
    public void test_basics() throws Exception  {
        Collection col = getColV1();
        col.reset();
        assertNull(col.getSched().getCard());
    }
    
    @Test
    public void test_new_v2()  throws Exception  {
        Collection col = getColV2();
        col.reset();
        // assertEquals(0, col.getSched().newCount);TODO: newCount getter
        // add a note
        Note note = col.newNote();
        note.setItem("Front","one");
        note.setItem("Back","two");
        col.addNote(note);
        col.reset();
        // assertEquals(1, col.getSched().newCount);TODO: newCount getter
        // fetch it
        Card c = col.getSched().getCard();
        assertNotNull(c);
        assertEquals(QUEUE_TYPE_NEW, c.getQueue());
        assertEquals(CARD_TYPE_NEW, c.getType());
        // if we answer it, it should become a learn card
        long t = intTime();
        col.getSched().answerCard(c, 1);
        assertEquals(QUEUE_TYPE_LRN, c.getQueue());
        assertEquals(CARD_TYPE_LRN, c.getType());
        assertTrue(c.getDue() >= t);
        
        // disabled for now, as the learn fudging makes this randomly fail
        // // the default order should ensure siblings are not seen together, and
        // // should show all cards
        // Model m = col.getModels().current(); Models mm = col.getModels()
        // JSONObject t = mm.newTemplate("Reverse")
        // t['qfmt'] = "{{Back}}"
        // t['afmt'] = "{{Front}}"
        // mm.addTemplateModChanged(m, t)
        // mm.save(m)
        // note = col.newNote()
        // note['Front'] = u"2"; note['Back'] = u"2"
        // col.addNote(note)
        // note = col.newNote()
        // note['Front'] = u"3"; note['Back'] = u"3"
        // col.addNote(note)
        // col.reset()
        // qs = ("2", "3", "2", "3")
        // for (int n = 0; n < 4; n++) {
        //     c = col.getSched().getCard()
        //     assertTrue(qs[n] in c.q())
        //     col.getSched().answerCard(c, 2)
        // }
    }
    
    @Test
    public void test_newLimits_V2()  throws Exception  {
        Collection col = getColV2();
        // add some notes
        long deck2 = col.getDecks().id("Default::foo");
        for (int i=0; i < 30; i++) {
            Note note = col.newNote();
            note.setItem("Front", Integer.toString(i));
            if (i > 4) {
                note.model().put("did", deck2);
            }
            col.addNote(note);
        }
        // give the child deck a different configuration
        long c2 = col.getDecks().confId("new conf");
        col.getDecks().setConf(col.getDecks().get(deck2), c2);
        col.reset();
        // both confs have defaulted to a limit of 20
        // assertEquals(20, col.getSched().newCount);TODO: newCount getter
        // first card we get comes from parent
        Card c = col.getSched().getCard();
        assertEquals(1, c.getDid());
        // limit the parent to 10 cards, meaning we get 10 in total
        DeckConfig conf1 = col.getDecks().confForDid(1);
        conf1.getJSONObject("new").put("perDay", 10);
        col.getDecks().save(conf1);
        col.reset();
        // assertEquals(10, col.getSched().newCount);TODO: newCount getter
        // if we limit child to 4, we should get 9
        DeckConfig conf2 = col.getDecks().confForDid(deck2);
        conf2.getJSONObject("new").put("perDay", 4);
        col.getDecks().save(conf2);
        col.reset();
        //assertEquals(9, col.getSched().newCount);TODO: newCount getter
    }
    
    @Test
    public void test_newBoxes_v2()  throws Exception  {
        Collection col = getColV2();
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        col.reset();
        Card c = col.getSched().getCard();
        DeckConfig conf = col.getSched()._cardConf(c);
        conf.getJSONObject("new").put("delays", new JSONArray(new double [] {1, 2, 3, 4, 5}));
        col.getDecks().save(conf);
        col.getSched().answerCard(c, 2);
        // should handle gracefully
        conf.getJSONObject("new").put("delays", new JSONArray(new double [] {1}));
        col.getDecks().save(conf);
        col.getSched().answerCard(c, 2);
    }
    
    @Test
    public void test_learnV2()  throws Exception  {
        Collection col = getColV2();
        // add a note
        Note note = col.newNote();
        note.setItem("Front","one");
        note.setItem("Back","two");
        col.addNote(note);
        // set as a learn card and rebuild queues
        col.getDb().execute("update cards set queue=0, type=0");
        col.reset();
        // sched.getCard should return it, since it's due in the past
        Card c = col.getSched().getCard();
        assertNotNull(c);
        DeckConfig conf = col.getSched()._cardConf(c);
        conf.getJSONObject("new").put("delays", new JSONArray(new double [] {0.5, 3, 10}));
        col.getDecks().save(conf);
        // fail it
        col.getSched().answerCard(c, 1);
        // it should have three reps left to graduation
        assertEquals(3, c.getLeft() % 1000);
        assertEquals(3 , c.getLeft() / 1000);
        // it should be due in 30 seconds
        long t = Math.round(c.getDue() - Utils.now());
        assertTrue(t >= 25 && t <= 40);
        // pass it once
        col.getSched().answerCard(c, 3);
        // it should be due in 3 minutes
        double dueIn = c.getDue() - Utils.now();
        assertTrue(178 <= dueIn && dueIn <= 180 * 1.25);
        assertEquals(2, c.getLeft() % 1000);
        assertEquals(2 , c.getLeft() / 1000);
        // check log is accurate
        Cursor log = col.getDb().getDatabase().query("select * from revlog order by id desc");
        assertTrue(log.moveToFirst());
        assertEquals(3, log.getInt(3));
        assertEquals(-180, log.getInt(4));
        assertEquals(-30, log.getInt(5));
        // pass again
        col.getSched().answerCard(c, 3);
        // it should be due in 10 minutes
        dueIn = c.getDue() - Utils.now();
        assertTrue(599 <= dueIn && dueIn <= 600 * 1.25);
        assertEquals(1, c.getLeft() % 1000);
        assertEquals(1 , c.getLeft() / 1000);
        // the next pass should graduate the card
        assertEquals(QUEUE_TYPE_LRN, c.getQueue());
        assertEquals(CARD_TYPE_LRN, c.getType());
        col.getSched().answerCard(c, 3);
        assertEquals(QUEUE_TYPE_REV, c.getQueue());
        assertEquals(CARD_TYPE_REV, c.getType());
        // should be due tomorrow, with an interval of 1
        assertEquals(col.getSched().getToday()+ 1, c.getDue());
        assertEquals(1, c.getIvl());
        // or normal removal
        c.setType(CARD_TYPE_NEW);
        c.setQueue(QUEUE_TYPE_LRN);
        col.getSched().answerCard(c, 4);
        assertEquals(CARD_TYPE_REV, c.getType());
        assertEquals(QUEUE_TYPE_REV, c.getQueue());
        assertTrue(checkRevIvl(col, c, 4));
        // revlog should have been updated each time
        assertEquals(5, col.getDb().queryScalar("select count() from revlog where type = 0"));
    }
    
    @Test
    public void test_relearn() throws Exception {
        Collection col = getColV2();
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        Card c = note.cards().get(0);
        c.setIvl(100);
        c.setDue(col.getSched().getToday());
        c.setQueue(CARD_TYPE_REV);
        c.setType(QUEUE_TYPE_REV);
        c.flush();
        
        // fail the card
        col.reset();
        c = col.getSched().getCard();
        col.getSched().answerCard(c, 1);
        assertEquals(QUEUE_TYPE_LRN, c.getQueue());
        assertEquals(CARD_TYPE_RELEARNING, c.getType());
        assertEquals(1, c.getIvl());
        
        // immediately graduate it
        col.getSched().answerCard(c, 4);
        assertEquals(CARD_TYPE_REV, c.getType());
        assertEquals(QUEUE_TYPE_REV, c.getQueue());
        assertEquals(2, c.getIvl());
        assertEquals(col.getSched().getToday()+ c.getIvl(), c.getDue());
    }
    
    @Test
    public void test_relearn_no_steps() throws Exception {
        Collection col = getColV2();
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        Card c = note.cards().get(0);
        c.setIvl(100);
        c.setDue(col.getSched().getToday());
        c.setQueue(CARD_TYPE_REV);
        c.setType(QUEUE_TYPE_REV);
        c.flush();
        
        DeckConfig conf = col.getDecks().confForDid(1);
        conf.getJSONObject("lapse").put("delays", new JSONArray(new double [] {}));
        col.getDecks().save(conf);
        
        // fail the card
        col.reset();
        c = col.getSched().getCard();
        col.getSched().answerCard(c, 1);
        assertEquals(CARD_TYPE_REV, c.getType());
        assertEquals(QUEUE_TYPE_REV, c.getQueue());
    }
    
    @Test
    public void test_learn_collapsedV2() throws Exception {
        Collection col = getColV2();
        // add 2 notes
        Note note = col.newNote();
        note.setItem("Front","1");
        col.addNote(note);
        note = col.newNote();
        note.setItem("Front","2");
        col.addNote(note);
        // set as a learn card and rebuild queues
        col.getDb().execute("update cards set queue=0, type=0");
        col.reset();
        // should get '1' first
        Card c = col.getSched().getCard();
        assertTrue(c.q().endsWith("1"));
        // pass it so it's due in 10 minutes
        col.getSched().answerCard(c, 3);
        // get the other card
        c = col.getSched().getCard();
        assertTrue(c.q().endsWith("2"));
        // fail it so it's due in 1 minute
        col.getSched().answerCard(c, 1);
        // we shouldn't get the same card again
        c = col.getSched().getCard();
        assertFalse(c.q().endsWith("2"));
    }
    
    @Test
    public void test_learn_dayV2() throws Exception {
        Collection col = getColV2();
        // add a note
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        col.getSched().reset();
        Card c = col.getSched().getCard();
        DeckConfig conf = col.getSched()._cardConf(c);
        conf.getJSONObject("new").put("delays", new JSONArray(new double [] {1, 10, 1440, 2880}));
        col.getDecks().save(conf);
        // pass it
        col.getSched().answerCard(c, 3);
        // two reps to graduate, 1 more today
        assertEquals(3, c.getLeft() % 1000);
        assertEquals(1 , c.getLeft() / 1000);
        assertArrayEquals(new int[]{0, 1, 0}, col.getSched().counts());
        c = col.getSched().getCard();
        
        assertEquals(86400, col.getSched().nextIvl(c, 3));
        // answering it will place it in queue 3
        col.getSched().answerCard(c, 3);
        assertEquals(col.getSched().getToday()+ 1, c.getDue());
        assertEquals(QUEUE_TYPE_DAY_LEARN_RELEARN, c.getQueue());
        assertNull(col.getSched().getCard());
        // for testing, move it back a day
        c.setDue(c.getDue() - 1);
        c.flush();
        col.reset();
        assertArrayEquals(new int[]{0, 1, 0}, col.getSched().counts());
        c = col.getSched().getCard();
        // nextIvl should work
        assertEquals(86400 * 2, col.getSched().nextIvl(c, 3));
        // if we fail it, it should be back in the correct queue
        col.getSched().answerCard(c, 1);
        assertEquals(QUEUE_TYPE_LRN, c.getQueue());
        col.undo();
        col.reset();
        c = col.getSched().getCard();
        col.getSched().answerCard(c, 3);
        // simulate the passing of another two days
        c.setDue(c.getDue() - 2);
        c.flush();
        col.reset();
        // the last pass should graduate it into a review card
        assertEquals(86400, col.getSched().nextIvl(c, 3));
        col.getSched().answerCard(c, 3);
        assertEquals(CARD_TYPE_REV, c.getType());
        assertEquals(QUEUE_TYPE_REV, c.getQueue());
        // if the lapse step is tomorrow, failing it should handle the counts
        // correctly
        c.setDue(0);
        c.flush();
        col.reset();
        assertArrayEquals(new int[]{0, 0, 1}, col.getSched().counts());
        conf = col.getSched()._cardConf(c);
        conf.getJSONObject("lapse").put("delays", new JSONArray(new double [] {1440}));
        col.getDecks().save(conf);
        c = col.getSched().getCard();
        col.getSched().answerCard(c, 1);
        assertEquals(QUEUE_TYPE_DAY_LEARN_RELEARN, c.getQueue());
        assertArrayEquals(new int[]{0, 0, 0}, col.getSched().counts());
    }
    
    @Test
    public void test_reviewsV2() throws Exception {
        Collection col = getColV2();
        // add a note
        Note note = col.newNote();
        note.setItem("Front","one");
        note.setItem("Back","two");
        col.addNote(note);
        // set the card up as a review card, due 8 days ago
        Card c = note.cards().get(0);
        c.setType(CARD_TYPE_REV);
        c.setQueue(QUEUE_TYPE_REV);
        c.setDue(col.getSched().getToday() - 8);
        c.setFactor(STARTING_FACTOR);
        c.setReps(3);
        c.setLapses(1);
        c.setIvl(100);
        c.startTimer();
        c.flush();
        // save it for later use as well
        Card cardcopy = c.clone();
        // try with an ease of 2
        ////////////////////////////////////////////////////////////////////////////////////////////////////
        c = cardcopy.clone();
        c.flush();
        col.reset();
        col.getSched().answerCard(c, 2);
        assertEquals(QUEUE_TYPE_REV, c.getQueue());
        // the new interval should be (100) * 1.2 = 120
        assertTrue(checkRevIvl(col, c, 120));
        assertEquals(col.getSched().getToday()+ c.getIvl(), c.getDue());
        // factor should have been decremented
        assertEquals(2350, c.getFactor());
        // check counters
        assertEquals(1, c.getLapses());
        assertEquals(4, c.getReps());
        // ease 3
        ////////////////////////////////////////////////////////////////////////////////////////////////////
        c = cardcopy.clone();
        c.flush();
        col.getSched().answerCard(c, 3);
        // the new interval should be (100 + 8/2) * 2.5 = 260
        assertTrue(checkRevIvl(col, c, 260));
        assertEquals(col.getSched().getToday()+ c.getIvl(), c.getDue());
        // factor should have been left alone
        assertEquals(STARTING_FACTOR, c.getFactor());
        // ease 4
        ////////////////////////////////////////////////////////////////////////////////////////////////////
        c = cardcopy.clone();
        c.flush();
        col.getSched().answerCard(c, 4);
        // the new interval should be (100 + 8) * 2.5 * 1.3 = 351
        assertTrue(checkRevIvl(col, c, 351));
        assertEquals(col.getSched().getToday()+ c.getIvl(), c.getDue());
        // factor should have been increased
        assertEquals(2650, c.getFactor());
        // leech handling
        ////////////////////////////////////////////////////////////////////////////////////////////////////
        DeckConfig conf = col.getDecks().getConf(1);
        conf.getJSONObject("lapse").put("leechAction", LEECH_SUSPEND);
        col.getDecks().save(conf);
        c = cardcopy.clone();
        c.setLapses(7);
        c.flush();
        /* todo hook
        // steup hook
        hooked = new [] {};
        
        def onLeech(card):
        hooked.append(1);
        
        hooks.card_did_leech.append(onLeech);
        col.getSched().answerCard(c, 1);
        assertTrue(hooked);
        assertEquals(QUEUE_TYPE_SUSPENDED, c.getQueue());
        c.load();
        assertEquals(QUEUE_TYPE_SUSPENDED, c.getQueue());
        */
    }
    
    @Test
    public void test_review_limits() throws Exception {
        Collection col=getColV2();
        
        Deck parent=col.getDecks().get(col.getDecks().id("parent"));
        Deck child=col.getDecks().get(col.getDecks().id("parent::child"));
        
        DeckConfig pconf=col.getDecks().getConf(col.getDecks().confId("parentConf"));
        DeckConfig cconf=col.getDecks().getConf(col.getDecks().confId("childConf"));
        
        pconf.getJSONObject("rev").put("perDay",5);
        col.getDecks().updateConf(pconf);
        col.getDecks().setConf(parent,pconf.getLong("id"));
        cconf.getJSONObject("rev").put("perDay",10);
        col.getDecks().updateConf(cconf);
        col.getDecks().setConf(child,cconf.getLong("id"));
        
        Model m=col.getModels().current();
        m.put("did",child.getLong("id"));
        col.getModels().save(m,false);
        
        // add some cards
        for(int i=0;i< 20;i++){
            Note note=col.newNote();
            note.setItem("Front","one");
            note.setItem("Back","two");
            col.addNote(note);
            
            // make them reviews
            Card c = note.cards().get(0);
            c.setQueue(CARD_TYPE_REV);
            c.setType(QUEUE_TYPE_REV);
            c.setDue(0);
            c.flush();
        }

        // position 0 is default deck. Different from upstream
        AbstractSched.DeckDueTreeNode tree = col.getSched().deckDueTree().get(1);
        // (('parent', 1514457677462, 5, 0, 0, (('child', 1514457677463, 5, 0, 0, ()),)))
        assertEquals("parent", tree.getFullDeckName());
        assertEquals(5,  tree.getRevCount());  // paren, tree.review_count)t
        assertEquals(5, tree.getChildren().get(0).getRevCount());
        
        // .counts() should match
        col.getDecks().select(child.getLong("id"));
        col.getSched().reset();
        assertArrayEquals(new int[]{0, 0, 5}, col.getSched().counts());
        
        // answering a card in the child should decrement parent count
        Card c = col.getSched().getCard();
        col.getSched().answerCard(c, 3);
        assertArrayEquals(new int[]{0, 0, 4}, col.getSched().counts());
        
        tree = col.getSched().deckDueTree().get(1);
        assertEquals(4 , tree.getRevCount());
        assertEquals(4 , tree.getChildren().get(0).getRevCount());
    }
    
    @Test
    public void test_button_spacingV2() throws Exception {
        Collection col = getColV2();
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        // 1 day ivl review card due now
        Card c = note.cards().get(0);
        c.setType(CARD_TYPE_REV);
        c.setQueue(QUEUE_TYPE_REV);
        c.setDue(col.getSched().getToday());
        c.setReps(1);
        c.setIvl(1);
        c.startTimer();
        c.flush();
        col.reset();
        // Upstream, there is no space in 2d
        assertEquals("2 d", without_unicode_isolation(col.getSched().nextIvlStr(getTargetContext(), c, 2)));
        assertEquals("3 d", without_unicode_isolation(col.getSched().nextIvlStr(getTargetContext(), c, 3)));
        assertEquals("4 d", without_unicode_isolation(col.getSched().nextIvlStr(getTargetContext(), c, 4)));
        
        // if hard factor is <= 1, then hard may not increase
        DeckConfig conf = col.getDecks().confForDid(1);
        conf.getJSONObject("rev").put("hardFactor", 1);
        col.getDecks().save(conf);
        assertEquals("1 d", without_unicode_isolation(col.getSched().nextIvlStr(getTargetContext(), c, 2)));
    }
    
    @Test
    public void test_overdue_lapseV2() throws Exception {
        // disabled in commit 3069729776990980f34c25be66410e947e9d51a2
        return;
        /* Upstream does not execute it
           Collection col = getColV2()  // pylint: disable=unreachable
           // add a note
           Note note = col.newNote();
           note.setItem("Front","one");
           col.addNote(note);
           // simulate a review that was lapsed and is now due for its normal review
           Card c = note.cards().get(0);
           c.setType(CARD_TYPE_REV);
           c.setQueue(QUEUE_TYPE_LRN);
           c.setDue(-1);
           c.setODue(-1);
           c.setFactor(STARTING_FACTOR);
           c.setLeft(2002);
           c.setIvl(0);
           c.flush();
           // checkpoint
           col.save();
           col.getSched().reset();
           assertArrayEquals(new int[]{0, 2, 0}, col.getSched().counts());
           c = col.getSched().getCard();
           col.getSched().answerCard(c, 3);
           // it should be due tomorrow
           assertEquals(col.getSched().getToday()+ 1, c.getDue());
           // revert to before
           /* todo: rollback
           col.rollback();
           // with the default settings, the overdue card should be removed from the
           // learning queue
           col.getSched().reset();
           assertArrayEquals(new int[]{0, 0, 1}, col.getSched().counts());
        */
        
    }
    
    @Test
    public void test_finishedV2() throws Exception {
        Collection col = getColV2();
        // nothing due
        assertTrue(col.getSched().finishedMsg(getTargetContext()).toString().contains("Congratulations"));
        assertFalse(col.getSched().finishedMsg(getTargetContext()).toString().contains("limit"));
        Note note = col.newNote();
        note.setItem("Front","one");
        note.setItem("Back","two");
        col.addNote(note);
        // have a new card
        assertTrue(col.getSched().finishedMsg(getTargetContext()).toString().contains("new cards available"));
        // turn it into a review
        col.reset();
        Card c = note.cards().get(0);
        c.startTimer();
        col.getSched().answerCard(c, 3);
        // nothing should be due tomorrow, as it's due in a week
        assertTrue(col.getSched().finishedMsg(getTargetContext()).toString().contains("Congratulations"));
        assertFalse(col.getSched().finishedMsg(getTargetContext()).toString().contains("limit"));
    }
    
    @Test
    public void test_nextIvlV2() throws Exception {
        Collection col = getColV2();
        Note note = col.newNote();
        note.setItem("Front","one");
        note.setItem("Back","two");
        col.addNote(note);
        col.reset();
        DeckConfig conf = col.getDecks().confForDid(1);
        conf.getJSONObject("new").put("delays", new JSONArray(new double [] {0.5, 3, 10}));
        conf.getJSONObject("lapse").put("delays", new JSONArray(new double [] {1, 5, 9}));
        col.getDecks().save(conf);
        Card c = col.getSched().getCard();
        // new cards
        ////////////////////////////////////////////////////////////////////////////////////////////////////
        
        assertEquals(30, col.getSched().nextIvl(c, 1));
        assertEquals((30 + 180)/2, col.getSched().nextIvl(c, 2));
        assertEquals(180, col.getSched().nextIvl(c, 3));
        assertEquals(4 * 86400, col.getSched().nextIvl(c, 4));
        col.getSched().answerCard(c, 1);
        // cards in learning
        ////////////////////////////////////////////////////////////////////////////////////////////////////
        assertEquals(30, col.getSched().nextIvl(c, 1));
        assertEquals((30 + 180)/2, col.getSched().nextIvl(c, 2));
        assertEquals(180, col.getSched().nextIvl(c, 3));
        assertEquals(4 * 86400, col.getSched().nextIvl(c, 4));
        col.getSched().answerCard(c, 3);
        assertEquals(30, col.getSched().nextIvl(c, 1));
        assertEquals((180 + 600)/2, col.getSched().nextIvl(c, 2));
        assertEquals(600, col.getSched().nextIvl(c, 3));
        assertEquals(4 * 86400, col.getSched().nextIvl(c, 4));
        col.getSched().answerCard(c, 3);
        // normal graduation is tomorrow
        assertEquals(1 * 86400, col.getSched().nextIvl(c, 3));
        assertEquals(4 * 86400, col.getSched().nextIvl(c, 4));
        // lapsed cards
        ////////////////////////////////////////////////////////////////////////////////////////////////////
        c.setType(CARD_TYPE_REV);
        c.setIvl(100);
        c.setFactor(STARTING_FACTOR);
        assertEquals(60, col.getSched().nextIvl(c, 1));
        assertEquals(100 * 86400, col.getSched().nextIvl(c, 3));
        assertEquals(101 * 86400, col.getSched().nextIvl(c, 4));
        // review cards
        ////////////////////////////////////////////////////////////////////////////////////////////////////
        c.setQueue(QUEUE_TYPE_REV);
        c.setIvl(100);
        c.setFactor(STARTING_FACTOR);
        // failing it should put it at 60s
        assertEquals(60, col.getSched().nextIvl(c, 1));
        // or 1 day if relearn is false
        conf.getJSONObject("lapse").put("delays", new JSONArray(new double [] {}));
        col.getDecks().save(conf);
        assertEquals(1 * 86400, col.getSched().nextIvl(c, 1));
        // (* 100 1.2 86400)10368000.0
        assertEquals(10368000, col.getSched().nextIvl(c, 2));
        // (* 100 2.5 86400)21600000.0
        assertEquals(21600000, col.getSched().nextIvl(c, 3));
        // (* 100 2.5 1.3 86400)28080000.0
        assertEquals(28080000, col.getSched().nextIvl(c, 4));
        // TODO: upstream is 10.8, try to understand the difference
        assertEquals("10.7 mo", without_unicode_isolation(col.getSched().nextIvlStr(getTargetContext(), c, 4)));
    }
    
    @Test
    public void test_bury() throws Exception {
        Collection col = getColV2();
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        Card c = note.cards().get(0);
        note = col.newNote();
        note.setItem("Front","two");
        col.addNote(note);
        Card c2 = note.cards().get(0);
        // burying
        col.getSched().buryCards(new long [] {c.getId()}, true);
        c.load();
        assertEquals(QUEUE_TYPE_MANUALLY_BURIED, c.getQueue());
        col.getSched().buryCards(new long [] {c2.getId()}, false);
        c2.load();
        assertEquals(QUEUE_TYPE_SIBLING_BURIED, c2.getQueue());
        
        col.reset();
        assertNull(col.getSched().getCard());
        
        col.getSched().unburyCardsForDeck(AbstractSched.UnburyType.MANUAL);
        c.load();
        assertEquals(QUEUE_TYPE_NEW, c.getQueue());
        c2.load();
        assertEquals(QUEUE_TYPE_SIBLING_BURIED, c2.getQueue());
        
        col.getSched().unburyCardsForDeck(AbstractSched.UnburyType.SIBLINGS);
        c2.load();
        assertEquals(QUEUE_TYPE_NEW, c2.getQueue());
        
        col.getSched().buryCards(new long [] {c.getId(), c2.getId()});
        col.getSched().unburyCardsForDeck(AbstractSched.UnburyType.ALL);
        
        col.reset();
        
        assertArrayEquals(new int[]{2, 0, 0}, col.getSched().counts());
    }
    
    @Test
    public void test_suspendv2() throws Exception {
        Collection col = getColV2();
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        Card c = note.cards().get(0);
        // suspending
        col.reset();
        assertNotNull(col.getSched().getCard());
        col.getSched().suspendCards(new long [] {c.getId()});
        col.reset();
        assertNull(col.getSched().getCard());
        // unsuspending
        col.getSched().unsuspendCards(new long [] {c.getId()});
        col.reset();
        assertNotNull(col.getSched().getCard());
        // should cope with rev cards being relearnt
        c.setDue(0);
        c.setIvl(100);
        c.setType(CARD_TYPE_REV);
        c.setQueue(QUEUE_TYPE_REV);
        c.flush();
        col.reset();
        c = col.getSched().getCard();
        col.getSched().answerCard(c, 1);
        assertTrue(c.getDue() >= Utils.now());
        long due = c.getDue();
        assertEquals(QUEUE_TYPE_LRN, c.getQueue());
        assertEquals(CARD_TYPE_RELEARNING, c.getType());
        col.getSched().suspendCards(new long [] {c.getId()});
        col.getSched().unsuspendCards(new long [] {c.getId()});
        c.load();
        assertEquals(QUEUE_TYPE_LRN, c.getQueue());
        assertEquals(CARD_TYPE_RELEARNING, c.getType());
        assertEquals(due, c.getDue());
        // should cope with cards in cram decks
        c.setDue(1);
        c.flush();
        col.getDecks().newDyn("tmp");
        col.getSched().rebuildDyn();
        c.load();
        assertNotEquals(1, c.getDue());
        assertNotEquals(1, c.getDid());
        col.getSched().suspendCards(new long [] {c.getId()});
        c.load();
        assertNotEquals(1, c.getDue());
        assertNotEquals(1, c.getDid());
        assertEquals(1, c.getODue());
    }
    
    @Test
    public void test_filt_reviewing_early_normal() throws Exception {
        Collection col = getColV2();
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        Card c = note.cards().get(0);
        c.setIvl(100);
        c.setQueue(CARD_TYPE_REV);
        c.setType(QUEUE_TYPE_REV);
        // due in 25 days, so it's been waiting 75 days
        c.setDue(col.getSched().getToday() + 25);
        c.setMod(1);
        c.setFactor(STARTING_FACTOR);
        c.startTimer();
        c.flush();
        col.reset();
        assertArrayEquals(new int[]{0, 0, 0}, col.getSched().counts());
        // create a dynamic deck and refresh it
        long did = col.getDecks().newDyn("Cram");
        col.getSched().rebuildDyn(did);
        col.reset();
        // should appear as normal in the deck list
        /* todo sort
           assertEquals(1, sorted(col.getSched().deckDueTree().getChildren())[0].review_count);
        */
        // and should appear in the counts
        assertArrayEquals(new int[]{0, 0, 1}, col.getSched().counts());
        // grab it and check estimates
        c = col.getSched().getCard();
        assertEquals(4, col.getSched().answerButtons(c));
        assertEquals(600, col.getSched().nextIvl(c, 1));
        assertEquals(Math.round(75 * 1.2) * 86400, col.getSched().nextIvl(c, 2));
        /* TODO: Investigate this difference
        //  assertEquals( ((long)(75 * 2.5)) * 86400, col.getSched().nextIvl(c, 3));
        assertEquals( Math.round(75 * 2.5 * 1.15), col.getSched().nextIvl(c, 4));

         */
        
        // answer 'good'
        col.getSched().answerCard(c, 3);
        checkRevIvl(col, c, 90);
        assertEquals(col.getSched().getToday()+ c.getIvl(), c.getDue());
        assertEquals(0L, c.getODue());
        // should not be in learning
        assertEquals(QUEUE_TYPE_REV, c.getQueue());
        // should be logged as a cram rep
        assertEquals(3, col.getDb().queryLongScalar("select type from revlog order by id desc limit 1"));
        
        // due in 75 days, so it's been waiting 25 days
        c.setIvl(100);
        c.setDue(col.getSched().getToday() + 75);
        c.flush();
        col.getSched().rebuildDyn(did);
        col.reset();
        c = col.getSched().getCard();
        
        assertEquals(60 * 86400, col.getSched().nextIvl(c, 2));
        assertEquals(100 * 86400, col.getSched().nextIvl(c, 3));
        assertEquals(114 * 86400, col.getSched().nextIvl(c, 4));
    }
    
    @Test
    public void test_filt_keep_lrn_state() throws Exception {
        Collection col = getColV2();
        
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        
        // fail the card outside filtered deck
        Card c = col.getSched().getCard();
        DeckConfig conf = col.getSched()._cardConf(c);
        conf.getJSONObject("new").put("delays", new JSONArray(new double [] {1, 10, 61}));
        col.getDecks().save(conf);
        
        col.getSched().answerCard(c, 1);
        
        assertEquals(CARD_TYPE_LRN, c.getQueue());
        assertEquals(QUEUE_TYPE_LRN, c.getType());
        assertEquals(3003, c.getLeft());
        
        col.getSched().answerCard(c, 3);
        assertEquals(CARD_TYPE_LRN, c.getQueue());
        assertEquals(QUEUE_TYPE_LRN, c.getType());
        
        // create a dynamic deck and refresh it
        long did = col.getDecks().newDyn("Cram");
        col.getSched().rebuildDyn(did);
        col.reset();
        
        // card should still be in learning state
        c.load();
        assertEquals(CARD_TYPE_LRN, c.getQueue());
        assertEquals(QUEUE_TYPE_LRN, c.getType());
        assertEquals(2002, c.getLeft());
        
        // should be able to advance learning steps
        col.getSched().answerCard(c, 3);
        // should be due at least an hour in the future
        assertTrue(c.getDue() - intTime() > 60 * 60);
        
        // emptying the deck preserves learning state
        col.getSched().emptyDyn(did);
        c.load();
        assertEquals(CARD_TYPE_LRN, c.getQueue());
        assertEquals(QUEUE_TYPE_LRN, c.getType());
        assertEquals(1001, c.getLeft());
        assertTrue(c.getDue() - intTime() > 60 * 60);
    }
    
    @Test
    public void test_preview() throws Exception {
        // add cards
        Collection col = getColV2();
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        Card c = note.cards().get(0);
        Card orig = c.clone();
        Note note2 = col.newNote();
        note2.setItem("Front","two");
        col.addNote(note2);
        // cram deck
        long did = col.getDecks().newDyn("Cram");
        Deck cram = col.getDecks().get(did);
        cram.put("resched", false);
        col.getDecks().save(cram);
        col.getSched().rebuildDyn(did);
        col.reset();
        // grab the first card
        c = col.getSched().getCard();
        assertEquals(2, col.getSched().answerButtons(c));
        assertEquals(600, col.getSched().nextIvl(c, 1));
        assertEquals(0, col.getSched().nextIvl(c, 2));
        // failing it will push its due time back
        long due = c.getDue();
        col.getSched().answerCard(c, 1);
        assertNotEquals(c.getDue(), due);
        
        // the other card should come next
        Card c2 = col.getSched().getCard();
        assertNotEquals(c2.getId(), c.getId());
        
        // passing it will remove it
        col.getSched().answerCard(c2, 2);
        assertEquals(QUEUE_TYPE_NEW, c2.getQueue());
        assertEquals(0, c2.getReps());
        assertEquals(CARD_TYPE_NEW, c2.getType());
        
        // the other card should appear again
        c = col.getSched().getCard();
        assertEquals(orig.getId(), c.getId());
        
        // emptying the filtered deck should restore card
        col.getSched().emptyDyn(did);
        c.load();
        assertEquals(QUEUE_TYPE_NEW, c.getQueue());
        assertEquals(0, c.getReps());
        assertEquals(CARD_TYPE_NEW, c.getType());
    }
    
    @Test
    public void test_ordcycleV2() throws Exception {
        Collection col = getColV2();
        // add two more templates and set second active
        Model m = col.getModels().current();
        Models mm = col.getModels();
        JSONObject t = mm.newTemplate("Reverse");
        t.put("qfmt", "{{Back}}");
        t.put("afmt", "{{Front}}");
        mm.addTemplateModChanged(m, t);
        t = mm.newTemplate("f2");
        t.put("qfmt", "{{Front}}");
        t.put("afmt", "{{Back}}");
        mm.addTemplateModChanged(m, t);
        mm.save(m);
        // create a new note; it should have 3 cards
        Note note = col.newNote();
        note.setItem("Front","1");
        note.setItem("Back","1");
        col.addNote(note);
        assertEquals(3, col.cardCount());
        col.reset();
        // ordinals should arrive in order
        AbstractSched sched = col.getSched();
        Card c = sched.getCard();
        sched.answerCard(c, sched.answerButtons(c)-1); // not upstream. But we are not expecting multiple getCard without review
        assertEquals(0, c.getOrd());
        c = sched.getCard();
        sched.answerCard(c, sched.answerButtons(c)-1); // not upstream. But we are not expecting multiple getCard without review
        assertEquals(1, c.getOrd());
        c = sched.getCard();
        sched.answerCard(c, sched.answerButtons(c)-1); // not upstream. But we are not expecting multiple getCard without review
        assertEquals(2, c.getOrd());
    }
    
    @Test
    public void test_counts_idxV2() throws Exception {
        Collection col = getColV2();
        Note note = col.newNote();
        note.setItem("Front","one");
        note.setItem("Back","two");
        col.addNote(note);
        col.reset();
        assertArrayEquals(new int[]{1, 0, 0}, col.getSched().counts());
        Card c = col.getSched().getCard();
        // counter's been decremented but idx indicates 1
        assertArrayEquals(new int[]{0, 0, 0}, col.getSched().counts());
        assertEquals(0, col.getSched().countIdx(c));
        // answer to move to learn queue
        col.getSched().answerCard(c, 1);
        assertArrayEquals(new int[]{0, 1, 0}, col.getSched().counts());
        // fetching again will decrement the count
        c = col.getSched().getCard();
        assertArrayEquals(new int[]{0, 0, 0}, col.getSched().counts());
        assertEquals(1, col.getSched().countIdx(c));
        // answering should add it back again
        col.getSched().answerCard(c, 1);
        assertArrayEquals(new int[]{0, 1, 0}, col.getSched().counts());
    }
    
    @Test
    public void test_repCountsV2() throws Exception {
        Collection col = getColV2();
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        col.reset();
        // lrnReps should be accurate on pass/fail
        assertArrayEquals(new int[]{1, 0, 0}, col.getSched().counts());
        col.getSched().answerCard(col.getSched().getCard(), 1);
        assertArrayEquals(new int[]{0, 1, 0}, col.getSched().counts());
        col.getSched().answerCard(col.getSched().getCard(), 1);
        assertArrayEquals(new int[]{0, 1, 0}, col.getSched().counts());
        col.getSched().answerCard(col.getSched().getCard(), 3);
        assertArrayEquals(new int[]{0, 1, 0}, col.getSched().counts());
        col.getSched().answerCard(col.getSched().getCard(), 1);
        assertArrayEquals(new int[]{0, 1, 0}, col.getSched().counts());
        col.getSched().answerCard(col.getSched().getCard(), 3);
        assertArrayEquals(new int[]{0, 1, 0}, col.getSched().counts());
        col.getSched().answerCard(col.getSched().getCard(), 3);
        assertArrayEquals(new int[]{0, 0, 0}, col.getSched().counts());
        note = col.newNote();
        note.setItem("Front","two");
        col.addNote(note);
        col.reset();
        // initial pass should be correct too
        col.getSched().answerCard(col.getSched().getCard(), 3);
        assertArrayEquals(new int[]{0, 1, 0}, col.getSched().counts());
        col.getSched().answerCard(col.getSched().getCard(), 1);
        assertArrayEquals(new int[]{0, 1, 0}, col.getSched().counts());
        col.getSched().answerCard(col.getSched().getCard(), 4);
        assertArrayEquals(new int[]{0, 0, 0}, col.getSched().counts());
        // immediate graduate should work
        note = col.newNote();
        note.setItem("Front","three");
        col.addNote(note);
        col.reset();
        col.getSched().answerCard(col.getSched().getCard(), 4);
        assertArrayEquals(new int[]{0, 0, 0}, col.getSched().counts());
        // and failing a review should too
        note = col.newNote();
        note.setItem("Front","three");
        col.addNote(note);
        Card c = note.cards().get(0);
        c.setType(CARD_TYPE_REV);
        c.setQueue(QUEUE_TYPE_REV);
        c.setDue(col.getSched().getToday());
        c.flush();
        col.reset();
        assertArrayEquals(new int[]{0, 0, 1}, col.getSched().counts());
        col.getSched().answerCard(col.getSched().getCard(), 1);
        assertArrayEquals(new int[]{0, 1, 0}, col.getSched().counts());
    }
    
    @Test
    public void test_timingV2() throws Exception {
        Collection col = getColV2();
        // add a few review cards, due today
        for (int i=0; i < 5; i++){
            Note note=col.newNote();
            note.setItem("Front","num"+i);
            col.addNote(note);
            Card c=note.cards().get(0);
            c.setType(CARD_TYPE_REV);
            c.setQueue(QUEUE_TYPE_REV);
            c.setDue(0);
            c.flush();
        }
        // fail the first one
        col.reset();
        Card c = col.getSched().getCard();
        col.getSched().answerCard(c, 1);
        // the next card should be another review
        Card c2 = col.getSched().getCard();
        assertEquals(QUEUE_TYPE_REV, c2.getQueue());
        // if the failed card becomes due, it should show first
        c.setDue(intTime() - 1);
        c.flush();
        col.reset();
        c = col.getSched().getCard();
        assertEquals(QUEUE_TYPE_LRN, c.getQueue());
    }
    
    @Test
    public void test_collapseV2() throws Exception {
        Collection col = getColV2();
        // add a note
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        col.reset();
        // test collapsing
        Card c = col.getSched().getCard();
        col.getSched().answerCard(c, 1);
        c = col.getSched().getCard();
        col.getSched().answerCard(c, 4);
        assertNull(col.getSched().getCard());
    }
    
    @Test
    public void test_deckDueV2() throws Exception {
        Collection col = getColV2();
        // add a note with default deck
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        // and one that's a child
        note = col.newNote();
        note.setItem("Front","two");
        long default1 = col.getDecks().id("Default::1");
        note.model().put("did", default1);
        col.addNote(note);
        // make it a review card
        Card c = note.cards().get(0);
        c.setQueue(QUEUE_TYPE_REV);
        c.setDue(0);
        c.flush();
        // add one more with a new deck
        note = col.newNote();
        note.setItem("Front","two");
        note.model().put("did", col.getDecks().id("foo::bar"));
        col.addNote(note);
        // and one that's a sibling
        note = col.newNote();
        note.setItem("Front","three");
        note.model().put("did", col.getDecks().id("foo::baz"));
        col.addNote(note);
        col.reset();
        assertEquals(5, col.getDecks().allSortedNames().size());
        AbstractSched.DeckDueTreeNode tree = col.getSched().deckDueTree().get(0);
        assertEquals("Default", tree.getLastDeckNameComponent());
        // sum of child and parent
        assertEquals(1, tree.getDid());
        assertEquals(1, tree.getRevCount());
        assertEquals(1, tree.getNewCount());
        // child count is just review
        AbstractSched.DeckDueTreeNode child = tree.getChildren().get(0);
        assertEquals("1", child.getLastDeckNameComponent());
        assertEquals(default1, child.getDid());
        assertEquals(1, child.getRevCount());
        assertEquals(0, child.getNewCount());
        // code should not fail if a card has an invalid deck
        c.setDid(12345);
        c.flush();
        col.getSched().deckDueTree();
    }
    
    @Test
    public void test_deckTree() throws Exception {
        Collection col = getColV2();
        col.getDecks().id("new::b::c");
        col.getDecks().id("new2");
        // new should not appear twice in tree
        List<String> names = new ArrayList<>();
        for (AbstractSched.DeckDueTreeNode tree: col.getSched().deckDueTree()) {
            names.add(tree.getLastDeckNameComponent());
        }
        names.remove("new");
        assertFalse(names.contains("new"));
    }
    
    @Test
    public void test_deckFlowV2() throws Exception {
        Collection col = getColV2();
        // add a note with default deck
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        // and one that's a child
        note = col.newNote();
        note.setItem("Front","two");
        long default1 = col.getDecks().id("Default::2");
        note.model().put("did", default1);
        col.addNote(note);
        // and another that's higher up
        note = col.newNote();
        note.setItem("Front","three");
        default1 = col.getDecks().id("Default::1");
        note.model().put("did", default1);
        col.addNote(note);
        // should get top level one first, then ::1, then ::2
        col.reset();
        assertArrayEquals(new int[]{3, 0, 0}, col.getSched().counts());
        for (String  i: new String[]{"one", "three", "two"}) {
            Card c = col.getSched().getCard();
            assertEquals(i, c.note().getItem("Front"));
            col.getSched().answerCard(c, 3);
        }
    }
    
    @Test
    public void test_reorder() throws Exception {
        Collection col = getColV2();
        // add a note with default deck
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        Note note2 = col.newNote();
        note2.setItem("Front","two");
        col.addNote(note2);
        assertEquals(2, note2.cards().get(0).getDue());
        boolean found = false;
        // 50/50 chance of being reordered
        for (int i=0; i < 20; i++) {
            col.getSched().randomizeCards(1);
            if (note.cards().get(0).getDue() != note.getId()) {
                found = true;
                break;
            }
        }
        assertTrue(found);
        col.getSched().orderCards(1);
        assertEquals(1, note.cards().get(0).getDue());
        // shifting
        Note note3 = col.newNote();
        note3.setItem("Front","three");
        col.addNote(note3);
        Note note4 = col.newNote();
        note4.setItem("Front","four");
        col.addNote(note4);
        assertEquals(1, note.cards().get(0).getDue());
        assertEquals(2, note2.cards().get(0).getDue());
        assertEquals(3, note3.cards().get(0).getDue());
        assertEquals(4, note4.cards().get(0).getDue());
        /* todo: start
           col.getSched().sortCards(new long [] {note3.cards().get(0).getId(), note4.cards().get(0).getId()}, start=1, shift=true);
           assertEquals(3, note.cards().get(0).getDue());
           assertEquals(4, note2.cards().get(0).getDue());
           assertEquals(1, note3.cards().get(0).getDue());
           assertEquals(2, note4.cards().get(0).getDue());
           
        */
    }
    
    @Test
    public void test_forgetV2() throws Exception {
        Collection col = getColV2();
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        Card c = note.cards().get(0);
        c.setQueue(QUEUE_TYPE_REV);
        c.setType(CARD_TYPE_REV);
        c.setIvl(100);
        c.setDue(0);
        c.flush();
        col.reset();
        assertArrayEquals(new int[]{0, 0, 1}, col.getSched().counts());
        col.getSched().forgetCards(new long [] {c.getId()});
        col.reset();
        assertArrayEquals(new int[]{1, 0, 0}, col.getSched().counts());
    }
    
    @Test
    public void test_reschedV2() throws Exception {
        Collection col = getColV2();
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        Card c = note.cards().get(0);
        col.getSched().reschedCards(new long [] {c.getId()}, 0, 0);
        c.load();
        assertEquals(col.getSched().getToday(), c.getDue());
        assertEquals(1, c.getIvl());
        assertEquals(QUEUE_TYPE_REV, c.getType());
        assertEquals(CARD_TYPE_REV, c.getQueue());
        col.getSched().reschedCards(new long [] {c.getId()}, 1, 1);
        c.load();
        assertEquals(col.getSched().getToday()+ 1, c.getDue());
        assertEquals(+1, c.getIvl());
    }
    
    @Test
    public void test_norelearnV2() throws Exception {
        Collection col = getColV2();
        // add a note
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        Card c = note.cards().get(0);
        c.setType(CARD_TYPE_REV);
        c.setQueue(QUEUE_TYPE_REV);
        c.setDue(0);
        c.setFactor(STARTING_FACTOR);
        c.setReps(3);
        c.setLapses(1);
        c.setIvl(100);
        c.startTimer();
        c.flush();
        col.reset();
        col.getSched().answerCard(c, 1);
        col.getSched()._cardConf(c).getJSONObject("lapse").put("delays", new JSONArray(new double [] {}));
        col.getSched().answerCard(c, 1);
    }
    
    @Test
    public void test_failmultV2() throws Exception {
        Collection col = getColV2();
        Note note = col.newNote();
        note.setItem("Front","one");
        note.setItem("Back","two");
        col.addNote(note);
        Card c = note.cards().get(0);
        c.setType(CARD_TYPE_REV);
        c.setQueue(QUEUE_TYPE_REV);
        c.setIvl(100);
        c.setDue(col.getSched().getToday() - c.getIvl());
        c.setFactor(STARTING_FACTOR);
        c.setReps(3);
        c.setLapses(1);
        c.startTimer();
        c.flush();
        DeckConfig conf = col.getSched()._cardConf(c);
        conf.getJSONObject("lapse").put("mult", 0.5);
        col.getDecks().save(conf);
        c = col.getSched().getCard();
        col.getSched().answerCard(c, 1);
        assertEquals(50, c.getIvl());
        col.getSched().answerCard(c, 1);
        assertEquals(25, c.getIvl());
    }
    
    @Test
    public void test_moveVersions() throws Exception {
        Collection col = getColV2();
        col.changeSchedulerVer(1);
        
        Note n = col.newNote();
        n.setItem("Front", "one");
        col.addNote(n);
        
        // make it a learning card
        col.reset();
        Card c = col.getSched().getCard();
        col.getSched().answerCard(c, 1);
        
        // the move to v2 should reset it to new
        col.changeSchedulerVer(2);
        c.load();
        assertEquals(QUEUE_TYPE_NEW, c.getQueue());
        assertEquals(CARD_TYPE_NEW, c.getType());
        
        // fail it again, and manually bury it
        col.reset();
        c = col.getSched().getCard();
        col.getSched().answerCard(c, 1);
        col.getSched().buryCards(new long [] {c.getId()});
        c.load();
        assertEquals(QUEUE_TYPE_MANUALLY_BURIED, c.getQueue());
        
        // revert to version 1
        col.changeSchedulerVer(1);
        
        // card should have moved queues
        c.load();
        assertEquals(QUEUE_TYPE_SIBLING_BURIED, c.getQueue());
        
        // and it should be new again when unburied
        col.getSched().unburyCards();
        c.load();
        assertEquals(CARD_TYPE_NEW, c.getQueue());
        assertEquals(QUEUE_TYPE_NEW, c.getType());
        
        // make sure relearning cards transition correctly to v1
        col.changeSchedulerVer(2);
        // card with 100 day interval, answering again
        col.getSched().reschedCards(new long [] {c.getId()}, 100, 100);
        c.load();
        c.setDue(0);
        c.flush();
        DeckConfig conf = col.getSched()._cardConf(c);
        conf.getJSONObject("lapse").put("mult", 0.5);
        col.getDecks().save(conf);
        col.getSched().reset();
        c = col.getSched().getCard();
        col.getSched().answerCard(c, 1);
        // due should be correctly set when removed from learning early
        col.changeSchedulerVer(1);
        c.load();
        assertEquals(50, c.getDue());
    }
    
    // cards with a due date earlier than the collection should retain
    // their due date when removed
    @Test
    public void test_negativeDueFilter() throws Exception {
        Collection col = getColV2();
        
        // card due prior to collection date
        Note note = col.newNote();
        note.setItem("Front","one");
        note.setItem("Back","two");
        col.addNote(note);
        Card c = note.cards().get(0);
        c.setDue(-5);
        c.setQueue(QUEUE_TYPE_REV);
        c.setIvl(5);
        c.flush();
        
        // into and out of filtered deck
        long did = col.getDecks().newDyn("Cram");
        col.getSched().rebuildDyn(did);
        col.getSched().emptyDyn(did);
        col.reset();
        
        c.load();
        assertEquals(-5, c.getDue());
    }
    
    
    // hard on the first step should be the average of again and good,
    // and it should be logged properly
    
    @Test
    @Ignore("Port anki@a9c93d933cadbf5d9c7e3e2b4f7a25d2c59da5d3")
    public void test_initial_repeat() throws Exception {
        Collection col = getColV2();
        Note note = col.newNote();
        note.setItem("Front","one");
        note.setItem("Back","two");
        col.addNote(note);
        
        col.reset();
        Card c = col.getSched().getCard();
        col.getSched().answerCard(c, 2);
        // should be due in ~ 5.5 mins
        double expected = Utils.now() + 5.5 * 60;
        long due = c.getDue();
        assertTrue((expected - 10 < due) && (due < expected * 1.25));
        
        long ivl = col.getDb().queryLongScalar("select ivl from revlog");
        assertEquals((long) (-5.5 * 60), ivl);
    }
    /*****************
     ** Stats        *
     *****************/
    /* TODO put in Collection
       @Test
       public void test_stats() throws Exception {
       Collection col = getCol();
       Note note = col.newNote();
       note.setItem("Front","foo");
       col.addNote(note);
       Card c = note.cards().get(0);
       // card stats
       assertTrue(col.cardStats(c));
       col.reset();
       c = col.getSched().getCard();
       col.getSched().answerCard(c, 3);
       col.getSched().answerCard(c, 2);
       assertTrue(col.cardStats(c));
       }
       
       @Test
       public void test_graphs_empty() throws Exception {
       Collection col = getCol();
       assertTrue(col.stats().report());
       }
       
       
       @Test
       public void test_graphs() throws Exception {
       dir = tempfile.gettempdir();
       Collection col = getCol();
       g = col.stats();
       rep = g.report();
       with open(os.path.join(dir, "test.html"), "w", encoding="UTF-8") as note:
       note.write(rep);
       return;
       } */
    /*****************
     ** Templates    *
     *****************/
    
    @Test
    public void test_deferred_frontside() throws Exception {
        Collection col = getCol();
        Model m = col.getModels().current();
        m.getJSONArray("tmpls").getJSONObject(0).put("qfmt", "{{custom:Front}}");
        col.getModels().save(m);
        
        Note note = col.newNote();
        note.setItem("Front","xxtest");
        note.setItem("Back","");
        col.addNote(note);
        
        assertTrue(note.cards().get(0).a().contains("xxtest"));
    }
    /*****************
     ** Undo         *
     *****************/
    @Test
    @Ignore("We need to figure out how to test save/undo")
    public void test_op() throws Exception {
        Collection col = getColV2();
        // should have no undo by default
        assertNull(col.undoType());
        // let's adjust a study option
        col.save("studyopts");
        col.getConf().put("abc", 5);
        // it should be listed as undoable
        assertEquals("studyopts", col.undoName(getTargetContext().getResources()));
        // with about 5 minutes until it's clobbered
        /* lastSave
           assertTrue(Utils.now() - col._lastSave < 1);
        */
        // undoing should restore the old value
        col.undo();
        assertEquals(null, col.undoType());
        assertFalse(col.getConf().has("abc"));
        // an (auto)save will clear the undo
        col.save("foo");
        assertEquals("foo", col.undoName(getTargetContext().getResources()));
        col.save();
        assertEquals("", col.undoName(getTargetContext().getResources()));
        // and a review will, too
        col.save("add");
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        col.reset();
        assertEquals("add", col.undoName(getTargetContext().getResources()));
        Card c = col.getSched().getCard();
        col.getSched().answerCard(c, 2);
        assertEquals("Review", col.undoName(getTargetContext().getResources()));
    }
    
    @Test
    public void test_review() throws Exception {
        Collection col = getColV2();
        col.getConf().put("counts", COUNT_REMAINING);
        Note note = col.newNote();
        note.setItem("Front","one");
        col.addNote(note);
        col.reset();
        /* TODO:  undo after reset ?
        assertNotNull(col.undoType());

         */
        // answer
        assertArrayEquals(new int[]{1, 0, 0}, col.getSched().counts());
        Card c = col.getSched().getCard();
        assertEquals(QUEUE_TYPE_NEW, c.getQueue());
        col.getSched().answerCard(c, 3);
        assertEquals(1001, c.getLeft());
        assertArrayEquals(new int[]{0, 1, 0}, col.getSched().counts());
        assertEquals(QUEUE_TYPE_LRN, c.getQueue());
        // undo
        assertNotNull(col.undoType());
        col.undo();
        col.reset();
        assertArrayEquals(new int[]{1, 0, 0}, col.getSched().counts());
        c.load();
        assertEquals(QUEUE_TYPE_NEW, c.getQueue());
        assertNotEquals(1001, c.getLeft());
        assertNull(col.undoType());
        // we should be able to undo multiple answers too
        note = col.newNote();
        note.setItem("Front","two");
        col.addNote(note);
        col.reset();
        assertArrayEquals(new int[]{2, 0, 0}, col.getSched().counts());
        c = col.getSched().getCard();
        col.getSched().answerCard(c, 3);
        c = col.getSched().getCard();
        col.getSched().answerCard(c, 3);
        assertArrayEquals(new int[]{0, 2, 0}, col.getSched().counts());
        col.undo();
        col.reset();
        assertArrayEquals(new int[]{1, 1, 0}, col.getSched().counts());
        col.undo();
        col.reset();
        assertArrayEquals(new int[]{2, 0, 0}, col.getSched().counts());
        // performing a normal op will clear the review queue
        c = col.getSched().getCard();
        col.getSched().answerCard(c, 3);
        assertEquals(Collection.DismissType.REVIEW, col.undoType());
        col.save("foo");
        /* TODO: save and undo
        assertEquals("foo", col.undoName(getTargetContext().getResources()));
         */
        col.undo();
    }
} 

