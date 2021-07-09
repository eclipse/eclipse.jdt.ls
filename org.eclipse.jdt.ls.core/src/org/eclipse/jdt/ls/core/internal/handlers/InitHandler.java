/*******************************************************************************
 * Copyright (c) 2016-2021 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Red Hat Inc. - initial API and implementation
 *     IBM Corporation (Markus Keller)
 *     Microsoft Corporation
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal.handlers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.ls.core.internal.JavaClientConnection;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.ServiceStatus;
import org.eclipse.jdt.ls.core.internal.managers.ProjectsManager;
import org.eclipse.jdt.ls.core.internal.preferences.PreferenceManager;
import org.eclipse.lsp4j.CodeActionOptions;
import org.eclipse.lsp4j.CodeLensOptions;
import org.eclipse.lsp4j.DocumentFilter;
import org.eclipse.lsp4j.DocumentOnTypeFormattingOptions;
import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.SaveOptions;
import org.eclipse.lsp4j.SemanticTokensServerFull;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.eclipse.lsp4j.WorkspaceFoldersOptions;
import org.eclipse.lsp4j.WorkspaceServerCapabilities;

/**
 * Handler for the VS Code extension initialization
 */
final public class InitHandler extends BaseInitHandler {
	private static final String BUNDLES_KEY = "bundles";

	private JavaClientConnection connection;
	private PreferenceManager preferenceManager;

	private WorkspaceExecuteCommandHandler commandHandler;

	public InitHandler(ProjectsManager manager, PreferenceManager preferenceManager, JavaClientConnection connection, WorkspaceExecuteCommandHandler commandHandler) {
		super(manager, preferenceManager);
		this.connection = connection;
		this.preferenceManager = preferenceManager;
		this.commandHandler = commandHandler;
	}

	@Override
	public Map<?, ?> handleInitializationOptions(InitializeParams param) {
		Map<?, ?> initializationOptions = super.handleInitializationOptions(param);

		try {
			Collection<String> bundleList = getInitializationOption(initializationOptions, BUNDLES_KEY, Collection.class);
			BundleUtils.loadBundles(bundleList);
		} catch (CoreException e) {
			// The additional plug-ins should not affect the main language server loading.
			JavaLanguageServerPlugin.logException("Failed to load extension bundles ", e);
		}

		return initializationOptions;
	}

	@Override
	public void registerCapabilities(InitializeResult initializeResult) {
		ServerCapabilities capabilities = new ServerCapabilities();
		if (!preferenceManager.getClientPreferences().isCompletionDynamicRegistered()) {
			capabilities.setCompletionProvider(CompletionHandler.DEFAULT_COMPLETION_OPTIONS);
		}
		if (!preferenceManager.getClientPreferences().isFormattingDynamicRegistrationSupported()) {
			capabilities.setDocumentFormattingProvider(Boolean.TRUE);
		}
		if (!preferenceManager.getClientPreferences().isRangeFormattingDynamicRegistrationSupported()) {
			capabilities.setDocumentRangeFormattingProvider(Boolean.TRUE);
		}
		if (!preferenceManager.getClientPreferences().isOnTypeFormattingDynamicRegistrationSupported()) {
			capabilities.setDocumentOnTypeFormattingProvider(new DocumentOnTypeFormattingOptions(";", Arrays.asList("\n", "}")));
		}
		if (!preferenceManager.getClientPreferences().isCodeLensDynamicRegistrationSupported()) {
			capabilities.setCodeLensProvider(new CodeLensOptions(true));
		}
		if (!preferenceManager.getClientPreferences().isSignatureHelpDynamicRegistrationSupported()) {
			capabilities.setSignatureHelpProvider(SignatureHelpHandler.createOptions());
		}
		if (!preferenceManager.getClientPreferences().isRenameDynamicRegistrationSupported()) {
			capabilities.setRenameProvider(RenameHandler.createOptions());
		}
		if (!preferenceManager.getClientPreferences().isCodeActionDynamicRegistered()) {
			if (preferenceManager.getClientPreferences().isResolveCodeActionSupported()) {
				CodeActionOptions codeActionOptions = new CodeActionOptions();
				codeActionOptions.setResolveProvider(Boolean.TRUE);
				capabilities.setCodeActionProvider(codeActionOptions);
			} else {
				capabilities.setCodeActionProvider(Boolean.TRUE);
			}
		}
		if (!preferenceManager.getClientPreferences().isExecuteCommandDynamicRegistrationSupported()) {
			Set<String> commands = commandHandler.getAllCommands();
			if (!commands.isEmpty()) {
				capabilities.setExecuteCommandProvider(new ExecuteCommandOptions(new ArrayList<>(commands)));
			}
		} else {
			// Send static command at the startup - they remain registered all the time
			Set<String> staticCommands = commandHandler.getStaticCommands();
			if (!staticCommands.isEmpty()) {
				capabilities.setExecuteCommandProvider(new ExecuteCommandOptions(new ArrayList<>(staticCommands)));
			}
		}
		if (!preferenceManager.getClientPreferences().isWorkspaceSymbolDynamicRegistered()) {
			capabilities.setWorkspaceSymbolProvider(Boolean.TRUE);
		}
		if (!preferenceManager.getClientPreferences().isClientDocumentSymbolProviderRegistered() && !preferenceManager.getClientPreferences().isDocumentSymbolDynamicRegistered()) {
			capabilities.setDocumentSymbolProvider(Boolean.TRUE);
		}
		if (!preferenceManager.getClientPreferences().isDefinitionDynamicRegistered()) {
			capabilities.setDefinitionProvider(Boolean.TRUE);
		}
		if (!preferenceManager.getClientPreferences().isTypeDefinitionDynamicRegistered()) {
			capabilities.setTypeDefinitionProvider(Boolean.TRUE);
		}
		if (!preferenceManager.getClientPreferences().isClientHoverProviderRegistered() && !preferenceManager.getClientPreferences().isHoverDynamicRegistered()) {
			capabilities.setHoverProvider(Boolean.TRUE);
		}
		if (!preferenceManager.getClientPreferences().isReferencesDynamicRegistered()) {
			capabilities.setReferencesProvider(Boolean.TRUE);
		}
		if (!preferenceManager.getClientPreferences().isDocumentHighlightDynamicRegistered()) {
			capabilities.setDocumentHighlightProvider(Boolean.TRUE);
		}
		if (!preferenceManager.getClientPreferences().isFoldgingRangeDynamicRegistered()) {
			capabilities.setFoldingRangeProvider(Boolean.TRUE);
		}
		if (!preferenceManager.getClientPreferences().isImplementationDynamicRegistered()) {
			capabilities.setImplementationProvider(Boolean.TRUE);
		}
		if (!preferenceManager.getClientPreferences().isSelectionRangeDynamicRegistered()) {
			capabilities.setSelectionRangeProvider(Boolean.TRUE);
		}
		capabilities.setCallHierarchyProvider(Boolean.TRUE);
		TextDocumentSyncOptions textDocumentSyncOptions = new TextDocumentSyncOptions();
		textDocumentSyncOptions.setOpenClose(Boolean.TRUE);
		textDocumentSyncOptions.setSave(new SaveOptions(Boolean.TRUE));
		textDocumentSyncOptions.setChange(TextDocumentSyncKind.Incremental);
		if (preferenceManager.getClientPreferences().isWillSaveRegistered()) {
			textDocumentSyncOptions.setWillSave(Boolean.TRUE);
		}

		if (preferenceManager.getClientPreferences().isWillSaveWaitUntilRegistered()) {
			textDocumentSyncOptions.setWillSaveWaitUntil(Boolean.TRUE);
		}
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
				connection.sendStatus(ServiceStatus.Starting, "Init...");
				SubMonitor subMonitor = SubMonitor.convert(monitor, 100);
				try {
					projectsManager.setAutoBuilding(false);
					projectsManager.initializeProjects(roots, subMonitor);
					projectsManager.configureFilters(monitor);
					projectsManager.setAutoBuilding(preferenceManager.getPreferences().isAutobuildEnabled());
					JavaLanguageServerPlugin.logInfo("Workspace initialized in " + (System.currentTimeMillis() - start) + "ms");
					connection.sendStatus(ServiceStatus.Started, "Ready");
				} catch (OperationCanceledException e) {
					connection.sendStatus(ServiceStatus.Error, "Initialization has been cancelled.");
					return Status.CANCEL_STATUS;
				} catch (Exception e) {
					JavaLanguageServerPlugin.logException("Initialization failed ", e);
					connection.sendStatus(ServiceStatus.Error, e.getMessage());
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
