package fr.bl.drit.flow.agent;

import static fr.bl.drit.flow.agent.AbstractBinaryRecorder.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/**
 * Binary format tests for {@link SingleThreadedBinaryRecorder} and {@link
 * MultiThreadedBinaryRecorder}.
 *
 * <p>Assumptions about format (as discussed):
 *
 * <p>File begins with a 1-byte header: F_SINGLE_THREADED or F_MULTI_THREADED
 *
 * <p>Events have a packed first byte: bits 7-6 = flag, bit 5 = continuation, bits 4-0 = low 5
 * payload. If continuation then following bytes are standard varint for remaining value (shifted).
 *
 * <p>This test locates and asserts those structures.
 */
public class RecorderBinaryFormatTest {

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

    try (SingleThreadedBinaryRecorder rec = new SingleThreadedBinaryRecorder(tmp)) {
      rec.enter("com.example.Foo#bar()");
      rec.exit();
      rec.enter("com.example.Foo#bar()"); // same name -> should reference existing nameId
      rec.exit();
    } catch (IOException e) {
      fail(e);
    }

    try (InputStream in = new BufferedInputStream(new FileInputStream(tmp))) {
      int header = in.read();
      assertEquals(F_SINGLE_THREADED, header, "file header should indicate single-threaded mode");

      // first event should be packed ENTER_NAMED (flag=EV_ENTER_NAMED)
      Object[] p1 = readFlagAndVarInt(in);
      byte flag1 = (byte) p1[0];
      long idRead1 = (long) p1[1];
      assertEquals(F_ENTER_NAMED, flag1, "first event must be ENTER_NAMED");
      // next: length varint and then name bytes
      long nameLen = readVarInt(in);
      byte[] nameBytes = in.readNBytes((int) nameLen);
      String name = new String(nameBytes, StandardCharsets.UTF_8);
      assertEquals("com.example.Foo#bar()", name, "enter_named should include the method name");
      // In single-threaded mode there is no TID appended. For single-thread format the next item
      // should be either EXIT or a packed event. We proceed.

      // Next event after ENTER_NAMED was EXIT
      int b = in.read();
      assertEquals(F_EXIT & 0xFF, b & 0xFF, "first exit should be raw EXIT byte");

      // Next should be second ENTER, which should be an ENTER referencing the nameId.
      Object[] p2 = readFlagAndVarInt(in);
      byte flag2 = (byte) p2[0];
      long nameIdRef = (long) p2[1];
      assertEquals(F_ENTER, flag2, "second enter should be a simple ENTER referencing name id");
      // nameIdRef should equal the id from first event (idRead1)
      assertEquals(
          idRead1, nameIdRef, "nameId reference must match the id assigned in ENTER_NAMED");

      // Next should be raw EXIT byte again
      int b2 = in.read();
      assertEquals(F_EXIT & 0xFF, b2 & 0xFF, "second exit should be raw EXIT byte");

      // stream should be exhausted
      int eof = in.read();
      assertEquals(-1, eof, "no more bytes expected");
    }
  }

  @Test
  public void testMultiThreaded_enterNamedReferenceAndExitAccumulation() throws Exception {
    File tmp = Files.createTempFile("multi-thread-rec-", ".bin").toFile();
    tmp.deleteOnExit();

    try (MultiThreadedBinaryRecorder rec = new MultiThreadedBinaryRecorder(tmp)) {
      // single thread usage but recorder is thread-aware
      rec.enter("com.example.Tool#doIt()");
      rec.exit();
      rec.exit(); // two consecutive exits should be coalesced into single EXIT with COUNT=1
      rec.close();
    }

    try (InputStream in = new BufferedInputStream(new FileInputStream(tmp))) {
      int header = in.read();
      assertEquals(
          F_MULTI_THREADED & 0xFF,
          header & 0xFF,
          "file header should indicate multi-threaded mode");

      // First event: ENTER_NAMED packed (flag EV_ENTER_NAMED)
      Object[] p1 = readFlagAndVarInt(in);
      byte flag1 = (byte) p1[0];
      assertEquals(F_ENTER_NAMED, flag1, "first event must be ENTER_NAMED (packed)");
      long nameLen = readVarInt(in);
      byte[] nameBytes = in.readNBytes((int) nameLen);
      String name = new String(nameBytes, StandardCharsets.UTF_8);
      assertEquals("com.example.Tool#doIt()", name, "enter_named should include the method name");
      // After the name, multithreaded writer appends TID varint
      long tid1 = readVarInt(in);

      // Next in stream should be EXIT raw byte
      Object[] p2 = readFlagAndVarInt(in);
      byte flag2 = (byte) p2[0];
      long count = (long) p2[1];
      assertEquals(F_EXIT, flag2, "expected EXIT byte");
      assertEquals(1L, count, "two consecutive exits should produce COUNT = 1 (additional exits)");
      // After EXIT and COUNT packed varint: TID varint
      long tid2 = readVarInt(in);
      assertEquals(tid1, tid2, "EXIT TID must match the TID of the thread that produced the exits");

      // No more bytes expected
      int eof = in.read();
      assertEquals(-1, eof, "no more bytes expected after EXIT record");
    }
  }
}
