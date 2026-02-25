package fr.bl.drit.flow.agent;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

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

  // Event flags
  public static final byte F_ENTER = (byte) 0x80;
  public static final byte F_EXIT = (byte) 0x00;

  protected final OutputStream out;

  /** Additional consecutive exits, 0 means exactly one exit. */
  protected long pendingExits = 0L;

  // For stats
  protected long invocations = 0L;

  public BinaryThreadRecorder(File output) throws IOException {
    out = new BufferedOutputStream(new FileOutputStream(output, false), 64 * 1024);
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
    while ((value & ~0x7FL) != 0) {
      out.write((byte) ((value & 0x7F) | 0x80));
      value >>>= 7;
    }
    out.write((byte) (value & 0x7F));
  }

  /**
   * Write unsigned variable-length integer with a 2-bit flag packed into the first byte.
   *
   * <p>First byte layout:
   *
   * <pre><code class="language-text">
   *   bits 7    : flag
   *   bit 6     : continuation (1 if more bytes follow)
   *   bits 5-0  : lowest 6 bits of value
   * </code></pre>
   *
   * Following bytes (if any) are encoded using {@link #writeVarInt(long) writeVarInt}.
   */
  protected void writeFlagAndVarInt(int flag, long value) throws IOException {
    // Extract lowest 6 bits for first byte
    int firstPayload = (int) (value & 0x3F); // 6 bits
    value >>>= 6;

    if (value == 0) { // No continuation needed
      int firstByte =
          flag // bit 7
              | firstPayload; // bits 5-0 (bit 6 is 0 continuation)
      out.write(firstByte);
      return;
    }

    // Continuation required
    int firstByte =
        flag // bit 7
            | 0x40 // bit 6 = continuation
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
