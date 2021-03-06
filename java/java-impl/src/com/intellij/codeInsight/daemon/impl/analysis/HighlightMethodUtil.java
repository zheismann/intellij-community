/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.codeInsight.daemon.impl.analysis;

import com.intellij.codeInsight.ExceptionUtil;
import com.intellij.codeInsight.daemon.JavaErrorMessages;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightInfoType;
import com.intellij.codeInsight.daemon.impl.RefCountHolder;
import com.intellij.codeInsight.daemon.impl.quickfix.*;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.QuickFixFactory;
import com.intellij.codeInspection.LocalQuickFixOnPsiElementAsIntentionAdapter;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.projectRoots.JavaSdkVersion;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.*;
import com.intellij.psi.infos.CandidateInfo;
import com.intellij.psi.infos.MethodCandidateInfo;
import com.intellij.psi.util.*;
import com.intellij.refactoring.util.RefactoringChangeUtil;
import com.intellij.ui.ColorUtil;
import com.intellij.util.containers.MostlySingularMultiMap;
import com.intellij.util.ui.UIUtil;
import com.intellij.xml.util.XmlStringUtil;
import org.intellij.lang.annotations.Language;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Highlight method problems
 *
 * @author cdr
 * Date: Aug 14, 2002
 */
public class HighlightMethodUtil {
  private static final QuickFixFactory QUICK_FIX_FACTORY = QuickFixFactory.getInstance();

  private HighlightMethodUtil() { }

  public static String createClashMethodMessage(PsiMethod method1, PsiMethod method2, boolean showContainingClasses) {
    @NonNls String pattern = showContainingClasses ? "clash.methods.message.show.classes" : "clash.methods.message";
    return JavaErrorMessages.message(pattern,
                                     JavaHighlightUtil.formatMethod(method1),
                                     JavaHighlightUtil.formatMethod(method2),
                                     HighlightUtil.formatClass(method1.getContainingClass()),
                                     HighlightUtil.formatClass(method2.getContainingClass()));
  }

  static HighlightInfo checkMethodWeakerPrivileges(@NotNull MethodSignatureBackedByPsiMethod methodSignature,
                                                   @NotNull List<HierarchicalMethodSignature> superMethodSignatures,
                                                   boolean includeRealPositionInfo,
                                                   @NotNull PsiFile containingFile) {
    PsiMethod method = methodSignature.getMethod();
    PsiModifierList modifierList = method.getModifierList();
    if (modifierList.hasModifierProperty(PsiModifier.PUBLIC)) return null;
    int accessLevel = PsiUtil.getAccessLevel(modifierList);
    String accessModifier = PsiUtil.getAccessModifier(accessLevel);
    for (MethodSignatureBackedByPsiMethod superMethodSignature : superMethodSignatures) {
      PsiMethod superMethod = superMethodSignature.getMethod();
      if (!PsiUtil.isAccessible(containingFile.getProject(), superMethod, method, null)) continue;
      HighlightInfo info = isWeaker(method, modifierList, accessModifier, accessLevel, superMethod, includeRealPositionInfo);
      if (info != null) return info;
    }
    return null;
  }

  private static HighlightInfo isWeaker(final PsiMethod method, final PsiModifierList modifierList, final String accessModifier, final int accessLevel,
                                        final PsiMethod superMethod,
                                        final boolean includeRealPositionInfo) {
    int superAccessLevel = PsiUtil.getAccessLevel(superMethod.getModifierList());
    if (accessLevel < superAccessLevel) {
      String description = JavaErrorMessages.message("weaker.privileges",
                                            createClashMethodMessage(method, superMethod, true),
                                            accessModifier,
                                            PsiUtil.getAccessModifier(superAccessLevel));
      TextRange textRange;
      if (includeRealPositionInfo) {
        if (modifierList.hasModifierProperty(PsiModifier.PACKAGE_LOCAL)) {
          textRange = method.getNameIdentifier().getTextRange();
        }
        else {
          PsiElement keyword = PsiUtil.findModifierInList(modifierList, accessModifier);
          textRange = keyword.getTextRange();
        }
      }
      else {
        textRange = TextRange.EMPTY_RANGE;
      }
      HighlightInfo highlightInfo = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(textRange).descriptionAndTooltip(description).create();
      IntentionAction fix = QUICK_FIX_FACTORY.createModifierListFix(method, PsiUtil.getAccessModifier(superAccessLevel), true, false);
      QuickFixAction.registerQuickFixAction(highlightInfo, fix);
      return highlightInfo;
    }
    return null;
  }


  static HighlightInfo checkMethodIncompatibleReturnType(MethodSignatureBackedByPsiMethod methodSignature,
                                                                List<HierarchicalMethodSignature> superMethodSignatures,
                                                                boolean includeRealPositionInfo) {
    PsiMethod method = methodSignature.getMethod();
    PsiType returnType = methodSignature.getSubstitutor().substitute(method.getReturnType());
    PsiClass aClass = method.getContainingClass();
    if (aClass == null) return null;
    for (MethodSignatureBackedByPsiMethod superMethodSignature : superMethodSignatures) {
      PsiMethod superMethod = superMethodSignature.getMethod();
      PsiType declaredReturnType = superMethod.getReturnType();
      PsiType superReturnType = declaredReturnType;
      if (superMethodSignature.isRaw()) superReturnType = TypeConversionUtil.erasure(declaredReturnType);
      if (returnType == null || superReturnType == null || method == superMethod) continue;
      PsiClass superClass = superMethod.getContainingClass();
      if (superClass == null) continue;
      HighlightInfo highlightInfo = checkSuperMethodSignature(superMethod, superMethodSignature, superReturnType, method, methodSignature,
                                                              returnType, includeRealPositionInfo, JavaErrorMessages.message("incompatible.return.type"), method);
      if (highlightInfo != null) return highlightInfo;
    }

    return null;
  }

  private static HighlightInfo checkSuperMethodSignature(PsiMethod superMethod,
                                                         MethodSignatureBackedByPsiMethod superMethodSignature,
                                                         PsiType superReturnType,
                                                         PsiMethod method,
                                                         MethodSignatureBackedByPsiMethod methodSignature,
                                                         PsiType returnType,
                                                         boolean includeRealPositionInfo,
                                                         String detailMessage,
                                                         PsiMethod methodToHighlight) {
    if (superReturnType == null) return null;
    if ("clone".equals(method.getName())) {
      final PsiClass containingClass = method.getContainingClass();
      final PsiClass superContainingClass = superMethod.getContainingClass();
      if (containingClass != null && superContainingClass != null && containingClass.isInterface() && !superContainingClass.isInterface()) {
        return null;
      }
    }

    PsiType substitutedSuperReturnType;
    final boolean isJdk15 = PsiUtil.isLanguageLevel5OrHigher(method);
    if (isJdk15 && !superMethodSignature.isRaw() && superMethodSignature.equals(methodSignature)) { //see 8.4.5
      PsiSubstitutor unifyingSubstitutor = MethodSignatureUtil.getSuperMethodSignatureSubstitutor(methodSignature,
                                                                                                  superMethodSignature);
      substitutedSuperReturnType = unifyingSubstitutor == null
                                   ? superReturnType
                                   : unifyingSubstitutor.substitute(superReturnType);
    }
    else {
      substitutedSuperReturnType = TypeConversionUtil.erasure(superMethodSignature.getSubstitutor().substitute(superReturnType));
    }

    if (returnType.equals(substitutedSuperReturnType)) return null;
    if (!(returnType instanceof PsiPrimitiveType) && substitutedSuperReturnType.getDeepComponentType() instanceof PsiClassType) {
      if (isJdk15 && TypeConversionUtil.isAssignable(substitutedSuperReturnType, returnType)) {
        return null;
      }
    }

    return createIncompatibleReturnTypeMessage(methodToHighlight, method, superMethod, includeRealPositionInfo,
                                               substitutedSuperReturnType, returnType, detailMessage);
  }

  private static HighlightInfo createIncompatibleReturnTypeMessage(PsiMethod methodToHighlight,
                                                                   PsiMethod method,
                                                                   PsiMethod superMethod,
                                                                   boolean includeRealPositionInfo,
                                                                   PsiType substitutedSuperReturnType,
                                                                   PsiType returnType,
                                                                   String detailMessage) {
    String description = MessageFormat.format("{0}; {1}", createClashMethodMessage(method, superMethod, true), detailMessage);
    TextRange textRange = includeRealPositionInfo ? methodToHighlight.getReturnTypeElement().getTextRange() : TextRange.EMPTY_RANGE;
    HighlightInfo errorResult = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(textRange).descriptionAndTooltip(description).create();
    IntentionAction fix = QUICK_FIX_FACTORY.createMethodReturnFix(method, substitutedSuperReturnType, false);
    QuickFixAction.registerQuickFixAction(errorResult, fix);
    QuickFixAction.registerQuickFixAction(errorResult, new SuperMethodReturnFix(superMethod, returnType));

    return errorResult;
  }


  static HighlightInfo checkMethodOverridesFinal(MethodSignatureBackedByPsiMethod methodSignature,
                                                 List<HierarchicalMethodSignature> superMethodSignatures) {
    PsiMethod method = methodSignature.getMethod();
    for (MethodSignatureBackedByPsiMethod superMethodSignature : superMethodSignatures) {
      PsiMethod superMethod = superMethodSignature.getMethod();
      HighlightInfo info = checkSuperMethodIsFinal(method, superMethod);
      if (info != null) return info;
    }
    return null;
  }

  private static HighlightInfo checkSuperMethodIsFinal(PsiMethod method, PsiMethod superMethod) {
    // strange things happen when super method is from Object and method from interface
    if (superMethod.hasModifierProperty(PsiModifier.FINAL)) {
      String description = JavaErrorMessages.message("final.method.override",
                                                 JavaHighlightUtil.formatMethod(method),
                                                 JavaHighlightUtil.formatMethod(superMethod),
                                                 HighlightUtil.formatClass(superMethod.getContainingClass()));
      TextRange textRange = HighlightNamesUtil.getMethodDeclarationTextRange(method);
      HighlightInfo errorResult = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(textRange).descriptionAndTooltip(description).create();
      IntentionAction fix = QUICK_FIX_FACTORY.createModifierListFix(superMethod, PsiModifier.FINAL, false, true);
      QuickFixAction.registerQuickFixAction(errorResult, fix);
      return errorResult;
    }
    return null;
  }

  static HighlightInfo checkMethodIncompatibleThrows(MethodSignatureBackedByPsiMethod methodSignature,
                                                            List<HierarchicalMethodSignature> superMethodSignatures,
                                                            boolean includeRealPositionInfo, PsiClass analyzedClass) {
    PsiMethod method = methodSignature.getMethod();
    PsiClass aClass = method.getContainingClass();
    if (aClass == null) return null;
    PsiSubstitutor superSubstitutor = TypeConversionUtil.getSuperClassSubstitutor(aClass, analyzedClass, PsiSubstitutor.EMPTY);
    PsiClassType[] exceptions = method.getThrowsList().getReferencedTypes();
    PsiJavaCodeReferenceElement[] referenceElements;
    List<PsiElement> exceptionContexts;
    if (includeRealPositionInfo) {
      exceptionContexts = new ArrayList<PsiElement>();
      referenceElements = method.getThrowsList().getReferenceElements();
    }
    else {
      exceptionContexts = null;
      referenceElements = null;
    }
    List<PsiClassType> checkedExceptions = new ArrayList<PsiClassType>();
    for (int i = 0; i < exceptions.length; i++) {
      PsiClassType exception = exceptions[i];
      if (!ExceptionUtil.isUncheckedException(exception)) {
        checkedExceptions.add(exception);
        if (includeRealPositionInfo && i < referenceElements.length) {
          PsiJavaCodeReferenceElement exceptionRef = referenceElements[i];
          exceptionContexts.add(exceptionRef);
        }
      }
    }
    for (MethodSignatureBackedByPsiMethod superMethodSignature : superMethodSignatures) {
      PsiMethod superMethod = superMethodSignature.getMethod();
      int index = getExtraExceptionNum(methodSignature, superMethodSignature, checkedExceptions, superSubstitutor);
      if (index != -1) {
        if (aClass.isInterface()) {
          final PsiClass superContainingClass = superMethod.getContainingClass();
          if (superContainingClass != null && !superContainingClass.isInterface()) continue;
          if (superContainingClass != null && !aClass.isInheritor(superContainingClass, true)) continue;
        }
        PsiClassType exception = checkedExceptions.get(index);
        String description = JavaErrorMessages.message("overridden.method.does.not.throw",
                                                   createClashMethodMessage(method, superMethod, true),
                                                   JavaHighlightUtil.formatType(exception));
        TextRange textRange;
        if (includeRealPositionInfo) {
          PsiElement exceptionContext = exceptionContexts.get(index);
          textRange = exceptionContext.getTextRange();
        }
        else {
          textRange = TextRange.EMPTY_RANGE;
        }
        HighlightInfo errorResult = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(textRange).descriptionAndTooltip(description).create();
        QuickFixAction.registerQuickFixAction(errorResult, new LocalQuickFixOnPsiElementAsIntentionAdapter(QUICK_FIX_FACTORY.createMethodThrowsFix(method, exception, false, false)));
        QuickFixAction.registerQuickFixAction(errorResult, new LocalQuickFixOnPsiElementAsIntentionAdapter(QUICK_FIX_FACTORY.createMethodThrowsFix(superMethod, exception, true, true)));
        return errorResult;
      }
    }
    return null;
  }

  // return number of exception  which was not declared in super method or -1
  private static int getExtraExceptionNum(final MethodSignature methodSignature,
                                          final MethodSignatureBackedByPsiMethod superSignature,
                                          List<PsiClassType> checkedExceptions, PsiSubstitutor substitutorForDerivedClass) {
    PsiMethod superMethod = superSignature.getMethod();
    PsiSubstitutor substitutorForMethod = MethodSignatureUtil.getSuperMethodSignatureSubstitutor(methodSignature, superSignature);
    if (substitutorForMethod == null) return -1;
    for (int i = 0; i < checkedExceptions.size(); i++) {
      PsiType exception = substitutorForDerivedClass.substitute(substitutorForMethod.substitute(checkedExceptions.get(i)));
      if (!isMethodThrows(superMethod, substitutorForMethod, exception, substitutorForDerivedClass)) {
        return i;
      }
    }
    return -1;
  }

  private static boolean isMethodThrows(PsiMethod method, PsiSubstitutor substitutorForMethod, PsiType exception, PsiSubstitutor substitutorForDerivedClass) {
    PsiClassType[] thrownExceptions = method.getThrowsList().getReferencedTypes();
    for (PsiClassType thrownException1 : thrownExceptions) {
      PsiType thrownException = substitutorForMethod.substitute(thrownException1);
      thrownException = substitutorForDerivedClass.substitute(thrownException);
      if (TypeConversionUtil.isAssignable(thrownException, exception)) return true;
    }
    return false;
  }

  @Nullable
  static HighlightInfo checkMethodCall(@NotNull PsiMethodCallExpression methodCall,
                                       @NotNull PsiResolveHelper resolveHelper,
                                       @NotNull LanguageLevel languageLevel,
                                       @NotNull JavaSdkVersion javaSdkVersion) {
    PsiExpressionList list = methodCall.getArgumentList();
    PsiReferenceExpression referenceToMethod = methodCall.getMethodExpression();
    JavaResolveResult[] results = referenceToMethod.multiResolve(true);
    JavaResolveResult resolveResult = results.length == 1 ? results[0] : JavaResolveResult.EMPTY;
    PsiElement resolved = resolveResult.getElement();

    boolean isDummy = isDummyConstructorCall(methodCall, resolveHelper, list, referenceToMethod);
    if (isDummy) return null;
    HighlightInfo highlightInfo;

    PsiSubstitutor substitutor = resolveResult.getSubstitutor();
    if (resolved instanceof PsiMethod && resolveResult.isValidResult()) {
      TextRange fixRange = getFixRange(methodCall);
      highlightInfo = HighlightUtil.checkUnhandledExceptions(methodCall, fixRange);
      if (highlightInfo == null && !LambdaUtil.isValidQualifier4InterfaceStaticMethodCall((PsiMethod)resolved, methodCall.getMethodExpression(), languageLevel)) {
        highlightInfo = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).descriptionAndTooltip("Static method may be invoked on containing interface class only").range(fixRange).create();
      }
    }
    else {
      PsiMethod resolvedMethod = null;
      MethodCandidateInfo candidateInfo = null;
      if (resolveResult instanceof MethodCandidateInfo) {
        candidateInfo = (MethodCandidateInfo)resolveResult;
        resolvedMethod = candidateInfo.getElement();
      }

      if (!resolveResult.isAccessible() || !resolveResult.isStaticsScopeCorrect()) {
        //highlightInfo = checkAmbiguousMethodCall(referenceToMethod, results, list, element, resolveResult, methodCall, resolveHelper);
        highlightInfo = null;
      }
      else if (candidateInfo != null && !candidateInfo.isApplicable()) {
        if (candidateInfo.isTypeArgumentsApplicable()) {
          String methodName = HighlightMessageUtil.getSymbolName(resolved, substitutor);
          PsiElement parent = resolved.getParent();
          String containerName = parent == null ? "" : HighlightMessageUtil.getSymbolName(parent, substitutor);
          String argTypes = buildArgTypesList(list);
          String description = JavaErrorMessages.message("wrong.method.arguments", methodName, containerName, argTypes);
          String toolTip = parent instanceof PsiClass && !ApplicationManager.getApplication().isUnitTestMode() ?
                           createMismatchedArgumentsHtmlTooltip(candidateInfo, list) : description;
          highlightInfo = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(list).description(description).escapedToolTip(toolTip).navigationShift(+1).create();
          if (highlightInfo != null) {
            registerMethodCallIntentions(highlightInfo, methodCall, list, resolveHelper);
          }
        }
        else {
          PsiReferenceExpression methodExpression = methodCall.getMethodExpression();
          PsiReferenceParameterList typeArgumentList = methodCall.getTypeArgumentList();
          if (typeArgumentList.getTypeArguments().length == 0 && resolvedMethod != null && resolvedMethod.hasTypeParameters()) {
            highlightInfo = GenericsHighlightUtil.checkInferredTypeArguments(resolvedMethod, methodCall, substitutor);
          }
          else {
            highlightInfo = GenericsHighlightUtil.checkParameterizedReferenceTypeArguments(resolved, methodExpression, substitutor, javaSdkVersion);
          }
        }
      }
      else {
        String description = JavaErrorMessages.message("method.call.expected");
        highlightInfo =
          HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(methodCall).descriptionAndTooltip(description).create();
        if (resolved instanceof PsiClass) {
          QuickFixAction.registerQuickFixAction(highlightInfo, new InsertNewFix(methodCall, (PsiClass)resolved));
        }
        else {
          TextRange range = getFixRange(methodCall);
          QuickFixAction.registerQuickFixAction(highlightInfo, range, new CreateMethodFromUsageFix(methodCall));
          QuickFixAction.registerQuickFixAction(highlightInfo, range, new CreateAbstractMethodFromUsageFix(methodCall));
          QuickFixAction.registerQuickFixAction(highlightInfo, range, new CreatePropertyFromUsageFix(methodCall));
        }
      }
    }
    if (highlightInfo == null) {
      highlightInfo = GenericsHighlightUtil.checkParameterizedReferenceTypeArguments(resolved, referenceToMethod, substitutor,
                                                                                     javaSdkVersion);
    }
    return highlightInfo;
  }

  static boolean isDummyConstructorCall(PsiMethodCallExpression methodCall,
                                        PsiResolveHelper resolveHelper,
                                        PsiExpressionList list,
                                        PsiReferenceExpression referenceToMethod) {
    boolean isDummy = false;
    boolean isThisOrSuper = referenceToMethod.getReferenceNameElement() instanceof PsiKeyword;
    if (isThisOrSuper) {
      // super(..) or this(..)
      if (list.getExpressions().length == 0) { // implicit ctr call
        CandidateInfo[] candidates = resolveHelper.getReferencedMethodCandidates(methodCall, true);
        if (candidates.length == 1 && !candidates[0].getElement().isPhysical()) {
          isDummy = true;// dummy constructor
        }
      }
    }
    return isDummy;
  }

  @Nullable
  static HighlightInfo checkAmbiguousMethodCall(final PsiReferenceExpression referenceToMethod,
                                                        JavaResolveResult[] resolveResults,
                                                        @NotNull PsiExpressionList list,
                                                        final PsiElement element,
                                                        final JavaResolveResult resolveResult,
                                                        final PsiMethodCallExpression methodCall, final PsiResolveHelper resolveHelper) {
    MethodCandidateInfo methodCandidate1 = null;
    MethodCandidateInfo methodCandidate2 = null;
    for (JavaResolveResult result : resolveResults) {
      if (!(result instanceof MethodCandidateInfo)) continue;
      MethodCandidateInfo candidate = (MethodCandidateInfo)result;
      if (candidate.isApplicable() && !candidate.getElement().isConstructor()) {
        if (methodCandidate1 == null) {
          methodCandidate1 = candidate;
        }
        else {
          methodCandidate2 = candidate;
          break;
        }
      }
    }
    MethodCandidateInfo[] candidates = toMethodCandidates(resolveResults);

    String description;
    String toolTip;
    PsiElement elementToHighlight;
    HighlightInfoType highlightInfoType = HighlightInfoType.ERROR;
    if (methodCandidate2 != null) {
      String m1 = PsiFormatUtil.formatMethod(methodCandidate1.getElement(),
                                             methodCandidate1.getSubstitutor(),
                                             PsiFormatUtilBase.SHOW_CONTAINING_CLASS | PsiFormatUtilBase.SHOW_NAME |
                                             PsiFormatUtilBase.SHOW_PARAMETERS,
                                             PsiFormatUtilBase.SHOW_TYPE);
      String m2 = PsiFormatUtil.formatMethod(methodCandidate2.getElement(),
                                             methodCandidate2.getSubstitutor(),
                                             PsiFormatUtilBase.SHOW_CONTAINING_CLASS | PsiFormatUtilBase.SHOW_NAME |
                                             PsiFormatUtilBase.SHOW_PARAMETERS,
                                             PsiFormatUtilBase.SHOW_TYPE);
      description = JavaErrorMessages.message("ambiguous.method.call", m1, m2);
      toolTip = createAmbiguousMethodHtmlTooltip(new MethodCandidateInfo[]{methodCandidate1, methodCandidate2});
      elementToHighlight = list;
    }
    else {
      if (element != null && !resolveResult.isAccessible()) {
        description = HighlightUtil.buildProblemWithAccessDescription(referenceToMethod, resolveResult);
        elementToHighlight = referenceToMethod.getReferenceNameElement();
      }
      else if (element != null && !resolveResult.isStaticsScopeCorrect()) {
        description = HighlightUtil.buildProblemWithStaticDescription(element);
        elementToHighlight = referenceToMethod.getReferenceNameElement();
      }
      else {
        String methodName = referenceToMethod.getReferenceName() + buildArgTypesList(list);
        description = JavaErrorMessages.message("cannot.resolve.method", methodName);
        if (candidates.length == 0) {
          elementToHighlight = referenceToMethod.getReferenceNameElement();
          highlightInfoType = HighlightInfoType.WRONG_REF;
        }
        else {
          elementToHighlight = list;
        }
      }
      toolTip = XmlStringUtil.escapeString(description);
    }
    HighlightInfo info =
      HighlightInfo.newHighlightInfo(highlightInfoType).range(elementToHighlight).description(description).escapedToolTip(toolTip).create();
    if (methodCandidate2 == null) {
      registerMethodCallIntentions(info, methodCall, list, resolveHelper);
    }
    if (!resolveResult.isAccessible() && resolveResult.isStaticsScopeCorrect() && methodCandidate2 != null) {
      HighlightUtil.registerAccessQuickFixAction((PsiMember)element, referenceToMethod, info, resolveResult.getCurrentFileResolveScope());
    }
    if (element != null && !resolveResult.isStaticsScopeCorrect()) {
      HighlightUtil.registerStaticProblemQuickFixAction(element, info, referenceToMethod);
    }

    TextRange fixRange = getFixRange(elementToHighlight);
    CastMethodArgumentFix.REGISTRAR.registerCastActions(candidates, methodCall, info, fixRange);
    PermuteArgumentsFix.registerFix(info, methodCall, candidates, fixRange);
    WrapExpressionFix.registerWrapAction(candidates, list.getExpressions(), info);
    ChangeParameterClassFix.registerQuickFixActions(methodCall, list, info);
    return info;
  }

  private static MethodCandidateInfo[] toMethodCandidates(JavaResolveResult[] resolveResults) {
    List<MethodCandidateInfo> candidateList = new ArrayList<MethodCandidateInfo>();

    for (JavaResolveResult result : resolveResults) {
      if (!(result instanceof MethodCandidateInfo)) continue;
      MethodCandidateInfo candidate = (MethodCandidateInfo)result;
      if (candidate.isAccessible()) candidateList.add(candidate);
    }
    return candidateList.toArray(new MethodCandidateInfo[candidateList.size()]);
  }

  private static void registerMethodCallIntentions(@Nullable HighlightInfo highlightInfo,
                                                   PsiMethodCallExpression methodCall,
                                                   PsiExpressionList list,
                                                   PsiResolveHelper resolveHelper) {
    TextRange fixRange = getFixRange(methodCall);
    QuickFixAction.registerQuickFixAction(highlightInfo, fixRange, new CreateMethodFromUsageFix(methodCall));
    QuickFixAction.registerQuickFixAction(highlightInfo, fixRange, new CreateAbstractMethodFromUsageFix(methodCall));
    QuickFixAction.registerQuickFixAction(highlightInfo, fixRange, new CreateConstructorFromSuperFix(methodCall));
    QuickFixAction.registerQuickFixAction(highlightInfo, fixRange, new CreateConstructorFromThisFix(methodCall));
    QuickFixAction.registerQuickFixAction(highlightInfo, fixRange, new CreatePropertyFromUsageFix(methodCall));
    QuickFixAction.registerQuickFixAction(highlightInfo, fixRange, new CreateGetterSetterPropertyFromUsageFix(methodCall));
    CandidateInfo[] methodCandidates = resolveHelper.getReferencedMethodCandidates(methodCall, false);
    CastMethodArgumentFix.REGISTRAR.registerCastActions(methodCandidates, methodCall, highlightInfo, fixRange);
    PermuteArgumentsFix.registerFix(highlightInfo, methodCall, methodCandidates, fixRange);
    AddTypeArgumentsFix.REGISTRAR.registerCastActions(methodCandidates, methodCall, highlightInfo, fixRange);
    registerMethodAccessLevelIntentions(methodCandidates, methodCall, list, highlightInfo);
    ChangeMethodSignatureFromUsageFix.registerIntentions(methodCandidates, list, highlightInfo, fixRange);
    RemoveRedundantArgumentsFix.registerIntentions(methodCandidates, list, highlightInfo, fixRange);
    ConvertDoubleToFloatFix.registerIntentions(methodCandidates, list, highlightInfo, fixRange);
    WrapExpressionFix.registerWrapAction(methodCandidates, list.getExpressions(), highlightInfo);
    ChangeParameterClassFix.registerQuickFixActions(methodCall, list, highlightInfo);
    if (methodCandidates.length == 0) {
      QuickFixAction.registerQuickFixAction(highlightInfo, fixRange, new StaticImportMethodFix(methodCall));
    }
    VariableTypeFromCallFix.registerQuickFixActions(methodCall, list, highlightInfo);
    QuickFixAction.registerQuickFixAction(highlightInfo, fixRange, new ReplaceAddAllArrayToCollectionFix(methodCall));
    QuickFixAction.registerQuickFixAction(highlightInfo, fixRange, new SurroundWithArrayFix(methodCall));
    QualifyThisArgumentFix.registerQuickFixAction(methodCandidates, methodCall, highlightInfo, fixRange);

    CandidateInfo[] candidates = resolveHelper.getReferencedMethodCandidates(methodCall, true);
    ChangeStringLiteralToCharInMethodCallFix.registerFixes(candidates, methodCall, highlightInfo);
  }

  private static void registerMethodAccessLevelIntentions(CandidateInfo[] methodCandidates,
                                                          PsiMethodCallExpression methodCall,
                                                          PsiExpressionList exprList,
                                                          HighlightInfo highlightInfo) {
    for (CandidateInfo methodCandidate : methodCandidates) {
      PsiMethod method = (PsiMethod)methodCandidate.getElement();
      if (!methodCandidate.isAccessible() && PsiUtil.isApplicable(method, methodCandidate.getSubstitutor(), exprList)) {
        HighlightUtil.registerAccessQuickFixAction(method, methodCall.getMethodExpression(), highlightInfo, methodCandidate.getCurrentFileResolveScope());
      }
    }
  }

  @NotNull
  private static String createAmbiguousMethodHtmlTooltip(MethodCandidateInfo[] methodCandidates) {
    return JavaErrorMessages.message("ambiguous.method.html.tooltip",
                                     Integer.valueOf(methodCandidates[0].getElement().getParameterList().getParametersCount() + 2),
                                     createAmbiguousMethodHtmlTooltipMethodRow(methodCandidates[0]),
                                     getContainingClassName(methodCandidates[0]),
                                     createAmbiguousMethodHtmlTooltipMethodRow(methodCandidates[1]),
                                     getContainingClassName(methodCandidates[1]));
  }

  private static String getContainingClassName(final MethodCandidateInfo methodCandidate) {
    PsiMethod method = methodCandidate.getElement();
    PsiClass containingClass = method.getContainingClass();
    return containingClass == null ? method.getContainingFile().getName() : HighlightUtil.formatClass(containingClass, false);
  }

  @Language("HTML")
  private static String createAmbiguousMethodHtmlTooltipMethodRow(final MethodCandidateInfo methodCandidate) {
    PsiMethod method = methodCandidate.getElement();
    PsiParameter[] parameters = method.getParameterList().getParameters();
    PsiSubstitutor substitutor = methodCandidate.getSubstitutor();
    @NonNls @Language("HTML") String ms = "<td><b>" + method.getName() + "</b></td>";

    for (int j = 0; j < parameters.length; j++) {
      PsiParameter parameter = parameters[j];
      PsiType type = substitutor.substitute(parameter.getType());
      ms += "<td><b>" + (j == 0 ? "(" : "") +
            XmlStringUtil.escapeString(type.getPresentableText())
            + (j == parameters.length - 1 ? ")" : ",") + "</b></td>";
    }
    if (parameters.length == 0) {
      ms += "<td><b>()</b></td>";
    }
    return ms;
  }

  private static String createMismatchedArgumentsHtmlTooltip(MethodCandidateInfo info, PsiExpressionList list) {
    PsiMethod method = info.getElement();
    PsiSubstitutor substitutor = info.getSubstitutor();
    PsiClass aClass = method.getContainingClass();
    PsiParameter[] parameters = method.getParameterList().getParameters();
    String methodName = method.getName();
    return createMismatchedArgumentsHtmlTooltip(list, parameters, methodName, substitutor, aClass);
  }

  private static String createShortMismatchedArgumentsHtmlTooltip(PsiExpressionList list,
                                                             PsiParameter[] parameters,
                                                             String methodName,
                                                             PsiSubstitutor substitutor,
                                                             PsiClass aClass) {
    PsiExpression[] expressions = list.getExpressions();
    int cols = Math.max(parameters.length, expressions.length);

    @Language("HTML")
    @NonNls String parensizedName = methodName + (parameters.length == 0 ? "(&nbsp;)&nbsp;" : "");
    return JavaErrorMessages.message(
      "argument.mismatch.html.tooltip",
      Integer.valueOf(cols - parameters.length + 1), parensizedName,
      HighlightUtil.formatClass(aClass, false),
      createMismatchedArgsHtmlTooltipParamsRow(parameters, substitutor, expressions),
      createMismatchedArgsHtmlTooltipArgumentsRow(expressions, parameters, substitutor, cols)
    );
  }

  private static String esctrim(@NotNull String s) {
    return XmlStringUtil.escapeString(trimNicely(s));
  }

  private static String trimNicely(String s) {
    if (s.length() <= 40) return s;

    List<TextRange> wordIndices = StringUtil.getWordIndicesIn(s);
    if (wordIndices.size() > 2) {
      int firstWordEnd = wordIndices.get(0).getEndOffset();

      // try firstWord...remainder
      for (int i = 1; i<wordIndices.size();i++) {
        int stringLength = firstWordEnd + s.length() - wordIndices.get(i).getStartOffset();
        if (stringLength <= 40) {
          return s.substring(0, firstWordEnd) + "..." + s.substring(wordIndices.get(i).getStartOffset());
        }
      }
    }
    // maybe one last word will fit?
    if (!wordIndices.isEmpty() && s.length() - wordIndices.get(wordIndices.size()-1).getStartOffset() <= 40) {
      return "..." + s.substring(wordIndices.get(wordIndices.size()-1).getStartOffset());
    }

    return StringUtil.last(s, 40, true).toString();
  }

  private static String createMismatchedArgumentsHtmlTooltip(PsiExpressionList list,
                                                             PsiParameter[] parameters,
                                                             String methodName,
                                                             PsiSubstitutor substitutor,
                                                             PsiClass aClass) {
    return Math.max(parameters.length, list.getExpressions().length) <= 2
           ? createShortMismatchedArgumentsHtmlTooltip(list, parameters, methodName, substitutor, aClass)
           : createLongMismatchedArgumentsHtmlTooltip(list, parameters, methodName, substitutor, aClass);
  }

  @Language("HTML")
  private static String createLongMismatchedArgumentsHtmlTooltip(PsiExpressionList list,
                                                             PsiParameter[] parameters,
                                                             String methodName,
                                                             PsiSubstitutor substitutor,
                                                             PsiClass aClass) {
    PsiExpression[] expressions = list.getExpressions();

    @NonNls
    String s = "<html><body><table border=0>" +
               "<tr><td colspan=3>" +
               "<nobr><b>" + methodName + "()</b> in <b>" + HighlightUtil.formatClass(aClass, false) +"</b> cannot be applied to:</nobr>" +
               "</td></tr>"+
               "<tr><td colspan=2 align=left>Expected<br>Parameters:</td><td align=left>Actual<br>Arguments:</td></tr>"+
               "<tr><td colspan=3><hr></td></tr>"
      ;

    for (int i = 0; i < Math.max(parameters.length,expressions.length); i++) {
      PsiParameter parameter = i < parameters.length ? parameters[i] : null;
      PsiExpression expression = i < expressions.length ? expressions[i] : null;
      boolean showShort = showShortType(i, parameters, expressions, substitutor);
      @NonNls String mismatchColor = showShort ? null : UIUtil.isUnderDarcula() ? "FF6B68" : "red";

      s += "<tr" + (i % 2 == 0 ? " style='background-color: #"
                                 + (UIUtil.isUnderDarcula() ? ColorUtil.toHex(ColorUtil.shift(UIUtil.getToolTipBackground(), 1.1)) : "eeeeee")
                                 + "'" : "") + ">";
      s += "<td><b><nobr>";
      if (parameter != null) {
        String name = parameter.getName();
        if (name != null) {
          s += esctrim(name) +":";
        }
      }
      s += "</nobr></b></td>";

      s += "<td><b><nobr>";
      if (parameter != null) {
        PsiType type = substitutor.substitute(parameter.getType());
        s +=  "<font " + (mismatchColor == null ? "" : "color=" + mismatchColor) + ">" +
              esctrim(showShort ? type.getPresentableText() : JavaHighlightUtil.formatType(type))
              + "</font>"
              ;
      }
      s += "</nobr></b></td>";

      s += "<td><b><nobr>";
      if (expression != null) {
        PsiType type = expression.getType();
        s += "<font " + (mismatchColor == null ? "" : "color='" + mismatchColor + "'") + ">" +
               esctrim(expression.getText()) + "&nbsp;&nbsp;"+
              (mismatchColor == null || type == null || type == PsiType.NULL ? "" : "("+esctrim(JavaHighlightUtil.formatType(type))+")")
              + "</font>"
              ;

      }
      s += "</nobr></b></td>";

      s += "</tr>";
    }

    s+= "</table></body></html>";
    return s;
  }

  @Language("HTML")
  private static String createMismatchedArgsHtmlTooltipArgumentsRow(final PsiExpression[] expressions, final PsiParameter[] parameters,
                                                                      final PsiSubstitutor substitutor, final int cols) {
    @Language("HTML")

    @NonNls String ms = "";
    for (int i = 0; i < expressions.length; i++) {
      PsiExpression expression = expressions[i];
      PsiType type = expression.getType();

      boolean showShort = showShortType(i, parameters, expressions, substitutor);
      @NonNls String mismatchColor = showShort ? null : UIUtil.isUnderDarcula() ? "ff6464" : "red";
      ms += "<td> " + "<b><nobr>" + (i == 0 ? "(" : "")
            + "<font " + (showShort ? "" : "color=" + mismatchColor) + ">" +
            XmlStringUtil.escapeString(showShort ? type.getPresentableText() : JavaHighlightUtil.formatType(type))
            + "</font>"
            + (i == expressions.length - 1 ? ")" : ",") + "</nobr></b></td>";
    }
    for (int i = expressions.length; i < cols + 1; i++) {
      ms += "<td>" + (i == 0 ? "<b>()</b>" : "") +
            "&nbsp;</td>";
    }
    return ms;
  }

  @Language("HTML")
  private static String createMismatchedArgsHtmlTooltipParamsRow(final PsiParameter[] parameters,
                                                                 final PsiSubstitutor substitutor,
                                                                 final PsiExpression[] expressions) {
    @NonNls String ms = "";
    for (int i = 0; i < parameters.length; i++) {
      PsiParameter parameter = parameters[i];
      PsiType type = substitutor.substitute(parameter.getType());
      ms += "<td><b><nobr>" + (i == 0 ? "(" : "") +
            XmlStringUtil.escapeString(showShortType(i, parameters, expressions, substitutor)
                                 ? type.getPresentableText()
                                 : JavaHighlightUtil.formatType(type))
            + (i == parameters.length - 1 ? ")" : ",") + "</nobr></b></td>";
    }
    return ms;
  }

  private static boolean showShortType(int i,
                                       PsiParameter[] parameters,
                                       PsiExpression[] expressions,
                                       PsiSubstitutor substitutor) {
    PsiExpression expression = i < expressions.length ? expressions[i] : null;
    if (expression == null) return true;
    PsiType paramType = i < parameters.length && parameters[i] != null
                        ? substitutor.substitute(parameters[i].getType())
                        : null;
    return paramType != null && TypeConversionUtil.areTypesAssignmentCompatible(paramType, expression);
  }


  static HighlightInfo checkMethodMustHaveBody(PsiMethod method, PsiClass aClass) {
    HighlightInfo errorResult = null;
    if (method.getBody() == null
        && !method.hasModifierProperty(PsiModifier.ABSTRACT)
        && !method.hasModifierProperty(PsiModifier.NATIVE)
        && aClass != null
        && !aClass.isInterface()
        && !PsiUtilCore.hasErrorElementChild(method)) {
      int start = method.getModifierList().getTextRange().getStartOffset();
      int end = method.getTextRange().getEndOffset();

      String description = JavaErrorMessages.message("missing.method.body");
      errorResult = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(start, end).descriptionAndTooltip(description).create();
      if (HighlightUtil.getIncompatibleModifier(PsiModifier.ABSTRACT, method.getModifierList()) == null) {
        IntentionAction fix = QUICK_FIX_FACTORY.createModifierListFix(method, PsiModifier.ABSTRACT, true, false);
        QuickFixAction.registerQuickFixAction(errorResult, fix);
      }
      QuickFixAction.registerQuickFixAction(errorResult, new AddMethodBodyFix(method));
    }
    return errorResult;
  }


  static HighlightInfo checkAbstractMethodInConcreteClass(PsiMethod method, PsiElement elementToHighlight) {
    HighlightInfo errorResult = null;
    PsiClass aClass = method.getContainingClass();
    if (method.hasModifierProperty(PsiModifier.ABSTRACT)
        && aClass != null
        && !aClass.hasModifierProperty(PsiModifier.ABSTRACT)
        && !aClass.isEnum()
        && !PsiUtilCore.hasErrorElementChild(method)) {
      String description = JavaErrorMessages.message("abstract.method.in.non.abstract.class");
      errorResult =
        HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(elementToHighlight).descriptionAndTooltip(description).create();
      if (method.getBody() != null) {
        IntentionAction fix = QUICK_FIX_FACTORY.createModifierListFix(method, PsiModifier.ABSTRACT, false, false);
        QuickFixAction.registerQuickFixAction(errorResult, fix);
      }
      QuickFixAction.registerQuickFixAction(errorResult, new AddMethodBodyFix(method));
      IntentionAction fix = QUICK_FIX_FACTORY.createModifierListFix(aClass, PsiModifier.ABSTRACT, true, false);
      QuickFixAction.registerQuickFixAction(errorResult, fix);
    }
    return errorResult;
  }


  static HighlightInfo checkConstructorName(PsiMethod method) {
    String methodName = method.getName();
    PsiClass aClass = method.getContainingClass();
    HighlightInfo errorResult = null;

    if (aClass != null) {
      String className = aClass instanceof PsiAnonymousClass ? null : aClass.getName();
      if (className == null || !Comparing.strEqual(methodName, className)) {
        PsiElement element = method.getNameIdentifier();
        String description = JavaErrorMessages.message("missing.return.type");
        errorResult = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(element).descriptionAndTooltip(description).create();
        if (className != null) {
          QuickFixAction.registerQuickFixAction(errorResult, new RenameElementFix(method, className));
        }
      }
    }
    return errorResult;
  }

  @Nullable
  static HighlightInfo checkDuplicateMethod(PsiClass aClass,
                                            @NotNull PsiMethod method,
                                            @NotNull MostlySingularMultiMap<MethodSignature, PsiMethod> duplicateMethods) {
    if (aClass == null || method instanceof ExternallyDefinedPsiElement) return null;
    MethodSignature methodSignature = method.getSignature(PsiSubstitutor.EMPTY);
    int methodCount = 1;
    List<PsiMethod> methods = (List<PsiMethod>)duplicateMethods.get(methodSignature);
    if (methods.size() > 1) {
      methodCount++;
    }

    if (methodCount == 1 && aClass.isEnum() &&
        GenericsHighlightUtil.isEnumSyntheticMethod(methodSignature, aClass.getProject())) {
      methodCount++;
    }
    if (methodCount > 1) {
      String description = JavaErrorMessages.message("duplicate.method",
                                                 JavaHighlightUtil.formatMethod(method),
                                                 HighlightUtil.formatClass(aClass));
      TextRange textRange = HighlightNamesUtil.getMethodDeclarationTextRange(method);
      return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(textRange).descriptionAndTooltip(description).create();
    }
    return null;
  }

  @Nullable
  static HighlightInfo checkMethodCanHaveBody(PsiMethod method, @NotNull LanguageLevel languageLevel,@NotNull PsiFile containingFile) {
    PsiClass aClass = method.getContainingClass();
    boolean hasNoBody = method.getBody() == null;
    boolean isInterface = aClass != null && aClass.isInterface();
    boolean isExtension = method.hasModifierProperty(PsiModifier.DEFAULT);
    boolean isStatic = method.hasModifierProperty(PsiModifier.STATIC);

    final List<IntentionAction> additionalFixes = new ArrayList<IntentionAction>();
    String description = null;
    if (hasNoBody) {
      if (isExtension) {
        description = JavaErrorMessages.message("extension.method.should.have.a.body");
        additionalFixes.add(new AddMethodBodyFix(method));
      }
      else if (isInterface && isStatic) {
        description = "Static methods in interfaces should have a body";
      }
    }
    else if (isInterface) {
      if (!isExtension && !isStatic) {
        description = JavaErrorMessages.message("interface.methods.cannot.have.body");
        if (PsiUtil.isLanguageLevel8OrHigher(method)) {
          additionalFixes.add(QUICK_FIX_FACTORY.createModifierListFix(method, PsiModifier.DEFAULT, true, false));
          additionalFixes.add(QUICK_FIX_FACTORY.createModifierListFix(method, PsiModifier.STATIC, true, false));
        }
      }
      else if (isExtension) {
        return HighlightUtil.checkExtensionMethodsFeature(method, languageLevel,containingFile);
      }
    }
    else if (isExtension) {
      description = JavaErrorMessages.message("extension.method.in.class");
    }
    else if (method.hasModifierProperty(PsiModifier.ABSTRACT)) {
      description = JavaErrorMessages.message("abstract.methods.cannot.have.a.body");
    }
    else if (method.hasModifierProperty(PsiModifier.NATIVE)) {
      description = JavaErrorMessages.message("native.methods.cannot.have.a.body");
    }
    if (description == null) return null;

    TextRange textRange = HighlightNamesUtil.getMethodDeclarationTextRange(method);
    HighlightInfo info = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(textRange).descriptionAndTooltip(description).create();
    if (!hasNoBody) {
      QuickFixAction.registerQuickFixAction(info, new DeleteMethodBodyFix(method));
    }
    if (method.hasModifierProperty(PsiModifier.ABSTRACT) && isInterface) {
      QuickFixAction.registerQuickFixAction(info, QUICK_FIX_FACTORY.createModifierListFix(method, PsiModifier.ABSTRACT, false, false));
    }
    for (IntentionAction intentionAction : additionalFixes) {
      QuickFixAction.registerQuickFixAction(info, intentionAction);
    }
    return info;
  }

  @Nullable
  static HighlightInfo checkConstructorCallMustBeFirstStatement(PsiReferenceExpression expression) {
    PsiElement methodCall = expression.getParent();
    if (!RefactoringChangeUtil.isSuperOrThisMethodCall(methodCall)) return null;
    PsiElement codeBlock = methodCall.getParent().getParent();
    if (codeBlock instanceof PsiCodeBlock
        && codeBlock.getParent() instanceof PsiMethod
        && ((PsiMethod)codeBlock.getParent()).isConstructor()) {
      PsiElement prevSibling = methodCall.getParent().getPrevSibling();
      while (true) {
        if (prevSibling == null) return null;
        if (prevSibling instanceof PsiStatement) break;
        prevSibling = prevSibling.getPrevSibling();
      }
    }
    String message = JavaErrorMessages.message("constructor.call.must.be.first.statement", expression.getText() + "()");
    PsiElement element = expression.getParent();
    return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(element).descriptionAndTooltip(message).create();
  }


  static HighlightInfo checkAbstractMethodDirectCall(PsiSuperExpression expr) {
    if (expr.getParent() instanceof PsiReferenceExpression
        && expr.getParent().getParent() instanceof PsiMethodCallExpression) {
      PsiMethodCallExpression methodCallExpression = (PsiMethodCallExpression)expr.getParent().getParent();
      PsiMethod method = methodCallExpression.resolveMethod();
      if (method != null && method.hasModifierProperty(PsiModifier.ABSTRACT)) {
        String message = JavaErrorMessages.message("direct.abstract.method.access", JavaHighlightUtil.formatMethod(method));
        return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(methodCallExpression).descriptionAndTooltip(message).create();
      }
    }
    return null;
  }


  static HighlightInfo checkConstructorCallsBaseClassConstructor(PsiMethod constructor,
                                                                        RefCountHolder refCountHolder,
                                                                        PsiResolveHelper resolveHelper) {
    if (!constructor.isConstructor()) return null;
    PsiClass aClass = constructor.getContainingClass();
    if (aClass == null) return null;
    if (aClass.isEnum()) return null;
    PsiCodeBlock body = constructor.getBody();
    if (body == null) return null;

    // check whether constructor call super(...) or this(...)
    PsiElement element = new PsiMatcherImpl(body)
      .firstChild(PsiMatchers.hasClass(PsiExpressionStatement.class))
      .firstChild(PsiMatchers.hasClass(PsiMethodCallExpression.class))
      .firstChild(PsiMatchers.hasClass(PsiReferenceExpression.class))
      .firstChild(PsiMatchers.hasClass(PsiKeyword.class))
      .getElement();
    if (element != null) return null;
    TextRange textRange = HighlightNamesUtil.getMethodDeclarationTextRange(constructor);
    PsiClassType[] handledExceptions = constructor.getThrowsList().getReferencedTypes();
    HighlightInfo info = HighlightClassUtil.checkBaseClassDefaultConstructorProblem(aClass, refCountHolder, resolveHelper, textRange, handledExceptions);
    if (info != null) {
      QuickFixAction.registerQuickFixAction(info, new InsertSuperFix(constructor));
      QuickFixAction.registerQuickFixAction(info, QUICK_FIX_FACTORY.createAddDefaultConstructorFix(aClass.getSuperClass()));
    }
    return info;
  }


  /**
   * @return error if static method overrides instance method or
   *         instance method overrides static. see JLS 8.4.6.1, 8.4.6.2
   */
  static HighlightInfo checkStaticMethodOverride(@NotNull PsiMethod method,@NotNull PsiFile containingFile) {
    // constructors are not members and therefor don't override class methods
    if (method.isConstructor()) {
      return null;
    }

    PsiClass aClass = method.getContainingClass();
    if (aClass == null) return null;
    PsiClass superClass = aClass.getSuperClass();
    PsiMethod superMethod = superClass == null
                            ? null
                            : MethodSignatureUtil.findMethodBySignature(superClass, method, true);

    boolean isStatic = method.hasModifierProperty(PsiModifier.STATIC);
    HighlightInfo highlightInfo = checkStaticMethodOverride(aClass, method, isStatic,superClass, superMethod,containingFile);
    if (highlightInfo != null) return highlightInfo;
    if (!isStatic) {
      // all methods in interface are instance, so no possible errors in this case
      return null;
    }
    PsiClass[] interfaces = aClass.getInterfaces();
    for (PsiClass aInterfaces : interfaces) {
        superClass = aInterfaces;
        superMethod = MethodSignatureUtil.findMethodInSuperClassBySignatureInDerived(aClass, superClass, method.getSignature(PsiSubstitutor.EMPTY), true);
        highlightInfo = checkStaticMethodOverride(aClass, method, true, superClass, superMethod,containingFile);
        if (highlightInfo != null) return highlightInfo;
      }
    return null;
  }

  private static HighlightInfo checkStaticMethodOverride(PsiClass aClass, PsiMethod method, boolean isMethodStatic, PsiClass superClass, PsiMethod superMethod,@NotNull PsiFile containingFile) {
    if (superMethod == null) return null;
    PsiManager manager = containingFile.getManager();
    PsiModifierList superModifierList = superMethod.getModifierList();
    PsiModifierList modifierList = method.getModifierList();
    if (superModifierList.hasModifierProperty(PsiModifier.PRIVATE)) return null;
    if (superModifierList.hasModifierProperty(PsiModifier.PACKAGE_LOCAL)
        && !JavaPsiFacade.getInstance(manager.getProject()).arePackagesTheSame(aClass, superClass)) {
      return null;
    }
    boolean isSuperMethodStatic = superModifierList.hasModifierProperty(PsiModifier.STATIC);
    if (isMethodStatic != isSuperMethodStatic) {
      TextRange textRange = HighlightNamesUtil.getMethodDeclarationTextRange(method);
      @NonNls final String messageKey = isMethodStatic
                                ? "static.method.cannot.override.instance.method"
                                : "instance.method.cannot.override.static.method";

      String description = JavaErrorMessages.message(messageKey,
                                                 JavaHighlightUtil.formatMethod(method),
                                                 HighlightUtil.formatClass(aClass),
                                                 JavaHighlightUtil.formatMethod(superMethod),
                                                 HighlightUtil.formatClass(superClass));

      HighlightInfo info = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(textRange).descriptionAndTooltip(description).create();
      if (!isSuperMethodStatic || HighlightUtil.getIncompatibleModifier(PsiModifier.STATIC, modifierList) == null) {
        IntentionAction fix = QUICK_FIX_FACTORY.createModifierListFix(method, PsiModifier.STATIC, isSuperMethodStatic, false);
        QuickFixAction.registerQuickFixAction(info, fix);
      }
      if (manager.isInProject(superMethod) &&
          (!isMethodStatic || HighlightUtil.getIncompatibleModifier(PsiModifier.STATIC, superModifierList) == null)) {
        IntentionAction fix = QUICK_FIX_FACTORY.createModifierListFix(superMethod, PsiModifier.STATIC, isMethodStatic, true);
        QuickFixAction.registerQuickFixAction(info, fix);
      }
      return info;
    }

    if (isMethodStatic) {
      int accessLevel = PsiUtil.getAccessLevel(modifierList);
      String accessModifier = PsiUtil.getAccessModifier(accessLevel);
      HighlightInfo info = isWeaker(method, modifierList, accessModifier, accessLevel, superMethod, true);
      if (info != null) return info;
      info = checkSuperMethodIsFinal(method, superMethod);
      if (info != null) return info;
    }
    return null;
  }

  private static HighlightInfo checkInterfaceInheritedMethodsReturnTypes(List<? extends MethodSignatureBackedByPsiMethod> superMethodSignatures) {
    if (superMethodSignatures.size() < 2) return null;
    MethodSignatureBackedByPsiMethod returnTypeSubstitutable = superMethodSignatures.get(0);
    for (int i = 1; i < superMethodSignatures.size(); i++) {
      PsiMethod currentMethod = returnTypeSubstitutable.getMethod();
      PsiType currentType = returnTypeSubstitutable.getSubstitutor().substitute(currentMethod.getReturnType());

      MethodSignatureBackedByPsiMethod otherSuperSignature = superMethodSignatures.get(i);
      PsiMethod otherSuperMethod = otherSuperSignature.getMethod();
      PsiType otherSuperReturnType = otherSuperSignature.getSubstitutor().substitute(otherSuperMethod.getReturnType());

      PsiSubstitutor unifyingSubstitutor = MethodSignatureUtil.getSuperMethodSignatureSubstitutor(returnTypeSubstitutable,
                                                                                                  otherSuperSignature);
      if (unifyingSubstitutor != null) {
        otherSuperReturnType = unifyingSubstitutor.substitute(otherSuperReturnType);
        currentType = unifyingSubstitutor.substitute(currentType);
      }

      if (otherSuperReturnType == null || currentType == null || otherSuperReturnType.equals(currentType)) continue;

      if (PsiUtil.isLanguageLevel5OrHigher(currentMethod)) {
        //http://docs.oracle.com/javase/specs/jls/se7/html/jls-8.html#jls-8.4.8 Example 8.1.5-3
        if (!(otherSuperReturnType instanceof PsiPrimitiveType || currentType instanceof PsiPrimitiveType)) {
          if (otherSuperReturnType.isAssignableFrom(currentType)) continue;
          if (currentType.isAssignableFrom(otherSuperReturnType)) {
            returnTypeSubstitutable = otherSuperSignature;
            continue;
          }
        }
      }
      return createIncompatibleReturnTypeMessage(currentMethod, currentMethod, otherSuperMethod, false, otherSuperReturnType,
                                                 currentType, JavaErrorMessages.message("unrelated.overriding.methods.return.types"));
    }
    return null;
  }

  static HighlightInfo checkOverrideEquivalentInheritedMethods(PsiClass aClass, PsiFile containingFile) {
    String description = null;
    final Collection<HierarchicalMethodSignature> visibleSignatures = aClass.getVisibleSignatures();
    PsiResolveHelper resolveHelper = JavaPsiFacade.getInstance(aClass.getProject()).getResolveHelper();
    Ultimate:
    for (HierarchicalMethodSignature signature : visibleSignatures) {
      PsiMethod method = signature.getMethod();
      if (!resolveHelper.isAccessible(method, aClass, null)) continue;
      List<HierarchicalMethodSignature> superSignatures = signature.getSuperSignatures();

      boolean allAbstracts = method.hasModifierProperty(PsiModifier.ABSTRACT);
      final PsiClass containingClass = method.getContainingClass();
      if (aClass.equals(containingClass)) continue; //to be checked at method level

      if (aClass.isInterface() && !containingClass.isInterface()) continue;
      HighlightInfo highlightInfo = null;
      if (allAbstracts) {
        superSignatures = new ArrayList<HierarchicalMethodSignature>(superSignatures);
        superSignatures.add(signature);
        highlightInfo = checkInterfaceInheritedMethodsReturnTypes(superSignatures);
      }
      else {
        highlightInfo = checkMethodIncompatibleReturnType(signature, superSignatures, false);
      }
      if (highlightInfo != null) description = highlightInfo.getDescription();

      if (method.hasModifierProperty(PsiModifier.STATIC)) {
        for (HierarchicalMethodSignature superSignature : superSignatures) {
          PsiMethod superMethod = superSignature.getMethod();
          if (!superMethod.hasModifierProperty(PsiModifier.STATIC)) {
            description = JavaErrorMessages.message("static.method.cannot.override.instance.method",
                                                         JavaHighlightUtil.formatMethod(method),
                                                         HighlightUtil.formatClass(containingClass),
                                                         JavaHighlightUtil.formatMethod(superMethod),
                                                         HighlightUtil.formatClass(superMethod.getContainingClass()));
            break Ultimate;
          }
        }
        continue;
      }

      if (description == null) {
        highlightInfo = checkMethodIncompatibleThrows(signature, superSignatures, false, aClass);
        if (highlightInfo != null) description = highlightInfo.getDescription();
      }

      if (description == null) {
        highlightInfo = checkMethodWeakerPrivileges(signature, superSignatures, false, containingFile);
        if (highlightInfo != null) description = highlightInfo.getDescription();
      }

      if (description != null) break;
    }


    if (description != null) {
      // show error info at the class level
      TextRange textRange = HighlightNamesUtil.getClassDeclarationTextRange(aClass);
      return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(textRange).descriptionAndTooltip(description).create();
    }
    return null;
  }


  static HighlightInfo checkConstructorHandleSuperClassExceptions(PsiMethod method) {
    if (!method.isConstructor()) {
      return null;
    }
    PsiCodeBlock body = method.getBody();
    PsiStatement[] statements = body == null ? null : body.getStatements();
    if (statements == null) return null;

    // if we have unhandled exception inside method body, we could not have been called here,
    // so the only problem it can catch here is with super ctr only
    Collection<PsiClassType> unhandled = ExceptionUtil.collectUnhandledExceptions(method, method.getContainingClass());
    if (unhandled.isEmpty()) return null;
    String description = HighlightUtil.getUnhandledExceptionsDescriptor(unhandled);
    TextRange textRange = HighlightNamesUtil.getMethodDeclarationTextRange(method);
    HighlightInfo highlightInfo = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(textRange).descriptionAndTooltip(description).create();
    for (PsiClassType exception : unhandled) {
      QuickFixAction.registerQuickFixAction(highlightInfo, new LocalQuickFixOnPsiElementAsIntentionAdapter(QUICK_FIX_FACTORY.createMethodThrowsFix(method, exception, true, false)));
    }
    return highlightInfo;
  }


  static HighlightInfo checkRecursiveConstructorInvocation(PsiMethod method) {
    if (HighlightControlFlowUtil.isRecursivelyCalledConstructor(method)) {
      TextRange textRange = HighlightNamesUtil.getMethodDeclarationTextRange(method);
      String description = JavaErrorMessages.message("recursive.constructor.invocation");
      return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(textRange).descriptionAndTooltip(description).create();
    }
    return null;
  }

  @NotNull
  public static TextRange getFixRange(@NotNull PsiElement element) {
    TextRange range = element.getTextRange();
    int start = range.getStartOffset();
    int end = range.getEndOffset();

    PsiElement nextSibling = element.getNextSibling();
    if (nextSibling instanceof PsiJavaToken && ((PsiJavaToken)nextSibling).getTokenType() == JavaTokenType.SEMICOLON) {
      return new TextRange(start, end + 1);
    }
    return range;
  }


  static void checkNewExpression(@NotNull PsiNewExpression expression, @NotNull HighlightInfoHolder holder, @NotNull JavaSdkVersion javaSdkVersion) {
    PsiType type = expression.getType();
    if (!(type instanceof PsiClassType)) return;
    PsiClassType.ClassResolveResult typeResult = ((PsiClassType)type).resolveGenerics();
    PsiClass aClass = typeResult.getElement();
    if (aClass == null) return;
    if (aClass instanceof PsiAnonymousClass) {
      type = ((PsiAnonymousClass)aClass).getBaseClassType();
      typeResult = ((PsiClassType)type).resolveGenerics();
      aClass = typeResult.getElement();
      if (aClass == null) return;
    }

    PsiJavaCodeReferenceElement classReference = expression.getClassOrAnonymousClassReference();
    checkConstructorCall(typeResult, expression, type, classReference, holder, javaSdkVersion);
  }


  public static void checkConstructorCall(PsiClassType.ClassResolveResult typeResolveResult,
                                          PsiConstructorCall constructorCall,
                                          PsiType type,
                                          PsiJavaCodeReferenceElement classReference,
                                          final HighlightInfoHolder holder,
                                          @NotNull JavaSdkVersion javaSdkVersion) {
    PsiExpressionList list = constructorCall.getArgumentList();
    if (list == null) return;
    PsiClass aClass = typeResolveResult.getElement();
    if (aClass == null) return;
    final PsiResolveHelper resolveHelper = JavaPsiFacade.getInstance(holder.getProject()).getResolveHelper();
    PsiClass accessObjectClass = null;
    if (constructorCall instanceof PsiNewExpression) {
      PsiExpression qualifier = ((PsiNewExpression)constructorCall).getQualifier();
      if (qualifier != null) {
        accessObjectClass = (PsiClass)PsiUtil.getAccessObjectClass(qualifier).getElement();
      }
    }
    if (classReference != null && !resolveHelper.isAccessible(aClass, constructorCall, accessObjectClass)) {
      String description = HighlightUtil.buildProblemWithAccessDescription(classReference, typeResolveResult);
      PsiElement element = classReference.getReferenceNameElement();
      HighlightInfo info =
        HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(element).descriptionAndTooltip(description).create();
      HighlightUtil.registerAccessQuickFixAction(aClass, classReference, info, null);
      holder.add(info);
      return;
    }
    PsiMethod[] constructors = aClass.getConstructors();

    if (constructors.length == 0) {
      if (list.getExpressions().length != 0) {
        String constructorName = aClass.getName();
        String argTypes = buildArgTypesList(list);
        String description = JavaErrorMessages.message("wrong.constructor.arguments", constructorName+"()", argTypes);
        String tooltip = createMismatchedArgumentsHtmlTooltip(list, PsiParameter.EMPTY_ARRAY, constructorName, PsiSubstitutor.EMPTY, aClass);
        HighlightInfo info = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(list).description(description).escapedToolTip(tooltip).navigationShift(+1).create();
        QuickFixAction.registerQuickFixAction(info, constructorCall.getTextRange(), new CreateConstructorFromCallFix(constructorCall));
        if (classReference != null) {
          ConstructorParametersFixer.registerFixActions(classReference, constructorCall, info,getFixRange(list));
        }
        holder.add(info);
        return;
      }
      if (classReference != null && aClass.hasModifierProperty(PsiModifier.PROTECTED) && callingProtectedConstructorFromDerivedClass(constructorCall, aClass)) {
        holder.add(buildAccessProblem(classReference, typeResolveResult, aClass));
      } else if (aClass.isInterface() && constructorCall instanceof PsiNewExpression) {
        final PsiReferenceParameterList typeArgumentList = ((PsiNewExpression)constructorCall).getTypeArgumentList();
        if (typeArgumentList.getTypeArguments().length > 0) {
          holder.add(HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(typeArgumentList)
            .descriptionAndTooltip("Anonymous class implements interface; cannot have type arguments").create());
        }
      }
    }
    else {
      PsiElement place = list;
      if (constructorCall instanceof PsiNewExpression) {
        final PsiAnonymousClass anonymousClass = ((PsiNewExpression)constructorCall).getAnonymousClass();
        if (anonymousClass != null) place = anonymousClass;
      }

      JavaResolveResult[] results = resolveHelper.multiResolveConstructor((PsiClassType)type, list, place);
      MethodCandidateInfo result = null;
      if (results.length == 1) result = (MethodCandidateInfo)results[0];

      PsiMethod constructor = result == null ? null : result.getElement();

      boolean applicable = true;
      try {
        applicable = constructor != null && result.isApplicable();
      }
      catch (IndexNotReadyException e) {
        // ignore
      }

      if (constructor == null) {
        String name = aClass.getName();
        name += buildArgTypesList(list);
        String description = JavaErrorMessages.message("cannot.resolve.constructor", name);
        HighlightInfo info = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(list).descriptionAndTooltip(description).navigationShift(+1).create();
        QuickFixAction.registerQuickFixAction(info, constructorCall.getTextRange(), new CreateConstructorFromCallFix(constructorCall));
        if (classReference != null) {
          ConstructorParametersFixer.registerFixActions(classReference, constructorCall, info,getFixRange(list));
          PermuteArgumentsFix.registerFix(info, constructorCall, toMethodCandidates(results), getFixRange(list));
        }
        WrapExpressionFix.registerWrapAction(results, list.getExpressions(), info);
        holder.add(info);
        ChangeStringLiteralToCharInMethodCallFix.registerFixes(constructors, constructorCall, info);
      }
      else {
        if (classReference != null && (!result.isAccessible() ||
                                       constructor.hasModifierProperty(PsiModifier.PROTECTED) && callingProtectedConstructorFromDerivedClass(constructorCall, aClass))) {
          holder.add(buildAccessProblem(classReference, result, constructor));
        }
        else if (!applicable) {
          String constructorName = HighlightMessageUtil.getSymbolName(constructor, result.getSubstitutor());
          String containerName = HighlightMessageUtil.getSymbolName(constructor.getContainingClass(), result.getSubstitutor());
          String argTypes = buildArgTypesList(list);
          String description = JavaErrorMessages.message("wrong.method.arguments", constructorName, containerName, argTypes);
          String toolTip = createMismatchedArgumentsHtmlTooltip(result, list);
          PsiElement infoElement = list.getTextLength() > 0 ? list : constructorCall;
          HighlightInfo info = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(infoElement).description(description).escapedToolTip(toolTip).navigationShift(+1).create();
          if (info != null) {
            QuickFixAction.registerQuickFixAction(info, constructorCall.getTextRange(), new CreateConstructorFromCallFix(constructorCall));
            if (classReference != null) {
              ConstructorParametersFixer.registerFixActions(classReference, constructorCall, info, getFixRange(infoElement));
              ChangeMethodSignatureFromUsageFix.registerIntentions(results, list, info, null);
              ChangeTypeArgumentsFix.registerIntentions(results, list, info, aClass);
              ConvertDoubleToFloatFix.registerIntentions(results, list, info, null);
              PermuteArgumentsFix.registerFix(info, constructorCall, toMethodCandidates(results), getFixRange(list));
              ChangeParameterClassFix.registerQuickFixActions(constructorCall, list, info);
              QuickFixAction.registerQuickFixAction(info, getFixRange(list), new SurroundWithArrayFix(constructorCall));
            }
            holder.add(info);
            ChangeStringLiteralToCharInMethodCallFix.registerFixes(constructors, constructorCall, info);
          }
        }
        else {
          if (constructorCall instanceof PsiNewExpression) {
            PsiReferenceParameterList typeArgumentList = ((PsiNewExpression)constructorCall).getTypeArgumentList();
            HighlightInfo info = GenericsHighlightUtil.checkReferenceTypeArgumentList(constructor, typeArgumentList, result.getSubstitutor(), false, javaSdkVersion);
            if (info != null) {
              holder.add(info);
            }
          }
        }
      }
    }
  }

  private static HighlightInfo buildAccessProblem(@NotNull PsiJavaCodeReferenceElement classReference, JavaResolveResult result, PsiMember elementToFix) {
    String description = HighlightUtil.buildProblemWithAccessDescription(classReference, result);
    HighlightInfo info = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(classReference).descriptionAndTooltip(
      description).navigationShift(+1).create();
    if (result.isStaticsScopeCorrect()) {
      HighlightUtil.registerAccessQuickFixAction(elementToFix, classReference, info, result.getCurrentFileResolveScope());
    }
    return info;
  }

  private static boolean callingProtectedConstructorFromDerivedClass(PsiConstructorCall place, PsiClass constructorClass) {
    if (constructorClass == null) return false;
    // indirect instantiation via anonymous class is ok
    if (place instanceof PsiNewExpression && ((PsiNewExpression)place).getAnonymousClass() != null) return false;
    PsiElement curElement = place;
    PsiClass containingClass = constructorClass.getContainingClass();
    while (true) {
      PsiClass aClass = PsiTreeUtil.getParentOfType(curElement, PsiClass.class);
      if (aClass == null) return false;
      curElement = aClass;
      if ((aClass.isInheritor(constructorClass, true) || containingClass != null && aClass.isInheritor(containingClass, true))
          && !JavaPsiFacade.getInstance(aClass.getProject()).arePackagesTheSame(aClass, constructorClass)) {
        return true;
      }
    }
  }

  private static String buildArgTypesList(PsiExpressionList list) {
    StringBuilder builder = new StringBuilder();
    builder.append("(");
    PsiExpression[] args = list.getExpressions();
    for (int i = 0; i < args.length; i++) {
      if (i > 0) {
        builder.append(", ");
      }
      PsiType argType = args[i].getType();
      builder.append(argType != null ? JavaHighlightUtil.formatType(argType) : "?");
    }
    builder.append(")");
    return builder.toString();
  }
}
