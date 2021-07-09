/*******************************************************************************
 * Copyright (c) 2019 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Copied from /org.eclipse.jdt.ui/src/org/eclipse/jdt/internal/ui/text/correction/UnInitializedFinalFieldSubProcessor.java
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Renaud Waldura &lt;renaud+eclipse@waldura.com&gt; - New class/interface with wizard
 *     Rabea Gransberger <rgransberger@gmx.de> - [quick fix] Fix several visibility issues - https://bugs.eclipse.org/394692
 *     Jens Reimann <jreimann@redhat.com> Bug 38201: [quick assist] Allow creating abstract method - https://bugs.eclipse.org/38201
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal.corrections.proposals;

import java.util.Collection;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.internal.core.manipulation.dom.ASTResolving;
import org.eclipse.jdt.internal.ui.text.correction.IProblemLocationCore;
import org.eclipse.jdt.ls.core.internal.corrections.IInvocationContext;

public class UnInitializedFinalFieldSubProcessor {

	public static void getProposals(IInvocationContext context, IProblemLocationCore problem, Collection<ChangeCorrectionProposal> proposals) throws CoreException {
		ICompilationUnit cu= context.getCompilationUnit();

		CompilationUnit astRoot= context.getASTRoot();
		ASTNode selectedNode= problem.getCoveringNode(astRoot);

		if (selectedNode == null) {
			return;
		}

		int type= selectedNode.getNodeType();
		if (type == ASTNode.METHOD_DECLARATION) {
			// propose add initialization to constructor
			IMethodBinding targetBinding= null;
			MethodDeclaration node= (MethodDeclaration) selectedNode;
			if (!node.isConstructor()) {
				return;
			}
			IMethodBinding binding= node.resolveBinding();
			if (binding != null) {
				targetBinding= binding;
			} else {
				return;
			}
			ITypeBinding targetDecl= targetBinding.getDeclaringClass();
			ICompilationUnit targetCU= ASTResolving.findCompilationUnitForBinding(cu, astRoot, targetDecl);

			proposals.add(new InitializeFinalFieldProposal(problem, targetCU, node, IProposalRelevance.CREATE_CONSTRUCTOR, InitializeFinalFieldProposal.UPDATE_AT_CONSTRUCTOR));

			InitializeFinalFieldProposal initializeFinalFieldProposal= new InitializeFinalFieldProposal(problem, targetCU, node, IProposalRelevance.CREATE_CONSTRUCTOR, InitializeFinalFieldProposal.UPDATE_CONSTRUCTOR_NEW_PARAMETER);
			if(initializeFinalFieldProposal.hasProposal()) {
				proposals.add(initializeFinalFieldProposal);
			}

		} else if (type == ASTNode.SIMPLE_NAME) {
			// propose add initialization at declaration
			IVariableBinding targetBinding= null;
			SimpleName node= (SimpleName) selectedNode;
			IBinding binding= node.resolveBinding();
			if (binding instanceof IVariableBinding) {
				targetBinding= (IVariableBinding) binding;
			} else {
				return;
			}
			ITypeBinding targetDecl= targetBinding.getDeclaringClass();
			ICompilationUnit targetCU= ASTResolving.findCompilationUnitForBinding(cu, astRoot, targetDecl);

			proposals.add(new InitializeFinalFieldProposal(problem, targetCU, node, targetBinding, IProposalRelevance.CREATE_CONSTRUCTOR));
		}
	}

	private UnInitializedFinalFieldSubProcessor() {
	}
}
