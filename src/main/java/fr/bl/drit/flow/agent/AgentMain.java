package fr.bl.drit.flow.agent;

import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;

import java.io.File;
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
 * Java agent entrypoint.
 *
 * <p>agentArgs are comma-separated key=value pairs, e.g. target=com.myapp.,out=/tmp/flow/
 * <li>{@code target} is required.
 * <li>{@code out} is required.
 */
public class AgentMain {

  public static void premain(String agentArgs, Instrumentation inst) {
    init(agentArgs, inst);
  }

  public static void agentmain(String agentArgs, Instrumentation inst) {
    init(agentArgs, inst);
  }

  private static void init(String agentArgs, Instrumentation inst) {
    // === parse arguments ===

    final Map<String, String> args = parseAgentArgs(agentArgs);
    final String target = args.get("target");
    final String outputPath = args.get("out");
    final String format = args.getOrDefault("format", "binary");
    final String optimizePath = args.get("optimize");
    String registryPath = args.get("registry");

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
    ElementMatcher<TypeDescription> typeMatcher = typeMatcher(target);
    if (typeMatcher == null) {
      System.err.println("[flow-agent] No valid prefixes found in 'target' argument.");
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

    // optimize method ID registry, set or overwrite 'registryPath' argument
    if (optimizePath != null) {
      try {
        Path optimizedRegistry =
            MethodIdRemapper.optimizeFromFlowDirectory(Paths.get(optimizePath), outputDir);
        registryPath = optimizedRegistry.toAbsolutePath().normalize().toString();
        System.out.println("[flow-agent] Optimized method registry: " + registryPath);
      } catch (IOException e) {
        System.err.println("[flow-agent] Failed to optimize method registry: " + e);
      }
    }

    // method ID registry
    final MethodIdRegistry idRegistry;
    if (registryPath != null) {
      try {
        idRegistry = new MethodIdRegistry(new File(registryPath));
        System.out.println(
            "[flow-agent] Loaded " + idRegistry.size() + " method IDs from " + registryPath);
      } catch (IOException e) {
        System.err.println(
            "[flow-agent] Failed to load method IDs from " + registryPath + ": " + e);
        return;
      }
    } else {
      idRegistry = new MethodIdRegistry();
    }

    // === recorder setup ===

    try {
      Singletons.RECORDER = new ThreadLocalRecorder(factory, outputDir);
    } catch (IOException e) {
      System.err.println("[flow-agent] Failed to create writer: " + e);
      e.printStackTrace();
    }

    final String finalRegistryPath = registryPath;

    // register shutdown hook to close recorder
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    Singletons.RECORDER.close();

                    if (finalRegistryPath == null) {
                      idRegistry.dump(outputDir.resolve("ids.properties"));
                    }

                    System.out.println("[flow-agent] Flow written to " + outputDir);
                  } catch (Exception e) {
                    System.err.println("[flow-agent] Failed to write flow: " + e);
                    e.printStackTrace();
                  }
                }));

    // === instrumentation setup ===

    Advice advice =
        Advice.withCustomMapping()
            .bind(MethodId.class, new MethodIdOffsetMapping(idRegistry))
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

    // agentBuilder = agentBuilder.with(AgentBuilder.Listener.StreamWriting.toSystemOut());

    agentBuilder.installOn(inst);
  }

  /**
   * Parse agentArgs using: - top-level pair separator: ',' - key/value separator: '='
   *
   * <p>Supported keys: - target (required) -> '+'-separated list - out (optional)
   *
   * <p>Example: target=com.myapp.+org.example.service,out=/tmp/cg
   */
  private static Map<String, String> parseAgentArgs(String agentArgs) {
    Map<String, String> map = new HashMap<>();

    if (agentArgs == null || agentArgs.trim().isEmpty()) {
      return map;
    }

    String[] pairs = agentArgs.split(",");
    for (String pair : pairs) {
      String trimmed = pair.trim();
      if (trimmed.isEmpty()) continue;

      String[] kv = trimmed.split("=", 2);
      if (kv.length != 2) {
        System.err.println(
            "[flow-agent] Invalid agent argument entry (expected key=value): " + trimmed);
        continue;
      }

      String key = kv[0].trim();
      String value = kv[1].trim();

      if (key.isEmpty() || value.isEmpty()) {
        System.err.println("[flow-agent] Invalid key/value in agent argument: " + trimmed);
        continue;
      }

      map.put(key, value);
    }

    if (!map.containsKey("target")) {
      System.err.println("[flow-agent] Missing required 'target' argument.");
    }

    return map;
  }

  private static ElementMatcher<TypeDescription> typeMatcher(String targetValue) {
    if (targetValue.isEmpty()) {
      System.err.println("[flow-agent] 'target' is empty; nothing to instrument.");
      return null;
    }

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
    out.println("[flow-agent] Provide agent arguments like: target=com.myapp.,out=/tmp/flow");
  }
}
