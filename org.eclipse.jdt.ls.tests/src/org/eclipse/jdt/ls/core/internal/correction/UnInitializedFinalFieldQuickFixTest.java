/*******************************************************************************
 * Copyright (c) 2020 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Red Hat Inc. - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal.correction;

import static org.junit.Assert.assertNotNull;

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.ls.core.internal.JavaCodeActionKind;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.Before;
import org.junit.Test;

public class UnInitializedFinalFieldQuickFixTest extends AbstractQuickFixTest {

	private IJavaProject javaProject;
	private IPackageFragmentRoot sourceFolder;

	@Before
	public void setup() throws Exception {
		javaProject = newEmptyProject();
		Map<String, String> options = TestOptions.getDefaultOptions();
		options.put(JavaCore.COMPILER_PB_NO_EFFECT_ASSIGNMENT, JavaCore.IGNORE);
		options.put(JavaCore.COMPILER_PB_INDIRECT_STATIC_ACCESS, JavaCore.ERROR);
		javaProject.setOptions(options);
		sourceFolder = javaProject.getPackageFragmentRoot(javaProject.getProject().getFolder("src"));
		setIgnoredKind(CodeActionKind.Refactor, JavaCodeActionKind.QUICK_ASSIST, JavaCodeActionKind.SOURCE_OVERRIDE_METHODS, JavaCodeActionKind.SOURCE_GENERATE_TO_STRING, JavaCodeActionKind.SOURCE_GENERATE_CONSTRUCTORS,
				JavaCodeActionKind.SOURCE_GENERATE_FINAL_MODIFIERS, JavaCodeActionKind.SOURCE_GENERATE_ACCESSORS, JavaCodeActionKind.REFACTOR_EXTRACT_FIELD, JavaCodeActionKind.REFACTOR_EXTRACT_VARIABLE,
				JavaCodeActionKind.REFACTOR_INTRODUCE_PARAMETER, CodeActionKind.RefactorInline);
	}

	@Test
	public void testUnInitializedFinalField() throws Exception {
		IPackageFragment pack1 = sourceFolder.createPackageFragment("org.sample", false, null);
		StringBuilder buf = new StringBuilder();
		buf.append("package org.sample;\n");
		buf.append("\n");
		buf.append("public class Foo {\n");
		buf.append("    static final int i;\n");
		buf.append("    final int j;\n");
		buf.append("    static {\n");
		buf.append("        assert (i = 0) == 0;\n");
		buf.append("        System.out.println(i);\n");
		buf.append("    }\n");
		buf.append("\n");
		buf.append("    public Foo() {\n");
		buf.append("    }\n");
		buf.append("}\n");
		ICompilationUnit cu = pack1.createCompilationUnit("Foo.java", buf.toString(), false, null);
		List<Either<Command, CodeAction>> codeActions = evaluateCodeActions(cu);
		Either<Command, CodeAction> codeAction = codeActions.stream().filter(c -> getTitle(c).matches("Initialize final field 'i' in constructor.")).findFirst().orElse(null);
		assertNotNull(codeAction);
		buf = new StringBuilder();
		buf.append("package org.sample;\n");
		buf.append("\n");
		buf.append("public class Foo {\n");
		buf.append("    static final int i = 0;\n");
		buf.append("    final int j;\n");
		buf.append("    static {\n");
		buf.append("        assert (i = 0) == 0;\n");
		buf.append("        System.out.println(i);\n");
		buf.append("    }\n");
		buf.append("\n");
		buf.append("    public Foo() {\n");
		buf.append("    }\n");
		buf.append("}\n");
		Expected expected = new Expected("Initialize final field 'i' in constructor.", buf.toString(), CodeActionKind.QuickFix);
		Range range = new Range(new Position(4, 22), new Position(4, 22));
		assertCodeActions(cu, range, expected);
	}

}
