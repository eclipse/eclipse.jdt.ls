/*******************************************************************************
 * Copyright (c) 2016-2020 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Red Hat Inc. - initial API and implementation
 *     Microsoft Corporation - extract to a base class
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal.handlers;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IOpenable;
import org.eclipse.jdt.core.IProblemRequestor;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblem;
import org.eclipse.jdt.internal.core.util.Util;
import org.eclipse.jdt.ls.core.internal.JDTUtils;
import org.eclipse.jdt.ls.core.internal.JavaClientConnection;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.ResourceUtils;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticRelatedInformation;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DiagnosticTag;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;

public abstract class BaseDiagnosticsHandler implements IProblemRequestor {

	private final ICompilationUnit cu;
	protected final List<IProblem> problems;
	private final String uri;
	private final JavaClientConnection connection;
	private boolean isDefaultProject;

	public static final int NON_PROJECT_JAVA_FILE = 0x10;
	public static final int NOT_ON_CLASSPATH = 0x20;

	public BaseDiagnosticsHandler(JavaClientConnection conn, ICompilationUnit cu) {
		problems = new ArrayList<>();
		this.cu = cu;
		this.uri = JDTUtils.toURI(cu);
		this.connection = conn;
		this.isDefaultProject = JDTUtils.isDefaultProject(cu);
	}

	@Override
	public void acceptProblem(IProblem problem) {
		if (!isSyntaxMode() || isSyntaxLikeError(problem)) {
			problems.add(problem);
		}
	}

	public abstract boolean isSyntaxMode();

	public boolean isSyntaxLikeError(IProblem problem) {
		//Syntax issues are always reported
		if ((problem.getID() & IProblem.Syntax) != 0) {
			return true;
		}
		if (!isDefaultProject && problem.getID() == IProblem.PackageIsNotExpectedPackage) {
			return false;
		}
		//Type and Import issues are never reported
		if ((problem.getID() & IProblem.TypeRelated) != 0 || //
				(problem.getID() & IProblem.ImportRelated) != 0) {
			return false;
		}
		//For the rest, we need to cherry pick what is ignored or not
		switch (problem.getID()) {
			case IProblem.AbstractMethodMustBeImplemented:
			case IProblem.AmbiguousMethod:
			case IProblem.DanglingReference:
			case IProblem.MethodMustOverrideOrImplement:
			case IProblem.MissingReturnType:
			case IProblem.MissingTypeInConstructor:
			case IProblem.MissingTypeInLambda:
			case IProblem.MissingTypeInMethod:
			case IProblem.UndefinedConstructor:
			case IProblem.UndefinedField:
			case IProblem.UndefinedMethod:
			case IProblem.UndefinedName:
			case IProblem.UnresolvedVariable:
			case IProblem.ParameterMismatch:
				return false;
			default:
				//We log problems for troubleshooting purposes
				String error = getError(problem);
				JavaLanguageServerPlugin.logInfo(problem.getMessage() + " is of type " + error);
		}
		return true;
	}

	private String getError(IProblem problem) {
		try {
			for (Field field : IProblem.class.getDeclaredFields()) {
				if (int.class.equals(field.getType())
						&& Integer.valueOf(problem.getID()).equals(field.get(null))) {
					return field.getName();
				}
			}
		} catch (Exception e) {
		}
		return "unknown";
	}

	@Override
	public void beginReporting() {
		JavaLanguageServerPlugin.logInfo("begin problem for " + this.uri.substring(this.uri.lastIndexOf('/')));
		problems.clear();
	}

	@Override
	public void endReporting() {
		JavaLanguageServerPlugin.logInfo(problems.size() + " problems reported for " + this.uri.substring(this.uri.lastIndexOf('/')));
		boolean isDiagnosticTagSupported = JavaLanguageServerPlugin.getPreferencesManager().getClientPreferences().isDiagnosticTagSupported();
		String clientUri = ResourceUtils.toClientUri(uri);
		PublishDiagnosticsParams $ = new PublishDiagnosticsParams(clientUri, toDiagnosticsArray(this.cu, problems, isDiagnosticTagSupported, clientUri));
		this.connection.publishDiagnostics($);
	}

	@Override
	public boolean isActive() {
		return true;
	}

	@Deprecated
	public static List<Diagnostic> toDiagnosticsArray(IOpenable openable, List<IProblem> problems, String clientUri) {
		return toDiagnosticsArray(openable, problems, false, clientUri);
	}

	@Deprecated
	public static List<Diagnostic> toDiagnosticsArray(IOpenable openable, List<IProblem> problems, boolean isDiagnosticTagSupported) {
		return toDiagnosticsArray(openable, problems, isDiagnosticTagSupported, null);
	}

	public static List<Diagnostic> toDiagnosticsArray(IOpenable openable, List<IProblem> problems, boolean isDiagnosticTagSupported, String clientUri) {
		List<Diagnostic> array = new ArrayList<>(problems.size());
		for (IProblem problem : problems) {
			Diagnostic diag = new Diagnostic();
			diag.setSource(JavaLanguageServerPlugin.SERVER_SOURCE_ID);
			diag.setMessage(problem.getMessage());
			diag.setCode(Integer.toString(problem.getID()));
			diag.setSeverity(convertSeverity(problem));
			Range range = convertRange(openable, problem);
			diag.setRange(range);
			if (isDiagnosticTagSupported) {
				diag.setTags(getDiagnosticTag(problem.getID()));
			}
			if (clientUri != null && problem.getArguments() != null && problem.getArguments().length > 0) {
				String argumentsString = Util.getProblemArgumentsForMarker(problem.getArguments());
				if (argumentsString != null && !argumentsString.isEmpty()) {
					Location location = new Location(clientUri, range);
					DiagnosticRelatedInformation dri = new DiagnosticRelatedInformation(location, argumentsString);
					List<DiagnosticRelatedInformation> relatedInformation = new ArrayList<>();
					relatedInformation.add(dri);
					diag.setRelatedInformation(relatedInformation);
				}
			}
			array.add(diag);
		}
		return array;
	}

	public static List<DiagnosticTag> getDiagnosticTag(int id) {
		switch (id) {
			case IProblem.UsingDeprecatedType:
			case IProblem.UsingDeprecatedField:
			case IProblem.UsingDeprecatedMethod:
			case IProblem.UsingDeprecatedConstructor:
			case IProblem.OverridingDeprecatedMethod:
			case IProblem.JavadocUsingDeprecatedField:
			case IProblem.JavadocUsingDeprecatedConstructor:
			case IProblem.JavadocUsingDeprecatedMethod:
			case IProblem.JavadocUsingDeprecatedType:
			case IProblem.UsingTerminallyDeprecatedType:
			case IProblem.UsingTerminallyDeprecatedMethod:
			case IProblem.UsingTerminallyDeprecatedConstructor:
			case IProblem.UsingTerminallyDeprecatedField:
			case IProblem.OverridingTerminallyDeprecatedMethod:
			case IProblem.UsingDeprecatedSinceVersionType:
			case IProblem.UsingDeprecatedSinceVersionMethod:
			case IProblem.UsingDeprecatedSinceVersionConstructor:
			case IProblem.UsingDeprecatedSinceVersionField:
			case IProblem.OverridingDeprecatedSinceVersionMethod:
			case IProblem.UsingTerminallyDeprecatedSinceVersionType:
			case IProblem.UsingTerminallyDeprecatedSinceVersionMethod:
			case IProblem.UsingTerminallyDeprecatedSinceVersionConstructor:
			case IProblem.UsingTerminallyDeprecatedSinceVersionField:
			case IProblem.OverridingTerminallyDeprecatedSinceVersionMethod:
			case IProblem.UsingDeprecatedPackage:
			case IProblem.UsingDeprecatedSinceVersionPackage:
			case IProblem.UsingTerminallyDeprecatedPackage:
			case IProblem.UsingTerminallyDeprecatedSinceVersionPackage:
			case IProblem.UsingDeprecatedModule:
			case IProblem.UsingDeprecatedSinceVersionModule:
			case IProblem.UsingTerminallyDeprecatedModule:
			case IProblem.UsingTerminallyDeprecatedSinceVersionModule:
				return Arrays.asList(DiagnosticTag.Deprecated);
			case IProblem.UnnecessaryCast:
			case IProblem.UnnecessaryInstanceof:
			case IProblem.UnnecessaryElse:
			case IProblem.UnnecessaryNLSTag:
			// Report *unused* cases as unnecessary
			case IProblem.UnusedPrivateType:
			case IProblem.UnusedPrivateField:
			case IProblem.UnusedPrivateMethod:
			case IProblem.UnusedPrivateConstructor:
			case IProblem.UnusedObjectAllocation:
			case IProblem.UnusedMethodDeclaredThrownException:
			case IProblem.UnusedConstructorDeclaredThrownException:
			case IProblem.UnusedLabel:
			case IProblem.UnusedImport:
			case IProblem.UnusedTypeArgumentsForMethodInvocation:
			case IProblem.UnusedWarningToken:
			case IProblem.UnusedTypeArgumentsForConstructorInvocation:
			case IProblem.UnusedTypeParameter:
			// Other unused cases
			case IProblem.LocalVariableIsNeverUsed:
			case IProblem.ArgumentIsNeverUsed:
			case IProblem.ExceptionParameterIsNeverUsed:
				return Arrays.asList(DiagnosticTag.Unnecessary);
		}

		return null;
	}

	private static DiagnosticSeverity convertSeverity(IProblem problem) {
		if (problem.isError()) {
			return DiagnosticSeverity.Error;
		}
		if (problem.isWarning() && (problem.getID() != IProblem.Task)) {
			return DiagnosticSeverity.Warning;
		}
		return DiagnosticSeverity.Information;
	}

	@SuppressWarnings("restriction")
	private static Range convertRange(IOpenable openable, IProblem problem) {
		try {
			if (problem.getID() == IProblem.UndefinedType && openable instanceof ICompilationUnit) {
				ICompilationUnit cu = (ICompilationUnit) openable;
				if (cu.getBuffer() != null) {
					int start = problem.getSourceStart();
					if (start > 0) {
						start--;
						char ch = cu.getBuffer().getChar(start);
						while (Character.isWhitespace(ch)) {
							start--;
							ch = cu.getBuffer().getChar(start);
						}
						if (ch == '@') {
							return JDTUtils.toRange(openable, start, problem.getSourceEnd() - start + 1);
						}
					}
				}
			}
			return JDTUtils.toRange(openable, problem.getSourceStart(), problem.getSourceEnd() - problem.getSourceStart() + 1);
		} catch (CoreException e) {
			// In case failed to open the IOpenable's buffer, use the IProblem's information to calculate the range.
			Position start = new Position();
			Position end = new Position();

			start.setLine(problem.getSourceLineNumber() - 1);// The protocol is 0-based.
			end.setLine(problem.getSourceLineNumber() - 1);
			if (problem instanceof DefaultProblem) {
				DefaultProblem dProblem = (DefaultProblem) problem;
				start.setCharacter(dProblem.getSourceColumnNumber() - 1);
				int offset = 0;
				if (dProblem.getSourceStart() != -1 && dProblem.getSourceEnd() != -1) {
					offset = dProblem.getSourceEnd() - dProblem.getSourceStart() + 1;
				}
				end.setCharacter(dProblem.getSourceColumnNumber() - 1 + offset);
			}
			return new Range(start, end);
		}
	}

	public void clearDiagnostics() {
		JavaLanguageServerPlugin.logInfo("Clearing problems for " + this.uri.substring(this.uri.lastIndexOf('/')));
		problems.clear();
		PublishDiagnosticsParams $ = new PublishDiagnosticsParams(ResourceUtils.toClientUri(uri), Collections.emptyList());
		this.connection.publishDiagnostics($);
	}
}
