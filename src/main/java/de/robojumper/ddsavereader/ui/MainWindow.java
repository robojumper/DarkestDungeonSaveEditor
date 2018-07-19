package de.robojumper.ddsavereader.ui;

import java.awt.EventQueue;

import javax.swing.JFrame;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import javax.swing.JTextField;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.BoxLayout;
import java.awt.Color;
import de.fuerstenau.buildconfig.BuildConfig;

import javax.swing.border.TitledBorder;
import javax.swing.UIManager;
import javax.swing.JLabel;
import javax.swing.JTabbedPane;
import java.awt.Component;

import javax.swing.Box;
import java.awt.TextArea;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.JMenuBar;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;

public class MainWindow {

    private JFrame frame;
    private JTextField gameDataPathBox;
    private JTextField savePathBox;
    private JTextField workshopPathBox;

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
    }

    /**
     * Initialize the contents of the frame.
     */
    private void initialize() {
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
        mntmExit.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                attemptExit();
            }
        });
        fileMenu.add(mntmExit);
        
        JMenu mnHelp = new JMenu("Help");
        menuBar.add(mnHelp);
        
        JMenuItem mntmAbout = new JMenuItem("About");
        mntmAbout.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                JOptionPane.showMessageDialog(null, BuildConfig.DISPLAY_NAME + " " + BuildConfig.VERSION + "\nBy: /u/robojumper\nGitHub: " +  BuildConfig.GITHUB_URL, "About", JOptionPane.INFORMATION_MESSAGE);
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
        savePathPanel.setBorder(new TitledBorder(UIManager.getBorder("TitledBorder.border"), "Save File Directory", TitledBorder.LEADING, TitledBorder.TOP, null, new Color(0, 0, 0)));
        inputPanel.add(savePathPanel);
        savePathPanel.setLayout(new BoxLayout(savePathPanel, BoxLayout.LINE_AXIS));
        
        JLabel saveFileStatus = new JLabel("");
        saveFileStatus.setIcon(Resources.WARNING_ICON);
        savePathPanel.add(saveFileStatus);
        
        savePathBox = new JTextField();
        savePathPanel.add(savePathBox);
        savePathBox.setColumns(10);
        
        JButton chooseSavePathButton = new JButton("Browse...");
        chooseSavePathButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                JFileChooser chooser = new JFileChooser();
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                int result = chooser.showOpenDialog(null);
                if (result == JFileChooser.APPROVE_OPTION) {
                    savePathBox.setText(chooser.getSelectedFile().getAbsolutePath());
                }
            }
        });
        savePathPanel.add(chooseSavePathButton);
        
        JButton btnRefreshbutton = new JButton("Load");
        savePathPanel.add(btnRefreshbutton);
        
        JPanel gameDataPathPanel = new JPanel();
        gameDataPathPanel.setBorder(new TitledBorder(null, "Game Data Directory", TitledBorder.LEADING, TitledBorder.TOP, null, null));
        inputPanel.add(gameDataPathPanel);
        gameDataPathPanel.setLayout(new BoxLayout(gameDataPathPanel, BoxLayout.LINE_AXIS));
        
        JLabel gameDataStatus = new JLabel("");
        gameDataStatus.setIcon(Resources.OK_ICON);
        gameDataPathPanel.add(gameDataStatus);
        
        gameDataPathBox = new JTextField();
        gameDataPathPanel.add(gameDataPathBox);
        gameDataPathBox.setColumns(10);
        
        JButton chooseGamePathButton = new JButton("Browse...");
        gameDataPathPanel.add(chooseGamePathButton);
        
        JPanel workshopPathPanel = new JPanel();
        workshopPathPanel.setBorder(new TitledBorder(null, "Workshop Directory", TitledBorder.LEADING, TitledBorder.TOP, null, null));
        inputPanel.add(workshopPathPanel);
        workshopPathPanel.setLayout(new BoxLayout(workshopPathPanel, BoxLayout.LINE_AXIS));
        
        JLabel workshopStatus = new JLabel("");
        workshopStatus.setIcon(Resources.OK_ICON);
        workshopPathPanel.add(workshopStatus);
        
        workshopPathBox = new JTextField();
        workshopPathPanel.add(workshopPathBox);
        workshopPathBox.setColumns(10);
        
        JButton chooseWorkshopPathButton = new JButton("Browse...");
        workshopPathPanel.add(chooseWorkshopPathButton);
        contentPanel.add(inputPanel, BorderLayout.NORTH);
        
        JPanel panel = new JPanel();
        contentPanel.add(panel, BorderLayout.CENTER);
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        
        JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);
        panel.add(tabbedPane);
        
        JPanel dummyTab = new JPanel();
        tabbedPane.addTab("Dummy Tab", null, dummyTab, "*");
        dummyTab.setLayout(new BoxLayout(dummyTab, BoxLayout.X_AXIS));
        
        TextArea textArea = new TextArea();
        dummyTab.add(textArea);
        
        JPanel buttonPanel = new JPanel();
        frame.getContentPane().add(buttonPanel, BorderLayout.SOUTH);
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.LINE_AXIS));
        
        Component horizontalGlue = Box.createHorizontalGlue();
        buttonPanel.add(horizontalGlue);
        
        JLabel saveStatus = new JLabel("");
        saveStatus.setIcon(Resources.OK_ICON);
        buttonPanel.add(saveStatus);
        
        JButton saveButton = new JButton("Save Changes");
        buttonPanel.add(saveButton);
        
        JButton discardChangesButton = new JButton("Discard Changes");
        buttonPanel.add(discardChangesButton);
    }

    protected void attemptExit() {
        System.exit(0);
    }

}
