package com.bimvo.app;

import java.io.*;
import java.util.Properties;
import java.util.Set;

/** Filename safety and recovery for app-owned audio renaming, including Cyrillic. */
final class StoredAudioNames {
    static boolean valid(String id, String name) {
        if (id == null || !id.matches("[a-f0-9-]{36}") || name == null) return false;
        if (name.contains("/") || name.contains("\\") || name.indexOf(0) >= 0) return false;
        return name.matches("[a-f0-9-]{36}\\.[a-z0-9]{1,8}") ||
                (name.startsWith(id + "_") && name.length() > 37 &&
                 name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 255);
    }
    static void writeJournal(File file, String from, String to) throws IOException {
        Properties p = new Properties(); p.setProperty("from", from); p.setProperty("to", to);
        try (FileOutputStream out = new FileOutputStream(file)) { p.store(out, null); out.getFD().sync(); }
    }
    static void recoverRenames(File directory, Set<String> referenced) throws IOException {
        File[] journals = directory.listFiles((dir, name) -> name.startsWith(".rename-"));
        if (journals == null) return;
        for (File journal : journals) {
            String id = journal.getName().substring(8);
            Properties p = new Properties();
            try (InputStream in = new FileInputStream(journal)) { p.load(in); }
            String from = p.getProperty("from"), to = p.getProperty("to");
            if (valid(id, from) && valid(id, to) && referenced.contains(from)) {
                File oldFile = new File(directory, from), newFile = new File(directory, to);
                if (!oldFile.exists() && newFile.exists() && !newFile.renameTo(oldFile))
                    throw new IOException("Не удалось восстановить аудиофайл после переименования");
            }
            journal.delete();
        }
    }
}
