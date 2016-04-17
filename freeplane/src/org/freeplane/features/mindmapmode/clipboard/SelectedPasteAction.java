/*
 *  Freeplane - mind map editor
 *  Copyright (C) 2008 Joerg Mueller, Daniel Polansky, Christian Foltin, Dimitry Polivaev
 *
 *  This file is modified by Dimitry Polivaev in 2008.
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
package org.freeplane.features.mindmapmode.clipboard;

import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Collection;

import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;

import org.freeplane.core.controller.Controller;
import org.freeplane.core.model.NodeModel;
import org.freeplane.core.resources.ResourceBundles;
import org.freeplane.core.ui.AFreeplaneAction;
import org.freeplane.features.common.clipboard.ClipboardController;
import org.freeplane.features.mindmapmode.clipboard.MClipboardController.IDataFlavorHandler;

class SelectedPasteAction extends AFreeplaneAction {
	/**
     * 
     */
    private static final long serialVersionUID = 1L;

	public SelectedPasteAction(final Controller controller) {
		super("SelectedPasteAction", controller);
	}

	public void actionPerformed(final ActionEvent e) {
		final MClipboardController clipboardController = (MClipboardController) ClipboardController.getController(getModeController());
		final Collection<IDataFlavorHandler> flavorHandlers = clipboardController.getFlavorHandlers();
		if(flavorHandlers.isEmpty()){
			return;
		}
		final JPanel options = createPane(flavorHandlers);
		if(JOptionPane.CANCEL_OPTION == JOptionPane.showConfirmDialog((Component) e.getSource(), 
			options, 
			e.getActionCommand(),
			JOptionPane.OK_CANCEL_OPTION)){
			return;
		}
		
		NodeModel parent = getController().getSelection().getSelected();
		clipboardController.paste(selectedHandler, parent, false, parent.isNewChildLeft());
    	selectedHandler = null;
	}
	private IDataFlavorHandler selectedHandler;
	
    private JPanel createPane(final Collection<IDataFlavorHandler> flavorHandlers) {
    	ButtonGroup group = new ButtonGroup();
    	JRadioButton[] buttons = new JRadioButton[flavorHandlers.size()];
    	int i = 0;
    	selectedHandler = null;
    	for(final IDataFlavorHandler handler : flavorHandlers){
    		final JRadioButton radioButton = new JRadioButton(ResourceBundles.getText(handler.getClass().getSimpleName()));
    		group.add(radioButton);
    		if(selectedHandler == null){
    			selectedHandler = handler;
    			radioButton.setSelected(true);
    		}
    		radioButton.addActionListener(new ActionListener(){

				public void actionPerformed(ActionEvent e) {
					selectedHandler = handler;
                }});
			buttons[i++] = radioButton; 
    	}
    	return createPane(buttons);
    }
    
    private JPanel createPane(JRadioButton[] radioButtons) {

        int numChoices = radioButtons.length;
        JPanel box = new JPanel();

        box.setLayout(new BoxLayout(box, BoxLayout.PAGE_AXIS));

        for (int i = 0; i < numChoices; i++) {
            box.add(radioButtons[i]);
        }
        return box;
    }


}
