package fr.bl.drit.flow.agent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Call tree JSONL recorder.
 *
 * <p>Each line is either: - {"e":"enter","method":"<id>"} - {"e":"exit"}
 */
public final class JsonlThreadRecorder implements ThreadRecorder {

  /** Writer for the call tree data of the recorder's thread. */
  private final Writer out;

  private final String fileName;

  public JsonlThreadRecorder(Path outputDir) throws IOException {
    this.fileName = "thread-" + Thread.currentThread().getId() + ".jsonl";
    this.out =
        new BufferedWriter(
            new OutputStreamWriter(
                Files.newOutputStream(
                    outputDir.resolve(this.fileName),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING),
                StandardCharsets.UTF_8),
            64 * 1024);
  }

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
