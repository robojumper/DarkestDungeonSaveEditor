package de.robojumper.ddsavereader.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import de.robojumper.ddsavereader.file.DsonTypes;
import de.robojumper.ddsavereader.util.Helpers;
import de.robojumper.ddsavereader.util.ReadNames;

public class DataPathsDialog {

    private static final File CACHED_NAME_FILE = new File(Helpers.DATA_DIR, "names_cache.txt");

    private JDialog dialog, idial;
    private JFrame frame;
    private JTextField gameDataPathBox, workshopPathBox;

    private JButton okButton, cancelButton;

    private String gameDir, modsDir;
    private State state;

    public DataPathsDialog(JFrame frame, String _gameDir, String _modsDir, State _state, boolean skipInsteadOfCancel) {

        this.gameDir = _gameDir;
        this.modsDir = _modsDir;
        this.state = _state;
        this.frame = frame;

        JPanel content = new JPanel();
        content.setBorder(new TitledBorder(UIManager.getBorder("TitledBorder.border"), "", TitledBorder.LEADING,
                TitledBorder.TOP, null, new Color(0, 0, 0)));
        content.setLayout(new BoxLayout(content, BoxLayout.PAGE_AXIS));

        content.add(new JLabel("You can point the save editor towards the game installation and your mods folder."));
        content.add(new JLabel("This allows the editor to reverse some numbers into strings, i.e. \"jester\" instead of "
                                + DsonTypes.stringHash("jester") + "."));
        content.add(new JLabel("The data is cached, consider re-running this after game updates or new mod installation."));
        content.add(new JLabel("Reach this dialogue any time via Tools -> Generate Name Files"));

        content.add(new JSeparator(SwingConstants.HORIZONTAL));

        JPanel gameDataPathPanel = new JPanel();
        gameDataPathPanel.setBorder(new TitledBorder(UIManager.getBorder("TitledBorder.border"),
                "Game Data Directory (ends with DarkestDungeon)", TitledBorder.LEADING, TitledBorder.TOP, null,
                new Color(0, 0, 0)));
        gameDataPathPanel.setLayout(new BoxLayout(gameDataPathPanel, BoxLayout.LINE_AXIS));

        gameDataPathBox = new JTextField();
        gameDataPathBox.setEditable(true);
        gameDataPathPanel.add(gameDataPathBox);
        gameDataPathBox.setColumns(30);
        gameDataPathBox.setText(gameDir);

        DocumentListener listener = new DocumentListener() {

            @Override
            public void removeUpdate(DocumentEvent e) {
                onChange();
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                onChange();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onChange();
            }
        };

        gameDataPathBox.getDocument().addDocumentListener(listener);

        JButton chooseGamePathButton = new JButton("Browse...");
        chooseGamePathButton.addActionListener(e -> {
            MainWindow.directoryChooser(state.getGameDir(), s -> this.gameDir = s);
            gameDataPathBox.setText(this.gameDir);
        });
        gameDataPathPanel.add(chooseGamePathButton);

        JPanel workshopPathPanel = new JPanel();
        workshopPathPanel.setBorder(
                new TitledBorder(UIManager.getBorder("TitledBorder.border"), "Workshop Directory (ends with 262060)",
                        TitledBorder.LEADING, TitledBorder.TOP, null, new Color(0, 0, 0)));
        workshopPathPanel.setLayout(new BoxLayout(workshopPathPanel, BoxLayout.LINE_AXIS));

        workshopPathBox = new JTextField();
        workshopPathBox.setEditable(true);
        workshopPathPanel.add(workshopPathBox);
        workshopPathBox.setColumns(10);
        workshopPathBox.setText(modsDir);
        workshopPathBox.getDocument().addDocumentListener(listener);

        JButton chooseWorkshopPathButton = new JButton("Browse...");
        chooseWorkshopPathButton.addActionListener(e -> {
            MainWindow.directoryChooser(state.getModsDir(), s -> this.modsDir = s);
            workshopPathBox.setText(this.modsDir);
        });
        workshopPathPanel.add(chooseWorkshopPathButton);

        content.add(gameDataPathPanel);
        content.add(workshopPathPanel);

        JPanel buttonPanel = new JPanel();
        content.add(buttonPanel);
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.LINE_AXIS));

        Component horizontalGlue = Box.createHorizontalGlue();
        buttonPanel.add(horizontalGlue);

        okButton = new JButton("Scan Names");
        okButton.addActionListener(e -> {
            final JOptionPane optionPane = new JOptionPane("Loading names, please wait...",
                    JOptionPane.INFORMATION_MESSAGE, JOptionPane.DEFAULT_OPTION, null, new Object[] {}, null);

            idial = new JDialog();
            idial.setTitle("Loading...");
            idial.setModal(true);

            idial.setContentPane(optionPane);
            idial.setLocationRelativeTo(frame);

            idial.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

            idial.pack();
            idial.setAlwaysOnTop(true);

            FileLoader loader = new FileLoader(gameDir, modsDir);
            loader.execute();

            idial.setVisible(true);
            if (loader.success) {
                dialog.dispose();
            }
        });
        buttonPanel.add(okButton);

        cancelButton = new JButton(skipInsteadOfCancel ? "Skip" : "Cancel");
        cancelButton.addActionListener(e -> {
            dialog.dispose();
        });
        buttonPanel.add(cancelButton);

        dialog = new JDialog(frame, "Choose Game Data Directories", true);
        dialog.setLocationRelativeTo(frame);
        dialog.add(content);
        dialog.setResizable(false);
        dialog.setAlwaysOnTop(true);
        dialog.pack();
        dialog.setVisible(true);
    }

    void onChange() {
        this.gameDir = gameDataPathBox.getText();
        this.modsDir = workshopPathBox.getText();
    }

    private class FileLoader extends SwingWorker<Set<String>, Void> {

        private String gamePath, modsPath;
        boolean success = false;

        public FileLoader(String gamePath, String modsPath) {
            this.gamePath = gamePath;
            this.modsPath = modsPath;
            frame.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        }

        @Override
        public Set<String> doInBackground() throws IOException {
            System.out.println("Reading Names...");
            ArrayList<String> paths = new ArrayList<>();
            if (!gamePath.equals("")) {
                if (new File(gamePath, "svn_revision.txt").exists()) {
                    paths.add(gamePath);
                } else {
                    throw new IOException("Path specified as game path does not point to game installation");
                }
            }

            if (!modsPath.equals("")) {
                if (Paths.get(modsPath).endsWith("262060")) {
                    paths.add(modsPath);
                } else {
                    throw new IOException("Path specified as mods path does not point to workshop folder");
                }
            }

            Set<String> names = ReadNames.collectNames(paths);
            System.out.println("Done");
            return names;
        }

        @Override
        public void done() {
            try {
                Set<String> result = get();
                if (!CACHED_NAME_FILE.exists()) {
                    CACHED_NAME_FILE.getParentFile().mkdirs();
                    CACHED_NAME_FILE.createNewFile();
                }
                int numRead = result.size();
                Files.write(CACHED_NAME_FILE.toPath(), result, StandardCharsets.UTF_8);
                idial.dispose();
                dialog.setAlwaysOnTop(false);
                JOptionPane.showMessageDialog(frame, "Successfully read " + numRead + " names.", "Ok",
                        JOptionPane.INFORMATION_MESSAGE);
                state.setGameDir(gamePath);
                state.setModsDir(modsPath);
                success = true;
            } catch (ExecutionException | InterruptedException | IOException ex) {
                // display error
                idial.dispose();
                dialog.setAlwaysOnTop(false);
                JOptionPane.showMessageDialog(DataPathsDialog.this.frame, ex.getMessage(), "Error opening file",
                        JOptionPane.ERROR_MESSAGE);
            } finally {
                frame.setCursor(Cursor.getDefaultCursor());
            }
        }
    }

    static void updateFromDataFile() {
        try (BufferedReader br = new BufferedReader(new FileReader(CACHED_NAME_FILE))) {
            String line;
            while ((line = br.readLine()) != null) {
                DsonTypes.offerName(line);
            }
        } catch (IOException e) {
            // Ignore
        }
    }
}
