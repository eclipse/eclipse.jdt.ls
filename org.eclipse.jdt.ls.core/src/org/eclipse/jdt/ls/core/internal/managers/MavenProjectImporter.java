/*******************************************************************************
 * Copyright (c) 2016-2017 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Red Hat Inc. - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal.managers;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jdt.ls.core.internal.AbstractProjectImporter;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.ProjectUtils;
import org.eclipse.jdt.ls.core.internal.preferences.PreferenceManager;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.embedder.MavenModelManager;
import org.eclipse.m2e.core.internal.preferences.MavenConfigurationImpl;
import org.eclipse.m2e.core.internal.preferences.ProblemSeverity;
import org.eclipse.m2e.core.project.IProjectConfigurationManager;
import org.eclipse.m2e.core.project.LocalProjectScanner;
import org.eclipse.m2e.core.project.MavenProjectInfo;
import org.eclipse.m2e.core.project.MavenUpdateRequest;
import org.eclipse.m2e.core.project.ProjectImportConfiguration;

public class MavenProjectImporter extends AbstractProjectImporter {

	private static final String POM_FILE = "pom.xml";

	private Set<MavenProjectInfo> projectInfos = null;

	private IProjectConfigurationManager configurationManager;

	public MavenProjectImporter() {
		this(MavenPlugin.getProjectConfigurationManager());
	}

	public MavenProjectImporter(IProjectConfigurationManager configurationManager) {
		this.configurationManager = configurationManager;
	}


	@Override
	public boolean applies(IProgressMonitor monitor) throws OperationCanceledException, CoreException {
		PreferenceManager preferencesManager = JavaLanguageServerPlugin.getPreferencesManager();
		if (preferencesManager != null && !preferencesManager.getPreferences().isImportMavenEnabled()) {
			return false;
		}
		Set<MavenProjectInfo> files = getMavenProjectInfo(monitor);
		if (files != null) {
			Iterator<MavenProjectInfo> iter = files.iterator();
			while (iter.hasNext()) {
				MavenProjectInfo projectInfo = iter.next();
				File dir = projectInfo.getPomFile() == null ? null : projectInfo.getPomFile().getParentFile();
				if (dir != null && exclude(dir.toPath())) {
					iter.remove();
				}
			}
		}
		return files != null && !files.isEmpty();
	}

	private boolean exclude(java.nio.file.Path path) {
		List<String> javaImportExclusions = JavaLanguageServerPlugin.getPreferencesManager().getPreferences().getJavaImportExclusions();
		if (javaImportExclusions != null) {
			for (String pattern : javaImportExclusions) {
				PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
				if (matcher.matches(path)) {
					return true;
				}
			}
		}
		return false;
	}

	synchronized Set<MavenProjectInfo> getMavenProjectInfo(IProgressMonitor monitor) throws OperationCanceledException {
		if (projectInfos == null) {
			projectInfos = collectMavenProjectInfo(monitor);
		}
		return projectInfos;
	}

	Set<MavenProjectInfo> collectMavenProjectInfo(IProgressMonitor monitor) throws OperationCanceledException {
		MavenModelManager modelManager = MavenPlugin.getMavenModelManager();
		return getMavenProjects(getProjectDirectory(), modelManager, monitor);
	}

	@Override
	public void reset() {
		projectInfos = null;
	}

	@Override
	@SuppressWarnings("restriction")
	public void importToWorkspace(IProgressMonitor monitor) throws CoreException, OperationCanceledException {
		JavaLanguageServerPlugin.logInfo("Importing Maven project(s)");
		MavenConfigurationImpl configurationImpl = (MavenConfigurationImpl)MavenPlugin.getMavenConfiguration();
		configurationImpl.setDownloadSources(true);
		configurationImpl.setNotCoveredMojoExecutionSeverity(ProblemSeverity.ignore.toString());
		SubMonitor subMonitor = SubMonitor.convert(monitor, 100);
		Set<MavenProjectInfo> files = getMavenProjectInfo(subMonitor.split(5));
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
		Collection<IProject> projects = new LinkedHashSet<>();
		Collection<MavenProjectInfo> toImport = new LinkedHashSet<>();
		//Separate existing projects from new ones
		for (MavenProjectInfo projectInfo : files) {
			File pom = projectInfo.getPomFile();
			IContainer container = root.getContainerForLocation(new Path(pom.getAbsolutePath()));
			if (container == null) {
				toImport.add(projectInfo);
			} else {
				IProject project = container.getProject();
				if (ProjectUtils.isMavenProject(project)) {
					projects.add(container.getProject());
				} else if (project != null) {
					//Project doesn't have the Maven nature, so we (re)import it
					toImport.add(projectInfo);
				}
			}
		}
		if (!toImport.isEmpty()) {
			ProjectImportConfiguration importConfig = new ProjectImportConfiguration();
			configurationManager.importProjects(toImport, importConfig, subMonitor.split(95));
		}
		updateProjects(projects, monitor);
	}

	private File getProjectDirectory() {
		return rootFolder;
	}

	private void updateProjects(Collection<IProject> projects, IProgressMonitor monitor) throws CoreException {
		if (projects.isEmpty()) {
			return;
		}
		Iterator<IProject> iterator = projects.iterator();
		while (iterator.hasNext()) {
			IProject project = iterator.next();
			project.open(monitor);
			project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
			if (!needsMavenUpdate(project)) {
				iterator.remove();
			}
		}
		if (projects.isEmpty()) {
			return;
		}

		new WorkspaceJob("Update Maven project configuration") {
			@Override
			public IStatus runInWorkspace(IProgressMonitor monitor) throws CoreException {
				for (IProject project : projects) {
					if (monitor.isCanceled()) {
						return Status.CANCEL_STATUS;
					}
					JavaLanguageServerPlugin.logInfo("Updating project configuration for Maven project " + project.getName());
					MavenUpdateRequest request = new MavenUpdateRequest(project, MavenPlugin.getMavenConfiguration().isOffline(), true);
					configurationManager.updateProjectConfiguration(request, monitor);
				}
				return Status.OK_STATUS;
			}
		}.schedule();
	}

	private boolean needsMavenUpdate(IProject project) {
		//TODO need to figure how to detect pom.xml changed since last time the server was running
		return true;
	}

	private Set<MavenProjectInfo> getMavenProjects(File directory, MavenModelManager modelManager, IProgressMonitor monitor) throws OperationCanceledException {
		if (directory == null) {
			return Collections.emptySet();
		}
		try {
			LocalProjectScanner scanner = new LocalProjectScanner(directory.getParentFile(), directory.toString(), false, modelManager);
			scanner.run(monitor);
			return collectProjects(scanner.getProjects());
		} catch (InterruptedException e) {
			throw new OperationCanceledException();
		}
	}

	public boolean isMavenProject() {
		return  isMavenProject(getProjectDirectory());
	}

	private boolean isMavenProject(File dir) {
		if (!isReadable(dir)
				|| !dir.isDirectory()) {
			return false;
		}
		return isReadable(new File(dir, POM_FILE));
	}

	private boolean isReadable(File destination) {
		return destination != null
				&& destination.canRead();
	}

	public Set<MavenProjectInfo> collectProjects(
			Collection<MavenProjectInfo> projects) {
		return new LinkedHashSet<MavenProjectInfo>() {
			private static final long serialVersionUID = 1L;

			public Set<MavenProjectInfo> collectProjects(
					Collection<MavenProjectInfo> projects) {
				for (MavenProjectInfo projectInfo : projects) {
					add(projectInfo);
					collectProjects(projectInfo.getProjects());
				}
				return this;
			}
		}.collectProjects(projects);
	}

}
