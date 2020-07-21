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
public class ExportingTest extends RobolectricTest {
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
    @Test
    public void empty_test() {
    }


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
}
