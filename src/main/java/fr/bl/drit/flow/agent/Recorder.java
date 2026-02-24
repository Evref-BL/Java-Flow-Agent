package fr.bl.drit.flow.agent;

import java.io.Closeable;
import java.io.IOException;

/** Common interface for execution recorders. */
public interface Recorder extends Closeable {
  /**
   * Emit an enter event for the given class and method signature. Returns the assigned invocation
   * id for this enter.
   */
  long enter(String methodSignature) throws IOException;

  /** Emit an exit event for the given invocation id. */
  void exit() throws IOException;
}
