package fr.bl.drit.flow.agent;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Call tree binary recorder.
 *
 * <pre>
 *   [ENTER:0x80][METHOD_ID:packed_varint]
 *   [EXIT:0x00][COUNT:packed_varint]
 * </pre>
 *
 * Where METHOD_ID refers to the ID of the method stored... TODO
 */
public class BinaryThreadRecorder implements Recorder {

  // === Event flags
  /** Highest bit is 1 (0x80). */
  public static final byte F_ENTER = (byte) 0x80;

  /** Highest bit is 0 (0x00). */
  public static final byte F_EXIT = 0x00;

  // === Masks
  /** Flag bit, the highest bit (0x80). */
  public static final byte M_FLAG = (byte) 0x80;

  /** Continuation bit in a varint, the highest bit (0x80). */
  public static final byte M_CONT = (byte) 0x80;

  /** Payload in a varint, the lowest 7 bits (0x7F). */
  public static final byte M_PAYLOAD = 0x7F;

  /** Rest of payload to encode in following bytes in a varint, all but 7 lowest bits (~0x7FL). */
  public static final long M_PAYLOAD_REST = ~0x7FL;

  /** Continuation bit in a packed varint, second highest bit (0x40). */
  public static final byte M_PACK_CONT = 0x40;

  /** Payload in a packed varint, the lowest 6 bits (0x3F). */
  public static final byte M_PACK_PAYLOAD = 0x3F;

  protected final OutputStream out;

  /** Additional consecutive exits, 0 means exactly one exit. */
  protected long pendingExits = 0L;

  // For stats
  protected long invocations = 0L;

  public BinaryThreadRecorder(Path output) throws IOException {
    out =
        new BufferedOutputStream(
            Files.newOutputStream(
                output,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING),
            64 * 1024);
  }

  @Override
  public void enter(long methodId) throws IOException {
    invocations++; // only for stats

    flushPendingExits();
    writeFlagAndVarInt(F_ENTER, methodId);
  }

  @Override
  public void exit() throws IOException {
    pendingExits++;
  }

  protected void flushPendingExits() throws IOException {
    if (pendingExits == 0L) {
      return;
    } else if (pendingExits == 1L) {
      out.write(F_EXIT);
    } else {
      writeFlagAndVarInt(F_EXIT, pendingExits - 1);
    }
    pendingExits = 0L;
  }

  /**
   * Write an unsigned variable-length integer (LEB128 style).
   *
   * <pre><code class="language-text">
   *   bit 7     : continuation (1 if more bytes follow)
   *   bits 6-0  : next 7 bits of value
   * </code></pre>
   */
  protected void writeVarInt(long value) throws IOException {
    while ((value & M_PAYLOAD_REST) != 0) {
      out.write((byte) ((value & M_PAYLOAD) | M_CONT));
      value >>>= 7;
    }
    out.write((byte) value);
  }

  /**
   * Write unsigned variable-length integer with a 2-bit flag packed into the first byte.
   *
   * <p>First byte layout:
   *
   * <pre><code class="language-text">
   *   bit 7    : flag
   *   bit 6     : continuation (1 if more bytes follow)
   *   bits 5-0  : lowest 6 bits of value
   * </code></pre>
   *
   * Following bytes (if any) are encoded using {@link #writeVarInt(long) writeVarInt}.
   */
  protected void writeFlagAndVarInt(int flag, long value) throws IOException {
    // Extract lowest 6 bits for first byte
    int firstPayload = (int) (value & M_PACK_PAYLOAD); // 6 bits
    value >>>= 6;

    if (value == 0L) { // No continuation needed
      int firstByte =
          flag // bit 7
              | firstPayload; // bits 5-0 (bit 6 is 0 continuation)
      out.write(firstByte);
      return;
    }

    // Continuation required
    int firstByte =
        flag // bit 7
            | M_PACK_CONT // bit 6 = continuation
            | firstPayload; // bits 5-0

    out.write(firstByte);
    writeVarInt(value);
  }

  @Override
  public void close() throws IOException {
    flushPendingExits();
    out.flush();
    out.close();
  }
}
