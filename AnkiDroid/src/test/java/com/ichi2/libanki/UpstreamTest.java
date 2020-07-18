package com.ichi2.libanki;

import com.ichi2.anki.RobolectricTest;
import com.ichi2.libanki.sched.AbstractSched;
import com.ichi2.utils.Assert;
import com.ichi2.utils.JSONObject;

import org.apache.http.util.Asserts;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
         col.remove_cards_and_orphaned_notes([cid]);
         assertEquals( 0, col.cardCount() );
         assertEquals( 0, col.noteCount() );
         assertEquals( 0, col.getDb().queryScalar("select count() from notes") );
         assertEquals( 0, col.getDb().queryScalar("select count() from cards") );
         assertEquals( 2, col.getDb().queryScalar("select count() from graves") );
     }

     @Test
     public void test_misc(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","1");
         note.setItem("Back","2");
         col.addNote(note);
         Card c = note.cards().get(0);
         long id = col.getModels().current().getLong("id");
         assertEquals( 0, c.template().getInt("ord") );
     }

     @Test
     public void test_genrem(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","1");
         note.setItem("Back","");
         col.addNote(note);
         assertEquals( 1, note.cards().size() );
         Model m = col.getModels().current();
         Models mm = col.getModels();
         // adding a new template should automatically create cards
         JSONObject t = mm.newTemplate("rev");
         t.put("qfmt", "{{Front}}");
         t.put("afmt", "");
         mm.addTemplateModChanged(m, t);
         mm.save(m, true);
         assertEquals( 2, note.cards().size() );
         // if the template is changed to remove cards, they'll be removed
         JSONObject t = m["tmpls"][1];
         t.put("qfmt", "{{Back}}");
         mm.save(m, true);
         List<Long> rep = col.emptyCids();
         for (Note n: rep.notes) {
             col.remove_cards_and_orphaned_notes(n.card_ids);
         }
         assertEquals( 1, note.cards().size() );
         // if we add to the note, a card should be automatically generated
         note.load();
         note.setItem("Back","1");
         note.flush();
         assertEquals( 2, note.cards().size() );
     }

     @Test
     public void test_gendeck(){
         Collection col = getCol();
         Model cloze = col.getModels().byName("Cloze");
         col.getModels().setCurrent(cloze);
         Note note = col.newNote();
         note.setItem("Text","{{c1::one}}");
         col.addNote(note);
         assertEquals( 1, col.cardCount() );
         assertEquals( 1, note.cards().get(0).getDid() );
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
         assertEquals( 1, note.cards().get(2).getDid() );
         // if one of the cards is in a different col, it should revert to the
         // model default
         Card c = note.cards().get(1);
         c.setDid(newId);
         c.flush();
         note.setItem("Text","{{c4::four}}");
         note.flush();
         assertEquals( newId, note.cards().get(3).getDid() );
     }
     /*****************
      ** Collection   *
      *****************/

     @Test
     public void test_create_open(){
         (fd, path) = tempfile.mkstemp(suffix=".anki2", prefix="test_attachNew");
         try {
             os.close(fd);
             os.unlink(path);
         } catch ( OSError) {
         }
         Collection col = aopen(path);
         // for open()
         String newPath = col.getPath();
         long newMod = col.getMod();
         col.close();
         del col;

         // reopen
         Collection col = aopen(newPath);
         assertEquals( newMod, col.getMod() );
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
     }

     @Test
     public void test_noteAddDelete(){
         Collection col = getCol();
         // add a note
         Note note = col.newNote();
         note.setItem("Front","one");
         note.setItem("Back","two");
         Note n = col.addNote(note);
         assertEquals( 1, n );
         // test multiple cards - add another template
         Model m = col.getModels().current();
         Models mm = col.getModels();
         JSONObject t = mm.newTemplate("Reverse");
         t.put("qfmt", "{{Back}}");
         t.put("afmt", "{{Front}}");
         mm.addTemplateModChanged(m, t);
         mm.save(m);
         assertEquals( 2, col.cardCount() );
         // creating new notes should use both cards
         Note note = col.newNote();
         note.setItem("Front","three");
         note.setItem("Back","four");
         Note n = col.addNote(note);
         assertEquals( 2, n );
         assertEquals( 4, col.cardCount() );
         // check q/a generation
         Card c0 = note.cards().get(0);
         assertTrue("three" in c0.q());
         // it should not be a duplicate
         assertFalse(note.dupeOrEmpty());
         // now let's make a duplicate
         note2 = col.newNote();
         note2.setItem("Front","one");
         note2.setItem("Back","");
         assertTrue(note2.dupeOrEmpty());
         // empty first field should not be permitted either
         note2.setItem("Front"," ");
         assertTrue(note2.dupeOrEmpty());
     }

     @Test
     public void test_fieldChecksum(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","new");
         note.setItem("Back","new2");
         col.addNote(note);
         assertEquals(0xc2a6b03f, col.getDb().scalar("select csum from notes") );
         // changing the val should change the checksum
         note.setItem("Front","newx");
         note.flush();
         assertEquals(0x302811ae, col.getDb().scalar("select csum from notes"));
     }

     @Test
     public void test_addDelTags(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","1");
         col.addNote(note);
         note2 = col.newNote();
         note2.setItem("Front","2");
         col.addNote(note2);
         // adding for a given id
         col.getTags().bulkAdd([note.getId()], "foo");
         note.load();
         note2.load();
         assertTrue("foo" in note.getTags());
         assertTrue("foo" not in note2.getTags());
         // should be canonified
         col.getTags().bulkAdd([note.getId()], "foo aaa");
         note.load();
         assertEquals( "aaa", note.getTags()[0] );
         assertEquals( 2, note.getTags().size() );
     }

     @Test
     public void test_timestamps(){
         Collection col = getCol();
         assertEquals( get_stock_notetypes(col, col.getModels().all_names_and_ids().size() ).size());
         for (int i = 0; i < 100; i++) {
             addBasicModel(col);
         }
         assertEquals( 100 + get_stock_notetypes(col, col.getModels().all_names_and_ids().size() ).size());
     }

     @Test
     public void test_furigana(){
         Collection col = getCol();
         Models mm = col.getModels();
         Model m = mm.current();
         // filter should work
         m["tmpls"][0].put("qfmt", "{{kana:Front}}");
         mm.save(m);
         Note n = col.newNote();
         n.put("Front", "foo[abc]");
         col.addNote(n);
         Card c = n.cards().get(0);
         assertTrue(c.q().endswith("abc"));
         // and should avoid sound
         n.put("Front", "foo[sound:abc.mp3]");
         n.flush();
         assertTrue("anki:play" in c.q(reload=true));
         // it shouldn't throw an error while people are editing
         m["tmpls"][0].put("qfmt", "{{kana:}}");
         mm.save(m);
         c.q(reload=true);
     }

     @Test
     public void test_translate(){
         Collection col = getCol();
         no_uni = without_unicode_isolation;

         assertTrue(();
             col.tr(TR.CARD_TEMPLATE_RENDERING_FRONT_SIDE_PROBLEM);
             == "Front template has a problem:";
     );
         assertEquals( "1 review", no_uni(col.tr(TR.STATISTICS_REVIEWS, reviews=1)) );
         assertEquals( "2 reviews", no_uni(col.tr(TR.STATISTICS_REVIEWS, reviews=2)) );
     }

     @Test
     public void test_db_named_args(capsys):
         sql = "select a, 2+:test5 from b where arg =:foo and x = :test5";
         args = [];
         kwargs = dict(test5=5, foo="blah");

         s, a = emulate_named_args(sql, args, kwargs);
    assertEquals( "select a, 2+?1 from b where arg =?2 and x = ?1", s );
    assertEquals( [5, "blah"], a );

         // swallow the warning
         _ = capsys.readouterr();
     }
     /*****************
      ** Decks        *
      *****************/

     @Test
     public void test_basic(){
         Collection col = getCol();
         // we start with a standard col
         assertEquals( 1, col.getDecks().all_names_and_ids().size() );
         // it should have an id of 1
         assertTrue(col.getDecks().name(1));
         // create a new col
         long parentId = col.getDecks().id("new deck");
         assertTrue(parentId);
         assertEquals( 2, col.getDecks().all_names_and_ids().size() );
         // should get the same id
         assertEquals( parentId, col.getDecks().id("new deck") );
         // we start with the default col selected
         assertEquals( 1, col.getDecks().selected() );
         assertEquals( [1], col.getDecks().active() );
         // we can select a different col
         col.getDecks().select(parentId);
         assertEquals( parentId, col.getDecks().selected() );
         assertEquals( [parentId], col.getDecks().active() );
         // let's create a child
         long childId = col.getDecks().id("new deck::child");
         col.getSched().reset();
         // it should have been added to the active list
         assertEquals( parentId, col.getDecks().selected() );
         assertEquals( [parentId, childId], col.getDecks().active() );
         // we can select the child individually too
         col.getDecks().select(childId);
         assertEquals( childId, col.getDecks().selected() );
         assertEquals( [childId], col.getDecks().active() );
         // parents with a different case should be handled correctly
         col.getDecks().id("ONE");
         Model m = col.getModels().current();
         m.put("did", col.getDecks().id("one::two"));
         col.getModels().save(m, false);
         Note n = col.newNote();
         n.put("Front", "abc");
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
         assertEquals( deck1, c.getDid() );
         assertEquals( 1, col.cardCount() );
         col.getDecks().rem(deck1);
         assertEquals( 0, col.cardCount() );
         // if we try to get it, we get the default
         assertEquals( "[no deck]", col.getDecks().name(c.getDid()) );
     }

     @Test
     public void test_rename(){
         Collection col = getCol();
         long id = col.getDecks().id("hello::world");
         // should be able to rename into a completely different branch, creating
         // parents as necessary
         col.getDecks().rename(col.getDecks().get(id), "foo::bar");
         List<String> names =  col.getDecks().allNames();
         assertTrue("foo" in names);
         assertTrue("foo::bar" in names);
         assertTrue("hello::world" not in names);
         // create another col
         long id = col.getDecks().id("tmp");
         // automatically adjusted if a duplicate name
         col.getDecks().rename(col.getDecks().get(id), "FOO");
         names =  col.getDecks().allNames();
         assertTrue("FOO+" in names);
         // when renaming, the children should be renamed too
         col.getDecks().id("one::two::three");
         long id = col.getDecks().id("one");
         col.getDecks().rename(col.getDecks().get(id), "yo");
         names =  col.getDecks().allNames();
         for (String n: new String [] {"yo", "yo::two", "yo::two::three"}) {
             assertTrue(n in names);
         }
         // over filtered
         long filteredId = col.getDecks().newDyn("filtered");
         Deck filtered = col.getDecks().get(filteredId);
         long childId = col.getDecks().id("child");
         Deck child = col.getDecks().get(childId);
         assertException(DeckRenameError, lambda: col.getDecks().rename(child, "filtered::child"));
         assertException(DeckRenameError, lambda: col.getDecks().rename(child, "FILTERED::child"));
     }

     @Test
     public void test_renameForDragAndDrop(){
         Collection col = getCol();

         long languages_did = col.getDecks().id("Languages");
         long chinese_did = col.getDecks().id("Chinese");
         long hsk_did = col.getDecks().id("Chinese::HSK");

         // Renaming also renames children
         col.getDecks().renameForDragAndDrop(chinese_did, languages_did);
         assertEquals( ["Languages", "Languages::Chinese", "Languages::Chinese::HSK"], col.getDecks().allNames() );

         // Dragging a col onto itself is a no-op
         col.getDecks().renameForDragAndDrop(languages_did, languages_did);
         assertEquals( ["Languages", "Languages::Chinese", "Languages::Chinese::HSK"], col.getDecks().allNames() );

         // Dragging a col onto its parent is a no-op
         col.getDecks().renameForDragAndDrop(hsk_did, chinese_did);
         assertEquals( ["Languages", "Languages::Chinese", "Languages::Chinese::HSK"], col.getDecks().allNames() );

         // Dragging a col onto a descendant is a no-op
         col.getDecks().renameForDragAndDrop(languages_did, hsk_did);
         assertEquals( ["Languages", "Languages::Chinese", "Languages::Chinese::HSK"], col.getDecks().allNames() );

         // Can drag a grandchild onto its grandparent.  It becomes a child
         col.getDecks().renameForDragAndDrop(hsk_did, languages_did);
         assertEquals( ["Languages", "Languages::Chinese", "Languages::HSK"], col.getDecks().allNames() );

         // Can drag a col onto its sibling
         col.getDecks().renameForDragAndDrop(hsk_did, chinese_did);
         assertEquals( ["Languages", "Languages::Chinese", "Languages::Chinese::HSK"], col.getDecks().allNames() );

         // Can drag a col back to the top level
         col.getDecks().renameForDragAndDrop(chinese_did, null);
         assertEquals( ["Chinese", "Chinese::HSK", "Languages"], col.getDecks().allNames() );

         // Dragging a top level col to the top level is a no-op
         col.getDecks().renameForDragAndDrop(chinese_did, null);
         assertEquals( ["Chinese", "Chinese::HSK", "Languages"], col.getDecks().allNames() );

         // decks are renamed if necessary
         long new_hsk_did = col.getDecks().id("hsk");
         col.getDecks().renameForDragAndDrop(new_hsk_did, chinese_did);
         assertEquals( ["Chinese", "Chinese::HSK", "Chinese::hsk+", "Languages"], col.getDecks().allNames() );
         col.getDecks().rem(new_hsk_did);

         // '' is a convenient alias for the top level DID
         col.getDecks().renameForDragAndDrop(hsk_did, "");
         assertEquals( ["Chinese", "HSK", "Languages"], col.getDecks().allNames() );
      }

     /*****************
      ** Exporting    *
      *****************/
     private void setup1(){
         global col;
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","foo");
         note.setItem("Back","bar<br>");
         note.tags = ["tag", "tag2"];
         col.addNote(note);
         // with a different col
         Note note = col.newNote();
         note.setItem("Front","baz");
         note.setItem("Back","qux");
         note.model().put("did", col.getDecks().id("new col"));
         col.addNote(note);
     }

             /*//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////// */



     @Test
     public void test_export_anki(){
         setup1();
         // create a new col with its own conf to test conf copying
         long did = col.getDecks().id("test");
         Deck dobj = col.getDecks().get(did);
         long confId = col.getDecks().add_config_returning_id("newconf");
         DeckConfig conf = col.getDecks().get_config(confId);
         conf["new"].put("perDay", 5);
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
         assertEquals( 2, col2.cardCount() );
         // as scheduling was reset, should also revert decks to default conf
         long did = col2.getDecks().id("test", create=false);
         assertTrue(did);
         conf2 = col2.getDecks().confForDid(did);
         assertTrue(conf2["new"].put("perDay",= 20));
         Deck dobj = col2.getDecks().get(did);
         // conf should be 1
         assertTrue(dobj.put("conf",= 1));
         // try again, limited to a deck
         fd, newname = tempfile.mkstemp(prefix="ankitest", suffix=".anki2");
         newname = str(newname);
         os.close(fd);
         os.unlink(newname);
         e.long did = 1;
         e.exportInto(newname);
         col2 = aopen(newname);
         assertEquals( 1, col2.cardCount() );
     }

     @Test
     public void test_export_ankipkg(){
         setup1();
         // add a test file to the media folder
         with open(os.path.join(col.media.dir(), "今日.mp3"), "w") as note:
             note.write("test");
         Note n = col.newNote();
         n.put("Front", "[sound:今日.mp3]");
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
         setup1();
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
         assertEquals( 1, c.ivl );
         assertEquals( 11, c.due );
         assertEquals( 10, col.getSched().today );
         assertEquals( 1, c.due - col.getSched().today );
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
         Card c = col2.getCard(c.getId());
         col2.getSched().reset();
         assertEquals( 1, c.due - col2.getSched().today );
     }

     @Test
     public void test_export_textcard(){
     //     setup1()
     //     e = TextCardExporter(col)
     //     Note note = unicode(tempfile.mkstemp(prefix="ankitest")[1])
     //     os.unlink(note)
     //     e.exportInto(note)
     //     e.includeTags = true
     //     e.exportInto(note)


     }

     @Test
     public void test_export_textnote(){
         setup1();
         e = TextNoteExporter(col);
         fd, Note note = tempfile.mkstemp(prefix="ankitest");
         Note note = str(note);
         os.close(fd);
         os.unlink(note);
         e.exportInto(note);
         with open(note) as file:
         assertEquals( "foo\tbar<br>\ttag tag2\n", file.readline() );
         e.includeTags = false;
         e.includeHTML = false;
         e.exportInto(note);
         with open(note) as file:
         assertEquals( "foo\tbar\n", file.readline() );
     }

     @Test
     public void test_exporters(){
         assertTrue("*.apkg" in str(exporters()));
     /*****************
      ** Find         *
      *****************/

     @Test
     public void test_findCards(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","dog");
         note.setItem("Back","cat");
         note.addTag("monkey animal_1 * %");
         col.addNote(note);
         long f1id = note.getId();
         firstCarlong did = note.cards().get(0).getId();
         Note note = col.newNote();
         note.setItem("Front","goats are fun");
         note.setItem("Back","sheep");
         note.addTag("sheep goat horse animal11");
         col.addNote(note);
         long f2id = note.getId();
         Note note = col.newNote();
         note.setItem("Front","cat");
         note.setItem("Back","sheep");
         col.addNote(note);
         catCard = note.cards().get(0);
         Model m = col.getModels().current();
         Model m = col.getModels().copy(m);
         Models mm = col.getModels();
         JSONObject t = mm.newTemplate("Reverse");
         t.put("qfmt", "{{Back}}");
         t.put("afmt", "{{Front}}");
         mm.addTemplateModChanged(m, t);
         mm.save(m);
         Note note = col.newNote();
         note.setItem("Front","test");
         note.setItem("Back","foo bar");
         col.addNote(note);
         col.save();
         long[] latestCardIds = [c.getId() for c in note.cards()];
         // tag searches
         assertEquals( 5, col.findCards("tag:*").size() );
         assertEquals( 1, col.findCards("tag:\\*").size() );
         assertEquals( 5, col.findCards("tag:%").size() );
         assertEquals( 1, col.findCards("tag:\\%").size() );
         assertEquals( 2, col.findCards("tag:animal_1").size() );
         assertEquals( 1, col.findCards("tag:animal\\_1").size() );
         assertFalse(col.findCards("tag:donkey"));
         assertEquals( 1, col.findCards("tag:sheep").size() );
         assertEquals( 1, col.findCards("tag:sheep tag:goat").size() );
         assertEquals( 0, col.findCards("tag:sheep tag:monkey").size() );
         assertEquals( 1, col.findCards("tag:monkey").size() );
         assertEquals( 1, col.findCards("tag:sheep -tag:monkey").size() );
         assertEquals( 4, col.findCards("-tag:sheep").size() );
         col.getTags().bulkAdd(col.getDb().list("select id from notes"), "foo bar");
         assertEquals( col.findCards("tag:bar", col.findCards("tag:foo").size() ).size() == 5);
         col.getTags().bulkRem(col.getDb().list("select id from notes"), "foo");
         assertEquals( 0, col.findCards("tag:foo").size() );
         assertEquals( 5, col.findCards("tag:bar").size() );
         // text searches
         assertEquals( 2, col.findCards("cat").size() );
         assertEquals( 1, col.findCards("cat -dog").size() );
         assertEquals( 1, col.findCards("cat -dog").size() );
         assertEquals( 1, col.findCards("are goats").size() );
         assertEquals( 0, col.findCards('"are goats"').size() );
         assertEquals( 1, col.findCards('"goats are"').size() );
         // card states
         Card c = note.cards().get(0);
         c.queue = c.type = CARD_TYPE_REV;
         assertEquals( [], col.findCards("is:review") );
         c.flush();
         assertEquals( [c.getId(, col.findCards("is:review") )]);
         assertEquals( [], col.findCards("is:due") );
         c.due = 0;
         c.queue = QUEUE_TYPE_REV;
         c.flush();
         assertEquals( [c.getId(, col.findCards("is:due") )]);
         assertEquals( 4, col.findCards("-is:due").size() );
         c.queue = -1;
         // ensure this card gets a later mod time
         c.flush();
         col.getDb().execute("update cards set mod = mod + 1 where long id = ?", c.getId());
         assertEquals( [c.getId(, col.findCards("is:suspended") )]);
         // nids
         assertEquals( [], col.findCards("nid:54321") );
         assertEquals( 2, col.findCards(f"nid:{note.getId()}").size() );
         assertEquals( 2, col.findCards(f"nid:{f1id},{f2id}").size() );
         // templates
         assertEquals( 0, col.findCards("card:foo").size() );
         assertEquals( 4, col.findCards('"card:card 1"').size() );
         assertEquals( 1, col.findCards("card:reverse").size() );
         assertEquals( 4, col.findCards("card:1").size() );
         assertEquals( 1, col.findCards("card:2").size() );
         // fields
         assertEquals( 1, col.findCards("front:dog").size() );
         assertEquals( 4, col.findCards("-front:dog").size() );
         assertEquals( 0, col.findCards("front:sheep").size() );
         assertEquals( 2, col.findCards("back:sheep").size() );
         assertEquals( 3, col.findCards("-back:sheep").size() );
         assertEquals( 0, col.findCards("front:do").size() );
         assertEquals( 5, col.findCards("front:*").size() );
         // ordering
         col..getConf().put("sortType", "noteCrt");
         col.flush();
         assertTrue(col.findCards("front:*", order=true)[-1] in latestCardIds);
         assertTrue(col.findCards("", order=true)[-1] in latestCardIds);
         col..getConf().put("sortType", "noteFld");
         col.flush();
         assertEquals( catCard.getId(, col.findCards("", order=true)[0] ));
         assertTrue(col.findCards("", order=true)[-1] in latestCardIds);
                  col..getConf().put("sortType", "cardMod");
         col.flush();
         assertTrue(col.findCards("", order=true)[-1] in latestCardIds);
         assertEquals( firstCardId, col.findCards("", order=true)[0] );
                  col..getConf().put("sortBackwards", true);
         col.flush();
         assertTrue(col.findCards("", order=true)[0] in latestCardIds);
         assertTrue(();
             col.find_cards("", order=BuiltinSortKind.CARD_DUE, reverse=false)[0];
             == firstCardId;
     );
         assertTrue(();
             col.find_cards("", order=BuiltinSortKind.CARD_DUE, reverse=true)[0];
             != firstCardId;
     );
         // model
         assertEquals( 3, col.findCards("note:basic").size() );
         assertEquals( 2, col.findCards("-note:basic").size() );
         assertEquals( 5, col.findCards("-note:foo").size() );
         // col
         assertEquals( 5, col.findCards("deck:default").size() );
         assertEquals( 0, col.findCards("-deck:default").size() );
         assertEquals( 5, col.findCards("-deck:foo").size() );
         assertEquals( 5, col.findCards("deck:def*").size() );
         assertEquals( 5, col.findCards("deck:*EFAULT").size() );
         assertEquals( 0, col.findCards("deck:*cefault").size() );
         // full search
         Note note = col.newNote();
         note.setItem("Front","hello<b>world</b>");
         note.setItem("Back","abc");
         col.addNote(note);
         // as it's the sort field, it matches
         assertEquals( 2, col.findCards("helloworld").size() );
         // assertEquals( , col.findCards("helloworld", full=true).size() )2
         // if we put it on the back, it won't
         (note.setItem("Front","Back")]) = (note.setItem("Back","Front")]);
         note.flush();
assertEquals( 0, col.findCards("helloworld").size() );
         // assertEquals( , col.findCards("helloworld", full=true).size() )2
         // assertEquals( , col.findCards("back:helloworld", full=true).size() )2
         // searching for an invalid special tag should not error
         with pytest.raises(Exception):
             col.findCards("is:invalid").size();
         // should be able to limit to parent col, no children
 long id = col.getDb().scalar("select id from cards limit 1");
         col.getDb().execute(;
             "update cards set long did = ? where long id = ?", col.getDecks().id("Default::Child"), id;
     );
         col.save();
assertEquals( 7, col.findCards("deck:default").size() );
assertEquals( 1, col.findCards("deck:default::child").size() );
assertEquals( 6, col.findCards("deck:default -deck:default::*").size() );
         // properties
 long id = col.getDb().scalar("select id from cards limit 1");
         col.getDb().execute(;
             "update cards set queue=2, ivl=10, reps=20, due=30, factor=2200 ";
             "where long id = ?",;
             id,;
     );
assertEquals( 1, col.findCards("prop:ivl>5").size() );
         assertTrue(col.findCards("prop:ivl<5").size() > 1);
assertEquals( 1, col.findCards("prop:ivl>=5").size() );
assertEquals( 0, col.findCards("prop:ivl=9").size() );
assertEquals( 1, col.findCards("prop:ivl=10").size() );
         assertTrue(col.findCards("prop:ivl!=10").size() > 1);
assertEquals( 1, col.findCards("prop:due>0").size() );
         // due dates should work
assertEquals( 0, col.findCards("prop:due=29").size() );
assertEquals( 1, col.findCards("prop:due=30").size() );
         // ease factors
assertEquals( 0, col.findCards("prop:ease=2.3").size() );
assertEquals( 1, col.findCards("prop:ease=2.2").size() );
assertEquals( 1, col.findCards("prop:ease>2").size() );
         assertTrue(col.findCards("-prop:ease>2").size() > 1);
         // recently failed
         if (not isNearCutoff()) {
             assertEquals( 0, col.findCards("rated:1:1").size() );
             assertEquals( 0, col.findCards("rated:1:2").size() );
             Card c = col.getSched().getCard();
             col.getSched().answerCard(c, 2);
             assertEquals( 0, col.findCards("rated:1:1").size() );
             assertEquals( 1, col.findCards("rated:1:2").size() );
             Card c = col.getSched().getCard();
             col.getSched().answerCard(c, 1);
             assertEquals( 1, col.findCards("rated:1:1").size() );
             assertEquals( 1, col.findCards("rated:1:2").size() );
             assertEquals( 2, col.findCards("rated:1").size() );
             assertEquals( 0, col.findCards("rated:0:2").size() );
             assertEquals( 1, col.findCards("rated:2:2").size() );
             // added
             assertEquals( 0, col.findCards("added:0").size() );
             col.getDb().execute("update cards set long id = id - 86400*1000 where long id = ?", id);
             assertEquals( col.cardCount(, col.findCards("added:1").size() ) - 1);
             assertEquals( col.cardCount(, col.findCards("added:2").size() ));
         } else {
             print("some find tests disabled near cutoff");
         }
         // empty field
assertEquals( 0, col.findCards("front:").size() );
         Note note = col.newNote();
note.setItem("Front","");
note.setItem("Back","abc2");
assertEquals( 1, col.addNote(note) );
assertEquals( 1, col.findCards("front:").size() );
         // OR searches and nesting
assertEquals( 2, col.findCards("tag:monkey or tag:sheep").size() );
assertEquals( 2, col.findCards("(tag:monkey OR tag:sheep)").size() );
assertEquals( 6, col.findCards("-(tag:monkey OR tag:sheep)").size() );
assertEquals( 2, col.findCards("tag:monkey or (tag:sheep sheep)").size() );
assertEquals( 1, col.findCards("tag:monkey or (tag:sheep octopus)").size() );
         // flag
         with pytest.raises(Exception):
             col.findCards("flag:12");
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
         nids = [note.getId(), note2.getId()];
         // should do nothing
         assertEquals( 0, col.findReplace(nids, "abc", "123") );
         // global replace
         assertEquals( 2, col.findReplace(nids, "foo", "qux") );
         note.load();
         assertTrue(note.setItem("Front","qux"));
         note2.load();
         assertTrue(note2.setItem("Back","qux"));
         // single field replace
         assertEquals( 1, col.findReplace(nids, "qux", "foo", field="Front") );
         note.load();
         assertTrue(note.setItem("Front","foo"));
         note2.load();
         assertTrue(note2.setItem("Back","qux"));
         // regex replace
         assertEquals( 0, col.findReplace(nids, "B.r", "reg") );
         note.load();
         assertTrue(note.setItem("Back","reg"));
         assertEquals( 1, col.findReplace(nids, "B.r", "reg", regex=true) );
         note.load();
         assertTrue(note.setItem("Back","reg"));
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
         note3 = col.newNote();
         note3.setItem("Front","quux");
         note3.setItem("Back","bar");
         col.addNote(note3);
         note4 = col.newNote();
         note4.setItem("Front","quuux");
         note4.setItem("Back","nope");
         col.addNote(note4);
         r = col.findDupes("Back");
         assertEquals( "bar", r[0][0] );
         assertEquals( 3, r[0][1].size() );
         // valid search
         r = col.findDupes("Back", "bar");
         assertEquals( "bar", r[0][0] );
         assertEquals( 3, r[0][1].size() );
         // excludes everything
         r = col.findDupes("Back", "invalid");
         assertFalse(r);
         // front isn't dupe
         assertEquals( [], col.findDupes("Front") );
     }

      /*****************
      ** Importing    *
      *****************/
      private void clear_tempfile(tf) {
             ;
         """ https://stackoverflow.com/questions/23212435/permission-denied-to-write-to-my-temporary-file """;
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
         n.put("Front", "[sound:foo.mp3]");
         mid = n.model().getLong("id");
         col.addNote(n);
         // add that sound to media folder
         with open(os.path.join(col.media.dir(), "foo.mp3"), "w") as note:
             note.write("foo");
         col.close();
         // it should be imported correctly into an empty deck
         Collection empty = getCol();
         Anki2Importer imp = Anki2Importer(empty, col.getPath());
         imp.run();
         assertEquals( ["foo.mp3"], os.listdir(empty.media.dir()) );
         // and importing again will not duplicate, as the file content matches
         empty.remove_cards_and_orphaned_notes(empty.getDb().list("select id from cards"));
         Anki2Importer imp = Anki2Importer(empty, col.getPath());
         imp.run();
         assertEquals( ["foo.mp3"], os.listdir(empty.media.dir()) );
         Note n = empty.getNote(empty.getDb().scalar("select id from notes"));
         assertTrue("foo.mp3" in n.fields[0]);
         // if the local file content is different, and import should trigger a
         // rename
         empty.remove_cards_and_orphaned_notes(empty.getDb().list("select id from cards"));
         with open(os.path.join(empty.media.dir(), "foo.mp3"), "w") as note:
             note.write("bar");
         Anki2Importer imp = Anki2Importer(empty, col.getPath());
         imp.run();
         assertEquals( ["foo.mp3", "foo_%s.mp3" % mid], sorted(os.listdir(empty.media.dir())) );
         Note n = empty.getNote(empty.getDb().scalar("select id from notes"));
         assertTrue("_" in n.fields[0]);
         // if the localized media file already exists, we rewrite the note and
         // media
         empty.remove_cards_and_orphaned_notes(empty.getDb().list("select id from cards"));
         with open(os.path.join(empty.media.dir(), "foo.mp3"), "w") as note:
             note.write("bar");
         Anki2Importer imp = Anki2Importer(empty, col.getPath());
         imp.run();
         assertEquals( ["foo.mp3", "foo_%s.mp3" % mid], sorted(os.listdir(empty.media.dir())) );
         assertEquals( ["foo.mp3", "foo_%s.mp3" % mid], sorted(os.listdir(empty.media.dir())) );
         Note n = empty.getNote(empty.getDb().scalar("select id from notes"));
         assertTrue("_" in n.fields[0]);
     }

     @Test
     public void test_apkg(){
         Collection col = getCol();
         String apkg = str(os.path.join(testDir, "support/media.apkg"));
         AnkiPackageImporter imp = AnkiPackageImporter(col, apkg);
         assertEquals( [], os.listdir(col.media.dir()) );
         imp.run();
         assertEquals( ["foo.wav"], os.listdir(col.media.dir()) );
         // importing again should be idempotent in terms of media
         col.remove_cards_and_orphaned_notes(col.getDb().list("select id from cards"));
         AnkiPackageImporter imp = AnkiPackageImporter(col, apkg);
         imp.run();
         assertEquals( ["foo.wav"], os.listdir(col.media.dir()) );
         // but if the local file has different data, it will rename
         col.remove_cards_and_orphaned_notes(col.getDb().list("select id from cards"));
         with open(os.path.join(col.media.dir(), "foo.wav"), "w") as note:
             note.write("xyz");
         imp = AnkiPackageImporter(col, apkg);
         imp.run();
         assertEquals( 2, os.listdir(col.media.dir()).size() );
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
         assertEquals( 1, dst.noteCount() );
         // the front template should contain the text added in the 2nd package
         tlong cid = dst.findCards("")[0]  // only 1 note in collection
         tNote note = dst.getCard(tcid).note();
         assertTrue("Changed Front Template" in tnote.cards().get(0).template()["qfmt"]);
     }

     @Test
     public void test_anki2_updates(){
         // create a new empty deck
         dst = getCol();
         Collection col = getUpgradeDeckPath("update1.apkg");
         AnkiPackageImporter imp = AnkiPackageImporter(dst, col);
         imp.run();
         assertEquals( 0, imp.dupes );
         assertEquals( 1, imp.added );
         assertEquals( 0, imp.updated );
         // importing again should be idempotent
         imp = AnkiPackageImporter(dst, col);
         imp.run();
         assertEquals( 1, imp.dupes );
         assertEquals( 0, imp.added );
         assertEquals( 0, imp.updated );
         // importing a newer note should update
         assertEquals( 1, dst.noteCount() );
         assertTrue(dst.getDb().scalar("select flds from notes").startswith("hello"));
         Collection col = getUpgradeDeckPath("update2.apkg");
         imp = AnkiPackageImporter(dst, col);
         imp.run();
         assertEquals( 0, imp.dupes );
         assertEquals( 0, imp.added );
         assertEquals( 1, imp.updated );
         assertEquals( 1, dst.noteCount() );
         assertTrue(dst.getDb().scalar("select flds from notes").startswith("goodbye"));
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
         assertEquals( 5, i.log.size() );
         assertEquals( 5, i.total );
         // if we run the import again, it should update instead
         i.run();
         assertEquals( 10, i.log.size() );
         assertEquals( 5, i.total );
         // but importing should not clobber tags if they're unmapped
         Note n = col.getNote(col.getDb().scalar("select id from notes"));
         n.addTag("test");
         n.flush();
         i.run();
         n.load();
         assertEquals( ["test"], n.tags );
         // if add-only mode, count will be 0
         i.importMode = 1;
         i.run();
         assertEquals( 0, i.total );
         // and if dupes mode, will reimport everything
         assertEquals( 5, col.cardCount() );
         i.importMode = 2;
         i.run();
         // includes repeated field
         assertEquals( 6, i.total );
         assertEquals( 11, col.cardCount() );
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
         n.put("Front", "1");
         n.put("Back", "2");
         n.put("Three", "3");
         col.addNote(n);
         // an update with unmapped fields should not clobber those fields
         file = str(os.path.join(testDir, "support/text-update.txt"));
         TextImporter i = TextImporter(col, file);
         i.initMapping();
         i.run();
         n.load();
         assertTrue(n.put("Front",= "1"));
         assertTrue(n.put("Back",= "x"));
         assertTrue(n.put("Three",= "3"));
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
         n.put("Front", "1");
         n.put("Back", "2");
         n.put("Top", "3");
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
         assertTrue(n.put("Front",= "1"));
         assertTrue(n.put("Back",= "b"));
         assertTrue(n.put("Top",= "c"));
         assertTrue("four" in n.getTags());
         assertTrue("boom" in n.getTags());
         assertEquals( 2, n.getTags().size() );
         assertEquals( 1, i.updateCount );

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
         n.put("Front", "1");
         n.put("Back", "2");
         n.put("Top", "3");
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
         assertTrue(n.put("Front",= "1"));
         assertTrue(n.put("Back",= "b"));
         assertTrue(n.put("Top",= "c"));
         assertEquals( list(sorted(["four", "five", "six"], list(sorted(n.getTags())) )));

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
         n.put("Front", "1");
         n.put("Back", "2");
         n.put("Left", "3");
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
         assertEquals( [], n.tags );
         assertEquals( 0, i.updateCount );

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
         assertEquals( 1, i.total );
         long cid = col.getDb().scalar("select id from cards");
         Card c = col.getCard(cid);
         // Applies A Factor-to-E Factor conversion
         assertEquals( 2879, c.factor );
         assertEquals( 7, c.reps );
         col.close();
     }

     @Test
     public void test_mnemo(){
         Collection col = getCol();
         String file = str(os.path.join(testDir, "support/mnemo.getDb()"));
         MnemosyneImporter i = MnemosyneImporter(col, file);
         i.run();
         assertEquals( 7, col.cardCount() );
         assertTrue("a_longer_tag" in col.getTags().all());
         assertEquals( 1, col.getDb().queryScalar("select count() from cards where type = 0") );
         col.close();
     }

     /*****************
      ** Flags        *
      *****************/

     @Test
     public void test_flags(){
         Collection col = getCol();
         Note n = col.newNote();
         n.put("Front", "one");
         n.put("Back", "two");
         int cnt = col.addNote(n);
         Card c = n.cards().get(0);
         // make sure higher bits are preserved
         int origBits = 0b101 << 3;
         c.flags = origBits;
         c.flush();
         // no flags to start with
         assertEquals( 0, c.userFlag() );
         assertEquals( 1, col.findCards("flag:0").size() );
         assertEquals( 0, col.findCards("flag:1").size() );
         // set flag 2
         col.setUserFlag(2, [c.getId()]);
         c.load();
         assertEquals( 2, c.userFlag() );
         assertEquals( origBits, c.flags & origBits );
         assertEquals( 0, col.findCards("flag:0").size() );
         assertEquals( 1, col.findCards("flag:2").size() );
         assertEquals( 0, col.findCards("flag:3").size() );
         // change to 3
         col.setUserFlag(3, [c.getId()]);
         c.load();
         assertEquals( 3, c.userFlag() );
         // unset
         col.setUserFlag(0, [c.getId()]);
         c.load();
         assertEquals( 0, c.userFlag() );

         // should work with Cards method as well
         c.setUserFlag(2);
         assertEquals( 2, c.userFlag() );
         c.setUserFlag(3);
         assertEquals( 3, c.userFlag() );
         c.setUserFlag(0);
         assertEquals( 0, c.userFlag() );
     }
     /*****************
      ** Media        *
      *****************/
     // copying files to media folder

     @Test
     public void test_add(){
         Collection col = getCol();
         String dir = tempfile.mkdtemp(prefix="anki");
         String path = os.path.join(dir, "foo.jpg");
         with open(path, "w") as note:
             note.write("hello");
         // new file, should preserve name
             assertEquals( "foo.jpg", col.media.addFile(path) );
         // adding the same file again should not create a duplicate
             assertEquals( "foo.jpg", col.media.addFile(path) );
         // but if it has a different sha1, it should
         with open(path, "w") as note:
             note.write("world");
             assertEquals( "foo-7c211433f02071597741e6ff5a8ea34789abbf43.jpg", col.media.addFile(path) );
     }

     @Test
     public void test_strings(){
         Collection col = getCol();
         mf = col.media.filesInStr;
         mid = col.getModels().current().getLong("id");
         assertEquals( [], mf(mid, "aoeu") );
         assertEquals( ["foo.jpg"], mf(mid, "aoeu<img src='foo.jpg'>ao") );
         assertEquals( ["foo.jpg"], mf(mid, "aoeu<img src='foo.jpg' style='test'>ao") );
         assertEquals( [, mf(mid, "aoeu<img src='foo.jpg'><img src=\"bar.jpg\">ao") );
             "foo.jpg",;
             "bar.jpg",;
     ];
         assertEquals( ["foo.jpg"], mf(mid, "aoeu<img src=foo.jpg style=bar>ao") );
         assertEquals( ["one", "two"], mf(mid, "<img src=one><img src=two>") );
         assertEquals( ["foo.jpg"], mf(mid, 'aoeu<img src="foo.jpg">ao') );
         assertEquals( [, mf(mid, 'aoeu<img src="foo.jpg"><img class=yo src=fo>ao') );
             "foo.jpg",;
             "fo",;
     ];
         assertEquals( ["foo.mp3"], mf(mid, "aou[sound:foo.mp3]aou") );
         sp = col.media.strip;
         assertEquals( "aoeu", sp("aoeu") );
         assertEquals( "aoeuaoeu", sp("aoeu[sound:foo.mp3]aoeu") );
         assertEquals( "aoeu", sp("a<img src=yo>oeu") );
         es = col.media.escapeImages;
         assertEquals( "aoeu", es("aoeu") );
         assertEquals( "<img src='http://foo.com'>", es("<img src='http://foo.com'>") );
         assertEquals( '<img src="foo%20bar.jpg">', es('<img src="foo bar.jpg">') );
     }

     @Test
     public void test_deckIntegration(){
         Collection col = getCol();
         // create a media dir
         col.media.dir();
         // put a file into it
         file = str(os.path.join(testDir, "support/fake.png"));
         col.media.addFile(file);
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
         with open(os.path.join(col.media.dir(), "foo.jpg"), "w") as note:
             note.write("test");
         // check media
         ret = col.media.check();
         assertEquals( ["fake2.png"], ret.missing );
         assertEquals( ["foo.jpg"], ret.unused );
     }
     /*****************
      ** Models       *
      *****************/

     @Test
     public void test_modelDelete(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","1");
         note.setItem("Back","2");
         col.addNote(note);
         assertEquals( 1, col.cardCount() );
         col.getModels().rem(col.getModels().current());
         assertEquals( 0, col.cardCount() );
     }

     @Test
     public void test_modelCopy(){
         Collection col = getCol();
         Model m = col.getModels().current();
         Model m2 = col.getModels().copy(m);
         assertTrue(m2.put("name",= "Basic copy"));
         assertTrue(m2.getLong("id") != m.getLong("id"));
         assertEquals( 2, m2["flds"].size() );
         assertEquals( 2, m["flds"].size() );
         assertEquals( m["flds"].size(, m2["flds"].size() ));
         assertEquals( 1, m["tmpls"].size() );
         assertEquals( 1, m2["tmpls"].size() );
         assertEquals( col.getModels(, col.getModels().scmhash(m) ).scmhash(m2));
     }

     @Test
     public void test_fields(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","1");
         note.setItem("Back","2");
         col.addNote(note);
         Model m = col.getModels().current();
         // make sure renaming a field updates the templates
         col.getModels().renameField(m, m["flds"][0], "NewFront");
         assertTrue("{{NewFront}}" in m["tmpls"][0]["qfmt"]);
         String h = col.getModels().scmhash(m);
         // add a field
         Note note = col.getModels().newField("foo");
         col.getModels().addField(m, note);
         assertEquals( ["1", "2", ""], col.getNote(col.getModels().nids(m)[0]).fields );
         assertNotEquals( h, col.getModels().scmhash(m) );
         // rename it
         Note note = m["flds"][2];
         col.getModels().renameField(m, note, "bar");
         assertTrue(col.getNote(col.getModels().nids(m)[0]).put("bar",= ""));
         // delete back
         col.getModels().remField(m, m["flds"][1]);
         assertEquals( ["1", ""], col.getNote(col.getModels().nids(m)[0]).fields );
         // move 0 -> 1
         col.getModels().moveField(m, m["flds"][0], 1);
         assertEquals( ["", "1"], col.getNote(col.getModels().nids(m)[0]).fields );
         // move 1 -> 0
         col.getModels().moveField(m, m["flds"][1], 0);
         assertEquals( ["1", ""], col.getNote(col.getModels().nids(m)[0]).fields );
         // add another and put in middle
         Note note = col.getModels().newField("baz");
         col.getModels().addField(m, note);
         Note note = col.getNote(col.getModels().nids(m)[0]);
         note.setItem("baz","2");
         note.flush();
         assertEquals( ["1", "", "2"], col.getNote(col.getModels().nids(m)[0]).fields );
         // move 2 -> 1
         col.getModels().moveField(m, m["flds"][2], 1);
         assertEquals( ["1", "2", ""], col.getNote(col.getModels().nids(m)[0]).fields );
         // move 0 -> 2
         col.getModels().moveField(m, m["flds"][0], 2);
         assertEquals( ["2", "", "1"], col.getNote(col.getModels().nids(m)[0]).fields );
         // move 0 -> 1
         col.getModels().moveField(m, m["flds"][0], 1);
         assertEquals( ["", "2", "1"], col.getNote(col.getModels().nids(m)[0]).fields );
     }

     @Test
     public void test_templates(){
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
         assertEquals( 2, col.cardCount() );
         (c, c2) = note.cards();
         // first card should have first ord
         assertEquals( 0, c.ord );
         assertEquals( 1, c2.ord );
         // switch templates
         col.getModels().moveTemplate(m, c.template(), 1);
         c.load();
         c2.load();
         assertEquals( 1, c.ord );
         assertEquals( 0, c2.ord );
         // removing a template should delete its cards
         col.getModels().remTemplate(m, m["tmpls"][0]);
         assertEquals( 1, col.cardCount() );
         // and should have updated the other cards' ordinals
         Card c = note.cards().get(0);
         assertEquals( 0, c.ord );
         assertEquals( "1", stripHTML(c.q()) );
         // it shouldn't be possible to orphan notes by removing templates
         JSONObject t = mm.newTemplate("template name");
         mm.addTemplateModChanged(m, t);
         col.getModels().remTemplate(m, m["tmpls"][0]);
         assertTrue(();
             col.getDb().scalar(;
                 "select count() from cards where nid not in (select id from notes)";
         );
             == 0;
     );
     }

     @Test
     public void test_cloze_ordinals(){
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
         col.getModels().remTemplate(m, m["tmpls"][0]);

         Note note = col.newNote();
         note.setItem("Text","{{c1::firstQ::firstA}}{{c2::secondQ::secondA}}");
         col.addNote(note);
         assertEquals( 2, col.cardCount() );
         (c, c2) = note.cards();
         // first card should have first ord
         assertEquals( 0, c.ord );
         assertEquals( 1, c2.ord );
     }

     @Test
     public void test_text(){
         Collection col = getCol();
         Model m = col.getModels().current();
         m["tmpls"][0].put("qfmt", "{{text:Front}}");
         col.getModels().save(m);
         Note note = col.newNote();
         note.setItem("Front","hello<b>world");
         col.addNote(note);
         assertTrue("helloworld" in note.cards().get(0).q());
     }

     @Test
     public void test_cloze(){
         Collection col = getCol();
         col.getModels().setCurrent(col.getModels().byName("Cloze"));
         Note note = col.newNote();
         assertTrue(note.model().put("name",= "Cloze"));
         // a cloze model with no clozes is not empty
         note.setItem("Text","nothing");
         assertTrue(col.addNote(note));
         // try with one cloze
         Note note = col.newNote();
         note.setItem("Text","hello {{c1::world}}");
         assertEquals( 1, col.addNote(note) );
         assertTrue("hello <span class=cloze>[...]</span>" in note.cards().get(0).q());
         assertTrue("hello <span class=cloze>world</span>" in note.cards().get(0).a());
         // and with a comment
         Note note = col.newNote();
         note.setItem("Text","hello {{c1::world::typical}}");
         assertEquals( 1, col.addNote(note) );
         assertTrue("<span class=cloze>[typical]</span>" in note.cards().get(0).q());
         assertTrue("<span class=cloze>world</span>" in note.cards().get(0).a());
         // and with 2 clozes
         Note note = col.newNote();
         note.setItem("Text","hello {{c1::world}} {{c2::bar}}");
         assertEquals( 2, col.addNote(note) );
         (c1, c2) = note.cards();
         assertTrue("<span class=cloze>[...]</span> bar" in c1.q());
         assertTrue("<span class=cloze>world</span> bar" in c1.a());
         assertTrue("world <span class=cloze>[...]</span>" in c2.q());
         assertTrue("world <span class=cloze>bar</span>" in c2.a());
         // if there are multiple answers for a single cloze, they are given in a
         // list
         Note note = col.newNote();
         note.setItem("Text","a {{c1::b}} {{c1::c}}");
         assertEquals( 1, col.addNote(note) );
         assertTrue("<span class=cloze>b</span> <span class=cloze>c</span>" in ();
             note.cards().get(0).a();
     );
         // if we add another cloze, a card should be generated
         cnt = col.cardCount();
         note.setItem("Text","{{c2::hello}} {{c1::foo}}");
         note.flush();
         assertEquals( cnt + 1, col.cardCount() );
         // 0 or negative indices are not supported
         note.setItem("Text","{{c0::zero}} {{c-1:foo}}");
         note.flush();
         assertEquals( 2, note.cards().size() );
     }

     @Test
     public void test_cloze_mathjax(){
         Collection col = getCol();
         col.getModels().setCurrent(col.getModels().byName("Cloze"));
         Note note = col.newNote();
         note[;
             "Text";
         ] = r"{{c1::ok}} \(2^2\) {{c2::not ok}} \(2^{{c3::2}}\) \(x^3\) {{c4::blah}} {{c5::text with \(x^2\) jax}}";
         assertTrue(col.addNote(note));
         assertEquals( 5, note.cards().size() );
         assertTrue("class=cloze" in note.cards().get(0).q());
         assertTrue("class=cloze" in note.cards().get(1).q());
         assertTrue("class=cloze" not in note.cards().get(2).q());
         assertTrue("class=cloze" in note.cards().get(3).q());
         assertTrue("class=cloze" in note.cards().get(4).q());

         Note note = col.newNote();
         note.setItem("Text","\(a\) {{c1::b}} \[ {{c1::c}} \]");
         assertTrue(col.addNote(note));
         assertEquals( 1, note.cards().size() );
         assertTrue(();
             note.cards().get(0);
             .q();
             .endswith(r"\(a\) <span class=cloze>[...]</span> \[ [...] \]");
     );
     }

     @Test
     public void test_typecloze(){
         Collection col = getCol();
         Model m = col.getModels().byName("Cloze");
         col.getModels().setCurrent(m);
         m["tmpls"][0].put("qfmt", "{{cloze:Text}}{{type:cloze:Text}}");
         col.getModels().save(m);
         Note note = col.newNote();
         note.setItem("Text","hello {{c1::world}}");
         col.addNote(note);
         assertTrue("[[type:cloze:Text]]" in note.cards().get(0).q());
     }

     @Test
     public void test_chained_mods(){
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
         col.getModels().remTemplate(m, m["tmpls"][0]);

         Note note = col.newNote();
         q1 = '<span style="color:red">phrase</span>';
         a1 = "<b>sentence</b>";
         q2 = '<span style="color:red">en chaine</span>';
         a2 = "<i>chained</i>";
         note.setItem("Text","This {{c1::%s::%s}} demonstrates {{c1::%s::%s}} clozes.") % (;
             q1,;
             a1,;
             q2,;
             a2,;
     );
         assertEquals( 1, col.addNote(note) );
         assertTrue(();
             "This <span class=cloze>[sentence]</span> demonstrates <span class=cloze>[chained]</span> clozes.";
             in note.cards().get(0).q();
     );
         assertTrue(();
             "This <span class=cloze>phrase</span> demonstrates <span class=cloze>en chaine</span> clozes.";
             in note.cards().get(0).a();
     );
     }

     @Test
     public void test_modelChange(){
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
         basiCard c = m;
         Note note = col.newNote();
         note.setItem("Front","note");
         note.setItem("Back","b123");
         col.addNote(note);
         // switch fields
         map = {0: 1, 1: 0}
         col.getModels().change(basic, [note.getId()], basic, map, null);
         note.load();
         assertTrue(note.setItem("Front","b123"));
         assertTrue(note.setItem("Back","note"));
         // switch cards
         Card c0 = note.cards().get(0);
         Card c1 = note.cards().get(1);
         assertTrue("b123" in c0.q());
         assertTrue("note" in c1.q());
         assertEquals( 0, c0.ord );
         assertEquals( 1, c1.ord );
         col.getModels().change(basic, [note.getId()], basic, null, map);
         note.load();
         c0.load();
         c1.load();
         assertTrue("note" in c0.q());
         assertTrue("b123" in c1.q());
         assertEquals( 1, c0.ord );
         assertEquals( 0, c1.ord );
         // .cards() returns cards in order
         assertEquals( c1.getId(, note.cards().get(0).getId() ));
         // delete first card
         map = {0: null, 1: 1};
         if (isWin) {
             // The low precision timer on Windows reveals a race condition
             time.sleep(0.05);
         }
         col.getModels().change(basic, [note.getId()], basic, null, map);
         note.load();
         c0.load();
         // the card was deleted
         try{
             c1.load();
             assertTrue(0);
         } catch ( NotFoundError) {
         }
         // but we have two cards, as a new one was generated
         assertEquals( 2, note.cards().size() );
         // an unmapped field becomes blank
             assertTrue(note.setItem("Front","b123"));
             assertTrue(note.setItem("Back","note"));
         col.getModels().change(basic, [note.getId()], basic, map, null);
         note.load();
         assertTrue(note.setItem("Front",""));
         assertTrue(note.setItem("Back","note"));
         // another note to try model conversion
         Note note = col.newNote();
         note.setItem("Front","f2");
         note.setItem("Back","b2");
         col.addNote(note);
         counts = col.getModels().all_use_counts();
         assertEquals( "Basic", next(c.use_count for c in counts if c.name ) == 2);
         assertEquals( "Cloze", next(c.use_count for c in counts if c.name ) == 0);
         map = {0: 0, 1: 1}
         col.getModels().change(basic, [note.getId()], cloze, map, map);
         note.load();
         assertTrue(note.setItem("Text","f2"));
         assertEquals( 2, note.cards().size() );
         // back the other way, with deletion of second ord
         col.getModels().remTemplate(basic, basic["tmpls"][1]);
         assertEquals( 2, col.getDb().queryScalar("select count() from cards where nid = ?", note.getId()) );
         map = {0: 0}
         col.getModels().change(cloze, [note.getId()], basic, map, map);
         assertEquals( 1, col.getDb().queryScalar("select count() from cards where nid = ?", note.getId()) );
     }

     @Test
     public void test_req(){
         def reqSize(model):
         if (model.put("type",= MODEL_CLOZE)) {
             return;
         }
         assertEquals( model["req"].size(, model["tmpls"].size() ));

         Collection col = getCol();
         Models mm = col.getModels();
         basiCard c = mm.byName("Basic");
         assertTrue("req" in basic);
         reqSize(basic);
         r = basic["req"][0];
         assertEquals( 0, r[0] );
         assertTrue(r[1] in ("any", "all"));
         assertEquals( [0], r[2] );
         opt = mm.byName("Basic (optional reversed card)");
         reqSize(opt);
         r = opt["req"][0];
         assertTrue(r[1] in ("any", "all"));
         assertEquals( [0], r[2] );
         assertEquals( [1, "all", [1, 2]], opt["req"][1] );
         // testing any
         opt["tmpls"][1].put("qfmt", "{{Back}}{{Add Reverse}}");
         mm.save(opt, true);
         assertEquals( [1, "any", [1, 2]], opt["req"][1] );
         // testing null
         opt["tmpls"][1].put("qfmt", "{{^Add Reverse}}{{/Add Reverse}}");
         mm.save(opt, true);
         assertEquals( [1, "none", []], opt["req"][1] );

         opt = mm.byName("Basic (type in the answer)");
         reqSize(opt);
         r = opt["req"][0];
         assertTrue(r[1] in ("any", "all"));
         assertEquals( [0, 1], r[2] );
     }

     /*****************
          ** SchedV1      *
      *****************/
     private Collection getCol(){
         Collection col = getColOrig();
         col.changeSchedulerVer(1);
         return col;
     }

     @Test
     public void test_clock(){
         Collection col = getCol();
         if ((col.getSched().dayCutoff - intTime()) < 10 * 60) {
             raise Exception("Unit tests will fail around the day rollover.");
         }
     }

     private boolean checkRevIvl(col, c, targetIvl) {
         min, max = col.getSched()._fuzzIvlRange(targetIvl);
         return min <= c.ivl <= max;
     }

     @Test
     public void test_basics(){
         Collection col = getCol();
         col.reset();
         assertFalse(col.getSched().getCard());
     }

     @Test
     public void test_new(){
         Collection col = getCol();
         col.reset();
         assertEquals( 0, col.getSched().newCount );
         // add a note
         Note note = col.newNote();
         note.setItem("Front","one");
         note.setItem("Back","two");
         col.addNote(note);
         col.reset();
         assertEquals( 1, col.getSched().newCount );
         // fetch it
         Card c = col.getSched().getCard();
         assertTrue(c);
         assertEquals( QUEUE_TYPE_NEW, c.queue );
         assertEquals( CARD_TYPE_NEW, c.type );
         // if we answer it, it should become a learn card
         JSONObject t = intTime();
         col.getSched().answerCard(c, 1);
         assertEquals( QUEUE_TYPE_LRN, c.queue );
         assertEquals( CARD_TYPE_LRN, c.type );
         assertTrue(c.due >= t);

         // disabled for now, as the learn fudging makes this randomly fail
         // // the default order should ensure siblings are not seen together, and
         // // should show all cards
         // Model m = col.getModels().current(); Models mm = col.getModels()
         // JSONObject t = mm.newTemplate("Reverse")
         // t['qfmt'] = "{{Back}}"
         // t['afmt'] = "{{Front}}"
         // mm.addTemplateModChanged(m, t)
         // mm.save(m)
         // Note note = col.newNote()
         // note['Front'] = u"2"; note['Back'] = u"2"
         // col.addNote(note)
         // Note note = col.newNote()
         // note['Front'] = u"3"; note['Back'] = u"3"
         // col.addNote(note)
         // col.reset()
         // qs = ("2", "3", "2", "3")
         // for (int n = 0; n < 4; n++) {
         //     Card c = col.getSched().getCard()
         //     assertTrue(qs[n] in c.q())
         //     col.getSched().answerCard(c, 2)
         // }
     }

     @Test
     public void test_newLimits(){
         Collection col = getCol();
         // add some notes
         deck2 = col.getDecks().id("Default::foo");
         for (i =0; i < 30; i++) {
             Note note = col.newNote();
         }
         note.setItem("Front","did")] = deck2;
             col.addNote(note);
         // give the child deck a different configuration
         c2 = col.getDecks().add_config_returning_id("new conf");
         col.getDecks().setConf(col.getDecks().get(deck2), c2);
         col.reset();
         // both confs have defaulted to a limit of 20
assertEquals( 20, col.getSched().newCount );
         // first card we get comes from parent
         Card c = col.getSched().getCard();
assertEquals( 1, c.long did );
         // limit the parent to 10 cards, meaning we get 10 in total
         conf1 = col.getDecks().confForDid(1);
         conf1["new"].put("perDay", 10);
         col.getDecks().save(conf1);
         col.reset();
assertEquals( 10, col.getSched().newCount );
         // if we limit child to 4, we should get 9
         conf2 = col.getDecks().confForDid(deck2);
         conf2["new"].put("perDay", 4);
         col.getDecks().save(conf2);
         col.reset();
assertEquals( 9, col.getSched().newCount );
     }

     @Test
     public void test_newBoxes(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         col.reset();
         Card c = col.getSched().getCard();
         conf = col.getSched()._cardConf(c);
         conf["new"].put("delays", [1, 2, 3, 4, 5]);
         col.getDecks().save(conf);
         col.getSched().answerCard(c, 2);
         // should handle gracefully
         conf["new"].put("delays", [1]);
         col.getDecks().save(conf);
         col.getSched().answerCard(c, 2);
     }

     @Test
     public void test_learn(){
         Collection col = getCol();
         // add a note
         Note note = col.newNote();
         note.setItem("Front","one");
         note.setItem("Back","two");
         Note note = col.addNote(note);
         // set as a learn card and rebuild queues
         col.getDb().execute("update cards set queue=0, type=0");
         col.reset();
         // sched.getCard should return it, since it's due in the past
         Card c = col.getSched().getCard();
         assertTrue(c);
         conf = col.getSched()._cardConf(c);
         conf["new"].put("delays", [0.5, 3, 10]);
         col.getDecks().save(conf);
         // fail it
         col.getSched().answerCard(c, 1);
         // it should have three reps left to graduation
         assertEquals( 3, c.left % 1000 );
         assertEquals( , c.left // 1000 )3
         // it should by due in 30 seconds
         JSONObject t = round(c.due - time.time());
                assertTrue(t >= 25 and t <= 40);
         // pass it once
         col.getSched().answerCard(c, 2);
         // it should by due in 3 minutes
                assertTrue(round(c.due - time.time()) in (179, 180));
                      assertEquals( 2, c.left % 1000 );
                      assertEquals( , c.left // 1000 )2
         // check log is accurate
         log = col.getDb().first("select * from revlog order by id desc");
                                   assertEquals( 2, log[3] );
                                   assertEquals( -180, log[4] );
                                   assertEquals( -30, log[5] );
         // pass again
         col.getSched().answerCard(c, 2);
         // it should by due in 10 minutes
                       assertTrue(round(c.due - time.time()) in (599, 600));
                                   assertEquals( 1, c.left % 1000 );
                                   assertEquals( , c.left // 1000 )1
         // the next pass should graduate the card
                                                assertEquals( QUEUE_TYPE_LRN, c.queue );
                                                assertEquals( CARD_TYPE_LRN, c.type );
         col.getSched().answerCard(c, 2);
                                                assertEquals( QUEUE_TYPE_REV, c.queue );
                                                assertEquals( CARD_TYPE_REV, c.type );
         // should be due tomorrow, with an interval of 1
                                                assertEquals( col.getSched(, c.due ).today + 1);
                                                assertEquals( 1, c.ivl );
         // or normal removal
         c.type = 0;
         c.queue = 1;
         col.getSched().answerCard(c, 3);
                                                assertEquals( CARD_TYPE_REV, c.type );
                                                assertEquals( QUEUE_TYPE_REV, c.queue );
                              assertTrue(checkRevIvl(col, c, 4));
         // revlog should have been updated each time
                                                assertEquals( 5, col.getDb().queryScalar("select count() from revlog where type = 0") );
         // now failed card handling
         c.type = CARD_TYPE_REV;
         c.queue = 1;
         c.odue = 123;
         col.getSched().answerCard(c, 3);
                                                assertEquals( 123, c.due );
                                                assertEquals( CARD_TYPE_REV, c.type );
                                                assertEquals( QUEUE_TYPE_REV, c.queue );
         // we should be able to remove manually, too
         c.type = CARD_TYPE_REV;
         c.queue = 1;
         c.odue = 321;
         c.flush();
         col.getSched().removeLrn();
         c.load();
                                                assertEquals( QUEUE_TYPE_REV, c.queue );
                                                assertEquals( 321, c.due );
     }

     @Test
     public void test_learn_collapsed(){
         Collection col = getCol();
         // add 2 notes
         Note note = col.newNote();
         note.setItem("Front","1");
         Note note = col.addNote(note);
         Note note = col.newNote();
         note.setItem("Front","2");
         Note note = col.addNote(note);
         // set as a learn card and rebuild queues
         col.getDb().execute("update cards set queue=0, type=0");
         col.reset();
         // should get '1' first
         Card c = col.getSched().getCard();
         assertTrue(c.q().endswith("1"));
         // pass it so it's due in 10 minutes
         col.getSched().answerCard(c, 2);
         // get the other card
         Card c = col.getSched().getCard();
         assertTrue(c.q().endswith("2"));
         // fail it so it's due in 1 minute
         col.getSched().answerCard(c, 1);
         // we shouldn't get the same card again
         Card c = col.getSched().getCard();
         assertFalse(c.q().endswith("2"));
     }

     @Test
     public void test_learn_day(){
         Collection col = getCol();
         // add a note
         Note note = col.newNote();
         note.setItem("Front","one");
         Note note = col.addNote(note);
         col.getSched().reset();
         Card c = col.getSched().getCard();
         conf = col.getSched()._cardConf(c);
         conf["new"].put("delays", [1, 10, 1440, 2880]);
         col.getDecks().save(conf);
         // pass it
         col.getSched().answerCard(c, 2);
         // two reps to graduate, 1 more today
         assertEquals( 3, c.left % 1000 );
         assertEquals( , c.left // 1000 )1
                      assertEquals( (0, 1, 0, col.getSched().counts() ));
         Card c = col.getSched().getCard();
         ni = col.getSched().nextIvl;
                      assertEquals( 86400, ni(c, 2) );
         // answering it will place it in queue 3
         col.getSched().answerCard(c, 2);
                      assertEquals( col.getSched(, c.due ).today + 1);
                      assertEquals( CARD_TYPE_RELEARNING, c.queue );
                assertFalse(col.getSched().getCard());
         // for testing, move it back a day
         c.due -= 1;
         c.flush();
         col.reset();
                      assertEquals( (0, 1, 0, col.getSched().counts() ));
         Card c = col.getSched().getCard();
         // nextIvl should work
                      assertEquals( 86400 * 2, ni(c, 2) );
         // if we fail it, it should be back in the correct queue
         col.getSched().answerCard(c, 1);
                      assertEquals( QUEUE_TYPE_LRN, c.queue );
         col.undo();
         col.reset();
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 2);
         // simulate the passing of another two days
         c.due -= 2;
         c.flush();
         col.reset();
         // the last pass should graduate it into a review card
                      assertEquals( 86400, ni(c, 2) );
         col.getSched().answerCard(c, 2);
                      assertEquals( CARD_TYPE_REV and c.type == QUEUE_TYPE_REV, c.queue );
         // if the lapse step is tomorrow, failing it should handle the counts
         // correctly
         c.due = 0;
         c.flush();
         col.reset();
                      assertEquals( (0, 0, 1, col.getSched().counts() ));
         conf = col.getSched()._cardConf(c);
                    conf["lapse"].put("delays", [1440]);
         col.getDecks().save(conf);
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 1);
                      assertEquals( CARD_TYPE_RELEARNING, c.queue );
                      assertEquals( (0, 0, 0, col.getSched().counts() ));
     }

     @Test
     public void test_reviews(){
         Collection col = getCol();
         // add a note
         Note note = col.newNote();
         note.setItem("Front","one");
         note.setItem("Back","two");
         col.addNote(note);
         // set the card up as a review card, due 8 days ago
         Card c = note.cards().get(0);
         c.type = CARD_TYPE_REV;
         c.queue = QUEUE_TYPE_REV;
         c.due = col.getSched().today - 8;
         c.factor = STARTING_FACTOR;
         c.reps = 3;
         c.lapses = 1;
         c.ivl = 100;
         c.startTimer();
         c.flush();
         // save it for later use as well
         cardcopy = copy.copy(c);
         // failing it should put it in the learn queue with the default options
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         // different delay to new
         col.reset();
         conf = col.getSched()._cardConf(c);
         conf["lapse"].put("delays", [2, 20]);
         col.getDecks().save(conf);
         col.getSched().answerCard(c, 1);
         assertEquals( QUEUE_TYPE_LRN, c.queue );
         // it should be due tomorrow, with an interval of 1
         assertEquals( col.getSched(, c.odue ).today + 1);
         assertEquals( 1, c.ivl );
         // but because it's in the learn queue, its current due time should be in
         // the future
         assertTrue(c.due >= time.time());
         assertTrue((c.due - time.time()) > 118);
         // factor should have been decremented
         assertEquals( 2300, c.factor );
         // check counters
         assertEquals( 2, c.lapses );
         assertEquals( 4, c.reps );
         // check ests.
         ni = col.getSched().nextIvl;
         assertEquals( 120, ni(c, 1) );
         assertEquals( 20 * 60, ni(c, 2) );
         // try again with an ease of 2 instead
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         Card c = copy.copy(cardcopy);
         c.flush();
         col.getSched().answerCard(c, 2);
         assertEquals( QUEUE_TYPE_REV, c.queue );
         // the new interval should be (100 + 8/4) * 1.2 = 122
         assertTrue(checkRevIvl(col, c, 122));
         assertEquals( col.getSched(, c.due ).today + c.ivl);
         // factor should have been decremented
         assertEquals( 2350, c.factor );
         // check counters
         assertEquals( 1, c.lapses );
         assertEquals( 4, c.reps );
         // ease 3
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         Card c = copy.copy(cardcopy);
         c.flush();
         col.getSched().answerCard(c, 3);
         // the new interval should be (100 + 8/2) * 2.5 = 260
         assertTrue(checkRevIvl(col, c, 260));
         assertEquals( col.getSched(, c.due ).today + c.ivl);
         // factor should have been left alone
         assertEquals( STARTING_FACTOR, c.factor );
         // ease 4
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         Card c = copy.copy(cardcopy);
         c.flush();
         col.getSched().answerCard(c, 4);
         // the new interval should be (100 + 8) * 2.5 * 1.3 = 351
         assertTrue(checkRevIvl(col, c, 351));
         assertEquals( col.getSched(, c.due ).today + c.ivl);
         // factor should have been increased
         assertEquals( 2650, c.factor );
     }

     @Test
     public void test_button_spacing(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         // 1 day ivl review card due now
         Card c = note.cards().get(0);
         c.type = CARD_TYPE_REV;
         c.queue = QUEUE_TYPE_REV;
         c.due = col.getSched().today;
         c.reps = 1;
         c.ivl = 1;
         c.startTimer();
         c.flush();
         col.reset();
         ni = col.getSched().nextIvlStr;
         wo = without_unicode_isolation;
         assertEquals( "2d", wo(ni(c, 2)) );
         assertEquals( "3d", wo(ni(c, 3)) );
         assertEquals( "4d", wo(ni(c, 4)) );
     }

     @Test
     public void test_overdue_lapse(){
         // disabled in commit 3069729776990980f34c25be66410e947e9d51a2
         return;
         Collection col = getCol()  // pylint: disable=unreachable
         // add a note
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         // simulate a review that was lapsed and is now due for its normal review
         Card c = note.cards().get(0);
         c.type = CARD_TYPE_REV;
         c.queue = 1;
         c.due = -1;
         c.odue = -1;
         c.factor = STARTING_FACTOR;
         c.left = 2002;
         c.ivl = 0;
         c.flush();
         col.getSched()._clearOverdue = false;
         // checkpoint
         col.save();
         col.getSched().reset();
         assertEquals( (0, 2, 0, col.getSched().counts() ));
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 3);
         // it should be due tomorrow
         assertEquals( col.getSched(, c.due ).today + 1);
         // revert to before
         col.rollback();
         col.getSched()._clearOverdue = true;
         // with the default settings, the overdue card should be removed from the
         // learning queue
         col.getSched().reset();
         assertEquals( (0, 0, 1, col.getSched().counts() ));
     }

     @Test
     public void test_finished(){
         Collection col = getCol();
         // nothing due
         assertTrue("Congratulations" in col.getSched().finishedMsg());
         assertTrue("limit" not in col.getSched().finishedMsg());
         Note note = col.newNote();
         note.setItem("Front","one");
         note.setItem("Back","two");
         col.addNote(note);
         // have a new card
         assertTrue("new cards available" in col.getSched().finishedMsg());
         // turn it into a review
         col.reset();
         Card c = note.cards().get(0);
         c.startTimer();
         col.getSched().answerCard(c, 3);
         // nothing should be due tomorrow, as it's due in a week
         assertTrue("Congratulations" in col.getSched().finishedMsg());
         assertTrue("limit" not in col.getSched().finishedMsg());
     }

     @Test
     public void test_nextIvl(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         note.setItem("Back","two");
         col.addNote(note);
         col.reset();
         conf = col.getDecks().confForDid(1);
         conf["new"].put("delays", [0.5, 3, 10]);
         conf["lapse"].put("delays", [1, 5, 9]);
         col.getDecks().save(conf);
         Card c = col.getSched().getCard();
         // new cards
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         ni = col.getSched().nextIvl;
         assertEquals( 30, ni(c, 1) );
         assertEquals( 180, ni(c, 2) );
         assertEquals( 4 * 86400, ni(c, 3) );
         col.getSched().answerCard(c, 1);
         // cards in learning
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         assertEquals( 30, ni(c, 1) );
         assertEquals( 180, ni(c, 2) );
         assertEquals( 4 * 86400, ni(c, 3) );
         col.getSched().answerCard(c, 2);
         assertEquals( 30, ni(c, 1) );
         assertEquals( 600, ni(c, 2) );
         assertEquals( 4 * 86400, ni(c, 3) );
         col.getSched().answerCard(c, 2);
         // normal graduation is tomorrow
         assertEquals( 1 * 86400, ni(c, 2) );
         assertEquals( 4 * 86400, ni(c, 3) );
         // lapsed cards
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         c.type = CARD_TYPE_REV;
         c.ivl = 100;
         c.factor = STARTING_FACTOR;
         assertEquals( 60, ni(c, 1) );
         assertEquals( 100 * 86400, ni(c, 2) );
         assertEquals( 100 * 86400, ni(c, 3) );
         // review cards
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         c.queue = QUEUE_TYPE_REV;
         c.ivl = 100;
         c.factor = STARTING_FACTOR;
         // failing it should put it at 60s
         assertEquals( 60, ni(c, 1) );
         // or 1 day if relearn is false
         conf["lapse"].put("delays", []);
         col.getDecks().save(conf);
         assertEquals( 1 * 86400, ni(c, 1) );
         // (* 100 1.2 86400)10368000.0
         assertEquals( 10368000, ni(c, 2) );
         // (* 100 2.5 86400)21600000.0
         assertEquals( 21600000, ni(c, 3) );
         // (* 100 2.5 1.3 86400)28080000.0
         assertEquals( 28080000, ni(c, 4) );
         assertEquals( "10.8mo", without_unicode_isolation(col.getSched().nextIvlStr(c, 4)) );
     }

     @Test
     public void test_misc(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         Card c = note.cards().get(0);
         // burying
         col.getSched().buryNote(c.nid);
         col.reset();
         assertFalse(col.getSched().getCard());
         col.getSched().unburyCards();
         col.reset();
         assertTrue(col.getSched().getCard());
     }

     @Test
     public void test_suspend(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         Card c = note.cards().get(0);
         // suspending
         col.reset();
         assertTrue(col.getSched().getCard());
         col.getSched().suspendCards([c.getId()]);
         col.reset();
         assertFalse(col.getSched().getCard());
         // unsuspending
         col.getSched().unsuspendCards([c.getId()]);
         col.reset();
         assertTrue(col.getSched().getCard());
         // should cope with rev cards being relearnt
         c.due = 0;
         c.ivl = 100;
         c.type = CARD_TYPE_REV;
         c.queue = QUEUE_TYPE_REV;
         c.flush();
         col.reset();
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 1);
         assertTrue(c.due >= time.time());
         assertEquals( QUEUE_TYPE_LRN, c.queue );
         assertEquals( CARD_TYPE_REV, c.type );
         col.getSched().suspendCards([c.getId()]);
         col.getSched().unsuspendCards([c.getId()]);
         c.load();
         assertEquals( QUEUE_TYPE_REV, c.queue );
         assertEquals( CARD_TYPE_REV, c.type );
         assertEquals( 1, c.due );
         // should cope with cards in cram decks
         c.due = 1;
         c.flush();
         cram = col.getDecks().newDyn("tmp");
         col.getSched().rebuildDyn();
         c.load();
         assertNotEquals( 1, c.due );
         assertNotEquals( 1, c.getDid() );
         col.getSched().suspendCards([c.getId()]);
         c.load();
         assertEquals( 1, c.due );
         assertEquals( 1, c.long did );
     }

     @Test
     public void test_cram(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         Card c = note.cards().get(0);
         c.ivl = 100;
         c.queue = CARD_TYPE_REV;
         c.type = QUEUE_TYPE_REV;
         // due in 25 days, so it's been waiting 75 days
         c.due = col.getSched().today + 25;
         c.setMod(1);
         c.factor = STARTING_FACTOR;
         c.startTimer();
         c.flush();
         col.reset();
         assertEquals( (0, 0, 0, col.getSched().counts() ));
         cardcopy = copy.copy(c);
         // create a dynamic deck and refresh it
         long did = col.getDecks().newDyn("Cram");
         col.getSched().rebuildDyn(did);
         col.reset();
         // should appear as new in the deck list
         assertEquals( 1, sorted(col.getSched().deck_due_tree().children)[0].new_count );
         // and should appear in the counts
         assertEquals( (1, 0, 0, col.getSched().counts() ));
         // grab it and check estimates
         Card c = col.getSched().getCard();
         assertEquals( 2, col.getSched().answerButtons(c) );
         assertEquals( 600, col.getSched().nextIvl(c, 1) );
         assertEquals( 138 * 60 * 60 * 24, col.getSched().nextIvl(c, 2) );
         cram = col.getDecks().get(did);
         cram.put("delays", [1, 10]);
         col.getDecks().save(cram);
         assertEquals( 3, col.getSched().answerButtons(c) );
         assertEquals( 60, col.getSched().nextIvl(c, 1) );
         assertEquals( 600, col.getSched().nextIvl(c, 2) );
         assertEquals( 138 * 60 * 60 * 24, col.getSched().nextIvl(c, 3) );
         col.getSched().answerCard(c, 2);
         // elapsed time was 75 days
         // factor = 2.5+1.2/2 = 1.85
         // int(75*1.85) = 138
         assertEquals( 138, c.ivl );
         assertEquals( 138, c.odue );
         assertEquals( QUEUE_TYPE_LRN, c.queue );
         // should be logged as a cram rep
         assertEquals( 3, col.getDb().scalar("select type from revlog order by id desc limit 1") );
         // check ivls again
         assertEquals( 60, col.getSched().nextIvl(c, 1) );
         assertEquals( 138 * 60 * 60 * 24, col.getSched().nextIvl(c, 2) );
         assertEquals( 138 * 60 * 60 * 24, col.getSched().nextIvl(c, 3) );
         // when it graduates, due is updated
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 2);
         assertEquals( 138, c.ivl );
         assertEquals( 138, c.due );
         assertEquals( QUEUE_TYPE_REV, c.queue );
         // and it will have moved back to the previous deck
         assertEquals( 1, c.long did );
         // cram the deck again
         col.getSched().rebuildDyn(did);
         col.reset();
         Card c = col.getSched().getCard();
         // check ivls again - passing should be idempotent
         assertEquals( 60, col.getSched().nextIvl(c, 1) );
         assertEquals( 600, col.getSched().nextIvl(c, 2) );
         assertEquals( 138 * 60 * 60 * 24, col.getSched().nextIvl(c, 3) );
         col.getSched().answerCard(c, 2);
         assertEquals( 138, c.ivl );
         assertEquals( 138, c.odue );
         // fail
         col.getSched().answerCard(c, 1);
         assertEquals( 60, col.getSched().nextIvl(c, 1) );
         assertEquals( 600, col.getSched().nextIvl(c, 2) );
         assertEquals( 86400, col.getSched().nextIvl(c, 3) );
         // delete the deck, returning the card mid-study
         col.getDecks().rem(col.getDecks().selected());
         assertEquals( 1, col.getSched().deck_due_tree().children.size() );
         c.load();
         assertEquals( 1, c.ivl );
         assertEquals( col.getSched(, c.due ).today + 1);
         // make it due
         col.reset();
         assertEquals( (0, 0, 0, col.getSched().counts() ));
         c.due = -5;
         c.ivl = 100;
         c.flush();
         col.reset();
         assertEquals( (0, 0, 1, col.getSched().counts() ));
         // cram again
         long did = col.getDecks().newDyn("Cram");
         col.getSched().rebuildDyn(did);
         col.reset();
         assertEquals( (0, 0, 1, col.getSched().counts() ));
         c.load();
         assertEquals( 4, col.getSched().answerButtons(c) );
         // add a sibling so we can test minSpace, etc
         c.Collection col = null;
         c2 = copy.deepcopy(c);
         c2.Collection col = c.Collection col = col;
         c2.getId() = 0;
         c2.ord = 1;
         c2.due = 325;
         c2.Collection col = c.col;
         c2.flush();
         // should be able to answer it
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 4);
         // it should have been moved back to the original deck
         assertEquals( 1, c.long did );
     }

     @Test
     public void test_cram_rem(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         oldDue = note.cards().get(0).due;
         long did = col.getDecks().newDyn("Cram");
         col.getSched().rebuildDyn(did);
         col.reset();
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 2);
         // answering the card will put it in the learning queue
         assertEquals( CARD_TYPE_LRN and c.queue == QUEUE_TYPE_LRN, c.type );
         assertNotEquals(c.due, oldDue);
         // if we terminate cramming prematurely it should be set back to new
         col.getSched().emptyDyn(did);
         c.load();
         assertEquals( CARD_TYPE_NEW and c.queue == QUEUE_TYPE_NEW, c.type );
         assertEquals( oldDue, c.due );
     }

     @Test
     public void test_cram_resched(){
         // add card
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         // cram deck
         long did = col.getDecks().newDyn("Cram");
         cram = col.getDecks().get(did);
         cram.put("resched", false);
         col.getDecks().save(cram);
         col.getSched().rebuildDyn(did);
         col.reset();
         // graduate should return it to new
         Card c = col.getSched().getCard();
         ni = col.getSched().nextIvl;
         assertEquals( 60, ni(c, 1) );
         assertEquals( 600, ni(c, 2) );
         assertEquals( 0, ni(c, 3) );
         assertEquals( "(end, col.getSched().nextIvlStr(c, 3) )");
         col.getSched().answerCard(c, 3);
         assertEquals( CARD_TYPE_NEW and c.queue == QUEUE_TYPE_NEW, c.type );
         // undue reviews should also be unaffected
         c.ivl = 100;
         c.queue = CARD_TYPE_REV;
         c.type = QUEUE_TYPE_REV;
         c.due = col.getSched().today + 25;
         c.factor = STARTING_FACTOR;
         c.flush();
         cardcopy = copy.copy(c);
         col.getSched().rebuildDyn(did);
         col.reset();
         Card c = col.getSched().getCard();
         assertEquals( 600, ni(c, 1) );
         assertEquals( 0, ni(c, 2) );
         assertEquals( 0, ni(c, 3) );
         col.getSched().answerCard(c, 2);
         assertEquals( 100, c.ivl );
         assertEquals( col.getSched(, c.due ).today + 25);
         // check failure too
         Card c = cardcopy;
         c.flush();
         col.getSched().rebuildDyn(did);
         col.reset();
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 1);
         col.getSched().emptyDyn(did);
         c.load();
         assertEquals( 100, c.ivl );
         assertEquals( col.getSched(, c.due ).today + 25);
         // fail+grad early
         Card c = cardcopy;
         c.flush();
         col.getSched().rebuildDyn(did);
         col.reset();
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 1);
         col.getSched().answerCard(c, 3);
         col.getSched().emptyDyn(did);
         c.load();
         assertEquals( 100, c.ivl );
         assertEquals( col.getSched(, c.due ).today + 25);
         // due cards - pass
         Card c = cardcopy;
         c.due = -25;
         c.flush();
         col.getSched().rebuildDyn(did);
         col.reset();
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 3);
         col.getSched().emptyDyn(did);
         c.load();
         assertEquals( 100, c.ivl );
         assertEquals( -25, c.due );
         // fail
         Card c = cardcopy;
         c.due = -25;
         c.flush();
         col.getSched().rebuildDyn(did);
         col.reset();
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 1);
         col.getSched().emptyDyn(did);
         c.load();
         assertEquals( 100, c.ivl );
         assertEquals( -25, c.due );
         // fail with normal grad
         Card c = cardcopy;
         c.due = -25;
         c.flush();
         col.getSched().rebuildDyn(did);
         col.reset();
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 1);
         col.getSched().answerCard(c, 3);
         c.load();
         assertEquals( 100, c.ivl );
         assertEquals( -25, c.due );
         // lapsed card pulled into cram
         // col.getSched()._cardConf(c)['lapse']['mult']=0.5
         // col.getSched().answerCard(c, 1)
         // col.getSched().rebuildDyn(did)
         // col.reset()
         // Card c = col.getSched().getCard()
         // col.getSched().answerCard(c, 2)
         // print c.__dict__
     }

     @Test
     public void test_ordcycle(){
         Collection col = getCol();
         // add two more templates and set second active
         Model m = col.getModels().current();
         Models mm = col.getModels();
         JSONObject t = mm.newTemplate("Reverse");
         t.put("qfmt", "{{Back}}");
         t.put("afmt", "{{Front}}");
         mm.addTemplateModChanged(m, t);
         JSONObject t = mm.newTemplate("f2");
         t.put("qfmt", "{{Front}}");
         t.put("afmt", "{{Back}}");
         mm.addTemplateModChanged(m, t);
         mm.save(m);
         // create a new note; it should have 3 cards
         Note note = col.newNote();
         note.setItem("Front","1");
         note.setItem("Back","1");
         col.addNote(note);
         assertEquals( 3, col.cardCount() );
         col.reset();
         // ordinals should arrive in order
         assertEquals( 0, col.getSched().getCard().ord );
         assertEquals( 1, col.getSched().getCard().ord );
         assertEquals( 2, col.getSched().getCard().ord );
     }

     @Test
     public void test_counts_idx(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         note.setItem("Back","two");
         col.addNote(note);
         col.reset();
         assertEquals( (1, 0, 0, col.getSched().counts() ));
         Card c = col.getSched().getCard();
         // counter's been decremented but idx indicates 1
         assertEquals( (0, 0, 0, col.getSched().counts() ));
         assertEquals( 0, col.getSched().countIdx(c) );
         // answer to move to learn queue
         col.getSched().answerCard(c, 1);
         assertEquals( (0, 2, 0, col.getSched().counts() ));
         // fetching again will decrement the count
         Card c = col.getSched().getCard();
         assertEquals( (0, 0, 0, col.getSched().counts() ));
         assertEquals( 1, col.getSched().countIdx(c) );
         // answering should add it back again
         col.getSched().answerCard(c, 1);
         assertEquals( (0, 2, 0, col.getSched().counts() ));
     }

     @Test
     public void test_repCounts(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         col.reset();
         // lrnReps should be accurate on pass/fail
         assertEquals( (1, 0, 0, col.getSched().counts() ));
         col.getSched().answerCard(col.getSched().getCard(), 1);
         assertEquals( (0, 2, 0, col.getSched().counts() ));
         col.getSched().answerCard(col.getSched().getCard(), 1);
         assertEquals( (0, 2, 0, col.getSched().counts() ));
         col.getSched().answerCard(col.getSched().getCard(), 2);
         assertEquals( (0, 1, 0, col.getSched().counts() ));
         col.getSched().answerCard(col.getSched().getCard(), 1);
         assertEquals( (0, 2, 0, col.getSched().counts() ));
         col.getSched().answerCard(col.getSched().getCard(), 2);
         assertEquals( (0, 1, 0, col.getSched().counts() ));
         col.getSched().answerCard(col.getSched().getCard(), 2);
         assertEquals( (0, 0, 0, col.getSched().counts() ));
         Note note = col.newNote();
         note.setItem("Front","two");
         col.addNote(note);
         col.reset();
         // initial pass should be correct too
         col.getSched().answerCard(col.getSched().getCard(), 2);
         assertEquals( (0, 1, 0, col.getSched().counts() ));
         col.getSched().answerCard(col.getSched().getCard(), 1);
         assertEquals( (0, 2, 0, col.getSched().counts() ));
         col.getSched().answerCard(col.getSched().getCard(), 3);
         assertEquals( (0, 0, 0, col.getSched().counts() ));
         // immediate graduate should work
         Note note = col.newNote();
         note.setItem("Front","three");
         col.addNote(note);
         col.reset();
         col.getSched().answerCard(col.getSched().getCard(), 3);
         assertEquals( (0, 0, 0, col.getSched().counts() ));
         // and failing a review should too
         Note note = col.newNote();
         note.setItem("Front","three");
         col.addNote(note);
         Card c = note.cards().get(0);
         c.type = CARD_TYPE_REV;
         c.queue = QUEUE_TYPE_REV;
         c.due = col.getSched().today;
         c.flush();
         col.reset();
         assertEquals( (0, 0, 1, col.getSched().counts() ));
         col.getSched().answerCard(col.getSched().getCard(), 1);
         assertEquals( (0, 1, 0, col.getSched().counts() ));
     }

     @Test
     public void test_timing(){
         Collection col = getCol();
         // add a few review cards, due today
         for (int i=0; i < 5; i++) {
             Note note = col.newNote();
         }
         note.setItem("Front","num") + str(i);
             col.addNote(note);
             Card c = note.cards().get(0);
             c.type = CARD_TYPE_REV;
             c.queue = QUEUE_TYPE_REV;
             c.due = 0;
             c.flush();
         // fail the first one
         col.reset();
         Card c = col.getSched().getCard();
         // set a a fail delay of 4 seconds
         conf = col.getSched()._cardConf(c);
         conf["lapse"]["delays"][0] = 1 / 15.0;
         col.getDecks().save(conf);
         col.getSched().answerCard(c, 1);
         // the next card should be another review
         Card c = col.getSched().getCard();
         assertEquals( QUEUE_TYPE_REV, c.queue );
         // but if we wait for a few seconds, the failed card should come back
         orig_time = time.time;

         def adjusted_time():
             return orig_time() + 5;

         time.time = adjusted_time;
         Card c = col.getSched().getCard();
         assertEquals( QUEUE_TYPE_LRN, c.queue );
         time.time = orig_time;
     }

     @Test
     public void test_collapse(){
         Collection col = getCol();
         // add a note
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         col.reset();
         // test collapsing
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 1);
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 3);
         assertFalse(col.getSched().getCard());
     }

     @Test
     public void test_deckDue(){
         Collection col = getCol();
         // add a note with default deck
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         // and one that's a child
         Note note = col.newNote();
         note.setItem("Front","two");
         default1 = note.model().put("did", col.getDecks().id("Default::1"));
         col.addNote(note);
         // make it a review card
         Card c = note.cards().get(0);
         c.queue = QUEUE_TYPE_REV;
         c.due = 0;
         c.flush();
         // add one more with a new deck
         Note note = col.newNote();
         note.setItem("Front","two");
         foobar = note.model().put("did", col.getDecks().id("foo::bar"));
         col.addNote(note);
         // and one that's a sibling
         Note note = col.newNote();
         note.setItem("Front","three");
         foobaz = note.model().put("did", col.getDecks().id("foo::baz"));
         col.addNote(note);
         col.reset();
         assertEquals( 5, col.getDecks().all_names_and_ids().size() );
         tree = col.getSched().deck_due_tree().children;
         assertEquals( "Default", tree[0].name );
         // sum of child and parent
         assertEquals( 1, tree[0].deck_id );
         assertEquals( 1, tree[0].review_count );
         assertEquals( 1, tree[0].new_count );
         // child count is just review
         child = tree[0].children[0];
         assertEquals( "1", child.name );
         assertEquals( default1, child.deck_id );
         assertEquals( 1, child.review_count );
         assertEquals( 0, child.new_count );
         // code should not fail if a card has an invalid deck
         c.long did = 12345;
         c.flush();
         col.getSched().deck_due_tree();
     }

     @Test
     public void test_deckFlow(){
         Collection col = getCol();
         // add a note with default deck
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         // and one that's a child
         Note note = col.newNote();
         note.setItem("Front","two");
         default1 = note.model().put("did", col.getDecks().id("Default::2"));
         col.addNote(note);
         // and another that's higher up
         Note note = col.newNote();
         note.setItem("Front","three");
         default1 = note.model().put("did", col.getDecks().id("Default::1"));
         col.addNote(note);
         // should get top level one first, then ::1, then ::2
         col.reset();
         assertEquals( (3, 0, 0, col.getSched().counts() ));
         for (String i: "one", "three", "two") {
             Card c = col.getSched().getCard();
         }
         assertTrue(c.note().put("Front",= i));
             col.getSched().answerCard(c, 2);
     }

     @Test
     public void test_reorder(){
         Collection col = getCol();
         // add a note with default deck
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         Note note2 = col.newNote();
         note2.setItem("Front","two");
         col.addNote(note2);
         assertEquals( 2, note2.cards().get(0).due );
         found = false;
         // 50/50 chance of being reordered
         for (int i=0; i < 2; i++0) {
             col.getSched().randomizeCards(1);
             if (note.cards().get(0).due != note.getId()) {
                 found = true;
                 break;
             }
         }
         assertTrue(found);
         col.getSched().orderCards(1);
         assertEquals( 1, note.cards().get(0).due );
         // shifting
         note3 = col.newNote();
         note3.setItem("Front","three");
         col.addNote(note3);
         note4 = col.newNote();
         note4.setItem("Front","four");
         col.addNote(note4);
         assertEquals( 1, note.cards().get(0).due );
         assertEquals( 2, note2.cards().get(0).due );
         assertEquals( 3, note3.cards().get(0).due );
         assertEquals( 4, note4.cards().get(0).due );
         col.getSched().sortCards([note3.cards().get(0).getId(), note4.cards().get(0).getId()], start=1, shift=true);
         assertEquals( 3, note.cards().get(0).due );
         assertEquals( 4, note2.cards().get(0).due );
         assertEquals( 1, note3.cards().get(0).due );
         assertEquals( 2, note4.cards().get(0).due );
     }

     @Test
     public void test_forget(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         Card c = note.cards().get(0);
         c.queue = QUEUE_TYPE_REV;
         c.type = CARD_TYPE_REV;
         c.ivl = 100;
         c.due = 0;
         c.flush();
         col.reset();
         assertEquals( (0, 0, 1, col.getSched().counts() ));
         col.getSched().forgetCards([c.getId()]);
         col.reset();
         assertEquals( (1, 0, 0, col.getSched().counts() ));
     }

     @Test
     public void test_resched(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         Card c = note.cards().get(0);
         col.getSched().reschedCards([c.getId()], 0, 0);
         c.load();
         assertEquals( col.getSched(, c.due ).today);
         assertEquals( 1, c.ivl );
         assertEquals( CARD_TYPE_REV and c.type == QUEUE_TYPE_REV, c.queue );
         col.getSched().reschedCards([c.getId()], 1, 1);
         c.load();
         assertEquals( col.getSched(, c.due ).today + 1);
         assertEquals( +1, c.ivl );
     }

     @Test
     public void test_norelearn(){
         Collection col = getCol();
         // add a note
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         Card c = note.cards().get(0);
         c.type = CARD_TYPE_REV;
         c.queue = QUEUE_TYPE_REV;
         c.due = 0;
         c.factor = STARTING_FACTOR;
         c.reps = 3;
         c.lapses = 1;
         c.ivl = 100;
         c.startTimer();
         c.flush();
         col.reset();
         col.getSched().answerCard(c, 1);
         col.getSched()._cardConf(c)["lapse"].put("delays", []);
         col.getSched().answerCard(c, 1);
     }

     @Test
     public void test_failmult(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         note.setItem("Back","two");
         col.addNote(note);
         Card c = note.cards().get(0);
         c.type = CARD_TYPE_REV;
         c.queue = QUEUE_TYPE_REV;
         c.ivl = 100;
         c.due = col.getSched().today - c.ivl;
         c.factor = STARTING_FACTOR;
         c.reps = 3;
         c.lapses = 1;
         c.startTimer();
         c.flush();
         conf = col.getSched()._cardConf(c);
         conf["lapse"].put("mult", 0.5);
         col.getDecks().save(conf);
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 1);
         assertEquals( 50, c.ivl );
         col.getSched().answerCard(c, 1);
         assertEquals( 25, c.ivl );
     }
     /*****************
          ** SchedV2      *
      *****************/
     private Collection getCol(){
         Collection col = getColOrig();
         col.changeSchedulerVer(2);
         return col;
     }

     @Test
     public void test_clock(){
         Collection col = getCol();
         if ((col.getSched().dayCutoff - intTime()) < 10 * 60) {
             raise Exception("Unit tests will fail around the day rollover.");
         }


     private boolean checkRevIvl(col, c, targetIvl):
         min, max = col.getSched()._fuzzIvlRange(targetIvl);
         return min <= c.ivl <= max;
     }

     @Test
     public void test_basics(){
         Collection col = getCol();
         col.reset();
         assertFalse(col.getSched().getCard());
     }

     @Test
     public void test_new(){
         Collection col = getCol();
         col.reset();
         assertEquals( 0, col.getSched().newCount );
         // add a note
         Note note = col.newNote();
         note.setItem("Front","one");
         note.setItem("Back","two");
         col.addNote(note);
         col.reset();
         assertEquals( 1, col.getSched().newCount );
         // fetch it
         Card c = col.getSched().getCard();
         assertTrue(c);
         assertEquals( QUEUE_TYPE_NEW, c.queue );
         assertEquals( CARD_TYPE_NEW, c.type );
         // if we answer it, it should become a learn card
         JSONObject t = intTime();
         col.getSched().answerCard(c, 1);
         assertEquals( QUEUE_TYPE_LRN, c.queue );
         assertEquals( CARD_TYPE_LRN, c.type );
         assertTrue(c.due >= t);

         // disabled for now, as the learn fudging makes this randomly fail
         // // the default order should ensure siblings are not seen together, and
         // // should show all cards
         // Model m = col.getModels().current(); Models mm = col.getModels()
         // JSONObject t = mm.newTemplate("Reverse")
         // t['qfmt'] = "{{Back}}"
         // t['afmt'] = "{{Front}}"
         // mm.addTemplateModChanged(m, t)
         // mm.save(m)
         // Note note = col.newNote()
         // note['Front'] = u"2"; note['Back'] = u"2"
         // col.addNote(note)
         // Note note = col.newNote()
         // note['Front'] = u"3"; note['Back'] = u"3"
         // col.addNote(note)
         // col.reset()
         // qs = ("2", "3", "2", "3")
         // for (int n = 0; n < 4; n++) {
         //     Card c = col.getSched().getCard()
         //     assertTrue(qs[n] in c.q())
         //     col.getSched().answerCard(c, 2)
         // }
     }

     @Test
     public void test_newLimits(){
         Collection col = getCol();
         // add some notes
         deck2 = col.getDecks().id("Default::foo");
         for (int i=0; i < 30; i++) {
             Note note = col.newNote();
         }
         note.setItem("Front","did")] = deck2;
             col.addNote(note);
         // give the child deck a different configuration
         c2 = col.getDecks().add_config_returning_id("new conf");
         col.getDecks().setConf(col.getDecks().get(deck2), c2);
         col.reset();
         // both confs have defaulted to a limit of 20
         assertEquals( 20, col.getSched().newCount );
         // first card we get comes from parent
         Card c = col.getSched().getCard();
         assertEquals( 1, c.long did );
         // limit the parent to 10 cards, meaning we get 10 in total
         conf1 = col.getDecks().confForDid(1);
         conf1["new"].put("perDay", 10);
         col.getDecks().save(conf1);
         col.reset();
         assertEquals( 10, col.getSched().newCount );
         // if we limit child to 4, we should get 9
         conf2 = col.getDecks().confForDid(deck2);
         conf2["new"].put("perDay", 4);
         col.getDecks().save(conf2);
         col.reset();
         assertEquals( 9, col.getSched().newCount );
     }

     @Test
     public void test_newBoxes(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         col.reset();
         Card c = col.getSched().getCard();
         conf = col.getSched()._cardConf(c);
         conf["new"].put("delays", [1, 2, 3, 4, 5]);
         col.getDecks().save(conf);
         col.getSched().answerCard(c, 2);
         // should handle gracefully
         conf["new"].put("delays", [1]);
         col.getDecks().save(conf);
         col.getSched().answerCard(c, 2);
     }

     @Test
     public void test_learn(){
         Collection col = getCol();
         // add a note
         Note note = col.newNote();
         note.setItem("Front","one");
         note.setItem("Back","two");
         Note note = col.addNote(note);
         // set as a learn card and rebuild queues
         col.getDb().execute("update cards set queue=0, type=0");
         col.reset();
         // sched.getCard should return it, since it's due in the past
         Card c = col.getSched().getCard();
         assertTrue(c);
         conf = col.getSched()._cardConf(c);
         conf["new"].put("delays", [0.5, 3, 10]);
         col.getDecks().save(conf);
         // fail it
         col.getSched().answerCard(c, 1);
         // it should have three reps left to graduation
         assertEquals( 3, c.left % 1000 );
         assertEquals( , c.left // 1000 )3
         // it should by due in 30 seconds
         JSONObject t = round(c.due - time.time());
                assertTrue(t >= 25 and t <= 40);
         // pass it once
         col.getSched().answerCard(c, 3);
         // it should by due in 3 minutes
         dueIn = c.due - time.time();
                assertTrue(178 <= dueIn <= 180 * 1.25);
                      assertEquals( 2, c.left % 1000 );
                      assertEquals( , c.left // 1000 )2
         // check log is accurate
         log = col.getDb().first("select * from revlog order by id desc");
                                   assertEquals( 3, log[3] );
                                   assertEquals( -180, log[4] );
                                   assertEquals( -30, log[5] );
         // pass again
         col.getSched().answerCard(c, 3);
         // it should by due in 10 minutes
         dueIn = c.due - time.time();
                       assertTrue(599 <= dueIn <= 600 * 1.25);
                                   assertEquals( 1, c.left % 1000 );
                                   assertEquals( , c.left // 1000 )1
         // the next pass should graduate the card
                                                assertEquals( QUEUE_TYPE_LRN, c.queue );
                                                assertEquals( CARD_TYPE_LRN, c.type );
         col.getSched().answerCard(c, 3);
                                                assertEquals( QUEUE_TYPE_REV, c.queue );
                                                assertEquals( CARD_TYPE_REV, c.type );
         // should be due tomorrow, with an interval of 1
                                                assertEquals( col.getSched(, c.due ).today + 1);
                                                assertEquals( 1, c.ivl );
         // or normal removal
         c.type = 0;
         c.queue = 1;
         col.getSched().answerCard(c, 4);
                                                assertEquals( CARD_TYPE_REV, c.type );
                                                assertEquals( QUEUE_TYPE_REV, c.queue );
                              assertTrue(checkRevIvl(col, c, 4));
         // revlog should have been updated each time
                                                assertEquals( 5, col.getDb().queryScalar("select count() from revlog where type = 0") );
     }

     @Test
     public void test_relearn(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         Card c = note.cards().get(0);
         c.ivl = 100;
         c.due = col.getSched().today;
         c.queue = CARD_TYPE_REV;
         c.type = QUEUE_TYPE_REV;
         c.flush();

         // fail the card
         col.reset();
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 1);
         assertEquals( QUEUE_TYPE_LRN, c.queue );
         assertEquals( CARD_TYPE_RELEARNING, c.type );
         assertEquals( 1, c.ivl );

         // immediately graduate it
         col.getSched().answerCard(c, 4);
         assertEquals( CARD_TYPE_REV and c.type == QUEUE_TYPE_REV, c.queue );
         assertEquals( 2, c.ivl );
         assertEquals( col.getSched(, c.due ).today + c.ivl);
     }

     @Test
     public void test_relearn_no_steps(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         Card c = note.cards().get(0);
         c.ivl = 100;
         c.due = col.getSched().today;
         c.queue = CARD_TYPE_REV;
         c.type = QUEUE_TYPE_REV;
         c.flush();

         conf = col.getDecks().confForDid(1);
         conf["lapse"].put("delays", []);
         col.getDecks().save(conf);

         // fail the card
         col.reset();
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 1);
         assertEquals( CARD_TYPE_REV and c.type == QUEUE_TYPE_REV, c.queue );
     }

     @Test
     public void test_learn_collapsed(){
         Collection col = getCol();
         // add 2 notes
         Note note = col.newNote();
         note.setItem("Front","1");
         Note note = col.addNote(note);
         Note note = col.newNote();
         note.setItem("Front","2");
         Note note = col.addNote(note);
         // set as a learn card and rebuild queues
         col.getDb().execute("update cards set queue=0, type=0");
         col.reset();
         // should get '1' first
         Card c = col.getSched().getCard();
         assertTrue(c.q().endswith("1"));
         // pass it so it's due in 10 minutes
         col.getSched().answerCard(c, 3);
         // get the other card
         Card c = col.getSched().getCard();
         assertTrue(c.q().endswith("2"));
         // fail it so it's due in 1 minute
         col.getSched().answerCard(c, 1);
         // we shouldn't get the same card again
         Card c = col.getSched().getCard();
         assertFalse(c.q().endswith("2"));
     }

     @Test
     public void test_learn_day(){
         Collection col = getCol();
         // add a note
         Note note = col.newNote();
         note.setItem("Front","one");
         Note note = col.addNote(note);
         col.getSched().reset();
         Card c = col.getSched().getCard();
         conf = col.getSched()._cardConf(c);
         conf["new"].put("delays", [1, 10, 1440, 2880]);
         col.getDecks().save(conf);
         // pass it
         col.getSched().answerCard(c, 3);
         // two reps to graduate, 1 more today
         assertEquals( 3, c.left % 1000 );
         assertEquals( , c.left // 1000 )1
                      assertEquals( (0, 1, 0, col.getSched().counts() ));
         Card c = col.getSched().getCard();
         ni = col.getSched().nextIvl;
                      assertEquals( 86400, ni(c, 3) );
         // answering it will place it in queue 3
         col.getSched().answerCard(c, 3);
                      assertEquals( col.getSched(, c.due ).today + 1);
                      assertEquals( QUEUE_TYPE_DAY_LEARN_RELEARN, c.queue );
                assertFalse(col.getSched().getCard());
         // for testing, move it back a day
         c.due -= 1;
         c.flush();
         col.reset();
                      assertEquals( (0, 1, 0, col.getSched().counts() ));
         Card c = col.getSched().getCard();
         // nextIvl should work
                      assertEquals( 86400 * 2, ni(c, 3) );
         // if we fail it, it should be back in the correct queue
         col.getSched().answerCard(c, 1);
                      assertEquals( QUEUE_TYPE_LRN, c.queue );
         col.undo();
         col.reset();
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 3);
         // simulate the passing of another two days
         c.due -= 2;
         c.flush();
         col.reset();
         // the last pass should graduate it into a review card
                      assertEquals( 86400, ni(c, 3) );
         col.getSched().answerCard(c, 3);
                      assertEquals( CARD_TYPE_REV and c.type == QUEUE_TYPE_REV, c.queue );
         // if the lapse step is tomorrow, failing it should handle the counts
         // correctly
         c.due = 0;
         c.flush();
         col.reset();
                      assertEquals( (0, 0, 1, col.getSched().counts() ));
         conf = col.getSched()._cardConf(c);
                    conf["lapse"].put("delays", [1440]);
         col.getDecks().save(conf);
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 1);
                      assertEquals( QUEUE_TYPE_DAY_LEARN_RELEARN, c.queue );
                      assertEquals( (0, 0, 0, col.getSched().counts() ));
     }

     @Test
     public void test_reviews(){
         Collection col = getCol();
         // add a note
         Note note = col.newNote();
         note.setItem("Front","one");
         note.setItem("Back","two");
         col.addNote(note);
         // set the card up as a review card, due 8 days ago
         Card c = note.cards().get(0);
         c.type = CARD_TYPE_REV;
         c.queue = QUEUE_TYPE_REV;
         c.due = col.getSched().today - 8;
         c.factor = STARTING_FACTOR;
         c.reps = 3;
         c.lapses = 1;
         c.ivl = 100;
         c.startTimer();
         c.flush();
         // save it for later use as well
         cardcopy = copy.copy(c);
         // try with an ease of 2
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         Card c = copy.copy(cardcopy);
         c.flush();
         col.reset();
         col.getSched().answerCard(c, 2);
         assertEquals( QUEUE_TYPE_REV, c.queue );
         // the new interval should be (100) * 1.2 = 120
         assertTrue(checkRevIvl(col, c, 120));
         assertEquals( col.getSched(, c.due ).today + c.ivl);
         // factor should have been decremented
         assertEquals( 2350, c.factor );
         // check counters
         assertEquals( 1, c.lapses );
         assertEquals( 4, c.reps );
         // ease 3
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         Card c = copy.copy(cardcopy);
         c.flush();
         col.getSched().answerCard(c, 3);
         // the new interval should be (100 + 8/2) * 2.5 = 260
         assertTrue(checkRevIvl(col, c, 260));
         assertEquals( col.getSched(, c.due ).today + c.ivl);
         // factor should have been left alone
         assertEquals( STARTING_FACTOR, c.factor );
         // ease 4
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         Card c = copy.copy(cardcopy);
         c.flush();
         col.getSched().answerCard(c, 4);
         // the new interval should be (100 + 8) * 2.5 * 1.3 = 351
         assertTrue(checkRevIvl(col, c, 351));
         assertEquals( col.getSched(, c.due ).today + c.ivl);
         // factor should have been increased
         assertEquals( 2650, c.factor );
         // leech handling
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         conf = col.getDecks().getConf(1);
         conf["lapse"].put("leechAction", LEECH_SUSPEND);
         col.getDecks().save(conf);
         Card c = copy.copy(cardcopy);
         c.lapses = 7;
         c.flush();
         // steup hook
         hooked = [];

         def onLeech(card):
             hooked.append(1);

         hooks.card_did_leech.append(onLeech);
         col.getSched().answerCard(c, 1);
         assertTrue(hooked);
         assertEquals( QUEUE_TYPE_SUSPENDED, c.queue );
         c.load();
         assertEquals( QUEUE_TYPE_SUSPENDED, c.queue );
     }

     @Test
     public void test_review_limits(){
         Collection col = getCol();

         parent = col.getDecks().get(col.getDecks().id("parent"));
         child = col.getDecks().get(col.getDecks().id("parent::child"));

         pconf = col.getDecks().get_config(col.getDecks().add_config_returning_id("parentConf"));
         cconf = col.getDecks().get_config(col.getDecks().add_config_returning_id("childConf"));

         pconf["rev"].put("perDay", 5);
         col.getDecks().update_config(pconf);
         col.getDecks().setConf(parent, pconf.getLong("id"));
         cconf["rev"].put("perDay", 10);
         col.getDecks().update_config(cconf);
         col.getDecks().setConf(child, cconf.getLong("id"));

         Model m = col.getModels().current();
         m.put("did", child.getLong("id"));
         col.getModels().save(m, false);

         // add some cards
         for (int i=0; i < 20; i++) {
             Note note = col.newNote();
         }
         note.setItem("Front","one");
         note.setItem("Back","two");
             col.addNote(note);

             // make them reviews
             Card c = note.cards().get(0);
             c.queue = CARD_TYPE_REV;
             c.type = QUEUE_TYPE_REV;
             c.due = 0;
             c.flush();

         tree = col.getSched().deck_due_tree().children;
         // (('parent', 1514457677462, 5, 0, 0, (('child', 1514457677463, 5, 0, 0, ()),)))
         assertEquals( 5  // paren, tree[0].review_count )t
                assertEquals( 5  // chil, tree[0].children[0].review_count )d

         // .counts() should match
         col.getDecks().select(child.getLong("id"));
         col.getSched().reset();
                             assertEquals( (0, 0, 5, col.getSched().counts() ));

         // answering a card in the child should decrement parent count
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 3);
                             assertEquals( (0, 0, 4, col.getSched().counts() ));

         tree = col.getSched().deck_due_tree().children;
                       assertEquals( 4  // paren, tree[0].review_count )t
                              assertEquals( 4  // chil, tree[0].children[0].review_count )d
     }

     @Test
     public void test_button_spacing(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         // 1 day ivl review card due now
         Card c = note.cards().get(0);
         c.type = CARD_TYPE_REV;
         c.queue = QUEUE_TYPE_REV;
         c.due = col.getSched().today;
         c.reps = 1;
         c.ivl = 1;
         c.startTimer();
         c.flush();
         col.reset();
         ni = col.getSched().nextIvlStr;
         wo = without_unicode_isolation;
         assertEquals( "2d", wo(ni(c, 2)) );
         assertEquals( "3d", wo(ni(c, 3)) );
         assertEquals( "4d", wo(ni(c, 4)) );

         // if hard factor is <= 1, then hard may not increase
         conf = col.getDecks().confForDid(1);
         conf["rev"].put("hardFactor", 1);
         col.getDecks().save(conf);
         assertEquals( "1d", wo(ni(c, 2)) );
     }

     @Test
     public void test_overdue_lapse(){
         // disabled in commit 3069729776990980f34c25be66410e947e9d51a2
         return;
         Collection col = getCol()  // pylint: disable=unreachable
         // add a note
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         // simulate a review that was lapsed and is now due for its normal review
         Card c = note.cards().get(0);
         c.type = CARD_TYPE_REV;
         c.queue = 1;
         c.due = -1;
         c.odue = -1;
         c.factor = STARTING_FACTOR;
         c.left = 2002;
         c.ivl = 0;
         c.flush();
         col.getSched()._clearOverdue = false;
         // checkpoint
         col.save();
         col.getSched().reset();
         assertEquals( (0, 2, 0, col.getSched().counts() ));
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 3);
         // it should be due tomorrow
         assertEquals( col.getSched(, c.due ).today + 1);
         // revert to before
         col.rollback();
         col.getSched()._clearOverdue = true;
         // with the default settings, the overdue card should be removed from the
         // learning queue
         col.getSched().reset();
         assertEquals( (0, 0, 1, col.getSched().counts() ));
     }

     @Test
     public void test_finished(){
         Collection col = getCol();
         // nothing due
         assertTrue("Congratulations" in col.getSched().finishedMsg());
         assertTrue("limit" not in col.getSched().finishedMsg());
         Note note = col.newNote();
         note.setItem("Front","one");
         note.setItem("Back","two");
         col.addNote(note);
         // have a new card
         assertTrue("new cards available" in col.getSched().finishedMsg());
         // turn it into a review
         col.reset();
         Card c = note.cards().get(0);
         c.startTimer();
         col.getSched().answerCard(c, 3);
         // nothing should be due tomorrow, as it's due in a week
         assertTrue("Congratulations" in col.getSched().finishedMsg());
         assertTrue("limit" not in col.getSched().finishedMsg());
     }

     @Test
     public void test_nextIvl(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         note.setItem("Back","two");
         col.addNote(note);
         col.reset();
         conf = col.getDecks().confForDid(1);
         conf["new"].put("delays", [0.5, 3, 10]);
         conf["lapse"].put("delays", [1, 5, 9]);
         col.getDecks().save(conf);
         Card c = col.getSched().getCard();
         // new cards
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         ni = col.getSched().nextIvl;
         assertEquals( 30, ni(c, 1) );
         assertEquals( (30 + 180, ni(c, 2) ) // )2
                      assertEquals( 180, ni(c, 3) );
                      assertEquals( 4 * 86400, ni(c, 4) );
         col.getSched().answerCard(c, 1);
         // cards in learning
     ////////////////////////////////////////////////////////////////////////////////////////////////////
                      assertEquals( 30, ni(c, 1) );
                      assertEquals( (30 + 180, ni(c, 2) ) // )2
                                   assertEquals( 180, ni(c, 3) );
                                   assertEquals( 4 * 86400, ni(c, 4) );
         col.getSched().answerCard(c, 3);
                                   assertEquals( 30, ni(c, 1) );
                                   assertEquals( (180 + 600, ni(c, 2) ) // )2
                                                assertEquals( 600, ni(c, 3) );
                                                assertEquals( 4 * 86400, ni(c, 4) );
         col.getSched().answerCard(c, 3);
         // normal graduation is tomorrow
                                                assertEquals( 1 * 86400, ni(c, 3) );
                                                assertEquals( 4 * 86400, ni(c, 4) );
         // lapsed cards
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         c.type = CARD_TYPE_REV;
         c.ivl = 100;
         c.factor = STARTING_FACTOR;
                                                assertEquals( 60, ni(c, 1) );
                                                assertEquals( 100 * 86400, ni(c, 3) );
                                                assertEquals( 101 * 86400, ni(c, 4) );
         // review cards
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         c.queue = QUEUE_TYPE_REV;
         c.ivl = 100;
         c.factor = STARTING_FACTOR;
         // failing it should put it at 60s
                                                assertEquals( 60, ni(c, 1) );
         // or 1 day if relearn is false
                                  conf["lapse"].put("delays", []);
         col.getDecks().save(conf);
                                                assertEquals( 1 * 86400, ni(c, 1) );
         // (* 100 1.2 86400)10368000.0
                                                assertEquals( 10368000, ni(c, 2) );
         // (* 100 2.5 86400)21600000.0
                                                assertEquals( 21600000, ni(c, 3) );
         // (* 100 2.5 1.3 86400)28080000.0
                                                assertEquals( 28080000, ni(c, 4) );
                                                assertEquals( "10.8mo", without_unicode_isolation(col.getSched().nextIvlStr(c, 4)) );
     }

     @Test
     public void test_bury(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         Card c = note.cards().get(0);
         Note note = col.newNote();
         note.setItem("Front","two");
         col.addNote(note);
         c2 = note.cards().get(0);
         // burying
         col.getSched().buryCards([c.getId()], manual=true)  // pylint: disable=unexpected-keyword-arg
         c.load();
         assertEquals( QUEUE_TYPE_MANUALLY_BURIED, c.queue );
         col.getSched().buryCards([c2.getId()], manual=false)  // pylint: disable=unexpected-keyword-arg
         c2.load();
         assertEquals( QUEUE_TYPE_SIBLING_BURIED, c2.queue );

         col.reset();
         assertFalse(col.getSched().getCard());

         col.getSched().unburyCardsForDeck(  // pylint: disable=unexpected-keyword-arg
             type="manual";
     );
         c.load();
         assertEquals( QUEUE_TYPE_NEW, c.queue );
         c2.load();
         assertEquals( QUEUE_TYPE_SIBLING_BURIED, c2.queue );

         col.getSched().unburyCardsForDeck(  // pylint: disable=unexpected-keyword-arg
             type="siblings";
     );
         c2.load();
         assertEquals( QUEUE_TYPE_NEW, c2.queue );

         col.getSched().buryCards([c.getId(), c2.getId()]);
         col.getSched().unburyCardsForDeck(type="all")  // pylint: disable=unexpected-keyword-arg

         col.reset();

         assertEquals( (2, 0, 0, col.getSched().counts() ));
     }

     @Test
     public void test_suspend(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         Card c = note.cards().get(0);
         // suspending
         col.reset();
         assertTrue(col.getSched().getCard());
         col.getSched().suspendCards([c.getId()]);
         col.reset();
         assertFalse(col.getSched().getCard());
         // unsuspending
         col.getSched().unsuspendCards([c.getId()]);
         col.reset();
         assertTrue(col.getSched().getCard());
         // should cope with rev cards being relearnt
         c.due = 0;
         c.ivl = 100;
         c.type = CARD_TYPE_REV;
         c.queue = QUEUE_TYPE_REV;
         c.flush();
         col.reset();
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 1);
         assertTrue(c.due >= time.time());
         due = c.due;
         assertEquals( QUEUE_TYPE_LRN, c.queue );
         assertEquals( CARD_TYPE_RELEARNING, c.type );
         col.getSched().suspendCards([c.getId()]);
         col.getSched().unsuspendCards([c.getId()]);
         c.load();
         assertEquals( QUEUE_TYPE_LRN, c.queue );
         assertEquals( CARD_TYPE_RELEARNING, c.type );
         assertEquals( due, c.due );
         // should cope with cards in cram decks
         c.due = 1;
         c.flush();
         cram = col.getDecks().newDyn("tmp");
         col.getSched().rebuildDyn();
         c.load();
         assertNotEquals(1, c.due);
         assertNotEquals(1, c.getDid());
         col.getSched().suspendCards([c.getId()]);
         c.load();
         assertNotEquals(1, c.due);
         assertNotEquals(1, c.getDid());
         assertEquals( 1, c.odue );
     }

     @Test
     public void test_filt_reviewing_early_normal(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         Card c = note.cards().get(0);
         c.ivl = 100;
         c.queue = CARD_TYPE_REV;
         c.type = QUEUE_TYPE_REV;
         // due in 25 days, so it's been waiting 75 days
         c.due = col.getSched().today + 25;
         c.setMod(1);
         c.factor = STARTING_FACTOR;
         c.startTimer();
         c.flush();
         col.reset();
         assertEquals( (0, 0, 0, col.getSched().counts() ));
         // create a dynamic deck and refresh it
         long did = col.getDecks().newDyn("Cram");
         col.getSched().rebuildDyn(did);
         col.reset();
         // should appear as normal in the deck list
         assertEquals( 1, sorted(col.getSched().deck_due_tree().children)[0].review_count );
         // and should appear in the counts
         assertEquals( (0, 0, 1, col.getSched().counts() ));
         // grab it and check estimates
         Card c = col.getSched().getCard();
         assertEquals( 4, col.getSched().answerButtons(c) );
         assertEquals( 600, col.getSched().nextIvl(c, 1) );
         assertEquals( int(75 * 1.2, col.getSched().nextIvl(c, 2) ) * 86400);
         assertEquals( int(75 * 2.5, col.getSched().nextIvl(c, 3) ) * 86400);
         assertEquals( int(75 * 2.5 * 1.15, col.getSched().nextIvl(c, 4) ) * 86400);

         // answer 'good'
         col.getSched().answerCard(c, 3);
         checkRevIvl(col, c, 90);
         assertEquals( col.getSched(, c.due ).today + c.ivl);
         assertFalse(c.odue);
         // should not be in learning
         assertEquals( QUEUE_TYPE_REV, c.queue );
         // should be logged as a cram rep
         assertEquals( 3, col.getDb().scalar("select type from revlog order by id desc limit 1") );

         // due in 75 days, so it's been waiting 25 days
         c.ivl = 100;
         c.due = col.getSched().today + 75;
         c.flush();
         col.getSched().rebuildDyn(did);
         col.reset();
         Card c = col.getSched().getCard();

         assertEquals( 60 * 86400, col.getSched().nextIvl(c, 2) );
         assertEquals( 100 * 86400, col.getSched().nextIvl(c, 3) );
         assertEquals( 114 * 86400, col.getSched().nextIvl(c, 4) );
     }

     @Test
     public void test_filt_keep_lrn_state(){
         Collection col = getCol();

         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);

         // fail the card outside filtered deck
         Card c = col.getSched().getCard();
         conf = col.getSched()._cardConf(c);
         conf["new"].put("delays", [1, 10, 61]);
         col.getDecks().save(conf);

         col.getSched().answerCard(c, 1);

         assertEquals( CARD_TYPE_LRN and c.queue == QUEUE_TYPE_LRN, c.type );
         assertEquals( 3003, c.left );

         col.getSched().answerCard(c, 3);
         assertEquals( CARD_TYPE_LRN and c.queue == QUEUE_TYPE_LRN, c.type );

         // create a dynamic deck and refresh it
         long did = col.getDecks().newDyn("Cram");
         col.getSched().rebuildDyn(did);
         col.reset();

         // card should still be in learning state
         c.load();
         assertEquals( CARD_TYPE_LRN and c.queue == QUEUE_TYPE_LRN, c.type );
         assertEquals( 2002, c.left );

         // should be able to advance learning steps
         col.getSched().answerCard(c, 3);
         // should be due at least an hour in the future
         assertTrue(c.due - intTime() > 60 * 60);

         // emptying the deck preserves learning state
         col.getSched().emptyDyn(did);
         c.load();
         assertEquals( CARD_TYPE_LRN and c.queue == QUEUE_TYPE_LRN, c.type );
         assertEquals( 1001, c.left );
         assertTrue(c.due - intTime() > 60 * 60);
     }

     @Test
     public void test_preview(){
         // add cards
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         Card c = note.cards().get(0);
         orig = copy.copy(c);
         Note note2 = col.newNote();
         note2.setItem("Front","two");
         col.addNote(note2);
         // cram deck
         long did = col.getDecks().newDyn("Cram");
         cram = col.getDecks().get(did);
         cram.put("resched", false);
         col.getDecks().save(cram);
         col.getSched().rebuildDyn(did);
         col.reset();
         // grab the first card
         Card c = col.getSched().getCard();
         assertEquals( 2, col.getSched().answerButtons(c) );
         assertEquals( 600, col.getSched().nextIvl(c, 1) );
         assertEquals( 0, col.getSched().nextIvl(c, 2) );
         // failing it will push its due time back
         due = c.due;
         col.getSched().answerCard(c, 1);
         assertNotEquals(c.due, due);

         // the other card should come next
         c2 = col.getSched().getCard();
         assertNotEquals(c2.getId(), c.getId());

         // passing it will remove it
         col.getSched().answerCard(c2, 2);
         assertEquals( QUEUE_TYPE_NEW, c2.queue );
         assertEquals( 0, c2.reps );
         assertEquals( CARD_TYPE_NEW, c2.type );

         // the other card should appear again
         Card c = col.getSched().getCard();
         assertEquals( orig.getId(, c.getId() ));

         // emptying the filtered deck should restore card
         col.getSched().emptyDyn(did);
         c.load();
         assertEquals( QUEUE_TYPE_NEW, c.queue );
         assertEquals( 0, c.reps );
         assertEquals( CARD_TYPE_NEW, c.type );
     }

     @Test
     public void test_ordcycle(){
         Collection col = getCol();
         // add two more templates and set second active
         Model m = col.getModels().current();
         Models mm = col.getModels();
         JSONObject t = mm.newTemplate("Reverse");
         t.put("qfmt", "{{Back}}");
         t.put("afmt", "{{Front}}");
         mm.addTemplateModChanged(m, t);
         JSONObject t = mm.newTemplate("f2");
         t.put("qfmt", "{{Front}}");
         t.put("afmt", "{{Back}}");
         mm.addTemplateModChanged(m, t);
         mm.save(m);
         // create a new note; it should have 3 cards
         Note note = col.newNote();
         note.setItem("Front","1");
         note.setItem("Back","1");
         col.addNote(note);
         assertEquals( 3, col.cardCount() );
         col.reset();
         // ordinals should arrive in order
         assertEquals( 0, col.getSched().getCard().ord );
         assertEquals( 1, col.getSched().getCard().ord );
         assertEquals( 2, col.getSched().getCard().ord );
     }

     @Test
     public void test_counts_idx(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         note.setItem("Back","two");
         col.addNote(note);
         col.reset();
         assertEquals( (1, 0, 0, col.getSched().counts() ));
         Card c = col.getSched().getCard();
         // counter's been decremented but idx indicates 1
         assertEquals( (0, 0, 0, col.getSched().counts() ));
         assertEquals( 0, col.getSched().countIdx(c) );
         // answer to move to learn queue
         col.getSched().answerCard(c, 1);
         assertEquals( (0, 1, 0, col.getSched().counts() ));
         // fetching again will decrement the count
         Card c = col.getSched().getCard();
         assertEquals( (0, 0, 0, col.getSched().counts() ));
         assertEquals( 1, col.getSched().countIdx(c) );
         // answering should add it back again
         col.getSched().answerCard(c, 1);
         assertEquals( (0, 1, 0, col.getSched().counts() ));
     }

     @Test
     public void test_repCounts(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         col.reset();
         // lrnReps should be accurate on pass/fail
         assertEquals( (1, 0, 0, col.getSched().counts() ));
         col.getSched().answerCard(col.getSched().getCard(), 1);
         assertEquals( (0, 1, 0, col.getSched().counts() ));
         col.getSched().answerCard(col.getSched().getCard(), 1);
         assertEquals( (0, 1, 0, col.getSched().counts() ));
         col.getSched().answerCard(col.getSched().getCard(), 3);
         assertEquals( (0, 1, 0, col.getSched().counts() ));
         col.getSched().answerCard(col.getSched().getCard(), 1);
         assertEquals( (0, 1, 0, col.getSched().counts() ));
         col.getSched().answerCard(col.getSched().getCard(), 3);
         assertEquals( (0, 1, 0, col.getSched().counts() ));
         col.getSched().answerCard(col.getSched().getCard(), 3);
         assertEquals( (0, 0, 0, col.getSched().counts() ));
         Note note = col.newNote();
         note.setItem("Front","two");
         col.addNote(note);
         col.reset();
         // initial pass should be correct too
         col.getSched().answerCard(col.getSched().getCard(), 3);
         assertEquals( (0, 1, 0, col.getSched().counts() ));
         col.getSched().answerCard(col.getSched().getCard(), 1);
         assertEquals( (0, 1, 0, col.getSched().counts() ));
         col.getSched().answerCard(col.getSched().getCard(), 4);
         assertEquals( (0, 0, 0, col.getSched().counts() ));
         // immediate graduate should work
         Note note = col.newNote();
         note.setItem("Front","three");
         col.addNote(note);
         col.reset();
         col.getSched().answerCard(col.getSched().getCard(), 4);
         assertEquals( (0, 0, 0, col.getSched().counts() ));
         // and failing a review should too
         Note note = col.newNote();
         note.setItem("Front","three");
         col.addNote(note);
         Card c = note.cards().get(0);
         c.type = CARD_TYPE_REV;
         c.queue = QUEUE_TYPE_REV;
         c.due = col.getSched().today;
         c.flush();
         col.reset();
         assertEquals( (0, 0, 1, col.getSched().counts() ));
         col.getSched().answerCard(col.getSched().getCard(), 1);
         assertEquals( (0, 1, 0, col.getSched().counts() ));
     }

     @Test
     public void test_timing(){
         Collection col = getCol();
         // add a few review cards, due today
         for (int i=0; i < 5; i++) {
             Note note = col.newNote();
         }
         note.setItem("Front","num") + str(i);
             col.addNote(note);
             Card c = note.cards().get(0);
             c.type = CARD_TYPE_REV;
             c.queue = QUEUE_TYPE_REV;
             c.due = 0;
             c.flush();
         // fail the first one
         col.reset();
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 1);
         // the next card should be another review
         c2 = col.getSched().getCard();
         assertEquals( QUEUE_TYPE_REV, c2.queue );
         // if the failed card becomes due, it should show first
         c.due = intTime() - 1;
         c.flush();
         col.reset();
         Card c = col.getSched().getCard();
         assertEquals( QUEUE_TYPE_LRN, c.queue );
     }

     @Test
     public void test_collapse(){
         Collection col = getCol();
         // add a note
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         col.reset();
         // test collapsing
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 1);
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 4);
         assertFalse(col.getSched().getCard());
     }

     @Test
     public void test_deckDue(){
         Collection col = getCol();
         // add a note with default deck
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         // and one that's a child
         Note note = col.newNote();
         note.setItem("Front","two");
         default1 = note.model().put("did", col.getDecks().id("Default::1"));
         col.addNote(note);
         // make it a review card
         Card c = note.cards().get(0);
         c.queue = QUEUE_TYPE_REV;
         c.due = 0;
         c.flush();
         // add one more with a new deck
         Note note = col.newNote();
         note.setItem("Front","two");
         foobar = note.model().put("did", col.getDecks().id("foo::bar"));
         col.addNote(note);
         // and one that's a sibling
         Note note = col.newNote();
         note.setItem("Front","three");
         foobaz = note.model().put("did", col.getDecks().id("foo::baz"));
         col.addNote(note);
         col.reset();
         assertEquals( 5, col.getDecks().all_names_and_ids().size() );
         tree = col.getSched().deck_due_tree().children;
         assertEquals( "Default", tree[0].name );
         // sum of child and parent
         assertEquals( 1, tree[0].deck_id );
         assertEquals( 1, tree[0].review_count );
         assertEquals( 1, tree[0].new_count );
         // child count is just review
         child = tree[0].children[0];
         assertEquals( "1", child.name );
         assertEquals( default1, child.deck_id );
         assertEquals( 1, child.review_count );
         assertEquals( 0, child.new_count );
         // code should not fail if a card has an invalid deck
         c.long did = 12345;
         c.flush();
         col.getSched().deck_due_tree();
     }

     @Test
     public void test_deckTree(){
         Collection col = getCol();
         col.getDecks().id("new::b::c");
         col.getDecks().id("new2");
         // new should not appear twice in tree
         names = [x.name for x in col.getSched().deck_due_tree().children];
         names.remove("new");
         assertTrue("new" not in names);
     }

     @Test
     public void test_deckFlow(){
         Collection col = getCol();
         // add a note with default deck
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         // and one that's a child
         Note note = col.newNote();
         note.setItem("Front","two");
         default1 = note.model().put("did", col.getDecks().id("Default::2"));
         col.addNote(note);
         // and another that's higher up
         Note note = col.newNote();
         note.setItem("Front","three");
         default1 = note.model().put("did", col.getDecks().id("Default::1"));
         col.addNote(note);
         // should get top level one first, then ::1, then ::2
         col.reset();
         assertEquals( (3, 0, 0, col.getSched().counts() ));
         for (String  i: "one", "three", "two") {
             Card c = col.getSched().getCard();
         }
         assertTrue(c.note().put("Front",= i));
             col.getSched().answerCard(c, 3);
     }

     @Test
     public void test_reorder(){
         Collection col = getCol();
         // add a note with default deck
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         Note note2 = col.newNote();
         note2.setItem("Front","two");
         col.addNote(note2);
         assertEquals( 2, note2.cards().get(0).due );
         found = false;
         // 50/50 chance of being reordered
         for (int i=0; i < 20; i++) {
             col.getSched().randomizeCards(1);
             if (note.cards().get(0).due != note.getId()) {
                 found = true;
                 break;
             }
         }
         assertTrue(found);
         col.getSched().orderCards(1);
         assertEquals( 1, note.cards().get(0).due );
         // shifting
         note3 = col.newNote();
         note3.setItem("Front","three")b;
         col.addNote(note3);
         note4 = col.newNote();
         note4.setItem("Front","four");
         col.addNote(note4);
         assertEquals( 1, note.cards().get(0).due );
         assertEquals( 2, note2.cards().get(0).due );
         assertEquals( 3, note3.cards().get(0).due );
         assertEquals( 4, note4.cards().get(0).due );
         col.getSched().sortCards([note3.cards().get(0).getId(), note4.cards().get(0).getId()], start=1, shift=true);
         assertEquals( 3, note.cards().get(0).due );
         assertEquals( 4, note2.cards().get(0).due );
         assertEquals( 1, note3.cards().get(0).due );
         assertEquals( 2, note4.cards().get(0).due );
     }

     @Test
     public void test_forget(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         Card c = note.cards().get(0);
         c.queue = QUEUE_TYPE_REV;
         c.type = CARD_TYPE_REV;
         c.ivl = 100;
         c.due = 0;
         c.flush();
         col.reset();
         assertEquals( (0, 0, 1, col.getSched().counts() ));
         col.getSched().forgetCards([c.getId()]);
         col.reset();
         assertEquals( (1, 0, 0, col.getSched().counts() ));
     }

     @Test
     public void test_resched(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         Card c = note.cards().get(0);
         col.getSched().reschedCards([c.getId()], 0, 0);
         c.load();
         assertEquals( col.getSched(, c.due ).today);
         assertEquals( 1, c.ivl );
         assertEquals( QUEUE_TYPE_REV and c.type == CARD_TYPE_REV, c.queue );
         col.getSched().reschedCards([c.getId()], 1, 1);
         c.load();
         assertEquals( col.getSched(, c.due ).today + 1);
         assertEquals( +1, c.ivl );
     }

     @Test
     public void test_norelearn(){
         Collection col = getCol();
         // add a note
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         Card c = note.cards().get(0);
         c.type = CARD_TYPE_REV;
         c.queue = QUEUE_TYPE_REV;
         c.due = 0;
         c.factor = STARTING_FACTOR;
         c.reps = 3;
         c.lapses = 1;
         c.ivl = 100;
         c.startTimer();
         c.flush();
         col.reset();
         col.getSched().answerCard(c, 1);
         col.getSched()._cardConf(c)["lapse"].put("delays", []);
         col.getSched().answerCard(c, 1);
     }

     @Test
     public void test_failmult(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         note.setItem("Back","two");
         col.addNote(note);
         Card c = note.cards().get(0);
         c.type = CARD_TYPE_REV;
         c.queue = QUEUE_TYPE_REV;
         c.ivl = 100;
         c.due = col.getSched().today - c.ivl;
         c.factor = STARTING_FACTOR;
         c.reps = 3;
         c.lapses = 1;
         c.startTimer();
         c.flush();
         conf = col.getSched()._cardConf(c);
         conf["lapse"].put("mult", 0.5);
         col.getDecks().save(conf);
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 1);
         assertEquals( 50, c.ivl );
         col.getSched().answerCard(c, 1);
         assertEquals( 25, c.ivl );
     }

     @Test
     public void test_moveVersions(){
         Collection col = getCol();
         col.changeSchedulerVer(1);

         Note n = col.newNote();
         n.put("Front", "one");
         col.addNote(n);

         // make it a learning card
         col.reset();
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 1);

         // the move to v2 should reset it to new
         col.changeSchedulerVer(2);
         c.load();
         assertEquals( QUEUE_TYPE_NEW, c.queue );
         assertEquals( CARD_TYPE_NEW, c.type );

         // fail it again, and manually bury it
         col.reset();
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 1);
         col.getSched().buryCards([c.getId()]);
         c.load();
         assertEquals( QUEUE_TYPE_MANUALLY_BURIED, c.queue );

         // revert to version 1
         col.changeSchedulerVer(1);

         // card should have moved queues
         c.load();
         assertEquals( QUEUE_TYPE_SIBLING_BURIED, c.queue );

         // and it should be new again when unburied
         col.getSched().unburyCards();
         c.load();
         assertEquals( CARD_TYPE_NEW and c.queue == QUEUE_TYPE_NEW, c.type );

         // make sure relearning cards transition correctly to v1
         col.changeSchedulerVer(2);
         // card with 100 day interval, answering again
         col.getSched().reschedCards([c.getId()], 100, 100);
         c.load();
         c.due = 0;
         c.flush();
         conf = col.getSched()._cardConf(c);
         conf["lapse"].put("mult", 0.5);
         col.getDecks().save(conf);
         col.getSched().reset();
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 1);
         // due should be correctly set when removed from learning early
         col.changeSchedulerVer(1);
         c.load();
         assertEquals( 50, c.due );
     }

     // cards with a due date earlier than the collection should retain
     // their due date when removed
     @Test
     public void test_negativeDueFilter(){
         Collection col = getCol();

         // card due prior to collection date
         Note note = col.newNote();
         note.setItem("Front","one");
         note.setItem("Back","two");
         col.addNote(note);
         Card c = note.cards().get(0);
         c.due = -5;
         c.queue = QUEUE_TYPE_REV;
         c.ivl = 5;
         c.flush();

         // into and out of filtered deck
         long did = col.getDecks().newDyn("Cram");
         col.getSched().rebuildDyn(did);
         col.getSched().emptyDyn(did);
         col.reset();

         c.load();
         assertEquals( -5, c.due );
     }


     // hard on the first step should be the average of again and good,
     // and it should be logged properly

     @Test
     public void test_initial_repeat(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         note.setItem("Back","two");
         col.addNote(note);

         col.reset();
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 2);
         // should be due in ~ 5.5 mins
         expected = time.time() + 5.5 * 60;
         assertTrue(expected - 10 < c.due < expected * 1.25);

         ivl = col.getDb().scalar("select ivl from revlog");
         assertEquals( -5.5 * 60, ivl );
     }
     /*****************
          ** Stats        *
      *****************/

     @Test
     public void test_stats(){
         Collection col = getCol();
         Note note = col.newNote();
         note.setItem("Front","foo");
         col.addNote(note);
         Card c = note.cards().get(0);
         // card stats
         assertTrue(col.cardStats(c));
         col.reset();
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 3);
         col.getSched().answerCard(c, 2);
         assertTrue(col.cardStats(c));
     }

     @Test
     public void test_graphs_empty(){
         Collection col = getCol();
         assertTrue(col.stats().report());
     }

     @Test
     public void test_graphs(){
         dir = tempfile.gettempdir();
         Collection col = getCol();
         g = col.stats();
         rep = g.report();
         with open(os.path.join(dir, "test.html"), "w", encoding="UTF-8") as note:
             note.write(rep);
         return;
     }
     /*****************
          ** Templates    *
      *****************/

     @Test
     public void test_deferred_frontside(){
         Collection col = getCol();
         Model m = col.getModels().current();
         m["tmpls"][0].put("qfmt", "{{custom:Front}}");
         col.getModels().save(m);

         Note note = col.newNote();
         note.setItem("Front","xxtest");
         note.setItem("Back","");
         col.addNote(note);

         assertTrue("xxtest" in note.cards().get(0).a());
     }
     /*****************
          ** Undo         *
      *****************/
     private Collection getCol(){
         Collection col = getColOrig();
         col.changeSchedulerVer(2);
         return col;
     }

     @Test
     public void test_op(){
         Collection col = getCol();
         // should have no undo by default
         assertFalse(col.undoName());
         // let's adjust a study option
         col.save("studyopts");
         col.conf.put("abc", 5);
         // it should be listed as undoable
         assertEquals( "studyopts", col.undoName() );
         // with about 5 minutes until it's clobbered
         assertTrue(time.time() - col._lastSave < 1);
         // undoing should restore the old value
         col.undo();
         assertFalse(col.undoName());
         assertTrue("abc" not in col..getConf());
         // an (auto)save will clear the undo
         col.save("foo");
         assertEquals( "foo", col.undoName() );
         col.save();
         assertFalse(col.undoName());
         // and a review will, too
         col.save("add");
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         col.reset();
         assertEquals( "add", col.undoName() );
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 2);
         assertEquals( "Review", col.undoName() );
     }

     @Test
     public void test_review(){
         Collection col = getCol();
         col.getConf().put("counts", COUNT_REMAINING);
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         col.reset();
         assertFalse(col.undoName());
         // answer
         assertEquals( (1, 0, 0, col.getSched().counts() ));
         Card c = col.getSched().getCard();
         assertEquals( QUEUE_TYPE_NEW, c.queue );
         col.getSched().answerCard(c, 3);
         assertEquals( 1001, c.left );
         assertEquals( (0, 1, 0, col.getSched().counts() ));
         assertEquals( QUEUE_TYPE_LRN, c.queue );
         // undo
         assertTrue(col.undoName());
         col.undo();
         col.reset();
         assertEquals( (1, 0, 0, col.getSched().counts() ));
         c.load();
         assertEquals( QUEUE_TYPE_NEW, c.queue );
         assertNotEquals(1001, c.left);
         assertFalse(col.undoName());
         // we should be able to undo multiple answers too
         Note note = col.newNote();
         note.setItem("Front","two");
         col.addNote(note);
         col.reset();
         assertEquals( (2, 0, 0, col.getSched().counts() ));
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 3);
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 3);
         assertEquals( (0, 2, 0, col.getSched().counts() ));
         col.undo();
         col.reset();
         assertEquals( (1, 1, 0, col.getSched().counts() ));
         col.undo();
         col.reset();
         assertEquals( (2, 0, 0, col.getSched().counts() ));
         // performing a normal op will clear the review queue
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 3);
         assertEquals( "Review", col.undoName() );
         col.save("foo");
         assertEquals( "foo", col.undoName() );
         col.undo();
     }

 } 
