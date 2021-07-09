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

import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.lsp4j.SemanticTokenTypes;

public enum TokenType {
	// Standard LSP token types, see https://microsoft.github.io/language-server-protocol/specifications/specification-current/#textDocument_semanticTokens
	NAMESPACE(SemanticTokenTypes.Namespace),
	CLASS(SemanticTokenTypes.Class),
	INTERFACE(SemanticTokenTypes.Interface),
	ENUM(SemanticTokenTypes.Enum),
	ENUM_MEMBER(SemanticTokenTypes.EnumMember),
	TYPE(SemanticTokenTypes.Type),
	TYPE_PARAMETER(SemanticTokenTypes.TypeParameter),
	METHOD(SemanticTokenTypes.Method),
	PROPERTY(SemanticTokenTypes.Property),
	VARIABLE(SemanticTokenTypes.Variable),
	PARAMETER(SemanticTokenTypes.Parameter),
	MODIFIER(SemanticTokenTypes.Modifier),
	KEYWORD(SemanticTokenTypes.Keyword),

	// Custom token types
	ANNOTATION("annotation"),
	ANNOTATION_MEMBER("annotationMember");

	/**
	 * This is the name of the token type given to the client, so it
	 * should be as generic as possible and follow the standard LSP (see below)
	 * token type names where applicable. For example, the generic name of the
	 * {@link #PACKAGE} type is "namespace", since it has similar meaning.
	 * Using standardized names makes life easier for theme authors, since
	 * they don't need to know about language-specific terminology.
	 *
	 * @see https://microsoft.github.io/language-server-protocol/specifications/specification-current/#textDocument_semanticTokens
	 */
	private String genericName;

	TokenType(String genericName) {
		this.genericName = genericName;
	}

	@Override
	public String toString() {
		return genericName;
	}

	/**
	* Returns the semantic token type that applies to a binding, or
	* {@code null} if there is no token type that applies to the binding.
	*
	* @param binding A binding.
	* @return The semantic token type that applies to the binding, or
	* {@code null} if there is no token type that applies to the binding.
	*/
	public static TokenType getApplicableType(IBinding binding) {
		if (binding == null) {
			return null;
		}

		switch (binding.getKind()) {
			case IBinding.VARIABLE: {
				IVariableBinding variableBinding = (IVariableBinding) binding;
				if (variableBinding.isEnumConstant()) {
					return TokenType.ENUM_MEMBER;
				}
				if (variableBinding.isField()) {
					return TokenType.PROPERTY;
				}
				if (variableBinding.isParameter()) {
					return TokenType.PARAMETER;
				}
				return TokenType.VARIABLE;
			}
			case IBinding.METHOD: {
				IMethodBinding methodBinding = (IMethodBinding) binding;
				if (methodBinding.isAnnotationMember()) {
					return TokenType.ANNOTATION_MEMBER;
				}
				return TokenType.METHOD;
			}
			case IBinding.TYPE: {
				ITypeBinding typeBinding = (ITypeBinding) binding;
				if (typeBinding.isTypeVariable()) {
					return TokenType.TYPE_PARAMETER;
				}
				if (typeBinding.isAnnotation()) {
					return TokenType.ANNOTATION;
				}
				if (typeBinding.isClass()) {
					return TokenType.CLASS;
				}
				if (typeBinding.isInterface()) {
					return TokenType.INTERFACE;
				}
				if (typeBinding.isEnum()) {
					return TokenType.ENUM;
				}
				return TokenType.TYPE;
			}
			case IBinding.PACKAGE:
			case IBinding.MODULE: {
				return TokenType.NAMESPACE;
			}
			default:
			return null;
		}
	}
}
