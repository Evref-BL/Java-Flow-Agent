package fr.bl.drit.flow.agent;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple NDJSON writer for debugging / POC.
 *
 * <p>Each line is either: {"e":"enter","tid":<tid>,"id":<id>,"method":"<fqcn#signature>"}
 * {"e":"exit","tid":<tid>,"id":<id>}
 */
public final class JSONLRecorder implements Recorder {

  private final Writer out;
  private final Object writeLock = new Object();
  private final AtomicLong nextId = new AtomicLong(1);

  public JSONLRecorder(String path) throws IOException {
    File f = new File(path);
    File parent = f.getParentFile();
    if (parent != null && !parent.exists()) parent.mkdirs();
    this.out =
        new BufferedWriter(
            new OutputStreamWriter(new FileOutputStream(f, false), StandardCharsets.UTF_8),
            32 * 1024);
  }

  @Override
  public void enter(String methodSignature) throws IOException {
    long id = nextId.getAndIncrement();
    long tid = Thread.currentThread().getId();
    StringBuilder sb = new StringBuilder(128);
    sb.append("{\"e\":\"enter\",\"tid\":")
        .append(tid)
        .append(",\"id\":")
        .append(id)
        .append(",\"method\":\"")
        .append(methodSignature)
        .append("\"}\n");
    synchronized (writeLock) {
      out.write(sb.toString());
    }
  }

  @Override
  public void exit() throws IOException {
    long tid = Thread.currentThread().getId();
    StringBuilder sb = new StringBuilder(64);
    sb.append("{\"e\":\"exit\",\"tid\":").append(tid).append("}\n");
    synchronized (writeLock) {
      out.write(sb.toString());
    }
  }

  @Override
  public void close() throws IOException {
    synchronized (writeLock) {
      out.flush();
      out.close();
    }
  }
}
