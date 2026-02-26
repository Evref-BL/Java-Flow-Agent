package fr.bl.drit.flow.agent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** A recorder that orchestrates per-thread recorders. */
public final class ThreadLocalRecorder implements Recorder {

  private final ThreadLocal<ThreadRecorder> local;
  private final Queue<ThreadRecorder> all = new ConcurrentLinkedQueue<>();

  /**
   * @param factory Responsible for creating {@link ThreadRecorder}s
   * @param outputDir Path to the output directory
   */
  public ThreadLocalRecorder(ThreadRecorderFactory factory, Path outputDir) {
    this.local =
        ThreadLocal.withInitial(
            () -> {
              try {
                ThreadRecorder recorder = factory.createForCurrentThread(outputDir);
                all.add(recorder);
                return recorder;
              } catch (IOException e) {
                throw new RuntimeException(
                    "Failed to create recorder for thread " + Thread.currentThread(), e);
              }
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
    for (ThreadRecorder recorder : all) {
      recorder.close();
    }
  }
}
