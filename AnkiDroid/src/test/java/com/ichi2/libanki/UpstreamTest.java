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

@RunWith(AndroidJUnit4.class)
public class UpstreamTest extends RobolectricTest {
    /*****************
      ** Cards        *
      *****************/

     @Test
     public void test_delete(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "1";
         note["Back"] = "2";
         col.addNote(note);
         cid = note.cards()[0].id;
         col.reset();
         col.sched.answerCard(col.sched.getCard(), 2);
         col.remove_cards_and_orphaned_notes([cid]);
         assert col.cardCount() == 0;
         assert col.noteCount() == 0;
         assert col.db.scalar("select count() from notes") == 0;
         assert col.db.scalar("select count() from cards") == 0;
         assert col.db.scalar("select count() from graves") == 2;
     }

     @Test
     public void test_misc(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "1";
         note["Back"] = "2";
         col.addNote(note);
         c = note.cards()[0];
         id = col.models.current()["id"];
         assert c.template()["ord"] == 0;
     }

     @Test
     public void test_genrem(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "1";
         note["Back"] = "";
         col.addNote(note);
         assert len(note.cards()) == 1;
         m = col.models.current();
         mm = col.models;
         // adding a new template should automatically create cards
         t = mm.newTemplate("rev");
         t["qfmt"] = "{{Front}}";
         t["afmt"] = "";
         mm.addTemplate(m, t);
         mm.save(m, templates=True);
         assert len(note.cards()) == 2;
         // if the template is changed to remove cards, they'll be removed
         t = m["tmpls"][1];
         t["qfmt"] = "{{Back}}";
         mm.save(m, templates=True);
         rep = col.backend.get_empty_cards();
         rep = col.backend.get_empty_cards();
         for n in rep.notes:
             col.remove_cards_and_orphaned_notes(n.card_ids);
         assert len(note.cards()) == 1;
         // if we add to the note, a card should be automatically generated
         note.load();
         note["Back"] = "1";
         note.flush();
         assert len(note.cards()) == 2;
     }

     @Test
     public void test_gendeck(){
         col = getEmptyCol();
         cloze = col.models.byName("Cloze");
         col.models.setCurrent(cloze);
         note = col.newNote();
         note["Text"] = "{{c1::one}}";
         col.addNote(note);
         assert col.cardCount() == 1;
         assert note.cards()[0].did == 1;
         // set the model to a new default col
         newId = col.decks.id("new");
         cloze["did"] = newId;
         col.models.save(cloze, updateReqs=False);
         // a newly generated card should share the first card's col
         note["Text"] += "{{c2::two}}";
         note.flush();
         assert note.cards()[1].did == 1;
         // and same with multiple cards
         note["Text"] += "{{c3::three}}";
         note.flush();
         assert note.cards()[2].did == 1;
         // if one of the cards is in a different col, it should revert to the
         // model default
         c = note.cards()[1];
         c.did = newId;
         c.flush();
         note["Text"] += "{{c4::four}}";
         note.flush();
         assert note.cards()[3].did == newId;
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
         col = aopen(path);
         // for open()
         newPath = col.path;
         newMod = col.mod;
         col.close();
         del col;

         // reopen
         col = aopen(newPath);
         assert col.mod == newMod;
         col.close();

         // non-writeable dir
         if isWin:
             dir = "c:\root.anki2";
         else:
             dir = "/attachroot.anki2";
         assertException(Exception, lambda: aopen(dir));
         // reuse tmp file from before, test non-writeable file
         os.chmod(newPath, 0);
         assertException(Exception, lambda: aopen(newPath));
         os.chmod(newPath, 0o666);
         os.unlink(newPath);
     }

     @Test
     public void test_noteAddDelete(){
         col = getEmptyCol();
         // add a note
         note = col.newNote();
         note["Front"] = "one";
         note["Back"] = "two";
         n = col.addNote(note);
         assert n == 1;
         // test multiple cards - add another template
         m = col.models.current();
         mm = col.models;
         t = mm.newTemplate("Reverse");
         t["qfmt"] = "{{Back}}";
         t["afmt"] = "{{Front}}";
         mm.addTemplate(m, t);
         mm.save(m);
         assert col.cardCount() == 2;
         // creating new notes should use both cards
         note = col.newNote();
         note["Front"] = "three";
         note["Back"] = "four";
         n = col.addNote(note);
         assert n == 2;
         assert col.cardCount() == 4;
         // check q/a generation
         c0 = note.cards()[0];
         assert "three" in c0.q();
         // it should not be a duplicate
         assert not note.dupeOrEmpty();
         // now let's make a duplicate
         note2 = col.newNote();
         note2["Front"] = "one";
         note2["Back"] = "";
         assert note2.dupeOrEmpty();
         // empty first field should not be permitted either
         note2["Front"] = " ";
         assert note2.dupeOrEmpty();
     }

     @Test
     public void test_fieldChecksum(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "new";
         note["Back"] = "new2";
         col.addNote(note);
         assert col.db.scalar("select csum from notes") == int("c2a6b03f", 16);
         // changing the val should change the checksum
         note["Front"] = "newx";
         note.flush();
         assert col.db.scalar("select csum from notes") == int("302811ae", 16);
     }

     @Test
     public void test_addDelTags(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "1";
         col.addNote(note);
         note2 = col.newNote();
         note2["Front"] = "2";
         col.addNote(note2);
         // adding for a given id
         col.tags.bulkAdd([note.id], "foo");
         note.load();
         note2.load();
         assert "foo" in note.tags;
         assert "foo" not in note2.tags;
         // should be canonified
         col.tags.bulkAdd([note.id], "foo aaa");
         note.load();
         assert note.tags[0] == "aaa";
         assert len(note.tags) == 2;
     }

     @Test
     public void test_timestamps(){
         col = getEmptyCol();
         assert len(col.models.all_names_and_ids()) == len(get_stock_notetypes(col));
         for i in range(100):
             addBasicModel(col);
         assert len(col.models.all_names_and_ids()) == 100 + len(get_stock_notetypes(col));
     }

     @Test
     public void test_furigana(){
         col = getEmptyCol();
         mm = col.models;
         m = mm.current();
         // filter should work
         m["tmpls"][0]["qfmt"] = "{{kana:Front}}";
         mm.save(m);
         n = col.newNote();
         n["Front"] = "foo[abc]";
         col.addNote(n);
         c = n.cards()[0];
         assert c.q().endswith("abc");
         // and should avoid sound
         n["Front"] = "foo[sound:abc.mp3]";
         n.flush();
         assert "anki:play" in c.q(reload=True);
         // it shouldn't throw an error while people are editing
         m["tmpls"][0]["qfmt"] = "{{kana:}}";
         mm.save(m);
         c.q(reload=True);
     }

     @Test
     public void test_translate(){
         col = getEmptyCol();
         no_uni = without_unicode_isolation;

         assert (;
             col.tr(TR.CARD_TEMPLATE_RENDERING_FRONT_SIDE_PROBLEM);
             == "Front template has a problem:";
     );
         assert no_uni(col.tr(TR.STATISTICS_REVIEWS, reviews=1)) == "1 review";
         assert no_uni(col.tr(TR.STATISTICS_REVIEWS, reviews=2)) == "2 reviews";
     }

     @Test
     public void test_db_named_args(capsys):
         sql = "select a, 2+:test5 from b where arg =:foo and x = :test5";
         args = [];
         kwargs = dict(test5=5, foo="blah");

         s, a = emulate_named_args(sql, args, kwargs);
         assert s == "select a, 2+?1 from b where arg =?2 and x = ?1";
         assert a == [5, "blah"];

         // swallow the warning
         _ = capsys.readouterr();
     }
     /*****************
      ** Decks        *
      *****************/

     @Test
     public void test_basic(){
         col = getEmptyCol();
         // we start with a standard col
         assert len(col.decks.all_names_and_ids()) == 1;
         // it should have an id of 1
         assert col.decks.name(1);
         // create a new col
         parentId = col.decks.id("new deck");
         assert parentId;
         assert len(col.decks.all_names_and_ids()) == 2;
         // should get the same id
         assert col.decks.id("new deck") == parentId;
         // we start with the default col selected
         assert col.decks.selected() == 1;
         assert col.decks.active() == [1];
         // we can select a different col
         col.decks.select(parentId);
         assert col.decks.selected() == parentId;
         assert col.decks.active() == [parentId];
         // let's create a child
         childId = col.decks.id("new deck::child");
         col.sched.reset();
         // it should have been added to the active list
         assert col.decks.selected() == parentId;
         assert col.decks.active() == [parentId, childId];
         // we can select the child individually too
         col.decks.select(childId);
         assert col.decks.selected() == childId;
         assert col.decks.active() == [childId];
         // parents with a different case should be handled correctly
         col.decks.id("ONE");
         m = col.models.current();
         m["did"] = col.decks.id("one::two");
         col.models.save(m, updateReqs=False);
         n = col.newNote();
         n["Front"] = "abc";
         col.addNote(n);
     }

     @Test
     public void test_remove(){
         col = getEmptyCol();
         // create a new col, and add a note/card to it
         deck1 = col.decks.id("deck1");
         note = col.newNote();
         note["Front"] = "1";
         note.model()["did"] = deck1;
         col.addNote(note);
         c = note.cards()[0];
         assert c.did == deck1;
         assert col.cardCount() == 1;
         col.decks.rem(deck1);
         assert col.cardCount() == 0;
         // if we try to get it, we get the default
         assert col.decks.name(c.did) == "[no deck]";
     }

     @Test
     public void test_rename(){
         col = getEmptyCol();
         id = col.decks.id("hello::world");
         // should be able to rename into a completely different branch, creating
         // parents as necessary
         col.decks.rename(col.decks.get(id), "foo::bar");
         names = [n.name for n in col.decks.all_names_and_ids()];
         assert "foo" in names;
         assert "foo::bar" in names;
         assert "hello::world" not in names;
         // create another col
         id = col.decks.id("tmp");
         // automatically adjusted if a duplicate name
         col.decks.rename(col.decks.get(id), "FOO");
         names = [n.name for n in col.decks.all_names_and_ids()];
         assert "FOO+" in names;
         // when renaming, the children should be renamed too
         col.decks.id("one::two::three");
         id = col.decks.id("one");
         col.decks.rename(col.decks.get(id), "yo");
         names = [n.name for n in col.decks.all_names_and_ids()];
         for n in "yo", "yo::two", "yo::two::three":
             assert n in names;
         // over filtered
         filteredId = col.decks.newDyn("filtered");
         filtered = col.decks.get(filteredId);
         childId = col.decks.id("child");
         child = col.decks.get(childId);
         assertException(DeckRenameError, lambda: col.decks.rename(child, "filtered::child"));
         assertException(DeckRenameError, lambda: col.decks.rename(child, "FILTERED::child"));
     }

     @Test
     public void test_renameForDragAndDrop(){
         col = getEmptyCol();

         def deckNames():
             return [n.name for n in col.decks.all_names_and_ids(skip_empty_default=True)];

         languages_did = col.decks.id("Languages");
         chinese_did = col.decks.id("Chinese");
         hsk_did = col.decks.id("Chinese::HSK");

         // Renaming also renames children
         col.decks.renameForDragAndDrop(chinese_did, languages_did);
         assert deckNames() == ["Languages", "Languages::Chinese", "Languages::Chinese::HSK"];

         // Dragging a col onto itself is a no-op
         col.decks.renameForDragAndDrop(languages_did, languages_did);
         assert deckNames() == ["Languages", "Languages::Chinese", "Languages::Chinese::HSK"];

         // Dragging a col onto its parent is a no-op
         col.decks.renameForDragAndDrop(hsk_did, chinese_did);
         assert deckNames() == ["Languages", "Languages::Chinese", "Languages::Chinese::HSK"];

         // Dragging a col onto a descendant is a no-op
         col.decks.renameForDragAndDrop(languages_did, hsk_did);
         assert deckNames() == ["Languages", "Languages::Chinese", "Languages::Chinese::HSK"];

         // Can drag a grandchild onto its grandparent.  It becomes a child
         col.decks.renameForDragAndDrop(hsk_did, languages_did);
         assert deckNames() == ["Languages", "Languages::Chinese", "Languages::HSK"];

         // Can drag a col onto its sibling
         col.decks.renameForDragAndDrop(hsk_did, chinese_did);
         assert deckNames() == ["Languages", "Languages::Chinese", "Languages::Chinese::HSK"];

         // Can drag a col back to the top level
         col.decks.renameForDragAndDrop(chinese_did, None);
         assert deckNames() == ["Chinese", "Chinese::HSK", "Languages"];

         // Dragging a top level col to the top level is a no-op
         col.decks.renameForDragAndDrop(chinese_did, None);
         assert deckNames() == ["Chinese", "Chinese::HSK", "Languages"];

         // decks are renamed if necessary
         new_hsk_did = col.decks.id("hsk");
         col.decks.renameForDragAndDrop(new_hsk_did, chinese_did);
         assert deckNames() == ["Chinese", "Chinese::HSK", "Chinese::hsk+", "Languages"];
         col.decks.rem(new_hsk_did);

         // '' is a convenient alias for the top level DID
         col.decks.renameForDragAndDrop(hsk_did, "");
         assert deckNames() == ["Chinese", "HSK", "Languages"];
      }

     /*****************
      ** Exporting    *
      *****************/
     private void setup1(){
         global col;
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "foo";
         note["Back"] = "bar<br>";
         note.tags = ["tag", "tag2"];
         col.addNote(note);
         // with a different col
         note = col.newNote();
         note["Front"] = "baz";
         note["Back"] = "qux";
         note.model()["did"] = col.decks.id("new col");
         col.addNote(note);
     }

             /*//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////// */



     @Test
     public void test_export_anki(){
         setup1();
         // create a new col with its own conf to test conf copying
         did = col.decks.id("test");
         dobj = col.decks.get(did);
         confId = col.decks.add_config_returning_id("newconf");
         conf = col.decks.get_config(confId);
         conf["new"]["perDay"] = 5;
         col.decks.save(conf);
         col.decks.setConf(dobj, confId);
         // export
         e = AnkiExporter(col);
         fd, newname = tempfile.mkstemp(prefix="ankitest", suffix=".anki2");
         newname = str(newname);
         os.close(fd);
         os.unlink(newname);
         e.exportInto(newname);
         // exporting should not have changed conf for original deck
         conf = col.decks.confForDid(did);
         assert conf["id"] != 1;
         // connect to new deck
         d2 = aopen(newname);
         assert d2.cardCount() == 2;
         // as scheduling was reset, should also revert decks to default conf
         did = d2.decks.id("test", create=False);
         assert did;
         conf2 = d2.decks.confForDid(did);
         assert conf2["new"]["perDay"] == 20;
         dobj = d2.decks.get(did);
         // conf should be 1
         assert dobj["conf"] == 1;
         // try again, limited to a deck
         fd, newname = tempfile.mkstemp(prefix="ankitest", suffix=".anki2");
         newname = str(newname);
         os.close(fd);
         os.unlink(newname);
         e.did = 1;
         e.exportInto(newname);
         d2 = aopen(newname);
         assert d2.cardCount() == 1;
     }

     @Test
     public void test_export_ankipkg(){
         setup1();
         // add a test file to the media folder
         with open(os.path.join(col.media.dir(), "今日.mp3"), "w") as note:
             note.write("test");
         n = col.newNote();
         n["Front"] = "[sound:今日.mp3]";
         col.addNote(n);
         e = AnkiPackageExporter(col);
         fd, newname = tempfile.mkstemp(prefix="ankitest", suffix=".apkg");
         newname = str(newname);
         os.close(fd);
         os.unlink(newname);
         e.exportInto(newname);
     }


     @errorsAfterMidnight
     @Test
     public void test_export_anki_due(){
         setup1();
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "foo";
         col.addNote(note);
         col.crt -= 86400 * 10;
         col.flush();
         col.sched.reset();
         c = col.sched.getCard();
         col.sched.answerCard(c, 3);
         col.sched.answerCard(c, 3);
         // should have ivl of 1, due on day 11
         assert c.ivl == 1;
         assert c.due == 11;
         assert col.sched.today == 10;
         assert c.due - col.sched.today == 1;
         // export
         e = AnkiExporter(col);
         e.includeSched = True;
         fd, newname = tempfile.mkstemp(prefix="ankitest", suffix=".anki2");
         newname = str(newname);
         os.close(fd);
         os.unlink(newname);
         e.exportInto(newname);
         // importing into a new deck, the due date should be equivalent
         deck2 = getEmptyCol();
         imp = Anki2Importer(deck2, newname);
         imp.run();
         c = deck2.getCard(c.id);
         deck2.sched.reset();
         assert c.due - deck2.sched.today == 1;
     }

     @Test
     public void test_export_textcard(){
     //     setup1()
     //     e = TextCardExporter(col)
     //     note = unicode(tempfile.mkstemp(prefix="ankitest")[1])
     //     os.unlink(note)
     //     e.exportInto(note)
     //     e.includeTags = True
     //     e.exportInto(note)


     }

     @Test
     public void test_export_textnote(){
         setup1();
         e = TextNoteExporter(col);
         fd, note = tempfile.mkstemp(prefix="ankitest");
         note = str(note);
         os.close(fd);
         os.unlink(note);
         e.exportInto(note);
         with open(note) as file:
             assert file.readline() == "foo\tbar<br>\ttag tag2\n";
         e.includeTags = False;
         e.includeHTML = False;
         e.exportInto(note);
         with open(note) as file:
             assert file.readline() == "foo\tbar\n";
     }

     @Test
     public void test_exporters(){
         assert "*.apkg" in str(exporters());
     /*****************
      ** Find         *
      *****************/
     class DummyCollection:
         def weakref(self):
             return None;
     }

     @Test
     public void test_findCards(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "dog";
         note["Back"] = "cat";
         note.tags.append("monkey animal_1 * %");
         col.addNote(note);
         f1id = note.id;
         firstCardId = note.cards()[0].id;
         note = col.newNote();
         note["Front"] = "goats are fun";
         note["Back"] = "sheep";
         note.tags.append("sheep goat horse animal11");
         col.addNote(note);
         f2id = note.id;
         note = col.newNote();
         note["Front"] = "cat";
         note["Back"] = "sheep";
         col.addNote(note);
         catCard = note.cards()[0];
         m = col.models.current();
         m = col.models.copy(m);
         mm = col.models;
         t = mm.newTemplate("Reverse");
         t["qfmt"] = "{{Back}}";
         t["afmt"] = "{{Front}}";
         mm.addTemplate(m, t);
         mm.save(m);
         note = col.newNote();
         note["Front"] = "test";
         note["Back"] = "foo bar";
         col.addNote(note);
         col.save();
         latestCardIds = [c.id for c in note.cards()];
         // tag searches
         assert len(col.findCards("tag:*")) == 5;
         assert len(col.findCards("tag:\\*")) == 1;
         assert len(col.findCards("tag:%")) == 5;
         assert len(col.findCards("tag:\\%")) == 1;
         assert len(col.findCards("tag:animal_1")) == 2;
         assert len(col.findCards("tag:animal\\_1")) == 1;
         assert not col.findCards("tag:donkey");
         assert len(col.findCards("tag:sheep")) == 1;
         assert len(col.findCards("tag:sheep tag:goat")) == 1;
         assert len(col.findCards("tag:sheep tag:monkey")) == 0;
         assert len(col.findCards("tag:monkey")) == 1;
         assert len(col.findCards("tag:sheep -tag:monkey")) == 1;
         assert len(col.findCards("-tag:sheep")) == 4;
         col.tags.bulkAdd(col.db.list("select id from notes"), "foo bar");
         assert len(col.findCards("tag:foo")) == len(col.findCards("tag:bar")) == 5;
         col.tags.bulkRem(col.db.list("select id from notes"), "foo");
         assert len(col.findCards("tag:foo")) == 0;
         assert len(col.findCards("tag:bar")) == 5;
         // text searches
         assert len(col.findCards("cat")) == 2;
         assert len(col.findCards("cat -dog")) == 1;
         assert len(col.findCards("cat -dog")) == 1;
         assert len(col.findCards("are goats")) == 1;
         assert len(col.findCards('"are goats"')) == 0;
         assert len(col.findCards('"goats are"')) == 1;
         // card states
         c = note.cards()[0];
         c.queue = c.type = CARD_TYPE_REV;
         assert col.findCards("is:review") == [];
         c.flush();
         assert col.findCards("is:review") == [c.id];
         assert col.findCards("is:due") == [];
         c.due = 0;
         c.queue = QUEUE_TYPE_REV;
         c.flush();
         assert col.findCards("is:due") == [c.id];
         assert len(col.findCards("-is:due")) == 4;
         c.queue = -1;
         // ensure this card gets a later mod time
         c.flush();
         col.db.execute("update cards set mod = mod + 1 where id = ?", c.id);
         assert col.findCards("is:suspended") == [c.id];
         // nids
         assert col.findCards("nid:54321") == [];
         assert len(col.findCards(f"nid:{note.id}")) == 2;
         assert len(col.findCards(f"nid:{f1id},{f2id}")) == 2;
         // templates
         assert len(col.findCards("card:foo")) == 0;
         assert len(col.findCards('"card:card 1"')) == 4;
         assert len(col.findCards("card:reverse")) == 1;
         assert len(col.findCards("card:1")) == 4;
         assert len(col.findCards("card:2")) == 1;
         // fields
         assert len(col.findCards("front:dog")) == 1;
         assert len(col.findCards("-front:dog")) == 4;
         assert len(col.findCards("front:sheep")) == 0;
         assert len(col.findCards("back:sheep")) == 2;
         assert len(col.findCards("-back:sheep")) == 3;
         assert len(col.findCards("front:do")) == 0;
         assert len(col.findCards("front:*")) == 5;
         // ordering
         col.conf["sortType"] = "noteCrt";
         col.flush();
         assert col.findCards("front:*", order=True)[-1] in latestCardIds;
         assert col.findCards("", order=True)[-1] in latestCardIds;
         col.conf["sortType"] = "noteFld";
         col.flush();
         assert col.findCards("", order=True)[0] == catCard.id;
         assert col.findCards("", order=True)[-1] in latestCardIds;
         col.conf["sortType"] = "cardMod";
         col.flush();
         assert col.findCards("", order=True)[-1] in latestCardIds;
         assert col.findCards("", order=True)[0] == firstCardId;
         col.conf["sortBackwards"] = True;
         col.flush();
         assert col.findCards("", order=True)[0] in latestCardIds;
         assert (;
             col.find_cards("", order=BuiltinSortKind.CARD_DUE, reverse=False)[0];
             == firstCardId;
     );
         assert (;
             col.find_cards("", order=BuiltinSortKind.CARD_DUE, reverse=True)[0];
             != firstCardId;
     );
         // model
         assert len(col.findCards("note:basic")) == 3;
         assert len(col.findCards("-note:basic")) == 2;
         assert len(col.findCards("-note:foo")) == 5;
         // col
         assert len(col.findCards("deck:default")) == 5;
         assert len(col.findCards("-deck:default")) == 0;
         assert len(col.findCards("-deck:foo")) == 5;
         assert len(col.findCards("deck:def*")) == 5;
         assert len(col.findCards("deck:*EFAULT")) == 5;
         assert len(col.findCards("deck:*cefault")) == 0;
         // full search
         note = col.newNote();
         note["Front"] = "hello<b>world</b>";
         note["Back"] = "abc";
         col.addNote(note);
         // as it's the sort field, it matches
         assert len(col.findCards("helloworld")) == 2;
         // assert len(col.findCards("helloworld", full=True)) == 2
         // if we put it on the back, it won't
         (note["Front"], note["Back"]) = (note["Back"], note["Front"]);
         note.flush();
         assert len(col.findCards("helloworld")) == 0;
         // assert len(col.findCards("helloworld", full=True)) == 2
         // assert len(col.findCards("back:helloworld", full=True)) == 2
         // searching for an invalid special tag should not error
         with pytest.raises(Exception):
             len(col.findCards("is:invalid"));
         // should be able to limit to parent col, no children
         id = col.db.scalar("select id from cards limit 1");
         col.db.execute(;
             "update cards set did = ? where id = ?", col.decks.id("Default::Child"), id;
     );
         col.save();
         assert len(col.findCards("deck:default")) == 7;
         assert len(col.findCards("deck:default::child")) == 1;
         assert len(col.findCards("deck:default -deck:default::*")) == 6;
         // properties
         id = col.db.scalar("select id from cards limit 1");
         col.db.execute(;
             "update cards set queue=2, ivl=10, reps=20, due=30, factor=2200 ";
             "where id = ?",;
             id,;
     );
         assert len(col.findCards("prop:ivl>5")) == 1;
         assert len(col.findCards("prop:ivl<5")) > 1;
         assert len(col.findCards("prop:ivl>=5")) == 1;
         assert len(col.findCards("prop:ivl=9")) == 0;
         assert len(col.findCards("prop:ivl=10")) == 1;
         assert len(col.findCards("prop:ivl!=10")) > 1;
         assert len(col.findCards("prop:due>0")) == 1;
         // due dates should work
         assert len(col.findCards("prop:due=29")) == 0;
         assert len(col.findCards("prop:due=30")) == 1;
         // ease factors
         assert len(col.findCards("prop:ease=2.3")) == 0;
         assert len(col.findCards("prop:ease=2.2")) == 1;
         assert len(col.findCards("prop:ease>2")) == 1;
         assert len(col.findCards("-prop:ease>2")) > 1;
         // recently failed
         if not isNearCutoff():
             assert len(col.findCards("rated:1:1")) == 0;
             assert len(col.findCards("rated:1:2")) == 0;
             c = col.sched.getCard();
             col.sched.answerCard(c, 2);
             assert len(col.findCards("rated:1:1")) == 0;
             assert len(col.findCards("rated:1:2")) == 1;
             c = col.sched.getCard();
             col.sched.answerCard(c, 1);
             assert len(col.findCards("rated:1:1")) == 1;
             assert len(col.findCards("rated:1:2")) == 1;
             assert len(col.findCards("rated:1")) == 2;
             assert len(col.findCards("rated:0:2")) == 0;
             assert len(col.findCards("rated:2:2")) == 1;
             // added
             assert len(col.findCards("added:0")) == 0;
             col.db.execute("update cards set id = id - 86400*1000 where id = ?", id);
             assert len(col.findCards("added:1")) == col.cardCount() - 1;
             assert len(col.findCards("added:2")) == col.cardCount();
         else:
             print("some find tests disabled near cutoff");
         // empty field
         assert len(col.findCards("front:")) == 0;
         note = col.newNote();
         note["Front"] = "";
         note["Back"] = "abc2";
         assert col.addNote(note) == 1;
         assert len(col.findCards("front:")) == 1;
         // OR searches and nesting
         assert len(col.findCards("tag:monkey or tag:sheep")) == 2;
         assert len(col.findCards("(tag:monkey OR tag:sheep)")) == 2;
         assert len(col.findCards("-(tag:monkey OR tag:sheep)")) == 6;
         assert len(col.findCards("tag:monkey or (tag:sheep sheep)")) == 2;
         assert len(col.findCards("tag:monkey or (tag:sheep octopus)")) == 1;
         // flag
         with pytest.raises(Exception):
             col.findCards("flag:12");
     }

     @Test
     public void test_findReplace(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "foo";
         note["Back"] = "bar";
         col.addNote(note);
         note2 = col.newNote();
         note2["Front"] = "baz";
         note2["Back"] = "foo";
         col.addNote(note2);
         nids = [note.id, note2.id];
         // should do nothing
         assert col.findReplace(nids, "abc", "123") == 0;
         // global replace
         assert col.findReplace(nids, "foo", "qux") == 2;
         note.load();
         assert note["Front"] == "qux";
         note2.load();
         assert note2["Back"] == "qux";
         // single field replace
         assert col.findReplace(nids, "qux", "foo", field="Front") == 1;
         note.load();
         assert note["Front"] == "foo";
         note2.load();
         assert note2["Back"] == "qux";
         // regex replace
         assert col.findReplace(nids, "B.r", "reg") == 0;
         note.load();
         assert note["Back"] != "reg";
         assert col.findReplace(nids, "B.r", "reg", regex=True) == 1;
         note.load();
         assert note["Back"] == "reg";
     }

     @Test
     public void test_findDupes(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "foo";
         note["Back"] = "bar";
         col.addNote(note);
         note2 = col.newNote();
         note2["Front"] = "baz";
         note2["Back"] = "bar";
         col.addNote(note2);
         f3 = col.newNote();
         f3["Front"] = "quux";
         f3["Back"] = "bar";
         col.addNote(f3);
         f4 = col.newNote();
         f4["Front"] = "quuux";
         f4["Back"] = "nope";
         col.addNote(f4);
         r = col.findDupes("Back");
         assert r[0][0] == "bar";
         assert len(r[0][1]) == 3;
         // valid search
         r = col.findDupes("Back", "bar");
         assert r[0][0] == "bar";
         assert len(r[0][1]) == 3;
         // excludes everything
         r = col.findDupes("Back", "invalid");
         assert not r;
         // front isn't dupe
         assert col.findDupes("Front") == [];
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
         tmp = getEmptyCol();
         // add a note that references a sound
         n = tmp.newNote();
         n["Front"] = "[sound:foo.mp3]";
         mid = n.model()["id"];
         tmp.addNote(n);
         // add that sound to media folder
         with open(os.path.join(tmp.media.dir(), "foo.mp3"), "w") as note:
             note.write("foo");
         tmp.close();
         // it should be imported correctly into an empty deck
         empty = getEmptyCol();
         imp = Anki2Importer(empty, tmp.path);
         imp.run();
         assert os.listdir(empty.media.dir()) == ["foo.mp3"];
         // and importing again will not duplicate, as the file content matches
         empty.remove_cards_and_orphaned_notes(empty.db.list("select id from cards"));
         imp = Anki2Importer(empty, tmp.path);
         imp.run();
         assert os.listdir(empty.media.dir()) == ["foo.mp3"];
         n = empty.getNote(empty.db.scalar("select id from notes"));
         assert "foo.mp3" in n.fields[0];
         // if the local file content is different, and import should trigger a
         // rename
         empty.remove_cards_and_orphaned_notes(empty.db.list("select id from cards"));
         with open(os.path.join(empty.media.dir(), "foo.mp3"), "w") as note:
             note.write("bar");
         imp = Anki2Importer(empty, tmp.path);
         imp.run();
         assert sorted(os.listdir(empty.media.dir())) == ["foo.mp3", "foo_%s.mp3" % mid];
         n = empty.getNote(empty.db.scalar("select id from notes"));
         assert "_" in n.fields[0];
         // if the localized media file already exists, we rewrite the note and
         // media
         empty.remove_cards_and_orphaned_notes(empty.db.list("select id from cards"));
         with open(os.path.join(empty.media.dir(), "foo.mp3"), "w") as note:
             note.write("bar");
         imp = Anki2Importer(empty, tmp.path);
         imp.run();
         assert sorted(os.listdir(empty.media.dir())) == ["foo.mp3", "foo_%s.mp3" % mid];
         assert sorted(os.listdir(empty.media.dir())) == ["foo.mp3", "foo_%s.mp3" % mid];
         n = empty.getNote(empty.db.scalar("select id from notes"));
         assert "_" in n.fields[0];
     }

     @Test
     public void test_apkg(){
         tmp = getEmptyCol();
         apkg = str(os.path.join(testDir, "support/media.apkg"));
         imp = AnkiPackageImporter(tmp, apkg);
         assert os.listdir(tmp.media.dir()) == [];
         imp.run();
         assert os.listdir(tmp.media.dir()) == ["foo.wav"];
         // importing again should be idempotent in terms of media
         tmp.remove_cards_and_orphaned_notes(tmp.db.list("select id from cards"));
         imp = AnkiPackageImporter(tmp, apkg);
         imp.run();
         assert os.listdir(tmp.media.dir()) == ["foo.wav"];
         // but if the local file has different data, it will rename
         tmp.remove_cards_and_orphaned_notes(tmp.db.list("select id from cards"));
         with open(os.path.join(tmp.media.dir(), "foo.wav"), "w") as note:
             note.write("xyz");
         imp = AnkiPackageImporter(tmp, apkg);
         imp.run();
         assert len(os.listdir(tmp.media.dir())) == 2;
     }

     @Test
     public void test_anki2_diffmodel_templates(){
         // different from the above as this one tests only the template text being
         // changed, not the number of cards/fields
         dst = getEmptyCol();
         // import the first version of the model
         tmp = getUpgradeDeckPath("diffmodeltemplates-1.apkg");
         imp = AnkiPackageImporter(dst, tmp);
         imp.dupeOnSchemaChange = True;
         imp.run();
         // then the version with updated template
         tmp = getUpgradeDeckPath("diffmodeltemplates-2.apkg");
         imp = AnkiPackageImporter(dst, tmp);
         imp.dupeOnSchemaChange = True;
         imp.run();
         // collection should contain the note we imported
         assert dst.noteCount() == 1;
         // the front template should contain the text added in the 2nd package
         tcid = dst.findCards("")[0]  // only 1 note in collection
         tnote = dst.getCard(tcid).note();
         assert "Changed Front Template" in tnote.cards()[0].template()["qfmt"];
     }

     @Test
     public void test_anki2_updates(){
         // create a new empty deck
         dst = getEmptyCol();
         tmp = getUpgradeDeckPath("update1.apkg");
         imp = AnkiPackageImporter(dst, tmp);
         imp.run();
         assert imp.dupes == 0;
         assert imp.added == 1;
         assert imp.updated == 0;
         // importing again should be idempotent
         imp = AnkiPackageImporter(dst, tmp);
         imp.run();
         assert imp.dupes == 1;
         assert imp.added == 0;
         assert imp.updated == 0;
         // importing a newer note should update
         assert dst.noteCount() == 1;
         assert dst.db.scalar("select flds from notes").startswith("hello");
         tmp = getUpgradeDeckPath("update2.apkg");
         imp = AnkiPackageImporter(dst, tmp);
         imp.run();
         assert imp.dupes == 0;
         assert imp.added == 0;
         assert imp.updated == 1;
         assert dst.noteCount() == 1;
         assert dst.db.scalar("select flds from notes").startswith("goodbye");
     }

     @Test
     public void test_csv(){
         col = getEmptyCol();
         file = str(os.path.join(testDir, "support/text-2fields.txt"));
         i = TextImporter(col, file);
         i.initMapping();
         i.run();
         // four problems - too many & too few fields, a missing front, and a
         // duplicate entry
         assert len(i.log) == 5;
         assert i.total == 5;
         // if we run the import again, it should update instead
         i.run();
         assert len(i.log) == 10;
         assert i.total == 5;
         // but importing should not clobber tags if they're unmapped
         n = col.getNote(col.db.scalar("select id from notes"));
         n.addTag("test");
         n.flush();
         i.run();
         n.load();
         assert n.tags == ["test"];
         // if add-only mode, count will be 0
         i.importMode = 1;
         i.run();
         assert i.total == 0;
         // and if dupes mode, will reimport everything
         assert col.cardCount() == 5;
         i.importMode = 2;
         i.run();
         // includes repeated field
         assert i.total == 6;
         assert col.cardCount() == 11;
         col.close();
     }

     @Test
     public void test_csv2(){
         col = getEmptyCol();
         mm = col.models;
         m = mm.current();
         note = mm.newField("Three");
         mm.addField(m, note);
         mm.save(m);
         n = col.newNote();
         n["Front"] = "1";
         n["Back"] = "2";
         n["Three"] = "3";
         col.addNote(n);
         // an update with unmapped fields should not clobber those fields
         file = str(os.path.join(testDir, "support/text-update.txt"));
         i = TextImporter(col, file);
         i.initMapping();
         i.run();
         n.load();
         assert n["Front"] == "1";
         assert n["Back"] == "x";
         assert n["Three"] == "3";
         col.close();
     }

     @Test
     public void test_tsv_tag_modified(){
         col = getEmptyCol();
         mm = col.models;
         m = mm.current();
         note = mm.newField("Top");
         mm.addField(m, note);
         mm.save(m);
         n = col.newNote();
         n["Front"] = "1";
         n["Back"] = "2";
         n["Top"] = "3";
         n.addTag("four");
         col.addNote(n);

         // https://stackoverflow.com/questions/23212435/permission-denied-to-write-to-my-temporary-file
         with NamedTemporaryFile(mode="w", delete=False) as tf:
             tf.write("1\tb\tc\n");
             tf.flush();
             i = TextImporter(col, tf.name);
             i.initMapping();
             i.tagModified = "boom";
             i.run();
             clear_tempfile(tf);

         n.load();
         assert n["Front"] == "1";
         assert n["Back"] == "b";
         assert n["Top"] == "c";
         assert "four" in n.tags;
         assert "boom" in n.tags;
         assert len(n.tags) == 2;
         assert i.updateCount == 1;

         col.close();
     }

     @Test
     public void test_tsv_tag_multiple_tags(){
         col = getEmptyCol();
         mm = col.models;
         m = mm.current();
         note = mm.newField("Top");
         mm.addField(m, note);
         mm.save(m);
         n = col.newNote();
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
             i = TextImporter(col, tf.name);
             i.initMapping();
             i.tagModified = "five six";
             i.run();
             clear_tempfile(tf);

         n.load();
         assert n["Front"] == "1";
         assert n["Back"] == "b";
         assert n["Top"] == "c";
         assert list(sorted(n.tags)) == list(sorted(["four", "five", "six"]));

         col.close();
     }

     @Test
     public void test_csv_tag_only_if_modified(){
         col = getEmptyCol();
         mm = col.models;
         m = mm.current();
         note = mm.newField("Left");
         mm.addField(m, note);
         mm.save(m);
         n = col.newNote();
         n["Front"] = "1";
         n["Back"] = "2";
         n["Left"] = "3";
         col.addNote(n);

         // https://stackoverflow.com/questions/23212435/permission-denied-to-write-to-my-temporary-file
         with NamedTemporaryFile(mode="w", delete=False) as tf:
             tf.write("1,2,3\n");
             tf.flush();
             i = TextImporter(col, tf.name);
             i.initMapping();
             i.tagModified = "right";
             i.run();
             clear_tempfile(tf);

         n.load();
         assert n.tags == [];
         assert i.updateCount == 0;

         col.close();
     }

     @pytest.mark.filterwarnings("ignore:Using or importing the ABCs")
     @Test
     public void test_supermemo_xml_01_unicode(){
         col = getEmptyCol();
         file = str(os.path.join(testDir, "support/supermemo1.xml"));
         i = SupermemoXmlImporter(col, file);
         // i.META.logToStdOutput = True
         i.run();
         assert i.total == 1;
         cid = col.db.scalar("select id from cards");
         c = col.getCard(cid);
         // Applies A Factor-to-E Factor conversion
         assert c.factor == 2879;
         assert c.reps == 7;
         col.close();
     }

     @Test
     public void test_mnemo(){
         col = getEmptyCol();
         file = str(os.path.join(testDir, "support/mnemo.db"));
         i = MnemosyneImporter(col, file);
         i.run();
         assert col.cardCount() == 7;
         assert "a_longer_tag" in col.tags.all();
         assert col.db.scalar("select count() from cards where type = 0") == 1;
         col.close();
     }

     /*****************
      ** Flags        *
      *****************/

     @Test
     public void test_flags(){
         col = getEmptyCol();
         n = col.newNote();
         n["Front"] = "one";
         n["Back"] = "two";
         cnt = col.addNote(n);
         c = n.cards()[0];
         // make sure higher bits are preserved
         origBits = 0b101 << 3;
         c.flags = origBits;
         c.flush();
         // no flags to start with
         assert c.userFlag() == 0;
         assert len(col.findCards("flag:0")) == 1;
         assert len(col.findCards("flag:1")) == 0;
         // set flag 2
         col.setUserFlag(2, [c.id]);
         c.load();
         assert c.userFlag() == 2;
         assert c.flags & origBits == origBits;
         assert len(col.findCards("flag:0")) == 0;
         assert len(col.findCards("flag:2")) == 1;
         assert len(col.findCards("flag:3")) == 0;
         // change to 3
         col.setUserFlag(3, [c.id]);
         c.load();
         assert c.userFlag() == 3;
         // unset
         col.setUserFlag(0, [c.id]);
         c.load();
         assert c.userFlag() == 0;

         // should work with Cards method as well
         c.setUserFlag(2);
         assert c.userFlag() == 2;
         c.setUserFlag(3);
         assert c.userFlag() == 3;
         c.setUserFlag(0);
         assert c.userFlag() == 0;
     }
     /*****************
      ** Media        *
      *****************/
     // copying files to media folder

     @Test
     public void test_add(){
         col = getEmptyCol();
         dir = tempfile.mkdtemp(prefix="anki");
         path = os.path.join(dir, "foo.jpg");
         with open(path, "w") as note:
             note.write("hello");
         // new file, should preserve name
         assert col.media.addFile(path) == "foo.jpg";
         // adding the same file again should not create a duplicate
         assert col.media.addFile(path) == "foo.jpg";
         // but if it has a different sha1, it should
         with open(path, "w") as note:
             note.write("world");
         assert col.media.addFile(path) == "foo-7c211433f02071597741e6ff5a8ea34789abbf43.jpg";
     }

     @Test
     public void test_strings(){
         col = getEmptyCol();
         mf = col.media.filesInStr;
         mid = col.models.current()["id"];
         assert mf(mid, "aoeu") == [];
         assert mf(mid, "aoeu<img src='foo.jpg'>ao") == ["foo.jpg"];
         assert mf(mid, "aoeu<img src='foo.jpg' style='test'>ao") == ["foo.jpg"];
         assert mf(mid, "aoeu<img src='foo.jpg'><img src=\"bar.jpg\">ao") == [;
             "foo.jpg",;
             "bar.jpg",;
     ];
         assert mf(mid, "aoeu<img src=foo.jpg style=bar>ao") == ["foo.jpg"];
         assert mf(mid, "<img src=one><img src=two>") == ["one", "two"];
         assert mf(mid, 'aoeu<img src="foo.jpg">ao') == ["foo.jpg"];
         assert mf(mid, 'aoeu<img src="foo.jpg"><img class=yo src=fo>ao') == [;
             "foo.jpg",;
             "fo",;
     ];
         assert mf(mid, "aou[sound:foo.mp3]aou") == ["foo.mp3"];
         sp = col.media.strip;
         assert sp("aoeu") == "aoeu";
         assert sp("aoeu[sound:foo.mp3]aoeu") == "aoeuaoeu";
         assert sp("a<img src=yo>oeu") == "aoeu";
         es = col.media.escapeImages;
         assert es("aoeu") == "aoeu";
         assert es("<img src='http://foo.com'>") == "<img src='http://foo.com'>";
         assert es('<img src="foo bar.jpg">') == '<img src="foo%20bar.jpg">';
     }

     @Test
     public void test_deckIntegration(){
         col = getEmptyCol();
         // create a media dir
         col.media.dir();
         // put a file into it
         file = str(os.path.join(testDir, "support/fake.png"));
         col.media.addFile(file);
         // add a note which references it
         note = col.newNote();
         note["Front"] = "one";
         note["Back"] = "<img src='fake.png'>";
         col.addNote(note);
         // and one which references a non-existent file
         note = col.newNote();
         note["Front"] = "one";
         note["Back"] = "<img src='fake2.png'>";
         col.addNote(note);
         // and add another file which isn't used
         with open(os.path.join(col.media.dir(), "foo.jpg"), "w") as note:
             note.write("test");
         // check media
         ret = col.media.check();
         assert ret.missing == ["fake2.png"];
         assert ret.unused == ["foo.jpg"];
     }
     /*****************
      ** Models       *
      *****************/

     @Test
     public void test_modelDelete(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "1";
         note["Back"] = "2";
         col.addNote(note);
         assert col.cardCount() == 1;
         col.models.rem(col.models.current());
         assert col.cardCount() == 0;
     }

     @Test
     public void test_modelCopy(){
         col = getEmptyCol();
         m = col.models.current();
         m2 = col.models.copy(m);
         assert m2["name"] == "Basic copy";
         assert m2["id"] != m["id"];
         assert len(m2["flds"]) == 2;
         assert len(m["flds"]) == 2;
         assert len(m2["flds"]) == len(m["flds"]);
         assert len(m["tmpls"]) == 1;
         assert len(m2["tmpls"]) == 1;
         assert col.models.scmhash(m) == col.models.scmhash(m2);
     }

     @Test
     public void test_fields(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "1";
         note["Back"] = "2";
         col.addNote(note);
         m = col.models.current();
         // make sure renaming a field updates the templates
         col.models.renameField(m, m["flds"][0], "NewFront");
         assert "{{NewFront}}" in m["tmpls"][0]["qfmt"];
         h = col.models.scmhash(m);
         // add a field
         note = col.models.newField("foo");
         col.models.addField(m, note);
         assert col.getNote(col.models.nids(m)[0]).fields == ["1", "2", ""];
         assert col.models.scmhash(m) != h;
         // rename it
         note = m["flds"][2];
         col.models.renameField(m, note, "bar");
         assert col.getNote(col.models.nids(m)[0])["bar"] == "";
         // delete back
         col.models.remField(m, m["flds"][1]);
         assert col.getNote(col.models.nids(m)[0]).fields == ["1", ""];
         // move 0 -> 1
         col.models.moveField(m, m["flds"][0], 1);
         assert col.getNote(col.models.nids(m)[0]).fields == ["", "1"];
         // move 1 -> 0
         col.models.moveField(m, m["flds"][1], 0);
         assert col.getNote(col.models.nids(m)[0]).fields == ["1", ""];
         // add another and put in middle
         note = col.models.newField("baz");
         col.models.addField(m, note);
         note = col.getNote(col.models.nids(m)[0]);
         note["baz"] = "2";
         note.flush();
         assert col.getNote(col.models.nids(m)[0]).fields == ["1", "", "2"];
         // move 2 -> 1
         col.models.moveField(m, m["flds"][2], 1);
         assert col.getNote(col.models.nids(m)[0]).fields == ["1", "2", ""];
         // move 0 -> 2
         col.models.moveField(m, m["flds"][0], 2);
         assert col.getNote(col.models.nids(m)[0]).fields == ["2", "", "1"];
         // move 0 -> 1
         col.models.moveField(m, m["flds"][0], 1);
         assert col.getNote(col.models.nids(m)[0]).fields == ["", "2", "1"];
     }

     @Test
     public void test_templates(){
         col = getEmptyCol();
         m = col.models.current();
         mm = col.models;
         t = mm.newTemplate("Reverse");
         t["qfmt"] = "{{Back}}";
         t["afmt"] = "{{Front}}";
         mm.addTemplate(m, t);
         mm.save(m);
         note = col.newNote();
         note["Front"] = "1";
         note["Back"] = "2";
         col.addNote(note);
         assert col.cardCount() == 2;
         (c, c2) = note.cards();
         // first card should have first ord
         assert c.ord == 0;
         assert c2.ord == 1;
         // switch templates
         col.models.moveTemplate(m, c.template(), 1);
         c.load();
         c2.load();
         assert c.ord == 1;
         assert c2.ord == 0;
         // removing a template should delete its cards
         col.models.remTemplate(m, m["tmpls"][0]);
         assert col.cardCount() == 1;
         // and should have updated the other cards' ordinals
         c = note.cards()[0];
         assert c.ord == 0;
         assert stripHTML(c.q()) == "1";
         // it shouldn't be possible to orphan notes by removing templates
         t = mm.newTemplate("template name");
         mm.addTemplate(m, t);
         col.models.remTemplate(m, m["tmpls"][0]);
         assert (;
             col.db.scalar(;
                 "select count() from cards where nid not in (select id from notes)";
         );
             == 0;
     );
     }

     @Test
     public void test_cloze_ordinals(){
         col = getEmptyCol();
         col.models.setCurrent(col.models.byName("Cloze"));
         m = col.models.current();
         mm = col.models;

         // We replace the default Cloze template
         t = mm.newTemplate("ChainedCloze");
         t["qfmt"] = "{{text:cloze:Text}}";
         t["afmt"] = "{{text:cloze:Text}}";
         mm.addTemplate(m, t);
         mm.save(m);
         col.models.remTemplate(m, m["tmpls"][0]);

         note = col.newNote();
         note["Text"] = "{{c1::firstQ::firstA}}{{c2::secondQ::secondA}}";
         col.addNote(note);
         assert col.cardCount() == 2;
         (c, c2) = note.cards();
         // first card should have first ord
         assert c.ord == 0;
         assert c2.ord == 1;
     }

     @Test
     public void test_text(){
         col = getEmptyCol();
         m = col.models.current();
         m["tmpls"][0]["qfmt"] = "{{text:Front}}";
         col.models.save(m);
         note = col.newNote();
         note["Front"] = "hello<b>world";
         col.addNote(note);
         assert "helloworld" in note.cards()[0].q();
     }

     @Test
     public void test_cloze(){
         col = getEmptyCol();
         col.models.setCurrent(col.models.byName("Cloze"));
         note = col.newNote();
         assert note.model()["name"] == "Cloze";
         // a cloze model with no clozes is not empty
         note["Text"] = "nothing";
         assert col.addNote(note);
         // try with one cloze
         note = col.newNote();
         note["Text"] = "hello {{c1::world}}";
         assert col.addNote(note) == 1;
         assert "hello <span class=cloze>[...]</span>" in note.cards()[0].q();
         assert "hello <span class=cloze>world</span>" in note.cards()[0].a();
         // and with a comment
         note = col.newNote();
         note["Text"] = "hello {{c1::world::typical}}";
         assert col.addNote(note) == 1;
         assert "<span class=cloze>[typical]</span>" in note.cards()[0].q();
         assert "<span class=cloze>world</span>" in note.cards()[0].a();
         // and with 2 clozes
         note = col.newNote();
         note["Text"] = "hello {{c1::world}} {{c2::bar}}";
         assert col.addNote(note) == 2;
         (c1, c2) = note.cards();
         assert "<span class=cloze>[...]</span> bar" in c1.q();
         assert "<span class=cloze>world</span> bar" in c1.a();
         assert "world <span class=cloze>[...]</span>" in c2.q();
         assert "world <span class=cloze>bar</span>" in c2.a();
         // if there are multiple answers for a single cloze, they are given in a
         // list
         note = col.newNote();
         note["Text"] = "a {{c1::b}} {{c1::c}}";
         assert col.addNote(note) == 1;
         assert "<span class=cloze>b</span> <span class=cloze>c</span>" in (;
             note.cards()[0].a();
     );
         // if we add another cloze, a card should be generated
         cnt = col.cardCount();
         note["Text"] = "{{c2::hello}} {{c1::foo}}";
         note.flush();
         assert col.cardCount() == cnt + 1;
         // 0 or negative indices are not supported
         note["Text"] += "{{c0::zero}} {{c-1:foo}}";
         note.flush();
         assert len(note.cards()) == 2;
     }

     @Test
     public void test_cloze_mathjax(){
         col = getEmptyCol();
         col.models.setCurrent(col.models.byName("Cloze"));
         note = col.newNote();
         note[;
             "Text";
         ] = r"{{c1::ok}} \(2^2\) {{c2::not ok}} \(2^{{c3::2}}\) \(x^3\) {{c4::blah}} {{c5::text with \(x^2\) jax}}";
         assert col.addNote(note);
         assert len(note.cards()) == 5;
         assert "class=cloze" in note.cards()[0].q();
         assert "class=cloze" in note.cards()[1].q();
         assert "class=cloze" not in note.cards()[2].q();
         assert "class=cloze" in note.cards()[3].q();
         assert "class=cloze" in note.cards()[4].q();

         note = col.newNote();
         note["Text"] = r"\(a\) {{c1::b}} \[ {{c1::c}} \]";
         assert col.addNote(note);
         assert len(note.cards()) == 1;
         assert (;
             note.cards()[0];
             .q();
             .endswith(r"\(a\) <span class=cloze>[...]</span> \[ [...] \]");
     );
     }

     @Test
     public void test_typecloze(){
         col = getEmptyCol();
         m = col.models.byName("Cloze");
         col.models.setCurrent(m);
         m["tmpls"][0]["qfmt"] = "{{cloze:Text}}{{type:cloze:Text}}";
         col.models.save(m);
         note = col.newNote();
         note["Text"] = "hello {{c1::world}}";
         col.addNote(note);
         assert "[[type:cloze:Text]]" in note.cards()[0].q();
     }

     @Test
     public void test_chained_mods(){
         col = getEmptyCol();
         col.models.setCurrent(col.models.byName("Cloze"));
         m = col.models.current();
         mm = col.models;

         // We replace the default Cloze template
         t = mm.newTemplate("ChainedCloze");
         t["qfmt"] = "{{cloze:text:Text}}";
         t["afmt"] = "{{cloze:text:Text}}";
         mm.addTemplate(m, t);
         mm.save(m);
         col.models.remTemplate(m, m["tmpls"][0]);

         note = col.newNote();
         q1 = '<span style="color:red">phrase</span>';
         a1 = "<b>sentence</b>";
         q2 = '<span style="color:red">en chaine</span>';
         a2 = "<i>chained</i>";
         note["Text"] = "This {{c1::%s::%s}} demonstrates {{c1::%s::%s}} clozes." % (;
             q1,;
             a1,;
             q2,;
             a2,;
     );
         assert col.addNote(note) == 1;
         assert (;
             "This <span class=cloze>[sentence]</span> demonstrates <span class=cloze>[chained]</span> clozes.";
             in note.cards()[0].q();
     );
         assert (;
             "This <span class=cloze>phrase</span> demonstrates <span class=cloze>en chaine</span> clozes.";
             in note.cards()[0].a();
     );
     }

     @Test
     public void test_modelChange(){
         col = getEmptyCol();
         cloze = col.models.byName("Cloze");
         // enable second template and add a note
         m = col.models.current();
         mm = col.models;
         t = mm.newTemplate("Reverse");
         t["qfmt"] = "{{Back}}";
         t["afmt"] = "{{Front}}";
         mm.addTemplate(m, t);
         mm.save(m);
         basic = m;
         note = col.newNote();
         note["Front"] = "note";
         note["Back"] = "b123";
         col.addNote(note);
         // switch fields
         map = {0: 1, 1: 0}
         col.models.change(basic, [note.id], basic, map, None);
         note.load();
         assert note["Front"] == "b123";
         assert note["Back"] == "note";
         // switch cards
         c0 = note.cards()[0];
         c1 = note.cards()[1];
         assert "b123" in c0.q();
         assert "note" in c1.q();
         assert c0.ord == 0;
         assert c1.ord == 1;
         col.models.change(basic, [note.id], basic, None, map);
         note.load();
         c0.load();
         c1.load();
         assert "note" in c0.q();
         assert "b123" in c1.q();
         assert c0.ord == 1;
         assert c1.ord == 0;
         // .cards() returns cards in order
         assert note.cards()[0].id == c1.id;
         // delete first card
         map = {0: None, 1: 1}
         if isWin:
             // The low precision timer on Windows reveals a race condition
             time.sleep(0.05);
         col.models.change(basic, [note.id], basic, None, map);
         note.load();
         c0.load();
         // the card was deleted
         try:
             c1.load();
             assert 0;
         except NotFoundError:
             pass;
         // but we have two cards, as a new one was generated
         assert len(note.cards()) == 2;
         // an unmapped field becomes blank
         assert note["Front"] == "b123";
         assert note["Back"] == "note";
         col.models.change(basic, [note.id], basic, map, None);
         note.load();
         assert note["Front"] == "";
         assert note["Back"] == "note";
         // another note to try model conversion
         note = col.newNote();
         note["Front"] = "f2";
         note["Back"] = "b2";
         col.addNote(note);
         counts = col.models.all_use_counts();
         assert next(c.use_count for c in counts if c.name == "Basic") == 2;
         assert next(c.use_count for c in counts if c.name == "Cloze") == 0;
         map = {0: 0, 1: 1}
         col.models.change(basic, [note.id], cloze, map, map);
         note.load();
         assert note["Text"] == "f2";
         assert len(note.cards()) == 2;
         // back the other way, with deletion of second ord
         col.models.remTemplate(basic, basic["tmpls"][1]);
         assert col.db.scalar("select count() from cards where nid = ?", note.id) == 2;
         map = {0: 0}
         col.models.change(cloze, [note.id], basic, map, map);
         assert col.db.scalar("select count() from cards where nid = ?", note.id) == 1;
     }

     @Test
     public void test_req(){
         def reqSize(model):
             if model["type"] == MODEL_CLOZE:
                 return;
             assert len(model["tmpls"]) == len(model["req"]);

         col = getEmptyCol();
         mm = col.models;
         basic = mm.byName("Basic");
         assert "req" in basic;
         reqSize(basic);
         r = basic["req"][0];
         assert r[0] == 0;
         assert r[1] in ("any", "all");
         assert r[2] == [0];
         opt = mm.byName("Basic (optional reversed card)");
         reqSize(opt);
         r = opt["req"][0];
         assert r[1] in ("any", "all");
         assert r[2] == [0];
         assert opt["req"][1] == [1, "all", [1, 2]];
         // testing any
         opt["tmpls"][1]["qfmt"] = "{{Back}}{{Add Reverse}}";
         mm.save(opt, templates=True);
         assert opt["req"][1] == [1, "any", [1, 2]];
         // testing None
         opt["tmpls"][1]["qfmt"] = "{{^Add Reverse}}{{/Add Reverse}}";
         mm.save(opt, templates=True);
         assert opt["req"][1] == [1, "none", []];

         opt = mm.byName("Basic (type in the answer)");
         reqSize(opt);
         r = opt["req"][0];
         assert r[1] in ("any", "all");
         assert r[2] == [0, 1];
     }

     /*****************
          ** SchedV1      *
      *****************/
     private Collection getEmptyCol(){
         col = getEmptyColOrig();
         col.changeSchedulerVer(1);
         return col;
     }

     @Test
     public void test_clock(){
         col = getEmptyCol();
         if (col.sched.dayCutoff - intTime()) < 10 * 60:
             raise Exception("Unit tests will fail around the day rollover.");
     }

     private boolean checkRevIvl(col, c, targetIvl) {
         min, max = col.sched._fuzzIvlRange(targetIvl);
         return min <= c.ivl <= max;
     }

     @Test
     public void test_basics(){
         col = getEmptyCol();
         col.reset();
         assert not col.sched.getCard();
     }

     @Test
     public void test_new(){
         col = getEmptyCol();
         col.reset();
         assert col.sched.newCount == 0;
         // add a note
         note = col.newNote();
         note["Front"] = "one";
         note["Back"] = "two";
         col.addNote(note);
         col.reset();
         assert col.sched.newCount == 1;
         // fetch it
         c = col.sched.getCard();
         assert c;
         assert c.queue == QUEUE_TYPE_NEW;
         assert c.type == CARD_TYPE_NEW;
         // if we answer it, it should become a learn card
         t = intTime();
         col.sched.answerCard(c, 1);
         assert c.queue == QUEUE_TYPE_LRN;
         assert c.type == CARD_TYPE_LRN;
         assert c.due >= t;

         // disabled for now, as the learn fudging makes this randomly fail
         // // the default order should ensure siblings are not seen together, and
         // // should show all cards
         // m = col.models.current(); mm = col.models
         // t = mm.newTemplate("Reverse")
         // t['qfmt'] = "{{Back}}"
         // t['afmt'] = "{{Front}}"
         // mm.addTemplate(m, t)
         // mm.save(m)
         // note = col.newNote()
         // note['Front'] = u"2"; note['Back'] = u"2"
         // col.addNote(note)
         // note = col.newNote()
         // note['Front'] = u"3"; note['Back'] = u"3"
         // col.addNote(note)
         // col.reset()
         // qs = ("2", "3", "2", "3")
         // for n in range(4):
         //     c = col.sched.getCard()
         //     assert qs[n] in c.q()
         //     col.sched.answerCard(c, 2)
     }

     @Test
     public void test_newLimits(){
         col = getEmptyCol();
         // add some notes
         deck2 = col.decks.id("Default::foo");
         for i in range(30):
             note = col.newNote();
             note["Front"] = str(i);
             if i > 4:
                 note.model()["did"] = deck2;
             col.addNote(note);
         // give the child deck a different configuration
         c2 = col.decks.add_config_returning_id("new conf");
         col.decks.setConf(col.decks.get(deck2), c2);
         col.reset();
         // both confs have defaulted to a limit of 20
         assert col.sched.newCount == 20;
         // first card we get comes from parent
         c = col.sched.getCard();
         assert c.did == 1;
         // limit the parent to 10 cards, meaning we get 10 in total
         conf1 = col.decks.confForDid(1);
         conf1["new"]["perDay"] = 10;
         col.decks.save(conf1);
         col.reset();
         assert col.sched.newCount == 10;
         // if we limit child to 4, we should get 9
         conf2 = col.decks.confForDid(deck2);
         conf2["new"]["perDay"] = 4;
         col.decks.save(conf2);
         col.reset();
         assert col.sched.newCount == 9;
     }

     @Test
     public void test_newBoxes(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         col.reset();
         c = col.sched.getCard();
         conf = col.sched._cardConf(c);
         conf["new"]["delays"] = [1, 2, 3, 4, 5];
         col.decks.save(conf);
         col.sched.answerCard(c, 2);
         // should handle gracefully
         conf["new"]["delays"] = [1];
         col.decks.save(conf);
         col.sched.answerCard(c, 2);
     }

     @Test
     public void test_learn(){
         col = getEmptyCol();
         // add a note
         note = col.newNote();
         note["Front"] = "one";
         note["Back"] = "two";
         note = col.addNote(note);
         // set as a learn card and rebuild queues
         col.db.execute("update cards set queue=0, type=0");
         col.reset();
         // sched.getCard should return it, since it's due in the past
         c = col.sched.getCard();
         assert c;
         conf = col.sched._cardConf(c);
         conf["new"]["delays"] = [0.5, 3, 10];
         col.decks.save(conf);
         // fail it
         col.sched.answerCard(c, 1);
         // it should have three reps left to graduation
         assert c.left % 1000 == 3;
         assert c.left // 1000 == 3
         // it should by due in 30 seconds
         t = round(c.due - time.time());
         assert t >= 25 and t <= 40;
         // pass it once
         col.sched.answerCard(c, 2);
         // it should by due in 3 minutes
         assert round(c.due - time.time()) in (179, 180);
         assert c.left % 1000 == 2;
         assert c.left // 1000 == 2
         // check log is accurate
         log = col.db.first("select * from revlog order by id desc");
         assert log[3] == 2;
         assert log[4] == -180;
         assert log[5] == -30;
         // pass again
         col.sched.answerCard(c, 2);
         // it should by due in 10 minutes
         assert round(c.due - time.time()) in (599, 600);
         assert c.left % 1000 == 1;
         assert c.left // 1000 == 1
         // the next pass should graduate the card
         assert c.queue == QUEUE_TYPE_LRN;
         assert c.type == CARD_TYPE_LRN;
         col.sched.answerCard(c, 2);
         assert c.queue == QUEUE_TYPE_REV;
         assert c.type == CARD_TYPE_REV;
         // should be due tomorrow, with an interval of 1
         assert c.due == col.sched.today + 1;
         assert c.ivl == 1;
         // or normal removal
         c.type = 0;
         c.queue = 1;
         col.sched.answerCard(c, 3);
         assert c.type == CARD_TYPE_REV;
         assert c.queue == QUEUE_TYPE_REV;
         assert checkRevIvl(col, c, 4);
         // revlog should have been updated each time
         assert col.db.scalar("select count() from revlog where type = 0") == 5;
         // now failed card handling
         c.type = CARD_TYPE_REV;
         c.queue = 1;
         c.odue = 123;
         col.sched.answerCard(c, 3);
         assert c.due == 123;
         assert c.type == CARD_TYPE_REV;
         assert c.queue == QUEUE_TYPE_REV;
         // we should be able to remove manually, too
         c.type = CARD_TYPE_REV;
         c.queue = 1;
         c.odue = 321;
         c.flush();
         col.sched.removeLrn();
         c.load();
         assert c.queue == QUEUE_TYPE_REV;
         assert c.due == 321;
     }

     @Test
     public void test_learn_collapsed(){
         col = getEmptyCol();
         // add 2 notes
         note = col.newNote();
         note["Front"] = "1";
         note = col.addNote(note);
         note = col.newNote();
         note["Front"] = "2";
         note = col.addNote(note);
         // set as a learn card and rebuild queues
         col.db.execute("update cards set queue=0, type=0");
         col.reset();
         // should get '1' first
         c = col.sched.getCard();
         assert c.q().endswith("1");
         // pass it so it's due in 10 minutes
         col.sched.answerCard(c, 2);
         // get the other card
         c = col.sched.getCard();
         assert c.q().endswith("2");
         // fail it so it's due in 1 minute
         col.sched.answerCard(c, 1);
         // we shouldn't get the same card again
         c = col.sched.getCard();
         assert not c.q().endswith("2");
     }

     @Test
     public void test_learn_day(){
         col = getEmptyCol();
         // add a note
         note = col.newNote();
         note["Front"] = "one";
         note = col.addNote(note);
         col.sched.reset();
         c = col.sched.getCard();
         conf = col.sched._cardConf(c);
         conf["new"]["delays"] = [1, 10, 1440, 2880];
         col.decks.save(conf);
         // pass it
         col.sched.answerCard(c, 2);
         // two reps to graduate, 1 more today
         assert c.left % 1000 == 3;
         assert c.left // 1000 == 1
         assert col.sched.counts() == (0, 1, 0);
         c = col.sched.getCard();
         ni = col.sched.nextIvl;
         assert ni(c, 2) == 86400;
         // answering it will place it in queue 3
         col.sched.answerCard(c, 2);
         assert c.due == col.sched.today + 1;
         assert c.queue == CARD_TYPE_RELEARNING;
         assert not col.sched.getCard();
         // for testing, move it back a day
         c.due -= 1;
         c.flush();
         col.reset();
         assert col.sched.counts() == (0, 1, 0);
         c = col.sched.getCard();
         // nextIvl should work
         assert ni(c, 2) == 86400 * 2;
         // if we fail it, it should be back in the correct queue
         col.sched.answerCard(c, 1);
         assert c.queue == QUEUE_TYPE_LRN;
         col.undo();
         col.reset();
         c = col.sched.getCard();
         col.sched.answerCard(c, 2);
         // simulate the passing of another two days
         c.due -= 2;
         c.flush();
         col.reset();
         // the last pass should graduate it into a review card
         assert ni(c, 2) == 86400;
         col.sched.answerCard(c, 2);
         assert c.queue == CARD_TYPE_REV and c.type == QUEUE_TYPE_REV;
         // if the lapse step is tomorrow, failing it should handle the counts
         // correctly
         c.due = 0;
         c.flush();
         col.reset();
         assert col.sched.counts() == (0, 0, 1);
         conf = col.sched._cardConf(c);
         conf["lapse"]["delays"] = [1440];
         col.decks.save(conf);
         c = col.sched.getCard();
         col.sched.answerCard(c, 1);
         assert c.queue == CARD_TYPE_RELEARNING;
         assert col.sched.counts() == (0, 0, 0);
     }

     @Test
     public void test_reviews(){
         col = getEmptyCol();
         // add a note
         note = col.newNote();
         note["Front"] = "one";
         note["Back"] = "two";
         col.addNote(note);
         // set the card up as a review card, due 8 days ago
         c = note.cards()[0];
         c.type = CARD_TYPE_REV;
         c.queue = QUEUE_TYPE_REV;
         c.due = col.sched.today - 8;
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
         conf = col.sched._cardConf(c);
         conf["lapse"]["delays"] = [2, 20];
         col.decks.save(conf);
         col.sched.answerCard(c, 1);
         assert c.queue == QUEUE_TYPE_LRN;
         // it should be due tomorrow, with an interval of 1
         assert c.odue == col.sched.today + 1;
         assert c.ivl == 1;
         // but because it's in the learn queue, its current due time should be in
         // the future
         assert c.due >= time.time();
         assert (c.due - time.time()) > 118;
         // factor should have been decremented
         assert c.factor == 2300;
         // check counters
         assert c.lapses == 2;
         assert c.reps == 4;
         // check ests.
         ni = col.sched.nextIvl;
         assert ni(c, 1) == 120;
         assert ni(c, 2) == 20 * 60;
         // try again with an ease of 2 instead
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         c = copy.copy(cardcopy);
         c.flush();
         col.sched.answerCard(c, 2);
         assert c.queue == QUEUE_TYPE_REV;
         // the new interval should be (100 + 8/4) * 1.2 = 122
         assert checkRevIvl(col, c, 122);
         assert c.due == col.sched.today + c.ivl;
         // factor should have been decremented
         assert c.factor == 2350;
         // check counters
         assert c.lapses == 1;
         assert c.reps == 4;
         // ease 3
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         c = copy.copy(cardcopy);
         c.flush();
         col.sched.answerCard(c, 3);
         // the new interval should be (100 + 8/2) * 2.5 = 260
         assert checkRevIvl(col, c, 260);
         assert c.due == col.sched.today + c.ivl;
         // factor should have been left alone
         assert c.factor == STARTING_FACTOR;
         // ease 4
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         c = copy.copy(cardcopy);
         c.flush();
         col.sched.answerCard(c, 4);
         // the new interval should be (100 + 8) * 2.5 * 1.3 = 351
         assert checkRevIvl(col, c, 351);
         assert c.due == col.sched.today + c.ivl;
         // factor should have been increased
         assert c.factor == 2650;
     }

     @Test
     public void test_button_spacing(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         // 1 day ivl review card due now
         c = note.cards()[0];
         c.type = CARD_TYPE_REV;
         c.queue = QUEUE_TYPE_REV;
         c.due = col.sched.today;
         c.reps = 1;
         c.ivl = 1;
         c.startTimer();
         c.flush();
         col.reset();
         ni = col.sched.nextIvlStr;
         wo = without_unicode_isolation;
         assert wo(ni(c, 2)) == "2d";
         assert wo(ni(c, 3)) == "3d";
         assert wo(ni(c, 4)) == "4d";
     }

     @Test
     public void test_overdue_lapse(){
         // disabled in commit 3069729776990980f34c25be66410e947e9d51a2
         return;
         col = getEmptyCol()  // pylint: disable=unreachable
         // add a note
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         // simulate a review that was lapsed and is now due for its normal review
         c = note.cards()[0];
         c.type = CARD_TYPE_REV;
         c.queue = 1;
         c.due = -1;
         c.odue = -1;
         c.factor = STARTING_FACTOR;
         c.left = 2002;
         c.ivl = 0;
         c.flush();
         col.sched._clearOverdue = False;
         // checkpoint
         col.save();
         col.sched.reset();
         assert col.sched.counts() == (0, 2, 0);
         c = col.sched.getCard();
         col.sched.answerCard(c, 3);
         // it should be due tomorrow
         assert c.due == col.sched.today + 1;
         // revert to before
         col.rollback();
         col.sched._clearOverdue = True;
         // with the default settings, the overdue card should be removed from the
         // learning queue
         col.sched.reset();
         assert col.sched.counts() == (0, 0, 1);
     }

     @Test
     public void test_finished(){
         col = getEmptyCol();
         // nothing due
         assert "Congratulations" in col.sched.finishedMsg();
         assert "limit" not in col.sched.finishedMsg();
         note = col.newNote();
         note["Front"] = "one";
         note["Back"] = "two";
         col.addNote(note);
         // have a new card
         assert "new cards available" in col.sched.finishedMsg();
         // turn it into a review
         col.reset();
         c = note.cards()[0];
         c.startTimer();
         col.sched.answerCard(c, 3);
         // nothing should be due tomorrow, as it's due in a week
         assert "Congratulations" in col.sched.finishedMsg();
         assert "limit" not in col.sched.finishedMsg();
     }

     @Test
     public void test_nextIvl(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "one";
         note["Back"] = "two";
         col.addNote(note);
         col.reset();
         conf = col.decks.confForDid(1);
         conf["new"]["delays"] = [0.5, 3, 10];
         conf["lapse"]["delays"] = [1, 5, 9];
         col.decks.save(conf);
         c = col.sched.getCard();
         // new cards
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         ni = col.sched.nextIvl;
         assert ni(c, 1) == 30;
         assert ni(c, 2) == 180;
         assert ni(c, 3) == 4 * 86400;
         col.sched.answerCard(c, 1);
         // cards in learning
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         assert ni(c, 1) == 30;
         assert ni(c, 2) == 180;
         assert ni(c, 3) == 4 * 86400;
         col.sched.answerCard(c, 2);
         assert ni(c, 1) == 30;
         assert ni(c, 2) == 600;
         assert ni(c, 3) == 4 * 86400;
         col.sched.answerCard(c, 2);
         // normal graduation is tomorrow
         assert ni(c, 2) == 1 * 86400;
         assert ni(c, 3) == 4 * 86400;
         // lapsed cards
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         c.type = CARD_TYPE_REV;
         c.ivl = 100;
         c.factor = STARTING_FACTOR;
         assert ni(c, 1) == 60;
         assert ni(c, 2) == 100 * 86400;
         assert ni(c, 3) == 100 * 86400;
         // review cards
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         c.queue = QUEUE_TYPE_REV;
         c.ivl = 100;
         c.factor = STARTING_FACTOR;
         // failing it should put it at 60s
         assert ni(c, 1) == 60;
         // or 1 day if relearn is false
         conf["lapse"]["delays"] = [];
         col.decks.save(conf);
         assert ni(c, 1) == 1 * 86400;
         // (* 100 1.2 86400)10368000.0
         assert ni(c, 2) == 10368000;
         // (* 100 2.5 86400)21600000.0
         assert ni(c, 3) == 21600000;
         // (* 100 2.5 1.3 86400)28080000.0
         assert ni(c, 4) == 28080000;
         assert without_unicode_isolation(col.sched.nextIvlStr(c, 4)) == "10.8mo";
     }

     @Test
     public void test_misc(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         c = note.cards()[0];
         // burying
         col.sched.buryNote(c.nid);
         col.reset();
         assert not col.sched.getCard();
         col.sched.unburyCards();
         col.reset();
         assert col.sched.getCard();
     }

     @Test
     public void test_suspend(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         c = note.cards()[0];
         // suspending
         col.reset();
         assert col.sched.getCard();
         col.sched.suspendCards([c.id]);
         col.reset();
         assert not col.sched.getCard();
         // unsuspending
         col.sched.unsuspendCards([c.id]);
         col.reset();
         assert col.sched.getCard();
         // should cope with rev cards being relearnt
         c.due = 0;
         c.ivl = 100;
         c.type = CARD_TYPE_REV;
         c.queue = QUEUE_TYPE_REV;
         c.flush();
         col.reset();
         c = col.sched.getCard();
         col.sched.answerCard(c, 1);
         assert c.due >= time.time();
         assert c.queue == QUEUE_TYPE_LRN;
         assert c.type == CARD_TYPE_REV;
         col.sched.suspendCards([c.id]);
         col.sched.unsuspendCards([c.id]);
         c.load();
         assert c.queue == QUEUE_TYPE_REV;
         assert c.type == CARD_TYPE_REV;
         assert c.due == 1;
         // should cope with cards in cram decks
         c.due = 1;
         c.flush();
         cram = col.decks.newDyn("tmp");
         col.sched.rebuildDyn();
         c.load();
         assert c.due != 1;
         assert c.did != 1;
         col.sched.suspendCards([c.id]);
         c.load();
         assert c.due == 1;
         assert c.did == 1;
     }

     @Test
     public void test_cram(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         c = note.cards()[0];
         c.ivl = 100;
         c.queue = CARD_TYPE_REV;
         c.type = QUEUE_TYPE_REV;
         // due in 25 days, so it's been waiting 75 days
         c.due = col.sched.today + 25;
         c.mod = 1;
         c.factor = STARTING_FACTOR;
         c.startTimer();
         c.flush();
         col.reset();
         assert col.sched.counts() == (0, 0, 0);
         cardcopy = copy.copy(c);
         // create a dynamic deck and refresh it
         did = col.decks.newDyn("Cram");
         col.sched.rebuildDyn(did);
         col.reset();
         // should appear as new in the deck list
         assert sorted(col.sched.deck_due_tree().children)[0].new_count == 1;
         // and should appear in the counts
         assert col.sched.counts() == (1, 0, 0);
         // grab it and check estimates
         c = col.sched.getCard();
         assert col.sched.answerButtons(c) == 2;
         assert col.sched.nextIvl(c, 1) == 600;
         assert col.sched.nextIvl(c, 2) == 138 * 60 * 60 * 24;
         cram = col.decks.get(did);
         cram["delays"] = [1, 10];
         col.decks.save(cram);
         assert col.sched.answerButtons(c) == 3;
         assert col.sched.nextIvl(c, 1) == 60;
         assert col.sched.nextIvl(c, 2) == 600;
         assert col.sched.nextIvl(c, 3) == 138 * 60 * 60 * 24;
         col.sched.answerCard(c, 2);
         // elapsed time was 75 days
         // factor = 2.5+1.2/2 = 1.85
         // int(75*1.85) = 138
         assert c.ivl == 138;
         assert c.odue == 138;
         assert c.queue == QUEUE_TYPE_LRN;
         // should be logged as a cram rep
         assert col.db.scalar("select type from revlog order by id desc limit 1") == 3;
         // check ivls again
         assert col.sched.nextIvl(c, 1) == 60;
         assert col.sched.nextIvl(c, 2) == 138 * 60 * 60 * 24;
         assert col.sched.nextIvl(c, 3) == 138 * 60 * 60 * 24;
         // when it graduates, due is updated
         c = col.sched.getCard();
         col.sched.answerCard(c, 2);
         assert c.ivl == 138;
         assert c.due == 138;
         assert c.queue == QUEUE_TYPE_REV;
         // and it will have moved back to the previous deck
         assert c.did == 1;
         // cram the deck again
         col.sched.rebuildDyn(did);
         col.reset();
         c = col.sched.getCard();
         // check ivls again - passing should be idempotent
         assert col.sched.nextIvl(c, 1) == 60;
         assert col.sched.nextIvl(c, 2) == 600;
         assert col.sched.nextIvl(c, 3) == 138 * 60 * 60 * 24;
         col.sched.answerCard(c, 2);
         assert c.ivl == 138;
         assert c.odue == 138;
         // fail
         col.sched.answerCard(c, 1);
         assert col.sched.nextIvl(c, 1) == 60;
         assert col.sched.nextIvl(c, 2) == 600;
         assert col.sched.nextIvl(c, 3) == 86400;
         // delete the deck, returning the card mid-study
         col.decks.rem(col.decks.selected());
         assert len(col.sched.deck_due_tree().children) == 1;
         c.load();
         assert c.ivl == 1;
         assert c.due == col.sched.today + 1;
         // make it due
         col.reset();
         assert col.sched.counts() == (0, 0, 0);
         c.due = -5;
         c.ivl = 100;
         c.flush();
         col.reset();
         assert col.sched.counts() == (0, 0, 1);
         // cram again
         did = col.decks.newDyn("Cram");
         col.sched.rebuildDyn(did);
         col.reset();
         assert col.sched.counts() == (0, 0, 1);
         c.load();
         assert col.sched.answerButtons(c) == 4;
         // add a sibling so we can test minSpace, etc
         c.col = None;
         c2 = copy.deepcopy(c);
         c2.col = c.col = col;
         c2.id = 0;
         c2.ord = 1;
         c2.due = 325;
         c2.col = c.col;
         c2.flush();
         // should be able to answer it
         c = col.sched.getCard();
         col.sched.answerCard(c, 4);
         // it should have been moved back to the original deck
         assert c.did == 1;
     }

     @Test
     public void test_cram_rem(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         oldDue = note.cards()[0].due;
         did = col.decks.newDyn("Cram");
         col.sched.rebuildDyn(did);
         col.reset();
         c = col.sched.getCard();
         col.sched.answerCard(c, 2);
         // answering the card will put it in the learning queue
         assert c.type == CARD_TYPE_LRN and c.queue == QUEUE_TYPE_LRN;
         assert c.due != oldDue;
         // if we terminate cramming prematurely it should be set back to new
         col.sched.emptyDyn(did);
         c.load();
         assert c.type == CARD_TYPE_NEW and c.queue == QUEUE_TYPE_NEW;
         assert c.due == oldDue;
     }

     @Test
     public void test_cram_resched(){
         // add card
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         // cram deck
         did = col.decks.newDyn("Cram");
         cram = col.decks.get(did);
         cram["resched"] = False;
         col.decks.save(cram);
         col.sched.rebuildDyn(did);
         col.reset();
         // graduate should return it to new
         c = col.sched.getCard();
         ni = col.sched.nextIvl;
         assert ni(c, 1) == 60;
         assert ni(c, 2) == 600;
         assert ni(c, 3) == 0;
         assert col.sched.nextIvlStr(c, 3) == "(end)";
         col.sched.answerCard(c, 3);
         assert c.type == CARD_TYPE_NEW and c.queue == QUEUE_TYPE_NEW;
         // undue reviews should also be unaffected
         c.ivl = 100;
         c.queue = CARD_TYPE_REV;
         c.type = QUEUE_TYPE_REV;
         c.due = col.sched.today + 25;
         c.factor = STARTING_FACTOR;
         c.flush();
         cardcopy = copy.copy(c);
         col.sched.rebuildDyn(did);
         col.reset();
         c = col.sched.getCard();
         assert ni(c, 1) == 600;
         assert ni(c, 2) == 0;
         assert ni(c, 3) == 0;
         col.sched.answerCard(c, 2);
         assert c.ivl == 100;
         assert c.due == col.sched.today + 25;
         // check failure too
         c = cardcopy;
         c.flush();
         col.sched.rebuildDyn(did);
         col.reset();
         c = col.sched.getCard();
         col.sched.answerCard(c, 1);
         col.sched.emptyDyn(did);
         c.load();
         assert c.ivl == 100;
         assert c.due == col.sched.today + 25;
         // fail+grad early
         c = cardcopy;
         c.flush();
         col.sched.rebuildDyn(did);
         col.reset();
         c = col.sched.getCard();
         col.sched.answerCard(c, 1);
         col.sched.answerCard(c, 3);
         col.sched.emptyDyn(did);
         c.load();
         assert c.ivl == 100;
         assert c.due == col.sched.today + 25;
         // due cards - pass
         c = cardcopy;
         c.due = -25;
         c.flush();
         col.sched.rebuildDyn(did);
         col.reset();
         c = col.sched.getCard();
         col.sched.answerCard(c, 3);
         col.sched.emptyDyn(did);
         c.load();
         assert c.ivl == 100;
         assert c.due == -25;
         // fail
         c = cardcopy;
         c.due = -25;
         c.flush();
         col.sched.rebuildDyn(did);
         col.reset();
         c = col.sched.getCard();
         col.sched.answerCard(c, 1);
         col.sched.emptyDyn(did);
         c.load();
         assert c.ivl == 100;
         assert c.due == -25;
         // fail with normal grad
         c = cardcopy;
         c.due = -25;
         c.flush();
         col.sched.rebuildDyn(did);
         col.reset();
         c = col.sched.getCard();
         col.sched.answerCard(c, 1);
         col.sched.answerCard(c, 3);
         c.load();
         assert c.ivl == 100;
         assert c.due == -25;
         // lapsed card pulled into cram
         // col.sched._cardConf(c)['lapse']['mult']=0.5
         // col.sched.answerCard(c, 1)
         // col.sched.rebuildDyn(did)
         // col.reset()
         // c = col.sched.getCard()
         // col.sched.answerCard(c, 2)
         // print c.__dict__
     }

     @Test
     public void test_ordcycle(){
         col = getEmptyCol();
         // add two more templates and set second active
         m = col.models.current();
         mm = col.models;
         t = mm.newTemplate("Reverse");
         t["qfmt"] = "{{Back}}";
         t["afmt"] = "{{Front}}";
         mm.addTemplate(m, t);
         t = mm.newTemplate("f2");
         t["qfmt"] = "{{Front}}";
         t["afmt"] = "{{Back}}";
         mm.addTemplate(m, t);
         mm.save(m);
         // create a new note; it should have 3 cards
         note = col.newNote();
         note["Front"] = "1";
         note["Back"] = "1";
         col.addNote(note);
         assert col.cardCount() == 3;
         col.reset();
         // ordinals should arrive in order
         assert col.sched.getCard().ord == 0;
         assert col.sched.getCard().ord == 1;
         assert col.sched.getCard().ord == 2;
     }

     @Test
     public void test_counts_idx(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "one";
         note["Back"] = "two";
         col.addNote(note);
         col.reset();
         assert col.sched.counts() == (1, 0, 0);
         c = col.sched.getCard();
         // counter's been decremented but idx indicates 1
         assert col.sched.counts() == (0, 0, 0);
         assert col.sched.countIdx(c) == 0;
         // answer to move to learn queue
         col.sched.answerCard(c, 1);
         assert col.sched.counts() == (0, 2, 0);
         // fetching again will decrement the count
         c = col.sched.getCard();
         assert col.sched.counts() == (0, 0, 0);
         assert col.sched.countIdx(c) == 1;
         // answering should add it back again
         col.sched.answerCard(c, 1);
         assert col.sched.counts() == (0, 2, 0);
     }

     @Test
     public void test_repCounts(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         col.reset();
         // lrnReps should be accurate on pass/fail
         assert col.sched.counts() == (1, 0, 0);
         col.sched.answerCard(col.sched.getCard(), 1);
         assert col.sched.counts() == (0, 2, 0);
         col.sched.answerCard(col.sched.getCard(), 1);
         assert col.sched.counts() == (0, 2, 0);
         col.sched.answerCard(col.sched.getCard(), 2);
         assert col.sched.counts() == (0, 1, 0);
         col.sched.answerCard(col.sched.getCard(), 1);
         assert col.sched.counts() == (0, 2, 0);
         col.sched.answerCard(col.sched.getCard(), 2);
         assert col.sched.counts() == (0, 1, 0);
         col.sched.answerCard(col.sched.getCard(), 2);
         assert col.sched.counts() == (0, 0, 0);
         note = col.newNote();
         note["Front"] = "two";
         col.addNote(note);
         col.reset();
         // initial pass should be correct too
         col.sched.answerCard(col.sched.getCard(), 2);
         assert col.sched.counts() == (0, 1, 0);
         col.sched.answerCard(col.sched.getCard(), 1);
         assert col.sched.counts() == (0, 2, 0);
         col.sched.answerCard(col.sched.getCard(), 3);
         assert col.sched.counts() == (0, 0, 0);
         // immediate graduate should work
         note = col.newNote();
         note["Front"] = "three";
         col.addNote(note);
         col.reset();
         col.sched.answerCard(col.sched.getCard(), 3);
         assert col.sched.counts() == (0, 0, 0);
         // and failing a review should too
         note = col.newNote();
         note["Front"] = "three";
         col.addNote(note);
         c = note.cards()[0];
         c.type = CARD_TYPE_REV;
         c.queue = QUEUE_TYPE_REV;
         c.due = col.sched.today;
         c.flush();
         col.reset();
         assert col.sched.counts() == (0, 0, 1);
         col.sched.answerCard(col.sched.getCard(), 1);
         assert col.sched.counts() == (0, 1, 0);
     }

     @Test
     public void test_timing(){
         col = getEmptyCol();
         // add a few review cards, due today
         for i in range(5):
             note = col.newNote();
             note["Front"] = "num" + str(i);
             col.addNote(note);
             c = note.cards()[0];
             c.type = CARD_TYPE_REV;
             c.queue = QUEUE_TYPE_REV;
             c.due = 0;
             c.flush();
         // fail the first one
         col.reset();
         c = col.sched.getCard();
         // set a a fail delay of 4 seconds
         conf = col.sched._cardConf(c);
         conf["lapse"]["delays"][0] = 1 / 15.0;
         col.decks.save(conf);
         col.sched.answerCard(c, 1);
         // the next card should be another review
         c = col.sched.getCard();
         assert c.queue == QUEUE_TYPE_REV;
         // but if we wait for a few seconds, the failed card should come back
         orig_time = time.time;

         def adjusted_time():
             return orig_time() + 5;

         time.time = adjusted_time;
         c = col.sched.getCard();
         assert c.queue == QUEUE_TYPE_LRN;
         time.time = orig_time;
     }

     @Test
     public void test_collapse(){
         col = getEmptyCol();
         // add a note
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         col.reset();
         // test collapsing
         c = col.sched.getCard();
         col.sched.answerCard(c, 1);
         c = col.sched.getCard();
         col.sched.answerCard(c, 3);
         assert not col.sched.getCard();
     }

     @Test
     public void test_deckDue(){
         col = getEmptyCol();
         // add a note with default deck
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         // and one that's a child
         note = col.newNote();
         note["Front"] = "two";
         default1 = note.model()["did"] = col.decks.id("Default::1");
         col.addNote(note);
         // make it a review card
         c = note.cards()[0];
         c.queue = QUEUE_TYPE_REV;
         c.due = 0;
         c.flush();
         // add one more with a new deck
         note = col.newNote();
         note["Front"] = "two";
         foobar = note.model()["did"] = col.decks.id("foo::bar");
         col.addNote(note);
         // and one that's a sibling
         note = col.newNote();
         note["Front"] = "three";
         foobaz = note.model()["did"] = col.decks.id("foo::baz");
         col.addNote(note);
         col.reset();
         assert len(col.decks.all_names_and_ids()) == 5;
         tree = col.sched.deck_due_tree().children;
         assert tree[0].name == "Default";
         // sum of child and parent
         assert tree[0].deck_id == 1;
         assert tree[0].review_count == 1;
         assert tree[0].new_count == 1;
         // child count is just review
         child = tree[0].children[0];
         assert child.name == "1";
         assert child.deck_id == default1;
         assert child.review_count == 1;
         assert child.new_count == 0;
         // code should not fail if a card has an invalid deck
         c.did = 12345;
         c.flush();
         col.sched.deck_due_tree();
     }

     @Test
     public void test_deckFlow(){
         col = getEmptyCol();
         // add a note with default deck
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         // and one that's a child
         note = col.newNote();
         note["Front"] = "two";
         default1 = note.model()["did"] = col.decks.id("Default::2");
         col.addNote(note);
         // and another that's higher up
         note = col.newNote();
         note["Front"] = "three";
         default1 = note.model()["did"] = col.decks.id("Default::1");
         col.addNote(note);
         // should get top level one first, then ::1, then ::2
         col.reset();
         assert col.sched.counts() == (3, 0, 0);
         for i in "one", "three", "two":
             c = col.sched.getCard();
             assert c.note()["Front"] == i;
             col.sched.answerCard(c, 2);
     }

     @Test
     public void test_reorder(){
         col = getEmptyCol();
         // add a note with default deck
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         note2 = col.newNote();
         note2["Front"] = "two";
         col.addNote(note2);
         assert note2.cards()[0].due == 2;
         found = False;
         // 50/50 chance of being reordered
         for i in range(20):
             col.sched.randomizeCards(1);
             if note.cards()[0].due != note.id:
                 found = True;
                 break;
         assert found;
         col.sched.orderCards(1);
         assert note.cards()[0].due == 1;
         // shifting
         f3 = col.newNote();
         f3["Front"] = "three";
         col.addNote(f3);
         f4 = col.newNote();
         f4["Front"] = "four";
         col.addNote(f4);
         assert note.cards()[0].due == 1;
         assert note2.cards()[0].due == 2;
         assert f3.cards()[0].due == 3;
         assert f4.cards()[0].due == 4;
         col.sched.sortCards([f3.cards()[0].id, f4.cards()[0].id], start=1, shift=True);
         assert note.cards()[0].due == 3;
         assert note2.cards()[0].due == 4;
         assert f3.cards()[0].due == 1;
         assert f4.cards()[0].due == 2;
     }

     @Test
     public void test_forget(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         c = note.cards()[0];
         c.queue = QUEUE_TYPE_REV;
         c.type = CARD_TYPE_REV;
         c.ivl = 100;
         c.due = 0;
         c.flush();
         col.reset();
         assert col.sched.counts() == (0, 0, 1);
         col.sched.forgetCards([c.id]);
         col.reset();
         assert col.sched.counts() == (1, 0, 0);
     }

     @Test
     public void test_resched(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         c = note.cards()[0];
         col.sched.reschedCards([c.id], 0, 0);
         c.load();
         assert c.due == col.sched.today;
         assert c.ivl == 1;
         assert c.queue == CARD_TYPE_REV and c.type == QUEUE_TYPE_REV;
         col.sched.reschedCards([c.id], 1, 1);
         c.load();
         assert c.due == col.sched.today + 1;
         assert c.ivl == +1;
     }

     @Test
     public void test_norelearn(){
         col = getEmptyCol();
         // add a note
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         c = note.cards()[0];
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
         col.sched.answerCard(c, 1);
         col.sched._cardConf(c)["lapse"]["delays"] = [];
         col.sched.answerCard(c, 1);
     }

     @Test
     public void test_failmult(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "one";
         note["Back"] = "two";
         col.addNote(note);
         c = note.cards()[0];
         c.type = CARD_TYPE_REV;
         c.queue = QUEUE_TYPE_REV;
         c.ivl = 100;
         c.due = col.sched.today - c.ivl;
         c.factor = STARTING_FACTOR;
         c.reps = 3;
         c.lapses = 1;
         c.startTimer();
         c.flush();
         conf = col.sched._cardConf(c);
         conf["lapse"]["mult"] = 0.5;
         col.decks.save(conf);
         c = col.sched.getCard();
         col.sched.answerCard(c, 1);
         assert c.ivl == 50;
         col.sched.answerCard(c, 1);
         assert c.ivl == 25;
     }
     /*****************
          ** SchedV2      *
      *****************/
     private Collection getEmptyCol(){
         col = getEmptyColOrig();
         col.changeSchedulerVer(2);
         return col;
     }

     @Test
     public void test_clock(){
         col = getEmptyCol();
         if (col.sched.dayCutoff - intTime()) < 10 * 60:
             raise Exception("Unit tests will fail around the day rollover.");


     private boolean checkRevIvl(col, c, targetIvl):
         min, max = col.sched._fuzzIvlRange(targetIvl);
         return min <= c.ivl <= max;
     }

     @Test
     public void test_basics(){
         col = getEmptyCol();
         col.reset();
         assert not col.sched.getCard();
     }

     @Test
     public void test_new(){
         col = getEmptyCol();
         col.reset();
         assert col.sched.newCount == 0;
         // add a note
         note = col.newNote();
         note["Front"] = "one";
         note["Back"] = "two";
         col.addNote(note);
         col.reset();
         assert col.sched.newCount == 1;
         // fetch it
         c = col.sched.getCard();
         assert c;
         assert c.queue == QUEUE_TYPE_NEW;
         assert c.type == CARD_TYPE_NEW;
         // if we answer it, it should become a learn card
         t = intTime();
         col.sched.answerCard(c, 1);
         assert c.queue == QUEUE_TYPE_LRN;
         assert c.type == CARD_TYPE_LRN;
         assert c.due >= t;

         // disabled for now, as the learn fudging makes this randomly fail
         // // the default order should ensure siblings are not seen together, and
         // // should show all cards
         // m = col.models.current(); mm = col.models
         // t = mm.newTemplate("Reverse")
         // t['qfmt'] = "{{Back}}"
         // t['afmt'] = "{{Front}}"
         // mm.addTemplate(m, t)
         // mm.save(m)
         // note = col.newNote()
         // note['Front'] = u"2"; note['Back'] = u"2"
         // col.addNote(note)
         // note = col.newNote()
         // note['Front'] = u"3"; note['Back'] = u"3"
         // col.addNote(note)
         // col.reset()
         // qs = ("2", "3", "2", "3")
         // for n in range(4):
         //     c = col.sched.getCard()
         //     assert qs[n] in c.q()
         //     col.sched.answerCard(c, 2)
     }

     @Test
     public void test_newLimits(){
         col = getEmptyCol();
         // add some notes
         deck2 = col.decks.id("Default::foo");
         for i in range(30):
             note = col.newNote();
             note["Front"] = str(i);
             if i > 4:
                 note.model()["did"] = deck2;
             col.addNote(note);
         // give the child deck a different configuration
         c2 = col.decks.add_config_returning_id("new conf");
         col.decks.setConf(col.decks.get(deck2), c2);
         col.reset();
         // both confs have defaulted to a limit of 20
         assert col.sched.newCount == 20;
         // first card we get comes from parent
         c = col.sched.getCard();
         assert c.did == 1;
         // limit the parent to 10 cards, meaning we get 10 in total
         conf1 = col.decks.confForDid(1);
         conf1["new"]["perDay"] = 10;
         col.decks.save(conf1);
         col.reset();
         assert col.sched.newCount == 10;
         // if we limit child to 4, we should get 9
         conf2 = col.decks.confForDid(deck2);
         conf2["new"]["perDay"] = 4;
         col.decks.save(conf2);
         col.reset();
         assert col.sched.newCount == 9;
     }

     @Test
     public void test_newBoxes(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         col.reset();
         c = col.sched.getCard();
         conf = col.sched._cardConf(c);
         conf["new"]["delays"] = [1, 2, 3, 4, 5];
         col.decks.save(conf);
         col.sched.answerCard(c, 2);
         // should handle gracefully
         conf["new"]["delays"] = [1];
         col.decks.save(conf);
         col.sched.answerCard(c, 2);
     }

     @Test
     public void test_learn(){
         col = getEmptyCol();
         // add a note
         note = col.newNote();
         note["Front"] = "one";
         note["Back"] = "two";
         note = col.addNote(note);
         // set as a learn card and rebuild queues
         col.db.execute("update cards set queue=0, type=0");
         col.reset();
         // sched.getCard should return it, since it's due in the past
         c = col.sched.getCard();
         assert c;
         conf = col.sched._cardConf(c);
         conf["new"]["delays"] = [0.5, 3, 10];
         col.decks.save(conf);
         // fail it
         col.sched.answerCard(c, 1);
         // it should have three reps left to graduation
         assert c.left % 1000 == 3;
         assert c.left // 1000 == 3
         // it should by due in 30 seconds
         t = round(c.due - time.time());
         assert t >= 25 and t <= 40;
         // pass it once
         col.sched.answerCard(c, 3);
         // it should by due in 3 minutes
         dueIn = c.due - time.time();
         assert 178 <= dueIn <= 180 * 1.25;
         assert c.left % 1000 == 2;
         assert c.left // 1000 == 2
         // check log is accurate
         log = col.db.first("select * from revlog order by id desc");
         assert log[3] == 3;
         assert log[4] == -180;
         assert log[5] == -30;
         // pass again
         col.sched.answerCard(c, 3);
         // it should by due in 10 minutes
         dueIn = c.due - time.time();
         assert 599 <= dueIn <= 600 * 1.25;
         assert c.left % 1000 == 1;
         assert c.left // 1000 == 1
         // the next pass should graduate the card
         assert c.queue == QUEUE_TYPE_LRN;
         assert c.type == CARD_TYPE_LRN;
         col.sched.answerCard(c, 3);
         assert c.queue == QUEUE_TYPE_REV;
         assert c.type == CARD_TYPE_REV;
         // should be due tomorrow, with an interval of 1
         assert c.due == col.sched.today + 1;
         assert c.ivl == 1;
         // or normal removal
         c.type = 0;
         c.queue = 1;
         col.sched.answerCard(c, 4);
         assert c.type == CARD_TYPE_REV;
         assert c.queue == QUEUE_TYPE_REV;
         assert checkRevIvl(col, c, 4);
         // revlog should have been updated each time
         assert col.db.scalar("select count() from revlog where type = 0") == 5;
     }

     @Test
     public void test_relearn(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         c = note.cards()[0];
         c.ivl = 100;
         c.due = col.sched.today;
         c.queue = CARD_TYPE_REV;
         c.type = QUEUE_TYPE_REV;
         c.flush();

         // fail the card
         col.reset();
         c = col.sched.getCard();
         col.sched.answerCard(c, 1);
         assert c.queue == QUEUE_TYPE_LRN;
         assert c.type == CARD_TYPE_RELEARNING;
         assert c.ivl == 1;

         // immediately graduate it
         col.sched.answerCard(c, 4);
         assert c.queue == CARD_TYPE_REV and c.type == QUEUE_TYPE_REV;
         assert c.ivl == 2;
         assert c.due == col.sched.today + c.ivl;
     }

     @Test
     public void test_relearn_no_steps(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         c = note.cards()[0];
         c.ivl = 100;
         c.due = col.sched.today;
         c.queue = CARD_TYPE_REV;
         c.type = QUEUE_TYPE_REV;
         c.flush();

         conf = col.decks.confForDid(1);
         conf["lapse"]["delays"] = [];
         col.decks.save(conf);

         // fail the card
         col.reset();
         c = col.sched.getCard();
         col.sched.answerCard(c, 1);
         assert c.queue == CARD_TYPE_REV and c.type == QUEUE_TYPE_REV;
     }

     @Test
     public void test_learn_collapsed(){
         col = getEmptyCol();
         // add 2 notes
         note = col.newNote();
         note["Front"] = "1";
         note = col.addNote(note);
         note = col.newNote();
         note["Front"] = "2";
         note = col.addNote(note);
         // set as a learn card and rebuild queues
         col.db.execute("update cards set queue=0, type=0");
         col.reset();
         // should get '1' first
         c = col.sched.getCard();
         assert c.q().endswith("1");
         // pass it so it's due in 10 minutes
         col.sched.answerCard(c, 3);
         // get the other card
         c = col.sched.getCard();
         assert c.q().endswith("2");
         // fail it so it's due in 1 minute
         col.sched.answerCard(c, 1);
         // we shouldn't get the same card again
         c = col.sched.getCard();
         assert not c.q().endswith("2");
     }

     @Test
     public void test_learn_day(){
         col = getEmptyCol();
         // add a note
         note = col.newNote();
         note["Front"] = "one";
         note = col.addNote(note);
         col.sched.reset();
         c = col.sched.getCard();
         conf = col.sched._cardConf(c);
         conf["new"]["delays"] = [1, 10, 1440, 2880];
         col.decks.save(conf);
         // pass it
         col.sched.answerCard(c, 3);
         // two reps to graduate, 1 more today
         assert c.left % 1000 == 3;
         assert c.left // 1000 == 1
         assert col.sched.counts() == (0, 1, 0);
         c = col.sched.getCard();
         ni = col.sched.nextIvl;
         assert ni(c, 3) == 86400;
         // answering it will place it in queue 3
         col.sched.answerCard(c, 3);
         assert c.due == col.sched.today + 1;
         assert c.queue == QUEUE_TYPE_DAY_LEARN_RELEARN;
         assert not col.sched.getCard();
         // for testing, move it back a day
         c.due -= 1;
         c.flush();
         col.reset();
         assert col.sched.counts() == (0, 1, 0);
         c = col.sched.getCard();
         // nextIvl should work
         assert ni(c, 3) == 86400 * 2;
         // if we fail it, it should be back in the correct queue
         col.sched.answerCard(c, 1);
         assert c.queue == QUEUE_TYPE_LRN;
         col.undo();
         col.reset();
         c = col.sched.getCard();
         col.sched.answerCard(c, 3);
         // simulate the passing of another two days
         c.due -= 2;
         c.flush();
         col.reset();
         // the last pass should graduate it into a review card
         assert ni(c, 3) == 86400;
         col.sched.answerCard(c, 3);
         assert c.queue == CARD_TYPE_REV and c.type == QUEUE_TYPE_REV;
         // if the lapse step is tomorrow, failing it should handle the counts
         // correctly
         c.due = 0;
         c.flush();
         col.reset();
         assert col.sched.counts() == (0, 0, 1);
         conf = col.sched._cardConf(c);
         conf["lapse"]["delays"] = [1440];
         col.decks.save(conf);
         c = col.sched.getCard();
         col.sched.answerCard(c, 1);
         assert c.queue == QUEUE_TYPE_DAY_LEARN_RELEARN;
         assert col.sched.counts() == (0, 0, 0);
     }

     @Test
     public void test_reviews(){
         col = getEmptyCol();
         // add a note
         note = col.newNote();
         note["Front"] = "one";
         note["Back"] = "two";
         col.addNote(note);
         // set the card up as a review card, due 8 days ago
         c = note.cards()[0];
         c.type = CARD_TYPE_REV;
         c.queue = QUEUE_TYPE_REV;
         c.due = col.sched.today - 8;
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
         c = copy.copy(cardcopy);
         c.flush();
         col.reset();
         col.sched.answerCard(c, 2);
         assert c.queue == QUEUE_TYPE_REV;
         // the new interval should be (100) * 1.2 = 120
         assert checkRevIvl(col, c, 120);
         assert c.due == col.sched.today + c.ivl;
         // factor should have been decremented
         assert c.factor == 2350;
         // check counters
         assert c.lapses == 1;
         assert c.reps == 4;
         // ease 3
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         c = copy.copy(cardcopy);
         c.flush();
         col.sched.answerCard(c, 3);
         // the new interval should be (100 + 8/2) * 2.5 = 260
         assert checkRevIvl(col, c, 260);
         assert c.due == col.sched.today + c.ivl;
         // factor should have been left alone
         assert c.factor == STARTING_FACTOR;
         // ease 4
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         c = copy.copy(cardcopy);
         c.flush();
         col.sched.answerCard(c, 4);
         // the new interval should be (100 + 8) * 2.5 * 1.3 = 351
         assert checkRevIvl(col, c, 351);
         assert c.due == col.sched.today + c.ivl;
         // factor should have been increased
         assert c.factor == 2650;
         // leech handling
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         conf = col.decks.getConf(1);
         conf["lapse"]["leechAction"] = LEECH_SUSPEND;
         col.decks.save(conf);
         c = copy.copy(cardcopy);
         c.lapses = 7;
         c.flush();
         // steup hook
         hooked = [];

         def onLeech(card):
             hooked.append(1);

         hooks.card_did_leech.append(onLeech);
         col.sched.answerCard(c, 1);
         assert hooked;
         assert c.queue == QUEUE_TYPE_SUSPENDED;
         c.load();
         assert c.queue == QUEUE_TYPE_SUSPENDED;
     }

     @Test
     public void test_review_limits(){
         col = getEmptyCol();

         parent = col.decks.get(col.decks.id("parent"));
         child = col.decks.get(col.decks.id("parent::child"));

         pconf = col.decks.get_config(col.decks.add_config_returning_id("parentConf"));
         cconf = col.decks.get_config(col.decks.add_config_returning_id("childConf"));

         pconf["rev"]["perDay"] = 5;
         col.decks.update_config(pconf);
         col.decks.setConf(parent, pconf["id"]);
         cconf["rev"]["perDay"] = 10;
         col.decks.update_config(cconf);
         col.decks.setConf(child, cconf["id"]);

         m = col.models.current();
         m["did"] = child["id"];
         col.models.save(m, updateReqs=False);

         // add some cards
         for i in range(20):
             note = col.newNote();
             note["Front"] = "one";
             note["Back"] = "two";
             col.addNote(note);

             // make them reviews
             c = note.cards()[0];
             c.queue = CARD_TYPE_REV;
             c.type = QUEUE_TYPE_REV;
             c.due = 0;
             c.flush();

         tree = col.sched.deck_due_tree().children;
         // (('parent', 1514457677462, 5, 0, 0, (('child', 1514457677463, 5, 0, 0, ()),)))
         assert tree[0].review_count == 5  // parent
         assert tree[0].children[0].review_count == 5  // child

         // .counts() should match
         col.decks.select(child["id"]);
         col.sched.reset();
         assert col.sched.counts() == (0, 0, 5);

         // answering a card in the child should decrement parent count
         c = col.sched.getCard();
         col.sched.answerCard(c, 3);
         assert col.sched.counts() == (0, 0, 4);

         tree = col.sched.deck_due_tree().children;
         assert tree[0].review_count == 4  // parent
         assert tree[0].children[0].review_count == 4  // child
     }

     @Test
     public void test_button_spacing(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         // 1 day ivl review card due now
         c = note.cards()[0];
         c.type = CARD_TYPE_REV;
         c.queue = QUEUE_TYPE_REV;
         c.due = col.sched.today;
         c.reps = 1;
         c.ivl = 1;
         c.startTimer();
         c.flush();
         col.reset();
         ni = col.sched.nextIvlStr;
         wo = without_unicode_isolation;
         assert wo(ni(c, 2)) == "2d";
         assert wo(ni(c, 3)) == "3d";
         assert wo(ni(c, 4)) == "4d";

         // if hard factor is <= 1, then hard may not increase
         conf = col.decks.confForDid(1);
         conf["rev"]["hardFactor"] = 1;
         col.decks.save(conf);
         assert wo(ni(c, 2)) == "1d";
     }

     @Test
     public void test_overdue_lapse(){
         // disabled in commit 3069729776990980f34c25be66410e947e9d51a2
         return;
         col = getEmptyCol()  // pylint: disable=unreachable
         // add a note
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         // simulate a review that was lapsed and is now due for its normal review
         c = note.cards()[0];
         c.type = CARD_TYPE_REV;
         c.queue = 1;
         c.due = -1;
         c.odue = -1;
         c.factor = STARTING_FACTOR;
         c.left = 2002;
         c.ivl = 0;
         c.flush();
         col.sched._clearOverdue = False;
         // checkpoint
         col.save();
         col.sched.reset();
         assert col.sched.counts() == (0, 2, 0);
         c = col.sched.getCard();
         col.sched.answerCard(c, 3);
         // it should be due tomorrow
         assert c.due == col.sched.today + 1;
         // revert to before
         col.rollback();
         col.sched._clearOverdue = True;
         // with the default settings, the overdue card should be removed from the
         // learning queue
         col.sched.reset();
         assert col.sched.counts() == (0, 0, 1);
     }

     @Test
     public void test_finished(){
         col = getEmptyCol();
         // nothing due
         assert "Congratulations" in col.sched.finishedMsg();
         assert "limit" not in col.sched.finishedMsg();
         note = col.newNote();
        note["Front"] = "one";
         note["Back"] = "two";
         col.addNote(note);
         // have a new card
         assert "new cards available" in col.sched.finishedMsg();
         // turn it into a review
         col.reset();
         c = note.cards()[0];
         c.startTimer();
         col.sched.answerCard(c, 3);
         // nothing should be due tomorrow, as it's due in a week
         assert "Congratulations" in col.sched.finishedMsg();
         assert "limit" not in col.sched.finishedMsg();
     }

     @Test
     public void test_nextIvl(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "one";
         note["Back"] = "two";
         col.addNote(note);
         col.reset();
         conf = col.decks.confForDid(1);
         conf["new"]["delays"] = [0.5, 3, 10];
         conf["lapse"]["delays"] = [1, 5, 9];
         col.decks.save(conf);
         c = col.sched.getCard();
         // new cards
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         ni = col.sched.nextIvl;
         assert ni(c, 1) == 30;
         assert ni(c, 2) == (30 + 180) // 2
         assert ni(c, 3) == 180;
         assert ni(c, 4) == 4 * 86400;
         col.sched.answerCard(c, 1);
         // cards in learning
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         assert ni(c, 1) == 30;
         assert ni(c, 2) == (30 + 180) // 2
         assert ni(c, 3) == 180;
         assert ni(c, 4) == 4 * 86400;
         col.sched.answerCard(c, 3);
         assert ni(c, 1) == 30;
         assert ni(c, 2) == (180 + 600) // 2
         assert ni(c, 3) == 600;
         assert ni(c, 4) == 4 * 86400;
         col.sched.answerCard(c, 3);
         // normal graduation is tomorrow
         assert ni(c, 3) == 1 * 86400;
         assert ni(c, 4) == 4 * 86400;
         // lapsed cards
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         c.type = CARD_TYPE_REV;
         c.ivl = 100;
         c.factor = STARTING_FACTOR;
         assert ni(c, 1) == 60;
         assert ni(c, 3) == 100 * 86400;
         assert ni(c, 4) == 101 * 86400;
         // review cards
     ////////////////////////////////////////////////////////////////////////////////////////////////////
         c.queue = QUEUE_TYPE_REV;
         c.ivl = 100;
         c.factor = STARTING_FACTOR;
         // failing it should put it at 60s
         assert ni(c, 1) == 60;
         // or 1 day if relearn is false
         conf["lapse"]["delays"] = [];
         col.decks.save(conf);
         assert ni(c, 1) == 1 * 86400;
         // (* 100 1.2 86400)10368000.0
         assert ni(c, 2) == 10368000;
         // (* 100 2.5 86400)21600000.0
         assert ni(c, 3) == 21600000;
         // (* 100 2.5 1.3 86400)28080000.0
         assert ni(c, 4) == 28080000;
         assert without_unicode_isolation(col.sched.nextIvlStr(c, 4)) == "10.8mo";
     }

     @Test
     public void test_bury(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         c = note.cards()[0];
         note = col.newNote();
         note["Front"] = "two";
         col.addNote(note);
         c2 = note.cards()[0];
         // burying
         col.sched.buryCards([c.id], manual=True)  // pylint: disable=unexpected-keyword-arg
         c.load();
         assert c.queue == QUEUE_TYPE_MANUALLY_BURIED;
         col.sched.buryCards([c2.id], manual=False)  // pylint: disable=unexpected-keyword-arg
         c2.load();
         assert c2.queue == QUEUE_TYPE_SIBLING_BURIED;

         col.reset();
         assert not col.sched.getCard();

         col.sched.unburyCardsForDeck(  // pylint: disable=unexpected-keyword-arg
             type="manual";
     );
         c.load();
         assert c.queue == QUEUE_TYPE_NEW;
         c2.load();
         assert c2.queue == QUEUE_TYPE_SIBLING_BURIED;

         col.sched.unburyCardsForDeck(  // pylint: disable=unexpected-keyword-arg
             type="siblings";
     );
         c2.load();
         assert c2.queue == QUEUE_TYPE_NEW;

         col.sched.buryCards([c.id, c2.id]);
         col.sched.unburyCardsForDeck(type="all")  // pylint: disable=unexpected-keyword-arg

         col.reset();

         assert col.sched.counts() == (2, 0, 0);
     }

     @Test
     public void test_suspend(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         c = note.cards()[0];
         // suspending
         col.reset();
         assert col.sched.getCard();
         col.sched.suspendCards([c.id]);
         col.reset();
         assert not col.sched.getCard();
         // unsuspending
         col.sched.unsuspendCards([c.id]);
         col.reset();
         assert col.sched.getCard();
         // should cope with rev cards being relearnt
         c.due = 0;
         c.ivl = 100;
         c.type = CARD_TYPE_REV;
         c.queue = QUEUE_TYPE_REV;
         c.flush();
         col.reset();
         c = col.sched.getCard();
         col.sched.answerCard(c, 1);
         assert c.due >= time.time();
         due = c.due;
         assert c.queue == QUEUE_TYPE_LRN;
         assert c.type == CARD_TYPE_RELEARNING;
         col.sched.suspendCards([c.id]);
         col.sched.unsuspendCards([c.id]);
         c.load();
         assert c.queue == QUEUE_TYPE_LRN;
         assert c.type == CARD_TYPE_RELEARNING;
         assert c.due == due;
         // should cope with cards in cram decks
         c.due = 1;
         c.flush();
         cram = col.decks.newDyn("tmp");
         col.sched.rebuildDyn();
         c.load();
         assert c.due != 1;
         assert c.did != 1;
         col.sched.suspendCards([c.id]);
         c.load();
         assert c.due != 1;
         assert c.did != 1;
         assert c.odue == 1;
     }

     @Test
     public void test_filt_reviewing_early_normal(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         c = note.cards()[0];
         c.ivl = 100;
         c.queue = CARD_TYPE_REV;
         c.type = QUEUE_TYPE_REV;
         // due in 25 days, so it's been waiting 75 days
         c.due = col.sched.today + 25;
         c.mod = 1;
         c.factor = STARTING_FACTOR;
         c.startTimer();
         c.flush();
         col.reset();
         assert col.sched.counts() == (0, 0, 0);
         // create a dynamic deck and refresh it
         did = col.decks.newDyn("Cram");
         col.sched.rebuildDyn(did);
         col.reset();
         // should appear as normal in the deck list
         assert sorted(col.sched.deck_due_tree().children)[0].review_count == 1;
         // and should appear in the counts
         assert col.sched.counts() == (0, 0, 1);
         // grab it and check estimates
         c = col.sched.getCard();
         assert col.sched.answerButtons(c) == 4;
         assert col.sched.nextIvl(c, 1) == 600;
         assert col.sched.nextIvl(c, 2) == int(75 * 1.2) * 86400;
         assert col.sched.nextIvl(c, 3) == int(75 * 2.5) * 86400;
         assert col.sched.nextIvl(c, 4) == int(75 * 2.5 * 1.15) * 86400;

         // answer 'good'
         col.sched.answerCard(c, 3);
         checkRevIvl(col, c, 90);
         assert c.due == col.sched.today + c.ivl;
         assert not c.odue;
         // should not be in learning
         assert c.queue == QUEUE_TYPE_REV;
         // should be logged as a cram rep
         assert col.db.scalar("select type from revlog order by id desc limit 1") == 3;

         // due in 75 days, so it's been waiting 25 days
         c.ivl = 100;
         c.due = col.sched.today + 75;
         c.flush();
         col.sched.rebuildDyn(did);
         col.reset();
         c = col.sched.getCard();

         assert col.sched.nextIvl(c, 2) == 60 * 86400;
         assert col.sched.nextIvl(c, 3) == 100 * 86400;
         assert col.sched.nextIvl(c, 4) == 114 * 86400;
     }

     @Test
     public void test_filt_keep_lrn_state(){
         col = getEmptyCol();

         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);

         // fail the card outside filtered deck
         c = col.sched.getCard();
         conf = col.sched._cardConf(c);
         conf["new"]["delays"] = [1, 10, 61];
         col.decks.save(conf);

         col.sched.answerCard(c, 1);

         assert c.type == CARD_TYPE_LRN and c.queue == QUEUE_TYPE_LRN;
         assert c.left == 3003;

         col.sched.answerCard(c, 3);
         assert c.type == CARD_TYPE_LRN and c.queue == QUEUE_TYPE_LRN;

         // create a dynamic deck and refresh it
         did = col.decks.newDyn("Cram");
         col.sched.rebuildDyn(did);
         col.reset();

         // card should still be in learning state
         c.load();
         assert c.type == CARD_TYPE_LRN and c.queue == QUEUE_TYPE_LRN;
         assert c.left == 2002;

         // should be able to advance learning steps
         col.sched.answerCard(c, 3);
         // should be due at least an hour in the future
         assert c.due - intTime() > 60 * 60;

         // emptying the deck preserves learning state
         col.sched.emptyDyn(did);
         c.load();
         assert c.type == CARD_TYPE_LRN and c.queue == QUEUE_TYPE_LRN;
         assert c.left == 1001;
         assert c.due - intTime() > 60 * 60;
     }

     @Test
     public void test_preview(){
         // add cards
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         c = note.cards()[0];
         orig = copy.copy(c);
         note2 = col.newNote();
         note2["Front"] = "two";
         col.addNote(note2);
         // cram deck
         did = col.decks.newDyn("Cram");
         cram = col.decks.get(did);
         cram["resched"] = False;
         col.decks.save(cram);
         col.sched.rebuildDyn(did);
         col.reset();
         // grab the first card
         c = col.sched.getCard();
         assert col.sched.answerButtons(c) == 2;
         assert col.sched.nextIvl(c, 1) == 600;
         assert col.sched.nextIvl(c, 2) == 0;
         // failing it will push its due time back
         due = c.due;
         col.sched.answerCard(c, 1);
         assert c.due != due;

         // the other card should come next
         c2 = col.sched.getCard();
         assert c2.id != c.id;

         // passing it will remove it
         col.sched.answerCard(c2, 2);
         assert c2.queue == QUEUE_TYPE_NEW;
         assert c2.reps == 0;
         assert c2.type == CARD_TYPE_NEW;

         // the other card should appear again
         c = col.sched.getCard();
         assert c.id == orig.id;

         // emptying the filtered deck should restore card
         col.sched.emptyDyn(did);
         c.load();
         assert c.queue == QUEUE_TYPE_NEW;
         assert c.reps == 0;
         assert c.type == CARD_TYPE_NEW;
     }

     @Test
     public void test_ordcycle(){
         col = getEmptyCol();
         // add two more templates and set second active
         m = col.models.current();
         mm = col.models;
         t = mm.newTemplate("Reverse");
         t["qfmt"] = "{{Back}}";
         t["afmt"] = "{{Front}}";
         mm.addTemplate(m, t);
         t = mm.newTemplate("f2");
         t["qfmt"] = "{{Front}}";
         t["afmt"] = "{{Back}}";
         mm.addTemplate(m, t);
         mm.save(m);
         // create a new note; it should have 3 cards
         note = col.newNote();
         note["Front"] = "1";
         note["Back"] = "1";
         col.addNote(note);
         assert col.cardCount() == 3;
         col.reset();
         // ordinals should arrive in order
         assert col.sched.getCard().ord == 0;
         assert col.sched.getCard().ord == 1;
         assert col.sched.getCard().ord == 2;
     }

     @Test
     public void test_counts_idx(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "one";
         note["Back"] = "two";
         col.addNote(note);
         col.reset();
         assert col.sched.counts() == (1, 0, 0);
         c = col.sched.getCard();
         // counter's been decremented but idx indicates 1
         assert col.sched.counts() == (0, 0, 0);
         assert col.sched.countIdx(c) == 0;
         // answer to move to learn queue
         col.sched.answerCard(c, 1);
         assert col.sched.counts() == (0, 1, 0);
         // fetching again will decrement the count
         c = col.sched.getCard();
         assert col.sched.counts() == (0, 0, 0);
         assert col.sched.countIdx(c) == 1;
         // answering should add it back again
         col.sched.answerCard(c, 1);
         assert col.sched.counts() == (0, 1, 0);
     }

     @Test
     public void test_repCounts(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         col.reset();
         // lrnReps should be accurate on pass/fail
         assert col.sched.counts() == (1, 0, 0);
         col.sched.answerCard(col.sched.getCard(), 1);
         assert col.sched.counts() == (0, 1, 0);
         col.sched.answerCard(col.sched.getCard(), 1);
         assert col.sched.counts() == (0, 1, 0);
         col.sched.answerCard(col.sched.getCard(), 3);
         assert col.sched.counts() == (0, 1, 0);
         col.sched.answerCard(col.sched.getCard(), 1);
         assert col.sched.counts() == (0, 1, 0);
         col.sched.answerCard(col.sched.getCard(), 3);
         assert col.sched.counts() == (0, 1, 0);
         col.sched.answerCard(col.sched.getCard(), 3);
         assert col.sched.counts() == (0, 0, 0);
         note = col.newNote();
         note["Front"] = "two";
         col.addNote(note);
         col.reset();
         // initial pass should be correct too
         col.sched.answerCard(col.sched.getCard(), 3);
         assert col.sched.counts() == (0, 1, 0);
         col.sched.answerCard(col.sched.getCard(), 1);
         assert col.sched.counts() == (0, 1, 0);
         col.sched.answerCard(col.sched.getCard(), 4);
         assert col.sched.counts() == (0, 0, 0);
         // immediate graduate should work
         note = col.newNote();
         note["Front"] = "three";
         col.addNote(note);
         col.reset();
         col.sched.answerCard(col.sched.getCard(), 4);
         assert col.sched.counts() == (0, 0, 0);
         // and failing a review should too
         note = col.newNote();
         note["Front"] = "three";
         col.addNote(note);
         c = note.cards()[0];
         c.type = CARD_TYPE_REV;
         c.queue = QUEUE_TYPE_REV;
         c.due = col.sched.today;
         c.flush();
         col.reset();
         assert col.sched.counts() == (0, 0, 1);
         col.sched.answerCard(col.sched.getCard(), 1);
         assert col.sched.counts() == (0, 1, 0);
     }

     @Test
     public void test_timing(){
         col = getEmptyCol();
         // add a few review cards, due today
         for i in range(5):
             note = col.newNote();
             note["Front"] = "num" + str(i);
             col.addNote(note);
             c = note.cards()[0];
             c.type = CARD_TYPE_REV;
             c.queue = QUEUE_TYPE_REV;
             c.due = 0;
             c.flush();
         // fail the first one
         col.reset();
         c = col.sched.getCard();
         col.sched.answerCard(c, 1);
         // the next card should be another review
         c2 = col.sched.getCard();
         assert c2.queue == QUEUE_TYPE_REV;
         // if the failed card becomes due, it should show first
         c.due = intTime() - 1;
         c.flush();
         col.reset();
         c = col.sched.getCard();
         assert c.queue == QUEUE_TYPE_LRN;
     }

     @Test
     public void test_collapse(){
         col = getEmptyCol();
         // add a note
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         col.reset();
         // test collapsing
         c = col.sched.getCard();
         col.sched.answerCard(c, 1);
         c = col.sched.getCard();
         col.sched.answerCard(c, 4);
         assert not col.sched.getCard();
     }

     @Test
     public void test_deckDue(){
         col = getEmptyCol();
         // add a note with default deck
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         // and one that's a child
         note = col.newNote();
         note["Front"] = "two";
         default1 = note.model()["did"] = col.decks.id("Default::1");
         col.addNote(note);
         // make it a review card
         c = note.cards()[0];
         c.queue = QUEUE_TYPE_REV;
         c.due = 0;
         c.flush();
         // add one more with a new deck
         note = col.newNote();
         note["Front"] = "two";
         foobar = note.model()["did"] = col.decks.id("foo::bar");
         col.addNote(note);
         // and one that's a sibling
         note = col.newNote();
         note["Front"] = "three";
         foobaz = note.model()["did"] = col.decks.id("foo::baz");
         col.addNote(note);
         col.reset();
         assert len(col.decks.all_names_and_ids()) == 5;
         tree = col.sched.deck_due_tree().children;
         assert tree[0].name == "Default";
         // sum of child and parent
         assert tree[0].deck_id == 1;
         assert tree[0].review_count == 1;
         assert tree[0].new_count == 1;
         // child count is just review
         child = tree[0].children[0];
         assert child.name == "1";
         assert child.deck_id == default1;
         assert child.review_count == 1;
         assert child.new_count == 0;
         // code should not fail if a card has an invalid deck
         c.did = 12345;
         c.flush();
         col.sched.deck_due_tree();
     }

     @Test
     public void test_deckTree(){
         col = getEmptyCol();
         col.decks.id("new::b::c");
         col.decks.id("new2");
         // new should not appear twice in tree
         names = [x.name for x in col.sched.deck_due_tree().children];
         names.remove("new");
         assert "new" not in names;
     }

     @Test
     public void test_deckFlow(){
         col = getEmptyCol();
         // add a note with default deck
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         // and one that's a child
         note = col.newNote();
         note["Front"] = "two";
         default1 = note.model()["did"] = col.decks.id("Default::2");
         col.addNote(note);
         // and another that's higher up
         note = col.newNote();
         note["Front"] = "three";
         default1 = note.model()["did"] = col.decks.id("Default::1");
         col.addNote(note);
         // should get top level one first, then ::1, then ::2
         col.reset();
         assert col.sched.counts() == (3, 0, 0);
         for i in "one", "three", "two":
             c = col.sched.getCard();
             assert c.note()["Front"] == i;
             col.sched.answerCard(c, 3);
     }

     @Test
     public void test_reorder(){
         col = getEmptyCol();
         // add a note with default deck
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         note2 = col.newNote();
         note2["Front"] = "two";
         col.addNote(note2);
         assert note2.cards()[0].due == 2;
         found = False;
         // 50/50 chance of being reordered
         for i in range(20):
             col.sched.randomizeCards(1);
             if note.cards()[0].due != note.id:
                 found = True;
                 break;
         assert found;
         col.sched.orderCards(1);
         assert note.cards()[0].due == 1;
         // shifting
         f3 = col.newNote();
         f3["Front"] = "three";
         col.addNote(f3);
         f4 = col.newNote();
         f4["Front"] = "four";
         col.addNote(f4);
         assert note.cards()[0].due == 1;
         assert note2.cards()[0].due == 2;
         assert f3.cards()[0].due == 3;
         assert f4.cards()[0].due == 4;
         col.sched.sortCards([f3.cards()[0].id, f4.cards()[0].id], start=1, shift=True);
         assert note.cards()[0].due == 3;
         assert note2.cards()[0].due == 4;
         assert f3.cards()[0].due == 1;
         assert f4.cards()[0].due == 2;
     }

     @Test
     public void test_forget(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         c = note.cards()[0];
         c.queue = QUEUE_TYPE_REV;
         c.type = CARD_TYPE_REV;
         c.ivl = 100;
         c.due = 0;
         c.flush();
         col.reset();
         assert col.sched.counts() == (0, 0, 1);
         col.sched.forgetCards([c.id]);
         col.reset();
         assert col.sched.counts() == (1, 0, 0);
     }

     @Test
     public void test_resched(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         c = note.cards()[0];
         col.sched.reschedCards([c.id], 0, 0);
         c.load();
         assert c.due == col.sched.today;
         assert c.ivl == 1;
         assert c.queue == QUEUE_TYPE_REV and c.type == CARD_TYPE_REV;
         col.sched.reschedCards([c.id], 1, 1);
         c.load();
         assert c.due == col.sched.today + 1;
         assert c.ivl == +1;
     }

     @Test
     public void test_norelearn(){
         col = getEmptyCol();
         // add a note
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         c = note.cards()[0];
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
         col.sched.answerCard(c, 1);
         col.sched._cardConf(c)["lapse"]["delays"] = [];
         col.sched.answerCard(c, 1);
     }

     @Test
     public void test_failmult(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "one";
         note["Back"] = "two";
         col.addNote(note);
         c = note.cards()[0];
         c.type = CARD_TYPE_REV;
         c.queue = QUEUE_TYPE_REV;
         c.ivl = 100;
         c.due = col.sched.today - c.ivl;
         c.factor = STARTING_FACTOR;
         c.reps = 3;
         c.lapses = 1;
         c.startTimer();
         c.flush();
         conf = col.sched._cardConf(c);
         conf["lapse"]["mult"] = 0.5;
         col.decks.save(conf);
         c = col.sched.getCard();
         col.sched.answerCard(c, 1);
         assert c.ivl == 50;
         col.sched.answerCard(c, 1);
         assert c.ivl == 25;
     }

     @Test
     public void test_moveVersions(){
         col = getEmptyCol();
         col.changeSchedulerVer(1);

         n = col.newNote();
         n["Front"] = "one";
         col.addNote(n);

         // make it a learning card
         col.reset();
         c = col.sched.getCard();
         col.sched.answerCard(c, 1);

         // the move to v2 should reset it to new
         col.changeSchedulerVer(2);
         c.load();
         assert c.queue == QUEUE_TYPE_NEW;
         assert c.type == CARD_TYPE_NEW;

         // fail it again, and manually bury it
         col.reset();
         c = col.sched.getCard();
         col.sched.answerCard(c, 1);
         col.sched.buryCards([c.id]);
         c.load();
         assert c.queue == QUEUE_TYPE_MANUALLY_BURIED;

         // revert to version 1
         col.changeSchedulerVer(1);

         // card should have moved queues
         c.load();
         assert c.queue == QUEUE_TYPE_SIBLING_BURIED;

         // and it should be new again when unburied
         col.sched.unburyCards();
         c.load();
         assert c.type == CARD_TYPE_NEW and c.queue == QUEUE_TYPE_NEW;

         // make sure relearning cards transition correctly to v1
         col.changeSchedulerVer(2);
         // card with 100 day interval, answering again
         col.sched.reschedCards([c.id], 100, 100);
         c.load();
         c.due = 0;
         c.flush();
         conf = col.sched._cardConf(c);
         conf["lapse"]["mult"] = 0.5;
         col.decks.save(conf);
         col.sched.reset();
         c = col.sched.getCard();
         col.sched.answerCard(c, 1);
         // due should be correctly set when removed from learning early
         col.changeSchedulerVer(1);
         c.load();
         assert c.due == 50;
     }

     // cards with a due date earlier than the collection should retain
     // their due date when removed
     @Test
     public void test_negativeDueFilter(){
         col = getEmptyCol();

         // card due prior to collection date
         note = col.newNote();
         note["Front"] = "one";
         note["Back"] = "two";
         col.addNote(note);
         c = note.cards()[0];
         c.due = -5;
         c.queue = QUEUE_TYPE_REV;
         c.ivl = 5;
         c.flush();

         // into and out of filtered deck
         did = col.decks.newDyn("Cram");
         col.sched.rebuildDyn(did);
         col.sched.emptyDyn(did);
         col.reset();

         c.load();
         assert c.due == -5;
     }


     // hard on the first step should be the average of again and good,
     // and it should be logged properly

     @Test
     public void test_initial_repeat(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "one";
         note["Back"] = "two";
         col.addNote(note);

         col.reset();
         c = col.sched.getCard();
         col.sched.answerCard(c, 2);
         // should be due in ~ 5.5 mins
         expected = time.time() + 5.5 * 60;
         assert expected - 10 < c.due < expected * 1.25;

         ivl = col.db.scalar("select ivl from revlog");
         assert ivl == -5.5 * 60;
     }
     /*****************
          ** Stats        *
      *****************/

     @Test
     public void test_stats(){
         col = getEmptyCol();
         note = col.newNote();
         note["Front"] = "foo";
         col.addNote(note);
         c = note.cards()[0];
         // card stats
         assert col.cardStats(c);
         col.reset();
         c = col.sched.getCard();
         col.sched.answerCard(c, 3);
         col.sched.answerCard(c, 2);
         assert col.cardStats(c);
     }

     @Test
     public void test_graphs_empty(){
         col = getEmptyCol();
         assert col.stats().report();
     }

     @Test
     public void test_graphs(){
         dir = tempfile.gettempdir();
         col = getEmptyCol();
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
         col = getEmptyCol();
         m = col.models.current();
         m["tmpls"][0]["qfmt"] = "{{custom:Front}}";
         col.models.save(m);

         note = col.newNote();
         note["Front"] = "xxtest";
         note["Back"] = "";
         col.addNote(note);

         assert "xxtest" in note.cards()[0].a();
     }
     /*****************
          ** Undo         *
      *****************/
     private Collection getEmptyCol(){
         col = getEmptyColOrig();
         col.changeSchedulerVer(2);
         return col;
     }

     @Test
     public void test_op(){
         col = getEmptyCol();
         // should have no undo by default
         assert not col.undoName();
         // let's adjust a study option
         col.save("studyopts");
         col.conf["abc"] = 5;
         // it should be listed as undoable
         assert col.undoName() == "studyopts";
         // with about 5 minutes until it's clobbered
         assert time.time() - col._lastSave < 1;
         // undoing should restore the old value
         col.undo();
         assert not col.undoName();
         assert "abc" not in col.conf;
         // an (auto)save will clear the undo
         col.save("foo");
         assert col.undoName() == "foo";
         col.save();
         assert not col.undoName();
         // and a review will, too
         col.save("add");
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         col.reset();
         assert col.undoName() == "add";
         c = col.sched.getCard();
         col.sched.answerCard(c, 2);
         assert col.undoName() == "Review";
     }

     @Test
     public void test_review(){
         col = getEmptyCol();
         col.conf["counts"] = COUNT_REMAINING;
         note = col.newNote();
         note["Front"] = "one";
         col.addNote(note);
         col.reset();
         assert not col.undoName();
         // answer
         assert col.sched.counts() == (1, 0, 0);
         c = col.sched.getCard();
         assert c.queue == QUEUE_TYPE_NEW;
         col.sched.answerCard(c, 3);
         assert c.left == 1001;
         assert col.sched.counts() == (0, 1, 0);
         assert c.queue == QUEUE_TYPE_LRN;
         // undo
         assert col.undoName();
         col.undo();
         col.reset();
         assert col.sched.counts() == (1, 0, 0);
         c.load();
         assert c.queue == QUEUE_TYPE_NEW;
         assert c.left != 1001;
         assert not col.undoName();
         // we should be able to undo multiple answers too
         note = col.newNote();
         note["Front"] = "two";
         col.addNote(note);
         col.reset();
         assert col.sched.counts() == (2, 0, 0);
         c = col.sched.getCard();
         col.sched.answerCard(c, 3);
         c = col.sched.getCard();
         col.sched.answerCard(c, 3);
         assert col.sched.counts() == (0, 2, 0);
         col.undo();
         col.reset();
         assert col.sched.counts() == (1, 1, 0);
         col.undo();
         col.reset();
         assert col.sched.counts() == (2, 0, 0);
         // performing a normal op will clear the review queue
         c = col.sched.getCard();
         col.sched.answerCard(c, 3);
         assert col.undoName() == "Review";
         col.save("foo");
         assert col.undoName() == "foo";
         col.undo();
             }

 } 
