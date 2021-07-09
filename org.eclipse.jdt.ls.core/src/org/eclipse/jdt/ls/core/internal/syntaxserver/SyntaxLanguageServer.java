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

import static org.eclipse.jdt.ls.core.internal.JDTEnvironmentUtils.SYNTAX_SERVER_ID;
import static org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin.logException;
import static org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin.logInfo;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.ls.core.internal.BaseJDTLanguageServer;
import org.eclipse.jdt.ls.core.internal.JDTUtils;
import org.eclipse.jdt.ls.core.internal.JSONUtility;
import org.eclipse.jdt.ls.core.internal.JavaClientConnection.JavaLanguageClient;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.JobHelpers;
import org.eclipse.jdt.ls.core.internal.ServiceStatus;
import org.eclipse.jdt.ls.core.internal.handlers.BaseDocumentLifeCycleHandler;
import org.eclipse.jdt.ls.core.internal.handlers.CompletionHandler;
import org.eclipse.jdt.ls.core.internal.handlers.CompletionResolveHandler;
import org.eclipse.jdt.ls.core.internal.handlers.DocumentSymbolHandler;
import org.eclipse.jdt.ls.core.internal.handlers.FoldingRangeHandler;
import org.eclipse.jdt.ls.core.internal.handlers.HoverHandler;
import org.eclipse.jdt.ls.core.internal.handlers.NavigateToDefinitionHandler;
import org.eclipse.jdt.ls.core.internal.handlers.NavigateToTypeDefinitionHandler;
import org.eclipse.jdt.ls.core.internal.handlers.SelectionRangeHandler;
import org.eclipse.jdt.ls.core.internal.handlers.SemanticTokensHandler;
import org.eclipse.jdt.ls.core.internal.handlers.WorkspaceEventsHandler;
import org.eclipse.jdt.ls.core.internal.handlers.WorkspaceFolderChangeHandler;
import org.eclipse.jdt.ls.core.internal.managers.ContentProviderManager;
import org.eclipse.jdt.ls.core.internal.managers.ProjectsManager;
import org.eclipse.jdt.ls.core.internal.preferences.PreferenceManager;
import org.eclipse.jdt.ls.core.internal.preferences.Preferences;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.FoldingRangeRequestParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.SelectionRange;
import org.eclipse.lsp4j.SelectionRangeParams;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TypeDefinitionParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.services.JsonDelegate;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

public class SyntaxLanguageServer extends BaseJDTLanguageServer implements LanguageServer, TextDocumentService, WorkspaceService, IExtendedProtocol {
	public static final String JAVA_LSP_JOIN_ON_COMPLETION = "java.lsp.joinOnCompletion";

	private SyntaxDocumentLifeCycleHandler documentLifeCycleHandler;
	private ContentProviderManager contentProviderManager;
	private ProjectsManager projectsManager;
	private PreferenceManager preferenceManager;
	private Job shutdownJob = new Job("Shutdown...") {

		@Override
		protected IStatus run(IProgressMonitor monitor) {
			try {
				ResourcesPlugin.getWorkspace().save(true, monitor);
			} catch (CoreException e) {
				logException(e.getMessage(), e);
			}
			return Status.OK_STATUS;
		}

	};

	public SyntaxLanguageServer(ContentProviderManager contentProviderManager,
								ProjectsManager projectsManager,
								PreferenceManager preferenceManager) {
		this(contentProviderManager, projectsManager, preferenceManager, true);
	}

	public SyntaxLanguageServer(ContentProviderManager contentProviderManager,
								ProjectsManager projectsManager,
								PreferenceManager preferenceManager,
								boolean delayValidation) {
		this.contentProviderManager = contentProviderManager;
		this.projectsManager = projectsManager;
		this.preferenceManager = preferenceManager;
		this.documentLifeCycleHandler = new SyntaxDocumentLifeCycleHandler(null, projectsManager, preferenceManager, delayValidation);
	}

	@Override
	public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
		logInfo(">> initialize");
		SyntaxInitHandler handler = new SyntaxInitHandler(projectsManager, preferenceManager);
		return CompletableFuture.completedFuture(handler.initialize(params));
	}

	@Override
	public CompletableFuture<Object> shutdown() {
		logInfo(">> shutdown");
		return computeAsync((monitor) -> {
			shutdownJob.schedule();
			shutdownReceived = true;
			if (preferenceManager.getClientPreferences().shouldLanguageServerExitOnShutdown()) {
				exit();
				try {
					/*
					 * Suppress annoying error message in client, by encouraging
					 * syntax server to exit before shutdown() can respond to client.
					 */
					Thread.sleep(1000);
				} catch (InterruptedException e) {
				}
			}
			return new Object();
		});
	}

	@Override
	public void exit() {
		logInfo(">> exit");
		if (!shutdownReceived) {
			shutdownJob.schedule();
		}
		try {
			shutdownJob.join();
		} catch (InterruptedException e) {
			JavaLanguageServerPlugin.logException(e.getMessage(), e);
		}
		JavaLanguageServerPlugin.getLanguageServer().exit();
		Executors.newSingleThreadScheduledExecutor().schedule(() -> {
			System.exit(1);
		}, 1, TimeUnit.MINUTES);
	}

	@Override
	public TextDocumentService getTextDocumentService() {
		return this;
	}

	@Override
	public WorkspaceService getWorkspaceService() {
		return this;
	}

	@JsonDelegate
	public IExtendedProtocol getExtendedService() {
		return this;
	}

	@Override
	public void initialized() {
		logInfo(">> initialized");
		try {
			Job.getJobManager().join(SyntaxInitHandler.JAVA_LS_INITIALIZATION_JOBS, null);
		} catch (OperationCanceledException | InterruptedException e) {
			logException(e.getMessage(), e);
		}
		this.client.sendStatus(ServiceStatus.Started, "LightWeightServiceReady");
		logInfo(">> initialization job finished");

		PreferenceManager preferenceManager = JavaLanguageServerPlugin.getPreferencesManager();
		if (preferenceManager.getClientPreferences().isCompletionDynamicRegistered()) {
			registerCapability(Preferences.COMPLETION_ID, Preferences.COMPLETION, CompletionHandler.DEFAULT_COMPLETION_OPTIONS);
		}

		if (!preferenceManager.getClientPreferences().isClientDocumentSymbolProviderRegistered() && preferenceManager.getClientPreferences().isDocumentSymbolDynamicRegistered()) {
			registerCapability(Preferences.DOCUMENT_SYMBOL_ID, Preferences.DOCUMENT_SYMBOL, null);
		}

		if (preferenceManager.getClientPreferences().isDefinitionDynamicRegistered()) {
			registerCapability(Preferences.DEFINITION_ID, Preferences.DEFINITION, null);
		}

		if (preferenceManager.getClientPreferences().isTypeDefinitionDynamicRegistered()) {
			registerCapability(Preferences.TYPEDEFINITION_ID, Preferences.TYPEDEFINITION, null);
		}

		if (preferenceManager.getClientPreferences().isFoldgingRangeDynamicRegistered()) {
			registerCapability(Preferences.FOLDINGRANGE_ID, Preferences.FOLDINGRANGE, null);
		}

		if (preferenceManager.getClientPreferences().isSelectionRangeDynamicRegistered()) {
			registerCapability(Preferences.SELECTION_RANGE_ID, Preferences.SELECTION_RANGE, null);
		}

		if (!preferenceManager.getClientPreferences().isClientHoverProviderRegistered() && preferenceManager.getClientPreferences().isHoverDynamicRegistered()) {
			registerCapability(Preferences.HOVER_ID, Preferences.HOVER, null);
		}

		if (preferenceManager.getClientPreferences().isWorkspaceChangeWatchedFilesDynamicRegistered()) {
			projectsManager.registerWatchers();
		}
	}

	@Override
	public void connectClient(JavaLanguageClient client) {
		super.connectClient(client);
		this.documentLifeCycleHandler.setClient(this.client);
	}

	@Override
	public void didChangeConfiguration(DidChangeConfigurationParams params) {
		logInfo(">> workspace/didChangeConfiguration");
		Object settings = JSONUtility.toModel(params.getSettings(), Map.class);
		if (settings instanceof Map) {
			Collection<IPath> rootPaths = preferenceManager.getPreferences().getRootPaths();
			@SuppressWarnings("unchecked")
			Preferences prefs = Preferences.createFrom((Map<String, Object>) settings);
			prefs.setRootPaths(rootPaths);
			preferenceManager.update(prefs);
		}
	}

	@Override
	public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
		logInfo(">> workspace/didChangeWatchedFiles ");
		WorkspaceEventsHandler handler = new WorkspaceEventsHandler(this.projectsManager, this.client, this.documentLifeCycleHandler);
		handler.didChangeWatchedFiles(params);
	}

	@Override
	public void didOpen(DidOpenTextDocumentParams params) {
		logInfo(">> document/didOpen");
		documentLifeCycleHandler.didOpen(params);
	}

	@Override
	public void didChange(DidChangeTextDocumentParams params) {
		logInfo(">> document/didChange");
		documentLifeCycleHandler.didChange(params);
	}

	@Override
	public void didClose(DidCloseTextDocumentParams params) {
		logInfo(">> document/didClose");
		documentLifeCycleHandler.didClose(params);
	}

	@Override
	public void didSave(DidSaveTextDocumentParams params) {
		logInfo(">> document/didSave");
		documentLifeCycleHandler.didSave(params);
	}

	@Override
	public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
		logInfo(">> document/documentSymbol");
		DocumentSymbolHandler handler = new DocumentSymbolHandler(preferenceManager);
		return computeAsync((monitor) -> {
			waitForLifecycleJobs(monitor);
			return handler.documentSymbol(params, monitor);
		});
	}

	@Override
	public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams position) {
		logInfo(">> document/definition");
		NavigateToDefinitionHandler handler = new NavigateToDefinitionHandler(this.preferenceManager);
		return computeAsync((monitor) -> {
			waitForLifecycleJobs(monitor);
			List<? extends Location> locations = handler.definition(position, monitor);
			for (Location location : locations) {
				location.setUri(JDTUtils.replaceUriFragment(location.getUri(), SYNTAX_SERVER_ID));
			}
			return Either.forLeft(locations);
		});
	}

	@Override
	public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> typeDefinition(TypeDefinitionParams position) {
		logInfo(">> document/typeDefinition");
		NavigateToTypeDefinitionHandler handler = new NavigateToTypeDefinitionHandler();
		return computeAsync((monitor) -> {
			waitForLifecycleJobs(monitor);
			List<? extends Location> locations = handler.typeDefinition(position, monitor);
			for (Location location : locations) {
				location.setUri(JDTUtils.replaceUriFragment(location.getUri(), SYNTAX_SERVER_ID));
			}
			return Either.forLeft(locations);
		});
	}

	@Override
	public CompletableFuture<List<FoldingRange>> foldingRange(FoldingRangeRequestParams params) {
		logInfo(">> document/foldingRange");
		return computeAsync((monitor) -> {
			waitForLifecycleJobs(monitor);
			return new FoldingRangeHandler().foldingRange(params, monitor);
		});
	}

	@Override
	public CompletableFuture<List<SelectionRange>> selectionRange(SelectionRangeParams params) {
		logInfo(">> document/selectionRange");
		return computeAsync((monitor) -> {
			waitForLifecycleJobs(monitor);
			return new SelectionRangeHandler().selectionRange(params, monitor);
		});
	}

	@Override
	public void didChangeWorkspaceFolders(DidChangeWorkspaceFoldersParams params) {
		logInfo(">> java/didChangeWorkspaceFolders");
		WorkspaceFolderChangeHandler handler = new WorkspaceFolderChangeHandler(projectsManager, preferenceManager);
		handler.update(params);
	}

	@Override
	public CompletableFuture<String> classFileContents(TextDocumentIdentifier param) {
		logInfo(">> java/classFileContents");
		URI uri = JDTUtils.toURI(param.getUri());
		return computeAsync((monitor) -> contentProviderManager.getContent(uri, monitor));
	}

	@Override
	public CompletableFuture<Hover> hover(HoverParams position) {
		logInfo(">> document/hover");
		HoverHandler handler = new HoverHandler(this.preferenceManager);
		return computeAsync((monitor) -> handler.hover(position, monitor));
	}

	/* (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.TextDocumentService#completion(org.eclipse.lsp4j.CompletionParams)
	 */
	@Override
	public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams position) {
		logInfo(">> document/completion");
		CompletionHandler handler = new CompletionHandler(preferenceManager);
		final IProgressMonitor[] monitors = new IProgressMonitor[1];
		CompletableFuture<Either<List<CompletionItem>, CompletionList>> result = computeAsync((monitor) -> {
			monitors[0] = monitor;
			if (Boolean.getBoolean(JAVA_LSP_JOIN_ON_COMPLETION)) {
				waitForLifecycleJobs(monitor);
			}
			return handler.completion(position, monitor);
		});
		result.join();
		if (monitors[0].isCanceled()) {
			result.cancel(true);
		}
		return result;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.TextDocumentService#resolveCompletionItem(org.eclipse.lsp4j.CompletionItem)
	 */
	@Override
	public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem unresolved) {
		logInfo(">> document/resolveCompletionItem");
		CompletionResolveHandler handler = new CompletionResolveHandler(preferenceManager);
		final IProgressMonitor[] monitors = new IProgressMonitor[1];
		CompletableFuture<CompletionItem> result = computeAsync((monitor) -> {
			monitors[0] = monitor;
			if ((Boolean.getBoolean(JAVA_LSP_JOIN_ON_COMPLETION))) {
				waitForLifecycleJobs(monitor);
			}
			return handler.resolve(unresolved, monitor);
		});
		result.join();
		if (monitors[0].isCanceled()) {
			result.cancel(true);
		}
		return result;
	}

	@Override
	public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
		logInfo(">> textDocument/semanticTokens/full");
		return computeAsync(monitor -> {
			waitForLifecycleJobs(monitor);
			return SemanticTokensHandler.provide(monitor, params);
		});
	}

	private void waitForLifecycleJobs(IProgressMonitor monitor) {
		JobHelpers.waitForJobs(BaseDocumentLifeCycleHandler.DOCUMENT_LIFE_CYCLE_JOBS, monitor);
	}
}
