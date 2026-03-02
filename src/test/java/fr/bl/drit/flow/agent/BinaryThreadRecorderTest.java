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

  @TempDir private static Path tempDir;

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
  public void testWriteVarInt_noContinuation() throws Exception {
    String outputPath = null;
    try (BinaryThreadRecorder rec = new BinaryThreadRecorder(tempDir)) {
      outputPath = rec.getFileName();
      rec.writeVarInt(0L);
      rec.writeVarInt(1L);
      rec.writeVarInt(127L);
    }

    byte[] actual = Files.readAllBytes(tempDir.resolve(outputPath));

    byte[] e0 = leb128Expected(0L); // 00
    byte[] e1 = leb128Expected(1L); // 01
    byte[] e127 = leb128Expected(127L); // 7F

    byte[] expected = concat(e0, e1, e127);
    assertBytesEquals(expected, actual);
  }

  @Test
  public void testWriteVarInt_withContinuation() throws Exception {
    String outputPath = null;
    try (BinaryThreadRecorder rec = new BinaryThreadRecorder(tempDir)) {
      outputPath = rec.getFileName();
      rec.writeVarInt(128L);
      rec.writeVarInt(300L);
      rec.writeVarInt(Long.MAX_VALUE);
    }

    byte[] actual = Files.readAllBytes(tempDir.resolve(outputPath));

    byte[] e128 = leb128Expected(128L); // 80 01
    byte[] e300 = leb128Expected(300L); // AC 02
    byte[] eMax = leb128Expected(Long.MAX_VALUE);

    byte[] expected = concat(e128, e300, eMax);
    assertBytesEquals(expected, actual);
  }

  @Test
  public void testWriteFlagAndVarInt_singleByteNoContinuation() throws Exception {
    String outputPath = null;
    try (BinaryThreadRecorder rec = new BinaryThreadRecorder(tempDir)) {
      outputPath = rec.getFileName();
      // value fits in 5 bits (<=31), no continuation
      rec.writeFlagAndVarInt(F_ENTER, 0L); // 0x80|0x0 = 0x80
      rec.writeFlagAndVarInt(F_ENTER, 10L); // 0x80|0x0A = 0x8A
      rec.writeFlagAndVarInt(F_ENTER, 63L); // 0x80|0x3F = 0xBF

      rec.writeFlagAndVarInt(F_EXIT, 0L); // 0x0|0x0 = 0x0
      rec.writeFlagAndVarInt(F_EXIT, 10L); // 0x0|0x0A = 0x0A
      rec.writeFlagAndVarInt(F_EXIT, 63L); // 0x0|0x3F = 0x3F
    }

    byte[] actual = Files.readAllBytes(tempDir.resolve(outputPath));
    byte[] expected = new byte[] {(byte) 0x80, (byte) 0x8A, (byte) 0xBF, 0x0, 0x0A, 0x3F};
    assertBytesEquals(expected, actual);
  }

  @Test
  public void testWriteFlagAndVarInt_withContinuation() throws Exception {
    String outputPath = null;
    try (BinaryThreadRecorder rec = new BinaryThreadRecorder(tempDir)) {
      outputPath = rec.getFileName();
      rec.writeFlagAndVarInt(F_ENTER, 64L); // [0x80|0x40|0x0][0x0|0x01]
      rec.writeFlagAndVarInt(F_ENTER, 300L); // [0x80|0x40|0x2C][0x0|0x04]
      rec.writeFlagAndVarInt(F_ENTER, 8192L); // [0x80|0x40|0x0][0x40|0x0][0x0|0x01]

      rec.writeFlagAndVarInt(F_EXIT, 64L); // [0x0|0x40|0x0][0x0|0x01]
      rec.writeFlagAndVarInt(F_EXIT, 300L); // [0x0|0x40|0x2C][0x0|0x04]
      rec.writeFlagAndVarInt(F_EXIT, 8192L); // [0x0|0x40|0x0][0x0|0x0][0x0|0x01]
    }

    byte[] enter1 =
        new byte[] {
          F_ENTER | M_PACK_CONT | (byte) (64L & M_PACK_PAYLOAD), (byte) ((64L >>> 6) & M_PAYLOAD)
        };
    byte[] enter2 =
        new byte[] {
          F_ENTER | M_PACK_CONT | (byte) (300L & M_PACK_PAYLOAD), (byte) ((300L >>> 6) & M_PAYLOAD)
        };
    byte[] enter3 =
        new byte[] {
          F_ENTER | M_PACK_CONT | (byte) (8192L & M_PACK_PAYLOAD),
          M_CONT | (byte) ((8192L >>> 6) & M_PAYLOAD),
          (byte) ((8192L >>> (6 + 7)) & M_PAYLOAD)
        };

    byte[] exit1 =
        new byte[] {
          F_EXIT | M_PACK_CONT | (byte) (64L & M_PACK_PAYLOAD), (byte) ((64L >>> 6) & M_PAYLOAD)
        };
    byte[] exit2 =
        new byte[] {
          F_EXIT | M_PACK_CONT | (byte) (300L & M_PACK_PAYLOAD), (byte) ((300L >>> 6) & M_PAYLOAD)
        };
    byte[] exit3 =
        new byte[] {
          F_EXIT | M_PACK_CONT | (byte) (8192L & M_PACK_PAYLOAD),
          M_CONT | (byte) ((8192L >>> 6) & M_PAYLOAD),
          (byte) ((8192L >>> (6 + 7)) & M_PAYLOAD)
        };

    byte[] actual = Files.readAllBytes(tempDir.resolve(outputPath));
    byte[] expected = concat(enter1, enter2, enter3, exit1, exit2, exit3);

    StringBuilder sb = new StringBuilder();
    for (byte b : enter2) {
      sb.append(String.format("%02X ", b));
    }
    System.out.println(sb.toString());

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
