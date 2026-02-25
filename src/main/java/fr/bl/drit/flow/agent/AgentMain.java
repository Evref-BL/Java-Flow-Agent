package fr.bl.drit.flow.agent;

import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;

import fr.bl.drit.flow.agent.FlowAdvice.MethodId;
import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
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
 * <p>agentArgs are comma-separated key=value pairs (keys: target, out) e.g.
 * target=com.myapp.,out=/tmp/cg.json
 *
 * <p>'target' is required (the binary-name target used with nameStartsWith()). 'out' is optional;
 * defaults to "flow" in the working directory.
 */
public class AgentMain {

  public static void premain(String agentArgs, Instrumentation inst) {
    init(agentArgs, inst);
  }

  public static void agentmain(String agentArgs, Instrumentation inst) {
    init(agentArgs, inst);
  }

  private static void init(String agentArgs, Instrumentation inst) {
    // // Example: benchmark classes from your app (use classes you control)
    // Class<?>[] sample = new Class<?>[] {Advice.class, ObjectMapper.class,
    // ObjectWriter.class};
    // // run quick benchmarks
    // RendererBench.run(sample, /*warmup*/ 20_000, /*measure*/ 200_000);
    // // then continue with agent installation

    final Map<String, String> args = parseAgentArgs(agentArgs);
    final String target = args.get("target");
    final String outPath = args.getOrDefault("out", "flow");
    final String format = args.getOrDefault("format", "binary");
    final String methodIdsPath = args.get("methodIds");

    // target classes
    if (target.isEmpty()) {
      System.err.println(
          "[flow-agent] No 'target' provided in agentArgs; instrumenter will be disabled.");
      System.err.println("[flow-agent] Provide agentArgs like: target=com.myapp.,out=/tmp/flow");
      return;
    }

    ElementMatcher<TypeDescription> typeMatcher = typeMatcher(target);
    if (typeMatcher == null) {
      System.err.println("[flow-agent] No valid prefixes found in 'target' argument.");
      return;
    }

    // output directory
    final File outputDir = new File(outPath);
    System.out.println("[flow-agent] Instrumenting classes starting with: " + target);
    System.out.println("[flow-agent] Callgraph will be written to: " + outputDir.getAbsolutePath());

    // call tree format
    final ThreadRecorderFactory factory =
        switch (format) {
          case "binary" -> BinaryThreadRecorder::new;
          case "jsonl" -> JsonlThreadRecorder::new;
          default -> throw new IllegalArgumentException("Unsupported format: " + format);
        };
    System.out.println("[flow-agent] Using call tree format: " + format);

    // method ID registry
    final MethodIdRegistry methodRegistry;
    if (methodIdsPath != null) {
      try {
        methodRegistry = new MethodIdRegistry(new File(methodIdsPath));
        System.out.println(
            "[flow-agent] Loaded " + methodRegistry.size() + " method IDs from " + methodIdsPath);
      } catch (IOException e) {
        System.err.println(
            "[flow-agent] Failed to load method IDs from " + methodIdsPath + ": " + e);
        e.printStackTrace();
        return;
      }
    } else {
      methodRegistry = new MethodIdRegistry();
    }

    // === recorder setup ===

    try {
      Singletons.RECORDER = new ThreadLocalRecorder(factory, outputDir);
    } catch (IOException e) {
      System.err.println("[flow-agent] failed to create writer: " + e);
      e.printStackTrace();
    }

    // register shutdown hook to close recorder
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    Singletons.RECORDER.close();
                    System.out.println(
                        "[flow-agent] flow written to " + outputDir.getAbsolutePath());
                  } catch (Exception e) {
                    System.err.println("[flow-agent] failed to write flow: " + e);
                    e.printStackTrace();
                  }
                }));

    // === instrumentation setup ===

    Advice advice =
        Advice.withCustomMapping()
            .bind(MethodId.class, new MethodIdOffsetMapping(methodRegistry))
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

    // agentBuilder =
    // agentBuilder.with(AgentBuilder.Listener.StreamWriting.toSystemOut());

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
}
