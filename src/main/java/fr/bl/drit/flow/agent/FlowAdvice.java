package fr.bl.drit.flow.agent;

import java.io.IOException;
import net.bytebuddy.asm.Advice;

/**
 * Contains the enter and exit {@link Advice} methods. Their code is inserted at the start and end
 * of instrumented methods, respectively.
 */
public final class FlowAdvice {

  /* (non-Javadoc)
  Use `inline = false` to avoid `invokedynamic` instructions which are illegal before Java 8.
  However, this can lead to a NoClassDefFoundError when the instrumented classloader cannot see the agent classes.
  If this happens, we should add a jar containing the necessary classes to the bootstrap classloader.

  Use `onThrowable = Throwable.class` to execute the exit hook even when an exception is thrown.
  The exception is then rethrown automatically to let the execution proceed normally.
  */

  /**
   * Record entering an instrumented method.
   *
   * @param methodId The ID of the instrumented method
   */
  @Advice.OnMethodEnter(inline = false)
  public static void enter(@MethodId long methodId) {
    try {
      Singletons.RECORDER.enter(methodId);
    } catch (IOException e) {
      System.err.println("[flow-agent] ENTER write error: " + e);
    }
  }

  /** Record exiting an instrumented method. */
  @Advice.OnMethodExit(inline = false, onThrowable = Throwable.class)
  public static void exit() {
    try {
      Singletons.RECORDER.exit();
    } catch (IOException e) {
      System.err.println("[flow-agent] EXIT write error: " + e);
    }
  }
}
