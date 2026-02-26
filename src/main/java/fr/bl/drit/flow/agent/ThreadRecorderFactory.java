package fr.bl.drit.flow.agent;

import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
public interface ThreadRecorderFactory {
  ThreadRecorder createForCurrentThread(Path outDir) throws IOException;
}
