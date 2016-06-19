/*
 * Copyright (c) 2006 Rogério Liesenfeld
 * This file is subject to the terms of the MIT license (see LICENSE.txt).
 */
package mockit.internal.expectations.injection;

import java.lang.annotation.*;
import java.lang.reflect.*;
import javax.annotation.*;
import javax.ejb.*;
import javax.inject.*;
import javax.persistence.*;
import javax.servlet.*;

import static mockit.internal.util.ClassLoad.*;
import static mockit.internal.util.MethodReflection.*;
import static mockit.internal.util.ParameterReflection.*;
import static mockit.internal.util.Utilities.*;

final class InjectionPoint
{
   enum KindOfInjectionPoint { NotAnnotated, Required, Optional, WithValue }

   @Nullable static final Class<? extends Annotation> INJECT_CLASS;
   @Nullable private static final Class<? extends Annotation> EJB_CLASS;
   @Nullable static final Class<? extends Annotation> PERSISTENCE_UNIT_CLASS;
   @Nullable static final Class<?> SERVLET_CLASS;
   @Nullable static final Class<?> CONVERSATION_CLASS;

   static
   {
      INJECT_CLASS = searchTypeInClasspath("javax.inject.Inject");
      EJB_CLASS = searchTypeInClasspath("javax.ejb.EJB");
      PERSISTENCE_UNIT_CLASS = searchTypeInClasspath("javax.persistence.PersistenceUnit");
      SERVLET_CLASS = searchTypeInClasspath("javax.servlet.Servlet");
      CONVERSATION_CLASS = searchTypeInClasspath("javax.enterprise.context.Conversation");
   }

   @Nonnull final Type type;
   @Nullable final String name;

   InjectionPoint(@Nonnull Type type) { this(type, null); }

   InjectionPoint(@Nonnull Type type, @Nullable String name)
   {
      this.type = type;
      this.name = name;
   }

   @Override @SuppressWarnings("EqualsWhichDoesntCheckParameterClass")
   public boolean equals(Object other)
   {
      if (this == other) return true;

      InjectionPoint otherIP = (InjectionPoint) other;

      if (type instanceof TypeVariable<?> || otherIP.type instanceof TypeVariable<?>) {
         return false;
      }

      String thisName = name;
      String otherName = otherIP.name;

      if (thisName != null && !thisName.equals(otherName)) {
         return false;
      }

      Class<?> thisClass = getClassType(type);
      Class<?> otherClass = getClassType(otherIP.type);

      return thisClass.isAssignableFrom(otherClass);
   }

   @Override
   public int hashCode() { return 31 * type.hashCode() + (name != null ? name.hashCode() : 0); }

   static boolean isServlet(@Nonnull Class<?> aClass)
   {
      return SERVLET_CLASS != null && Servlet.class.isAssignableFrom(aClass);
   }

   @Nonnull
   static Object wrapInProviderIfNeeded(@Nonnull Type type, @Nonnull final Object value)
   {
      if (
         INJECT_CLASS != null && type instanceof ParameterizedType && !(value instanceof Provider) &&
         ((ParameterizedType) type).getRawType() == Provider.class
      ) {
         return new Provider<Object>() { @Override public Object get() { return value; } };
      }

      return value;
   }

   @Nonnull
   static KindOfInjectionPoint isAnnotated(@Nonnull AccessibleObject fieldOrConstructor)
   {
      Annotation[] annotations = fieldOrConstructor.getDeclaredAnnotations();

      if (annotations.length == 0) {
         return KindOfInjectionPoint.NotAnnotated;
      }

      if (INJECT_CLASS != null && isAnnotated(annotations, Inject.class)) {
         return KindOfInjectionPoint.Required;
      }

      KindOfInjectionPoint kind = isAutowired(annotations);

      if (kind != KindOfInjectionPoint.NotAnnotated || fieldOrConstructor instanceof Constructor) {
         return kind;
      }

      if (hasValue(annotations)) {
         return KindOfInjectionPoint.WithValue;
      }

      if (isRequired(annotations)) {
         return KindOfInjectionPoint.Required;
      }

      return KindOfInjectionPoint.NotAnnotated;
   }

   private static boolean isAnnotated(
      @Nonnull Annotation[] declaredAnnotations, @Nonnull Class<? extends Annotation> annotationOfInterest)
   {
      Annotation annotation = getAnnotation(declaredAnnotations, annotationOfInterest);
      return annotation != null;
   }

   @Nullable
   private static <A extends Annotation> A getAnnotation(
      @Nonnull Annotation[] declaredAnnotations, @Nonnull Class<A> annotationOfInterest)
   {
      for (Annotation declaredAnnotation : declaredAnnotations) {
         if (declaredAnnotation.annotationType() == annotationOfInterest) {
            //noinspection unchecked
            return (A) declaredAnnotation;
         }
      }

      return null;
   }

   @Nonnull
   private static KindOfInjectionPoint isAutowired(@Nonnull Annotation[] declaredAnnotations)
   {
      for (Annotation declaredAnnotation : declaredAnnotations) {
         Class<? extends Annotation> annotationType = declaredAnnotation.annotationType();

         if (annotationType.getName().endsWith(".Autowired")) {
            Boolean required = invokePublicIfAvailable(annotationType, declaredAnnotation, "required", NO_PARAMETERS);
            return required != null && required ? KindOfInjectionPoint.Required : KindOfInjectionPoint.Optional;
         }
      }

      return KindOfInjectionPoint.NotAnnotated;
   }

   private static boolean hasValue(@Nonnull Annotation[] declaredAnnotations)
   {
      for (Annotation declaredAnnotation : declaredAnnotations) {
         Class<? extends Annotation> annotationType = declaredAnnotation.annotationType();

         if (annotationType.getName().endsWith(".Value")) {
            return true;
         }
      }

      return false;
   }

   private static boolean isRequired(@Nonnull Annotation[] annotations)
   {
      return
         isAnnotated(annotations, Resource.class) ||
         EJB_CLASS != null && isAnnotated(annotations, EJB.class) ||
         PERSISTENCE_UNIT_CLASS != null && (
            isAnnotated(annotations, PersistenceContext.class) || isAnnotated(annotations, PersistenceUnit.class)
         );
   }

   @Nullable
   static Object getValueFromAnnotation(@Nonnull Field field)
   {
      String value = null;

      for (Annotation declaredAnnotation : field.getDeclaredAnnotations()) {
         Class<? extends Annotation> annotationType = declaredAnnotation.annotationType();

         if (annotationType.getName().endsWith(".Value")) {
            value = invokePublicIfAvailable(annotationType, declaredAnnotation, "value", NO_PARAMETERS);
            break;
         }
      }

      Object convertedValue = convertFromString(field.getType(), value);
      return convertedValue;
   }

   @Nonnull
   static Type getTypeOfInjectionPointFromVarargsParameter(@Nonnull Type parameterType)
   {
      if (parameterType instanceof Class<?>) {
         return ((Class<?>) parameterType).getComponentType();
      }
      else {
         return ((GenericArrayType) parameterType).getGenericComponentType();
      }
   }

   @Nullable
   static String getQualifiedName(@Nonnull Annotation[] annotationsOnInjectionPoint)
   {
      for (Annotation annotation : annotationsOnInjectionPoint) {
         Class<? extends Annotation> annotationType = annotation.annotationType();
         String annotationName = annotationType.getName();

         if (annotationName.endsWith(".Named") || annotationName.endsWith(".Qualifier")) {
            String qualifiedName = invokePublicIfAvailable(annotationType, annotation, "value", NO_PARAMETERS);
            return qualifiedName;
         }
      }

      return null;
   }
}
