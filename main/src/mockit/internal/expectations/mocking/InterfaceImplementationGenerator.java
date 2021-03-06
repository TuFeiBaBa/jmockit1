/*
 * Copyright (c) 2006 JMockit developers
 * This file is subject to the terms of the MIT license (see LICENSE.txt).
 */
package mockit.internal.expectations.mocking;

import java.lang.reflect.Type;
import java.util.*;
import javax.annotation.*;

import static java.lang.reflect.Modifier.isStatic;

import mockit.asm.*;
import mockit.asm.ClassMetadataReader.*;
import mockit.internal.*;
import mockit.internal.classGeneration.*;
import mockit.internal.reflection.*;
import mockit.internal.reflection.GenericTypeReflection.*;
import static mockit.asm.Access.*;
import static mockit.asm.Opcodes.*;

final class InterfaceImplementationGenerator extends BaseClassModifier
{
   private static final int CLASS_ACCESS = PUBLIC + FINAL;
   private static final EnumSet<Attribute> SIGNATURE = EnumSet.of(Attribute.Signature);

   @Nonnull private final MockedTypeInfo mockedTypeInfo;
   @Nonnull private final String implementationClassDesc;
   @Nonnull private final List<String> implementedMethods;
   private String interfaceName;
   private String methodOwner;
   @Nullable private String[] initialSuperInterfaces;

   InterfaceImplementationGenerator(@Nonnull byte[] classfile, @Nonnull Type mockedType, @Nonnull String implementationClassName) {
      super(classfile);
      mockedTypeInfo = new MockedTypeInfo(mockedType);
      implementationClassDesc = implementationClassName.replace('.', '/');
      implementedMethods = new ArrayList<String>();
   }

   @Override
   public void visit(
      int version, int access, @Nonnull String name, @Nullable String signature, @Nullable String superName, @Nullable String[] interfaces
   ) {
      interfaceName = name;
      methodOwner = name;
      initialSuperInterfaces = interfaces;

      String classSignature = signature == null ? null : signature + mockedTypeInfo.implementationSignature;
      String[] implementedInterfaces = {name};
      super.visit(version, CLASS_ACCESS, implementationClassDesc, classSignature, superName, implementedInterfaces);

      generateNoArgsConstructor();
   }

   private void generateNoArgsConstructor() {
      mw = cw.visitMethod(PUBLIC, "<init>", "()V", null, null);
      mw.visitVarInsn(ALOAD, 0);
      mw.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
      generateEmptyImplementation();
   }

   @Override public AnnotationVisitor visitAnnotation(@Nonnull String desc) { return null; }
   @Override public void visitInnerClass(@Nonnull String name, String outerName, String innerName, int access) {}
   @Override public void visitOuterClass(@Nonnull String owner, @Nullable String name, @Nullable String desc) {}
   @Override public void visitSource(@Nullable String source) {}

   @Nullable @Override
   public FieldVisitor visitField(
      int access, @Nonnull String name, @Nonnull String desc, @Nullable String signature, @Nullable Object value) { return null; }

   @Nullable @Override
   public MethodVisitor visitMethod(
      int access, @Nonnull String name, @Nonnull String desc, @Nullable String signature, @Nullable String[] exceptions
   ) {
      generateMethodImplementation(access, name, desc, signature, exceptions);
      return null;
   }

   private void generateMethodImplementation(
      int access, @Nonnull String name, @Nonnull String desc, @Nullable String signature, @Nullable String[] exceptions
   ) {
      if (!isStatic(access)) {
         String methodNameAndDesc = name + desc;

         if (!implementedMethods.contains(methodNameAndDesc)) {
            generateMethodBody(access, name, desc, signature, exceptions);
            implementedMethods.add(methodNameAndDesc);
         }
      }
   }

   private void generateMethodBody(
      int access, @Nonnull String name, @Nonnull String desc, @Nullable String signature, @Nullable String[] exceptions
   ) {
      mw = cw.visitMethod(PUBLIC, name, desc, signature, exceptions);

      String className = null;

      if (signature != null) {
         String subInterfaceOverride = getSubInterfaceOverride(mockedTypeInfo.genericTypeMap, name, signature);

         if (subInterfaceOverride != null) {
            className = interfaceName;
            //noinspection AssignmentToMethodParameter
            desc = subInterfaceOverride.substring(name.length());
            //noinspection AssignmentToMethodParameter
            signature = null;
         }
      }

      if (className == null) {
         className = isOverrideOfMethodFromSuperInterface(name, desc) ? interfaceName : methodOwner;
      }

      generateDirectCallToHandler(className, access, name, desc, signature);
      generateReturnWithObjectAtTopOfTheStack(desc);
      mw.visitMaxStack(1);
   }

   @Nullable
   private String getSubInterfaceOverride(
      @Nonnull GenericTypeReflection genericTypeMap, @Nonnull String name, @Nonnull String genericSignature
   ) {
      if (!implementedMethods.isEmpty()) {
         GenericSignature parsedSignature = genericTypeMap.parseSignature(genericSignature);

         for (String implementedMethod : implementedMethods) {
            if (sameMethodName(implementedMethod, name) && parsedSignature.satisfiesSignature(implementedMethod)) {
               return implementedMethod;
            }
         }
      }

      return null;
   }

   private static boolean sameMethodName(@Nonnull String implementedMethod, @Nonnull String name) {
      return implementedMethod.startsWith(name) && implementedMethod.charAt(name.length()) == '(';
   }

   private boolean isOverrideOfMethodFromSuperInterface(@Nonnull String name, @Nonnull String desc) {
      if (!implementedMethods.isEmpty()) {
         int p = desc.lastIndexOf(')');
         String descNoReturnType = desc.substring(0, p + 1);

         for (String implementedMethod : implementedMethods) {
            if (sameMethodName(implementedMethod, name) && implementedMethod.contains(descNoReturnType)) {
               return true;
            }
         }
      }

      return false;
   }

   @Override
   public void visitEnd() {
      assert initialSuperInterfaces != null;

      for (String superInterface : initialSuperInterfaces) {
         generateImplementationsForInterfaceMethodsRecurringToSuperInterfaces(superInterface);
      }
   }

   private void generateImplementationsForInterfaceMethodsRecurringToSuperInterfaces(@Nonnull String anInterface) {
      methodOwner = anInterface;

      byte[] interfaceBytecode = ClassFile.getClassFile(anInterface);
      ClassMetadataReader cmr = new ClassMetadataReader(interfaceBytecode, SIGNATURE);
      String[] superInterfaces = cmr.getInterfaces();

      for (MethodInfo method : cmr.getMethods()) {
         generateMethodImplementation(method.accessFlags, method.name, method.desc, method.signature, null);
      }

      if (superInterfaces != null) {
         for (String superInterface : superInterfaces) {
            generateImplementationsForInterfaceMethodsRecurringToSuperInterfaces(superInterface);
         }
      }
   }
}
