package org.example;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;

public class InterfazFTP extends JFrame {
    private JTextField ftpServerField;
    private JTextField ftpPortField;
    private JTextField ftpUserField;
    private JPasswordField ftpPasswordField;
    private JTextField syncDirField;
    private JTextField authorField;
    private JTextField lifetimeField;
    private JTextField deleteFilePathField;
    private JButton selectFileButton;
    private JButton uploadButton;
    private JButton deleteFileButton;
    private JLabel statusLabel;
    private File selectedFile;
    private SincronizadorFTP enhancedSuperSync;

    public InterfazFTP() {
        setTitle("Photo Uploader");
        setSize(500, 400);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new GridLayout(12, 2));

        ftpServerField = new JTextField("localhost");
        ftpPortField = new JTextField("21");
        ftpUserField = new JTextField("Admin");
        ftpPasswordField = new JPasswordField("Admin1.");
        syncDirField = new JTextField(System.getProperty("user.dir"));
        authorField = new JTextField();
        lifetimeField = new JTextField();
        deleteFilePathField = new JTextField();
        selectFileButton = new JButton("Select File");
        uploadButton = new JButton("Upload");
        deleteFileButton = new JButton("Delete File");
        statusLabel = new JLabel();

        add(new JLabel("FTP Server:"));
        add(ftpServerField);
        add(new JLabel("FTP Port:"));
        add(ftpPortField);
        add(new JLabel("FTP User:"));
        add(ftpUserField);
        add(new JLabel("FTP Password:"));
        add(ftpPasswordField);
        add(new JLabel("Sync Directory:"));
        add(syncDirField);
        add(new JLabel("Author:"));
        add(authorField);
        add(new JLabel("Lifetime (seconds):"));
        add(lifetimeField);
        add(new JLabel("File to Delete:"));
        add(deleteFilePathField);
        add(selectFileButton);
        add(uploadButton);
        add(deleteFileButton);
        add(new JLabel("Status:"));
        add(statusLabel);

        selectFileButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                JFileChooser fileChooser = new JFileChooser();
                int result = fileChooser.showOpenDialog(InterfazFTP.this);
                if (result == JFileChooser.APPROVE_OPTION) {
                    selectedFile = fileChooser.getSelectedFile();
                    statusLabel.setText("Selected file: " + selectedFile.getName());
                }
            }
        });

        uploadButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String ftpServer = ftpServerField.getText();
                String ftpPortText = ftpPortField.getText();
                String ftpUser = ftpUserField.getText();
                String ftpPassword = new String(ftpPasswordField.getPassword());
                String syncDir = syncDirField.getText();
                String author = authorField.getText();
                String lifetimeText = lifetimeField.getText();

                if (selectedFile == null) {
                    statusLabel.setText("No file selected");
                    return;
                }

                // Verificar que los campos del puerto FTP y lifetime no estén vacíos y sean números válidos
                try {
                    int ftpPort = Integer.parseInt(ftpPortText);
                    int lifetime = Integer.parseInt(lifetimeText);

                    try {
                        File syncDirectory = new File(syncDir);
                        enhancedSuperSync = new SincronizadorFTP(syncDirectory, ftpServer, ftpPort, ftpUser, ftpPassword);
                        enhancedSuperSync.upload(selectedFile, author, lifetime * 1000L);
                        statusLabel.setText("File uploaded successfully");
                    } catch (IOException ex) {
                        ex.printStackTrace();
                        statusLabel.setText("Error: " + ex.getMessage());
                    }
                } catch (NumberFormatException nfe) {
                    Logger.logError("Formato de número inválido para puerto FTP o vida útil: " + ftpPortText + ", " + lifetimeText);
                    JOptionPane.showMessageDialog(null, "Por favor ingresa un número válido para el puerto FTP y la vida útil.");
                    statusLabel.setText("Error: Formato de número inválido para puerto FTP o vida útil");
                }
            }


        });

        deleteFileButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String ftpServer = ftpServerField.getText();
                int ftpPort = Integer.parseInt(ftpPortField.getText());
                String ftpUser = ftpUserField.getText();
                String ftpPassword = new String(ftpPasswordField.getPassword());
                String syncDir = syncDirField.getText();
                String filePathToDelete = deleteFilePathField.getText();

                try {
                    File syncDirectory = new File(syncDir);
                    enhancedSuperSync = new SincronizadorFTP(syncDirectory, ftpServer, ftpPort, ftpUser, ftpPassword);
                    enhancedSuperSync.deleteFile(filePathToDelete, Boolean.parseBoolean(ftpUser));
                    statusLabel.setText("File deleted successfully");
                } catch (IOException ex) {
                    ex.printStackTrace();
                    statusLabel.setText("Error: " + ex.getMessage());
                }
            }
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                new InterfazFTP().setVisible(true);
            }
        });
    }
}
