package fr.bl.drit.flow.agent;

import static fr.bl.drit.flow.agent.BinaryThreadRecorder.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link BinaryThreadRecorder}. */
public class BinaryThreadRecorderTest {

  @TempDir private Path tempDir;

  /** Expected LEB128 unsigned encoding (same algorithm used in writeVarInt). */
  private static byte[] leb128Expected(long value) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    while ((value & M_PAYLOAD_REST) != 0) {
      baos.write((int) ((value & M_PAYLOAD) | M_CONT));
      value >>>= 7;
    }
    baos.write((int) value);
    return baos.toByteArray();
  }

  private static String toHex(byte b) {
    return String.format("0x%02X", b & 0xFF);
  }

  private static void assertBytesEquals(byte[] expected, byte[] actual) {
    int min = Math.min(expected.length, actual.length);
    for (int i = 0; i < min; i++) {
      assertEquals(
          expected[i],
          actual[i],
          "Byte mismatch at index "
              + i
              + ": expected "
              + toHex(expected[i])
              + " but was "
              + toHex(actual[i]));
    }
    // if lengths differ, fail with helpful message
    assertEquals(
        expected.length,
        actual.length,
        "Length mismatch: expected " + expected.length + " but was " + actual.length);
  }

  private static byte[] concat(byte[]... parts) {
    int len = 0;
    for (byte[] p : parts) len += p.length;
    byte[] out = new byte[len];
    int pos = 0;
    for (byte[] p : parts) {
      System.arraycopy(p, 0, out, pos, p.length);
      pos += p.length;
    }
    return out;
  }

  /** Helper: read unsigned LEB128 varint from InputStream */
  private static long readVarInt(InputStream in) throws IOException {
    long result = 0L;
    int shift = 0;
    while (true) {
      int b = in.read();
      if (b < 0) throw new EOFException("unexpected EOF reading varint");
      result |= (long) (b & M_PAYLOAD) << shift;
      if ((b & M_CONT) == 0) break;
      shift += 7;
      if (shift > 63) throw new IOException("varint too long");
    }
    return result;
  }

  /**
   * Read the packed flag+varint first byte. Returns an object array: [Byte flag, Long value]. The
   * value is reconstructed as low6 | (high << 6) where high is the varint read by readVarInt().
   */
  private static Object[] readFlagAndVarInt(InputStream in) throws IOException {
    int first = in.read();
    if (first < 0) throw new EOFException("unexpected EOF reading flag+varint first byte");
    byte flag = (byte) (first & M_FLAG);
    boolean cont = (first & M_PACK_CONT) != 0;
    long low6 = first & M_PACK_PAYLOAD;
    if (!cont) {
      return new Object[] {flag, low6};
    } else {
      long high = readVarInt(in);
      long value = low6 | (high << 6);
      return new Object[] {flag, value};
    }
  }

  @Test
  public void testWriteVarInt_smallValues() throws Exception {
    String outputPath = null;
    try (BinaryThreadRecorder rec = new BinaryThreadRecorder(tempDir)) {
      outputPath = rec.getFileName();
      rec.writeVarInt(0L);
      rec.writeVarInt(1L);
      rec.writeVarInt(127L);
      rec.writeVarInt(128L);
      rec.writeVarInt(300L);
      rec.writeVarInt(Long.MAX_VALUE);
    }

    byte[] actual = Files.readAllBytes(tempDir.resolve(outputPath));

    byte[] e0 = leb128Expected(0L); // 00
    byte[] e1 = leb128Expected(1L); // 01
    byte[] e127 = leb128Expected(127L); // 7F
    byte[] e128 = leb128Expected(128L); // 80 01
    byte[] e300 = leb128Expected(300L); // AC 02
    byte[] eMax = leb128Expected(Long.MAX_VALUE);

    byte[] expected = concat(e0, e1, e127, e128, e300, eMax);
    assertBytesEquals(expected, actual);
  }

  @Test
  public void testWriteFlagAndVarInt_singleByteNoContinuation() throws Exception {
    String outputPath = null;
    try (BinaryThreadRecorder rec = new BinaryThreadRecorder(tempDir)) {
      outputPath = rec.getFileName();
      // value fits in 5 bits (<=31), no continuation
      rec.writeFlagAndVarInt(F_ENTER, 10L); // 0x80|10 = 0x8A
      rec.writeFlagAndVarInt(F_EXIT, 31L); // 0x00|31 = 0x1F
    }

    byte[] actual = Files.readAllBytes(tempDir.resolve(outputPath));
    byte[] expected = new byte[] {(byte) 0x8A, (byte) 0x1F};
    assertBytesEquals(expected, actual);
  }

  @Test
  public void testWriteFlagAndVarInt_withContinuation() throws Exception {
    String outputPath = null;
    try (BinaryThreadRecorder rec = new BinaryThreadRecorder(tempDir)) {
      outputPath = rec.getFileName();
      // value = 64 -> firstPayload=0, remainder=1 -> [flag+cont+0][0x01]
      rec.writeFlagAndVarInt(F_ENTER, 64L);
      // value = 300 (0x12C): low6=44, remainder=4 -> [flag+cont+44][0x04]
      rec.writeFlagAndVarInt(F_EXIT, 300L);
    }

    byte[] actual = Files.readAllBytes(tempDir.resolve(outputPath));

    byte[] expected1 = new byte[] {F_ENTER | 0x40, 0x01};
    int firstPayload = (int) (300L & M_PACK_PAYLOAD); // 44 = 0x2C
    byte firstByte = (byte) (F_EXIT | M_PACK_CONT | firstPayload);
    byte secondByte = (byte) ((300L >>> 6) & M_PAYLOAD); // 4 = 0x04
    byte[] expected2 = new byte[] {firstByte, secondByte};

    byte[] expected = concat(expected1, expected2);
    assertBytesEquals(expected, actual);
  }

  @Test
  public void testEnterAndExit() throws Exception {
    String outputPath = null;
    try (BinaryThreadRecorder rec = new BinaryThreadRecorder(tempDir)) {
      outputPath = rec.getFileName();
      rec.enter(1L);
      rec.exit();
      rec.enter(1L); // same id
      rec.exit();
    }

    try (InputStream in = Files.newInputStream(tempDir.resolve(outputPath))) {
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
