/*
 *  Freeplane - mind map editor
 *  Copyright (C) 2008 Dimitry Polivaev
 *
 *  This file author is Dimitry Polivaev
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.freeplane.core.io;

import java.io.IOException;
import java.util.Enumeration;

import org.freeplane.core.extension.IExtension;
import org.freeplane.n3.nanoxml.XMLElement;

/**
 * @author Dimitry Polivaev
 * 17.01.2009
 */
public class UnknownElementWriter implements IExtensionAttributeWriter, IExtensionElementWriter {
	public void writeAttributes(final ITreeWriter writer, final Object userObject, final IExtension extension) {
		final UnknownElements elements = (UnknownElements) extension;
		final XMLElement unknownElements = elements.getUnknownElements();
		if (unknownElements != null) {
			final Enumeration<String> unknownAttributes = unknownElements.enumerateAttributeNames();
			while (unknownAttributes.hasMoreElements()) {
				final String name = unknownAttributes.nextElement();
				final String value = unknownElements.getAttribute(name, null);
				writer.addAttribute(name, value);
			}
		}
	}

	public void writeContent(final ITreeWriter writer, final Object element, final IExtension extension)
	        throws IOException {
		final UnknownElements elements = (UnknownElements) extension;
		final XMLElement unknownElements = elements.getUnknownElements();
		if (unknownElements != null) {
			final Enumeration<XMLElement> unknownChildren = unknownElements.enumerateChildren();
			while (unknownChildren.hasMoreElements()) {
				writer.addElement(null, unknownChildren.nextElement());
			}
		}
	}
}
