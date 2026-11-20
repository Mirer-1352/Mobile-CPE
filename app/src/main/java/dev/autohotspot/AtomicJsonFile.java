package dev.autohotspot;

import android.system.Os;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;

/** Readers and writers share a lock: older AtomicFile readers may restore .bak. */
final class AtomicJsonFile {
    interface Transaction<T> { T run(Record record) throws Exception; }
    private final File file, lock;
    private final int limit, owner;

    AtomicJsonFile(File file, File lock, int limit, int owner) {
        this.file = file;
        this.lock = lock;
        this.limit = limit;
        this.owner = owner;
    }

    JSONObject read() throws Exception {
        if (!file.getParentFile().isDirectory()) return null;
        return access(Record::read);
    }

    void write(JSONObject value) throws Exception {
        access(record -> { record.write(value); return null; });
    }

    void delete() throws Exception {
        if (file.getParentFile().isDirectory()) access(record -> { record.atomic.delete(); return null; });
    }

    <T> T access(Transaction<T> transaction) throws Exception {
        // FileLock alone throws for overlapping locks in one VM; serialize VM access too.
        synchronized (AtomicJsonFile.class) {
            try (RandomAccessFile access = new RandomAccessFile(lock, "rw")) {
                protect(lock);
                try (FileLock ignored = access.getChannel().lock()) {
                    return transaction.run(new Record());
                }
            }
        }
    }

    private void protect(File target) throws Exception {
        if (android.os.Process.myUid() == 0) Os.chown(target.getPath(), owner, owner);
        Os.chmod(target.getPath(), 0600);
    }

    final class Record {
        private final AtomicFile atomic = new AtomicFile(file);

        JSONObject read() throws Exception {
            if (!file.exists() && !new File(file.getPath() + ".bak").exists()) return null;
            try (InputStream input = atomic.openRead(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > limit) throw new IOException("JSON record exceeds size limit");
                    bytes.write(buffer, 0, count);
                }
                return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            }
        }

        void write(JSONObject value) throws Exception {
            byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > limit) throw new IOException("JSON record exceeds size limit");
            FileOutputStream output = atomic.startWrite();
            try { output.write(bytes); atomic.finishWrite(output); }
            catch (Exception error) { atomic.failWrite(output); throw error; }
            protect(file);
        }
    }
}
