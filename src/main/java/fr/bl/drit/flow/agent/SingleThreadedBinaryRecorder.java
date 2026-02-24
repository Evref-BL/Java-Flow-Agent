package fr.bl.drit.flow.agent;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/**
 * Compact flow binary recorder for single-threaded applications.
 *
 * <pre>
 *   [ENTER:0x01][ID:packed_varint][NAME_ID:varint]
 *   [EXIT:0x02][COUNT:packed_varint]
 *   [ENTER_NAMED:0x03][ID:packed_varint][LENGTH:varint][NAME:utf8]
 * </pre>
 *
 * Where NAME_ID refers to the ID of the first ENTER_NAMED that introduced this NAME.
 */
public class SingleThreadedBinaryRecorder extends AbstractBinaryRecorder {

  /** Monotonic invocation id generator. */
  protected long nextInvocationId = 1L;

  protected SingleThreadedBinaryRecorder(File output) throws IOException {
    super(output, new HashMap<>());
  }

  @Override
  public long enter(String methodSignature) throws IOException {
    long invocationId = nextInvocationId++;

    // stats
    invocations++;

    Long existingNameId = nameIds.get(methodSignature);
    if (existingNameId == null) {
      // first time we see this name: this invocationId becomes the NAME_ID
      nameIds.put(methodSignature, invocationId);
      writeEnterNamed(invocationId, methodSignature);
      return invocationId;
    }

    // name already known: write ENTER referencing NAME_ID
    writeEnter(invocationId, existingNameId);
    return invocationId;
  }

  @Override
  public void exit() throws IOException {
    pendingExitCount++;
  }

  protected void writeHeader() throws IOException {
    out.write(F_SINGLE_THREADED);
  }

  protected void writeEnterNamed(long invocationId, String name) throws IOException {
    byte[] utf8 = name.getBytes(StandardCharsets.UTF_8);
    flushPendingExits();
    writeFlagAndVarInt(F_ENTER_NAMED, invocationId);
    writeVarInt(utf8.length);
    out.write(utf8);
  }

  protected void writeEnter(long invocationId, long nameId) throws IOException {
    flushPendingExits();
    writeFlagAndVarInt(F_ENTER, invocationId);
    writeVarInt(nameId);
  }

  protected void flushPendingExits() throws IOException {
    if (pendingExitCount == 0L) {
      return;
    }
    writeFlagAndVarInt(F_EXIT, pendingExitCount - 1);
    pendingExitCount = 0L;
  }

  @Override
  public void close() throws IOException {
    flushPendingExits();
    out.flush();
    out.close();
    System.out.println(
        "[flow-agent] Recorded " + invocations + " calls across " + nameIds.size() + " methods.");
  }
}
