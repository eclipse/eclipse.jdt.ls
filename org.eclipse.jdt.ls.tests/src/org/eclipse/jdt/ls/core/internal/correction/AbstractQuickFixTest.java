/*******************************************************************************
 * Copyright (c) 2017-2021 Microsoft Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Microsoft Corporation. - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal.correction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.manipulation.CoreASTProvider;
import org.eclipse.jdt.ls.core.internal.JDTUtils;
import org.eclipse.jdt.ls.core.internal.ResourceUtils;
import org.eclipse.jdt.ls.core.internal.TextEditUtil;
import org.eclipse.jdt.ls.core.internal.handlers.CodeActionHandler;
import org.eclipse.jdt.ls.core.internal.handlers.DiagnosticsHandler;
import org.eclipse.jdt.ls.core.internal.managers.AbstractProjectsManagerBasedTest;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ResourceOperation;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.Assert;

public class AbstractQuickFixTest extends AbstractProjectsManagerBasedTest {

	private List<String> ignoredCommands;

	private List<String> ignoredKinds = Arrays.asList(CodeActionKind.Source + ".*");

	private List<String> onlyKinds;

	protected void assertCodeActionExists(ICompilationUnit cu, Expected expected) throws Exception {
		List<Either<Command, CodeAction>> codeActions = evaluateCodeActions(cu);
		for (Either<Command, CodeAction> c : codeActions) {
			if (Objects.equals(expected.name, getTitle(c))) {
				expected.assertEquivalent(c);
				return;
			}
		}
		String allCommands = codeActions.stream().map(a -> getTitle(a)).collect(Collectors.joining("\n"));
		fail(expected.name + " not found in " + allCommands);
	}

	protected void assertCodeActionNotExists(ICompilationUnit cu, String label) throws Exception {
		List<Either<Command, CodeAction>> codeActionCommands = evaluateCodeActions(cu);
		assertFalse("'" + label + "' should not be added to the code actions", codeActionCommands.stream().filter(ca -> getTitle(ca).equals(label)).findAny().isPresent());
	}

	protected void assertCodeActions(ICompilationUnit cu, Collection<Expected> expected) throws Exception {
		assertCodeActions(cu, expected.toArray(new Expected[expected.size()]));
	}

	protected void assertCodeActions(ICompilationUnit cu, Range range, Collection<Expected> expected) throws Exception {
		assertCodeActions(cu, range, expected.toArray(new Expected[expected.size()]));
	}

	protected void assertCodeActions(ICompilationUnit cu, Expected... expecteds) throws Exception {
		List<Either<Command, CodeAction>> codeActions = evaluateCodeActions(cu);
		assertCodeActions(codeActions, expecteds);
	}

	protected void assertCodeActions(ICompilationUnit cu, Range range, Expected... expecteds) throws Exception {
		List<Either<Command, CodeAction>> codeActions = evaluateCodeActions(cu, range);
		assertCodeActions(codeActions, expecteds);
	}

	protected void assertCodeActions(List<Either<Command, CodeAction>> codeActions, Expected... expecteds) throws Exception {
		if (codeActions.size() < expecteds.length) {
			String res = codeActions.stream().map(a -> ("'" + getTitle(a) + "'")).collect(Collectors.joining(","));
			assertEquals("Number of code actions: " + res, expecteds.length, codeActions.size());
		}

		Map<String, Expected> expectedActions = Stream.of(expecteds).collect(Collectors.toMap(Expected::getName, Function.identity()));
		Map<String, Either<Command, CodeAction>> actualActions = codeActions.stream().collect(Collectors.toMap(this::getTitle, Function.identity()));

		for (Expected expected : expecteds) {
			Either<Command, CodeAction> action = actualActions.get(expected.name);
			assertNotNull("Should prompt code action: " + expected.name, action);
			expected.assertEquivalent(action);
		}

		int k = 0;
		String aStr = "", eStr = "", testContent = "";
		for (Either<Command, CodeAction> c : codeActions) {
			String title = getTitle(c);
			Expected e = expectedActions.get(title);
			if (e != null) {
				String actual = evaluateCodeActionCommand(c);
				if (!Objects.equals(e.content, actual)) {
					aStr += '\n' + title + '\n' + actual;
					eStr += '\n' + e.name + '\n' + e.content;
				}
				testContent += generateTest(actual, getTitle(c), k);
				k++;
			}
		}
		if (aStr.length() > 0) {
			aStr += '\n' + testContent;
		}
		assertEquals(eStr, aStr);
	}

	protected String generateTest(String actual, String name, int k) {
		StringBuilder builder = new StringBuilder();
		String[] lines = actual.split("\n");
		builder.append("		buf = new StringBuilder();\n");
		for (String line : lines) {
			wrapInBufAppend(line, builder);
		}
		builder.append("		Expected e" + k + " = new Expected(\"" + name + "\", buf.toString());\n");
		builder.append("\n");
		return builder.toString();
	}

	private static void wrapInBufAppend(String curr, StringBuilder buf) {
		buf.append("		buf.append(\"");

		int last = curr.length() - 1;
		for (int k = 0; k <= last; k++) {
			char ch = curr.charAt(k);
			if (ch == '\n') {
				buf.append("\\n\");\n");
				if (k < last) {
					buf.append("buf.append(\"");
				}
			} else if (ch == '\r') {
				// ignore
			} else if (ch == '\t') {
				buf.append("    "); // 4 spaces
			} else if (ch == '"' || ch == '\\') {
				buf.append('\\').append(ch);
			} else {
				buf.append(ch);
			}
		}
		if (buf.length() > 0 && buf.charAt(buf.length() - 1) != '\n') {
			buf.append("\\n\");\n");
		}
	}

	public class Expected {
		String name;
		String content;
		String kind;
		private static final String ALL_KINDS = "*";

		public Expected(String name, String content) {
			this(name, content, ALL_KINDS);
		}

		public Expected(String name, String content, String kind) {
			this.content = content;
			this.name = name;
			this.kind = kind;
		}

		public String getName() {
			return name;
		}

		/**
		 * Checks if the action has the same title as this. If it has, then assert that
		 * that action is equivalent to this in kind and content.
		 */
		public void assertEquivalent(Either<Command, CodeAction> action) throws Exception {
			String title = getTitle(action);
			assertEquals("Unexpected command :", name, title);
			if (!ALL_KINDS.equals(kind) && action.isRight()) {
				assertEquals(title + " has the wrong kind ", kind, action.getRight().getKind());
			}
			String actionContent = evaluateCodeActionCommand(action);
			actionContent = ResourceUtils.dos2Unix(actionContent);
			assertEquals(title + " has the wrong content ", content, actionContent);
		}
	}

	protected Range getRange(ICompilationUnit cu, IProblem[] problems) throws JavaModelException {
		if (problems.length == 0) {
			return new Range(new Position(), new Position());
		}
		IProblem problem = problems[0];
		return JDTUtils.toRange(cu, problem.getSourceStart(), 0);
	}

	protected void setIgnoredCommands(String... ignoredCommands) {
		this.ignoredCommands = Arrays.asList(ignoredCommands);
	}

	protected void setIgnoredKind(String... ignoredKind) {
		this.ignoredKinds = Arrays.asList(ignoredKind);
	}

	protected void setOnly(String... onlyKinds) {
		this.onlyKinds = Arrays.asList(onlyKinds);
	}

	protected List<Either<Command, CodeAction>> evaluateCodeActions(ICompilationUnit cu) throws JavaModelException {

		CompilationUnit astRoot = CoreASTProvider.getInstance().getAST(cu, CoreASTProvider.WAIT_YES, null);
		IProblem[] problems = astRoot.getProblems();

		Range range = getRange(cu, problems);
		return evaluateCodeActions(cu, range);
	}

	protected List<Either<Command, CodeAction>> evaluateCodeActions(ICompilationUnit cu, Range range) throws JavaModelException {

		CompilationUnit astRoot = CoreASTProvider.getInstance().getAST(cu, CoreASTProvider.WAIT_YES, null);
		IProblem[] problems = astRoot.getProblems();

		CodeActionParams parms = new CodeActionParams();

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier();
		textDocument.setUri(JDTUtils.toURI(cu));
		parms.setTextDocument(textDocument);
		parms.setRange(range);
		CodeActionContext context = new CodeActionContext();
		context.setDiagnostics(DiagnosticsHandler.toDiagnosticsArray(cu, Arrays.asList(problems), true, JDTUtils.toURI(cu)));
		context.setOnly(onlyKinds);
		parms.setContext(context);

		List<Either<Command, CodeAction>> codeActions = new CodeActionHandler(this.preferenceManager).getCodeActionCommands(parms, new NullProgressMonitor());
		if (onlyKinds != null && !onlyKinds.isEmpty()) {
			for (Either<Command, CodeAction> codeAction : codeActions) {
				Stream<String> acceptedActionKinds = onlyKinds.stream();
				String kind = codeAction.getRight().getKind();
				assertTrue(codeAction.getRight().getTitle() + " has kind " + kind + " but only " + onlyKinds + " are accepted", acceptedActionKinds.filter(k -> kind != null && kind.startsWith(k)).findFirst().isPresent());
			}
		}

		if (this.ignoredKinds != null) {
			List<Either<Command, CodeAction>> filteredList = codeActions.stream().filter(Either::isRight).filter(codeAction -> {
				for (String str : this.ignoredKinds) {
					if (codeAction.getRight().getKind().matches(str)) {
						return true;
					}
				}
				return false;
			}).collect(Collectors.toList());
			codeActions.removeAll(filteredList);
		}

		if (this.ignoredCommands != null) {
			List<Either<Command, CodeAction>> filteredList = new ArrayList<>();
			for (Either<Command, CodeAction> codeAction : codeActions) {
				for (String str : this.ignoredCommands) {
					if (getTitle(codeAction).matches(str)) {
						filteredList.add(codeAction);
						break;
					}
				}
			}
			codeActions.removeAll(filteredList);
		}
		return codeActions;
	}

	protected String evaluateCodeActionCommand(Either<Command, CodeAction> codeAction)
			throws BadLocationException, JavaModelException {

		Command c = codeAction.isLeft() ? codeAction.getLeft() : codeAction.getRight().getCommand();
		Assert.assertEquals(CodeActionHandler.COMMAND_ID_APPLY_EDIT, c.getCommand());
		Assert.assertNotNull(c.getArguments());
		Assert.assertTrue(c.getArguments().get(0) instanceof WorkspaceEdit);
		WorkspaceEdit we = (WorkspaceEdit) c.getArguments().get(0);
		return evaluateWorkspaceEdit(we);
	}

	public static String evaluateWorkspaceEdit(WorkspaceEdit edit) throws JavaModelException, BadLocationException {
		if (edit == null) {
			return null;
		}

		if (edit.getDocumentChanges() != null) {
			return ResourceUtils.dos2Unix(evaluateChanges(edit.getDocumentChanges()));
		}
		return ResourceUtils.dos2Unix(evaluateChanges(edit.getChanges()));
	}

	public static String evaluateChanges(List<Either<TextDocumentEdit, ResourceOperation>> documentChanges) throws BadLocationException, JavaModelException {
		List<TextDocumentEdit> changes = documentChanges.stream().filter(e -> e.isLeft()).map(e -> e.getLeft()).collect(Collectors.toList());
		assertFalse("No edits generated", changes.isEmpty());
		Set<String> uris = changes.stream().map(tde -> tde.getTextDocument().getUri()).distinct().collect(Collectors.toSet());
		assertEquals("Only one resource should be modified", 1, uris.size());
		String uri = uris.iterator().next();
		List<TextEdit> edits = changes.stream().flatMap(e -> e.getEdits().stream()).collect(Collectors.toList());
		return ResourceUtils.dos2Unix(evaluateChanges(uri, edits));
	}

	public static String evaluateChanges(Map<String, List<TextEdit>> changes) throws BadLocationException, JavaModelException {
		Iterator<Entry<String, List<TextEdit>>> editEntries = changes.entrySet().iterator();
		Entry<String, List<TextEdit>> entry = editEntries.next();
		assertNotNull("No edits generated", entry);
		assertEquals("More than one resource modified", false, editEntries.hasNext());
		return ResourceUtils.dos2Unix(evaluateChanges(entry.getKey(), entry.getValue()));
	}

	private static String evaluateChanges(String uri, List<TextEdit> edits) throws BadLocationException, JavaModelException {
		assertFalse("No edits generated: " + edits, edits == null || edits.isEmpty());
		ICompilationUnit cu = JDTUtils.resolveCompilationUnit(uri);
		assertNotNull("CU not found: " + uri, cu);
		Document doc = new Document();
		if (cu.exists()) {
			doc.set(cu.getSource());
		}
		return ResourceUtils.dos2Unix(TextEditUtil.apply(doc, edits));
	}

	public Command getCommand(Either<Command, CodeAction> codeAction) {
		return codeAction.isLeft() ? codeAction.getLeft() : codeAction.getRight().getCommand();
	}

	public String getTitle(Either<Command, CodeAction> codeAction) {
		return ResourceUtils.dos2Unix(getCommand(codeAction).getTitle());
	}

}
