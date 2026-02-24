package fr.bl.drit.flow.agent;

import java.io.Closeable;
import java.io.IOException;

/** Common interface for execution recorders. */
public interface Recorder extends Closeable {
  /** Emit an enter event for the given class and method signature. */
  void enter(String methodSignature) throws IOException;

  /** Emit an exit event for the given invocation id. */
  void exit() throws IOException;
}
