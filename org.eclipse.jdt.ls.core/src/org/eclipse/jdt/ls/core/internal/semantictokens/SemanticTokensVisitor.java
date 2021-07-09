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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Comment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ExportsDirective;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.IPackageBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.ModuleDeclaration;
import org.eclipse.jdt.core.dom.ModulePackageAccess;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NameQualifiedType;
import org.eclipse.jdt.core.dom.OpensDirective;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.QualifiedType;
import org.eclipse.jdt.core.dom.RequiresDirective;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.TagElement;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeLiteral;
import org.eclipse.lsp4j.SemanticTokens;

public class SemanticTokensVisitor extends ASTVisitor {
	private CompilationUnit cu;
	private List<SemanticToken> tokens;

	public SemanticTokensVisitor(CompilationUnit cu) {
		super(true);
		this.cu = cu;
		this.tokens = new ArrayList<>();
	}

	private class SemanticToken {
		private final TokenType tokenType;
		private final int tokenModifiers;
		private final int offset;
		private final int length;

		public SemanticToken(int offset, int length, TokenType tokenType, int tokenModifiers) {
			this.offset = offset;
			this.length = length;
			this.tokenType = tokenType;
			this.tokenModifiers = tokenModifiers;
		}

		public TokenType getTokenType() {
			return tokenType;
		}

		public int getTokenModifiers() {
			return tokenModifiers;
		}

		public int getOffset() {
			return offset;
		}

		public int getLength() {
			return length;
		}
	}

	public SemanticTokens getSemanticTokens() {
		return new SemanticTokens(encodedTokens());
	}

	private List<Integer> encodedTokens() {
		int numTokens = tokens.size();
		List<Integer> data = new ArrayList<>(numTokens * 5);
		int currentLine = 0;
		int currentColumn = 0;
		for (int i = 0; i < numTokens; i++) {
			SemanticToken token = tokens.get(i);
			int line = cu.getLineNumber(token.getOffset()) - 1;
			int column = cu.getColumnNumber(token.getOffset());
			int deltaLine = line - currentLine;
			if (deltaLine != 0) {
				currentLine = line;
				currentColumn = 0;
			}
			int deltaColumn = column - currentColumn;
			currentColumn = column;
			// Disallow duplicate/conflict token (if exists)
			if (deltaLine != 0 || deltaColumn != 0) {
				int tokenTypeIndex = token.getTokenType().ordinal();
				int tokenModifiers = token.getTokenModifiers();

				data.add(deltaLine);
				data.add(deltaColumn);
				data.add(token.getLength());
				data.add(tokenTypeIndex);
				data.add(tokenModifiers);
			}
		}
		return data;
	}

	/**
	 * "Static" modifiers which are always added by {@link #addToken(int, int, TokenType, int)}.
	 * Modifiers can be set or removed at any time during the visitation process, and as such
	 * will be applied for all nodes visited during the time a static modifier is set.
	 *
	 * Setting and removing the modifiers is done via bitwise OR/AND, see
	 * {@link TokenModifier#bitmask} and {@link TokenModifier#inverseBitmask}
	 */
	private int staticModifiers = 0;

	/**
	 * Adds a semantic token to the list of tokens being collected by this
	 * semantic token visitor.
	 *
	 * @param offset The document offset of the semantic token.
	 * @param length The length of the semantic token.
	 * @param tokenType The type of the semantic token.
	 * @param modifiers The bitwise OR of the semantic token modifiers, see {@link TokenModifier#bitmask}.
	 *
	 * @apiNote This method is order-dependent because of {@link #encodedTokens()}.
	 * If semantic tokens are not added in the order they appear in the document,
	 * the encoding algorithm might discard them.
	 */
	private void addToken(int offset, int length, TokenType tokenType, int modifiers) {
		tokens.add(new SemanticToken(offset, length, tokenType, modifiers | staticModifiers));
	}

	/**
	 * Adds a semantic token to the list of tokens being collected by this
	 * semantic token visitor.
	 *
	 * @param node The AST node representing the location of the semantic token.
	 * @param tokenType The type of the semantic token.
	 * @param modifiers The bitwise OR of the semantic token modifiers, see {@link TokenModifier#bitmask}.
	 *
	 * @apiNote This method is order-dependent because of {@link #encodedTokens()}.
	 * If semantic tokens are not added in the order they appear in the document,
	 * the encoding algorithm might discard them.
	 */
	private void addToken(ASTNode node, TokenType tokenType, int modifiers) {
		addToken(node.getStartPosition(), node.getLength(), tokenType, modifiers);
	}

	/**
	 * Adds a semantic token to the list of tokens being collected by this
	 * semantic token visitor.
	 *
	 * @param node The AST node representing the location of the semantic token.
	 * @param tokenType The type of the semantic token.
	 *
	 * @apiNote This method is order-dependent because of {@link #encodedTokens()}.
	 * If semantic tokens are not added in the order they appear in the document,
	 * the encoding algorithm might discard them.
	 */
	private void addToken(ASTNode node, TokenType tokenType) {
		addToken(node, tokenType, 0);
	}

	@Override
	public boolean visit(TypeLiteral node) {
		acceptNode(node.getType());
		// Don't add "class" keyword token for recovered type literals
		if ((node.getFlags() & ASTNode.RECOVERED) == 0) {
			// The last 5 characters of a TypeLiteral are the "class" keyword
			int offset = node.getStartPosition() + node.getLength() - 5;
			addToken(offset, 5, TokenType.KEYWORD, 0);
		}
		return false;
	}

	@Override
	public boolean visit(Javadoc node) {
		staticModifiers |= TokenModifier.DOCUMENTATION.bitmask;
		return super.visit(node);
	}

	@Override
	public void endVisit(Javadoc node) {
		staticModifiers &= TokenModifier.DOCUMENTATION.inverseBitmask;
		super.endVisit(node);
	}

	@Override
	public boolean visit(TagElement node) {
		if (node.getTagName() != null) {
			// If the token is nested, we need to skip the { character
			int offset = node.isNested() ? node.getStartPosition() + 1 : node.getStartPosition();
			addToken(offset, node.getTagName().length(), TokenType.KEYWORD, 0);
		}
		acceptNodeList(node.fragments());
		return false;
	}

	@Override
	public boolean visit(PackageDeclaration node) {
		acceptNode(node.getJavadoc());
		acceptNodeList(node.annotations());
		addTokenToSimpleNamesOfName(node.getName(), TokenType.NAMESPACE);
		return false;
	}

	@Override
	public boolean visit(ImportDeclaration node) {
		staticModifiers |= TokenModifier.IMPORT_DECLARATION.bitmask;

		IBinding binding = node.resolveBinding();
		if (binding == null || binding instanceof IPackageBinding) {
			addTokenToSimpleNamesOfName(node.getName(), TokenType.NAMESPACE);
		}
		else {
			nonPackageNameOfImportDeclarationVisitor(node.getName());
		}

		staticModifiers &= TokenModifier.IMPORT_DECLARATION.inverseBitmask;
		return false;
	}

	/**
	 * {@link NodeVisitor} for {@link Name} nodes inside an import declaration that are not package names.
	 *
	 * @param nonPackageName
	 */
	private void nonPackageNameOfImportDeclarationVisitor(Name nonPackageName) {
		if (nonPackageName instanceof SimpleName) {
			nonPackageName.accept(this);
		}
		else {
			QualifiedName qualifiedName = (QualifiedName) nonPackageName;
			Name qualifier = qualifiedName.getQualifier();

			if (hasPackageQualifier(qualifiedName)) {
				addTokenToSimpleNamesOfName(qualifier, TokenType.NAMESPACE);
			}
			else {
				nonPackageNameOfImportDeclarationVisitor(qualifier);
			}

			qualifiedName.getName().accept(this);
		}
	}

	/**
	 * Returns whether a qualified name has a package name as its qualifier,
	 * even if the qualifier's binding cannot be resolved.
	 * This is in order to work around an issue where package names in import statements
	 * cannot have their bindings resolved when their corresponding package is not explicitely
	 * exported and is part of a module.
	 *
	 * @param qualifiedName A qualified name.
	 * @return {@code true} if the qualified name has a package name as its qualifier,
	 * {@code false} otherwise.
	 */
	private boolean hasPackageQualifier(QualifiedName qualifiedName) {
		IBinding qualifierBinding = qualifiedName.getQualifier().resolveBinding();
		if (qualifierBinding != null) {
			return qualifierBinding instanceof IPackageBinding;
		}
		else {
			IBinding binding = qualifiedName.resolveBinding();
			return binding instanceof IPackageBinding || binding instanceof ITypeBinding;
		}
	}

	@Override
	public boolean visit(Modifier node) {
		addToken(node, TokenType.MODIFIER);
		return false;
	}

	@Override
	public boolean visit(SimpleName node) {
		IBinding binding = node.resolveBinding();
		TokenType tokenType = TokenType.getApplicableType(binding);
		if (tokenType != null) {
			int modifiers = TokenModifier.getApplicableModifiers(binding);
			if (TokenModifier.isGeneric(binding)) {
				modifiers |= TokenModifier.GENERIC.bitmask;
			}
			if (TokenModifier.isDeclaration(node)) {
				modifiers |= TokenModifier.DECLARATION.bitmask;
			}
			addToken(node, tokenType, modifiers);
		}

		return false;
	}

	@Override
	public boolean visit(ModuleDeclaration node) {
		acceptJavdocOfModuleDeclaration(node);
		acceptNodeList(node.annotations());
		addTokenToSimpleNamesOfName(node.getName(), TokenType.NAMESPACE);
		acceptNodeList(node.moduleStatements());
		return false;
	}

	/**
	 * Accepts into the Javadoc of a module declaration.
	 * This method attempts to work around an issue where {@link ModuleDeclaration#getJavadoc()}
	 * always returns {@code null}, even if there is an associated Javadoc comment. This is done by
	 * retrieving the Javadoc from {@link CompilationUnit#getCommentList()} instead.
	 * This method should be removed once the compiler supports resolving the Javadoc
	 * for module declarations.
	 *
	 * @param moduleDeclaration The module declaration.
	 */
	private void acceptJavdocOfModuleDeclaration(ModuleDeclaration moduleDeclaration) {
		for (Comment comment : this.<Comment>castNodeList(cu.getCommentList())) {
			// The start position of the module declaration still includes the Javadoc,
			// so we just need to look for a comment with the same start position.
			if (comment.getStartPosition() == moduleDeclaration.getStartPosition()) {
				comment.accept(this);
				break;
			}
		}
	}

	@Override
	public boolean visit(RequiresDirective node) {
		addTokenToSimpleNamesOfName(node.getName(), TokenType.NAMESPACE);
		return false;
	}

	@Override
	public boolean visit(ExportsDirective node) {
		modulePackageAccessVisitor(node);
		return false;
	}

	@Override
	public boolean visit(OpensDirective node) {
		modulePackageAccessVisitor(node);
		return false;
	}

	/**
	 * {@link NodeVisitor} for {@link ModulePackageAccess} nodes.
	 *
	 * @param modulePackageAccess
	 */
	private void modulePackageAccessVisitor(ModulePackageAccess modulePackageAccess) {
		acceptNode(modulePackageAccess.getName());
		this.<Name>visitNodeList(modulePackageAccess.modules(), module -> {
			addTokenToSimpleNamesOfName(module, TokenType.NAMESPACE);
		});
	}

	@Override
	public boolean visit(ParameterizedType node) {
		acceptNode(node.getType());
		visitNodeList(node.typeArguments(), this::typeArgumentVisitor);
		return false;
	}

	@Override
	public boolean visit(MethodInvocation node) {
		acceptNode(node.getExpression());
		visitNodeList(node.typeArguments(), this::typeArgumentVisitor);
		acceptNode(node.getName());
		acceptNodeList(node.arguments());
		return false;
	}

	/**
	 * {@link NodeVisitor} for {@link Type} nodes that are type arguments.
	 *
	 * @param typeArgument
	 */
	private void typeArgumentVisitor(Type typeArgument) {
		visitSimpleNameOfType(typeArgument, (simpleName) -> {
			IBinding binding = simpleName.resolveBinding();
			TokenType tokenType = TokenType.getApplicableType(binding);
			if (tokenType != null) {
				int modifiers = TokenModifier.getApplicableModifiers(binding);
				if (TokenModifier.isGeneric((ITypeBinding) binding)) {
					modifiers |= TokenModifier.GENERIC.bitmask;
				}
				modifiers |= TokenModifier.TYPE_ARGUMENT.bitmask;
				addToken(simpleName, tokenType, modifiers);
			}
		});
	}

	@Override
	public boolean visit(ClassInstanceCreation node) {
		acceptNode(node.getExpression());
		visitNodeList(node.typeArguments(), this::typeArgumentVisitor);

		visitSimpleNameOfType(node.getType(), (simpleName) -> {
			IMethodBinding constructorBinding = node.resolveConstructorBinding();
			int modifiers = TokenModifier.getApplicableModifiers(constructorBinding);
			if (TokenModifier.isGeneric(constructorBinding) || node.getType().isParameterizedType()) {
				modifiers |= TokenModifier.GENERIC.bitmask;
			}
			addToken(simpleName, TokenType.METHOD, modifiers);
		});

		acceptNodeList(node.arguments());
		acceptNode(node.getAnonymousClassDeclaration());
		return false;
	}

	/**
	 * A node visitor may be used by helpers like {@link #visitSimpleNameOfType(Name, NodeVisitor)},
	 * and is responsible for the visitation logic of a special kind of node.
	 */
	@FunctionalInterface
	private interface NodeVisitor<T extends ASTNode> {
		public void visit(T node);
	}

	/**
	 * Helper method to make an unchecked cast from a raw list of AST nodes
	 * to a generic list of a certain type. The caller is responsible for
	 * making sure that the cast is safe. An example use case is for
	 * {@link CompilationUnit#getCommentList()}, which returns a raw list,
	 * but whose Javadoc states that the element type is {@link Comment}.
	 *
	 * @param <T> The type of {@link ASTNode} expected in the node list.
	 * @param nodeList The node list to be cast.
	 * @return The cast node list.
	 */
	@SuppressWarnings("unchecked")
	private <T extends ASTNode> List<T> castNodeList(List<?> nodeList) {
		return (List<T>) nodeList;
	}

	/**
	 * Helper method which visits the nodes in a raw list of AST nodes
	 * using a {@link NodeVisitor}. An unchecked cast is made via
	 * {@link #castNodeList(List)}, so the caller is responsible for
	 * making sure that the cast is safe.
	 *
	 * @param <T> The type of {@link ASTNode} expected in the node list.
	 * @param nodeList The node list to visit (may be {@code null}).
	 * @param visitor A {@link NodeVisitor}.
	 */
	private <T extends ASTNode> void visitNodeList(List<?> nodeList, NodeVisitor<T> visitor) {
		if (nodeList != null) {
			for (T node : this.<T>castNodeList(nodeList)) {
				visitor.visit(node);
			}
		}
	}

	/**
	 * Helper method which accepts into the nodes in a raw list of AST nodes.
	 * An unchecked cast is made via {@link #castNodeList(List)},
	 * so the caller is responsible for making sure that the cast is safe.
	 *
	 * @param nodeList The node list to accept into (may be {@code null}).
	 */
	private void acceptNodeList(List<?> nodeList) {
		if (nodeList != null) {
			for (ASTNode node : this.<ASTNode>castNodeList(nodeList)) {
				node.accept(this);
			}
		}
	}

	/**
	 * Helper method which performs a null-check on and then accepts into an AST node.
	 *
	 * @param node The AST node to accept into (may be {@code null}).
	 */
	private void acceptNode(ASTNode node) {
		if (node != null) {
			node.accept(this);
		}
	}

	/**
	 * Helper method to recursively visit all the simple names of a {@link Name} node
	 * using the specified {@link NodeVisitor}.
	 *
	 * @param name A {@link Name} node (may be {@code null}).
	 * @param visitor The {@link NodeVisitor} to use.
	 */
	private void visitSimpleNamesOfName(Name name, NodeVisitor<SimpleName> visitor) {
		if (name == null) {
			return;
		}
		if (name instanceof SimpleName) {
			visitor.visit((SimpleName) name);
		}
		else {
			QualifiedName qualifiedName = (QualifiedName) name;
			visitSimpleNamesOfName(qualifiedName.getQualifier(), visitor);
			visitor.visit(qualifiedName.getName());
		}
	}

	/**
	 * Helper method to recursively add a token of the specified type to
	 * all the simple names of a name.
	 *
	 * @param name A {@link Name} node (may be {@code null}).
	 * @param tokenType The {@link TokenType} to add for all the simple names.
	 */
	private void addTokenToSimpleNamesOfName(Name name, TokenType tokenType) {
		visitSimpleNamesOfName(name, simpleName -> addToken(simpleName, tokenType));
	}

	/**
	 * Helper method to find and visit the simple name of a {@code Type} node
	 * using the specified {@link NodeVisitor}.
	 *
	 * @param type A type node (may be {@code null}).
	 * @param visitor The {@link NodeVisitor} to use.
	 */
	private void visitSimpleNameOfType(Type type, NodeVisitor<SimpleName> visitor) {
		if (type == null) {
			return;
		}
		else if (type instanceof SimpleType) {
			SimpleType simpleType = (SimpleType) type;
			acceptNodeList(simpleType.annotations());

			Name simpleTypeName = simpleType.getName();
			if (simpleTypeName instanceof SimpleName) {
				visitor.visit((SimpleName) simpleTypeName);
			}
			else {
				QualifiedName qualifiedName = (QualifiedName) simpleTypeName;
				qualifiedName.getQualifier().accept(this);
				visitor.visit(qualifiedName.getName());
			}
		}
		else if (type instanceof QualifiedType) {
			QualifiedType qualifiedType = (QualifiedType) type;
			qualifiedType.getQualifier().accept(this);
			acceptNodeList(qualifiedType.annotations());
			visitor.visit(qualifiedType.getName());
		}
		else if (type instanceof NameQualifiedType) {
			NameQualifiedType nameQualifiedType = (NameQualifiedType) type;
			nameQualifiedType.getQualifier().accept(this);
			acceptNodeList(nameQualifiedType.annotations());
			visitor.visit(nameQualifiedType.getName());
		}
		else if (type instanceof ParameterizedType) {
			ParameterizedType parameterizedType = (ParameterizedType) type;
			visitSimpleNameOfType(parameterizedType.getType(), visitor);
			visitNodeList(parameterizedType.typeArguments(), this::typeArgumentVisitor);
		}
		else {
			type.accept(this);
		}
	}
}
