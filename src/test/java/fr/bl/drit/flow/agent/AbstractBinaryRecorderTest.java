package fr.bl.drit.flow.agent;

import static fr.bl.drit.flow.agent.AbstractBinaryRecorder.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

/** Tests for AbstractBinaryRecorder's varint encoders. */
public class AbstractBinaryRecorderTest {

  /** Minimal concrete subclass used for testing protected encoder methods. */
  private static final class TestRecorder extends AbstractBinaryRecorder {

    TestRecorder(File output) throws IOException {
      super(output, new HashMap<>());
    }

    @Override
    public long enter(String methodSignature) {
      return -1;
    }

    @Override
    public void exit() {
      // no-op
    }

    @Override
    protected void writeHeader() throws IOException {
      // no-op
    }

    @Override
    protected void flushPendingExits() throws IOException {
      // no-op
    }

    @Override
    public void close() throws IOException {
      out.flush();
      out.close();
    }
  }

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

    try (AbstractBinaryRecorder r = new TestRecorder(tmp)) {
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

    try (AbstractBinaryRecorder r = new TestRecorder(tmp)) {
      // value fits in 5 bits (<=31), no continuation
      r.writeFlagAndVarInt(F_ENTER, 10L); // 0x40|10 = 0x4A
      r.writeFlagAndVarInt(F_EXIT, 31L); // 0x80|31 = 0x9F
      r.writeFlagAndVarInt(F_ENTER_NAMED, 5L); // 0xC0|5 = 0xC5
    } catch (IOException e) {
      fail(e);
    }

    byte[] actual = contentOf(tmp);
    byte[] expected = new byte[] {(byte) 0x4A, (byte) 0x9F, (byte) 0xC5};
    assertBytesEquals(expected, actual);
  }

  @Test
  public void testWriteFlagAndVarInt_withContinuation() throws Exception {
    File tmp = Files.createTempFile("rec-test-flag-cont-", ".bin").toFile();
    tmp.deleteOnExit();

    try (AbstractBinaryRecorder r = new TestRecorder(tmp)) {
      // value = 32 -> firstPayload=0, remainder 1 -> [flag+cont+0][0x01]
      r.writeFlagAndVarInt(F_ENTER, 32L);
      // value = 300 (0x12C): low5=12, remainder=9 -> [flag+cont+12][0x09]
      r.writeFlagAndVarInt(F_EXIT, 300L);
    } catch (IOException e) {
      fail(e);
    }

    byte[] actual = contentOf(tmp);

    byte[] expected1 = new byte[] {(byte) 0x60, (byte) 0x01};
    int firstPayload = (int) (300L & 0x1F); // 12 = 0x0C
    byte firstByte = (byte) (F_EXIT | 0x20 | firstPayload);
    byte secondByte = (byte) ((300L >>> 5) & 0x7F); // 9
    byte[] expected2 = new byte[] {firstByte, secondByte};

    byte[] expected = concat(expected1, expected2);
    assertBytesEquals(expected, actual);
  }
}
