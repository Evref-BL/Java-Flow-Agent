package fr.bl.drit.flow.agent;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * Call tree JSONL recorder.
 *
 * <p>Each line is either: - {"e":"enter","method":"<id>"} - {"e":"exit"}
 */
public final class JsonlThreadRecorder implements Recorder {

  private final Writer out;

  public JsonlThreadRecorder(File output) throws IOException {
    out =
        new BufferedWriter(
            new OutputStreamWriter(new FileOutputStream(output, false), StandardCharsets.UTF_8),
            64 * 1024);
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
