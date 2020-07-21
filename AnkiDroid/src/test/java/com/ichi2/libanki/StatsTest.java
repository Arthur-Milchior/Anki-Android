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
public class StatsTest extends RobolectricTest {

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
}
