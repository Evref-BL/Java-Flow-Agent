package fr.bl.drit.flow.agent;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Records a call tree in a compact binary format.
 *
 * <h2>Overview</h2>
 *
 * <p>The generated {@code .flow} file is a linear sequence of events without any global header. Two
 * event types exist:
 *
 * <ul>
 *   <li><b>ENTER</b>: a method entry event
 *   <li><b>EXIT</b>: one or more consecutive method exits
 * </ul>
 *
 * <p>Method identifiers are numeric values assigned by a {@link MethodIdMapping} and stored in a
 * separate file. The mapping associates a method identifier with a fully qualified method
 * signature.
 *
 * <h2>Event Structure</h2>
 *
 * <p>Each event starts with a single byte that encodes both:
 *
 * <ul>
 *   <li>the event type (ENTER or EXIT), and
 *   <li>the beginning of an unsigned variable-length integer.
 * </ul>
 *
 * <p>The most significant bit (bit 7) determines the event type:
 *
 * <ul>
 *   <li>{@code 1} ({@link #F_ENTER}): ENTER event
 *   <li>{@code 0} ({@link #F_EXIT}): EXIT event
 * </ul>
 *
 * <h2>Packed First Byte Layout</h2>
 *
 * <p>The first byte of every event has the following structure:
 *
 * <pre><code class="language-text">
 *   bit 7     : flag (1 = ENTER, 0 = EXIT)
 *   bit 6     : continuation bit (1 = more bytes follow)
 *   bits 5-0  : lowest 6 bits of the encoded value
 * </code></pre>
 *
 * <p>The encoded value depends on the event type:
 *
 * <ul>
 *   <li>For ENTER events: the value is the method ID.
 *   <li>For EXIT events: the value is the number of <i>additional</i> consecutive exits.
 * </ul>
 *
 * <h2>Variable-Length Integer Encoding</h2>
 *
 * <p>All integer values are encoded as unsigned variable-length integers in a format equivalent to
 * LEB128.
 *
 * <p>After the first byte, if the continuation bit (bit 6) is set, the remaining higher-order bits
 * of the value are encoded using standard 7-bit groups:
 *
 * <pre><code class="language-text">
 *   bit 7     : continuation (1 = more bytes follow)
 *   bits 6-0  : next 7 bits of the value
 * </code></pre>
 *
 * <p>Each subsequent byte contributes 7 additional bits to the value. Decoding proceeds by
 * accumulating payload bits while continuation bits are set.
 *
 * <h2>ENTER Event</h2>
 *
 * <p>An ENTER event encodes a single method entry as {@code [flag=1][methodId]}. The {@code
 * methodId} refers to the numeric identifier defined in the ID mapping file.
 *
 * <p>Example with {@code methodId = 1}: {@code 1000 0001}
 *
 * <p>Example with {@code methodId = 8192}: {@code 1100 0000 0000 0001}
 *
 * <h2>EXIT Event</h2>
 *
 * <p>EXIT events are run-length encoded.
 *
 * <p>Instead of writing one byte per exit, consecutive exits are accumulated internally and flushed
 * as a single event.
 *
 * <ul>
 *   <li>If exactly one exit occurred: {@code [flag=0]} (no continuation, no payload)
 *   <li>If {@code n > 1} consecutive exits occurred: {@code [flag=0][n - 1]}
 * </ul>
 *
 * <p>This means:
 *
 * <ul>
 *   <li>{@code value == 0} represents a single exit.
 *   <li>{@code value == k} represents {@code k + 1} consecutive exits.
 * </ul>
 *
 * <p>This run-length encoding significantly reduces file size when methods return in bursts.
 *
 * <h2>Decoding Algorithm (High-Level)</h2>
 *
 * <ol>
 *   <li>Read first byte.
 *   <li>Extract event type from bit 7.
 *   <li>Extract continuation bit from bit 6.
 *   <li>Extract lower 6 bits as initial value.
 *   <li>If continuation is set, read additional LEB128 bytes and accumulate 7-bit groups.
 *   <li>If ENTER: emit method entry with decoded {@code methodId}.
 *   <li>If EXIT: emit {@code value + 1} exits.
 * </ol>
 */
public class BinaryThreadRecorder implements ThreadRecorder {

  // ---------- Event flags ----------

  /** Highest bit is 1 (0x80). */
  public static final byte F_ENTER = (byte) 0x80;

  /** Highest bit is 0 (0x00). */
  public static final byte F_EXIT = 0x00;

  // ---------- Masks ----------

  /** Flag bit, the highest bit (0x80). */
  public static final byte M_FLAG = (byte) 0x80;

  /** Continuation bit in a varint, the highest bit (0x80). */
  public static final byte M_CONT = (byte) 0x80;

  /** Payload in a varint, the lowest 7 bits (0x7F). */
  public static final byte M_PAYLOAD = 0x7F;

  /** Rest of payload to encode in following varint bytes, all but 7 lowest bits (~0x7FL). */
  public static final long M_PAYLOAD_REST = ~0x7FL;

  /** Continuation bit in a packed varint, second highest bit (0x40). */
  public static final byte M_PACK_CONT = 0x40;

  /** Payload in a packed varint, the lowest 6 bits (0x3F). */
  public static final byte M_PACK_PAYLOAD = 0x3F;

  // ---------- State ----------

  /** Output stream for the binary call tree data of the recorder's thread. */
  protected final OutputStream out;

  /** The file containing the call tree data of the recorder's thread. */
  protected final String fileName;

  /** Pending consecutive exits. */
  protected long pendingExits = 0L;

  /**
   * @param outputDir Path to output directory
   * @throws IOException If an I/O error occurs when opening an output stream.
   */
  public BinaryThreadRecorder(Path outputDir) throws IOException {
    this.fileName = "thread-" + Thread.currentThread().getId() + ".flow";
    this.out =
        new BufferedOutputStream(
            Files.newOutputStream(
                outputDir.resolve(this.fileName),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING),
            64 * 1024);
  }

  /**
   * @return The file name this recorder is writing to
   */
  public String getFileName() {
    return fileName;
  }

  @Override
  public void enter(long methodId) throws IOException {
    flushPendingExits();
    writeFlagAndVarInt(F_ENTER, methodId);
  }

  @Override
  public void exit() throws IOException {
    pendingExits++;
  }

  /**
   * Flush a pending exit event if there is one.
   *
   * @throws IOException If an I/O error occurs when writing to file.
   */
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
   * Write an unsigned variable-length integer (LEB128-style).
   *
   * <pre><code class="language-text">
   *   bit 7     : continuation (1 if more bytes follow)
   *   bits 6-0  : next 7 bits of value
   * </code></pre>
   *
   * @param value The positive integer to encode
   * @throws IOException If an I/O error occurs when writing to file.
   */
  protected void writeVarInt(long value) throws IOException {
    while ((value & M_PAYLOAD_REST) != 0) {
      out.write((byte) ((value & M_PAYLOAD) | M_CONT));
      value >>>= 7;
    }
    out.write((byte) value);
  }

  /**
   * Write unsigned variable-length integer with a 1-bit flag packed into the first byte.
   *
   * <p>First byte layout:
   *
   * <pre><code class="language-text">
   *   bit 7     : flag
   *   bit 6     : continuation (1 if more bytes follow)
   *   bits 5-0  : lowest 6 bits of value
   * </code></pre>
   *
   * Following bytes (if any) are encoded using {@link #writeVarInt(long) writeVarInt}.
   *
   * @param flag The event flag
   * @param value The positive integer to encode
   * @throws IOException If an I/O error occurs when writing to file.
   */
  protected void writeFlagAndVarInt(byte flag, long value) throws IOException {
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
