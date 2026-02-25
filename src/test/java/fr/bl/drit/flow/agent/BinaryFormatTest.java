package fr.bl.drit.flow.agent;

import static fr.bl.drit.flow.agent.BinaryThreadRecorder.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/**
 * Binary format tests for {@link BinaryThreadRecorder}.
 *
 * <p>Assumptions about format (as discussed):
 *
 * <p>Events have a packed first byte: bit 7 = flag, bit 6 = continuation, bits 5-0 = low 6 bits of
 * payload. If continuation then following bytes are standard varint for remaining value (shifted).
 */
public class BinaryFormatTest {

  /** Helper: read unsigned LEB128 varint from InputStream */
  private static long readVarInt(InputStream in) throws IOException {
    long result = 0L;
    int shift = 0;
    while (true) {
      int b = in.read();
      if (b < 0) throw new EOFException("unexpected EOF reading varint");
      result |= (long) (b & 0x7F) << shift;
      if ((b & 0x80) == 0) break;
      shift += 7;
      if (shift > 63) throw new IOException("varint too long");
    }
    return result;
  }

  /**
   * Read the packed flag+varint first byte. Returns an object array: [Integer flag, Long value].
   * The value is reconstructed as low5 | (high << 5) where high is the varint read by readVarInt().
   */
  private static Object[] readFlagAndVarInt(InputStream in) throws IOException {
    int first = in.read();
    if (first < 0) throw new EOFException("unexpected EOF reading flag+varint first byte");
    byte flag = (byte) ((first) & 0xC0);
    boolean cont = (first & 0x20) != 0;
    long low5 = first & 0x1F;
    if (!cont) {
      return new Object[] {flag, low5};
    } else {
      long high = readVarInt(in);
      long value = low5 | (high << 5);
      return new Object[] {flag, value};
    }
  }

  @Test
  public void testSingleThreaded_enterNamedAndReference() throws Exception {
    File tmp = Files.createTempFile("single-thread-rec-", ".bin").toFile();
    tmp.deleteOnExit();

    try (BinaryThreadRecorder rec = new BinaryThreadRecorder(tmp)) {
      rec.enter(1L);
      rec.exit();
      rec.enter(1L); // same id
      rec.exit();
    } catch (IOException e) {
      fail(e);
    }

    try (InputStream in = new BufferedInputStream(new FileInputStream(tmp))) {
      // first event should be packed ENTER
      Object[] p1 = readFlagAndVarInt(in);
      byte flag1 = (byte) p1[0];
      long id1 = (long) p1[1];
      assertEquals(F_ENTER, flag1, "first event must be ENTER");
      assertEquals(1L, id1, "methodId in first ENTER must be 1");

      // Next event after ENTER was EXIT
      int b = in.read();
      assertEquals(F_EXIT, b, "second event must be EXIT");

      // Next should be second ENTER, which should be an ENTER referencing the methodId.
      Object[] p2 = readFlagAndVarInt(in);
      byte flag2 = (byte) p2[0];
      long id2 = (long) p2[1];
      assertEquals(F_ENTER, flag2, "third event must be ENTER");
      assertEquals(id1, id2, "methodId in second ENTER must match the one in first ENTER");

      // Next should be raw EXIT byte again
      int b2 = in.read();
      assertEquals(F_EXIT, b2, "fourth event must be EXIT");

      // stream should be exhausted
      int eof = in.read();
      assertEquals(-1, eof, "no more bytes expected");
    }
  }
}
