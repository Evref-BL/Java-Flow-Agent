package fr.bl.drit.flow.agent;

import java.io.IOException;
import net.bytebuddy.asm.Advice;

public class CallAdvice {

  /* (non-Javadoc)
  Use `inline = false` to avoid `invokedynamic` instructions which are illegal before Java 8.
  However, this can lead to a NoClassDefFoundError when the instrumented classloader cannot see the agent classes.
  If this happens, we should add a jar containing the necessary classes to the bootstrap classloader.

  Use `onThrowable = Throwable.class` to execute the exit hook even when an exception is thrown.
  The exception is then rethrown automatically to let the execution proceed normally.
  */

  @Advice.OnMethodEnter(inline = false)
  public static void enter(
      @Advice.Origin("#t") String className,
      @Advice.Origin("#m") String method,
      @Advice.Origin("#d") String descriptor) {
    String signature = className + "#" + method + descriptor.substring(0, descriptor.indexOf(')'));
    try {
      Singletons.RECORDER.enter(signature);
    } catch (IOException e) {
      System.err.println("[flow-agent] ENTER write error: " + e);
      e.printStackTrace();
    }
  }

  @Advice.OnMethodExit(inline = false, onThrowable = Throwable.class)
  public static void exit() {
    try {
      Singletons.RECORDER.exit();
    } catch (IOException e) {
      System.err.println("[flow-agent] EXIT write error: " + e);
      e.printStackTrace();
    }
  }
}
