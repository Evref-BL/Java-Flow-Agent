package fr.bl.drit.flow.agent;

import static fr.bl.drit.flow.agent.BinaryThreadRecorder.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/** Tests for {@link BinaryThreadRecorder}. */
public class BinaryThreadRecorderTest {

  // helper to read file bytes after recorder closed
  private static byte[] contentOf(File f) throws IOException {
    return Files.readAllBytes(f.toPath());
  }

  // helper: expected LEB128 unsigned encoding (same algorithm used in writeVarInt)
  private static byte[] leb128Expected(long value) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    while ((value & ~0x7FL) != 0) {
      baos.write((int) ((value & 0x7F) | 0x80));
      value >>>= 7;
    }
    baos.write((int) (value & 0x7F));
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

  @Test
  public void testWriteVarInt_smallValues() throws Exception {
    File tmp = Files.createTempFile("rec-test-varint-", ".bin").toFile();
    tmp.deleteOnExit();

    try (BinaryThreadRecorder r = new BinaryThreadRecorder(tmp)) {
      r.writeVarInt(0L);
      r.writeVarInt(1L);
      r.writeVarInt(127L);
      r.writeVarInt(128L);
      r.writeVarInt(300L);
    } catch (IOException e) {
      fail(e);
    }

    byte[] actual = contentOf(tmp);

    byte[] e0 = leb128Expected(0L); // 00
    byte[] e1 = leb128Expected(1L); // 01
    byte[] e127 = leb128Expected(127L); // 7F
    byte[] e128 = leb128Expected(128L); // 80 01
    byte[] e300 = leb128Expected(300L); // AC 02

    byte[] expected = concat(e0, e1, e127, e128, e300);
    assertBytesEquals(expected, actual);
  }

  @Test
  public void testWriteFlagAndVarInt_singleByteNoContinuation() throws Exception {
    File tmp = Files.createTempFile("rec-test-flag-", ".bin").toFile();
    tmp.deleteOnExit();

    try (BinaryThreadRecorder r = new BinaryThreadRecorder(tmp)) {
      // value fits in 5 bits (<=31), no continuation
      r.writeFlagAndVarInt(F_ENTER, 10L); // 0x80|10 = 0x8A
      r.writeFlagAndVarInt(F_EXIT, 31L); // 0x00|31 = 0x1F
    } catch (IOException e) {
      fail(e);
    }

    byte[] actual = contentOf(tmp);
    byte[] expected = new byte[] {(byte) 0x8A, (byte) 0x1F};
    assertBytesEquals(expected, actual);
  }

  @Test
  public void testWriteFlagAndVarInt_withContinuation() throws Exception {
    File tmp = Files.createTempFile("rec-test-flag-cont-", ".bin").toFile();
    tmp.deleteOnExit();

    try (BinaryThreadRecorder r = new BinaryThreadRecorder(tmp)) {
      // value = 64 -> firstPayload=0, remainder=1 -> [flag+cont+0][0x01]
      r.writeFlagAndVarInt(F_ENTER, 64L);
      // value = 300 (0x12C): low6=44, remainder=4 -> [flag+cont+44][0x04]
      r.writeFlagAndVarInt(F_EXIT, 300L);
    } catch (IOException e) {
      fail(e);
    }

    byte[] actual = contentOf(tmp);

    byte[] expected1 = new byte[] {F_ENTER | 0x40, 0x01};
    int firstPayload = (int) (300L & 0x3F); // 44 = 0x2C
    byte firstByte = (byte) (F_EXIT | 0x40 | firstPayload);
    byte secondByte = (byte) ((300L >>> 6) & 0x7F); // 4 = 0x04
    byte[] expected2 = new byte[] {firstByte, secondByte};

    byte[] expected = concat(expected1, expected2);
    assertBytesEquals(expected, actual);
  }
}
