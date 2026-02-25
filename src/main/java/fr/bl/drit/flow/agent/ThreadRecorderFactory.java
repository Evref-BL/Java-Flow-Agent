package fr.bl.drit.flow.agent;

import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
public interface ThreadRecorderFactory {
  Recorder createForCurrentThread(Path directory) throws IOException;
}
