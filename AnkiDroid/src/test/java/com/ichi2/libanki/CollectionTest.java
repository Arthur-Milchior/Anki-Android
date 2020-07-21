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
public class CollectionTest extends RobolectricTest {

    /*******************
     ** Upstream tests *
     *******************/

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
}
