package com.ichi2.libanki;

import com.ichi2.anki.RobolectricTest;
import com.ichi2.libanki.sched.AbstractSched;
import com.ichi2.utils.Assert;
import com.ichi2.utils.JSONObject;

import org.apache.http.util.Asserts;
import org.junit.Test;
import org.junit.runner.RunWith;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class UpstreamTest extends RobolectricTest {
    /*****************
      ** Cards        *
      *****************/

     @Test
     public void test_delete(){
         Collection col = getEmptyCol();
         Note note = col.newNote();
         note.setItem("Front","1");
         note.setItem("Back","2");
         col.addNote(note);
         long cid = note.cards().get(0).getId();
         col.reset();
         col.getSched().answerCard(col.getSched().getCard(), 2);
         col.remove_cards_and_orphaned_notes([cid]);
         assertTrue(col.cardCount() == 0);
         assertTrue(col.noteCount() == 0);
         assertTrue(col.getDb().queryScalar("select count() from notes") == 0);
         assertTrue(col.getDb().queryScalar("select count() from cards") == 0);
         assertTrue(col.getDb().queryScalar("select count() from graves") == 2);
     }

     @Test
     public void test_misc(){
         Collection col = getEmptyCol();
         Note note = col.newNote();
         note.setItem("Front","1");
         note.setItem("Back","2");
         col.addNote(note);
         Card c = note.cards().get(0);
         long id = col.getModels().current().getLong("id");
         assertTrue(c.template()["ord"] == 0);
     }

     @Test
     public void test_genrem(){
         Collection col = getEmptyCol();
         Note note = col.newNote();
         note.setItem("Front","1");
         note.setItem("Back","");
         col.addNote(note);
         assertTrue(len(note.cards()) == 1);
         Model m = col.getModels().current();
         Models mm = col.getModels();
         // adding a new template should automatically create cards
         JSONObject t = mm.newTemplate("rev");
         t["qfmt"] = "{{Front}}";
         t["afmt"] = "";
         mm.addTemplate(m, t);
         mm.save(m, templates=True);
         assertTrue(len(note.cards()) == 2);
         // if the template is changed to remove cards, they'll be removed
         JSONObject t = m["tmpls"][1];
         t["qfmt"] = "{{Back}}";
         mm.save(m, templates=True);
         rep = col.backend.get_empty_cards();
         rep = col.backend.get_empty_cards();
         for n in rep.notes:
             col.remove_cards_and_orphaned_notes(n.card_ids);
             assertTrue(len(note.cards()) == 1);
         // if we add to the note, a card should be automatically generated
         note.load();
         note.setItem("Back","1");
         note.flush();
         assertTrue(len(note.cards()) == 2);
     }

     @Test
     public void test_gendeck(){
         Collection col = getEmptyCol();
         Model cloze = col.getModels().byName("Cloze");
         col.getModels().setCurrent(cloze);
         Note note = col.newNote();
         note.setItem("Text","{{c1::one}}");
         col.addNote(note);
         assertTrue(col.cardCount() == 1);
         assertTrue(note.cards().get(0).did == 1);
         // set the model to a new default col
         long newid = col.decks.getId()("new");
         cloze["did"] = newId;
         col.getModels().save(cloze, updateReqs=False);
         // a newly generated card should share the first card's col
         note.setItem("Text","{{c2::two}}");
         note.flush();
         assertTrue(note.cards()[1].did == 1);
         // and same with multiple cards
         note.setItem("Text","{{c3::three}}");
         note.flush();
         assertTrue(note.cards()[2].did == 1);
         // if one of the cards is in a different col, it should revert to the
         // model default
         Card c = note.cards()[1];
         c.did = newId;
         c.flush();
         note.setItem("Text","{{c4::four}}");
         note.flush();
         assertTrue(note.cards()[3].did == newId);
     }
     /*****************
      ** Collection   *
      *****************/

     @Test
     public void test_create_open(){
         (fd, path) = tempfile.mkstemp(suffix=".anki2", prefix="test_attachNew");
         try:
             os.close(fd);
             os.unlink(path);
         except OSError:
             pass;
         Collection col = aopen(path);
         // for open()
         String newPath = col.path;
         long newMod = col.mod;
         col.close();
         del col;

         // reopen
         Collection col = aopen(newPath);
         assertTrue(col.mod == newMod);
         col.close();

         // non-writeable dir
         if isWin:
             String dir = "c:\root.anki2";
         else:
             String dir = "/attachroot.anki2";
         assertException(Exception, lambda: aopen(dir));
         // reuse tmp file from before, test non-writeable file
         os.chmod(newPath, 0);
         assertException(Exception, lambda: aopen(newPath));
         os.chmod(newPath, 0o666);
         os.unlink(newPath);
     }

     @Test
     public void test_noteAddDelete(){
         Collection col = getEmptyCol();
         // add a note
         Note note = col.newNote();
         note.setItem("Front","one");
         note.setItem("Back","two");
         Note n = col.addNote(note);
         assertTrue(n == 1);
         // test multiple cards - add another template
         Model m = col.getModels().current();
         Models mm = col.getModels();
         JSONObject t = mm.newTemplate("Reverse");
         t["qfmt"] = "{{Back}}";
         t["afmt"] = "{{Front}}";
         mm.addTemplate(m, t);
         mm.save(m);
         assertTrue(col.cardCount() == 2);
         // creating new notes should use both cards
         Note note = col.newNote();
         note.setItem("Front","three");
         note.setItem("Back","four");
         Note n = col.addNote(note);
         assertTrue(n == 2);
         assertTrue(col.cardCount() == 4);
         // check q/a generation
         Card c0 = note.cards().get(0);
         assertTrue("three" in c0.q());
         // it should not be a duplicate
         assertTrue(not note.dupeOrEmpty());
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
         Collection col = getEmptyCol();
         Note note = col.newNote();
         note.setItem("Front","new");
         note.setItem("Back","new2");
         col.addNote(note);
         assertTrue(col.getDb().scalar("select csum from notes") == int("c2a6b03f", 16));
         // changing the val should change the checksum
         note.setItem("Front","newx");
         note.flush();
         assertTrue(col.getDb().scalar("select csum from notes") == int("302811ae", 16));
     }

     @Test
     public void test_addDelTags(){
         Collection col = getEmptyCol();
         Note note = col.newNote();
         note.setItem("Front","1");
         col.addNote(note);
         note2 = col.newNote();
         note2.setItem("Front","2");
         col.addNote(note2);
         // adding for a given id
         col.tags.bulkAdd([note.getId()], "foo");
         note.load();
         note2.load();
         assertTrue("foo" in note.tags);
         assertTrue("foo" not in note2.tags);
         // should be canonified
         col.tags.bulkAdd([note.getId()], "foo aaa");
         note.load();
         assertTrue(note.tags[0] == "aaa");
         assertTrue(len(note.tags) == 2);
     }

     @Test
     public void test_timestamps(){
         Collection col = getEmptyCol();
         assertTrue(len(col.getModels().all_names_and_ids()) == len(get_stock_notetypes(col)));
         for i in range(100):
             addBasicModel(col);
             assertTrue(len(col.getModels().all_names_and_ids()) == 100 + len(get_stock_notetypes(col)));
     }

     @Test
     public void test_furigana(){
         Collection col = getEmptyCol();
         Models mm = col.getModels();
         Model m = mm.current();
         // filter should work
         m["tmpls"][0]["qfmt"] = "{{kana:Front}}";
         mm.save(m);
         Note n = col.newNote();
         n["Front"] = "foo[abc]";
         col.addNote(n);
         Card c = n.cards().get(0);
         assertTrue(c.q().endswith("abc"));
         // and should avoid sound
         n["Front"] = "foo[sound:abc.mp3]";
         n.flush();
         assertTrue("anki:play" in c.q(reload=True));
         // it shouldn't throw an error while people are editing
         m["tmpls"][0]["qfmt"] = "{{kana:}}";
         mm.save(m);
         c.q(reload=True);
     }

     @Test
     public void test_translate(){
         Collection col = getEmptyCol();
         no_uni = without_unicode_isolation;

         assertTrue(();
             col.tr(TR.CARD_TEMPLATE_RENDERING_FRONT_SIDE_PROBLEM);
             == "Front template has a problem:";
     );
         assertTrue(no_uni(col.tr(TR.STATISTICS_REVIEWS, reviews=1)) == "1 review");
         assertTrue(no_uni(col.tr(TR.STATISTICS_REVIEWS, reviews=2)) == "2 reviews");
     }

     @Test
     public void test_db_named_args(capsys):
         sql = "select a, 2+:test5 from b where arg =:foo and x = :test5";
         args = [];
         kwargs = dict(test5=5, foo="blah");

         s, a = emulate_named_args(sql, args, kwargs);
    assertTrue(s == "select a, 2+?1 from b where arg =?2 and x = ?1");
         assertTrue(a == [5, "blah"]);

         // swallow the warning
         _ = capsys.readouterr();
     }
     /*****************
      ** Decks        *
      *****************/

     @Test
     public void test_basic(){
         Collection col = getEmptyCol();
         // we start with a standard col
         assertTrue(len(col.decks.all_names_and_ids()) == 1);
         // it should have an id of 1
         assertTrue(col.decks.name(1));
         // create a new col
         long parentId = col.decks.getId()("new deck");
         assertTrue(parentId);
         assertTrue(len(col.decks.all_names_and_ids()) == 2);
         // should get the same id
         assertTrue(col.decks.getId()("new deck") == parentId);
         // we start with the default col selected
         assertTrue(col.decks.selected() == 1);
         assertTrue(col.decks.active() == [1]);
         // we can select a different col
         col.decks.select(parentId);
         assertTrue(col.decks.selected() == parentId);
         assertTrue(col.decks.active() == [parentId]);
         // let's create a child
         long childId = col.decks.getId()("new deck::child");
         col.getSched().reset();
         // it should have been added to the active list
         assertTrue(col.decks.selected() == parentId);
         assertTrue(col.decks.active() == [parentId, childId]);
         // we can select the child individually too
         col.decks.select(childId);
         assertTrue(col.decks.selected() == childId);
         assertTrue(col.decks.active() == [childId]);
         // parents with a different case should be handled correctly
         col.decks.getId()("ONE");
         Model m = col.getModels().current();
         m["did"] = col.decks.getId()("one::two");
         col.getModels().save(m, updateReqs=False);
         Note n = col.newNote();
         n["Front"] = "abc";
         col.addNote(n);
     }

     @Test
     public void test_remove(){
         Collection col = getEmptyCol();
         // create a new col, and add a note/card to it
         long deck1 = col.decks.getId()("deck1");
         Note note = col.newNote();
         note.setItem("Front","1");
         note.model()["did"] = deck1;
         col.addNote(note);
         Card c = note.cards().get(0);
         assertTrue(c.did == deck1);
         assertTrue(col.cardCount() == 1);
         col.decks.rem(deck1);
         assertTrue(col.cardCount() == 0);
         // if we try to get it, we get the default
         assertTrue(col.decks.name(c.did) == "[no deck]");
     }

     @Test
     public void test_rename(){
         Collection col = getEmptyCol();
         long id = col.decks.getId()("hello::world");
         // should be able to rename into a completely different branch, creating
         // parents as necessary
         col.decks.rename(col.decks.get(id), "foo::bar");
         names = [n.name for n in col.decks.all_names_and_ids()];
         assertTrue("foo" in names);
         assertTrue("foo::bar" in names);
         assertTrue("hello::world" not in names);
         // create another col
         long id = col.decks.getId()("tmp");
         // automatically adjusted if a duplicate name
         col.decks.rename(col.decks.get(id), "FOO");
         names = [n.name for n in col.decks.all_names_and_ids()];
         assertTrue("FOO+" in names);
         // when renaming, the children should be renamed too
         col.decks.getId()("one::two::three");
         long id = col.decks.getId()("one");
         col.decks.rename(col.decks.get(id), "yo");
         names = [n.name for n in col.decks.all_names_and_ids()];
         for n in "yo", "yo::two", "yo::two::three":
         assertTrue(n in names);
         // over filtered
         long filteredId = col.decks.newDyn("filtered");
         Deck filtered = col.decks.get(filteredId);
         long childId = col.decks.getId()("child");
         Deck child = col.decks.get(childId);
         assertException(DeckRenameError, lambda: col.decks.rename(child, "filtered::child"));
         assertException(DeckRenameError, lambda: col.decks.rename(child, "FILTERED::child"));
     }

     @Test
     public void test_renameForDragAndDrop(){
         Collection col = getEmptyCol();

         def deckNames():
             return [n.name for n in col.decks.all_names_and_ids(skip_empty_default=True)];

         long languages_did = col.decks.getId()("Languages");
         long chinese_did = col.decks.getId()("Chinese");
         long hsk_did = col.decks.getId()("Chinese::HSK");

         // Renaming also renames children
         col.decks.renameForDragAndDrop(chinese_did, languages_did);
         assertTrue(deckNames() == ["Languages", "Languages::Chinese", "Languages::Chinese::HSK"]);

         // Dragging a col onto itself is a no-op
         col.decks.renameForDragAndDrop(languages_did, languages_did);
         assertTrue(deckNames() == ["Languages", "Languages::Chinese", "Languages::Chinese::HSK"]);

         // Dragging a col onto its parent is a no-op
         col.decks.renameForDragAndDrop(hsk_did, chinese_did);
         assertTrue(deckNames() == ["Languages", "Languages::Chinese", "Languages::Chinese::HSK"]);

         // Dragging a col onto a descendant is a no-op
         col.decks.renameForDragAndDrop(languages_did, hsk_did);
         assertTrue(deckNames() == ["Languages", "Languages::Chinese", "Languages::Chinese::HSK"]);

         // Can drag a grandchild onto its grandparent.  It becomes a child
         col.decks.renameForDragAndDrop(hsk_did, languages_did);
         assertTrue(deckNames() == ["Languages", "Languages::Chinese", "Languages::HSK"]);

         // Can drag a col onto its sibling
         col.decks.renameForDragAndDrop(hsk_did, chinese_did);
         assertTrue(deckNames() == ["Languages", "Languages::Chinese", "Languages::Chinese::HSK"]);

         // Can drag a col back to the top level
         col.decks.renameForDragAndDrop(chinese_did, None);
         assertTrue(deckNames() == ["Chinese", "Chinese::HSK", "Languages"]);

         // Dragging a top level col to the top level is a no-op
         col.decks.renameForDragAndDrop(chinese_did, None);
         assertTrue(deckNames() == ["Chinese", "Chinese::HSK", "Languages"]);

         // decks are renamed if necessary
         long new_hsk_did = col.decks.getId()("hsk");
         col.decks.renameForDragAndDrop(new_hsk_did, chinese_did);
         assertTrue(deckNames() == ["Chinese", "Chinese::HSK", "Chinese::hsk+", "Languages"]);
         col.decks.rem(new_hsk_did);

         // '' is a convenient alias for the top level DID
         col.decks.renameForDragAndDrop(hsk_did, "");
         assertTrue(deckNames() == ["Chinese", "HSK", "Languages"]);
      }

     /*****************
      ** Exporting    *
      *****************/
     private void setup1(){
         global col;
         Collection col = getEmptyCol();
         Note note = col.newNote();
         note.setItem("Front","foo");
         note.setItem("Back","bar<br>");
         note.tags = ["tag", "tag2"];
         col.addNote(note);
         // with a different col
         Note note = col.newNote();
         note.setItem("Front","baz");
         note.setItem("Back","qux");
         note.model()["did"] = col.decks.getId()("new col");
         col.addNote(note);
     }

             /*//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////// */



     @Test
     public void test_export_anki(){
         setup1();
         // create a new col with its own conf to test conf copying
         long did = col.decks.getId()("test");
         Deck dobj = col.decks.get(did);
         long confId = col.decks.add_config_returning_id("newconf");
         DeckConfig conf = col.decks.get_config(confId);
         conf["new"]["perDay"] = 5;
         col.decks.save(conf);
         col.decks.setConf(dobj, confId);
         // export
         AnkiPackageExporter e = AnkiExporter(col);
         fd, newname = tempfile.mkstemp(prefix="ankitest", suffix=".anki2");
         newname = str(newname);
         os.close(fd);
         os.unlink(newname);
         e.exportInto(newname);
         // exporting should not have changed conf for original deck
         conf = col.decks.confForDid(did);
         assertTrue(conf.getLong("id") != 1);
         // connect to new deck
         Collection col2 = aopen(newname);
         assertTrue(col2.cardCount() == 2);
         // as scheduling was reset, should also revert decks to default conf
         long did = col2.decks.getId()("test", create=False);
         assertTrue(did);
         conf2 = col2.decks.confForDid(did);
         assertTrue(conf2["new"]["perDay"] == 20);
         Deck dobj = col2.decks.get(did);
         // conf should be 1
         assertTrue(dobj["conf"] == 1);
         // try again, limited to a deck
         fd, newname = tempfile.mkstemp(prefix="ankitest", suffix=".anki2");
         newname = str(newname);
         os.close(fd);
         os.unlink(newname);
         e.long did = 1;
         e.exportInto(newname);
         col2 = aopen(newname);
         assertTrue(col2.cardCount() == 1);
     }

     @Test
     public void test_export_ankipkg(){
         setup1();
         // add a test file to the media folder
         with open(os.path.join(col.media.dir(), "今日.mp3"), "w") as note:
             note.write("test");
         Note n = col.newNote();
         n["Front"] = "[sound:今日.mp3]";
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
         Collection col = getEmptyCol();
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
         assertTrue(c.ivl == 1);
         assertTrue(c.due == 11);
         assertTrue(col.getSched().today == 10);
         assertTrue(c.due - col.getSched().today == 1);
         // export
         AnkiPackageExporter e = AnkiExporter(col);
         e.includeSched = True;
         fd, newname = tempfile.mkstemp(prefix="ankitest", suffix=".anki2");
         String newname = str(newname);
         os.close(fd);
         os.unlink(newname);
         e.exportInto(newname);
         // importing into a new deck, the due date should be equivalent
         col2 = getEmptyCol();
         imp = Anki2Importer(col2, newname);
         imp.run();
         Card c = col2.getCard(c.getId());
         col2.getSched().reset();
         assertTrue(c.due - col2.getSched().today == 1);
     }

     @Test
     public void test_export_textcard(){
     //     setup1()
     //     e = TextCardExporter(col)
     //     Note note = unicode(tempfile.mkstemp(prefix="ankitest")[1])
     //     os.unlink(note)
     //     e.exportInto(note)
     //     e.includeTags = True
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
             assertTrue(file.readline() == "foo\tbar<br>\ttag tag2\n");
         e.includeTags = False;
         e.includeHTML = False;
         e.exportInto(note);
         with open(note) as file:
             assertTrue(file.readline() == "foo\tbar\n");
     }

     @Test
     public void test_exporters(){
         assertTrue("*.apkg" in str(exporters()));
     /*****************
      ** Find         *
      *****************/
     class DummyCollection:
         def weakref(self):
             return None;
     }

     @Test
     public void test_findCards(){
         Collection col = getEmptyCol();
         Note note = col.newNote();
         note.setItem("Front","dog");
         note.setItem("Back","cat");
         note.tags.append("monkey animal_1 * %");
         col.addNote(note);
         long f1id = note.getId();
         firstCarlong did = note.cards().get(0).getId();
         Note note = col.newNote();
         note.setItem("Front","goats are fun");
         note.setItem("Back","sheep");
         note.tags.append("sheep goat horse animal11");
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
         t["qfmt"] = "{{Back}}";
         t["afmt"] = "{{Front}}";
         mm.addTemplate(m, t);
         mm.save(m);
         Note note = col.newNote();
         note.setItem("Front","test");
         note.setItem("Back","foo bar");
         col.addNote(note);
         col.save();
         long[] latestCardIds = [c.getId() for c in note.cards()];
         // tag searches
         assertTrue(len(col.findCards("tag:*")) == 5);
         assertTrue(len(col.findCards("tag:\\*")) == 1);
         assertTrue(len(col.findCards("tag:%")) == 5);
         assertTrue(len(col.findCards("tag:\\%")) == 1);
         assertTrue(len(col.findCards("tag:animal_1")) == 2);
         assertTrue(len(col.findCards("tag:animal\\_1")) == 1);
         assertTrue(not col.findCards("tag:donkey"));
         assertTrue(len(col.findCards("tag:sheep")) == 1);
         assertTrue(len(col.findCards("tag:sheep tag:goat")) == 1);
         assertTrue(len(col.findCards("tag:sheep tag:monkey")) == 0);
         assertTrue(len(col.findCards("tag:monkey")) == 1);
         assertTrue(len(col.findCards("tag:sheep -tag:monkey")) == 1);
         assertTrue(len(col.findCards("-tag:sheep")) == 4);
         col.tags.bulkAdd(col.getDb().list("select id from notes"), "foo bar");
         assertTrue(len(col.findCards("tag:foo")) == len(col.findCards("tag:bar")) == 5);
         col.tags.bulkRem(col.getDb().list("select id from notes"), "foo");
         assertTrue(len(col.findCards("tag:foo")) == 0);
         assertTrue(len(col.findCards("tag:bar")) == 5);
         // text searches
         assertTrue(len(col.findCards("cat")) == 2);
         assertTrue(len(col.findCards("cat -dog")) == 1);
         assertTrue(len(col.findCards("cat -dog")) == 1);
         assertTrue(len(col.findCards("are goats")) == 1);
         assertTrue(len(col.findCards('"are goats"')) == 0);
         assertTrue(len(col.findCards('"goats are"')) == 1);
         // card states
         Card c = note.cards().get(0);
         c.queue = c.type = CARD_TYPE_REV;
         assertTrue(col.findCards("is:review") == []);
         c.flush();
         assertTrue(col.findCards("is:review") == [c.getId()]);
         assertTrue(col.findCards("is:due") == []);
         c.due = 0;
         c.queue = QUEUE_TYPE_REV;
         c.flush();
         assertTrue(col.findCards("is:due") == [c.getId()]);
         assertTrue(len(col.findCards("-is:due")) == 4);
         c.queue = -1;
         // ensure this card gets a later mod time
         c.flush();
         col.getDb().execute("update cards set mod = mod + 1 where long id = ?", c.getId());
         assertTrue(col.findCards("is:suspended") == [c.getId()]);
         // nids
         assertTrue(col.findCards("nid:54321") == []);
         assertTrue(len(col.findCards(f"nid:{note.getId()}")) == 2);
         assertTrue(len(col.findCards(f"nid:{f1id},{f2id}")) == 2);
         // templates
         assertTrue(len(col.findCards("card:foo")) == 0);
         assertTrue(len(col.findCards('"card:card 1"')) == 4);
         assertTrue(len(col.findCards("card:reverse")) == 1);
         assertTrue(len(col.findCards("card:1")) == 4);
         assertTrue(len(col.findCards("card:2")) == 1);
         // fields
         assertTrue(len(col.findCards("front:dog")) == 1);
         assertTrue(len(col.findCards("-front:dog")) == 4);
         assertTrue(len(col.findCards("front:sheep")) == 0);
         assertTrue(len(col.findCards("back:sheep")) == 2);
         assertTrue(len(col.findCards("-back:sheep")) == 3);
         assertTrue(len(col.findCards("front:do")) == 0);
         assertTrue(len(col.findCards("front:*")) == 5);
         // ordering
         col.conf["sortType"] = "noteCrt";
         col.flush();
         assertTrue(col.findCards("front:*", order=True)[-1] in latestCardIds);
         assertTrue(col.findCards("", order=True)[-1] in latestCardIds);
         col.conf["sortType"] = "noteFld";
         col.flush();
         assertTrue(col.findCards("", order=True)[0] == catCard.getId());
         assertTrue(col.findCards("", order=True)[-1] in latestCardIds);
         col.conf["sortType"] = "cardMod";
         col.flush();
         assertTrue(col.findCards("", order=True)[-1] in latestCardIds);
         assertTrue(col.findCards("", order=True)[0] == firstCardId);
         col.conf["sortBackwards"] = True;
         col.flush();
         assertTrue(col.findCards("", order=True)[0] in latestCardIds);
         assertTrue(();
             col.find_cards("", order=BuiltinSortKind.CARD_DUE, reverse=False)[0];
             == firstCardId;
     );
         assertTrue(();
             col.find_cards("", order=BuiltinSortKind.CARD_DUE, reverse=True)[0];
             != firstCardId;
     );
         // model
         assertTrue(len(col.findCards("note:basic")) == 3);
         assertTrue(len(col.findCards("-note:basic")) == 2);
         assertTrue(len(col.findCards("-note:foo")) == 5);
         // col
         assertTrue(len(col.findCards("deck:default")) == 5);
         assertTrue(len(col.findCards("-deck:default")) == 0);
         assertTrue(len(col.findCards("-deck:foo")) == 5);
         assertTrue(len(col.findCards("deck:def*")) == 5);
         assertTrue(len(col.findCards("deck:*EFAULT")) == 5);
         assertTrue(len(col.findCards("deck:*cefault")) == 0);
         // full search
         Note note = col.newNote();
         note.setItem("Front","hello<b>world</b>");
         note.setItem("Back","abc");
         col.addNote(note);
         // as it's the sort field, it matches
         assertTrue(len(col.findCards("helloworld")) == 2);
         // assertTrue(len(col.findCards("helloworld", full=True)) == )2
         // if we put it on the back, it won't
         (note.setItem("Front","Back")]) = (note.setItem("Back","Front")]);
         note.flush();
         assertTrue(len(col.findCards("helloworld")) == 0);
         // assertTrue(len(col.findCards("helloworld", full=True)) == )2
         // assertTrue(len(col.findCards("back:helloworld", full=True)) == )2
         // searching for an invalid special tag should not error
         with pytest.raises(Exception):
             len(col.findCards("is:invalid"));
         // should be able to limit to parent col, no children
 long id = col.getDb().scalar("select id from cards limit 1");
         col.getDb().execute(;
             "update cards set long did = ? where long id = ?", col.decks.getId()("Default::Child"), id;
     );
         col.save();
         assertTrue(len(col.findCards("deck:default")) == 7);
         assertTrue(len(col.findCards("deck:default::child")) == 1);
         assertTrue(len(col.findCards("deck:default -deck:default::*")) == 6);
         // properties
 long id = col.getDb().scalar("select id from cards limit 1");
         col.getDb().execute(;
             "update cards set queue=2, ivl=10, reps=20, due=30, factor=2200 ";
             "where long id = ?",;
             id,;
     );
         assertTrue(len(col.findCards("prop:ivl>5")) == 1);
         assertTrue(len(col.findCards("prop:ivl<5")) > 1);
         assertTrue(len(col.findCards("prop:ivl>=5")) == 1);
         assertTrue(len(col.findCards("prop:ivl=9")) == 0);
         assertTrue(len(col.findCards("prop:ivl=10")) == 1);
         assertTrue(len(col.findCards("prop:ivl!=10")) > 1);
         assertTrue(len(col.findCards("prop:due>0")) == 1);
         // due dates should work
         assertTrue(len(col.findCards("prop:due=29")) == 0);
         assertTrue(len(col.findCards("prop:due=30")) == 1);
         // ease factors
         assertTrue(len(col.findCards("prop:ease=2.3")) == 0);
         assertTrue(len(col.findCards("prop:ease=2.2")) == 1);
         assertTrue(len(col.findCards("prop:ease>2")) == 1);
         assertTrue(len(col.findCards("-prop:ease>2")) > 1);
         // recently failed
         if not isNearCutoff():
         assertTrue(len(col.findCards("rated:1:1")) == 0);
         assertTrue(len(col.findCards("rated:1:2")) == 0);
             Card c = col.getSched().getCard();
             col.getSched().answerCard(c, 2);
             assertTrue(len(col.findCards("rated:1:1")) == 0);
             assertTrue(len(col.findCards("rated:1:2")) == 1);
             Card c = col.getSched().getCard();
             col.getSched().answerCard(c, 1);
             assertTrue(len(col.findCards("rated:1:1")) == 1);
             assertTrue(len(col.findCards("rated:1:2")) == 1);
             assertTrue(len(col.findCards("rated:1")) == 2);
             assertTrue(len(col.findCards("rated:0:2")) == 0);
             assertTrue(len(col.findCards("rated:2:2")) == 1);
             // added
             assertTrue(len(col.findCards("added:0")) == 0);
             col.getDb().execute("update cards set long id = id - 86400*1000 where long id = ?", id);
             assertTrue(len(col.findCards("added:1")) == col.cardCount() - 1);
             assertTrue(len(col.findCards("added:2")) == col.cardCount());
         else:
             print("some find tests disabled near cutoff");
         // empty field
             assertTrue(len(col.findCards("front:")) == 0);
         Note note = col.newNote();
note.setItem("Front","");
note.setItem("Back","abc2");
         assertTrue(col.addNote(note) == 1);
         assertTrue(len(col.findCards("front:")) == 1);
         // OR searches and nesting
         assertTrue(len(col.findCards("tag:monkey or tag:sheep")) == 2);
         assertTrue(len(col.findCards("(tag:monkey OR tag:sheep)")) == 2);
         assertTrue(len(col.findCards("-(tag:monkey OR tag:sheep)")) == 6);
         assertTrue(len(col.findCards("tag:monkey or (tag:sheep sheep)")) == 2);
         assertTrue(len(col.findCards("tag:monkey or (tag:sheep octopus)")) == 1);
         // flag
         with pytest.raises(Exception):
             col.findCards("flag:12");
     }

     @Test
     public void test_findReplace(){
         Collection col = getEmptyCol();
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
         assertTrue(col.findReplace(nids, "abc", "123") == 0);
         // global replace
         assertTrue(col.findReplace(nids, "foo", "qux") == 2);
         note.load();
         assertTrue(note.setItem("Front","qux"));
         note2.load();
         assertTrue(note2.setItem("Back","qux"));
         // single field replace
         assertTrue(col.findReplace(nids, "qux", "foo", field="Front") == 1);
         note.load();
         assertTrue(note.setItem("Front","foo"));
         note2.load();
         assertTrue(note2.setItem("Back","qux"));
         // regex replace
         assertTrue(col.findReplace(nids, "B.r", "reg") == 0);
         note.load();
         assertTrue(note.setItem("Back","reg"));
         assertTrue(col.findReplace(nids, "B.r", "reg", regex=True) == 1);
         note.load();
         assertTrue(note.setItem("Back","reg"));
     }

     @Test
     public void test_findDupes(){
         Collection col = getEmptyCol();
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
         assertTrue(r[0][0] == "bar");
         assertTrue(len(r[0][1]) == 3);
         // valid search
         r = col.findDupes("Back", "bar");
         assertTrue(r[0][0] == "bar");
         assertTrue(len(r[0][1]) == 3);
         // excludes everything
         r = col.findDupes("Back", "invalid");
         assertTrue(not r);
         // front isn't dupe
         assertTrue(col.findDupes("Front") == []);
     }

      /*****************
      ** Importing    *
      *****************/
      private void clear_tempfile(tf) {
             ;
         """ https://stackoverflow.com/questions/23212435/permission-denied-to-write-to-my-temporary-file """;
         try:
             tf.close();
             os.unlink(tf.name);
         except:
             pass;
     }

     @Test
     public void test_anki2_mediadupes(){
         Collection col = getEmptyCol();
         // add a note that references a sound
         Note n = tmp.newNote();
         n["Front"] = "[sound:foo.mp3]";
         mid = n.model().getLong("id");
         col.addNote(n);
         // add that sound to media folder
         with open(os.path.join(col.media.dir(), "foo.mp3"), "w") as note:
             note.write("foo");
         col.close();
         // it should be imported correctly into an empty deck
         Collection empty = getEmptyCol();
         Anki2Importer imp = Anki2Importer(empty, col.path);
         imp.run();
         assertTrue(os.listdir(empty.media.dir()) == ["foo.mp3"]);
         // and importing again will not duplicate, as the file content matches
         empty.remove_cards_and_orphaned_notes(empty.getDb().list("select id from cards"));
         Anki2Importer imp = Anki2Importer(empty, col.path);
         imp.run();
         assertTrue(os.listdir(empty.media.dir()) == ["foo.mp3"]);
         Note n = empty.getNote(empty.getDb().scalar("select id from notes"));
         assertTrue("foo.mp3" in n.fields[0]);
         // if the local file content is different, and import should trigger a
         // rename
         empty.remove_cards_and_orphaned_notes(empty.getDb().list("select id from cards"));
         with open(os.path.join(empty.media.dir(), "foo.mp3"), "w") as note:
             note.write("bar");
         Anki2Importer imp = Anki2Importer(empty, col.path);
         imp.run();
         assertTrue(sorted(os.listdir(empty.media.dir())) == ["foo.mp3", "foo_%s.mp3" % mid]);
         Note n = empty.getNote(empty.getDb().scalar("select id from notes"));
         assertTrue("_" in n.fields[0]);
         // if the localized media file already exists, we rewrite the note and
         // media
         empty.remove_cards_and_orphaned_notes(empty.getDb().list("select id from cards"));
         with open(os.path.join(empty.media.dir(), "foo.mp3"), "w") as note:
             note.write("bar");
         Anki2Importer imp = Anki2Importer(empty, col.path);
         imp.run();
         assertTrue(sorted(os.listdir(empty.media.dir())) == ["foo.mp3", "foo_%s.mp3" % mid]);
         assertTrue(sorted(os.listdir(empty.media.dir())) == ["foo.mp3", "foo_%s.mp3" % mid]);
         Note n = empty.getNote(empty.getDb().scalar("select id from notes"));
         assertTrue("_" in n.fields[0]);
     }

     @Test
     public void test_apkg(){
         Collection col = getEmptyCol();
         String apkg = str(os.path.join(testDir, "support/media.apkg"));
         AnkiPackageImporter imp = AnkiPackageImporter(col, apkg);
         assertTrue(os.listdir(col.media.dir()) == []);
         imp.run();
         assertTrue(os.listdir(col.media.dir()) == ["foo.wav"]);
         // importing again should be idempotent in terms of media
         col.remove_cards_and_orphaned_notes(col.getDb().list("select id from cards"));
         AnkiPackageImporter imp = AnkiPackageImporter(col, apkg);
         imp.run();
         assertTrue(os.listdir(col.media.dir()) == ["foo.wav"]);
         // but if the local file has different data, it will rename
         col.remove_cards_and_orphaned_notes(col.getDb().list("select id from cards"));
         with open(os.path.join(col.media.dir(), "foo.wav"), "w") as note:
             note.write("xyz");
         imp = AnkiPackageImporter(col, apkg);
         imp.run();
         assertTrue(len(os.listdir(col.media.dir())) == 2);
     }

     @Test
     public void test_anki2_diffmodel_templates(){
         // different from the above as this one tests only the template text being
         // changed, not the number of cards/fields
         Collection dst = getEmptyCol();
         // import the first version of the model
         Collection col = getUpgradeDeckPath("diffmodeltemplates-1.apkg");
         AnkiPackageImporter imp = AnkiPackageImporter(dst, col);
         imp.dupeOnSchemaChange = True;
         imp.run();
         // then the version with updated template
         Collection col = getUpgradeDeckPath("diffmodeltemplates-2.apkg");
         imp = AnkiPackageImporter(dst, col);
         imp.dupeOnSchemaChange = True;
         imp.run();
         // collection should contain the note we imported
         assertTrue(dst.noteCount() == 1);
         // the front template should contain the text added in the 2nd package
         tlong cid = dst.findCards("")[0]  // only 1 note in collection
         tNote note = dst.getCard(tcid).note();
         assertTrue("Changed Front Template" in tnote.cards().get(0).template()["qfmt"]);
     }

     @Test
     public void test_anki2_updates(){
         // create a new empty deck
         dst = getEmptyCol();
         Collection col = getUpgradeDeckPath("update1.apkg");
         AnkiPackageImporter imp = AnkiPackageImporter(dst, col);
         imp.run();
         assertTrue(imp.dupes == 0);
         assertTrue(imp.added == 1);
         assertTrue(imp.updated == 0);
         // importing again should be idempotent
         imp = AnkiPackageImporter(dst, col);
         imp.run();
         assertTrue(imp.dupes == 1);
         assertTrue(imp.added == 0);
         assertTrue(imp.updated == 0);
         // importing a newer note should update
         assertTrue(dst.noteCount() == 1);
         assertTrue(dst.getDb().scalar("select flds from notes").startswith("hello"));
         Collection col = getUpgradeDeckPath("update2.apkg");
         imp = AnkiPackageImporter(dst, col);
         imp.run();
         assertTrue(imp.dupes == 0);
         assertTrue(imp.added == 0);
         assertTrue(imp.updated == 1);
         assertTrue(dst.noteCount() == 1);
         assertTrue(dst.getDb().scalar("select flds from notes").startswith("goodbye"));
     }

     @Test
     public void test_csv(){
         Collection col = getEmptyCol();
         file = str(os.path.join(testDir, "support/text-2fields.txt"));
         i = TextImporter(col, file);
         i.initMapping();
         i.run();
         // four problems - too many & too few fields, a missing front, and a
         // duplicate entry
         assertTrue(len(i.log) == 5);
         assertTrue(i.total == 5);
         // if we run the import again, it should update instead
         i.run();
         assertTrue(len(i.log) == 10);
         assertTrue(i.total == 5);
         // but importing should not clobber tags if they're unmapped
         Note n = col.getNote(col.getDb().scalar("select id from notes"));
         n.addTag("test");
         n.flush();
         i.run();
         n.load();
         assertTrue(n.tags == ["test"]);
         // if add-only mode, count will be 0
         i.importMode = 1;
         i.run();
         assertTrue(i.total == 0);
         // and if dupes mode, will reimport everything
         assertTrue(col.cardCount() == 5);
         i.importMode = 2;
         i.run();
         // includes repeated field
         assertTrue(i.total == 6);
         assertTrue(col.cardCount() == 11);
         col.close();
     }

     @Test
     public void test_csv2(){
         Collection col = getEmptyCol();
         Models mm = col.getModels();
         Model m = mm.current();
         Note note = mm.newField("Three");
         mm.addField(m, note);
         mm.save(m);
         Note n = col.newNote();
         n["Front"] = "1";
         n["Back"] = "2";
         n["Three"] = "3";
         col.addNote(n);
         // an update with unmapped fields should not clobber those fields
         file = str(os.path.join(testDir, "support/text-update.txt"));
         TextImporter i = TextImporter(col, file);
         i.initMapping();
         i.run();
         n.load();
         assertTrue(n["Front"] == "1");
         assertTrue(n["Back"] == "x");
         assertTrue(n["Three"] == "3");
         col.close();
     }

     @Test
     public void test_tsv_tag_modified(){
         Collection col = getEmptyCol();
         Models mm = col.getModels();
         Model m = mm.current();
         Note note = mm.newField("Top");
         mm.addField(m, note);
         mm.save(m);
         Note n = col.newNote();
         n["Front"] = "1";
         n["Back"] = "2";
         n["Top"] = "3";
         n.addTag("four");
         col.addNote(n);

         // https://stackoverflow.com/questions/23212435/permission-denied-to-write-to-my-temporary-file
         with NamedTemporaryFile(mode="w", delete=False) as tf:
             tf.write("1\tb\tc\n");
             tf.flush();
             TextImporter i = TextImporter(col, tf.name);
             i.initMapping();
             i.tagModified = "boom";
             i.run();
             clear_tempfile(tf);

         n.load();
         assertTrue(n["Front"] == "1");
         assertTrue(n["Back"] == "b");
         assertTrue(n["Top"] == "c");
         assertTrue("four" in n.tags);
         assertTrue("boom" in n.tags);
         assertTrue(len(n.tags) == 2);
         assertTrue(i.updateCount == 1);

         col.close();
     }

     @Test
     public void test_tsv_tag_multiple_tags(){
         Collection col = getEmptyCol();
         Models mm = col.getModels();
         Model m = mm.current();
         Note note = mm.newField("Top");
         mm.addField(m, note);
         mm.save(m);
         Note n = col.newNote();
         n["Front"] = "1";
         n["Back"] = "2";
         n["Top"] = "3";
         n.addTag("four");
         n.addTag("five");
         col.addNote(n);

         // https://stackoverflow.com/questions/23212435/permission-denied-to-write-to-my-temporary-file
         with NamedTemporaryFile(mode="w", delete=False) as tf:
             tf.write("1\tb\tc\n");
             tf.flush();
             TextImporter i = TextImporter(col, tf.name);
             i.initMapping();
             i.tagModified = "five six";
             i.run();
             clear_tempfile(tf);

         n.load();
         assertTrue(n["Front"] == "1");
         assertTrue(n["Back"] == "b");
         assertTrue(n["Top"] == "c");
         assertTrue(list(sorted(n.tags)) == list(sorted(["four", "five", "six"])));

         col.close();
     }

     @Test
     public void test_csv_tag_only_if_modified(){
         Collection col = getEmptyCol();
         Models mm = col.getModels();
         Model m = mm.current();
         Note note = mm.newField("Left");
         mm.addField(m, note);
         mm.save(m);
         Note n = col.newNote();
         n["Front"] = "1";
         n["Back"] = "2";
         n["Left"] = "3";
         col.addNote(n);

         // https://stackoverflow.com/questions/23212435/permission-denied-to-write-to-my-temporary-file
         with NamedTemporaryFile(mode="w", delete=False) as tf:
             tf.write("1,2,3\n");
             tf.flush();
             TextImporter i = TextImporter(col, tf.name);
             i.initMapping();
             i.tagModified = "right";
             i.run();
             clear_tempfile(tf);

         n.load();
         assertTrue(n.tags == []);
         assertTrue(i.updateCount == 0);

         col.close();
     }

     @pytest.mark.filterwarnings("ignore:Using or importing the ABCs")
     @Test
     public void test_supermemo_xml_01_unicode(){
         Collection col = getEmptyCol();
         String file = str(os.path.join(testDir, "support/supermemo1.xml"));
         SupermemoXmlImporter i = SupermemoXmlImporter(col, file);
         // i.META.logToStdOutput = True
         i.run();
         assertTrue(i.total == 1);
         long cid = col.getDb().scalar("select id from cards");
         Card c = col.getCard(cid);
         // Applies A Factor-to-E Factor conversion
         assertTrue(c.factor == 2879);
         assertTrue(c.reps == 7);
         col.close();
     }

     @Test
     public void test_mnemo(){
         Collection col = getEmptyCol();
         String file = str(os.path.join(testDir, "support/mnemo.getDb()"));
         MnemosyneImporter i = MnemosyneImporter(col, file);
         i.run();
         assertTrue(col.cardCount() == 7);
         assertTrue("a_longer_tag" in col.tags.all());
         assertTrue(col.getDb().queryScalar("select count() from cards where type = 0") == 1);
         col.close();
     }

     /*****************
      ** Flags        *
      *****************/

     @Test
     public void test_flags(){
         Collection col = getEmptyCol();
         Note n = col.newNote();
         n["Front"] = "one";
         n["Back"] = "two";
         int cnt = col.addNote(n);
         Card c = n.cards().get(0);
         // make sure higher bits are preserved
         int origBits = 0b101 << 3;
         c.flags = origBits;
         c.flush();
         // no flags to start with
         assertTrue(c.userFlag() == 0);
         assertTrue(len(col.findCards("flag:0")) == 1);
         assertTrue(len(col.findCards("flag:1")) == 0);
         // set flag 2
         col.setUserFlag(2, [c.getId()]);
         c.load();
         assertTrue(c.userFlag() == 2);
         assertTrue(c.flags & origBits == origBits);
         assertTrue(len(col.findCards("flag:0")) == 0);
         assertTrue(len(col.findCards("flag:2")) == 1);
         assertTrue(len(col.findCards("flag:3")) == 0);
         // change to 3
         col.setUserFlag(3, [c.getId()]);
         c.load();
         assertTrue(c.userFlag() == 3);
         // unset
         col.setUserFlag(0, [c.getId()]);
         c.load();
         assertTrue(c.userFlag() == 0);

         // should work with Cards method as well
         c.setUserFlag(2);
         assertTrue(c.userFlag() == 2);
         c.setUserFlag(3);
         assertTrue(c.userFlag() == 3);
         c.setUserFlag(0);
         assertTrue(c.userFlag() == 0);
     }
     /*****************
      ** Media        *
      *****************/
     // copying files to media folder

     @Test
     public void test_add(){
         Collection col = getEmptyCol();
         String dir = tempfile.mkdtemp(prefix="anki");
         String path = os.path.join(dir, "foo.jpg");
         with open(path, "w") as note:
             note.write("hello");
         // new file, should preserve name
             assertTrue(col.media.addFile(path) == "foo.jpg");
         // adding the same file again should not create a duplicate
             assertTrue(col.media.addFile(path) == "foo.jpg");
         // but if it has a different sha1, it should
         with open(path, "w") as note:
             note.write("world");
             assertTrue(col.media.addFile(path) == "foo-7c211433f02071597741e6ff5a8ea34789abbf43.jpg");
     }

     @Test
     public void test_strings(){
         Collection col = getEmptyCol();
         mf = col.media.filesInStr;
         mid = col.getModels().current().getLong("id");
         assertTrue(mf(mid, "aoeu") == []);
         assertTrue(mf(mid, "aoeu<img src='foo.jpg'>ao") == ["foo.jpg"]);
         assertTrue(mf(mid, "aoeu<img src='foo.jpg' style='test'>ao") == ["foo.jpg"]);
         assertTrue(mf(mid, "aoeu<img src='foo.jpg'><img src=\"bar.jpg\">ao") == [);
             "foo.jpg",;
             "bar.jpg",;
     ];
         assertTrue(mf(mid, "aoeu<img src=foo.jpg style=bar>ao") == ["foo.jpg"]);
         assertTrue(mf(mid, "<img src=one><img src=two>") == ["one", "two"]);
         assertTrue(mf(mid, 'aoeu<img src="foo.jpg">ao') == ["foo.jpg"]);
         assertTrue(mf(mid, 'aoeu<img src="foo.jpg"><img class=yo src=fo>ao') == [);
             "foo.jpg",;
             "fo",;
     ];
         assertTrue(mf(mid, "aou[sound:foo.mp3]aou") == ["foo.mp3"]);
         sp = col.media.strip;
         assertTrue(sp("aoeu") == "aoeu");
         assertTrue(sp("aoeu[sound:foo.mp3]aoeu") == "aoeuaoeu");
         assertTrue(sp("a<img src=yo>oeu") == "aoeu");
         es = col.media.escapeImages;
         assertTrue(es("aoeu") == "aoeu");
         assertTrue(es("<img src='http://foo.com'>") == "<img src='http://foo.com'>");
         assertTrue(es('<img src="foo bar.jpg">') == '<img src="foo%20bar.jpg">');
     }

     @Test
     public void test_deckIntegration(){
         Collection col = getEmptyCol();
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
         assertTrue(ret.missing == ["fake2.png"]);
         assertTrue(ret.unused == ["foo.jpg"]);
     }
     /*****************
      ** Models       *
      *****************/

     @Test
     public void test_modelDelete(){
         Collection col = getEmptyCol();
         Note note = col.newNote();
         note.setItem("Front","1");
         note.setItem("Back","2");
         col.addNote(note);
         assertTrue(col.cardCount() == 1);
         col.getModels().rem(col.getModels().current());
         assertTrue(col.cardCount() == 0);
     }

     @Test
     public void test_modelCopy(){
         Collection col = getEmptyCol();
         Model m = col.getModels().current();
         Model m2 = col.getModels().copy(m);
         assertTrue(m2["name"] == "Basic copy");
         assertTrue(m2.getLong("id") != m.getLong("id"));
         assertTrue(len(m2["flds"]) == 2);
         assertTrue(len(m["flds"]) == 2);
         assertTrue(len(m2["flds"]) == len(m["flds"]));
         assertTrue(len(m["tmpls"]) == 1);
         assertTrue(len(m2["tmpls"]) == 1);
         assertTrue(col.getModels().scmhash(m) == col.getModels().scmhash(m2));
     }

     @Test
     public void test_fields(){
         Collection col = getEmptyCol();
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
         assertTrue(col.getNote(col.getModels().nids(m)[0]).fields == ["1", "2", ""]);
         assertTrue(col.getModels().scmhash(m) != h);
         // rename it
         Note note = m["flds"][2];
         col.getModels().renameField(m, note, "bar");
         assertTrue(col.getNote(col.getModels().nids(m)[0])["bar"] == "");
         // delete back
         col.getModels().remField(m, m["flds"][1]);
         assertTrue(col.getNote(col.getModels().nids(m)[0]).fields == ["1", ""]);
         // move 0 -> 1
         col.getModels().moveField(m, m["flds"][0], 1);
         assertTrue(col.getNote(col.getModels().nids(m)[0]).fields == ["", "1"]);
         // move 1 -> 0
         col.getModels().moveField(m, m["flds"][1], 0);
         assertTrue(col.getNote(col.getModels().nids(m)[0]).fields == ["1", ""]);
         // add another and put in middle
         Note note = col.getModels().newField("baz");
         col.getModels().addField(m, note);
         Note note = col.getNote(col.getModels().nids(m)[0]);
         note.setItem("baz","2");
         note.flush();
         assertTrue(col.getNote(col.getModels().nids(m)[0]).fields == ["1", "", "2"]);
         // move 2 -> 1
         col.getModels().moveField(m, m["flds"][2], 1);
         assertTrue(col.getNote(col.getModels().nids(m)[0]).fields == ["1", "2", ""]);
         // move 0 -> 2
         col.getModels().moveField(m, m["flds"][0], 2);
         assertTrue(col.getNote(col.getModels().nids(m)[0]).fields == ["2", "", "1"]);
         // move 0 -> 1
         col.getModels().moveField(m, m["flds"][0], 1);
         assertTrue(col.getNote(col.getModels().nids(m)[0]).fields == ["", "2", "1"]);
     }

     @Test
     public void test_templates(){
         Collection col = getEmptyCol();
         Model m = col.getModels().current();
         Models mm = col.getModels();
         JSONObject t = mm.newTemplate("Reverse");
         t["qfmt"] = "{{Back}}";
         t["afmt"] = "{{Front}}";
         mm.addTemplate(m, t);
         mm.save(m);
         Note note = col.newNote();
         note.setItem("Front","1");
         note.setItem("Back","2");
         col.addNote(note);
         assertTrue(col.cardCount() == 2);
         (c, c2) = note.cards();
         // first card should have first ord
         assertTrue(c.ord == 0);
         assertTrue(c2.ord == 1);
         // switch templates
         col.getModels().moveTemplate(m, c.template(), 1);
         c.load();
         c2.load();
         assertTrue(c.ord == 1);
         assertTrue(c2.ord == 0);
         // removing a template should delete its cards
         col.getModels().remTemplate(m, m["tmpls"][0]);
         assertTrue(col.cardCount() == 1);
         // and should have updated the other cards' ordinals
         Card c = note.cards().get(0);
         assertTrue(c.ord == 0);
         assertTrue(stripHTML(c.q()) == "1");
         // it shouldn't be possible to orphan notes by removing templates
         JSONObject t = mm.newTemplate("template name");
         mm.addTemplate(m, t);
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
         Collection col = getEmptyCol();
         col.getModels().setCurrent(col.getModels().byName("Cloze"));
         Model m = col.getModels().current();
         Models mm = col.getModels();

         // We replace the default Cloze template
         JSONObject t = mm.newTemplate("ChainedCloze");
         t["qfmt"] = "{{text:cloze:Text}}";
         t["afmt"] = "{{text:cloze:Text}}";
         mm.addTemplate(m, t);
         mm.save(m);
         col.getModels().remTemplate(m, m["tmpls"][0]);

         Note note = col.newNote();
         note.setItem("Text","{{c1::firstQ::firstA}}{{c2::secondQ::secondA}}");
         col.addNote(note);
         assertTrue(col.cardCount() == 2);
         (c, c2) = note.cards();
         // first card should have first ord
         assertTrue(c.ord == 0);
         assertTrue(c2.ord == 1);
     }

     @Test
     public void test_text(){
         Collection col = getEmptyCol();
         Model m = col.getModels().current();
         m["tmpls"][0]["qfmt"] = "{{text:Front}}";
         col.getModels().save(m);
         Note note = col.newNote();
         note.setItem("Front","hello<b>world");
         col.addNote(note);
         assertTrue("helloworld" in note.cards().get(0).q());
     }

     @Test
     public void test_cloze(){
         Collection col = getEmptyCol();
         col.getModels().setCurrent(col.getModels().byName("Cloze"));
         Note note = col.newNote();
         assertTrue(note.model()["name"] == "Cloze");
         // a cloze model with no clozes is not empty
         note.setItem("Text","nothing");
         assertTrue(col.addNote(note));
         // try with one cloze
         Note note = col.newNote();
         note.setItem("Text","hello {{c1::world}}");
         assertTrue(col.addNote(note) == 1);
         assertTrue("hello <span class=cloze>[...]</span>" in note.cards().get(0).q());
         assertTrue("hello <span class=cloze>world</span>" in note.cards().get(0).a());
         // and with a comment
         Note note = col.newNote();
         note.setItem("Text","hello {{c1::world::typical}}");
         assertTrue(col.addNote(note) == 1);
         assertTrue("<span class=cloze>[typical]</span>" in note.cards().get(0).q());
         assertTrue("<span class=cloze>world</span>" in note.cards().get(0).a());
         // and with 2 clozes
         Note note = col.newNote();
         note.setItem("Text","hello {{c1::world}} {{c2::bar}}");
         assertTrue(col.addNote(note) == 2);
         (c1, c2) = note.cards();
         assertTrue("<span class=cloze>[...]</span> bar" in c1.q());
         assertTrue("<span class=cloze>world</span> bar" in c1.a());
         assertTrue("world <span class=cloze>[...]</span>" in c2.q());
         assertTrue("world <span class=cloze>bar</span>" in c2.a());
         // if there are multiple answers for a single cloze, they are given in a
         // list
         Note note = col.newNote();
         note.setItem("Text","a {{c1::b}} {{c1::c}}");
         assertTrue(col.addNote(note) == 1);
         assertTrue("<span class=cloze>b</span> <span class=cloze>c</span>" in ();
             note.cards().get(0).a();
     );
         // if we add another cloze, a card should be generated
         cnt = col.cardCount();
         note.setItem("Text","{{c2::hello}} {{c1::foo}}");
         note.flush();
         assertTrue(col.cardCount() == cnt + 1);
         // 0 or negative indices are not supported
         note.setItem("Text","{{c0::zero}} {{c-1:foo}}");
         note.flush();
         assertTrue(len(note.cards()) == 2);
     }

     @Test
     public void test_cloze_mathjax(){
         Collection col = getEmptyCol();
         col.getModels().setCurrent(col.getModels().byName("Cloze"));
         Note note = col.newNote();
         note[;
             "Text";
         ] = r"{{c1::ok}} \(2^2\) {{c2::not ok}} \(2^{{c3::2}}\) \(x^3\) {{c4::blah}} {{c5::text with \(x^2\) jax}}";
         assertTrue(col.addNote(note));
         assertTrue(len(note.cards()) == 5);
         assertTrue("class=cloze" in note.cards().get(0).q());
         assertTrue("class=cloze" in note.cards()[1].q());
         assertTrue("class=cloze" not in note.cards()[2].q());
         assertTrue("class=cloze" in note.cards()[3].q());
         assertTrue("class=cloze" in note.cards()[4].q());

         Note note = col.newNote();
         note.setItem("Text","\(a\) {{c1::b}} \[ {{c1::c}} \]");
         assertTrue(col.addNote(note));
         assertTrue(len(note.cards()) == 1);
         assertTrue(();
             note.cards().get(0);
             .q();
             .endswith(r"\(a\) <span class=cloze>[...]</span> \[ [...] \]");
     );
     }

     @Test
     public void test_typecloze(){
         Collection col = getEmptyCol();
         Model m = col.getModels().byName("Cloze");
         col.getModels().setCurrent(m);
         m["tmpls"][0]["qfmt"] = "{{cloze:Text}}{{type:cloze:Text}}";
         col.getModels().save(m);
         Note note = col.newNote();
         note.setItem("Text","hello {{c1::world}}");
         col.addNote(note);
         assertTrue("[[type:cloze:Text]]" in note.cards().get(0).q());
     }

     @Test
     public void test_chained_mods(){
         Collection col = getEmptyCol();
         col.getModels().setCurrent(col.getModels().byName("Cloze"));
         Model m = col.getModels().current();
         Models mm = col.getModels();

         // We replace the default Cloze template
         JSONObject t = mm.newTemplate("ChainedCloze");
         t["qfmt"] = "{{cloze:text:Text}}";
         t["afmt"] = "{{cloze:text:Text}}";
         mm.addTemplate(m, t);
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
         assertTrue(col.addNote(note) == 1);
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
         Collection col = getEmptyCol();
         Model cloze = col.getModels().byName("Cloze");
         // enable second template and add a note
         Model m = col.getModels().current();
         Models mm = col.getModels();
         JSONObject t = mm.newTemplate("Reverse");
         t["qfmt"] = "{{Back}}";
         t["afmt"] = "{{Front}}";
         mm.addTemplate(m, t);
         mm.save(m);
         basiCard c = m;
         Note note = col.newNote();
         note.setItem("Front","note");
         note.setItem("Back","b123");
         col.addNote(note);
         // switch fields
         map = {0: 1, 1: 0}
         col.getModels().change(basic, [note.getId()], basic, map, None);
         note.load();
         assertTrue(note.setItem("Front","b123"));
         assertTrue(note.setItem("Back","note"));
         // switch cards
         Card c0 = note.cards().get(0);
         Card c1 = note.cards()[1];
         assertTrue("b123" in c0.q());
         assertTrue("note" in c1.q());
         assertTrue(c0.ord == 0);
         assertTrue(c1.ord == 1);
         col.getModels().change(basic, [note.getId()], basic, None, map);
         note.load();
         c0.load();
         c1.load();
         assertTrue("note" in c0.q());
         assertTrue("b123" in c1.q());
         assertTrue(c0.ord == 1);
         assertTrue(c1.ord == 0);
         // .cards() returns cards in order
         assertTrue(note.cards().get(0).getId() == c1.getId());
         // delete first card
         map = {0: None, 1: 1}
         if isWin:
             // The low precision timer on Windows reveals a race condition
             time.sleep(0.05);
         col.getModels().change(basic, [note.getId()], basic, None, map);
         note.load();
         c0.load();
         // the card was deleted
         try:
             c1.load();
             assertTrue(0);
         except NotFoundError:
             pass;
         // but we have two cards, as a new one was generated
             assertTrue(len(note.cards()) == 2);
         // an unmapped field becomes blank
             assertTrue(note.setItem("Front","b123"));
             assertTrue(note.setItem("Back","note"));
         col.getModels().change(basic, [note.getId()], basic, map, None);
         note.load();
         assertTrue(note.setItem("Front",""));
         assertTrue(note.setItem("Back","note"));
         // another note to try model conversion
         Note note = col.newNote();
         note.setItem("Front","f2");
         note.setItem("Back","b2");
         col.addNote(note);
         counts = col.getModels().all_use_counts();
         assertTrue(next(c.use_count for c in counts if c.name == "Basic") == 2);
         assertTrue(next(c.use_count for c in counts if c.name == "Cloze") == 0);
         map = {0: 0, 1: 1}
         col.getModels().change(basic, [note.getId()], cloze, map, map);
         note.load();
         assertTrue(note.setItem("Text","f2"));
         assertTrue(len(note.cards()) == 2);
         // back the other way, with deletion of second ord
         col.getModels().remTemplate(basic, basic["tmpls"][1]);
         assertTrue(col.getDb().queryScalar("select count() from cards where nid = ?", note.getId()) == 2);
         map = {0: 0}
         col.getModels().change(cloze, [note.getId()], basic, map, map);
         assertTrue(col.getDb().queryScalar("select count() from cards where nid = ?", note.getId()) == 1);
     }

     @Test
     public void test_req(){
         def reqSize(model):
             if model["type"] == MODEL_CLOZE:
                 return;
                 assertTrue(len(model["tmpls"]) == len(model["req"]));

         Collection col = getEmptyCol();
         Models mm = col.getModels();
         basiCard c = mm.byName("Basic");
         assertTrue("req" in basic);
         reqSize(basic);
         r = basic["req"][0];
         assertTrue(r[0] == 0);
         assertTrue(r[1] in ("any", "all"));
         assertTrue(r[2] == [0]);
         opt = mm.byName("Basic (optional reversed card)");
         reqSize(opt);
         r = opt["req"][0];
         assertTrue(r[1] in ("any", "all"));
         assertTrue(r[2] == [0]);
         assertTrue(opt["req"][1] == [1, "all", [1, 2]]);
         // testing any
         opt["tmpls"][1]["qfmt"] = "{{Back}}{{Add Reverse}}";
         mm.save(opt, templates=True);
         assertTrue(opt["req"][1] == [1, "any", [1, 2]]);
         // testing None
         opt["tmpls"][1]["qfmt"] = "{{^Add Reverse}}{{/Add Reverse}}";
         mm.save(opt, templates=True);
         assertTrue(opt["req"][1] == [1, "none", []]);

         opt = mm.byName("Basic (type in the answer)");
         reqSize(opt);
         r = opt["req"][0];
         assertTrue(r[1] in ("any", "all"));
         assertTrue(r[2] == [0, 1]);
     }

     /*****************
          ** SchedV1      *
      *****************/
     private Collection getEmptyCol(){
         Collection col = getEmptyColOrig();
         col.changeSchedulerVer(1);
         return col;
     }

     @Test
     public void test_clock(){
         Collection col = getEmptyCol();
         if (col.getSched().dayCutoff - intTime()) < 10 * 60:
             raise Exception("Unit tests will fail around the day rollover.");
     }

     private boolean checkRevIvl(col, c, targetIvl) {
         min, max = col.getSched()._fuzzIvlRange(targetIvl);
         return min <= c.ivl <= max;
     }

     @Test
     public void test_basics(){
         Collection col = getEmptyCol();
         col.reset();
         assertTrue(not col.getSched().getCard());
     }

     @Test
     public void test_new(){
         Collection col = getEmptyCol();
         col.reset();
         assertTrue(col.getSched().newCount == 0);
         // add a note
         Note note = col.newNote();
         note.setItem("Front","one");
         note.setItem("Back","two");
         col.addNote(note);
         col.reset();
         assertTrue(col.getSched().newCount == 1);
         // fetch it
         Card c = col.getSched().getCard();
         assertTrue(c);
         assertTrue(c.queue == QUEUE_TYPE_NEW);
         assertTrue(c.type == CARD_TYPE_NEW);
         // if we answer it, it should become a learn card
         JSONObject t = intTime();
         col.getSched().answerCard(c, 1);
         assertTrue(c.queue == QUEUE_TYPE_LRN);
         assertTrue(c.type == CARD_TYPE_LRN);
         assertTrue(c.due >= t);

         // disabled for now, as the learn fudging makes this randomly fail
         // // the default order should ensure siblings are not seen together, and
         // // should show all cards
         // Model m = col.getModels().current(); Models mm = col.getModels()
         // JSONObject t = mm.newTemplate("Reverse")
         // t['qfmt'] = "{{Back}}"
         // t['afmt'] = "{{Front}}"
         // mm.addTemplate(m, t)
         // mm.save(m)
         // Note note = col.newNote()
         // note['Front'] = u"2"; note['Back'] = u"2"
         // col.addNote(note)
         // Note note = col.newNote()
         // note['Front'] = u"3"; note['Back'] = u"3"
         // col.addNote(note)
         // col.reset()
         // qs = ("2", "3", "2", "3")
         // for n in range(4):
         //     Card c = col.getSched().getCard()
         //     assertTrue(qs[n] in c.q())
         //     col.getSched().answerCard(c, 2)
     }

     @Test
     public void test_newLimits(){
         Collection col = getEmptyCol();
         // add some notes
         deck2 = col.decks.getId()("Default::foo");
         for i in range(30):
             Note note = col.newNote();
         note.setItem("Front","did")] = deck2;
             col.addNote(note);
         // give the child deck a different configuration
         c2 = col.decks.add_config_returning_id("new conf");
         col.decks.setConf(col.decks.get(deck2), c2);
         col.reset();
         // both confs have defaulted to a limit of 20
         assertTrue(col.getSched().newCount == 20);
         // first card we get comes from parent
         Card c = col.getSched().getCard();
         assertTrue(c.long did == 1);
         // limit the parent to 10 cards, meaning we get 10 in total
         conf1 = col.decks.confForDid(1);
         conf1["new"]["perDay"] = 10;
         col.decks.save(conf1);
         col.reset();
         assertTrue(col.getSched().newCount == 10);
         // if we limit child to 4, we should get 9
         conf2 = col.decks.confForDid(deck2);
         conf2["new"]["perDay"] = 4;
         col.decks.save(conf2);
         col.reset();
         assertTrue(col.getSched().newCount == 9);
     }

     @Test
     public void test_newBoxes(){
         Collection col = getEmptyCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         col.reset();
         Card c = col.getSched().getCard();
         conf = col.getSched()._cardConf(c);
         conf["new"]["delays"] = [1, 2, 3, 4, 5];
         col.decks.save(conf);
         col.getSched().answerCard(c, 2);
         // should handle gracefully
         conf["new"]["delays"] = [1];
         col.decks.save(conf);
         col.getSched().answerCard(c, 2);
     }

     @Test
     public void test_learn(){
         Collection col = getEmptyCol();
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
         conf["new"]["delays"] = [0.5, 3, 10];
         col.decks.save(conf);
         // fail it
         col.getSched().answerCard(c, 1);
         // it should have three reps left to graduation
         assertTrue(c.left % 1000 == 3);
         assertTrue(c.left // 1000 == )3
         // it should by due in 30 seconds
         JSONObject t = round(c.due - time.time());
                assertTrue(t >= 25 and t <= 40);
         // pass it once
         col.getSched().answerCard(c, 2);
         // it should by due in 3 minutes
                assertTrue(round(c.due - time.time()) in (179, 180));
                assertTrue(c.left % 1000 == 2);
                assertTrue(c.left // 1000 == )2
         // check log is accurate
         log = col.getDb().first("select * from revlog order by id desc");
                       assertTrue(log[3] == 2);
                       assertTrue(log[4] == -180);
                       assertTrue(log[5] == -30);
         // pass again
         col.getSched().answerCard(c, 2);
         // it should by due in 10 minutes
                       assertTrue(round(c.due - time.time()) in (599, 600));
                       assertTrue(c.left % 1000 == 1);
                       assertTrue(c.left // 1000 == )1
         // the next pass should graduate the card
                              assertTrue(c.queue == QUEUE_TYPE_LRN);
                              assertTrue(c.type == CARD_TYPE_LRN);
         col.getSched().answerCard(c, 2);
                              assertTrue(c.queue == QUEUE_TYPE_REV);
                              assertTrue(c.type == CARD_TYPE_REV);
         // should be due tomorrow, with an interval of 1
                              assertTrue(c.due == col.getSched().today + 1);
                              assertTrue(c.ivl == 1);
         // or normal removal
         c.type = 0;
         c.queue = 1;
         col.getSched().answerCard(c, 3);
                              assertTrue(c.type == CARD_TYPE_REV);
                              assertTrue(c.queue == QUEUE_TYPE_REV);
                              assertTrue(checkRevIvl(col, c, 4));
         // revlog should have been updated each time
                              assertTrue(col.getDb().queryScalar("select count() from revlog where type = 0") == 5);
         // now failed card handling
         c.type = CARD_TYPE_REV;
         c.queue = 1;
         c.odue = 123;
         col.getSched().answerCard(c, 3);
                              assertTrue(c.due == 123);
                              assertTrue(c.type == CARD_TYPE_REV);
                              assertTrue(c.queue == QUEUE_TYPE_REV);
         // we should be able to remove manually, too
         c.type = CARD_TYPE_REV;
         c.queue = 1;
         c.odue = 321;
         c.flush();
         col.getSched().removeLrn();
         c.load();
                              assertTrue(c.queue == QUEUE_TYPE_REV);
                              assertTrue(c.due == 321);
     }

     @Test
     public void test_learn_collapsed(){
         Collection col = getEmptyCol();
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
         assertTrue(not c.q().endswith("2"));
     }

     @Test
     public void test_learn_day(){
         Collection col = getEmptyCol();
         // add a note
         Note note = col.newNote();
         note.setItem("Front","one");
         Note note = col.addNote(note);
         col.getSched().reset();
         Card c = col.getSched().getCard();
         conf = col.getSched()._cardConf(c);
         conf["new"]["delays"] = [1, 10, 1440, 2880];
         col.decks.save(conf);
         // pass it
         col.getSched().answerCard(c, 2);
         // two reps to graduate, 1 more today
         assertTrue(c.left % 1000 == 3);
         assertTrue(c.left // 1000 == )1
                assertTrue(col.getSched().counts() == (0, 1, 0));
         Card c = col.getSched().getCard();
         ni = col.getSched().nextIvl;
                assertTrue(ni(c, 2) == 86400);
         // answering it will place it in queue 3
         col.getSched().answerCard(c, 2);
                assertTrue(c.due == col.getSched().today + 1);
                assertTrue(c.queue == CARD_TYPE_RELEARNING);
                assertTrue(not col.getSched().getCard());
         // for testing, move it back a day
         c.due -= 1;
         c.flush();
         col.reset();
                assertTrue(col.getSched().counts() == (0, 1, 0));
         Card c = col.getSched().getCard();
         // nextIvl should work
                assertTrue(ni(c, 2) == 86400 * 2);
         // if we fail it, it should be back in the correct queue
         col.getSched().answerCard(c, 1);
                assertTrue(c.queue == QUEUE_TYPE_LRN);
         col.undo();
         col.reset();
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 2);
         // simulate the passing of another two days
         c.due -= 2;
         c.flush();
         col.reset();
         // the last pass should graduate it into a review card
                assertTrue(ni(c, 2) == 86400);
         col.getSched().answerCard(c, 2);
                assertTrue(c.queue == CARD_TYPE_REV and c.type == QUEUE_TYPE_REV);
         // if the lapse step is tomorrow, failing it should handle the counts
         // correctly
         c.due = 0;
         c.flush();
         col.reset();
                assertTrue(col.getSched().counts() == (0, 0, 1));
         conf = col.getSched()._cardConf(c);
         conf["lapse"]["delays"] = [1440];
         col.decks.save(conf);
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 1);
                assertTrue(c.queue == CARD_TYPE_RELEARNING);
                assertTrue(col.getSched().counts() == (0, 0, 0));
     }

     @Test
     public void test_reviews(){
         Collection col = getEmptyCol();
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
         conf["lapse"]["delays"] = [2, 20];
         col.decks.save(conf);
         col.getSched().answerCard(c, 1);
         assertTrue(c.queue == QUEUE_TYPE_LRN);
         // it should be due tomorrow, with an interval of 1
         assertTrue(c.odue == col.getSched().today + 1);
         assertTrue(c.ivl == 1);
         // but because it's in the learn queue, its current due time should be in
         // the future
         assertTrue(c.due >= time.time());
         assertTrue((c.due - time.time()) > 118);
         // factor should have been decremented
         assertTrue(c.factor == 2300);
         // check counters
         assertTrue(c.lapses == 2);
         assertTrue(c.reps == 4);
         // check ests.
         ni = col.getSched().nextIvl;
         assertTrue(ni(c, 1) == 120);
         assertTrue(ni(c, 2) == 20 * 60);
         // try again with an ease of 2 instead
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         Card c = copy.copy(cardcopy);
         c.flush();
         col.getSched().answerCard(c, 2);
         assertTrue(c.queue == QUEUE_TYPE_REV);
         // the new interval should be (100 + 8/4) * 1.2 = 122
         assertTrue(checkRevIvl(col, c, 122));
         assertTrue(c.due == col.getSched().today + c.ivl);
         // factor should have been decremented
         assertTrue(c.factor == 2350);
         // check counters
         assertTrue(c.lapses == 1);
         assertTrue(c.reps == 4);
         // ease 3
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         Card c = copy.copy(cardcopy);
         c.flush();
         col.getSched().answerCard(c, 3);
         // the new interval should be (100 + 8/2) * 2.5 = 260
         assertTrue(checkRevIvl(col, c, 260));
         assertTrue(c.due == col.getSched().today + c.ivl);
         // factor should have been left alone
         assertTrue(c.factor == STARTING_FACTOR);
         // ease 4
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         Card c = copy.copy(cardcopy);
         c.flush();
         col.getSched().answerCard(c, 4);
         // the new interval should be (100 + 8) * 2.5 * 1.3 = 351
         assertTrue(checkRevIvl(col, c, 351));
         assertTrue(c.due == col.getSched().today + c.ivl);
         // factor should have been increased
         assertTrue(c.factor == 2650);
     }

     @Test
     public void test_button_spacing(){
         Collection col = getEmptyCol();
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
         assertTrue(wo(ni(c, 2)) == "2d");
         assertTrue(wo(ni(c, 3)) == "3d");
         assertTrue(wo(ni(c, 4)) == "4d");
     }

     @Test
     public void test_overdue_lapse(){
         // disabled in commit 3069729776990980f34c25be66410e947e9d51a2
         return;
         Collection col = getEmptyCol()  // pylint: disable=unreachable
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
         col.getSched()._clearOverdue = False;
         // checkpoint
         col.save();
         col.getSched().reset();
         assertTrue(col.getSched().counts() == (0, 2, 0));
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 3);
         // it should be due tomorrow
         assertTrue(c.due == col.getSched().today + 1);
         // revert to before
         col.rollback();
         col.getSched()._clearOverdue = True;
         // with the default settings, the overdue card should be removed from the
         // learning queue
         col.getSched().reset();
         assertTrue(col.getSched().counts() == (0, 0, 1));
     }

     @Test
     public void test_finished(){
         Collection col = getEmptyCol();
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
         Collection col = getEmptyCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         note.setItem("Back","two");
         col.addNote(note);
         col.reset();
         conf = col.decks.confForDid(1);
         conf["new"]["delays"] = [0.5, 3, 10];
         conf["lapse"]["delays"] = [1, 5, 9];
         col.decks.save(conf);
         Card c = col.getSched().getCard();
         // new cards
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         ni = col.getSched().nextIvl;
         assertTrue(ni(c, 1) == 30);
         assertTrue(ni(c, 2) == 180);
         assertTrue(ni(c, 3) == 4 * 86400);
         col.getSched().answerCard(c, 1);
         // cards in learning
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         assertTrue(ni(c, 1) == 30);
         assertTrue(ni(c, 2) == 180);
         assertTrue(ni(c, 3) == 4 * 86400);
         col.getSched().answerCard(c, 2);
         assertTrue(ni(c, 1) == 30);
         assertTrue(ni(c, 2) == 600);
         assertTrue(ni(c, 3) == 4 * 86400);
         col.getSched().answerCard(c, 2);
         // normal graduation is tomorrow
         assertTrue(ni(c, 2) == 1 * 86400);
         assertTrue(ni(c, 3) == 4 * 86400);
         // lapsed cards
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         c.type = CARD_TYPE_REV;
         c.ivl = 100;
         c.factor = STARTING_FACTOR;
         assertTrue(ni(c, 1) == 60);
         assertTrue(ni(c, 2) == 100 * 86400);
         assertTrue(ni(c, 3) == 100 * 86400);
         // review cards
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         c.queue = QUEUE_TYPE_REV;
         c.ivl = 100;
         c.factor = STARTING_FACTOR;
         // failing it should put it at 60s
         assertTrue(ni(c, 1) == 60);
         // or 1 day if relearn is false
         conf["lapse"]["delays"] = [];
         col.decks.save(conf);
         assertTrue(ni(c, 1) == 1 * 86400);
         // (* 100 1.2 86400)10368000.0
         assertTrue(ni(c, 2) == 10368000);
         // (* 100 2.5 86400)21600000.0
         assertTrue(ni(c, 3) == 21600000);
         // (* 100 2.5 1.3 86400)28080000.0
         assertTrue(ni(c, 4) == 28080000);
         assertTrue(without_unicode_isolation(col.getSched().nextIvlStr(c, 4)) == "10.8mo");
     }

     @Test
     public void test_misc(){
         Collection col = getEmptyCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         Card c = note.cards().get(0);
         // burying
         col.getSched().buryNote(c.nid);
         col.reset();
         assertTrue(not col.getSched().getCard());
         col.getSched().unburyCards();
         col.reset();
         assertTrue(col.getSched().getCard());
     }

     @Test
     public void test_suspend(){
         Collection col = getEmptyCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         Card c = note.cards().get(0);
         // suspending
         col.reset();
         assertTrue(col.getSched().getCard());
         col.getSched().suspendCards([c.getId()]);
         col.reset();
         assertTrue(not col.getSched().getCard());
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
         assertTrue(c.queue == QUEUE_TYPE_LRN);
         assertTrue(c.type == CARD_TYPE_REV);
         col.getSched().suspendCards([c.getId()]);
         col.getSched().unsuspendCards([c.getId()]);
         c.load();
         assertTrue(c.queue == QUEUE_TYPE_REV);
         assertTrue(c.type == CARD_TYPE_REV);
         assertTrue(c.due == 1);
         // should cope with cards in cram decks
         c.due = 1;
         c.flush();
         cram = col.decks.newDyn("tmp");
         col.getSched().rebuildDyn();
         c.load();
         assertTrue(c.due != 1);
         assertTrue(c.did != 1);
         col.getSched().suspendCards([c.getId()]);
         c.load();
         assertTrue(c.due == 1);
         assertTrue(c.long did == 1);
     }

     @Test
     public void test_cram(){
         Collection col = getEmptyCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         Card c = note.cards().get(0);
         c.ivl = 100;
         c.queue = CARD_TYPE_REV;
         c.type = QUEUE_TYPE_REV;
         // due in 25 days, so it's been waiting 75 days
         c.due = col.getSched().today + 25;
         c.mod = 1;
         c.factor = STARTING_FACTOR;
         c.startTimer();
         c.flush();
         col.reset();
         assertTrue(col.getSched().counts() == (0, 0, 0));
         cardcopy = copy.copy(c);
         // create a dynamic deck and refresh it
         long did = col.decks.newDyn("Cram");
         col.getSched().rebuildDyn(did);
         col.reset();
         // should appear as new in the deck list
         assertTrue(sorted(col.getSched().deck_due_tree().children)[0].new_count == 1);
         // and should appear in the counts
         assertTrue(col.getSched().counts() == (1, 0, 0));
         // grab it and check estimates
         Card c = col.getSched().getCard();
         assertTrue(col.getSched().answerButtons(c) == 2);
         assertTrue(col.getSched().nextIvl(c, 1) == 600);
         assertTrue(col.getSched().nextIvl(c, 2) == 138 * 60 * 60 * 24);
         cram = col.decks.get(did);
         cram["delays"] = [1, 10];
         col.decks.save(cram);
         assertTrue(col.getSched().answerButtons(c) == 3);
         assertTrue(col.getSched().nextIvl(c, 1) == 60);
         assertTrue(col.getSched().nextIvl(c, 2) == 600);
         assertTrue(col.getSched().nextIvl(c, 3) == 138 * 60 * 60 * 24);
         col.getSched().answerCard(c, 2);
         // elapsed time was 75 days
         // factor = 2.5+1.2/2 = 1.85
         // int(75*1.85) = 138
         assertTrue(c.ivl == 138);
         assertTrue(c.odue == 138);
         assertTrue(c.queue == QUEUE_TYPE_LRN);
         // should be logged as a cram rep
         assertTrue(col.getDb().scalar("select type from revlog order by id desc limit 1") == 3);
         // check ivls again
         assertTrue(col.getSched().nextIvl(c, 1) == 60);
         assertTrue(col.getSched().nextIvl(c, 2) == 138 * 60 * 60 * 24);
         assertTrue(col.getSched().nextIvl(c, 3) == 138 * 60 * 60 * 24);
         // when it graduates, due is updated
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 2);
         assertTrue(c.ivl == 138);
         assertTrue(c.due == 138);
         assertTrue(c.queue == QUEUE_TYPE_REV);
         // and it will have moved back to the previous deck
         assertTrue(c.long did == 1);
         // cram the deck again
         col.getSched().rebuildDyn(did);
         col.reset();
         Card c = col.getSched().getCard();
         // check ivls again - passing should be idempotent
         assertTrue(col.getSched().nextIvl(c, 1) == 60);
         assertTrue(col.getSched().nextIvl(c, 2) == 600);
         assertTrue(col.getSched().nextIvl(c, 3) == 138 * 60 * 60 * 24);
         col.getSched().answerCard(c, 2);
         assertTrue(c.ivl == 138);
         assertTrue(c.odue == 138);
         // fail
         col.getSched().answerCard(c, 1);
         assertTrue(col.getSched().nextIvl(c, 1) == 60);
         assertTrue(col.getSched().nextIvl(c, 2) == 600);
         assertTrue(col.getSched().nextIvl(c, 3) == 86400);
         // delete the deck, returning the card mid-study
         col.decks.rem(col.decks.selected());
         assertTrue(len(col.getSched().deck_due_tree().children) == 1);
         c.load();
         assertTrue(c.ivl == 1);
         assertTrue(c.due == col.getSched().today + 1);
         // make it due
         col.reset();
         assertTrue(col.getSched().counts() == (0, 0, 0));
         c.due = -5;
         c.ivl = 100;
         c.flush();
         col.reset();
         assertTrue(col.getSched().counts() == (0, 0, 1));
         // cram again
         long did = col.decks.newDyn("Cram");
         col.getSched().rebuildDyn(did);
         col.reset();
         assertTrue(col.getSched().counts() == (0, 0, 1));
         c.load();
         assertTrue(col.getSched().answerButtons(c) == 4);
         // add a sibling so we can test minSpace, etc
         c.Collection col = None;
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
         assertTrue(c.long did == 1);
     }

     @Test
     public void test_cram_rem(){
         Collection col = getEmptyCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         oldDue = note.cards().get(0).due;
         long did = col.decks.newDyn("Cram");
         col.getSched().rebuildDyn(did);
         col.reset();
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 2);
         // answering the card will put it in the learning queue
         assertTrue(c.type == CARD_TYPE_LRN and c.queue == QUEUE_TYPE_LRN);
         assertTrue(c.due != oldDue);
         // if we terminate cramming prematurely it should be set back to new
         col.getSched().emptyDyn(did);
         c.load();
         assertTrue(c.type == CARD_TYPE_NEW and c.queue == QUEUE_TYPE_NEW);
         assertTrue(c.due == oldDue);
     }

     @Test
     public void test_cram_resched(){
         // add card
         Collection col = getEmptyCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         // cram deck
         long did = col.decks.newDyn("Cram");
         cram = col.decks.get(did);
         cram["resched"] = False;
         col.decks.save(cram);
         col.getSched().rebuildDyn(did);
         col.reset();
         // graduate should return it to new
         Card c = col.getSched().getCard();
         ni = col.getSched().nextIvl;
         assertTrue(ni(c, 1) == 60);
         assertTrue(ni(c, 2) == 600);
         assertTrue(ni(c, 3) == 0);
         assertTrue(col.getSched().nextIvlStr(c, 3) == "(end)");
         col.getSched().answerCard(c, 3);
         assertTrue(c.type == CARD_TYPE_NEW and c.queue == QUEUE_TYPE_NEW);
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
         assertTrue(ni(c, 1) == 600);
         assertTrue(ni(c, 2) == 0);
         assertTrue(ni(c, 3) == 0);
         col.getSched().answerCard(c, 2);
         assertTrue(c.ivl == 100);
         assertTrue(c.due == col.getSched().today + 25);
         // check failure too
         Card c = cardcopy;
         c.flush();
         col.getSched().rebuildDyn(did);
         col.reset();
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 1);
         col.getSched().emptyDyn(did);
         c.load();
         assertTrue(c.ivl == 100);
         assertTrue(c.due == col.getSched().today + 25);
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
         assertTrue(c.ivl == 100);
         assertTrue(c.due == col.getSched().today + 25);
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
         assertTrue(c.ivl == 100);
         assertTrue(c.due == -25);
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
         assertTrue(c.ivl == 100);
         assertTrue(c.due == -25);
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
         assertTrue(c.ivl == 100);
         assertTrue(c.due == -25);
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
         Collection col = getEmptyCol();
         // add two more templates and set second active
         Model m = col.getModels().current();
         Models mm = col.getModels();
         JSONObject t = mm.newTemplate("Reverse");
         t["qfmt"] = "{{Back}}";
         t["afmt"] = "{{Front}}";
         mm.addTemplate(m, t);
         JSONObject t = mm.newTemplate("f2");
         t["qfmt"] = "{{Front}}";
         t["afmt"] = "{{Back}}";
         mm.addTemplate(m, t);
         mm.save(m);
         // create a new note; it should have 3 cards
         Note note = col.newNote();
         note.setItem("Front","1");
         note.setItem("Back","1");
         col.addNote(note);
         assertTrue(col.cardCount() == 3);
         col.reset();
         // ordinals should arrive in order
         assertTrue(col.getSched().getCard().ord == 0);
         assertTrue(col.getSched().getCard().ord == 1);
         assertTrue(col.getSched().getCard().ord == 2);
     }

     @Test
     public void test_counts_idx(){
         Collection col = getEmptyCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         note.setItem("Back","two");
         col.addNote(note);
         col.reset();
         assertTrue(col.getSched().counts() == (1, 0, 0));
         Card c = col.getSched().getCard();
         // counter's been decremented but idx indicates 1
         assertTrue(col.getSched().counts() == (0, 0, 0));
         assertTrue(col.getSched().countIdx(c) == 0);
         // answer to move to learn queue
         col.getSched().answerCard(c, 1);
         assertTrue(col.getSched().counts() == (0, 2, 0));
         // fetching again will decrement the count
         Card c = col.getSched().getCard();
         assertTrue(col.getSched().counts() == (0, 0, 0));
         assertTrue(col.getSched().countIdx(c) == 1);
         // answering should add it back again
         col.getSched().answerCard(c, 1);
         assertTrue(col.getSched().counts() == (0, 2, 0));
     }

     @Test
     public void test_repCounts(){
         Collection col = getEmptyCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         col.reset();
         // lrnReps should be accurate on pass/fail
         assertTrue(col.getSched().counts() == (1, 0, 0));
         col.getSched().answerCard(col.getSched().getCard(), 1);
         assertTrue(col.getSched().counts() == (0, 2, 0));
         col.getSched().answerCard(col.getSched().getCard(), 1);
         assertTrue(col.getSched().counts() == (0, 2, 0));
         col.getSched().answerCard(col.getSched().getCard(), 2);
         assertTrue(col.getSched().counts() == (0, 1, 0));
         col.getSched().answerCard(col.getSched().getCard(), 1);
         assertTrue(col.getSched().counts() == (0, 2, 0));
         col.getSched().answerCard(col.getSched().getCard(), 2);
         assertTrue(col.getSched().counts() == (0, 1, 0));
         col.getSched().answerCard(col.getSched().getCard(), 2);
         assertTrue(col.getSched().counts() == (0, 0, 0));
         Note note = col.newNote();
         note.setItem("Front","two");
         col.addNote(note);
         col.reset();
         // initial pass should be correct too
         col.getSched().answerCard(col.getSched().getCard(), 2);
         assertTrue(col.getSched().counts() == (0, 1, 0));
         col.getSched().answerCard(col.getSched().getCard(), 1);
         assertTrue(col.getSched().counts() == (0, 2, 0));
         col.getSched().answerCard(col.getSched().getCard(), 3);
         assertTrue(col.getSched().counts() == (0, 0, 0));
         // immediate graduate should work
         Note note = col.newNote();
         note.setItem("Front","three");
         col.addNote(note);
         col.reset();
         col.getSched().answerCard(col.getSched().getCard(), 3);
         assertTrue(col.getSched().counts() == (0, 0, 0));
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
         assertTrue(col.getSched().counts() == (0, 0, 1));
         col.getSched().answerCard(col.getSched().getCard(), 1);
         assertTrue(col.getSched().counts() == (0, 1, 0));
     }

     @Test
     public void test_timing(){
         Collection col = getEmptyCol();
         // add a few review cards, due today
         for i in range(5):
             Note note = col.newNote();
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
         col.decks.save(conf);
         col.getSched().answerCard(c, 1);
         // the next card should be another review
         Card c = col.getSched().getCard();
         assertTrue(c.queue == QUEUE_TYPE_REV);
         // but if we wait for a few seconds, the failed card should come back
         orig_time = time.time;

         def adjusted_time():
             return orig_time() + 5;

         time.time = adjusted_time;
         Card c = col.getSched().getCard();
         assertTrue(c.queue == QUEUE_TYPE_LRN);
         time.time = orig_time;
     }

     @Test
     public void test_collapse(){
         Collection col = getEmptyCol();
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
         assertTrue(not col.getSched().getCard());
     }

     @Test
     public void test_deckDue(){
         Collection col = getEmptyCol();
         // add a note with default deck
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         // and one that's a child
         Note note = col.newNote();
         note.setItem("Front","two");
         default1 = note.model()["did"] = col.decks.getId()("Default::1");
         col.addNote(note);
         // make it a review card
         Card c = note.cards().get(0);
         c.queue = QUEUE_TYPE_REV;
         c.due = 0;
         c.flush();
         // add one more with a new deck
         Note note = col.newNote();
         note.setItem("Front","two");
         foobar = note.model()["did"] = col.decks.getId()("foo::bar");
         col.addNote(note);
         // and one that's a sibling
         Note note = col.newNote();
         note.setItem("Front","three");
         foobaz = note.model()["did"] = col.decks.getId()("foo::baz");
         col.addNote(note);
         col.reset();
         assertTrue(len(col.decks.all_names_and_ids()) == 5);
         tree = col.getSched().deck_due_tree().children;
         assertTrue(tree[0].name == "Default");
         // sum of child and parent
         assertTrue(tree[0].deck_id == 1);
         assertTrue(tree[0].review_count == 1);
         assertTrue(tree[0].new_count == 1);
         // child count is just review
         child = tree[0].children[0];
         assertTrue(child.name == "1");
         assertTrue(child.deck_id == default1);
         assertTrue(child.review_count == 1);
         assertTrue(child.new_count == 0);
         // code should not fail if a card has an invalid deck
         c.long did = 12345;
         c.flush();
         col.getSched().deck_due_tree();
     }

     @Test
     public void test_deckFlow(){
         Collection col = getEmptyCol();
         // add a note with default deck
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         // and one that's a child
         Note note = col.newNote();
         note.setItem("Front","two");
         default1 = note.model()["did"] = col.decks.getId()("Default::2");
         col.addNote(note);
         // and another that's higher up
         Note note = col.newNote();
         note.setItem("Front","three");
         default1 = note.model()["did"] = col.decks.getId()("Default::1");
         col.addNote(note);
         // should get top level one first, then ::1, then ::2
         col.reset();
         assertTrue(col.getSched().counts() == (3, 0, 0));
         for i in "one", "three", "two":
             Card c = col.getSched().getCard();
             assertTrue(c.note()["Front"] == i);
             col.getSched().answerCard(c, 2);
     }

     @Test
     public void test_reorder(){
         Collection col = getEmptyCol();
         // add a note with default deck
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         Note note2 = col.newNote();
         note2.setItem("Front","two");
         col.addNote(note2);
         assertTrue(note2.cards().get(0).due == 2);
         found = False;
         // 50/50 chance of being reordered
         for i in range(20):
             col.getSched().randomizeCards(1);
             if note.cards().get(0).due != note.getId():
                 found = True;
                 break;
                 assertTrue(found);
         col.getSched().orderCards(1);
         assertTrue(note.cards().get(0).due == 1);
         // shifting
         note3 = col.newNote();
         note3.setItem("Front","three");
         col.addNote(note3);
         note4 = col.newNote();
         note4.setItem("Front","four");
         col.addNote(note4);
         assertTrue(note.cards().get(0).due == 1);
         assertTrue(note2.cards().get(0).due == 2);
         assertTrue(note3.cards().get(0).due == 3);
         assertTrue(note4.cards().get(0).due == 4);
         col.getSched().sortCards([note3.cards().get(0).getId(), note4.cards().get(0).getId()], start=1, shift=True);
         assertTrue(note.cards().get(0).due == 3);
         assertTrue(note2.cards().get(0).due == 4);
         assertTrue(note3.cards().get(0).due == 1);
         assertTrue(note4.cards().get(0).due == 2);
     }

     @Test
     public void test_forget(){
         Collection col = getEmptyCol();
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
         assertTrue(col.getSched().counts() == (0, 0, 1));
         col.getSched().forgetCards([c.getId()]);
         col.reset();
         assertTrue(col.getSched().counts() == (1, 0, 0));
     }

     @Test
     public void test_resched(){
         Collection col = getEmptyCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         Card c = note.cards().get(0);
         col.getSched().reschedCards([c.getId()], 0, 0);
         c.load();
         assertTrue(c.due == col.getSched().today);
         assertTrue(c.ivl == 1);
         assertTrue(c.queue == CARD_TYPE_REV and c.type == QUEUE_TYPE_REV);
         col.getSched().reschedCards([c.getId()], 1, 1);
         c.load();
         assertTrue(c.due == col.getSched().today + 1);
         assertTrue(c.ivl == +1);
     }

     @Test
     public void test_norelearn(){
         Collection col = getEmptyCol();
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
         col.getSched()._cardConf(c)["lapse"]["delays"] = [];
         col.getSched().answerCard(c, 1);
     }

     @Test
     public void test_failmult(){
         Collection col = getEmptyCol();
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
         conf["lapse"]["mult"] = 0.5;
         col.decks.save(conf);
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 1);
         assertTrue(c.ivl == 50);
         col.getSched().answerCard(c, 1);
         assertTrue(c.ivl == 25);
     }
     /*****************
          ** SchedV2      *
      *****************/
     private Collection getEmptyCol(){
         Collection col = getEmptyColOrig();
         col.changeSchedulerVer(2);
         return col;
     }

     @Test
     public void test_clock(){
         Collection col = getEmptyCol();
         if (col.getSched().dayCutoff - intTime()) < 10 * 60:
             raise Exception("Unit tests will fail around the day rollover.");


     private boolean checkRevIvl(col, c, targetIvl):
         min, max = col.getSched()._fuzzIvlRange(targetIvl);
         return min <= c.ivl <= max;
     }

     @Test
     public void test_basics(){
         Collection col = getEmptyCol();
         col.reset();
         assertTrue(not col.getSched().getCard());
     }

     @Test
     public void test_new(){
         Collection col = getEmptyCol();
         col.reset();
         assertTrue(col.getSched().newCount == 0);
         // add a note
         Note note = col.newNote();
         note.setItem("Front","one");
         note.setItem("Back","two");
         col.addNote(note);
         col.reset();
         assertTrue(col.getSched().newCount == 1);
         // fetch it
         Card c = col.getSched().getCard();
         assertTrue(c);
         assertTrue(c.queue == QUEUE_TYPE_NEW);
         assertTrue(c.type == CARD_TYPE_NEW);
         // if we answer it, it should become a learn card
         JSONObject t = intTime();
         col.getSched().answerCard(c, 1);
         assertTrue(c.queue == QUEUE_TYPE_LRN);
         assertTrue(c.type == CARD_TYPE_LRN);
         assertTrue(c.due >= t);

         // disabled for now, as the learn fudging makes this randomly fail
         // // the default order should ensure siblings are not seen together, and
         // // should show all cards
         // Model m = col.getModels().current(); Models mm = col.getModels()
         // JSONObject t = mm.newTemplate("Reverse")
         // t['qfmt'] = "{{Back}}"
         // t['afmt'] = "{{Front}}"
         // mm.addTemplate(m, t)
         // mm.save(m)
         // Note note = col.newNote()
         // note['Front'] = u"2"; note['Back'] = u"2"
         // col.addNote(note)
         // Note note = col.newNote()
         // note['Front'] = u"3"; note['Back'] = u"3"
         // col.addNote(note)
         // col.reset()
         // qs = ("2", "3", "2", "3")
         // for n in range(4):
         //     Card c = col.getSched().getCard()
         //     assertTrue(qs[n] in c.q())
         //     col.getSched().answerCard(c, 2)
     }

     @Test
     public void test_newLimits(){
         Collection col = getEmptyCol();
         // add some notes
         deck2 = col.decks.getId()("Default::foo");
         for i in range(30):
             Note note = col.newNote();
         note.setItem("Front","did")] = deck2;
             col.addNote(note);
         // give the child deck a different configuration
         c2 = col.decks.add_config_returning_id("new conf");
         col.decks.setConf(col.decks.get(deck2), c2);
         col.reset();
         // both confs have defaulted to a limit of 20
         assertTrue(col.getSched().newCount == 20);
         // first card we get comes from parent
         Card c = col.getSched().getCard();
         assertTrue(c.long did == 1);
         // limit the parent to 10 cards, meaning we get 10 in total
         conf1 = col.decks.confForDid(1);
         conf1["new"]["perDay"] = 10;
         col.decks.save(conf1);
         col.reset();
         assertTrue(col.getSched().newCount == 10);
         // if we limit child to 4, we should get 9
         conf2 = col.decks.confForDid(deck2);
         conf2["new"]["perDay"] = 4;
         col.decks.save(conf2);
         col.reset();
         assertTrue(col.getSched().newCount == 9);
     }

     @Test
     public void test_newBoxes(){
         Collection col = getEmptyCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         col.reset();
         Card c = col.getSched().getCard();
         conf = col.getSched()._cardConf(c);
         conf["new"]["delays"] = [1, 2, 3, 4, 5];
         col.decks.save(conf);
         col.getSched().answerCard(c, 2);
         // should handle gracefully
         conf["new"]["delays"] = [1];
         col.decks.save(conf);
         col.getSched().answerCard(c, 2);
     }

     @Test
     public void test_learn(){
         Collection col = getEmptyCol();
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
         conf["new"]["delays"] = [0.5, 3, 10];
         col.decks.save(conf);
         // fail it
         col.getSched().answerCard(c, 1);
         // it should have three reps left to graduation
         assertTrue(c.left % 1000 == 3);
         assertTrue(c.left // 1000 == )3
         // it should by due in 30 seconds
         JSONObject t = round(c.due - time.time());
                assertTrue(t >= 25 and t <= 40);
         // pass it once
         col.getSched().answerCard(c, 3);
         // it should by due in 3 minutes
         dueIn = c.due - time.time();
                assertTrue(178 <= dueIn <= 180 * 1.25);
                assertTrue(c.left % 1000 == 2);
                assertTrue(c.left // 1000 == )2
         // check log is accurate
         log = col.getDb().first("select * from revlog order by id desc");
                       assertTrue(log[3] == 3);
                       assertTrue(log[4] == -180);
                       assertTrue(log[5] == -30);
         // pass again
         col.getSched().answerCard(c, 3);
         // it should by due in 10 minutes
         dueIn = c.due - time.time();
                       assertTrue(599 <= dueIn <= 600 * 1.25);
                       assertTrue(c.left % 1000 == 1);
                       assertTrue(c.left // 1000 == )1
         // the next pass should graduate the card
                              assertTrue(c.queue == QUEUE_TYPE_LRN);
                              assertTrue(c.type == CARD_TYPE_LRN);
         col.getSched().answerCard(c, 3);
                              assertTrue(c.queue == QUEUE_TYPE_REV);
                              assertTrue(c.type == CARD_TYPE_REV);
         // should be due tomorrow, with an interval of 1
                              assertTrue(c.due == col.getSched().today + 1);
                              assertTrue(c.ivl == 1);
         // or normal removal
         c.type = 0;
         c.queue = 1;
         col.getSched().answerCard(c, 4);
                              assertTrue(c.type == CARD_TYPE_REV);
                              assertTrue(c.queue == QUEUE_TYPE_REV);
                              assertTrue(checkRevIvl(col, c, 4));
         // revlog should have been updated each time
                              assertTrue(col.getDb().queryScalar("select count() from revlog where type = 0") == 5);
     }

     @Test
     public void test_relearn(){
         Collection col = getEmptyCol();
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
         assertTrue(c.queue == QUEUE_TYPE_LRN);
         assertTrue(c.type == CARD_TYPE_RELEARNING);
         assertTrue(c.ivl == 1);

         // immediately graduate it
         col.getSched().answerCard(c, 4);
         assertTrue(c.queue == CARD_TYPE_REV and c.type == QUEUE_TYPE_REV);
         assertTrue(c.ivl == 2);
         assertTrue(c.due == col.getSched().today + c.ivl);
     }

     @Test
     public void test_relearn_no_steps(){
         Collection col = getEmptyCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         Card c = note.cards().get(0);
         c.ivl = 100;
         c.due = col.getSched().today;
         c.queue = CARD_TYPE_REV;
         c.type = QUEUE_TYPE_REV;
         c.flush();

         conf = col.decks.confForDid(1);
         conf["lapse"]["delays"] = [];
         col.decks.save(conf);

         // fail the card
         col.reset();
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 1);
         assertTrue(c.queue == CARD_TYPE_REV and c.type == QUEUE_TYPE_REV);
     }

     @Test
     public void test_learn_collapsed(){
         Collection col = getEmptyCol();
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
         assertTrue(not c.q().endswith("2"));
     }

     @Test
     public void test_learn_day(){
         Collection col = getEmptyCol();
         // add a note
         Note note = col.newNote();
         note.setItem("Front","one");
         Note note = col.addNote(note);
         col.getSched().reset();
         Card c = col.getSched().getCard();
         conf = col.getSched()._cardConf(c);
         conf["new"]["delays"] = [1, 10, 1440, 2880];
         col.decks.save(conf);
         // pass it
         col.getSched().answerCard(c, 3);
         // two reps to graduate, 1 more today
         assertTrue(c.left % 1000 == 3);
         assertTrue(c.left // 1000 == )1
                assertTrue(col.getSched().counts() == (0, 1, 0));
         Card c = col.getSched().getCard();
         ni = col.getSched().nextIvl;
                assertTrue(ni(c, 3) == 86400);
         // answering it will place it in queue 3
         col.getSched().answerCard(c, 3);
                assertTrue(c.due == col.getSched().today + 1);
                assertTrue(c.queue == QUEUE_TYPE_DAY_LEARN_RELEARN);
                assertTrue(not col.getSched().getCard());
         // for testing, move it back a day
         c.due -= 1;
         c.flush();
         col.reset();
                assertTrue(col.getSched().counts() == (0, 1, 0));
         Card c = col.getSched().getCard();
         // nextIvl should work
                assertTrue(ni(c, 3) == 86400 * 2);
         // if we fail it, it should be back in the correct queue
         col.getSched().answerCard(c, 1);
                assertTrue(c.queue == QUEUE_TYPE_LRN);
         col.undo();
         col.reset();
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 3);
         // simulate the passing of another two days
         c.due -= 2;
         c.flush();
         col.reset();
         // the last pass should graduate it into a review card
                assertTrue(ni(c, 3) == 86400);
         col.getSched().answerCard(c, 3);
                assertTrue(c.queue == CARD_TYPE_REV and c.type == QUEUE_TYPE_REV);
         // if the lapse step is tomorrow, failing it should handle the counts
         // correctly
         c.due = 0;
         c.flush();
         col.reset();
                assertTrue(col.getSched().counts() == (0, 0, 1));
         conf = col.getSched()._cardConf(c);
         conf["lapse"]["delays"] = [1440];
         col.decks.save(conf);
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 1);
                assertTrue(c.queue == QUEUE_TYPE_DAY_LEARN_RELEARN);
                assertTrue(col.getSched().counts() == (0, 0, 0));
     }

     @Test
     public void test_reviews(){
         Collection col = getEmptyCol();
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
         assertTrue(c.queue == QUEUE_TYPE_REV);
         // the new interval should be (100) * 1.2 = 120
         assertTrue(checkRevIvl(col, c, 120));
         assertTrue(c.due == col.getSched().today + c.ivl);
         // factor should have been decremented
         assertTrue(c.factor == 2350);
         // check counters
         assertTrue(c.lapses == 1);
         assertTrue(c.reps == 4);
         // ease 3
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         Card c = copy.copy(cardcopy);
         c.flush();
         col.getSched().answerCard(c, 3);
         // the new interval should be (100 + 8/2) * 2.5 = 260
         assertTrue(checkRevIvl(col, c, 260));
         assertTrue(c.due == col.getSched().today + c.ivl);
         // factor should have been left alone
         assertTrue(c.factor == STARTING_FACTOR);
         // ease 4
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         Card c = copy.copy(cardcopy);
         c.flush();
         col.getSched().answerCard(c, 4);
         // the new interval should be (100 + 8) * 2.5 * 1.3 = 351
         assertTrue(checkRevIvl(col, c, 351));
         assertTrue(c.due == col.getSched().today + c.ivl);
         // factor should have been increased
         assertTrue(c.factor == 2650);
         // leech handling
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         conf = col.decks.getConf(1);
         conf["lapse"]["leechAction"] = LEECH_SUSPEND;
         col.decks.save(conf);
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
         assertTrue(c.queue == QUEUE_TYPE_SUSPENDED);
         c.load();
         assertTrue(c.queue == QUEUE_TYPE_SUSPENDED);
     }

     @Test
     public void test_review_limits(){
         Collection col = getEmptyCol();

         parent = col.decks.get(col.decks.getId()("parent"));
         child = col.decks.get(col.decks.getId()("parent::child"));

         pconf = col.decks.get_config(col.decks.add_config_returning_id("parentConf"));
         cconf = col.decks.get_config(col.decks.add_config_returning_id("childConf"));

         pconf["rev"]["perDay"] = 5;
         col.decks.update_config(pconf);
         col.decks.setConf(parent, pconf.getLong("id"));
         cconf["rev"]["perDay"] = 10;
         col.decks.update_config(cconf);
         col.decks.setConf(child, cconf.getLong("id"));

         Model m = col.getModels().current();
         m["did"] = child.getLong("id");
         col.getModels().save(m, updateReqs=False);

         // add some cards
         for i in range(20):
             Note note = col.newNote();
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
         assertTrue(tree[0].review_count == 5  // paren)t
                assertTrue(tree[0].children[0].review_count == 5  // chil)d

         // .counts() should match
         col.decks.select(child.getLong("id"));
         col.getSched().reset();
                       assertTrue(col.getSched().counts() == (0, 0, 5));

         // answering a card in the child should decrement parent count
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 3);
                       assertTrue(col.getSched().counts() == (0, 0, 4));

         tree = col.getSched().deck_due_tree().children;
                       assertTrue(tree[0].review_count == 4  // paren)t
                              assertTrue(tree[0].children[0].review_count == 4  // chil)d
     }

     @Test
     public void test_button_spacing(){
         Collection col = getEmptyCol();
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
         assertTrue(wo(ni(c, 2)) == "2d");
         assertTrue(wo(ni(c, 3)) == "3d");
         assertTrue(wo(ni(c, 4)) == "4d");

         // if hard factor is <= 1, then hard may not increase
         conf = col.decks.confForDid(1);
         conf["rev"]["hardFactor"] = 1;
         col.decks.save(conf);
         assertTrue(wo(ni(c, 2)) == "1d");
     }

     @Test
     public void test_overdue_lapse(){
         // disabled in commit 3069729776990980f34c25be66410e947e9d51a2
         return;
         Collection col = getEmptyCol()  // pylint: disable=unreachable
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
         col.getSched()._clearOverdue = False;
         // checkpoint
         col.save();
         col.getSched().reset();
         assertTrue(col.getSched().counts() == (0, 2, 0));
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 3);
         // it should be due tomorrow
         assertTrue(c.due == col.getSched().today + 1);
         // revert to before
         col.rollback();
         col.getSched()._clearOverdue = True;
         // with the default settings, the overdue card should be removed from the
         // learning queue
         col.getSched().reset();
         assertTrue(col.getSched().counts() == (0, 0, 1));
     }

     @Test
     public void test_finished(){
         Collection col = getEmptyCol();
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
         Collection col = getEmptyCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         note.setItem("Back","two");
         col.addNote(note);
         col.reset();
         conf = col.decks.confForDid(1);
         conf["new"]["delays"] = [0.5, 3, 10];
         conf["lapse"]["delays"] = [1, 5, 9];
         col.decks.save(conf);
         Card c = col.getSched().getCard();
         // new cards
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         ni = col.getSched().nextIvl;
         assertTrue(ni(c, 1) == 30);
         assertTrue(ni(c, 2) == (30 + 180) // )2
                assertTrue(ni(c, 3) == 180);
                assertTrue(ni(c, 4) == 4 * 86400);
         col.getSched().answerCard(c, 1);
         // cards in learning
     ////////////////////////////////////////////////////////////////////////////////////////////////////
                assertTrue(ni(c, 1) == 30);
                assertTrue(ni(c, 2) == (30 + 180) // )2
                       assertTrue(ni(c, 3) == 180);
                       assertTrue(ni(c, 4) == 4 * 86400);
         col.getSched().answerCard(c, 3);
                       assertTrue(ni(c, 1) == 30);
                       assertTrue(ni(c, 2) == (180 + 600) // )2
                              assertTrue(ni(c, 3) == 600);
                              assertTrue(ni(c, 4) == 4 * 86400);
         col.getSched().answerCard(c, 3);
         // normal graduation is tomorrow
                              assertTrue(ni(c, 3) == 1 * 86400);
                              assertTrue(ni(c, 4) == 4 * 86400);
         // lapsed cards
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         c.type = CARD_TYPE_REV;
         c.ivl = 100;
         c.factor = STARTING_FACTOR;
                              assertTrue(ni(c, 1) == 60);
                              assertTrue(ni(c, 3) == 100 * 86400);
                              assertTrue(ni(c, 4) == 101 * 86400);
         // review cards
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         c.queue = QUEUE_TYPE_REV;
         c.ivl = 100;
         c.factor = STARTING_FACTOR;
         // failing it should put it at 60s
                              assertTrue(ni(c, 1) == 60);
         // or 1 day if relearn is false
         conf["lapse"]["delays"] = [];
         col.decks.save(conf);
                              assertTrue(ni(c, 1) == 1 * 86400);
         // (* 100 1.2 86400)10368000.0
                              assertTrue(ni(c, 2) == 10368000);
         // (* 100 2.5 86400)21600000.0
                              assertTrue(ni(c, 3) == 21600000);
         // (* 100 2.5 1.3 86400)28080000.0
                              assertTrue(ni(c, 4) == 28080000);
                              assertTrue(without_unicode_isolation(col.getSched().nextIvlStr(c, 4)) == "10.8mo");
     }

     @Test
     public void test_bury(){
         Collection col = getEmptyCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         Card c = note.cards().get(0);
         Note note = col.newNote();
         note.setItem("Front","two");
         col.addNote(note);
         c2 = note.cards().get(0);
         // burying
         col.getSched().buryCards([c.getId()], manual=True)  // pylint: disable=unexpected-keyword-arg
         c.load();
         assertTrue(c.queue == QUEUE_TYPE_MANUALLY_BURIED);
         col.getSched().buryCards([c2.getId()], manual=False)  // pylint: disable=unexpected-keyword-arg
         c2.load();
         assertTrue(c2.queue == QUEUE_TYPE_SIBLING_BURIED);

         col.reset();
         assertTrue(not col.getSched().getCard());

         col.getSched().unburyCardsForDeck(  // pylint: disable=unexpected-keyword-arg
             type="manual";
     );
         c.load();
         assertTrue(c.queue == QUEUE_TYPE_NEW);
         c2.load();
         assertTrue(c2.queue == QUEUE_TYPE_SIBLING_BURIED);

         col.getSched().unburyCardsForDeck(  // pylint: disable=unexpected-keyword-arg
             type="siblings";
     );
         c2.load();
         assertTrue(c2.queue == QUEUE_TYPE_NEW);

         col.getSched().buryCards([c.getId(), c2.getId()]);
         col.getSched().unburyCardsForDeck(type="all")  // pylint: disable=unexpected-keyword-arg

         col.reset();

         assertTrue(col.getSched().counts() == (2, 0, 0));
     }

     @Test
     public void test_suspend(){
         Collection col = getEmptyCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         Card c = note.cards().get(0);
         // suspending
         col.reset();
         assertTrue(col.getSched().getCard());
         col.getSched().suspendCards([c.getId()]);
         col.reset();
         assertTrue(not col.getSched().getCard());
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
         assertTrue(c.queue == QUEUE_TYPE_LRN);
         assertTrue(c.type == CARD_TYPE_RELEARNING);
         col.getSched().suspendCards([c.getId()]);
         col.getSched().unsuspendCards([c.getId()]);
         c.load();
         assertTrue(c.queue == QUEUE_TYPE_LRN);
         assertTrue(c.type == CARD_TYPE_RELEARNING);
         assertTrue(c.due == due);
         // should cope with cards in cram decks
         c.due = 1;
         c.flush();
         cram = col.decks.newDyn("tmp");
         col.getSched().rebuildDyn();
         c.load();
         assertTrue(c.due != 1);
         assertTrue(c.did != 1);
         col.getSched().suspendCards([c.getId()]);
         c.load();
         assertTrue(c.due != 1);
         assertTrue(c.did != 1);
         assertTrue(c.odue == 1);
     }

     @Test
     public void test_filt_reviewing_early_normal(){
         Collection col = getEmptyCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         Card c = note.cards().get(0);
         c.ivl = 100;
         c.queue = CARD_TYPE_REV;
         c.type = QUEUE_TYPE_REV;
         // due in 25 days, so it's been waiting 75 days
         c.due = col.getSched().today + 25;
         c.mod = 1;
         c.factor = STARTING_FACTOR;
         c.startTimer();
         c.flush();
         col.reset();
         assertTrue(col.getSched().counts() == (0, 0, 0));
         // create a dynamic deck and refresh it
         long did = col.decks.newDyn("Cram");
         col.getSched().rebuildDyn(did);
         col.reset();
         // should appear as normal in the deck list
         assertTrue(sorted(col.getSched().deck_due_tree().children)[0].review_count == 1);
         // and should appear in the counts
         assertTrue(col.getSched().counts() == (0, 0, 1));
         // grab it and check estimates
         Card c = col.getSched().getCard();
         assertTrue(col.getSched().answerButtons(c) == 4);
         assertTrue(col.getSched().nextIvl(c, 1) == 600);
         assertTrue(col.getSched().nextIvl(c, 2) == int(75 * 1.2) * 86400);
         assertTrue(col.getSched().nextIvl(c, 3) == int(75 * 2.5) * 86400);
         assertTrue(col.getSched().nextIvl(c, 4) == int(75 * 2.5 * 1.15) * 86400);

         // answer 'good'
         col.getSched().answerCard(c, 3);
         checkRevIvl(col, c, 90);
         assertTrue(c.due == col.getSched().today + c.ivl);
         assertTrue(not c.odue);
         // should not be in learning
         assertTrue(c.queue == QUEUE_TYPE_REV);
         // should be logged as a cram rep
         assertTrue(col.getDb().scalar("select type from revlog order by id desc limit 1") == 3);

         // due in 75 days, so it's been waiting 25 days
         c.ivl = 100;
         c.due = col.getSched().today + 75;
         c.flush();
         col.getSched().rebuildDyn(did);
         col.reset();
         Card c = col.getSched().getCard();

         assertTrue(col.getSched().nextIvl(c, 2) == 60 * 86400);
         assertTrue(col.getSched().nextIvl(c, 3) == 100 * 86400);
         assertTrue(col.getSched().nextIvl(c, 4) == 114 * 86400);
     }

     @Test
     public void test_filt_keep_lrn_state(){
         Collection col = getEmptyCol();

         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);

         // fail the card outside filtered deck
         Card c = col.getSched().getCard();
         conf = col.getSched()._cardConf(c);
         conf["new"]["delays"] = [1, 10, 61];
         col.decks.save(conf);

         col.getSched().answerCard(c, 1);

         assertTrue(c.type == CARD_TYPE_LRN and c.queue == QUEUE_TYPE_LRN);
         assertTrue(c.left == 3003);

         col.getSched().answerCard(c, 3);
         assertTrue(c.type == CARD_TYPE_LRN and c.queue == QUEUE_TYPE_LRN);

         // create a dynamic deck and refresh it
         long did = col.decks.newDyn("Cram");
         col.getSched().rebuildDyn(did);
         col.reset();

         // card should still be in learning state
         c.load();
         assertTrue(c.type == CARD_TYPE_LRN and c.queue == QUEUE_TYPE_LRN);
         assertTrue(c.left == 2002);

         // should be able to advance learning steps
         col.getSched().answerCard(c, 3);
         // should be due at least an hour in the future
         assertTrue(c.due - intTime() > 60 * 60);

         // emptying the deck preserves learning state
         col.getSched().emptyDyn(did);
         c.load();
         assertTrue(c.type == CARD_TYPE_LRN and c.queue == QUEUE_TYPE_LRN);
         assertTrue(c.left == 1001);
         assertTrue(c.due - intTime() > 60 * 60);
     }

     @Test
     public void test_preview(){
         // add cards
         Collection col = getEmptyCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         Card c = note.cards().get(0);
         orig = copy.copy(c);
         Note note2 = col.newNote();
         note2.setItem("Front","two");
         col.addNote(note2);
         // cram deck
         long did = col.decks.newDyn("Cram");
         cram = col.decks.get(did);
         cram["resched"] = False;
         col.decks.save(cram);
         col.getSched().rebuildDyn(did);
         col.reset();
         // grab the first card
         Card c = col.getSched().getCard();
         assertTrue(col.getSched().answerButtons(c) == 2);
         assertTrue(col.getSched().nextIvl(c, 1) == 600);
         assertTrue(col.getSched().nextIvl(c, 2) == 0);
         // failing it will push its due time back
         due = c.due;
         col.getSched().answerCard(c, 1);
         assertTrue(c.due != due);

         // the other card should come next
         c2 = col.getSched().getCard();
         assertTrue(c2.getId() != c.getId());

         // passing it will remove it
         col.getSched().answerCard(c2, 2);
         assertTrue(c2.queue == QUEUE_TYPE_NEW);
         assertTrue(c2.reps == 0);
         assertTrue(c2.type == CARD_TYPE_NEW);

         // the other card should appear again
         Card c = col.getSched().getCard();
         assertTrue(c.getId() == orig.getId());

         // emptying the filtered deck should restore card
         col.getSched().emptyDyn(did);
         c.load();
         assertTrue(c.queue == QUEUE_TYPE_NEW);
         assertTrue(c.reps == 0);
         assertTrue(c.type == CARD_TYPE_NEW);
     }

     @Test
     public void test_ordcycle(){
         Collection col = getEmptyCol();
         // add two more templates and set second active
         Model m = col.getModels().current();
         Models mm = col.getModels();
         JSONObject t = mm.newTemplate("Reverse");
         t["qfmt"] = "{{Back}}";
         t["afmt"] = "{{Front}}";
         mm.addTemplate(m, t);
         JSONObject t = mm.newTemplate("f2");
         t["qfmt"] = "{{Front}}";
         t["afmt"] = "{{Back}}";
         mm.addTemplate(m, t);
         mm.save(m);
         // create a new note; it should have 3 cards
         Note note = col.newNote();
         note.setItem("Front","1");
         note.setItem("Back","1");
         col.addNote(note);
         assertTrue(col.cardCount() == 3);
         col.reset();
         // ordinals should arrive in order
         assertTrue(col.getSched().getCard().ord == 0);
         assertTrue(col.getSched().getCard().ord == 1);
         assertTrue(col.getSched().getCard().ord == 2);
     }

     @Test
     public void test_counts_idx(){
         Collection col = getEmptyCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         note.setItem("Back","two");
         col.addNote(note);
         col.reset();
         assertTrue(col.getSched().counts() == (1, 0, 0));
         Card c = col.getSched().getCard();
         // counter's been decremented but idx indicates 1
         assertTrue(col.getSched().counts() == (0, 0, 0));
         assertTrue(col.getSched().countIdx(c) == 0);
         // answer to move to learn queue
         col.getSched().answerCard(c, 1);
         assertTrue(col.getSched().counts() == (0, 1, 0));
         // fetching again will decrement the count
         Card c = col.getSched().getCard();
         assertTrue(col.getSched().counts() == (0, 0, 0));
         assertTrue(col.getSched().countIdx(c) == 1);
         // answering should add it back again
         col.getSched().answerCard(c, 1);
         assertTrue(col.getSched().counts() == (0, 1, 0));
     }

     @Test
     public void test_repCounts(){
         Collection col = getEmptyCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         col.reset();
         // lrnReps should be accurate on pass/fail
         assertTrue(col.getSched().counts() == (1, 0, 0));
         col.getSched().answerCard(col.getSched().getCard(), 1);
         assertTrue(col.getSched().counts() == (0, 1, 0));
         col.getSched().answerCard(col.getSched().getCard(), 1);
         assertTrue(col.getSched().counts() == (0, 1, 0));
         col.getSched().answerCard(col.getSched().getCard(), 3);
         assertTrue(col.getSched().counts() == (0, 1, 0));
         col.getSched().answerCard(col.getSched().getCard(), 1);
         assertTrue(col.getSched().counts() == (0, 1, 0));
         col.getSched().answerCard(col.getSched().getCard(), 3);
         assertTrue(col.getSched().counts() == (0, 1, 0));
         col.getSched().answerCard(col.getSched().getCard(), 3);
         assertTrue(col.getSched().counts() == (0, 0, 0));
         Note note = col.newNote();
         note.setItem("Front","two");
         col.addNote(note);
         col.reset();
         // initial pass should be correct too
         col.getSched().answerCard(col.getSched().getCard(), 3);
         assertTrue(col.getSched().counts() == (0, 1, 0));
         col.getSched().answerCard(col.getSched().getCard(), 1);
         assertTrue(col.getSched().counts() == (0, 1, 0));
         col.getSched().answerCard(col.getSched().getCard(), 4);
         assertTrue(col.getSched().counts() == (0, 0, 0));
         // immediate graduate should work
         Note note = col.newNote();
         note.setItem("Front","three");
         col.addNote(note);
         col.reset();
         col.getSched().answerCard(col.getSched().getCard(), 4);
         assertTrue(col.getSched().counts() == (0, 0, 0));
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
         assertTrue(col.getSched().counts() == (0, 0, 1));
         col.getSched().answerCard(col.getSched().getCard(), 1);
         assertTrue(col.getSched().counts() == (0, 1, 0));
     }

     @Test
     public void test_timing(){
         Collection col = getEmptyCol();
         // add a few review cards, due today
         for i in range(5):
             Note note = col.newNote();
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
         assertTrue(c2.queue == QUEUE_TYPE_REV);
         // if the failed card becomes due, it should show first
         c.due = intTime() - 1;
         c.flush();
         col.reset();
         Card c = col.getSched().getCard();
         assertTrue(c.queue == QUEUE_TYPE_LRN);
     }

     @Test
     public void test_collapse(){
         Collection col = getEmptyCol();
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
         assertTrue(not col.getSched().getCard());
     }

     @Test
     public void test_deckDue(){
         Collection col = getEmptyCol();
         // add a note with default deck
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         // and one that's a child
         Note note = col.newNote();
         note.setItem("Front","two");
         default1 = note.model()["did"] = col.decks.getId()("Default::1");
         col.addNote(note);
         // make it a review card
         Card c = note.cards().get(0);
         c.queue = QUEUE_TYPE_REV;
         c.due = 0;
         c.flush();
         // add one more with a new deck
         Note note = col.newNote();
         note.setItem("Front","two");
         foobar = note.model()["did"] = col.decks.getId()("foo::bar");
         col.addNote(note);
         // and one that's a sibling
         Note note = col.newNote();
         note.setItem("Front","three");
         foobaz = note.model()["did"] = col.decks.getId()("foo::baz");
         col.addNote(note);
         col.reset();
         assertTrue(len(col.decks.all_names_and_ids()) == 5);
         tree = col.getSched().deck_due_tree().children;
         assertTrue(tree[0].name == "Default");
         // sum of child and parent
         assertTrue(tree[0].deck_id == 1);
         assertTrue(tree[0].review_count == 1);
         assertTrue(tree[0].new_count == 1);
         // child count is just review
         child = tree[0].children[0];
         assertTrue(child.name == "1");
         assertTrue(child.deck_id == default1);
         assertTrue(child.review_count == 1);
         assertTrue(child.new_count == 0);
         // code should not fail if a card has an invalid deck
         c.long did = 12345;
         c.flush();
         col.getSched().deck_due_tree();
     }

     @Test
     public void test_deckTree(){
         Collection col = getEmptyCol();
         col.decks.getId()("new::b::c");
         col.decks.getId()("new2");
         // new should not appear twice in tree
         names = [x.name for x in col.getSched().deck_due_tree().children];
         names.remove("new");
         assertTrue("new" not in names);
     }

     @Test
     public void test_deckFlow(){
         Collection col = getEmptyCol();
         // add a note with default deck
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         // and one that's a child
         Note note = col.newNote();
         note.setItem("Front","two");
         default1 = note.model()["did"] = col.decks.getId()("Default::2");
         col.addNote(note);
         // and another that's higher up
         Note note = col.newNote();
         note.setItem("Front","three");
         default1 = note.model()["did"] = col.decks.getId()("Default::1");
         col.addNote(note);
         // should get top level one first, then ::1, then ::2
         col.reset();
         assertTrue(col.getSched().counts() == (3, 0, 0));
         for i in "one", "three", "two":
             Card c = col.getSched().getCard();
             assertTrue(c.note()["Front"] == i);
             col.getSched().answerCard(c, 3);
     }

     @Test
     public void test_reorder(){
         Collection col = getEmptyCol();
         // add a note with default deck
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         Note note2 = col.newNote();
         note2.setItem("Front","two");
         col.addNote(note2);
         assertTrue(note2.cards().get(0).due == 2);
         found = False;
         // 50/50 chance of being reordered
         for i in range(20):
             col.getSched().randomizeCards(1);
             if note.cards().get(0).due != note.getId():
                 found = True;
                 break;
                 assertTrue(found);
         col.getSched().orderCards(1);
         assertTrue(note.cards().get(0).due == 1);
         // shifting
         note3 = col.newNote();
         note3.setItem("Front","three")b;
         col.addNote(note3);
         note4 = col.newNote();
         note4.setItem("Front","four");
         col.addNote(note4);
         assertTrue(note.cards().get(0).due == 1);
         assertTrue(note2.cards().get(0).due == 2);
         assertTrue(note3.cards().get(0).due == 3);
         assertTrue(note4.cards().get(0).due == 4);
         col.getSched().sortCards([note3.cards().get(0).getId(), note4.cards().get(0).getId()], start=1, shift=True);
         assertTrue(note.cards().get(0).due == 3);
         assertTrue(note2.cards().get(0).due == 4);
         assertTrue(note3.cards().get(0).due == 1);
         assertTrue(note4.cards().get(0).due == 2);
     }

     @Test
     public void test_forget(){
         Collection col = getEmptyCol();
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
         assertTrue(col.getSched().counts() == (0, 0, 1));
         col.getSched().forgetCards([c.getId()]);
         col.reset();
         assertTrue(col.getSched().counts() == (1, 0, 0));
     }

     @Test
     public void test_resched(){
         Collection col = getEmptyCol();
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         Card c = note.cards().get(0);
         col.getSched().reschedCards([c.getId()], 0, 0);
         c.load();
         assertTrue(c.due == col.getSched().today);
         assertTrue(c.ivl == 1);
         assertTrue(c.queue == QUEUE_TYPE_REV and c.type == CARD_TYPE_REV);
         col.getSched().reschedCards([c.getId()], 1, 1);
         c.load();
         assertTrue(c.due == col.getSched().today + 1);
         assertTrue(c.ivl == +1);
     }

     @Test
     public void test_norelearn(){
         Collection col = getEmptyCol();
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
         col.getSched()._cardConf(c)["lapse"]["delays"] = [];
         col.getSched().answerCard(c, 1);
     }

     @Test
     public void test_failmult(){
         Collection col = getEmptyCol();
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
         conf["lapse"]["mult"] = 0.5;
         col.decks.save(conf);
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 1);
         assertTrue(c.ivl == 50);
         col.getSched().answerCard(c, 1);
         assertTrue(c.ivl == 25);
     }

     @Test
     public void test_moveVersions(){
         Collection col = getEmptyCol();
         col.changeSchedulerVer(1);

         Note n = col.newNote();
         n["Front"] = "one";
         col.addNote(n);

         // make it a learning card
         col.reset();
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 1);

         // the move to v2 should reset it to new
         col.changeSchedulerVer(2);
         c.load();
         assertTrue(c.queue == QUEUE_TYPE_NEW);
         assertTrue(c.type == CARD_TYPE_NEW);

         // fail it again, and manually bury it
         col.reset();
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 1);
         col.getSched().buryCards([c.getId()]);
         c.load();
         assertTrue(c.queue == QUEUE_TYPE_MANUALLY_BURIED);

         // revert to version 1
         col.changeSchedulerVer(1);

         // card should have moved queues
         c.load();
         assertTrue(c.queue == QUEUE_TYPE_SIBLING_BURIED);

         // and it should be new again when unburied
         col.getSched().unburyCards();
         c.load();
         assertTrue(c.type == CARD_TYPE_NEW and c.queue == QUEUE_TYPE_NEW);

         // make sure relearning cards transition correctly to v1
         col.changeSchedulerVer(2);
         // card with 100 day interval, answering again
         col.getSched().reschedCards([c.getId()], 100, 100);
         c.load();
         c.due = 0;
         c.flush();
         conf = col.getSched()._cardConf(c);
         conf["lapse"]["mult"] = 0.5;
         col.decks.save(conf);
         col.getSched().reset();
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 1);
         // due should be correctly set when removed from learning early
         col.changeSchedulerVer(1);
         c.load();
         assertTrue(c.due == 50);
     }

     // cards with a due date earlier than the collection should retain
     // their due date when removed
     @Test
     public void test_negativeDueFilter(){
         Collection col = getEmptyCol();

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
         long did = col.decks.newDyn("Cram");
         col.getSched().rebuildDyn(did);
         col.getSched().emptyDyn(did);
         col.reset();

         c.load();
         assertTrue(c.due == -5);
     }


     // hard on the first step should be the average of again and good,
     // and it should be logged properly

     @Test
     public void test_initial_repeat(){
         Collection col = getEmptyCol();
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
         assertTrue(ivl == -5.5 * 60);
     }
     /*****************
          ** Stats        *
      *****************/

     @Test
     public void test_stats(){
         Collection col = getEmptyCol();
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
         Collection col = getEmptyCol();
         assertTrue(col.stats().report());
     }

     @Test
     public void test_graphs(){
         dir = tempfile.gettempdir();
         Collection col = getEmptyCol();
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
         Collection col = getEmptyCol();
         Model m = col.getModels().current();
         m["tmpls"][0]["qfmt"] = "{{custom:Front}}";
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
     private Collection getEmptyCol(){
         Collection col = getEmptyColOrig();
         col.changeSchedulerVer(2);
         return col;
     }

     @Test
     public void test_op(){
         Collection col = getEmptyCol();
         // should have no undo by default
         assertTrue(not col.undoName());
         // let's adjust a study option
         col.save("studyopts");
         col.conf["abc"] = 5;
         // it should be listed as undoable
         assertTrue(col.undoName() == "studyopts");
         // with about 5 minutes until it's clobbered
         assertTrue(time.time() - col._lastSave < 1);
         // undoing should restore the old value
         col.undo();
         assertTrue(not col.undoName());
         assertTrue("abc" not in col.conf);
         // an (auto)save will clear the undo
         col.save("foo");
         assertTrue(col.undoName() == "foo");
         col.save();
         assertTrue(not col.undoName());
         // and a review will, too
         col.save("add");
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         col.reset();
         assertTrue(col.undoName() == "add");
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 2);
         assertTrue(col.undoName() == "Review");
     }

     @Test
     public void test_review(){
         Collection col = getEmptyCol();
         col.conf["counts"] = COUNT_REMAINING;
         Note note = col.newNote();
         note.setItem("Front","one");
         col.addNote(note);
         col.reset();
         assertTrue(not col.undoName());
         // answer
         assertTrue(col.getSched().counts() == (1, 0, 0));
         Card c = col.getSched().getCard();
         assertTrue(c.queue == QUEUE_TYPE_NEW);
         col.getSched().answerCard(c, 3);
         assertTrue(c.left == 1001);
         assertTrue(col.getSched().counts() == (0, 1, 0));
         assertTrue(c.queue == QUEUE_TYPE_LRN);
         // undo
         assertTrue(col.undoName());
         col.undo();
         col.reset();
         assertTrue(col.getSched().counts() == (1, 0, 0));
         c.load();
         assertTrue(c.queue == QUEUE_TYPE_NEW);
         assertTrue(c.left != 1001);
         assertTrue(not col.undoName());
         // we should be able to undo multiple answers too
         Note note = col.newNote();
         note.setItem("Front","two");
         col.addNote(note);
         col.reset();
         assertTrue(col.getSched().counts() == (2, 0, 0));
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 3);
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 3);
         assertTrue(col.getSched().counts() == (0, 2, 0));
         col.undo();
         col.reset();
         assertTrue(col.getSched().counts() == (1, 1, 0));
         col.undo();
         col.reset();
         assertTrue(col.getSched().counts() == (2, 0, 0));
         // performing a normal op will clear the review queue
         Card c = col.getSched().getCard();
         col.getSched().answerCard(c, 3);
         assertTrue(col.undoName() == "Review");
         col.save("foo");
         assertTrue(col.undoName() == "foo");
         col.undo();
     }

 } 
