package fr.bl.drit.flow.agent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Call tree JSONL recorder.
 *
 * <p>Each line is either:
 *
 * <ul>
 *   <li>A method enter event: {@code {"e":"enter","method":"<id>"}}
 *   <li>A method exit event: {@code {"e":"exit"}}
 * </ul>
 */
public final class JsonlThreadRecorder implements ThreadRecorder {

  /** Writer for the call tree data of the recorder's thread. */
  private final Writer out;

  private final String fileName;

  /**
   * @param outputDir Path to output directory
   * @throws IOException If an I/O error occurs when opening an output stream.
   */
  public JsonlThreadRecorder(Path outputDir) throws IOException {
    this.fileName = "thread-" + Thread.currentThread().getId() + ".jsonl";
    this.out =
        new BufferedWriter(
            new OutputStreamWriter(
                Files.newOutputStream(
                    outputDir.resolve(this.fileName),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)),
            64 * 1024);
  }

  /**
   * @return The file name this recorder is writing to
   */
  public String getFileName() {
    return fileName;
  }

  @Override
  public void enter(long methodId) throws IOException {
    StringBuilder sb = new StringBuilder(128);
    sb.append("{\"e\":\"enter\",\"method\":\"").append(methodId).append("\"}\n");
    out.write(sb.toString());
  }

  @Override
  public void exit() throws IOException {
    out.write("{\"e\":\"exit\"}\n");
  }

  @Override
  public void close() throws IOException {
    out.flush();
    out.close();
  }
}
