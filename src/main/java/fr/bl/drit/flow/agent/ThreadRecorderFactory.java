package fr.bl.drit.flow.agent;

import java.io.IOException;
import java.nio.file.Path;

/** Functional interface for creating {@link ThreadRecorder}s. */
@FunctionalInterface
public interface ThreadRecorderFactory {

  /**
   * @param outputDir Path to output directory
   * @return A new {@link ThreadRecorder}
   * @throws IOException If an I/O error occurs when opening an output stream.
   */
  ThreadRecorder createForCurrentThread(Path outputDir) throws IOException;
}
