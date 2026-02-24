package fr.bl.drit.flow.agent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.MethodDescription.ForLoadedMethod;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.description.type.TypeDescription.ForLoadedType;

/**
 * Benchmark that uses ByteBuddy's Advice.Origin renderers to build canonical signatures:
 * FQCN#method(params)
 *
 * <p>Pipelines (matching your Advice examples): - descriptorPipeline: type(#t) + '#' + method(#m) +
 * params from descriptor(#d) up to ')' - javaSignaturePipeline: type(#t) + '#' + method(#m) +
 * signatureParens(#s) - originCleanPipeline: normalize(Advice.Origin default string)
 *
 * <p>Usage: Class<?>[] sample = new Class<?>[] { YourClass1.class, YourClass2.class };
 * ByteBuddySignatureBench.run(sample, warmup, iterations);
 */
public final class RendererBench {

  private RendererBench() {}

  public static void run(Class<?>[] sampleClasses, int warmup, int iterations) {
    Method[] methods = collectMethods(sampleClasses);
    System.out.println("[flow-agent] SignatureBench: collected " + methods.length + " methods");

    // pre-create TypeDescription and MethodDescription arrays (simulate renderer-only cost)
    TypeDescription[] tds = new TypeDescription[methods.length];
    ForLoadedMethod[] mds = new ForLoadedMethod[methods.length];
    for (int i = 0; i < methods.length; i++) {
      Method m = methods[i];
      tds[i] = ForLoadedType.of(m.getDeclaringClass());
      mds[i] = new ForLoadedMethod(m);
    }

    // the renderers we use (these are the same renderers Advice uses for @Advice.Origin tokens)
    Advice.OffsetMapping.ForOrigin.Renderer typeR =
        Advice.OffsetMapping.ForOrigin.Renderer.ForTypeName.INSTANCE;
    Advice.OffsetMapping.ForOrigin.Renderer methodR =
        Advice.OffsetMapping.ForOrigin.Renderer.ForMethodName.INSTANCE;
    Advice.OffsetMapping.ForOrigin.Renderer descR =
        Advice.OffsetMapping.ForOrigin.Renderer.ForDescriptor.INSTANCE;
    Advice.OffsetMapping.ForOrigin.Renderer javaSigR =
        Advice.OffsetMapping.ForOrigin.Renderer.ForJavaSignature.INSTANCE;
    Advice.OffsetMapping.ForOrigin.Renderer originR =
        Advice.OffsetMapping.ForOrigin.Renderer.ForStringRepresentation.INSTANCE;

    // warmup
    warmupLoop(tds, mds, warmup, typeR, methodR, descR, javaSigR, originR);

    // measurements
    measure(
        "descriptorPipeline (type#m + #d params substring)",
        tds,
        mds,
        iterations,
        (td, md) -> {
          String type = typeR.apply(td, md);
          String method = methodR.apply(td, md);
          String desc = descR.apply(td, md);
          int end = desc.indexOf(')') + 1;
          if (end <= 0) end = desc.length();
          return type + "#" + method + desc.substring(0, end);
        });

    measure(
        "javaSignaturePipeline (type#m + #s)",
        tds,
        mds,
        iterations,
        (td, md) -> {
          String type = typeR.apply(td, md);
          String method = methodR.apply(td, md);
          String jsig = javaSigR.apply(td, md); // e.g., "(java.lang.String,int)"
          return type + "#" + method + jsig;
        });

    measure(
        "originCleanPipeline (normalize(Origin.toString()))",
        tds,
        mds,
        iterations,
        (td, md) -> {
          String origin = originR.apply(td, md);
          return normalizeOriginToCanonical(origin);
        });
  }

  private static void warmupLoop(
      TypeDescription[] tds,
      ForLoadedMethod[] mds,
      int warmup,
      Advice.OffsetMapping.ForOrigin.Renderer typeR,
      Advice.OffsetMapping.ForOrigin.Renderer methodR,
      Advice.OffsetMapping.ForOrigin.Renderer descR,
      Advice.OffsetMapping.ForOrigin.Renderer javaSigR,
      Advice.OffsetMapping.ForOrigin.Renderer originR) {
    int n = tds.length;
    for (int i = 0; i < warmup; i++) {
      int idx = i % n;
      // call each pipeline once to warm up renderers & hotspot
      typeR.apply(tds[idx], mds[idx]);
      methodR.apply(tds[idx], mds[idx]);
      descR.apply(tds[idx], mds[idx]);
      javaSigR.apply(tds[idx], mds[idx]);
      originR.apply(tds[idx], mds[idx]);
    }
  }

  private static void measure(
      String name,
      TypeDescription[] tds,
      ForLoadedMethod[] mds,
      int iterations,
      Pipeline pipeline) {
    final int n = tds.length;
    long total = 0L;
    long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      int idx = i % n;
      long t0 = System.nanoTime();
      String sig = pipeline.apply(tds[idx], mds[idx]);
      long t1 = System.nanoTime();
      total += (t1 - t0);

      // trivial check to avoid accidental dead code elimination
      if (sig == null || sig.length() == 0) {
        System.err.println("[flow-agent] empty signature produced");
      }
    }
    long end = System.nanoTime();
    double avg = iterations == 0 ? 0.0 : ((double) total) / iterations;
    System.out.printf(
        "[flow-agent] Bench %s: iterations=%d totalNs=%d avgNs=%.1f wallNs=%d%n",
        name, iterations, total, avg, (end - start));
  }

  private static String normalizeOriginToCanonical(String origin) {
    if (origin == null || origin.isEmpty()) return origin;
    // remove throws clause
    int thr = origin.indexOf(" throws ");
    String s = thr >= 0 ? origin.substring(0, thr) : origin;
    int paramsStart = s.indexOf('(');
    if (paramsStart < 0) return origin;
    String paramsPart = s.substring(paramsStart); // includes (...)
    String before = s.substring(0, paramsStart).trim();
    String[] tokens = before.split("\\s+");
    if (tokens.length == 0) return origin;
    String fqMethod = tokens[tokens.length - 1]; // e.g. com.foo.Bar.baz
    int lastDot = fqMethod.lastIndexOf('.');
    if (lastDot < 0) return origin;
    String className = fqMethod.substring(0, lastDot);
    String methodName = fqMethod.substring(lastDot + 1);
    return className + "#" + methodName + paramsPart;
  }

  // collect declared methods from classes (skip synthetic / bridge)
  private static Method[] collectMethods(Class<?>[] classes) {
    List<Method> list = new ArrayList<>();
    for (Class<?> c : classes) {
      try {
        for (Method m : c.getDeclaredMethods()) {
          if (m.isSynthetic() || m.isBridge()) continue;
          list.add(m);
        }
      } catch (Throwable t) {
        System.err.println("[flow-agent] collectMethods error for " + c + ": " + t);
      }
    }
    return list.toArray(new Method[0]);
  }

  @FunctionalInterface
  private interface Pipeline {
    String apply(TypeDescription td, MethodDescription md);
  }
}
