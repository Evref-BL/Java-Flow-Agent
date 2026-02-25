package fr.bl.drit.flow.agent;

import static fr.bl.drit.flow.agent.BinaryThreadRecorder.*;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Utilities to read existing method-id mapping, count method invocations from BinaryThreadRecorder
 * trace files, and produce an optimized mapping.
 */
public final class MethodIdRemapper {

  private MethodIdRemapper() {}

  // ---------- I/O for mapping files (key=id) ----------

  private static Map<String, Long> readMapping(Path mappingFile) throws IOException {
    Map<String, Long> map = new LinkedHashMap<>();
    if (!Files.exists(mappingFile)) {
      return map;
    }
    try (BufferedReader r = Files.newBufferedReader(mappingFile, StandardCharsets.UTF_8)) {
      String line;
      while ((line = r.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("#")) continue;
        int eq = line.indexOf('=');
        if (eq <= 0) continue;
        String key = line.substring(0, eq).trim();
        String val = line.substring(eq + 1).trim();
        if (key.isEmpty() || val.isEmpty()) continue;
        long id = Long.parseLong(val);
        map.put(key, id);
      }
    }
    return map;
  }

  // ---------- Trace parsing (BinaryThreadRecorder encoding) ----------

  /**
   * Count enter events in all files under inputDir (non-recursive). The previousMapping is used to
   * translate methodId -> methodKey.
   *
   * <p>Returns a map methodKey -> count (0 if never seen), and prints diagnostics via returned
   * result (counts + number of unknown ids).
   */
  private static Map<String, Long> countCallsFromFlowDirectory(
      Path inputDir, Map<String, Long> previousMapping) throws IOException {
    Map<Long, String> idToKey = invertMapping(previousMapping);
    Map<String, Long> counts = new HashMap<>();

    // initialize all known methods with zero
    for (String key : previousMapping.keySet()) {
      counts.put(key, 0L);
    }

    long unknownIdEvents = 0L;

    try (DirectoryStream<Path> ds = Files.newDirectoryStream(inputDir, "*.flow")) {
      for (Path flowFile : ds) {
        if (!Files.isRegularFile(flowFile)) continue;

        try (InputStream in = new BufferedInputStream(Files.newInputStream(flowFile))) {
          long unknown = countCallsFromSingleTrace(in, idToKey, counts);
          unknownIdEvents += unknown;
        } catch (EOFException eof) {
          // ignore truncated file
        }
      }
    }

    if (unknownIdEvents > 0) {
      System.err.println(
          "[flow-agent] Found "
              + unknownIdEvents
              + " unknown IDs while processing flow files in "
              + inputDir);
    }

    return counts;
  }

  /**
   * Parse a single trace InputStream and increment counts map for enter events.
   *
   * <p>Returns the number of enter events whose methodId was NOT present in idToKey.
   */
  private static long countCallsFromSingleTrace(
      InputStream in, Map<Long, String> idToKey, Map<String, Long> counts) throws IOException {
    long unknowns = 0L;
    int b;
    while ((b = in.read()) != -1) {
      boolean flagEnter = (b & F_ENTER) != 0;
      boolean continuation = (b & M_PACK_CONT) != 0;
      long value = b & M_PACK_PAYLOAD;

      if (continuation) {
        // read LEB-like continuation bytes, starting at bit 6
        int shift = 6;
        while (true) {
          int next = in.read();
          if (next == -1) {
            throw new EOFException("Unexpected EOF while reading varint continuation");
          }
          value |= ((long) (next & M_PAYLOAD)) << shift;
          if ((next & F_ENTER) == 0) {
            break;
          }
          shift += 7;
        }
      }

      if (flagEnter) {
        long methodId = value;
        String key = idToKey.get(methodId);
        if (key != null) {
          counts.merge(key, 1L, Long::sum);
        } else {
          unknowns++;
        }
      } else {
        // exit events do not carry method id; value encodes extra exits (pendingExits-1)
        // we do not need to do anything for exit events for counting enters
      }
    }
    return unknowns;
  }

  private static Map<Long, String> invertMapping(Map<String, Long> mapping) {
    Map<Long, String> inv = new HashMap<>();
    for (Map.Entry<String, Long> e : mapping.entrySet()) {
      inv.put(e.getValue(), e.getKey());
    }
    return inv;
  }

  // ---------- Optimization: sort by frequency ----------

  /**
   * Create a new dense mapping by sorting methods by descending count. Tie-breaker: previousId
   * (ascending, missing previousId treated as Long.MAX_VALUE), then method key lexicographic.
   *
   * <p>Returns map methodKey -> newId.
   */
  private static Map<String, Long> optimizeMappingByCounts(
      Map<String, Long> previousMapping, Map<String, Long> counts) {
    // union of all keys: include methods present in counts or previous mapping
    Set<String> allKeys = new LinkedHashSet<>();
    allKeys.addAll(previousMapping.keySet());
    allKeys.addAll(counts.keySet());

    class Entry {
      final String key;
      final long count;
      final long prevId;

      Entry(String k, long c, long p) {
        key = k;
        count = c;
        prevId = p;
      }
    }

    List<Entry> list = new ArrayList<>(allKeys.size());
    for (String k : allKeys) {
      long c = counts.getOrDefault(k, 0L);
      long p = previousMapping.getOrDefault(k, Long.MAX_VALUE);
      list.add(new Entry(k, c, p));
    }

    list.sort(
        Comparator.comparingLong((Entry e) -> -e.count) // desc count
            .thenComparingLong(e -> e.prevId) // asc previous id
            .thenComparing(e -> e.key) // deterministic lexicographic
        );

    Map<String, Long> out = new LinkedHashMap<>();
    long nextId = 0L;
    for (Entry e : list) {
      out.put(e.key, nextId++);
    }
    return out;
  }

  // ---------- Convenience: orchestrator used by your agent ----------

  /**
   * Orchestrate reading previous mapping, scanning traces, optimizing and writing.
   *
   * @param inputDir directory containing binary trace files
   * @param outputDir directory where optimized mapping will be written
   */
  public static Path optimizeFromFlowDirectory(Path inputDir, Path outputDir) throws IOException {
    Path idsFile = inputDir.resolve("ids.properties");
    if (!Files.exists(idsFile)) {
      throw new FileNotFoundException("ids.properties not found in " + inputDir);
    }

    Map<String, Long> previous = readMapping(idsFile);
    Map<String, Long> counts = countCallsFromFlowDirectory(inputDir, previous);
    Map<String, Long> optimized = optimizeMappingByCounts(previous, counts);

    Path outPath = outputDir.resolve("ids.properties");

    MethodIdRegistry.dump(optimized, outPath);
    return outPath;
  }
}
