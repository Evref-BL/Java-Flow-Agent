package fr.bl.drit.flow.agent;

import java.io.File;
import java.io.IOException;

@FunctionalInterface
public interface ThreadRecorderFactory {
  Recorder createForCurrentThread(File directory) throws IOException;
}
