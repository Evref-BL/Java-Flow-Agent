package fr.bl.drit.flow.agent;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.build.HashCodeAndEqualsPlugin;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.implementation.bytecode.constant.LongConstant;

/**
 * Custom OffsetMapping to inject method IDs into the advice. Method IDs are assigned by the
 * provided {@link MethodIdRegistry}.
 */
@HashCodeAndEqualsPlugin.Enhance
public final class MethodIdOffsetMapping implements Advice.OffsetMapping {
  private final MethodIdRegistry registry;

  MethodIdOffsetMapping(MethodIdRegistry registry) {
    this.registry = registry;
  }

  @Override
  public Advice.OffsetMapping.Target resolve(
      TypeDescription instrumentedType,
      MethodDescription instrumentedMethod,
      Assigner assigner,
      Advice.ArgumentHandler argumentHandler,
      Advice.OffsetMapping.Sort sort) {
    String key =
        instrumentedType.getName()
            + "#"
            + instrumentedMethod.getInternalName()
            + instrumentedMethod.getDescriptor();

    long id = registry.idFor(key);

    return new Advice.OffsetMapping.Target.ForStackManipulation(LongConstant.forValue(id));
  }
}
