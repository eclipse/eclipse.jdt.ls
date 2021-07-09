/*******************************************************************************
 * Copyright (c) 2016-2017 Red Hat Inc. and others.
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
package org.eclipse.jdt.ls.core.internal.handlers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.core.internal.resources.CheckMissingNaturesListener;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaModelMarker;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.ls.core.internal.JDTUtils;
import org.eclipse.jdt.ls.core.internal.JavaClientConnection;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.ResourceUtils;
import org.eclipse.jdt.ls.core.internal.managers.ProjectsManager;
import org.eclipse.jdt.ls.core.internal.preferences.ClientPreferences;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticRelatedInformation;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.m2e.core.internal.IMavenConstants;
import org.eclipse.m2e.core.internal.Messages;

/**
 * Listens to the resource change events and converts {@link IMarker}s to {@link Diagnostic}s.
 *
 * @author Gorkem Ercan
 *
 */
@SuppressWarnings("restriction")
public final class WorkspaceDiagnosticsHandler implements IResourceChangeListener, IResourceDeltaVisitor {

	public static final String PROJECT_CONFIGURATION_IS_NOT_UP_TO_DATE_WITH_POM_XML = "Project configuration is not up-to-date with pom.xml, requires an update.";
	private final JavaClientConnection connection;
	private final ProjectsManager projectsManager;
	private final boolean isDiagnosticTagSupported;

	@Deprecated
	public WorkspaceDiagnosticsHandler(JavaClientConnection connection, ProjectsManager projectsManager) {
		this(connection, projectsManager, null);
	}

	public WorkspaceDiagnosticsHandler(JavaClientConnection connection, ProjectsManager projectsManager, ClientPreferences prefs) {
		this.connection = connection;
		this.projectsManager = projectsManager;
		this.isDiagnosticTagSupported = prefs != null ? prefs.isDiagnosticTagSupported() : false;
	}

	public void addResourceChangeListener() {
		ResourcesPlugin.getWorkspace().addResourceChangeListener(this, IResourceChangeEvent.POST_CHANGE);
	}

	public void removeResourceChangeListener() {
		ResourcesPlugin.getWorkspace().removeResourceChangeListener(this);
	}

	@Override
	public void resourceChanged(IResourceChangeEvent event) {
		try {
			IResourceDelta delta = event.getDelta();
			delta.accept(this);
		} catch (CoreException e) {
			JavaLanguageServerPlugin.logException("failed to send diagnostics", e);
		}

	}

	/*
	 * (non-Javadoc)
	 *
	 * @see
	 * org.eclipse.core.resources.IResourceDeltaVisitor#visit(org.eclipse.core.
	 * resources.IResourceDelta)
	 */
	@Override
	public boolean visit(IResourceDelta delta) throws CoreException {
		IResource resource = delta.getResource();
		if (resource == null) {
			return false;
		}
		if (resource.getType() == IResource.FOLDER || resource.getType() == IResource.ROOT) {
			return true;
		}
		// WorkspaceEventsHandler only handles the case of deleting the specific file and removes it's diagnostics.
		// If delete a folder directly, no way to clean up the diagnostics for it's children.
		// The resource delta visitor will make sure to clean up all stale diagnostics.
		if (!resource.isAccessible()) { // Check if resource is accessible.
			if (isSupportedDiagnosticsResource(resource)) {
				cleanUpDiagnostics(resource);
				return resource.getType() == IResource.PROJECT;
			}

			// If delete a project folder directly, make sure to clean up its build file diagnostics.
			if (projectsManager.isBuildLikeFileName(resource.getName())) {
				cleanUpDiagnostics(resource);
				if(!resource.getParent().isAccessible()) { // Clean up the project folder diagnostics.
					cleanUpDiagnostics(resource.getParent(), Platform.OS_WIN32.equals(Platform.getOS()));
				}
			}

			return false;
		}
		if (resource.getType() == IResource.PROJECT) {
			// ignore problems caused by standalone files (problems in the default project)
			if (JavaLanguageServerPlugin.getProjectsManager().getDefaultProject().equals(resource.getProject())) {
				return false;
			}
			IProject project = (IProject) resource;
			// report problems for other projects
			IMarker[] markers = project.findMarkers(null, true, IResource.DEPTH_ZERO);
			publishMarkers(project, markers);
			return true;
		}
		// No marker changes continue to visit
		if ((delta.getFlags() & IResourceDelta.MARKERS) == 0) {
			return false;
		}
		IFile file = (IFile) resource;
		IDocument document = null;
		IMarker[] markers = null;
		// Check if it is a Java ...
		if (JavaCore.isJavaLikeFileName(file.getName())) {
			ICompilationUnit cu = (ICompilationUnit) JavaCore.create(file);
			// Clear the diagnostics for the resource not on the classpath
			IJavaProject javaProject = cu.getJavaProject();
			if (javaProject == null || !javaProject.isOnClasspath(cu)) {
				String uri = JDTUtils.getFileURI(resource);
				this.connection.publishDiagnostics(new PublishDiagnosticsParams(ResourceUtils.toClientUri(uri), Collections.emptyList()));
				return false;
			}

			IMarker[] javaMarkers = resource.findMarkers(IJavaModelMarker.JAVA_MODEL_PROBLEM_MARKER, false, IResource.DEPTH_ONE);
			IMarker[] taskMarkers = resource.findMarkers(IJavaModelMarker.TASK_MARKER, false, IResource.DEPTH_ONE);
			markers = Arrays.copyOf(javaMarkers, javaMarkers.length + taskMarkers.length);
			System.arraycopy(taskMarkers, 0, markers, javaMarkers.length, taskMarkers.length);
			document = JsonRpcHelpers.toDocument(cu.getBuffer());
		} // or a build file
		else if (projectsManager.isBuildFile(file)) {
			//all errors on that build file should be relevant
			markers = file.findMarkers(null, true, 1);
			document = JsonRpcHelpers.toDocument(file);
		}
		if (document != null) {
			String uri = JDTUtils.getFileURI(resource);
			this.connection.publishDiagnostics(new PublishDiagnosticsParams(ResourceUtils.toClientUri(uri), toDiagnosticsArray(document, markers, isDiagnosticTagSupported, uri)));
		}
		return false;
	}

	private void publishMarkers(IProject project, IMarker[] markers) throws CoreException {
		Range range = new Range(new Position(0, 0), new Position(0, 0));

		List<IMarker> projectMarkers = new ArrayList<>(markers.length);

		String uri = JDTUtils.getFileURI(project);
		IFile pom = project.getFile("pom.xml");
		List<IMarker> pomMarkers = new ArrayList<>();
		for (IMarker marker : markers) {
			if (!marker.exists() || CheckMissingNaturesListener.MARKER_TYPE.equals(marker.getType())) {
				continue;
			}
			if (IMavenConstants.MARKER_CONFIGURATION_ID.equals(marker.getType())) {
				pomMarkers.add(marker);
			} else {
				projectMarkers.add(marker);
			}
		}
		List<Diagnostic> diagnostics = toDiagnosticArray(range, projectMarkers, isDiagnosticTagSupported, uri);
		String clientUri = ResourceUtils.toClientUri(uri);
		connection.publishDiagnostics(new PublishDiagnosticsParams(clientUri, diagnostics));
		if (pom.exists()) {
			IDocument document = JsonRpcHelpers.toDocument(pom);
			diagnostics = toDiagnosticsArray(document, pom.findMarkers(null, true, IResource.DEPTH_ZERO), isDiagnosticTagSupported, uri);
			List<Diagnostic> diagnosicts2 = toDiagnosticArray(range, pomMarkers, isDiagnosticTagSupported);
			diagnostics.addAll(diagnosicts2);
			String pomSuffix = clientUri.endsWith("/") ? "pom.xml" : "/pom.xml";
			connection.publishDiagnostics(new PublishDiagnosticsParams(ResourceUtils.toClientUri(clientUri + pomSuffix), diagnostics));
		}
	}

	public List<IMarker> publishDiagnostics(IProgressMonitor monitor) throws CoreException {
		List<IMarker> problemMarkers = getProblemMarkers(monitor);
		publishDiagnostics(problemMarkers);
		return problemMarkers;
	}

	private List<IMarker> getProblemMarkers(IProgressMonitor monitor) throws CoreException {
		IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
		List<IMarker> markers = new ArrayList<>();
		for (IProject project : projects) {
			if (monitor != null && monitor.isCanceled()) {
				throw new OperationCanceledException();
			}
			if (JavaLanguageServerPlugin.getProjectsManager().getDefaultProject().equals(project)) {
				continue;
			}
			IMarker[] allMarkers = project.findMarkers(null, true, IResource.DEPTH_INFINITE);
			for (IMarker marker : allMarkers) {
				if (!marker.exists() || CheckMissingNaturesListener.MARKER_TYPE.equals(marker.getType())) {
					continue;
				}
				if (IJavaModelMarker.JAVA_MODEL_PROBLEM_MARKER.equals(marker.getType()) || IJavaModelMarker.TASK_MARKER.equals(marker.getType())) {
					markers.add(marker);
					continue;
				}
				IResource resource = marker.getResource();
				if (project.equals(resource) || projectsManager.isBuildFile(resource)) {
					markers.add(marker);
				}
			}
		}
		return markers;
	}

	private void publishDiagnostics(List<IMarker> markers) {
		Map<IResource, List<IMarker>> map = markers.stream().collect(Collectors.groupingBy(IMarker::getResource));
		for (Map.Entry<IResource, List<IMarker>> entry : map.entrySet()) {
			IResource resource = entry.getKey();
			if (resource instanceof IProject) {
				try {
					IProject project = (IProject) resource;
					publishMarkers(project, entry.getValue().toArray(new IMarker[0]));
				} catch (CoreException e) {
					JavaLanguageServerPlugin.logException(e.getMessage(), e);
				}
				continue;
			}
			IFile file = resource.getAdapter(IFile.class);
			if (file == null) {
				continue;
			}
			IDocument document = null;
			String uri = JDTUtils.getFileURI(file);
			if (JavaCore.isJavaLikeFileName(file.getName())) {
				ICompilationUnit cu = JDTUtils.resolveCompilationUnit(uri);
				//ignoring working copies, they're handled in the DocumentLifecycleHandler
				if (cu != null && !cu.isWorkingCopy()) {
					try {
						document = JsonRpcHelpers.toDocument(cu.getBuffer());
					} catch (JavaModelException e) {
						JavaLanguageServerPlugin.logException("Failed to publish diagnostics for " + uri, e);
					}
				}
			} else if (projectsManager.isBuildFile(file)) {
				document = JsonRpcHelpers.toDocument(file);
			}
			if (document != null) {
				List<Diagnostic> diagnostics = WorkspaceDiagnosticsHandler.toDiagnosticsArray(document, entry.getValue().toArray(new IMarker[0]), isDiagnosticTagSupported, uri);
				connection.publishDiagnostics(new PublishDiagnosticsParams(ResourceUtils.toClientUri(uri), diagnostics));
			}
		}
	}

	@Deprecated
	public static List<Diagnostic> toDiagnosticArray(Range range, Collection<IMarker> markers) {
		return toDiagnosticArray(range, markers, false);
	}

	/**
	 * Transforms {@link IMarker}s into a list of {@link Diagnostic}s
	 *
	 * @param range
	 * @param markers
	 * @return a list of {@link Diagnostic}s
	 */
	public static List<Diagnostic> toDiagnosticArray(Range range, Collection<IMarker> markers, boolean isDiagnosticTagSupported) {
		List<Diagnostic> diagnostics = markers.stream().map(m -> toDiagnostic(range, m, isDiagnosticTagSupported, null)).filter(d -> d != null).collect(Collectors.toList());
		return diagnostics;
	}

	public static List<Diagnostic> toDiagnosticArray(Range range, Collection<IMarker> markers, boolean isDiagnosticTagSupported, String uri) {
		List<Diagnostic> diagnostics = markers.stream().map(m -> toDiagnostic(range, m, isDiagnosticTagSupported, uri)).filter(d -> d != null).collect(Collectors.toList());
		return diagnostics;
	}

	private static Diagnostic toDiagnostic(Range range, IMarker marker, boolean isDiagnosticTagSupported, String uri) {
		if (marker == null || !marker.exists()) {
			return null;
		}
		Diagnostic d = new Diagnostic();
		d.setSource(JavaLanguageServerPlugin.SERVER_SOURCE_ID);
		String message = marker.getAttribute(IMarker.MESSAGE, "");
		if (Messages.ProjectConfigurationUpdateRequired.equals(message)) {
			message = PROJECT_CONFIGURATION_IS_NOT_UP_TO_DATE_WITH_POM_XML;
		}
		d.setMessage(message);
		d.setSeverity(convertSeverity(marker.getAttribute(IMarker.SEVERITY, -1)));
		int problemId = marker.getAttribute(IJavaModelMarker.ID, 0);
		d.setCode(String.valueOf(problemId));
		if (isDiagnosticTagSupported) {
			d.setTags(DiagnosticsHandler.getDiagnosticTag(problemId));
		}
		addRelatedInformation(d, range, marker, uri);
		d.setRange(range);
		return d;
	}

	private static void addRelatedInformation(Diagnostic d, Range range, IMarker marker, String uri) {
		if (uri != null) {
			String argumentsString = marker.getAttribute(IJavaModelMarker.ARGUMENTS, null);
			if (argumentsString != null && !argumentsString.isEmpty()) {
				Location location = new Location(ResourceUtils.toClientUri(uri), range);
				DiagnosticRelatedInformation dri = new DiagnosticRelatedInformation(location, argumentsString);
				List<DiagnosticRelatedInformation> relatedInformation = new ArrayList<>();
				relatedInformation.add(dri);
				d.setRelatedInformation(relatedInformation);
			}
		}
	}

	@Deprecated
	public static List<Diagnostic> toDiagnosticsArray(IDocument document, IMarker[] markers) {
		return toDiagnosticsArray(document, markers, false, null);
	}

	@Deprecated
	public static List<Diagnostic> toDiagnosticsArray(IDocument document, IMarker[] markers, boolean isDiagnosticTagSupported) {
		return toDiagnosticsArray(document, markers, isDiagnosticTagSupported, null);
	}

	/**
	 * Transforms {@link IMarker}s of a {@link IDocument} into a list of
	 * {@link Diagnostic}s.
	 *
	 * @param document
	 * @param markers
	 * @return a list of {@link Diagnostic}s
	 */
	public static List<Diagnostic> toDiagnosticsArray(IDocument document, IMarker[] markers, boolean isDiagnosticTagSupported, String uri) {
		List<Diagnostic> diagnostics = Stream.of(markers)
				.map(m -> toDiagnostic(document, m, isDiagnosticTagSupported,
						uri))
				.filter(d -> d != null)
				.collect(Collectors.toList());
		return diagnostics;
	}

	private static Diagnostic toDiagnostic(IDocument document, IMarker marker, boolean isDiagnosticTagSupported, String uri) {
		if (marker == null || !marker.exists()) {
			return null;
		}
		Diagnostic d = new Diagnostic();
		d.setSource(JavaLanguageServerPlugin.SERVER_SOURCE_ID);
		d.setMessage(marker.getAttribute(IMarker.MESSAGE, ""));
		int problemId = marker.getAttribute(IJavaModelMarker.ID, 0);
		d.setCode(String.valueOf(problemId));
		d.setSeverity(convertSeverity(marker.getAttribute(IMarker.SEVERITY, -1)));
		Range range = convertRange(document, marker);
		d.setRange(range);
		if (isDiagnosticTagSupported) {
			d.setTags(DiagnosticsHandler.getDiagnosticTag(problemId));
		}
		addRelatedInformation(d, range, marker, uri);
		return d;
	}

	/**
	 * @param marker
	 * @return
	 */
	private static Range convertRange(IDocument document, IMarker marker) {
		int line = marker.getAttribute(IMarker.LINE_NUMBER, -1) - 1;
		if (line < 0) {
			int end = marker.getAttribute(IMarker.CHAR_END, -1);
			int start = marker.getAttribute(IMarker.CHAR_START, -1);
			if (start >= 0 && end >= start) {
				try {
					Range range = getAnnotationRange(document, marker);
					if (range != null) {
						return range;
					}
				} catch (BadLocationException | JavaModelException e) {
					JavaLanguageServerPlugin.logException(e.getMessage(), e);
				}
				int[] startPos = JsonRpcHelpers.toLine(document, start);
				int[] endPos = JsonRpcHelpers.toLine(document, end);
				return new Range(new Position(startPos[0], startPos[1]), new Position(endPos[0], endPos[1]));
			}
			return new Range(new Position(0, 0), new Position(0, 0));
		}
		int cStart = 0;
		int cEnd = 0;
		try {
			//Buildship doesn't provide markers for gradle files, Maven does
			if (marker.isSubtypeOf(IMavenConstants.MARKER_ID)) {
				cStart = marker.getAttribute(IMavenConstants.MARKER_COLUMN_START, -1);
				cEnd = marker.getAttribute(IMavenConstants.MARKER_COLUMN_END, -1);
			} else {
				if (marker.getAttribute(IJavaModelMarker.ID, -1) == IProblem.UndefinedType) {
					try {
						Range range = getAnnotationRange(document, marker);
						if (range != null) {
							return range;
						}
					} catch (BadLocationException e) {
						JavaLanguageServerPlugin.logException(e.getMessage(), e);
					}
				} else {
					int lineOffset = 0;
					try {
						lineOffset = document.getLineOffset(line);
					} catch (BadLocationException unlikelyException) {
						JavaLanguageServerPlugin.logException(unlikelyException.getMessage(), unlikelyException);
						return new Range(new Position(line, 0), new Position(line, 0));
					}
					cEnd = marker.getAttribute(IMarker.CHAR_END, -1) - lineOffset;
					cStart = marker.getAttribute(IMarker.CHAR_START, -1) - lineOffset;
				}
			}
		} catch (CoreException e) {
			JavaLanguageServerPlugin.logException(e.getMessage(), e);
		}
		cStart = Math.max(0, cStart);
		cEnd = Math.max(0, cEnd);
		return new Range(new Position(line, cStart), new Position(line, cEnd));
	}

	private static Range getAnnotationRange(IDocument document, IMarker marker) throws BadLocationException, JavaModelException {
		if (marker.getAttribute(IJavaModelMarker.ID, -1) == IProblem.UndefinedType) {
			int end = marker.getAttribute(IMarker.CHAR_END, -1);
			int start = marker.getAttribute(IMarker.CHAR_START, -1);
			if (start > 0) {
				start--;
				char ch = document.getChar(start);
				while (Character.isWhitespace(ch)) {
					start--;
					ch = document.getChar(start);
				}
				if (ch == '@') {
					return JDTUtils.toRange(document, start, end - start);
				}
			}
		}
		return null;
	}

	/**
	 * @param attribute
	 * @return
	 */
	private static DiagnosticSeverity convertSeverity(int severity) {
		if (severity == IMarker.SEVERITY_ERROR) {
			return DiagnosticSeverity.Error;
		}
		if (severity == IMarker.SEVERITY_WARNING) {
			return DiagnosticSeverity.Warning;
		}
		return DiagnosticSeverity.Information;
	}

	private void cleanUpDiagnostics(IResource resource) {
		cleanUpDiagnostics(resource, false);
	}

	private void cleanUpDiagnostics(IResource resource, boolean addTrailingSlash) {
		String uri = JDTUtils.getFileURI(resource);
		if (uri != null) {
			if (addTrailingSlash && !uri.endsWith("/")) {
				uri = uri + "/";
			}
			this.connection.publishDiagnostics(new PublishDiagnosticsParams(ResourceUtils.toClientUri(uri), Collections.emptyList()));
		}
	}

	private boolean isSupportedDiagnosticsResource(IResource resource) {
		if (resource.getType() == IResource.PROJECT) {
			return true;
		}

		IFile file = (IFile) resource;
		return JavaCore.isJavaLikeFileName(file.getName()) || projectsManager.isBuildFile(file);
	}
}
