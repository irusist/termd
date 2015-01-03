package io.termd.core.readline;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class ReaderTest {

  @Test
  public void testDecodeKeySeq() {
    Reader reader = new Reader(new ByteArrayInputStream("\"ab\":foo".getBytes()));
    reader.append('a').reduce();
    assertEquals(0, reader.getEvents().size());
    reader.append('b', 'c').reduce();
    assertEquals(2, reader.getEvents().size());
    FunctionEvent action = (FunctionEvent) reader.getEvents().get(0);
    assertEquals("foo", action.getName());
    KeyEvent key = (KeyEvent) reader.getEvents().get(1);
    assertEquals(1, key.length());
    assertEquals('c', key.getAt(0));
  }

  @Test
  public void testDecodeKeySeqPrefix() {
    Reader reader = new Reader(new ByteArrayInputStream("\"ab\":foo".getBytes()));
    reader.append('a').reduce();
    assertEquals(0, reader.getEvents().size());
    reader.append('c').reduce();
    assertEquals(2, reader.getEvents().size());
    KeyEvent key = (KeyEvent) reader.getEvents().get(0);
    assertEquals(1, key.length());
    assertEquals('a', key.getAt(0));
    key = (KeyEvent) reader.getEvents().get(1);
    assertEquals(1, key.length());
    assertEquals('c', key.getAt(0));
  }

  @Test
  public void testRecognizePredefinedKey1() {
    Reader reader = new Reader();
    reader.append(27, 91, 65);
    reader.append(65);
    reader.reduceOnce();
    assertEquals(1, reader.getEvents().size());
    assertEquals(Collections.<Event>singletonList(Keys.UP), reader.getEvents());
  }

  @Test
  public void testRecognizePredefinedKey2() {
    Reader reader = new Reader();
    reader.append(27, 91);
    reader.append(66);
    reader.reduceOnce();
    assertEquals(1, reader.getEvents().size());
    assertEquals(Collections.<Event>singletonList(Keys.DOWN), reader.getEvents());
  }

  @Test
  public void testNotRecognizePredefinedKey() {
    Reader reader = new Reader();
    reader.append('a');
    reader.reduceOnce();
    assertEquals(1, reader.getEvents().size());
    KeyEvent key = (KeyEvent) reader.getEvents().get(0);
    assertEquals(1, key.length());
    assertEquals('a', key.getAt(0));
  }

  @Test
  public void testDouble() {
    Reader reader = new Reader();
    reader.append(27);
    reader.append(65);
    reader.reduceOnce();
    reader.reduceOnce();
    assertEquals(2, reader.getEvents().size());
  }
}
