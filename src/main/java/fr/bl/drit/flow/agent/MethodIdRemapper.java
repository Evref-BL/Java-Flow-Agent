package fr.bl.drit.flow.agent;

import static fr.bl.drit.flow.agent.BinaryThreadRecorder.*;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utilities to read existing method ID mapping, count method invocations from {@link Recorder}
 * trace files, and produce an optimized mapping.
 */
public final class MethodIdRemapper {

  private MethodIdRemapper() {}

  /** Read mapping from a file, using the method ID as the key and the signature as the value. */
  private static Map<Long, String> readMapping(Path mappingFile) throws IOException {
    Map<Long, String> map = new HashMap<>();
    if (!Files.exists(mappingFile)) {
      return map;
    }

    try (BufferedReader r = Files.newBufferedReader(mappingFile)) {
      String line;
      while ((line = r.readLine()) != null) {
        if (line.isEmpty() || line.startsWith("#")) continue;
        int eq = line.indexOf('=');
        if (eq <= 0) continue;
        String signature = line.substring(0, eq);
        String idString = line.substring(eq + 1);
        if (signature.isEmpty() || idString.isEmpty()) continue;
        long id = Long.parseLong(idString);
        map.put(id, signature);
      }
    }
    return map;
  }

  // ---------- Trace parsing of BinaryThreadRecorder encoding ----------

  /**
   * Count enter events in all files under inputDir (non-recursive).
   *
   * @return Map of methodId -> count (0 if never seen).
   */
  private static Map<Long, Long> countCalls(Path inputDir, Map<Long, String> previousMapping)
      throws IOException {
    Map<Long, Long> counts = new HashMap<>();

    // initialize all known methods with zero
    for (Long id : previousMapping.keySet()) {
      counts.put(id, 0L);
    }

    // iterate over each thread's call tree
    try (DirectoryStream<Path> ds = Files.newDirectoryStream(inputDir, "*.flow")) {
      for (Path flowFile : ds) {
        if (!Files.isRegularFile(flowFile)) continue;

        try (InputStream in = new BufferedInputStream(Files.newInputStream(flowFile))) {
          countCallsInThread(in, previousMapping, counts);
        } catch (EOFException eof) {
          System.err.println(eof);
        }
      }
    }

    return counts;
  }

  /** Parse a single trace file and increment {@code counts} map for enter events. */
  private static void countCallsInThread(
      InputStream in, Map<Long, String> idToSignature, Map<Long, Long> counts) throws IOException {
    int b;
    while ((b = in.read()) != -1) {
      boolean isEnter = (b & F_ENTER) != 0;
      boolean continuation = (b & M_PACK_CONT) != 0;
      long methodId = b & M_PACK_PAYLOAD;

      if (continuation) {
        // read LEB-like continuation bytes, starting at bit 6
        int shift = 6;
        while (true) {
          int next = in.read();
          if (next == -1) {
            throw new EOFException("Unexpected EOF while reading varint continuation");
          }
          methodId |= ((long) (next & M_PAYLOAD)) << shift;
          if ((next & M_CONT) == 0) {
            break;
          }
          shift += 7;
        }
      }

      if (isEnter) { // only enter events are relevant for counting calls
        counts.merge(methodId, 1L, Long::sum);
      }
    }
  }

  // ---------- Optimization: sort by frequency ----------

  /**
   * Create a new mapping by sorting methods by descending count.
   *
   * @return Map of methodKey -> newId
   */
  private static Map<String, Long> optimizeMappingByCounts(
      Map<Long, String> mapping, Map<Long, Long> counts) {
    // store all IDs in a list for sorting
    // they are supposed to be dense (continuous from 0 to N-1) so we could use the mapping size
    // however, the current implementation is defensive and allows for sparse mappings
    List<Long> ids = new ArrayList<>(mapping.size());
    for (Long id : mapping.keySet()) {
      ids.add(id);
    }

    // sort by descending count
    ids.sort(Comparator.comparingLong(id -> -counts.getOrDefault(id, 0L)));

    // assign new IDs starting from most frequently called methods
    Map<String, Long> optimized = new HashMap<>();
    long nextId = 0L;
    for (Long id : ids) {
      optimized.put(mapping.get(id), nextId++);
    }
    return optimized;
  }

  /**
   * Read existing traces and mapping. Then, optimize the mapping and write it.
   *
   * @param inputDir directory containing trace files
   * @param outputDir directory where optimized mapping will be written
   * @return path to optimized mapping file
   * @throws IOException If an I/O error occurs when reading from {@code inputDir} or writing to
   *     {@code outputDir}.
   */
  public static Path optimize(Path inputDir, Path outputDir) throws IOException {
    Path idsFile = inputDir.resolve("ids.properties");
    if (!Files.exists(idsFile)) {
      throw new FileNotFoundException("ids.properties not found in " + inputDir);
    }

    Map<Long, String> previous = readMapping(idsFile);
    Map<Long, Long> counts = countCalls(inputDir, previous);
    Map<String, Long> optimized = optimizeMappingByCounts(previous, counts);

    Path outPath = outputDir.resolve("ids.properties");
    MethodIdMapping.dump(optimized, outPath);
    return outPath;
  }
}
