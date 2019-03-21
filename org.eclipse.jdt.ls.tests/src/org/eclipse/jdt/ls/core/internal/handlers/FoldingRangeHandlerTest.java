/*******************************************************************************
 * Copyright (c) 2019 Microsoft Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Microsoft Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.jdt.ls.core.internal.handlers;

import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.ls.core.internal.ClassFileUtil;
import org.eclipse.jdt.ls.core.internal.WorkspaceHelper;
import org.eclipse.jdt.ls.core.internal.managers.AbstractProjectsManagerBasedTest;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.FoldingRangeKind;
import org.eclipse.lsp4j.FoldingRangeRequestParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.Before;
import org.junit.Test;

public class FoldingRangeHandlerTest extends AbstractProjectsManagerBasedTest {

	private IProject project;

	@Before
	public void setup() throws Exception {
		importProjects(Arrays.asList("maven/foldingRange"));
		project = WorkspaceHelper.getProject("foldingRange");
	}

	@Test
	public void testFoldingRanges() throws Exception {
		testClass("org.apache.commons.lang3.text.WordUtils");
	}

	@Test
	public void testTypes() throws Exception {
		String className = "org.sample.TestFoldingRange";
		List<FoldingRange> foldingRanges = getFoldingRanges(className);
		assertTrue(foldingRanges.size() == 7);
		assertHasFoldingRange(2, 3, FoldingRangeKind.Imports, foldingRanges);
		assertHasFoldingRange(5, 7, FoldingRangeKind.Comment, foldingRanges);
		assertHasFoldingRange(8, 24, null, foldingRanges);
		assertHasFoldingRange(10, 14, FoldingRangeKind.Comment, foldingRanges);
		assertHasFoldingRange(19, 23, null, foldingRanges);
		assertHasFoldingRange(20, 22, null, foldingRanges);
	}

	@Test
	public void testErrorTypes() throws Exception {
		String className = "org.sample.TestUnmatchFoldingRange";
		List<FoldingRange> foldingRanges = getFoldingRanges(className);
		assertTrue(foldingRanges.size() == 3);
		assertHasFoldingRange(2, 12, null, foldingRanges);
		assertHasFoldingRange(3, 10, null, foldingRanges);
		assertHasFoldingRange(5, 7, null, foldingRanges);
	}

	@Test
	public void testRegionFoldingRanges() throws Exception {
		String className = "org.sample.RegionFoldingRange";
		List<FoldingRange> foldingRanges = getFoldingRanges(className);
		assertTrue(foldingRanges.size() == 7);
		assertHasFoldingRange(7, 15, FoldingRangeKind.Region, foldingRanges);
		assertHasFoldingRange(17, 24, FoldingRangeKind.Region, foldingRanges);
		assertHasFoldingRange(18, 20, FoldingRangeKind.Region, foldingRanges);
	}

	private void testClass(String className) throws CoreException {
		List<FoldingRange> foldingRanges = getFoldingRanges(className);
		for (FoldingRange range : foldingRanges) {
			assertTrue("Class: " + className + ", FoldingRange:" + range.getKind() + " - invalid location.", isValid(range));
		}
	}

	private List<FoldingRange> getFoldingRanges(String className) throws CoreException {
		String uri = ClassFileUtil.getURI(project, className);
		TextDocumentIdentifier identifier = new TextDocumentIdentifier(uri);
		FoldingRangeRequestParams params = new FoldingRangeRequestParams();
		params.setTextDocument(identifier);
		return new FoldingRangeHandler().foldingRange(params, monitor);
	}

	private boolean isValid(FoldingRange range) {
		return range != null && range.getStartLine() <= range.getEndLine();
	}

	private void assertHasFoldingRange(int startLine, int endLine, String expectedKind, Collection<FoldingRange> foldingRanges) {
		Optional<FoldingRange> range = foldingRanges.stream().filter(s -> s.getStartLine() == startLine && s.getEndLine() == endLine).findFirst();
		assertTrue("Expected type" + expectedKind, range.get().getKind() == expectedKind);
	}
}