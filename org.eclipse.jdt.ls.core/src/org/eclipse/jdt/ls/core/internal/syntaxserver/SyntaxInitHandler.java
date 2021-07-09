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
*******************************************************************************/

package org.eclipse.jdt.ls.core.internal.syntaxserver;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.handlers.BaseInitHandler;
import org.eclipse.jdt.ls.core.internal.handlers.CompletionHandler;
import org.eclipse.jdt.ls.core.internal.handlers.SemanticTokensHandler;
import org.eclipse.jdt.ls.core.internal.managers.ProjectsManager;
import org.eclipse.jdt.ls.core.internal.preferences.PreferenceManager;
import org.eclipse.lsp4j.DocumentFilter;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.SaveOptions;
import org.eclipse.lsp4j.SemanticTokensServerFull;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.eclipse.lsp4j.WorkspaceFoldersOptions;
import org.eclipse.lsp4j.WorkspaceServerCapabilities;

public class SyntaxInitHandler extends BaseInitHandler {
	private ProjectsManager projectsManager;
	private PreferenceManager preferenceManager;

	public SyntaxInitHandler(ProjectsManager projectsManager, PreferenceManager preferenceManager) {
		super(projectsManager, preferenceManager);
		this.projectsManager = projectsManager;
		this.preferenceManager = preferenceManager;
	}

	@Override
	public void registerCapabilities(InitializeResult initializeResult) {
		ServerCapabilities capabilities = new ServerCapabilities();
		if (!preferenceManager.getClientPreferences().isClientDocumentSymbolProviderRegistered() && !preferenceManager.getClientPreferences().isDocumentSymbolDynamicRegistered()) {
			capabilities.setDocumentSymbolProvider(Boolean.TRUE);
		}
		if (!preferenceManager.getClientPreferences().isDefinitionDynamicRegistered()) {
			capabilities.setDefinitionProvider(Boolean.TRUE);
		}
		if (!preferenceManager.getClientPreferences().isTypeDefinitionDynamicRegistered()) {
			capabilities.setTypeDefinitionProvider(Boolean.TRUE);
		}
		if (!preferenceManager.getClientPreferences().isFoldgingRangeDynamicRegistered()) {
			capabilities.setFoldingRangeProvider(Boolean.TRUE);
		}
		if (!preferenceManager.getClientPreferences().isSelectionRangeDynamicRegistered()) {
			capabilities.setSelectionRangeProvider(Boolean.TRUE);
		}
		if (!preferenceManager.getClientPreferences().isHoverDynamicRegistered()) {
			capabilities.setHoverProvider(Boolean.TRUE);
		}
		if (!preferenceManager.getClientPreferences().isCompletionDynamicRegistered()) {
			capabilities.setCompletionProvider(CompletionHandler.DEFAULT_COMPLETION_OPTIONS);
		}
		TextDocumentSyncOptions textDocumentSyncOptions = new TextDocumentSyncOptions();
		textDocumentSyncOptions.setOpenClose(Boolean.TRUE);
		textDocumentSyncOptions.setSave(new SaveOptions(Boolean.TRUE));
		textDocumentSyncOptions.setChange(TextDocumentSyncKind.Incremental);
		capabilities.setTextDocumentSync(textDocumentSyncOptions);

		WorkspaceServerCapabilities wsCapabilities = new WorkspaceServerCapabilities();
		WorkspaceFoldersOptions wsFoldersOptions = new WorkspaceFoldersOptions();
		wsFoldersOptions.setSupported(Boolean.TRUE);
		wsFoldersOptions.setChangeNotifications(Boolean.TRUE);
		wsCapabilities.setWorkspaceFolders(wsFoldersOptions);
		capabilities.setWorkspace(wsCapabilities);

		SemanticTokensWithRegistrationOptions semanticTokensOptions = new SemanticTokensWithRegistrationOptions();
		semanticTokensOptions.setFull(new SemanticTokensServerFull(false));
		semanticTokensOptions.setRange(false);
		semanticTokensOptions.setDocumentSelector(List.of(
			new DocumentFilter("java", "file", null),
			new DocumentFilter("java", "jdt", null)
		));
		semanticTokensOptions.setLegend(SemanticTokensHandler.getLegend());
		capabilities.setSemanticTokensProvider(semanticTokensOptions);

		initializeResult.setCapabilities(capabilities);
	}

	@Override
	public void triggerInitialization(Collection<IPath> roots) {

		Job job = new WorkspaceJob("Initialize Workspace") {
			@Override
			public IStatus runInWorkspace(IProgressMonitor monitor) {
				long start = System.currentTimeMillis();
				try {
					projectsManager.initializeProjects(roots, monitor);
					JavaLanguageServerPlugin.logInfo("Workspace initialized in " + (System.currentTimeMillis() - start) + "ms");
				} catch (Exception e) {
					JavaLanguageServerPlugin.logException("Initialization failed ", e);
				}

				return Status.OK_STATUS;
			}

			/* (non-Javadoc)
			 * @see org.eclipse.core.runtime.jobs.Job#belongsTo(java.lang.Object)
			 */
			@SuppressWarnings("unchecked")
			@Override
			public boolean belongsTo(Object family) {
				Collection<IPath> rootPathsSet = roots.stream().collect(Collectors.toSet());
				boolean equalToRootPaths = false;
				if (family instanceof Collection<?>) {
					equalToRootPaths = rootPathsSet.equals(((Collection<IPath>) family).stream().collect(Collectors.toSet()));
				}

				return JAVA_LS_INITIALIZATION_JOBS.equals(family) || equalToRootPaths;
			}
		};
		job.setPriority(Job.BUILD);
		job.setRule(ResourcesPlugin.getWorkspace().getRoot());
		job.schedule();
	}
}
