package de.robojumper.ddsavereader.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.EventQueue;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;

import de.fuerstenau.buildconfig.BuildConfig;
import de.robojumper.ddsavereader.spreadsheets.SpreadsheetsService;
import de.robojumper.ddsavereader.spreadsheets.SpreadsheetsService.SheetUpdater;
import de.robojumper.ddsavereader.ui.State.SaveFile;
import de.robojumper.ddsavereader.ui.State.Status;
import de.robojumper.ddsavereader.updatechecker.UpdateChecker;
import de.robojumper.ddsavereader.updatechecker.UpdateChecker.Release;
import de.robojumper.ddsavereader.updatechecker.Version;

public class MainWindow {

    private JFrame frame;
    private JTextField gameDataPathBox, savePathBox, workshopPathBox;
    private JLabel saveFileStatus, gameDataStatus, workshopStatus;
    private JTabbedPane tabbedPane;
    private JLabel saveStatus, errorLabel;
    private JButton saveButton;
    private JButton makeBackupButton, restoreBackupButton;
    private JButton discardChangesButton, reloadButton;
    private JMenuItem mntmSpreadsheets;

    private State state = new State();

    /**
     * Launch the application.
     */
    public static void main(String[] args) {
        EventQueue.invokeLater(new Runnable() {
            public void run() {
                try {
                    MainWindow window = new MainWindow();
                    window.frame.setVisible(true);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * Create the application.
     */
    public MainWindow() {
        initialize();
        initSettings();
    }

    private void initSettings() {
        state.init();
        updateSaveDir();
        updateGameDir();
        updateModsDir();
        updateFiles();
        updateSaveStatus();
        checkForUpdates();
    }

    /**
     * Initialize the contents of the frame.
     */
    private void initialize() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException
                | UnsupportedLookAndFeelException e1) {
            e1.printStackTrace();
        }

        frame = new JFrame();
        frame.setBounds(100, 100, 800, 500);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                attemptExit();
            }
        });
        frame.setTitle(BuildConfig.DISPLAY_NAME + "/" + BuildConfig.VERSION);

        JMenuBar menuBar = new JMenuBar();
        frame.getContentPane().add(menuBar, BorderLayout.NORTH);

        JMenu fileMenu = new JMenu("File");
        menuBar.add(fileMenu);

        JMenuItem mntmExit = new JMenuItem("Exit");
        mntmExit.addActionListener(e -> {
            attemptExit();
        });

        JMenuItem mntmOpenBackupDirectory = new JMenuItem("Open Backup Directory");
        mntmOpenBackupDirectory.addActionListener(e -> {
            try {
                Desktop.getDesktop().open(new File(state.getBackupPath()));
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        });
        fileMenu.add(mntmOpenBackupDirectory);
        fileMenu.add(mntmExit);

        JMenu mnTools = new JMenu("Tools");
        menuBar.add(mnTools);

        mntmSpreadsheets = new JMenuItem("Spreadsheets");
        mntmSpreadsheets.setEnabled(false);
        mntmSpreadsheets.addActionListener(e -> {
            if (state.getSaveStatus() == Status.OK) {
                SheetUpdater sheetUpdater;
                try {
                    final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
                    Credential cred = SpreadsheetsService.getCredentials(HTTP_TRANSPORT);
                    if (cred == null) {
                        Object[] options = { "OK", "Go to GitHub ReadMe" };
                        int openInstructions = JOptionPane.showOptionDialog(frame,
                                "It seems like the Spreadsheet application wasn't set up. See the Readme file for instructions!",
                                "Error", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, options,
                                options[0]);
                        switch (openInstructions) {
                        case 0:
                            break;
                        case 1:
                            try {
                                Desktop.getDesktop().browse(new URI(BuildConfig.GITHUB_URL
                                        + "/blob/master/README.md#spreadsheets"));
                            } catch (IOException | URISyntaxException e1) {
                                e1.printStackTrace();
                            }
                        }
                        return;
                    }
                    String sheetID = JOptionPane.showInputDialog(frame, "Set Spreadsheet ID",
                            state.getLastSheetID());
                    if (sheetID != null) {
                        state.setLastSheetID(sheetID);
                        sheetUpdater = SpreadsheetsService.makeUpdaterRunnable(sheetID, state.getSaveDir(), cred,
                            HTTP_TRANSPORT);
                    } else {
                        return;
                    }
                } catch (IOException | GeneralSecurityException e1) {
                    JOptionPane.showMessageDialog(null, "An unknown error occurred.", "Spreadsheets",
                            JOptionPane.ERROR_MESSAGE);
                    return;
                }

                final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

                // Hack box so that we can refer to the future (no pun intented)
                final class Box<T> {
                    private T obj;

                    void set(T obj) {
                        this.obj = obj;
                    }

                    T get() {
                        return this.obj;
                    }

                    Box() {
                        this(null);
                    }

                    Box(T obj) {
                        set(obj);
                    }
                }

                Box<ScheduledFuture<?>> future = new Box<>();
                JLabel runningLabel = new JLabel("Running... click OK to cancel!");
                future.set(scheduler.scheduleAtFixedRate(new Runnable() {

                    @Override
                    public void run() {
                        if (sheetUpdater.isRunning()) {
                            sheetUpdater.run();
                        } else {
                            if (future.get() != null) {
                                future.get().cancel(false);
                                runningLabel.setText("Stopped!");
                            }
                        }
                    }
                }, 3, 120, TimeUnit.SECONDS));

                JOptionPane.showConfirmDialog(null,
                        runningLabel,
                        "Spreadsheets",
                        JOptionPane.DEFAULT_OPTION,
                        JOptionPane.PLAIN_MESSAGE);
                sheetUpdater.cancel();
                future.get().cancel(false);
            }
        });
        mnTools.add(mntmSpreadsheets);

        JMenu mnHelp = new JMenu("Help");
        menuBar.add(mnHelp);

        JMenuItem mntmAbout = new JMenuItem("About");
        mntmAbout.addActionListener(e -> {
            Object[] options = { "OK", "Go to GitHub Page" };
            int result = JOptionPane.showOptionDialog(frame,
                    BuildConfig.DISPLAY_NAME + " " + BuildConfig.VERSION + "\nBy: /u/robojumper\nGitHub: "
                            + BuildConfig.GITHUB_URL,
                    "About", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
            switch (result) {
            case 0:
                break;
            case 1:
                try {
                    Desktop.getDesktop().browse(new URI(BuildConfig.GITHUB_URL));
                } catch (IOException | URISyntaxException e1) {
                    e1.printStackTrace();
                }
            }
        });
        mnHelp.add(mntmAbout);

        JPanel contentPanel = new JPanel();
        frame.getContentPane().add(contentPanel, BorderLayout.CENTER);
        contentPanel.setLayout(new BorderLayout(0, 0));

        JPanel inputPanel = new JPanel();
        inputPanel.setBorder(null);
        inputPanel.setLayout(new BoxLayout(inputPanel, BoxLayout.PAGE_AXIS));

        JPanel savePathPanel = new JPanel();
        savePathPanel.setBorder(new TitledBorder(UIManager.getBorder("TitledBorder.border"), "Save File Directory",
                TitledBorder.LEADING, TitledBorder.TOP, null, new Color(0, 0, 0)));
        inputPanel.add(savePathPanel);
        savePathPanel.setLayout(new BoxLayout(savePathPanel, BoxLayout.LINE_AXIS));

        saveFileStatus = new JLabel("");
        saveFileStatus.setIcon(Resources.WARNING_ICON);
        savePathPanel.add(saveFileStatus);

        savePathBox = new JTextField();
        savePathBox.setEditable(false);
        savePathPanel.add(savePathBox);
        savePathBox.setColumns(10);

        JButton chooseSavePathButton = new JButton("Browse...");
        chooseSavePathButton.addActionListener(e -> {
            if (confirmLoseChanges()) {
                directoryChooser("", s -> state.setSaveDir(s));
                updateSaveDir();
                updateFiles();
            }
        });

        makeBackupButton = new JButton("Make Backup...");
        makeBackupButton.setEnabled(false);
        makeBackupButton.addActionListener(e -> {
            if (state.getSaveStatus() == Status.OK) {
                String result = JOptionPane.showInputDialog(frame, "Choose backup name",
                        new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()));
                if (result == null) {
                    return;
                } else {
                    result = result.replaceAll("[:\\\\/*?|<>]", "_");
                    if (state.hasBackup(result)) {
                        int confirmed = JOptionPane.showConfirmDialog(frame,
                                "Backup " + result + " already exists. Overwrite?", "Backup already exists",
                                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                        switch (confirmed) {
                        case JOptionPane.YES_OPTION:
                            break;
                        case JOptionPane.NO_OPTION:
                            return;
                        }
                    }
                    state.makeBackup(result);
                    updateBackupButtons();
                }
            }
        });
        savePathPanel.add(makeBackupButton);

        restoreBackupButton = new JButton("Load Backup...");
        restoreBackupButton.addActionListener(e -> {
            if (state.getSaveStatus() == Status.OK && state.hasAnyBackups() && confirmLoseChanges()) {
                String[] backups = state.getBackupNames().toArray(new String[0]);
                Object result = JOptionPane.showInputDialog(frame, "Choose backup", "Restore",
                        JOptionPane.OK_CANCEL_OPTION, null, backups, backups[0]);
                if (result != null) {
                    state.restoreBackup((String) result);
                    state.loadFiles();
                    updateFiles();
                }
            }
        });
        restoreBackupButton.setEnabled(false);
        savePathPanel.add(restoreBackupButton);
        savePathPanel.add(chooseSavePathButton);

        JPanel gameDataPathPanel = new JPanel();
        gameDataPathPanel.setBorder(
                new TitledBorder(null, "Game Data Directory", TitledBorder.LEADING, TitledBorder.TOP, null, null));
        inputPanel.add(gameDataPathPanel);
        gameDataPathPanel.setLayout(new BoxLayout(gameDataPathPanel, BoxLayout.LINE_AXIS));

        gameDataStatus = new JLabel("");
        gameDataStatus.setIcon(Resources.OK_ICON);
        gameDataPathPanel.add(gameDataStatus);

        gameDataPathBox = new JTextField();
        gameDataPathBox.setEditable(false);
        gameDataPathPanel.add(gameDataPathBox);
        gameDataPathBox.setColumns(10);

        JButton chooseGamePathButton = new JButton("Browse...");
        chooseGamePathButton.addActionListener(e -> {
            if (confirmLoseChanges()) {
                directoryChooser("", s -> state.setGameDir(s));
                updateGameDir();
            }
        });
        gameDataPathPanel.add(chooseGamePathButton);

        JPanel workshopPathPanel = new JPanel();
        workshopPathPanel.setBorder(
                new TitledBorder(null, "Workshop Directory", TitledBorder.LEADING, TitledBorder.TOP, null, null));
        inputPanel.add(workshopPathPanel);
        workshopPathPanel.setLayout(new BoxLayout(workshopPathPanel, BoxLayout.LINE_AXIS));

        workshopStatus = new JLabel("");
        workshopStatus.setIcon(Resources.OK_ICON);
        workshopPathPanel.add(workshopStatus);

        workshopPathBox = new JTextField();
        workshopPathBox.setEditable(false);
        workshopPathPanel.add(workshopPathBox);
        workshopPathBox.setColumns(10);

        JButton chooseWorkshopPathButton = new JButton("Browse...");
        chooseWorkshopPathButton.addActionListener(e -> {
            if (confirmLoseChanges()) {
                directoryChooser("", s -> state.setModsDir(s));
                updateModsDir();
            }
        });
        workshopPathPanel.add(chooseWorkshopPathButton);
        contentPanel.add(inputPanel, BorderLayout.NORTH);

        JPanel panel = new JPanel();
        contentPanel.add(panel, BorderLayout.CENTER);
        panel.setLayout(new BoxLayout(panel, BoxLayout.LINE_AXIS));

        tabbedPane = new JTabbedPane(JTabbedPane.TOP);
        tabbedPane.addChangeListener(e -> {
            updateSaveStatus();
        });
        panel.add(tabbedPane);

        JPanel buttonPanel = new JPanel();
        frame.getContentPane().add(buttonPanel, BorderLayout.SOUTH);
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.LINE_AXIS));

        discardChangesButton = new JButton("Discard File Changes");
        discardChangesButton.addActionListener(e -> {
            Component c = tabbedPane.getSelectedComponent();
            if (c != null) {
                String fileName = ((Tab) c).fileName;
                SaveFile s = state.getSaveFile(fileName);
                Tab t = (Tab) tabbedPane.getSelectedComponent();
                t.area.setText(s.originalContents);
                t.area.setCaretPosition(0);
            }
        });
        buttonPanel.add(discardChangesButton);

        Component horizontalGlue = Box.createHorizontalGlue();
        buttonPanel.add(horizontalGlue);

        errorLabel = new JLabel("Optional Error Text");
        buttonPanel.add(errorLabel);

        Component horizontalStrut = Box.createHorizontalStrut(20);
        buttonPanel.add(horizontalStrut);

        saveStatus = new JLabel("");
        saveStatus.setIcon(Resources.OK_ICON);
        buttonPanel.add(saveStatus);

        saveButton = new JButton("Save All Changes");
        saveButton.addActionListener(e -> {
            if (state.canSave()) {
                state.saveChanges();
                state.loadFiles();
                updateFiles();
            }
        });
        buttonPanel.add(saveButton);

        reloadButton = new JButton("Reload All");
        reloadButton.addActionListener(e -> {
            state.loadFiles();
            updateFiles();
        });
        buttonPanel.add(reloadButton);
    }

    private static final void directoryChooser(String def, Consumer<String> onSuccess) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.putClientProperty("JFileChooser.appBundleIsTraversable", "always");
        int result = chooser.showOpenDialog(null);
        if (result == JFileChooser.APPROVE_OPTION) {
            onSuccess.accept(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void updateSaveDir() {
        savePathBox.setText(state.getSaveDir());
        saveFileStatus.setIcon(state.getSaveStatus().icon);
        reloadButton.setEnabled(state.getSaveStatus() == Status.OK);
        mntmSpreadsheets.setEnabled(state.getSaveStatus() == Status.OK);
        updateBackupButtons();
    }

    private void updateBackupButtons() {
        makeBackupButton.setEnabled(state.getSaveStatus() == Status.OK);
        restoreBackupButton.setEnabled(state.hasAnyBackups());
    }

    private void updateGameDir() {
        gameDataPathBox.setText(state.getGameDir());
        gameDataStatus.setIcon(state.getGameStatus().icon);
    }

    private void updateModsDir() {
        workshopPathBox.setText(state.getModsDir());
        workshopStatus.setIcon(state.getModsStatus().icon);
    }

    private void updateFiles() {
        tabbedPane.removeAll();
        for (SaveFile f : state.getSaveFiles()) {
            Tab compPanel = new Tab();
            compPanel.fileName = f.name;
            compPanel.setLayout(new BoxLayout(compPanel, BoxLayout.LINE_AXIS));
            RSyntaxTextArea a = new RSyntaxTextArea(f.contents);
            a.setCodeFoldingEnabled(true);
            a.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JSON);
            compPanel.area = a;
            a.getDocument().addDocumentListener(new DocumentListener() {

                @Override
                public void removeUpdate(DocumentEvent e) {
                    update(e);
                }

                @Override
                public void insertUpdate(DocumentEvent e) {
                    update(e);
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    update(e);
                }

                private void update(DocumentEvent e) {
                    try {
                        state.changeFile(f.name, e.getDocument().getText(0, e.getDocument().getLength()));
                        updateSaveStatus();
                    } catch (BadLocationException e1) {
                        e1.printStackTrace();
                    }
                    updateFile(compPanel);
                }
            });
            RTextScrollPane sp = new RTextScrollPane(a);
            compPanel.add(sp);
            tabbedPane.addTab((f.changed() ? "*" : "") + f.name, iconFor(f), compPanel);
        }
    }

    private void updateFile(Tab t) {
        SaveFile f = state.getSaveFile(t.fileName);
        tabbedPane.setTitleAt(tabbedPane.indexOfComponent(t), (f.changed() ? "*" : "") + f.name);
        tabbedPane.setIconAt(tabbedPane.indexOfComponent(t), iconFor(f));
        t.area.getHighlighter().removeAllHighlights();
        if (!f.canSave()) {
            Highlighter.HighlightPainter redPainter = new DefaultHighlighter.DefaultHighlightPainter(
                    new Color(255, 127, 127));
            int[] errorLine = f.getErrorLine();
            try {
                t.area.getHighlighter().addHighlight(errorLine[0], errorLine[1], redPainter);
            } catch (BadLocationException e) {
            }
        }
        discardChangesButton.setEnabled(f.changed());
    }

    private static Icon iconFor(SaveFile f) {
        if (f.changed()) {
            return f.canSave() ? Resources.OK_ICON : Resources.ERROR_ICON;
        } else {
            return f.canSave() ? null : Resources.WARNING_ICON;
        }
    }

    private void updateSaveStatus() {
        saveStatus.setIcon(state.canSave() ? Status.OK.icon : Status.ERROR.icon);
        saveButton.setEnabled(state.canSave());
        Component tab = tabbedPane.getSelectedComponent();
        if (tab != null) {
            SaveFile f = state.getSaveFile(((Tab) tab).fileName);
            errorLabel.setText(f.canSave() ? "" : f.errorReason);
            discardChangesButton.setEnabled(f.changed());
        } else {
            errorLabel.setText("");
            discardChangesButton.setEnabled(false);
        }
    }

    protected void attemptExit() {
        if (confirmLoseChanges()) {
            state.save();
            System.exit(0);
        }
    }

    protected boolean confirmLoseChanges() {
        if (state.getNumUnsavedChanges() > 0) {
            int result = JOptionPane.showConfirmDialog(frame,
                    "You have " + state.getNumUnsavedChanges()
                            + " unsaved changes! Are you sure you want to discard them?",
                    "Discard Changes", JOptionPane.YES_NO_OPTION);
            if (result == JOptionPane.NO_OPTION) {
                return false;
            }
        }
        return true;
    }
    
    private void checkForUpdates() {
        try {
            Release r = UpdateChecker.getLatestRelease();
            Version curr = new Version(BuildConfig.VERSION);
            if (r.version.compareTo(curr) > 0) {
                Object[] options = { "OK", "Go to Releases Page" };
                int openInstructions = JOptionPane.showOptionDialog(frame,
                        "There are updates available!\nCurrent version: " + curr.toString() + ", new version: " + r.version.toString(),
                        "Update available", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, options,
                        options[1]);
                switch (openInstructions) {
                case 0:
                    break;
                case 1:
                    try {
                        Desktop.getDesktop().browse(new URI(r.htmlUrl));
                    } catch (IOException | URISyntaxException e1) {
                        e1.printStackTrace();
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Update check failed.");
        }
    }

    private class Tab extends JPanel {
        private static final long serialVersionUID = 7066962308849880236L;
        private String fileName;
        private RSyntaxTextArea area;
    }
}
