package fr.bl.drit.flow.agent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Used in {@link FlowAdvice#enter(long)} to annotate the parameter that contains the instrumented
 * method ID.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface MethodId {}
