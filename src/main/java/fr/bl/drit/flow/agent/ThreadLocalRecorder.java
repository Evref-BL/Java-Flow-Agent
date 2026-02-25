package fr.bl.drit.flow.agent;

import java.io.File;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class ThreadLocalRecorder implements Recorder {

  private final ThreadLocal<Recorder> local;
  private final Queue<Recorder> all = new ConcurrentLinkedQueue<>();

  public ThreadLocalRecorder(ThreadRecorderFactory factory, File output) throws IOException {
    this.local =
        ThreadLocal.withInitial(
            () -> {
              Thread thread = Thread.currentThread();
              long tid = thread.getId();
              Recorder recorder = null;
              try {
                recorder =
                    factory.createForCurrentThread(new File(output, "thread-" + tid + ".flow"));
              } catch (IOException e) {
                throw new RuntimeException("Failed to create recorder for thread " + thread, e);
              }
              all.add(recorder);
              return recorder;
            });
  }

  @Override
  public void enter(long methodId) throws IOException {
    local.get().enter(methodId);
  }

  @Override
  public void exit() throws IOException {
    local.get().exit();
  }

  /** Flush and close all per-thread streams at shutdown. */
  @Override
  public void close() throws IOException {
    for (Recorder recorder : all) {
      recorder.close();
    }
  }
}
