/*******************************************************************************
 * Copyright (c) 2020 Microsoft Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Microsoft Corporation - initial API and implementation
 *     0dinD - Semantic highlighting improvements - https://github.com/eclipse/eclipse.jdt.ls/pull/1501
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal.semantictokens;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeMemberDeclaration;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.StructuralPropertyDescriptor;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.TypeParameter;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.lsp4j.SemanticTokenModifiers;

public enum TokenModifier {
	// Standard LSP token modifiers, see https://microsoft.github.io/language-server-protocol/specifications/specification-current/#textDocument_semanticTokens
	ABSTRACT(SemanticTokenModifiers.Abstract),
	STATIC(SemanticTokenModifiers.Static),
	FINAL(SemanticTokenModifiers.Readonly),
	DEPRECATED(SemanticTokenModifiers.Deprecated),
	DECLARATION(SemanticTokenModifiers.Declaration),
	DOCUMENTATION(SemanticTokenModifiers.Documentation),

	// Custom token modifiers
	PUBLIC("public"),
	PRIVATE("private"),
	PROTECTED("protected"),
	NATIVE("native"),
	GENERIC("generic"),
	TYPE_ARGUMENT("typeArgument"),
	IMPORT_DECLARATION("importDeclaration");

	/**
	 * This is the name of the token modifier given to the client, so it
	 * should be as generic as possible and follow the standard LSP (see below)
	 * token modifier names where applicable. For example, the generic name of the
	 * {@link #FINAL} modifier is "readonly", since it has similar meaning.
	 * Using standardized names makes life easier for theme authors, since
	 * they don't need to know about language-specific terminology.
	 *
	 * @see https://microsoft.github.io/language-server-protocol/specifications/specification-current/#textDocument_semanticTokens
	 */
	private final String genericName;

	/**
	 * The bitmask for this semantic token modifier.
	 * Use bitwise OR to combine with other token modifiers.
	 */
	public final int bitmask = 1 << ordinal();

	/**
	 * The inverse bitmask for this semantic token modifier.
	 * Use bitwise AND to remove from other token modifiers.
	 */
	public final int inverseBitmask = ~bitmask;

	TokenModifier(String genericName) {
		this.genericName = genericName;
	}

	@Override
	public String toString() {
		return genericName;
	}

	/**
	 * Returns the bitwise OR of all the semantic token modifiers that can
	 * be easily figured out from a given binding. No modifiers (the value 0)
	 * are returned if the binding is {@code null}.
	 *
	 * @param binding A binding.
	 * @return The bitwise OR of the applicable modifiers for the binding.
	 */
	public static int getApplicableModifiers(IBinding binding) {
		if (binding == null) {
			return 0;
		}

		int modifiers = 0;
		int bindingModifiers = binding.getModifiers();
		if (Modifier.isPublic(bindingModifiers)) {
			modifiers |= PUBLIC.bitmask;
		}
		if (Modifier.isPrivate(bindingModifiers)) {
			modifiers |= PRIVATE.bitmask;
		}
		if (Modifier.isProtected(bindingModifiers)) {
			modifiers |= PROTECTED.bitmask;
		}
		if (Modifier.isAbstract(bindingModifiers)) {
			modifiers |= ABSTRACT.bitmask;
		}
		if (Modifier.isStatic(bindingModifiers)) {
			modifiers |= STATIC.bitmask;
		}
		if (Modifier.isFinal(bindingModifiers)) {
			modifiers |= FINAL.bitmask;
		}
		if (Modifier.isNative(bindingModifiers)) {
			modifiers |= NATIVE.bitmask;
		}
		if (binding.isDeprecated()) {
			modifiers |= DEPRECATED.bitmask;
		}
		return modifiers;
	}

	/**
	 * Returns whether or not a given binding corresponds to
	 * a generic (or parameterized) type or method. {@code false}
	 * is returned if the binding is {@code null}.
	 *
	 * @param binding A binding.
	 * @return {@code true} if the binding corresponds to a generic
	 * type or method, {@code false} otherwise.
	 */
	public static boolean isGeneric(IBinding binding) {
		if (binding == null) {
			return false;
		}

		switch (binding.getKind()) {
			case IBinding.TYPE: {
				return isGeneric((ITypeBinding) binding);
			}
			case IBinding.METHOD: {
				return isGeneric((IMethodBinding) binding);
			}
			default:
			return false;
		}
	}

	/**
	 * Returns whether or not a given type binding corresponds to
	 * a generic (or parameterized) type. {@code false}
	 * is returned if the type binding is {@code null}.
	 *
	 * @param typeBinding A type binding.
	 * @return {@code true} if the type binding corresponds to a generic
	 * type, {@code false} otherwise.
	 */
	public static boolean isGeneric(ITypeBinding typeBinding) {
		if (typeBinding == null) {
			return false;
		}

		return typeBinding.isGenericType() || typeBinding.isParameterizedType();
	}

	/**
	 * Returns whether or not a given method binding corresponds to
	 * a generic (or parameterized) method. {@code false}
	 * is returned if the method binding is {@code null}.
	 *
	 * @param methodBinding A method binding.
	 * @return {@code true} if the method binding corresponds to a generic
	 * method, {@code false} otherwise.
	 */
	public static boolean isGeneric(IMethodBinding methodBinding) {
		if (methodBinding == null) {
			return false;
		}

		return methodBinding.isGenericMethod() || methodBinding.isParameterizedMethod();
	}

	/**
	* Returns whether a simple name represents a name that is being defined,
	* as opposed to one being referenced. This method behaves exactly like
	* {@link SimpleName#isDeclaration()} except that it also returns true
	* for constructor declarations.
	*
	* @param simpleName A simple name.
	* @return {@code true} if this node declares a name, and {@code false} otherwise.
	*
	* @see SimpleName#isDeclaration()
	*/
	public static boolean isDeclaration(SimpleName simpleName) {
		StructuralPropertyDescriptor d = simpleName.getLocationInParent();
		if (d == null) {
			return false;
		}
		ASTNode parent = simpleName.getParent();
		if (parent instanceof TypeDeclaration) {
			return (d == TypeDeclaration.NAME_PROPERTY);
		}
		if (parent instanceof MethodDeclaration) {
			return (d == MethodDeclaration.NAME_PROPERTY);
		}
		if (parent instanceof SingleVariableDeclaration) {
			return (d == SingleVariableDeclaration.NAME_PROPERTY);
		}
		if (parent instanceof VariableDeclarationFragment) {
			return (d == VariableDeclarationFragment.NAME_PROPERTY);
		}
		if (parent instanceof EnumDeclaration) {
			return (d == EnumDeclaration.NAME_PROPERTY);
		}
		if (parent instanceof EnumConstantDeclaration) {
			return (d == EnumConstantDeclaration.NAME_PROPERTY);
		}
		if (parent instanceof TypeParameter) {
			return (d == TypeParameter.NAME_PROPERTY);
		}
		if (parent instanceof AnnotationTypeDeclaration) {
			return (d == AnnotationTypeDeclaration.NAME_PROPERTY);
		}
		if (parent instanceof AnnotationTypeMemberDeclaration) {
			return (d == AnnotationTypeMemberDeclaration.NAME_PROPERTY);
		}
		return false;
	}
}
