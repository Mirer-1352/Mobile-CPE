package dev.autohotspot;

import android.os.SystemClock;
import android.system.*;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import dev.autohotspot.AmbrProtocol.Unavailable;

/** Read-only 27.007 queries on one known auxiliary MTK AT node; never opens an owned/shared port. */
final class ModemAmbrReader {
    static JSONObject read(int slot, String internetApn) throws Exception {
        if (slot < 0 || slot > 1) throw new Unavailable("SIM_UNCONFIRMED");
        if (internetApn.isEmpty()) throw new Unavailable("NO_CONTEXT");
        Class<?> props = Class.forName("android.os.SystemProperties");
        String platform = (String) props.getMethod("get", String.class, String.class).invoke(null, "ro.mediatek.platform", "");
        if (!platform.toUpperCase(Locale.ROOT).startsWith("MT")) throw new Unavailable("UNSUPPORTED");
        String path = "/dev/ttyC0";
        StructStat node;
        try { node = Os.stat(path); } catch (ErrnoException absent) { throw new Unavailable("NO_PORT"); }
        if (!OsConstants.S_ISCHR(node.st_mode)) throw new Unavailable("NO_PORT");
        // Query budget is independent of tethering and is bounded across all commands.
        long deadline = SystemClock.elapsedRealtime() + 5000;
        try (Channel channel = new Channel(path, node.st_rdev, deadline)) {
            AmbrProtocol.Limit limit = AmbrProtocol.read(slot, internetApn, channel::query);
            return new JSONObject().put("kind", limit.kind).put("downKbps", limit.downKbps).put("upKbps", limit.upKbps);
        }
    }

    private static final class Channel implements AutoCloseable {
        private final FileDescriptor descriptor;
        private final long deadline;
        private boolean exclusive;
        Channel(String path, long device, long deadline) throws Exception {
            this.deadline = deadline;
            owners(device, deadline);
            // Never send AT until exclusive ownership and absence of existing owners are confirmed.
            descriptor = Os.open(path, OsConstants.O_RDWR | OsConstants.O_NONBLOCK | OsConstants.O_CLOEXEC | OsConstants.O_NOCTTY, 0);
            try {
                StructStat opened = Os.fstat(descriptor);
                if (!OsConstants.S_ISCHR(opened.st_mode) || opened.st_rdev != device) throw new Unavailable("BUSY");
                ioctl(descriptor, 0x540C); exclusive = true;
                owners(device, deadline);
                // Do not interpret a prior session's pending OK/QoS response as ours.
                long drainUntil = Math.min(deadline, SystemClock.elapsedRealtime() + 200);
                StructPollfd pending = new StructPollfd(); pending.fd = descriptor; pending.events = (short) OsConstants.POLLIN;
                int drained = 0; byte[] discard = new byte[1024];
                while (Os.poll(new StructPollfd[]{pending}, 0) > 0) {
                    if (SystemClock.elapsedRealtime() >= drainUntil || drained > 16384) throw new Unavailable("BUSY");
                    int count = Os.read(descriptor, discard, 0, discard.length);
                    if (count <= 0) break;
                    drained += count;
                }
            } catch (Exception | LinkageError failure) { close(); throw failure; }
        }
        private static void owners(long device, long deadline) throws Exception {
                File[] processes = new File("/proc").listFiles();
                if (processes == null) throw new Unavailable("BUSY");
                for (File process : processes) {
                    if (!process.getName().matches("[0-9]+") || process.getName().equals(Integer.toString(android.os.Process.myPid()))) continue;
                    File directory = new File(process, "fd");
                    File[] files = directory.listFiles();
                    if (files == null) { if (directory.exists()) throw new Unavailable("BUSY"); else continue; }
                    for (File fd : files) try {
                        if (SystemClock.elapsedRealtime() >= deadline) throw new Unavailable("BUSY");
                        StructStat value = Os.stat(fd.getPath());
                        if (OsConstants.S_ISCHR(value.st_mode) && value.st_rdev == device) throw new Unavailable("BUSY");
                    } catch (ErrnoException gone) { if (gone.errno != OsConstants.ENOENT && gone.errno != OsConstants.ESRCH) throw new Unavailable("BUSY"); }
                }
        }
        private static void ioctl(FileDescriptor descriptor, int request) throws Exception {
            // Linux asm-generic/ioctls.h TIOCEXCL; ROM method ABI is discovered, not guessed.
            try {
                Object bridge = Class.forName("libcore.io.Libcore").getField("os").get(null);
                for (Method method : Class.forName("libcore.io.Os").getMethods()) {
                    Class<?>[] p = method.getParameterTypes();
                    if (Arrays.asList("ioctlVoid", "ioctlInt", "ioctlRet").contains(method.getName()) &&
                        Arrays.equals(p, new Class<?>[]{FileDescriptor.class, int.class})) {
                        method.invoke(bridge, descriptor, request); return;
                    }
                }
                // Older ROMs use a mutable integer holder. Discover its actual type from the ABI.
                for (Method method : Class.forName("libcore.io.Os").getMethods()) {
                    Class<?>[] p = method.getParameterTypes();
                    if (method.getName().equals("ioctlInt") && p.length == 3 && p[0] == FileDescriptor.class && p[1] == int.class) {
                        method.invoke(bridge, descriptor, request, p[2].getConstructor(int.class).newInstance(0)); return;
                    }
                }
                throw new NoSuchMethodException("No compatible ioctl ABI");
            } catch (Exception | LinkageError denied) { throw new Unavailable("EXCLUSIVE_UNAVAILABLE"); }
        }
        String query(String command) throws Exception {
            if (Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime() >= deadline) throw new Unavailable("UNAVAILABLE");
            byte[] request = (command + "\r").getBytes(StandardCharsets.US_ASCII);
            int written = Os.write(descriptor, request, 0, request.length);
            if (written != request.length) throw new Unavailable("UNAVAILABLE");
            long until = Math.min(deadline, SystemClock.elapsedRealtime() + 800);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] block = new byte[1024];
            StructPollfd poll = new StructPollfd(); poll.fd = descriptor; poll.events = (short) OsConstants.POLLIN;
            while (SystemClock.elapsedRealtime() < until && !Thread.currentThread().isInterrupted()) {
                if (Os.poll(new StructPollfd[]{poll}, (int) Math.max(0, Math.min(100, until - SystemClock.elapsedRealtime()))) == 0) continue;
                try {
                    int count = Os.read(descriptor, block, 0, block.length);
                    if (count <= 0) throw new Unavailable("UNAVAILABLE");
                    if (bytes.size() + count > 16384) throw new Unavailable("UNAVAILABLE");
                    bytes.write(block, 0, count);
                    String response = bytes.toString("US-ASCII");
                    Matcher terminal = Pattern.compile("(?m)^(OK|ERROR|\\+CME ERROR:[^\\r\\n]*)\\r?\\n").matcher(response);
                    if (terminal.find()) {
                        if (terminal.group(1).equals("OK")) return response.substring(0, terminal.end());
                        throw new Unavailable("NO_AMBR");
                    }
                } catch (ErrnoException retry) { if (retry.errno != OsConstants.EAGAIN && retry.errno != OsConstants.EINTR) throw retry; }
            }
            throw new Unavailable("UNAVAILABLE");
        }
        @Override public void close() {
            // Explicitly undo our flag even if another privileged process raced an open.
            if (exclusive) try { ioctl(descriptor, 0x540D); } catch (Exception | LinkageError ignored) { }
            try { Os.close(descriptor); } catch (ErrnoException ignored) { }
        }
    }
}
