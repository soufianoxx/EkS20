/*
 *  Freeplane - mind map editor
 *  Copyright (C) 2008 Joerg Mueller, Daniel Polansky, Christian Foltin, Dimitry Polivaev
 *
 *  This file is created by Dimitry Polivaev in 2008.
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
package org.freeplane.features.url.mindmapmode;

import java.awt.Component;
import java.awt.dnd.DropTarget;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.SequenceInputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.channels.FileLock;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Set;
import java.util.TreeSet;
import java.util.Vector;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.swing.Box;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileFilter;
import javax.swing.plaf.FileChooserUI;
import javax.swing.plaf.basic.BasicFileChooserUI;

import org.freeplane.core.extension.IExtension;
import org.freeplane.core.resources.ResourceController;
import org.freeplane.core.resources.components.ComboProperty;
import org.freeplane.core.resources.components.IPropertyControl;
import org.freeplane.core.resources.components.IPropertyControlCreator;
import org.freeplane.core.resources.components.OptionPanelBuilder;
import org.freeplane.core.ui.CaseSensitiveFileNameExtensionFilter;
import org.freeplane.core.ui.FileOpener;
import org.freeplane.core.ui.IndexedTree;
import org.freeplane.core.ui.LabelAndMnemonicSetter;
import org.freeplane.core.ui.components.JFreeplaneCustomizableFileChooser;
import org.freeplane.core.ui.components.OptionalDontShowMeAgainDialog;
import org.freeplane.core.ui.components.OptionalDontShowMeAgainDialog.MessageType;
import org.freeplane.core.ui.components.UITools;
import org.freeplane.core.util.Compat;
import org.freeplane.core.util.FileUtils;
import org.freeplane.core.util.LogUtils;
import org.freeplane.core.util.TextUtils;
import org.freeplane.features.link.LinkController;
import org.freeplane.features.map.MapChangeEvent;
import org.freeplane.features.map.MapModel;
import org.freeplane.features.map.MapWriter.Mode;
import org.freeplane.features.map.NodeModel;
import org.freeplane.features.map.mindmapmode.DocuMapAttribute;
import org.freeplane.features.map.mindmapmode.MMapController;
import org.freeplane.features.map.mindmapmode.MMapModel;
import org.freeplane.features.mode.Controller;
import org.freeplane.features.mode.ModeController;
import org.freeplane.features.mode.mindmapmode.MModeController;
import org.freeplane.features.styles.MapStyle;
import org.freeplane.features.text.TextController;
import org.freeplane.features.ui.IMapViewChangeListener;
import org.freeplane.features.url.IMapInputStreamConverter;
import org.freeplane.features.url.MapVersionInterpreter;
import org.freeplane.features.url.UrlManager;
import org.freeplane.n3.nanoxml.XMLException;
import org.freeplane.n3.nanoxml.XMLParseException;
import org.freeplane.view.swing.features.filepreview.MindMapPreview;
import org.freeplane.view.swing.features.filepreview.MindMapPreviewWithOptions;

/**
 * @author Dimitry Polivaev
 */
public class MFileManager extends UrlManager implements IMapViewChangeListener {
	public static final String STANDARD_TEMPLATE = "standard_template";
	private static final String DEFAULT_SAVE_DIR_PROPERTY = "default_save_dir";
	private static final String BACKUP_EXTENSION = "bak";
    static final String BACKUP_DIR = ".backup";
	private static final int DEBUG_OFFSET = 0;

	static private class BackupFlag implements IExtension {
	}

	enum MindmapDirectory{
	    MINDMAPS("mindmaps_desc"), TEMPLATES("template_dir"), USER_TEMPLATES("user_template_dir");
	    final String key;

        MindmapDirectory(String key){
	        this.key = key;
	    }
	}
	static private class MindMapDirectoryFilter extends FileFilter {

	    final File directory;
        final String description;


		MindMapDirectoryFilter(MindmapDirectory mindmapDirectory, File directory) {
            this.directory = directory;
            this.description = TextUtils.getText(mindmapDirectory.key) + " (*.mm)";
        }

        @Override
		public boolean accept(final File f) {
			if (f.isDirectory()) {
				return true;
			}
			final String extension = FileUtils.getExtension(f.getName());
			if (extension != null) {
				if (extension.equals(UrlManager.FREEPLANE_FILE_EXTENSION_WITHOUT_DOT)) {
					return true;
				}
				else {
					return false;
				}
			}
			return false;
		}

		@Override
		public String getDescription() {
            return description;
		}
	}

    private final static FileFilter MINDMAP_FILE_FILTER = new CaseSensitiveFileNameExtensionFilter("mm", TextUtils.getText("mindmaps_desc"));
	private static final String BACKUP_FILE_NUMBER = "backup_file_number";
	private static File singleBackupDirectory;

	private File[] findFileRevisions(final File file, final File backupDir, final AlternativeFileMode mode) {
		final String fileExtensionPattern;
		if (mode == AlternativeFileMode.ALL)
			fileExtensionPattern = "(" + BACKUP_EXTENSION + "|" + DoAutomaticSave.AUTOSAVE_EXTENSION + ")";
		else
			fileExtensionPattern = DoAutomaticSave.AUTOSAVE_EXTENSION;
		final Pattern pattern = Pattern
		    .compile("^" + Pattern.quote(backupFileName(file)) + "\\.+\\d+\\." + fileExtensionPattern);
		if (backupDir.exists()) {
			final File[] fileList = backupDir.listFiles(new java.io.FileFilter() {
				@Override
				public boolean accept(final File f) {
					final String name = f.getName();
					return pattern.matcher(name).matches() && f.isFile()
					// && (f.lastModified() > (file.lastModified() - DEBUG_OFFSET) || name.endsWith(BACKUP_EXTENSION))
					        && (mode == AlternativeFileMode.ALL
					                || f.lastModified() > (file.lastModified() - DEBUG_OFFSET));
				}
			});
			return fileList;
		}
		return new File[0];
	}

	/** prevents name conflicts with singleBackupDirectory in most cases (uses the file's hashcode). */
	private static String backupFileName(final File file) {
		if (singleBackupDirectory == null)
			return file.getName();
		return file.getName() + "." + file.hashCode();
	}

	private static void backupFile(final File file, final int backupFileNumber, final String extension) {
		if (backupFileNumber == 0) {
			return;
		}
		final File backupDir = MFileManager.backupDir(file);
		backupDir.mkdir();
		if (backupDir.exists()) {
			final File backupFile = MFileManager.renameBackupFiles(backupDir, file, backupFileNumber, extension);
			if (!backupFile.exists()) {
				performBackup(file, backupFile);
			}
		}
	}

	private static void performBackup(final File file, final File backupFile) {
		try {
			FileUtils.copyFile(file, backupFile);
			backupFile.setLastModified(file.lastModified());
		}
		catch (IOException e) {
		}
	}

	static File backupDir(final File file) {
		if (singleBackupDirectory != null)
			return singleBackupDirectory;
		else if (file == null) {
           return new File(ResourceController.getResourceController()
                    .getFreeplaneUserDirectory(), BACKUP_DIR);
        }
		else
		    return new File(file.getParentFile(), BACKUP_DIR);
	}

	static File createBackupFile(final File backupDir, final File file, final int number, final String extension) {
		return new File(backupDir, backupFileName(file) + '.' + number + '.' + extension);
	}

	static File renameBackupFiles(final File backupDir, final File file, final int backupFileNumber,
	                              final String extension) {
		if (backupFileNumber == 0) {
			return null;
		}
		for (int i = backupFileNumber + 1;; i++) {
			final File newFile = MFileManager.createBackupFile(backupDir, file, i, extension);
			if (!newFile.exists()) {
				break;
			}
			newFile.delete();
		}
		int i = backupFileNumber;
		for (;;) {
			final File newFile = MFileManager.createBackupFile(backupDir, file, i, extension);
			if (newFile.exists()) {
				break;
			}
			i--;
			if (i == 0) {
				break;
			}
		}
		if (i < backupFileNumber) {
			return MFileManager.createBackupFile(backupDir, file, i + 1, extension);
		}
		for (i = 1; i < backupFileNumber; i++) {
			final File newFile = MFileManager.createBackupFile(backupDir, file, i, extension);
			final File oldFile = MFileManager.createBackupFile(backupDir, file, i + 1, extension);
			newFile.delete();
			if (!oldFile.renameTo(newFile)) {
				return null;
			}
		}
		return MFileManager.createBackupFile(backupDir, file, backupFileNumber, extension);
	}

	public MFileManager() {
		super();
		setLastCurrentDir(new File(getDefaultSaveDirFromPrefs()));
	}

	private String getDefaultSaveDirFromPrefs() {
		return ResourceController.getResourceController().getProperty(DEFAULT_SAVE_DIR_PROPERTY);
	}

	@Override
	protected void init() {
		super.init();
		createActions();
		createPreferences();
		if (ResourceController.getResourceController().getBooleanProperty("single_backup_directory")) {
			String value = ResourceController.getResourceController().getProperty("single_backup_directory_path");
			String freeplaneUserDirectory = ResourceController.getResourceController().getFreeplaneUserDirectory();
			value = TextUtils.replaceAtBegin(value, "{freeplaneuserdir}", freeplaneUserDirectory);
			singleBackupDirectory = new File(value);
		}
	}

    private void createPreferences() {
		final MModeController modeController = (MModeController) Controller.getCurrentModeController();
		final OptionPanelBuilder optionPanelBuilder = modeController.getOptionPanelBuilder();
		optionPanelBuilder.addCreator("Environment/load", new IPropertyControlCreator() {
			@Override
			public IPropertyControl createControl() {
				final Set<String> charsets = Charset.availableCharsets().keySet();
				final LinkedList<String> charsetList = new LinkedList<String>(charsets);
				charsetList.addFirst("JVMdefault");
				final LinkedList<String> charsetTranslationList = new LinkedList<String>(charsets);
				charsetTranslationList.addFirst(TextUtils.getText("OptionPanel.default"));
				return new ComboProperty("default_charset", charsetList, charsetTranslationList);
			}
		}, IndexedTree.AS_CHILD);
		optionPanelBuilder.addCreator("Environment/files/skip_template_selection", new IPropertyControlCreator() {
			@Override
			public IPropertyControl createControl() {
				final Collection<String> templates = collectAvailableMapTemplates();
				ComboProperty comboProperty = new ComboProperty(STANDARD_TEMPLATE, templates, templates);
				comboProperty.setEditable(true);
				return comboProperty;
			}
		}, IndexedTree.BEFORE);
	}

	private void backup(final File file) {
		if (file == null) {
			return;
		}
		final int backupFileNumber = ResourceController.getResourceController().getIntProperty(BACKUP_FILE_NUMBER, 0);
		MFileManager.backupFile(file, backupFileNumber, BACKUP_EXTENSION);
	}

	private void createActions() {
		final Controller controller = Controller.getCurrentController();
		final ModeController modeController = controller.getModeController();
		controller.addAction(new OpenAction());
		controller.addAction(new OpenURLMapAction());
		controller.addAction(new NewMapAction());
		final File userTemplates = defaultUserTemplateDir();
		userTemplates.mkdir();
		modeController.addAction(new NewMapFromTemplateAction("new_map_from_user_templates"));
		modeController.addAction(new SaveAction());
		modeController.addAction(new SaveAsAction());
		modeController.addAction(new ExportBranchAction());
		modeController.addAction(new ImportBranchAction());
		modeController.addAction(new ImportLinkedBranchAction());
		modeController.addAction(new ImportLinkedBranchWithoutRootAction());
		modeController.addAction(new ImportExplorerFavoritesAction());
		modeController.addAction(new ImportFolderStructureAction());
		modeController.addAction(new RevertAction());
		modeController.addAction(new OpenUserDirAction());
	}

	public JFreeplaneCustomizableFileChooser getMindMapFileChooser() {

	    final JFreeplaneCustomizableFileChooser fileChooser = super.getFileChooser();
        final File mapsDirectory = fileChooser.getCurrentDirectory();
        final File templateDir = defaultStandardTemplateDir();
        final File userTemplateDir = defaultUserTemplateDir();

        MindMapDirectoryFilter maps = new MindMapDirectoryFilter(MindmapDirectory.MINDMAPS, mapsDirectory);
        MindMapDirectoryFilter templates = new MindMapDirectoryFilter(MindmapDirectory.TEMPLATES, templateDir);
        MindMapDirectoryFilter userTemplates = new MindMapDirectoryFilter(MindmapDirectory.USER_TEMPLATES, userTemplateDir);

        fileChooser.addChoosableFileFilter(maps);
        fileChooser.setFileFilter(maps);
        fileChooser.addChoosableFileFilter(templates);
        fileChooser.addChoosableFileFilter(userTemplates);

        fileChooser.addPropertyChangeListener(JFileChooser.FILE_FILTER_CHANGED_PROPERTY, new PropertyChangeListener() {
                @Override
                public void propertyChange(final PropertyChangeEvent evt) {
                    final FileFilter filter = fileChooser.getFileFilter();
                    if(! (filter instanceof MindMapDirectoryFilter)){
                        return;
                    }
                    File newDirectory = ((MindMapDirectoryFilter)filter).directory;
                    if(! fileChooser.getCurrentDirectory().equals(newDirectory)) {
                        fileChooser.setCurrentDirectory(newDirectory);
                    }
                }
            });

        fileChooser.addPropertyChangeListener(JFileChooser.DIRECTORY_CHANGED_PROPERTY, new PropertyChangeListener() {
            @Override
            public void propertyChange(final PropertyChangeEvent evt) {
                if(fileChooser.getDialogType() == JFileChooser.OPEN_DIALOG) {
                    resetNonExistingFileName();
                }
            }

            private void resetNonExistingFileName() {
                FileChooserUI ui = fileChooser.getUI();
                if(ui instanceof BasicFileChooserUI) {
                    BasicFileChooserUI basicUi = (BasicFileChooserUI) ui;
                    File newSelectedFile = new File (fileChooser.getCurrentDirectory(), basicUi.getFileName());
                    if (! newSelectedFile.exists()) {
                        basicUi.setFileName("");
                    }
                }
            };
        });
	    return fileChooser;
	}

    public FileFilter getFileFilter() {
		return MINDMAP_FILE_FILTER;
	};


	/**
	 * Creates a proposal for a file name to save the map. Removes all illegal
	 * characters. Fixed: When creating file names based on the text of the root
	 * node, now all the extra unicode characters are replaced with _. This is
	 * not very good. For chinese content, you would only get a list of ______
	 * as a file name. Only characters special for building file paths shall be
	 * removed (rather than replaced with _), like : or /. The exact list of
	 * dangeous characters needs to be investigated. 0.8.0RC3. Keywords: suggest
	 * file name.
	 *
	 * @param map
	 */
	private String getFileNameProposal(final MapModel map) {
		String rootText = TextController.getController().getPlainTransformedTextWithoutNodeNumber((map.getRootNode()));
		rootText = FileUtils.validFileNameOf(rootText);
		return rootText;
	}

	public URI getLinkByFileChooser(final MapModel map) {
		JFileChooser chooser = null;
		final File file = map.getFile();
		if (file == null && LinkController.getLinkType() == LinkController.LINK_RELATIVE_TO_MINDMAP) {
			JOptionPane.showMessageDialog(
			    Controller.getCurrentController().getViewController().getCurrentRootComponent(),
			    TextUtils.getText("not_saved_for_link_error"), "Freeplane", JOptionPane.WARNING_MESSAGE);
			return null;
		}
		if (getLastCurrentDir() != null) {
			chooser = UITools.newFileChooser(getLastCurrentDir());
		}
		else {
			chooser = UITools.newFileChooser();
		}
		chooser.setAcceptAllFileFilterUsed(true);
		chooser.setFileFilter(chooser.getAcceptAllFileFilter());
		chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
		final int returnVal = chooser.showOpenDialog(Controller.getCurrentController().getViewController()
		    .getCurrentRootComponent());
		if (returnVal != JFileChooser.APPROVE_OPTION) {
			return null;
		}
		final File input = chooser.getSelectedFile();
		setLastCurrentDir(input.getParentFile());
		return LinkController.toLinkTypeDependantURI(file, input);
	}

	/**@deprecated -- use MapIO*/
	@Deprecated
	public void loadAndLock(final URL url, final MapModel map)
	        throws FileNotFoundException, IOException, XMLParseException,
	        URISyntaxException {
		final File file = Compat.urlToFile(url);
		if (file == null) {
			loadCatchExceptions(url, map);
		}
		else {
			lock(map, file);
			if (file.length() != 0) {
				//DOCEAR - fixed: set the file for the map before parsing the xml, necessary for some events
				setFile(map, file);
				NodeModel root = loadTree(map, file);
				assert (map.getRootNode() == root);
			}
			if (map.getRootNode() == null)
				map.createNewRoot();
		}
	}

	public void lock(final MapModel map, final File file) throws FileNotFoundException {
		if (!file.exists()) {
			throw new FileNotFoundException(TextUtils.format("file_not_found", file.getPath()));
		}
		if (!Files.isReadable(file.toPath())) {
			throw new FileNotFoundException(TextUtils.format("file_not_accessible", file.getPath()));
		}
		if (!file.canWrite()) {
			map.setReadOnly(true);
		}
		try {
			final String lockingUser = tryToLock(map, file);
			if (lockingUser != null) {
				UITools.informationMessage(
				    Controller.getCurrentController().getViewController().getCurrentRootComponent(),
				    TextUtils.format("map_locked_by_open", file.getName(), lockingUser));
				map.setReadOnly(true);
			}
		}
		catch (final Exception e) {
			LogUtils.severe(e);
			UITools.informationMessage(Controller.getCurrentController().getViewController().getCurrentRootComponent(),
			    TextUtils.format("locking_failed_by_open", file.getName()));
			map.setReadOnly(true);
		}
	}

	public URL getAlternativeURL(final URL url, AlternativeFileMode mode) {
		try {
			final File file = Compat.urlToFile(url);
			if (file == null) {
				return url;
			}
			File alternativeFile;
			alternativeFile = getAlternativeFile(file, mode);
			if (alternativeFile != null)
				return Compat.fileToUrl(alternativeFile);
			else
				return null;
		}
		catch (MalformedURLException e) {
		}
		return null;
	}

	public enum AlternativeFileMode {
		ALL, AUTOSAVE
	};

	public File getAlternativeFile(final File file, AlternativeFileMode mode) {
		final File[] revisions = findFileRevisions(file, MFileManager.backupDir(file), mode);
		if (revisions.length == 0 && mode == AlternativeFileMode.AUTOSAVE)
			return file;
		final FileRevisionsDialog newerFileRevisionsFoundDialog = new FileRevisionsDialog(file, revisions, mode);
		final File selectedFile = newerFileRevisionsFoundDialog.getSelectedFile();
		if (file.equals(selectedFile)) {
			boolean success = file.setLastModified(System.currentTimeMillis());
			if (!success)
				LogUtils.warn("Unable to set the last modification time for " + file);
		}
		return selectedFile;
	}

	public NodeModel loadTree(final MapModel map, final File file) throws XMLParseException, IOException {
		try {
			final NodeModel rootNode = loadTreeImpl(map, file);
			return rootNode;
		}
		catch (final Exception ex) {
			final String errorMessage = "Error while parsing file:" + file;
			LogUtils.warn(errorMessage, ex);
			final NodeModel result = new NodeModel(map);
			map.setRoot(result);
			result.setText(errorMessage);
			return result;
		}
	}

	private NodeModel loadTreeImpl(final MapModel map, final File f) throws FileNotFoundException, IOException,
	        XMLException {
		int versionInfoLength = 1000;
		final byte[] buffer = new byte[versionInfoLength];
        try (final BufferedInputStream file = new BufferedInputStream(new FileInputStream(f))) {
            final int readCount = file.read(buffer);
            final String mapStart = new String(buffer, StandardCharsets.UTF_8.name());
            final ByteArrayInputStream readBytes = new ByteArrayInputStream(buffer, 0, readCount);
            MapVersionInterpreter versionInterpreter = MapVersionInterpreter.getVersionInterpreter(mapStart);
            map.addExtension(versionInterpreter);
            if (versionInterpreter.anotherDialect) {
                String message = versionInterpreter.getDialectInfo(f.getAbsolutePath());
                UITools.showMessage(message, JOptionPane.WARNING_MESSAGE);
            }
            try (final InputStream sequencedInput = new SequenceInputStream(readBytes, file);
                    Reader reader = openInputStream(f, sequencedInput, versionInterpreter)) {
                return Controller.getCurrentModeController().getMapController().getMapReader()
                    .createNodeTreeFromXml(map, reader, Mode.FILE);
            }
        }
	}

    private Reader openInputStream(final File file, final InputStream sequencedInput,
            MapVersionInterpreter versionInterpreter) throws IOException, FileNotFoundException {
        Reader reader;
        if (versionInterpreter.needsConversion) {
			final int showResult = OptionalDontShowMeAgainDialog.show("really_convert_to_current_version",
			    "confirmation", MMapController.RESOURCES_CONVERT_TO_CURRENT_VERSION,
			    MessageType.ONLY_OK_SELECTION_IS_STORED);
			IMapInputStreamConverter isConverter = versionInterpreter.getMapInputStreamConverter();
			if (showResult != JOptionPane.OK_OPTION || isConverter == null) {
				reader = new InputStreamReader(sequencedInput, StandardCharsets.UTF_8);
			}
			else {
				reader = isConverter.getConvertedStream(file);
			}
		}
		else {
			reader = new InputStreamReader(sequencedInput, StandardCharsets.UTF_8);
		}
        return reader;
    }

	/**@deprecated -- use LinkController*/
	@Deprecated
	@Override
	public void loadURL(final URI relative) {
		final MapModel map = Controller.getCurrentController().getMap();
		if (map == null || map.getURL() == null) {
			if (!relative.toString().startsWith("#") && !relative.isAbsolute() || relative.isOpaque()) {
				Controller.getCurrentController().getViewController().out("You must save the current map first!");
				final boolean result = ((MFileManager) UrlManager.getController()).save(map);
				if (!result) {
					return;
				}
			}
		}
		super.loadURL(relative);
	}

	public void open() {
		final JFileChooser chooser = getMindMapFileChooser();
		chooser.setMultiSelectionEnabled(true);
		final int returnVal = chooser
		    .showOpenDialog(Controller.getCurrentController().getMapViewManager().getMapViewComponent());
		if (returnVal != JFileChooser.APPROVE_OPTION) {
			return;
		}
		File[] selectedFiles;
		selectedFiles = chooser.getSelectedFiles();
		for (int i = 0; i < selectedFiles.length; i++) {
			final File theFile = selectedFiles[i];
			try {
				setLastCurrentDir(theFile.getParentFile());
				Controller.getCurrentModeController().getMapController().openMap(Compat.fileToUrl(theFile));
			}
			catch (final Exception ex) {
				handleLoadingException(ex);
				break;
			}
		}
		Controller.getCurrentController().getMapViewManager().setMapTitles();
	}

	public MapModel newMapFromDefaultTemplate() {
		return AccessController.doPrivileged(new PrivilegedAction<MapModel>() {
			private boolean follow = false;
			private File chosenFile;
			private void chooseTemplateFile() {
				final ResourceController resourceController = ResourceController.getResourceController();
				boolean skipTemplateSelection = resourceController.getBooleanProperty("skip_template_selection");
				if (skipTemplateSelection) {
				    chosenFile = defaultTemplateFile();
				    follow = resourceController.getBooleanProperty("follow_mind_map_by_default");
				}
				else {
					TemplateChooser templateChooser = new TemplateChooser(false);
					chosenFile = templateChooser.chosenTemplateFile();
					follow = templateChooser.isConnectChecked();
				}
			}
			@Override
			public MapModel run() {
				chooseTemplateFile();
				return chosenFile != null ? openUntitledMap(chosenFile, follow) : null;
			}
		});
	}

	class TemplateChooser {
		final private JCheckBox follow;
		private Box verticalBox;
		private JCheckBox mDontShowAgainBox;
		private JComboBox<String> templateComboBox;


		public TemplateChooser(boolean followSelected) {
			final ResourceController resourceController = ResourceController.getResourceController();
			final String standardTemplatePath = resourceController.getProperty(STANDARD_TEMPLATE);
			final TreeSet<String> availableMapTemplates = collectAvailableMapTemplates();
			availableMapTemplates.add(standardTemplatePath);
			verticalBox = Box.createVerticalBox();
			follow = new JCheckBox();
			LabelAndMnemonicSetter.setLabelAndMnemonic(follow, TextUtils.getRawText("followMindMap"));
			follow.setAlignmentX(Component.LEFT_ALIGNMENT);
			follow.setSelected(followSelected);
			verticalBox.add(follow);

			MindMapPreview mindMapPreview = new MindMapPreview();
			templateComboBox = new JComboBox<>(new Vector<>(availableMapTemplates));
			templateComboBox.addItemListener(new ItemListener() {

				@Override
				public void itemStateChanged(ItemEvent e) {
					if(e.getStateChange() == ItemEvent.SELECTED) {
						mindMapPreview.updateView(templateFile(e.getItem().toString()));
					}
				}
			});
			templateComboBox.setSelectedItem(standardTemplatePath);
			templateComboBox.setAlignmentX(0f);
			verticalBox.add(templateComboBox);
			mindMapPreview.setAlignmentX(0f);
			verticalBox.add(mindMapPreview);
			final String checkBoxText = TextUtils.getRawText("OptionalDontShowMeAgainDialog.rememberMyDescision");
			mDontShowAgainBox = new JCheckBox();
			mDontShowAgainBox.setAlignmentX(0f);
			LabelAndMnemonicSetter.setLabelAndMnemonic(mDontShowAgainBox, checkBoxText);
			verticalBox.add(mDontShowAgainBox);
		}


		File chosenTemplateFile() {
			int option = JOptionPane.showConfirmDialog(UITools.getCurrentFrame(), verticalBox, TextUtils.getText("select_template"),
				JOptionPane.PLAIN_MESSAGE);
			final String selectedTemplate = (String) templateComboBox.getSelectedItem();
			if(option == JOptionPane.CLOSED_OPTION)
			    return null;
			if (mDontShowAgainBox.isSelected()) {
				final ResourceController resourceController = ResourceController.getResourceController();
				resourceController.setProperty("skip_template_selection", true);
				resourceController.setProperty("follow_mind_map_by_default", follow.isSelected());
				resourceController.setProperty(STANDARD_TEMPLATE, selectedTemplate);
			}
			return templateFile(selectedTemplate);
		}

		public boolean isConnectChecked() {
			return follow.isSelected();
		}
	}
	@Override
	public File defaultTemplateFile() {
		final String userDefinedTemplateFilePath = ResourceController.getResourceController()
		    .getProperty(STANDARD_TEMPLATE);
		return templateFile(userDefinedTemplateFilePath);
	}

	private File templateFile(final String userDefinedTemplateFilePath) {
		final ResourceController resourceController = ResourceController.getResourceController();
		final File userDefinedTemplateFile = new File(userDefinedTemplateFilePath);
		if (userDefinedTemplateFile.isAbsolute() && userDefinedTemplateFile.exists()
		        && !userDefinedTemplateFile.isDirectory()) {
			return userDefinedTemplateFile;
		}
		for (final String filePath : new String[] { userDefinedTemplateFilePath,
		        resourceController.getDefaultProperty(STANDARD_TEMPLATE) }) {
			for (final File userTemplates : new File[] { defaultUserTemplateDir(), defaultStandardTemplateDir() }) {
				if (userTemplates.isDirectory()) {
					final File userStandard = new File(userTemplates, filePath);
					if (userStandard.exists() && !userStandard.isDirectory()) {
						if (!filePath.equals(userDefinedTemplateFilePath))
							resourceController.setProperty(STANDARD_TEMPLATE, filePath);
						return userStandard;
					}
				}
			}
		}
		return null;
	}

	public File defaultUserTemplateDir() {
		final String userDir = ResourceController.getResourceController().getFreeplaneUserDirectory();
		final File userTemplates = new File(userDir, "templates");
		return userTemplates;
	}

	public File defaultStandardTemplateDir() {
		final String resourceBaseDir = ResourceController.getResourceController().getResourceBaseDir();
		final File allUserTemplates = new File(resourceBaseDir, "templates");
		return allUserTemplates;
	}

	public MapModel openUntitledMap(final File startFile, boolean startFollow) {
		return AccessController.doPrivileged(new PrivilegedAction<MapModel>() {
			@Override
			public MapModel run() {
				final File file;
				final boolean follow;
				if (startFile == null) {
					file = getLastCurrentDir();
					follow = false;
				}
				else if (startFile.isDirectory()) {
                    final JFileChooser chooser = getMindMapFileChooser();
                    MindMapPreviewWithOptions previewWithOptions = new MindMapPreviewWithOptions(chooser, startFollow);
					chooser.setAccessory(previewWithOptions);
					Stream.of(chooser.getChoosableFileFilters())
					    .filter(filter -> filter instanceof MindMapDirectoryFilter)
					    .map(MindMapDirectoryFilter.class::cast)
					    .filter(filter -> filter.directory.equals(startFile))
					    .findFirst()
					    .ifPresent(filter -> chooser.setFileFilter(filter));
                    chooser.setCurrentDirectory(startFile);
					final int returnVal = chooser
					    .showOpenDialog(Controller.getCurrentController().getMapViewManager().getMapViewComponent());
					if (returnVal != JFileChooser.APPROVE_OPTION) {
						return null;
					}
					file = chooser.getSelectedFile();
					follow = previewWithOptions.isFollowChecked();
				}
				else {
					file = startFile;
					follow = startFollow;
				}
				try {
					final MMapController mapController = (MMapController) Controller.getCurrentModeController()
					    .getMapController();
					final Controller controller = Controller.getCurrentController();
					final MapModel map = mapController.newMap(Compat.fileToUrl(file), follow);
					MapStyle mapStyle = mapController.getModeController().getExtension(MapStyle.class);
					mapStyle.setProperty(map, FREEPLANE_ADD_ON_FILE_EXTENSION, FILE_SCHEME);
					return map;
				}
				catch (Exception e) {
					handleLoadingException(e);
				}
				return null;
			}
		});
	}

	public boolean save(final MapModel map) {
		if (map == null || map.isSaved()) {
			return true;
		}
		if (map.getURL() == null || map.isReadOnly()) {
			return saveAs(map);
		}
		else {
			return save(map, map.getFile());
		}
	}

	public boolean save(final MapModel map, final File file) {
		if (file == null) {
			return saveAs(map);
		}
		try {
			if (null == map.getExtension(BackupFlag.class)) {
				map.addExtension(new BackupFlag());
				backup(file);
			}
			final String lockingUser = tryToLock(map, file);
			if (lockingUser != null) {
				UITools.informationMessage(
				    Controller.getCurrentController().getViewController().getCurrentRootComponent(),
				    TextUtils.format("map_locked_by_save_as", file.getName(), lockingUser));
				return false;
			}
		}
		catch (final Exception e) {
			UITools.informationMessage(Controller.getCurrentController().getViewController().getCurrentRootComponent(),
			    TextUtils.format("locking_failed_by_save_as", file.getName()));
			return false;
		}
		if (file.exists() && !file.canWrite()) {
			JOptionPane.showMessageDialog(Controller.getCurrentController()
			    .getMapViewManager().getMapViewComponent(),
			    TextUtils.format("SaveAs_toReadonlyMsg", file),
			    TextUtils.getText("SaveAs_toReadonlyTitle"),
			    JOptionPane.WARNING_MESSAGE);
			return false;
		}
		final URL urlBefore = map.getURL();
		setFile(map, file);
		final boolean saved = saveInternal((MMapModel) map, file, false);
		if (!saved) {
			return false;
		}
		map.updateLastKnownFileModificationTime();
		map.setReadOnly(false);
		final URL urlAfter = map.getURL();
		final MMapController mapController = (MMapController) Controller.getCurrentModeController().getMapController();
		if(! urlAfter.equals(urlBefore))
			mapController.fireMapChanged(new MapChangeEvent(this, map, UrlManager.MAP_URL, urlBefore, urlAfter, false));
		mapController.setSaved(map, true);
		return true;
	}

	public boolean saveAs(final MapModel map) {
		final JFileChooser chooser = getMindMapFileChooser();
		if (getMapsParentFile(map) == null) {
			File defaultFile = new File(getFileNameProposal(map)
			        + org.freeplane.features.url.UrlManager.FREEPLANE_FILE_EXTENSION);
			chooser.setSelectedFile(defaultFile);
		}
		else {
			chooser.setSelectedFile(map.getFile());
		}
		chooser.setDialogTitle(TextUtils.getText("SaveAsAction.text"));
		final int returnVal = chooser
		    .showSaveDialog(Controller.getCurrentController().getMapViewManager().getMapViewComponent());
		if (returnVal != JFileChooser.APPROVE_OPTION) {
			return false;
		}
		File f = chooser.getSelectedFile();
		setLastCurrentDir(f.getParentFile());
		final String ext = FileUtils.getExtension(f.getName());
		if (!ext.equals(org.freeplane.features.url.UrlManager.FREEPLANE_FILE_EXTENSION_WITHOUT_DOT)) {
			f = new File(f.getParent(), f.getName()
			        + org.freeplane.features.url.UrlManager.FREEPLANE_FILE_EXTENSION);
		}
		if (f.exists()) {
			final int overwriteMap = JOptionPane.showConfirmDialog(Controller.getCurrentController()
			    .getMapViewManager().getMapViewComponent(), TextUtils.getText("map_already_exists"), "Freeplane",
			    JOptionPane.YES_NO_OPTION);
			if (overwriteMap != JOptionPane.YES_OPTION) {
				return false;
			}
		}
		// extra backup in this case.
		File oldFile = map.getFile();
		if (oldFile != null) {
			oldFile = oldFile.getAbsoluteFile();
		}
		if (!f.getAbsoluteFile().equals(oldFile)) {
			if (null != map.getExtension(BackupFlag.class)) {
				map.removeExtension(BackupFlag.class);
			}
			if (null != map.getExtension(DocuMapAttribute.class)) {
				map.removeExtension(DocuMapAttribute.class);
			}
			map.setReadOnly(false);
		}
		if (save(map, f)) {
			Controller.getCurrentController().getMapViewManager().updateMapViewName();
			return true;
		}
		return false;
	}

	/**
	 * This method is intended to provide both normal save routines and saving
	 * of temporary (internal) files.
	 */
	boolean saveInternal(final MMapModel map, final File file, final boolean isInternal) {
		try {
			if (map.getTimerForAutomaticSaving() != null) {
				map.getTimerForAutomaticSaving().stop();
			}
			writeToFile(map, file);
			map.scheduleTimerForAutomaticSaving();
			return true;
		}
		catch (final IOException e) {
			final String message = TextUtils.format("save_failed", file.getName());
			if (!isInternal) {
				UITools.errorMessage(message);
				LogUtils.warn(message, e);
			}
			else {
				Controller.getCurrentController().getViewController().out(message);
			}
		}
		catch (final Exception e) {
			LogUtils.severe("Error in MapModel.save(): ", e);
		}
		map.scheduleTimerForAutomaticSaving();
		return false;
	}

	/**@deprecated -- use MMapIO*/
	@Deprecated
	public void writeToFile(final MapModel map, final File file) throws FileNotFoundException, IOException {
		FileLock lock = null;
		try (final FileOutputStream out = new FileOutputStream(file)){
			boolean lockedByOtherApplication = false;
			try {
				lock = out.getChannel().tryLock();
				lockedByOtherApplication = lock == null;
			}
			catch (Exception e) {
				LogUtils.warn(e.getMessage(), e);
			}
			if (lockedByOtherApplication) {
				throw new IOException("can not obtain file lock for " + file);
			}
			final BufferedWriter fileout = new BufferedWriter(new OutputStreamWriter(out,//
				StandardCharsets.UTF_8));
			Controller.getCurrentModeController().getMapController().getMapWriter()
			    .writeMapAsXml(map, fileout, Mode.FILE, true, false);
		}
		finally {
			if (lock != null && lock.isValid())
				lock.release();
		}
	}

	public void setFile(final MapModel map, final File file) {
		try {
			final URL url = Compat.fileToUrl(file);
			setURL(map, url);
		}
		catch (final MalformedURLException e) {
			LogUtils.severe(e);
		}
	}

	/**
	 * Attempts to lock the map using a semaphore file
	 *
	 * @return If the map is locked, return the name of the locking user,
	 *         otherwise return null.
	 * @throws Exception
	 *             , when the locking failed for other reasons than that the
	 *             file is being edited.
	 *
	 * @deprecated -- use MMapIO
	 */
	@Deprecated
	public String tryToLock(final MapModel map, final File file) throws Exception {
		final String lockingUser = ((MMapModel) map).getLockManager().tryToLock(file);
		final String lockingUserOfOldLock = ((MMapModel) map).getLockManager().popLockingUserOfOldLock();
		if (lockingUserOfOldLock != null) {
			UITools.informationMessage(UITools.getCurrentRootComponent(),
			    TextUtils.format("locking_old_lock_removed", file.getName(), lockingUserOfOldLock));
		}
		return lockingUser;
	}

	@Override
	public void afterViewCreated(Component oldView, Component newView) {
		if (newView != null) {
			final FileOpener fileOpener = new FileOpener("mm", new DroppedMindMapOpener());
			new DropTarget(newView, fileOpener);
		}
	}

	protected TreeSet<String> collectAvailableMapTemplates() {
		final TreeSet<String> templates = new TreeSet<String>();
		for (File dir : new File[] { defaultStandardTemplateDir(), defaultUserTemplateDir() })
			if (dir.isDirectory())
				templates.addAll(Arrays.asList(dir.list(new FilenameFilter() {
					@Override
					public boolean accept(File dir, String name) {
						return name.endsWith(FREEPLANE_FILE_EXTENSION);
					}
				})));
		return templates;
	}


	public static MFileManager getController(ModeController modeController) {
		return (MFileManager) modeController.getExtension(UrlManager.class);
	}
}
