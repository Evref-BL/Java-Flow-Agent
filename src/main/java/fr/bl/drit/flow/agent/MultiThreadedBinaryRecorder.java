package fr.bl.drit.flow.agent;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Compact flow binary recorder for multi-threaded applications.
 *
 * <p>In a multithreaded context, ENTER events contain a thread ID field, and EXIT events include
 * the invocation ID:
 *
 * <pre>
 *   [ENTER:0x01][NAME_ID:packed_varint][TID:varint]
 *   [EXIT:0x02][COUNT:packed_varint][TID:varint]
 *   [ENTER_NAMED:0x03][ID:packed_varint][LENGTH:varint][NAME:utf8][TID:varint]
 * </pre>
 *
 * Where NAME_ID refers to the ID of the first ENTER_NAMED that introduced this NAME.
 */
public final class MultiThreadedBinaryRecorder extends AbstractBinaryRecorder {

  /** Monotonic name id generator. */
  protected final AtomicLong nextNameId = new AtomicLong(1L);

  protected final Object writeLock = new Object();
  protected final Object poolLock = new Object();

  /**
   * Run-length encoding for EXIT events: pending run under writeLock. pendingExitTid == 0 => no
   * pending exit run.
   */
  protected long pendingExitTid = 0L;

  // For stats
  protected final Set<Long> threads = new HashSet<>();

  public MultiThreadedBinaryRecorder(File output) throws IOException {
    super(output, new ConcurrentHashMap<>());
  }

  @Override
  public void enter(String methodSignature) throws IOException {
    long tid = Thread.currentThread().getId();

    // stats
    invocations++;
    threads.add(tid);

    Long existingNameId = nameIds.get(methodSignature);
    if (existingNameId == null) {
      synchronized (poolLock) {
        existingNameId = nameIds.get(methodSignature);
        if (existingNameId == null) {
          // first time we see this name: this invocationId becomes the NAME_ID
          long invocationId = nextNameId.getAndIncrement();
          nameIds.put(methodSignature, invocationId);
          writeEnterNamed(invocationId, methodSignature, tid);
          return;
        }
      }
    }

    // already known name: write ENTER referencing NAME_ID
    writeEnter(existingNameId.longValue(), tid);
  }

  @Override
  public void exit() throws IOException {
    long tid = Thread.currentThread().getId();
    synchronized (writeLock) {
      if (pendingExitTid == 0L) { // start run: one exit => COUNT = 0
        pendingExitTid = tid;
        pendingExitCount = 1L;
        return;
      }

      if (pendingExitTid == tid) { // extend run: COUNT is "additional exits"
        pendingExitCount++;
        return;
      }

      // thread changed: flush previous run, start new one
      flushPendingExits();
      pendingExitTid = tid;
      pendingExitCount = 1L;
    }
  }

  protected void writeHeader() throws IOException {
    out.write(F_MULTI_THREADED);
  }

  protected void writeEnterNamed(long invocationId, String name, long tid) throws IOException {
    byte[] utf8 = name.getBytes(StandardCharsets.UTF_8);
    synchronized (writeLock) {
      flushPendingExits();
      writeFlagAndVarInt(F_ENTER_NAMED, invocationId);
      writeVarInt(utf8.length);
      out.write(utf8);
      writeVarInt(tid);
    }
  }

  protected void writeEnter(long nameId, long tid) throws IOException {
    synchronized (writeLock) {
      flushPendingExits();
      writeFlagAndVarInt(F_ENTER, nameId);
      writeVarInt(tid);
    }
  }

  protected void flushPendingExits() throws IOException {
    if (pendingExitCount == 0L) {
      return;
    }
    writeFlagAndVarInt(F_EXIT, pendingExitCount - 1);
    writeVarInt(pendingExitTid);

    pendingExitTid = 0L;
    pendingExitCount = 0L;
  }

  @Override
  public void close() throws IOException {
    synchronized (writeLock) {
      flushPendingExits();
      out.flush();
      out.close();
    }
    System.out.println(
        "[flow-agent] Recorded "
            + invocations
            + " calls across "
            + nameIds.size()
            + " methods and "
            + threads.size()
            + " threads.");
  }
}
