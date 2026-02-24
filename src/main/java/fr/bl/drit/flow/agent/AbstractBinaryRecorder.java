package fr.bl.drit.flow.agent;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

public abstract class AbstractBinaryRecorder implements Recorder {

  // Header flags
  public static final byte F_SINGLE_THREADED = 0x01;
  public static final byte F_MULTI_THREADED = 0x02;

  // Event flags
  public static final byte F_ENTER = (byte) 0x40;
  public static final byte F_EXIT = (byte) 0x80;
  public static final byte F_ENTER_NAMED = (byte) 0xC0; // first time a name appears

  protected final OutputStream out;

  /** Maps method signature string -> nameId (the invocation id of the first ENTER_NAMED). */
  protected final Map<String, Long> nameIds;

  /** Additional consecutive exits, 0 means exactly one exit. */
  protected long pendingExitCount = 0L;

  // For stats
  protected long invocations = 0;

  /** Inject specific {@link Map} implementation for {@code nameIds}. */
  protected AbstractBinaryRecorder(File output, Map<String, Long> nameIds) throws IOException {
    this.nameIds = nameIds;

    File parent = output.getParentFile();
    if (parent != null && !parent.exists()) {
      parent.mkdirs();
    }
    out = new BufferedOutputStream(new FileOutputStream(output, false), 32 * 1024);

    writeHeader();
  }

  protected abstract void writeHeader() throws IOException;

  protected abstract void flushPendingExits() throws IOException;

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
   *   bits 7-6  : flag (0-3)
   *   bit 5     : continuation (1 if more bytes follow)
   *   bits 4-0  : lowest 5 bits of value
   * </code></pre>
   *
   * Following bytes (if any) are encoded using {@link #writeVarInt(long) writeVarInt}.
   */
  protected void writeFlagAndVarInt(int flag, long value) throws IOException {
    // Extract lowest 5 bits for first byte
    int firstPayload = (int) (value & 0x1F); // 5 bits
    value >>>= 5;

    if (value == 0) { // No continuation needed
      int firstByte =
          (flag) // bits 7-6
              | firstPayload; // bits 4-0 (bit 5 is 0 continuation)
      out.write(firstByte);
      return;
    }

    // Continuation required
    int firstByte =
        (flag) // bits 7-6
            | 0x20 // bit 5 = continuation
            | firstPayload; // bits 4-0

    out.write(firstByte);
    writeVarInt(value);
  }
}
