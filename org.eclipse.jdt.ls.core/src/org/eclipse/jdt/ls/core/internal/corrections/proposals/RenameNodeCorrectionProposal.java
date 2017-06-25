/*******************************************************************************
 * Copyright (c) 2000, 2012 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Copied from /org.eclipse.jdt.ui/src/org/eclipse/jdt/internal/ui/text/correction/proposals/RenameNodeCorrectionProposal.java
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal.corrections.proposals;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.ls.core.internal.SharedASTProvider;
import org.eclipse.jdt.ls.core.internal.corext.dom.LinkedNodeFinder;
import org.eclipse.jface.text.IDocument;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;

public class RenameNodeCorrectionProposal extends CUCorrectionProposal {

	private String fNewName;
	private int fOffset;
	private int fLength;

	public RenameNodeCorrectionProposal(String name, ICompilationUnit cu, int offset, int length, String newName, int relevance) {
		super(name, cu, relevance);
		fOffset= offset;
		fLength= length;
		fNewName= newName;
	}

	@Override
	protected void addEdits(IDocument doc, TextEdit root) throws CoreException {
		super.addEdits(doc, root);

		// build a full AST
		CompilationUnit unit = SharedASTProvider.getInstance().getAST(getCompilationUnit(), null);

		ASTNode name= NodeFinder.perform(unit, fOffset, fLength);
		if (name instanceof SimpleName) {

			SimpleName[] names= LinkedNodeFinder.findByProblems(unit, (SimpleName) name);
			if (names != null) {
				for (int i= 0; i < names.length; i++) {
					SimpleName curr= names[i];
					root.addChild(new ReplaceEdit(curr.getStartPosition(), curr.getLength(), fNewName));
				}
				return;
			}
		}
		root.addChild(new ReplaceEdit(fOffset, fLength, fNewName));
	}
}
