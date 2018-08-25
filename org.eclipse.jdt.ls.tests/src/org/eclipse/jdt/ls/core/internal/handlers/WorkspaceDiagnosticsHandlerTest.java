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
package org.eclipse.jdt.ls.core.internal.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.apache.commons.io.IOUtils;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jdt.ls.core.internal.JavaClientConnection;
import org.eclipse.jdt.ls.core.internal.managers.AbstractProjectsManagerBasedTest;
import org.eclipse.jface.text.IDocument;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.m2e.core.internal.IMavenConstants;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

/**
 * @author Fred Bricon
 *
 */
@SuppressWarnings("restriction")
@RunWith(MockitoJUnitRunner.class)
public class WorkspaceDiagnosticsHandlerTest extends AbstractProjectsManagerBasedTest {
	@Mock
	private JavaClientConnection connection;

	@InjectMocks
	private WorkspaceDiagnosticsHandler handler;

	@Before
	public void setup() throws Exception {
		handler.addResourceChangeListener();
	}

	@Test
	public void testToDiagnosticsArray() throws Exception {
		String msg1 = "Something's wrong Jim";
		IMarker m1 = createMarker(IMarker.SEVERITY_WARNING, msg1, 2, 95, 100);

		String msg2 = "He's dead";
		IMarker m2 = createMarker(IMarker.SEVERITY_ERROR, msg2, 10, 1015, 1025);

		String msg3 = "It's probably time to panic";
		IMarker m3 = createMarker(42, msg3, 100, 10000, 10005);

		IDocument d = mock(IDocument.class);
		when(d.getLineOffset(1)).thenReturn(90);
		when(d.getLineOffset(9)).thenReturn(1000);
		when(d.getLineOffset(99)).thenReturn(10000);

		List<Diagnostic> diags = handler.toDiagnosticsArray(d, new IMarker[]{m1, m2, m3});
		assertEquals(3, diags.size());

		Range r;
		Diagnostic d1 = diags.get(0);
		assertEquals(msg1, d1.getMessage());
		assertEquals(DiagnosticSeverity.Warning, d1.getSeverity());
		r = d1.getRange();
		assertEquals(1, r.getStart().getLine());
		assertEquals(5, r.getStart().getCharacter());
		assertEquals(1, r.getEnd().getLine());
		assertEquals(10, r.getEnd().getCharacter());

		Diagnostic d2 = diags.get(1);
		assertEquals(msg2, d2.getMessage());
		assertEquals(DiagnosticSeverity.Error, d2.getSeverity());
		r = d2.getRange();
		assertEquals(9, r.getStart().getLine());
		assertEquals(15, r.getStart().getCharacter());
		assertEquals(9, r.getEnd().getLine());
		assertEquals(25, r.getEnd().getCharacter());

		Diagnostic d3 = diags.get(2);
		assertEquals(msg3, d3.getMessage());
		assertEquals(DiagnosticSeverity.Information, d3.getSeverity());
		r = d3.getRange();
		assertEquals(99, r.getStart().getLine());
		assertEquals(0, r.getStart().getCharacter());
		assertEquals(99, r.getEnd().getLine());
		assertEquals(5, r.getEnd().getCharacter());

	}

	@Test
	public void testTaskMarkers() throws Exception {
		//import project
		importProjects("eclipse/hello");
		ArgumentCaptor<PublishDiagnosticsParams> captor = ArgumentCaptor.forClass(PublishDiagnosticsParams.class);
		verify(connection, atLeastOnce()).publishDiagnostics(captor.capture());

		List<PublishDiagnosticsParams> allCalls = captor.getAllValues();
		Collections.reverse(allCalls);
		projectsManager.setConnection(client);

		Optional<PublishDiagnosticsParams> taskDiags = allCalls.stream().filter(p -> p.getUri().endsWith("TaskMarkerTest.java")).findFirst();
		assertTrue("No TaskMarkerTest.java markers were found", taskDiags.isPresent());
		List<Diagnostic> diags = taskDiags.get().getDiagnostics();
		assertEquals("Some marker is missing", 3, diags.size());
		long todoMarkers = diags.stream().filter(p -> p.getMessage().startsWith("TODO")).count();
		assertEquals("A TODO marker is missing", todoMarkers, 2);
		Collections.sort(diags, new Comparator<Diagnostic>() {

			@Override
			public int compare(Diagnostic o1, Diagnostic o2) {
				return o1.getMessage().compareTo(o2.getMessage());
			}
		});
		Range r;
		Diagnostic d = diags.get(1);
		assertEquals("TODO task 2", d.getMessage());
		assertEquals(DiagnosticSeverity.Information, d.getSeverity());
		r = d.getRange();
		assertEquals(11, r.getStart().getLine());
		assertEquals(11, r.getStart().getCharacter());
		assertEquals(11, r.getEnd().getLine());
		assertEquals(22, r.getEnd().getCharacter());
		d = diags.get(0);
		assertEquals("TODO task 1", d.getMessage());
		assertEquals(DiagnosticSeverity.Information, d.getSeverity());
		r = d.getRange();
		assertEquals(9, r.getStart().getLine());
		assertEquals(11, r.getStart().getCharacter());
		assertEquals(9, r.getEnd().getLine());
		assertEquals(22, r.getEnd().getCharacter());
	}

	@Test
	public void testMavenMarkers() throws Exception {
		String msg1 = "Some dependency is missing";
		IMarker m1 = createMavenMarker(IMarker.SEVERITY_ERROR, msg1, 2, 95, 100);

		IDocument d = mock(IDocument.class);
		when(d.getLineOffset(1)).thenReturn(90);

		List<Diagnostic> diags = handler.toDiagnosticsArray(d, new IMarker[]{m1, null});
		assertEquals(1, diags.size());

		Range r;
		Diagnostic d1 = diags.get(0);
		assertEquals(msg1, d1.getMessage());
		assertEquals(DiagnosticSeverity.Error, d1.getSeverity());
		r = d1.getRange();
		assertEquals(1, r.getStart().getLine());
		assertEquals(95, r.getStart().getCharacter());
		assertEquals(1, r.getEnd().getLine());
		assertEquals(100, r.getEnd().getCharacter());
	}

	@Test
	public void testMarkerListening() throws Exception {
		//import project
		importProjects("maven/broken");

		ArgumentCaptor<PublishDiagnosticsParams> captor = ArgumentCaptor.forClass(PublishDiagnosticsParams.class);
		verify(connection, atLeastOnce()).publishDiagnostics(captor.capture());

		List<PublishDiagnosticsParams> allCalls = captor.getAllValues();
		Collections.reverse(allCalls);
		projectsManager.setConnection(client);

		Optional<PublishDiagnosticsParams> fooDiags = allCalls.stream().filter(p -> p.getUri().endsWith("Foo.java")).findFirst();
		assertTrue("No Foo.java errors were found", fooDiags.isPresent());
		List<Diagnostic> diags = fooDiags.get().getDiagnostics();
		Comparator<Diagnostic> comparator = (Diagnostic d1, Diagnostic d2) -> {
			int diff = d1.getRange().getStart().getLine() - d2.getRange().getStart().getLine();
			if (diff == 0) {
				diff = d1.getMessage().compareTo(d2.getMessage());
			}
			return diff;
		};
		Collections.sort(diags, comparator );
		assertEquals(diags.toString(), 2, diags.size());
		assertEquals("The import org cannot be resolved", diags.get(0).getMessage());
		assertEquals("StringUtils cannot be resolved", diags.get(1).getMessage());

		Optional<PublishDiagnosticsParams> pomDiags = allCalls.stream().filter(p -> p.getUri().endsWith("pom.xml")).findFirst();
		assertTrue("No pom.xml errors were found", pomDiags.isPresent());
		diags = pomDiags.get().getDiagnostics();
		Collections.sort(diags, comparator);
		assertEquals(diags.toString(), 3, diags.size());
		assertTrue(diags.get(0).getMessage()
				.startsWith("For artifact {org.apache.commons:commons-lang3:null:jar}: The version cannot be empty. (org.apache.maven.plugins:maven-resources-plugin:2.6:resources:default-resources:process-resources)"));
		assertTrue(diags.get(1).getMessage()
				.startsWith("For artifact {org.apache.commons:commons-lang3:null:jar}: The version cannot be empty. (org.apache.maven.plugins:maven-resources-plugin:2.6:testResources:default-testResources:process-test-resources)"));
		assertEquals("Project build error: 'dependencies.dependency.version' for org.apache.commons:commons-lang3:jar is missing.", diags.get(2).getMessage());
	}

	@Test
	public void testProjectLevelMarkers() throws Exception {
		//import project
		importProjects("maven/broken");
		ArgumentCaptor<PublishDiagnosticsParams> captor = ArgumentCaptor.forClass(PublishDiagnosticsParams.class);
		verify(connection, atLeastOnce()).publishDiagnostics(captor.capture());
		List<PublishDiagnosticsParams> allCalls = captor.getAllValues();
		Collections.reverse(allCalls);
		projectsManager.setConnection(client);
		Optional<PublishDiagnosticsParams> projectDiags = allCalls.stream().filter(p -> p.getUri().endsWith("maven/broken")).findFirst();
		assertTrue("No maven/broken errors were found", projectDiags.isPresent());
		List<Diagnostic> diags = projectDiags.get().getDiagnostics();
		Comparator<Diagnostic> comparator = (Diagnostic d1, Diagnostic d2) -> {
			int diff = d1.getRange().getStart().getLine() - d2.getRange().getStart().getLine();
			if (diff == 0) {
				diff = d1.getMessage().compareTo(d2.getMessage());
			}
			return diff;
		};
		Collections.sort(diags, comparator);
		assertEquals(diags.toString(), 5, diags.size());
		assertTrue(diags.get(4).getMessage().startsWith("The compiler compliance specified is 1.7 but a JRE 1.8 is used"));
	}

	@Test
	public void testProjectConfigurationIsNotUpToDate() throws Exception {
		InitHandler initHandler = new InitHandler(projectsManager, preferenceManager, connection);
		initHandler.addWorkspaceDiagnosticsHandler();
		//import project
		importProjects("maven/salut");
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject("salut");
		IFile pom = project.getFile("/pom.xml");
		assertTrue(pom.exists());
		try (InputStream is = pom.getContents(); InputStream nis = new ByteArrayInputStream(change(is).getBytes())) {
			pom.setContents(nis, IResource.FORCE, null);
		}
		ResourcesPlugin.getWorkspace().build(IncrementalProjectBuilder.FULL_BUILD, monitor);
		ArgumentCaptor<PublishDiagnosticsParams> captor = ArgumentCaptor.forClass(PublishDiagnosticsParams.class);
		verify(connection, atLeastOnce()).publishDiagnostics(captor.capture());
		List<PublishDiagnosticsParams> allCalls = captor.getAllValues();
		Collections.reverse(allCalls);
		projectsManager.setConnection(client);
		Optional<PublishDiagnosticsParams> projectDiags = allCalls.stream().filter(p -> p.getUri().endsWith("maven/salut")).findFirst();
		assertTrue("No maven/salut errors were found", projectDiags.isPresent());
		List<Diagnostic> diags = projectDiags.get().getDiagnostics();
		Comparator<Diagnostic> comparator = (Diagnostic d1, Diagnostic d2) -> {
			int diff = d1.getRange().getStart().getLine() - d2.getRange().getStart().getLine();
			if (diff == 0) {
				diff = d1.getMessage().compareTo(d2.getMessage());
			}
			return diff;
		};
		Collections.sort(diags, comparator);
		assertEquals(diags.toString(), 3, diags.size());
		Diagnostic diag = diags.get(1);
		assertTrue(diag.getMessage().startsWith("Project configuration is not up-to-date with pom.xml, requires an update."));
		assertEquals(diag.getSeverity(), DiagnosticSeverity.Warning);
	}

	private String change(InputStream is) throws IOException {
		String str = IOUtils.toString(is);
		String newStr = str.replaceAll("1.7", "1.8");
		return newStr;
	}

	private IMarker createMarker(int severity, String msg, int line, int start, int end) {
		IMarker m = mock(IMarker.class);
		when(m.exists()).thenReturn(true);
		when(m.getAttribute(IMarker.SEVERITY, -1)).thenReturn(severity);
		when(m.getAttribute(IMarker.MESSAGE, "")).thenReturn(msg);
		when(m.getAttribute(IMarker.LINE_NUMBER, -1)).thenReturn(line);
		when(m.getAttribute(IMarker.CHAR_START, -1)).thenReturn(start);
		when(m.getAttribute(IMarker.CHAR_END, -1)).thenReturn(end);
		return m;
	}

	private IMarker createMavenMarker(int severity, String msg, int line, int start, int end) throws Exception {
		IMarker m = createMarker(severity, msg, line, start, end);
		when(m.isSubtypeOf(IMavenConstants.MARKER_ID)).thenReturn(true);
		when(m.getAttribute(IMarker.MESSAGE, "")).thenReturn(msg);
		when(m.getAttribute(IMavenConstants.MARKER_COLUMN_START, -1)).thenReturn(start);
		when(m.getAttribute(IMavenConstants.MARKER_COLUMN_END, -1)).thenReturn(end);
		return m;
	}

	@After
	public void removeResourceChangeListener() {
		handler.removeResourceChangeListener();
	}

}
