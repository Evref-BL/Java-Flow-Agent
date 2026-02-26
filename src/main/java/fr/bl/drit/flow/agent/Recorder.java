package fr.bl.drit.flow.agent;

import java.io.Closeable;
import java.io.IOException;

/** Specifies a recorder that is used to record flow data. */
public interface Recorder extends Closeable {
  /** Emit a method enter event with the given method ID. */
  void enter(long methodId) throws IOException;

  /** Emit a method exit event. */
  void exit() throws IOException;
}
