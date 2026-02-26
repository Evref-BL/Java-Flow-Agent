package fr.bl.drit.flow.agent;

import java.io.Closeable;
import java.io.IOException;

/** Specifies a recorder that is used to record flow data. */
public interface Recorder extends Closeable {
  /**
   * Emit a method enter event with the given method ID.
   *
   * @param methodId The ID of the method to trace
   * @throws IOException If an I/O error occurs when emitting the event.
   */
  void enter(long methodId) throws IOException;

  /**
   * Emit a method exit event.
   *
   * @throws IOException If an I/O error occurs when emitting the event.
   */
  void exit() throws IOException;
}
