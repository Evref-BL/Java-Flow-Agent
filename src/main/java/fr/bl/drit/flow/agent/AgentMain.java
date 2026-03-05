package fr.bl.drit.flow.agent;

import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * The Java Flow agent entry point. It records method call trees for target classes and writes them
 * to files in a supplied directory. The directory will contain a method ID mapping file and a call
 * tree file for each thread. The call tree file format can be configured using the format argument,
 * which currently supports two formats: a compact binary format (.flow) and a more verbose JSON
 * Lines format (.jsonl). Both formats consist of two types of events: method entry and method exit.
 * Method entries are recorded with the ID of the entered method, which can be found in the method
 * ID mapping.
 *
 * <p>Arguments are comma-separated key=value pairs. The supported arguments are:
 *
 * <ul>
 *   <li>target (required) -> '+'-separated list of class name prefixes to instrument
 *   <li>out (required) -> output directory, will contain method ID mapping and per-thread call tree
 *       files
 *   <li>format (optional) -> "binary" (default) or "jsonl"
 *   <li>optimize (optional) -> path to flow directory to optimize method ID mapping
 *   <li>ids (optional) -> path to existing method ID mapping file
 * </ul>
 *
 * <pre><code class="language-properties">
 * Minimal example: target=com.myapp.,out=/tmp/flow/
 * </code></pre>
 *
 * Use the {@code optimize} argument to optimize method IDs by leveraging an existing mapping of
 * method IDs and flow files. Method IDs are natural numbers. Those that are called more frequently
 * will have smaller IDs. Using the {@code binary} format with variable-length integer encoding
 * significantly reduces the size of the recorded call tree data. The optimized mapping will be
 * written to the output directory and can be reused in subsequent runs by specifying its location
 * using the {@code ids} argument. Refer to the {@link MethodIdRemapper} class comment for more
 * details about the optimization process.
 *
 * <pre><code class="language-properties">
 * Example with optimization: target=com.myapp.,optimize=/tmp/flow/,out=/tmp/optimized-flow/
 * Then reuse optimized mapping: target=com.myapp.,ids=/tmp/optimized-flow/ids.properties,out=/tmp/optimized-flow-2/
 * </code></pre>
 */
public class AgentMain {

  /**
   * When starting the application with the agent.
   *
   * @param agentArgs Arguments to parse
   * @param inst Allows instrumenting Java code
   */
  public static void premain(String agentArgs, Instrumentation inst) {
    init(agentArgs, inst);
  }

  /**
   * When attaching the agent at runtime.
   *
   * @param agentArgs Arguments to parse
   * @param inst Allows instrumenting Java code
   */
  public static void agentmain(String agentArgs, Instrumentation inst) {
    init(agentArgs, inst);
  }

  private static void init(String agentArgs, Instrumentation inst) {
    // === parse arguments ===

    final Map<String, String> args = parseArgs(agentArgs);
    final String target = args.get("target");
    final String outputPath = args.get("out");
    final String format = args.getOrDefault("format", "binary");
    final String optimizePath = args.get("optimize");
    final boolean debug = args.getOrDefault("debug", "false").equalsIgnoreCase("true");
    String mappingPath = args.get("ids"); // can be overwritten if optimize is used

    if (target.isEmpty()) {
      System.err.println(
          "[flow-agent] No 'target' provided in agent arguments; instrumenter will be disabled.");
      printUsage(System.err);
      return;
    }

    if (outputPath == null) {
      System.err.println(
          "[flow-agent] No 'out' provided in agent arguments; instrumenter will be disabled.");
      printUsage(System.err);
      return;
    }

    // === process arguments ===

    // target classes
    final ElementMatcher<TypeDescription> typeMatcher = typeMatcher(target);
    if (typeMatcher == null) {
      System.err.println("[flow-agent] No prefixes found in 'target' argument.");
      return;
    }
    System.out.println("[flow-agent] Instrumenting classes starting with: " + target);

    // output directory
    final Path outputDir = Paths.get(outputPath).toAbsolutePath().normalize();
    try {
      Files.createDirectories(outputDir);
    } catch (IOException e) {
      System.err.println("[flow-agent] Failed to create output directory: " + outputDir);
      e.printStackTrace();
      return;
    }
    System.out.println("[flow-agent] Flow will be written to: " + outputDir);

    // call tree format
    final ThreadRecorderFactory factory =
        switch (format) {
          case "binary" -> BinaryThreadRecorder::new;
          case "jsonl" -> JsonlThreadRecorder::new;
          default -> throw new IllegalArgumentException("Unsupported format: " + format);
        };
    System.out.println("[flow-agent] Using call tree format: " + format);

    // optimize method ID mapping, set or overwrite 'ids' argument
    if (optimizePath != null) {
      try {
        Path optimizedMapping = MethodIdRemapper.optimize(Paths.get(optimizePath), outputDir);
        mappingPath = optimizedMapping.toAbsolutePath().normalize().toString();
        System.out.println("[flow-agent] Optimized method mapping: " + mappingPath);
      } catch (IOException e) {
        System.err.println("[flow-agent] Failed to optimize method mapping: " + e);
      }
    }

    // method ID mapping
    final MethodIdMapping idMapping;
    if (mappingPath != null) {
      try {
        idMapping = new MethodIdMapping(Paths.get(mappingPath));
        System.out.println(
            "[flow-agent] Loaded " + idMapping.size() + " method IDs from " + mappingPath);
      } catch (IOException e) {
        System.err.println("[flow-agent] Failed to load method IDs from " + mappingPath + ": " + e);
        return;
      }
    } else {
      idMapping = new MethodIdMapping();
    }

    // === recorder setup ===

    Singletons.RECORDER = new ThreadLocalRecorder(factory, outputDir);

    final boolean hasMappingPath = mappingPath != null;

    // register shutdown hook to close recorder
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    Singletons.RECORDER.close();

                    if (!hasMappingPath) {
                      idMapping.dump(outputDir.resolve("ids.properties"));
                    }

                    System.out.println("[flow-agent] Flow written to " + outputDir);
                  } catch (IOException e) {
                    System.err.println("[flow-agent] Failed to write flow: " + e);
                    e.printStackTrace();
                  }
                }));

    // === instrumentation setup ===

    Advice advice =
        Advice.withCustomMapping()
            .bind(MethodId.class, new MethodIdOffsetMapping(idMapping))
            .to(FlowAdvice.class);

    ElementMatcher<MethodDescription> methodMatcher =
        isMethod().and(not(isConstructor())).and(not(isAbstract()));

    AgentBuilder agentBuilder =
        new AgentBuilder.Default()
            .ignore(nameStartsWith("net.bytebuddy."))
            .ignore(nameStartsWith("sun."))
            .ignore(nameStartsWith("java."))
            .ignore(nameStartsWith("jdk."))
            .type(typeMatcher)
            .transform(
                (builder, typeDescription, classLoader, module, protectionDomain) ->
                    builder.visit(advice.on(methodMatcher)));

    // add a listener to print instrumentation events if debug is enabled
    if (debug) {
      agentBuilder = agentBuilder.with(AgentBuilder.Listener.StreamWriting.toSystemOut());
    }
    
    agentBuilder.installOn(inst);
  }

  /**
   * Parse agent arguments into a dictionary using:
   *
   * <ul>
   *   <li>argument separator: ','
   *   <li>key/value separator: '='
   * </ul>
   */
  private static Map<String, String> parseArgs(String args) {
    Map<String, String> map = new HashMap<>();

    if (args == null || args.trim().isEmpty()) {
      return map;
    }

    String[] pairs = args.split(",");
    for (String pair : pairs) {
      String trimmed = pair.trim();
      if (trimmed.isEmpty()) continue;

      String[] kv = trimmed.split("=", 2);
      String key = kv[0].trim();
      String value = kv[1].trim();

      if (key.isEmpty() || value.isEmpty()) {
        System.err.println("[flow-agent] Ignoring empty key/value in agent argument: " + trimmed);
        continue;
      }

      map.put(key, value);
    }

    return map;
  }

  private static ElementMatcher<TypeDescription> typeMatcher(String targetValue) {
    // split on '+' and build an OR matcher:
    // nameStartsWith(t1).or(nameStartsWith(t2))...
    String[] tokens = targetValue.split("\\+");
    ElementMatcher.Junction<TypeDescription> typeMatcher = null;
    for (String tok : tokens) {
      String p = tok.trim();
      if (p.isEmpty()) continue;
      if (typeMatcher == null) {
        typeMatcher = nameStartsWith(p);
      } else {
        typeMatcher = typeMatcher.or(nameStartsWith(p));
      }
    }
    return typeMatcher;
  }

  private static void printUsage(PrintStream out) {
    out.println(
        "[flow-agent] Usage: target=<prefix[+prefix...]>,out=<dir>[,format=binary|jsonl][,optimize=<dir>][,ids=<file>]");
    out.println("  target   : '+'-separated list of class name prefixes to instrument (required)");
    out.println("  out      : output directory for flow files and method ID mapping (required)");
    out.println("  format   : call tree format: 'binary' (default) or 'jsonl'");
    out.println("  optimize : path to existing flow directory to optimize method IDs (optional)");
    out.println("  ids      : path to existing method ID mapping file to reuse IDs (optional)");
  }
}
