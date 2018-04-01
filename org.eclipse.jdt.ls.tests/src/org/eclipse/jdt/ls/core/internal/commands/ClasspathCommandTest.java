/*******************************************************************************
 * Copyright (c) 2017 Microsoft Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Microsoft Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.jdt.ls.core.internal.commands;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.net.URI;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.ls.core.internal.JarFileContentProvider;
import org.eclipse.jdt.ls.core.internal.correction.TestOptions;
import org.eclipse.jdt.ls.core.internal.managers.AbstractProjectsManagerBasedTest;
import org.junit.Before;
import org.junit.Test;

public class ClasspathCommandTest extends AbstractProjectsManagerBasedTest {

	private IJavaProject fJProject1;

	@Before
	public void setup() throws Exception {
		fJProject1 = newEmptyProject();
		Hashtable<String, String> options = TestOptions.getDefaultOptions();
		fJProject1.setOptions(options);
	}

	@Test
	public void testEclipseProject() throws CoreException {
		ClasspathQuery query = new ClasspathQuery();
		query.setKind(ClasspathNodeKind.PROJECT);
		query.setProjectUri(getProjectUri(fJProject1));
		List<ClasspathNode> result = ClasspathCommand.getChildren(Arrays.asList(query), monitor);
		assertEquals(1, result.size());

		query.setPath(result.get(0).getPath());
		query.setKind(ClasspathNodeKind.CONTAINER);
		result = ClasspathCommand.getChildren(Arrays.asList(query), monitor);

		assertEquals(1, result.size());

		query.setRootPath(result.get(0).getPath());
		query.setKind(ClasspathNodeKind.JAR);
		result = ClasspathCommand.getChildren(Arrays.asList(query), monitor);

		assertEquals(38, result.size());

		query.setPath(result.get(0).getName());
		query.setKind(ClasspathNodeKind.PACKAGE);
		result = ClasspathCommand.getChildren(Arrays.asList(query), monitor);

		assertEquals(2, result.size());
		assertEquals("PropertyChangeListener.class", result.get(1).getName());
	}

	@Test
	public void testMavenProject() throws Exception {
		List<IProject> projects = importProjects("maven/salut");
		IJavaProject jProject = JavaCore.create(projects.get(1));
		ClasspathQuery query = new ClasspathQuery();
		query.setKind(ClasspathNodeKind.PROJECT);
		query.setProjectUri(getProjectUri(jProject));

		List<ClasspathNode> result = ClasspathCommand.getChildren(Arrays.asList(query), monitor);
		assertEquals(2, result.size());
		assertEquals("Maven Dependencies", result.get(1).getName());

		query.setPath(result.get(1).getPath());
		query.setKind(ClasspathNodeKind.CONTAINER);
		result = ClasspathCommand.getChildren(Arrays.asList(query), monitor);

		assertEquals(1, result.size());

		query.setRootPath(result.get(0).getPath());
		query.setKind(ClasspathNodeKind.JAR);
		result = ClasspathCommand.getChildren(Arrays.asList(query), monitor);

		assertEquals(13, result.size());
		assertEquals(ClasspathNodeKind.Folder, result.get(12).getKind());

		query.setPath(result.get(12).getPath());
		query.setKind(ClasspathNodeKind.Folder);
		result = ClasspathCommand.getChildren(Arrays.asList(query), monitor);
		ClasspathNode node = result.get(1);
		String content = (new JarFileContentProvider()).getContent(URI.create(node.getUri()), monitor);
		assertTrue(content.contains("Apache License"));

		query.setKind(ClasspathNodeKind.JAR);
		result = ClasspathCommand.getChildren(Arrays.asList(query), monitor);
		query.setPath(result.get(0).getName());
		query.setKind(ClasspathNodeKind.PACKAGE);
		result = ClasspathCommand.getChildren(Arrays.asList(query), monitor);

		assertEquals(28, result.size());
		assertEquals("AnnotationUtils.class", result.get(0).getName());
	}

	@Test
	public void testGradleProject() throws Exception {
		List<IProject> projects = importProjects("gradle/simple-gradle");
		IJavaProject jProject = JavaCore.create(projects.get(1));

		ClasspathQuery query = new ClasspathQuery();
		query.setKind(ClasspathNodeKind.PROJECT);
		query.setProjectUri(getProjectUri(jProject));

		List<ClasspathNode> result = ClasspathCommand.getChildren(Arrays.asList(query), monitor);
		assertEquals(2, result.size());
		assertEquals("Project and External Dependencies", result.get(1).getName());

		query.setPath(result.get(1).getPath());
		query.setKind(ClasspathNodeKind.CONTAINER);
		result = ClasspathCommand.getChildren(Arrays.asList(query), monitor);

		assertEquals(3, result.size());

		query.setRootPath(result.get(0).getPath());
		query.setKind(ClasspathNodeKind.JAR);
		result = ClasspathCommand.getChildren(Arrays.asList(query), monitor);

		assertEquals(5, result.size());

		query.setPath(result.get(0).getName());
		query.setKind(ClasspathNodeKind.PACKAGE);
		result = ClasspathCommand.getChildren(Arrays.asList(query), monitor);

		assertEquals(16, result.size());
		assertEquals("BaseDescription.class", result.get(0).getName());
	}

	private String getProjectUri(IJavaProject project) {
		return fromFilepathToUri(project.getProject().getLocation().toOSString());
	}

	private String fromFilepathToUri(String filePath) {
		return (new File(filePath)).toURI().toString();
	}
}
