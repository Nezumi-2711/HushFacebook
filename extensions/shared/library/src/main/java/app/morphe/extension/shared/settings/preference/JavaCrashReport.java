/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.shared.settings.preference;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;

import androidx.annotation.Nullable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticRedactor;

/**
 * Keeps the Java crash that ends the app for the diagnostic report's [LATEST JAVA CRASH] section.
 *
 * <p>Android keeps no stack trace for a Java crash: its exit record says CRASH and nothing more, so
 * a report sent after "Facebook keeps stopping" (#94) couldn't say what stopped it. The pause
 * module's uncaught-exception handler hands each crash here before passing it on. The trace and
 * the last diagnostic events go through the redactor and stay in Facebook's own files, so every
 * report exported after it carries them, until the next crash replaces them or the diagnostic log
 * is cleared. While the app's crash file switch is on, the same text also goes to a file in
 * Download/Hushfacebook ({@link CrashFile}), for a Facebook that crashes before settings open.
 *
 * <p>The handler sits in front of Facebook's own, so it also sees an exception one of Facebook's
 * swallows. When the handlers after it return, the process lives on, and an exception off the main
 * thread puts the report back as it was ({@link #survived}) and takes its file back, so it can't
 * push out a crash from an earlier start. One swallowed on the main thread stays: that thread is gone, and Facebook freezes.
 *
 * <p>Each save answers its own {@link Saved}, so a swallowed exception takes back only what it
 * wrote. While another thread's crash saved after it is still waiting on its handlers, the report
 * is that crash's, and it stays.
 */
public final class JavaCrashReport {
    /** How much of the diagnostic event log goes in after the trace. */
    static final int RECENT_EVENTS_MAX_CHARS = 12_000;

    /** The exception kept last, weakly: a handler Facebook put on top of ours can hand it back. */
    private static volatile WeakReference<Throwable> lastSaved = new WeakReference<>(null);

    /**
     * The saves whose handlers haven't let the process live on, oldest first, each in the report
     * after the one before it. A main thread's stays, as its report does. Guarded by itself.
     */
    private static final List<Saved> PENDING = new ArrayList<>();

    /** One save, for {@link #survived}: what the report held before it, and the file it wrote. */
    public static final class Saved {
        /** "" for nothing. A save before this one that lives on hands this one its own. */
        String before;

        @Nullable
        final Uri file;

        Saved(String before, @Nullable Uri file) {
            this.before = before;
            this.file = file;
        }
    }

    private JavaCrashReport() {
    }

    /**
     * Keeps [throwable] as the latest Java crash, unless it's the one kept last. Answers the save,
     * for {@link #survived}, or null when nothing was written. Never throws.
     */
    @Nullable
    public static Saved save(@Nullable Thread thread, @Nullable Throwable throwable) {
        try {
            Context context = Utils.getContext();
            if (context == null || throwable == null || lastSaved.get() == throwable) return null;
            lastSaved = new WeakReference<>(throwable);
            String report = build(context, thread, throwable);
            synchronized (PENDING) {
                String before = LogBufferManager.readCrashReport(context);
                LogBufferManager.persistCrashReport(context, report);
                Uri file = CrashFile.wanted() ? CrashFile.write(context, report, System.currentTimeMillis()) : null;
                Saved saved = new Saved(before, file);
                PENDING.add(saved);
                return saved;
            }
        } catch (Throwable ignored) {
            // Crash-time code must never get in the way of the handlers after this one.
            return null;
        }
    }

    /**
     * The handlers after ours returned for [thread]'s exception, so the process lives on. Off the
     * main thread, its file goes, and the report goes back to what it held before [saved], unless a
     * newer save is in it. That one then goes back there when it lives on too. Never throws.
     */
    public static void survived(@Nullable Thread thread, @Nullable Saved saved) {
        try {
            Context context = Utils.getContext();
            if (context == null || saved == null || thread == null || thread == Looper.getMainLooper().getThread()) return;
            synchronized (PENDING) {
                int at = PENDING.indexOf(saved);
                if (at < 0) return;
                PENDING.remove(at);
                if (at == PENDING.size()) {
                    LogBufferManager.persistCrashReport(context, saved.before);
                } else {
                    PENDING.get(at).before = saved.before;
                }
                if (saved.file != null) CrashFile.takeBack(context, saved.file);
            }
        } catch (Throwable ignored) {
            // The exception's report stays; nothing else is lost.
        }
    }

    static String build(Context context, @Nullable Thread thread, Throwable throwable) {
        StringBuilder report = new StringBuilder();
        report.append("schema: 1\n")
                .append("complete: true\n")
                .append("timestamp_utc: ").append(Instant.ofEpochMilli(System.currentTimeMillis())).append('\n')
                .append("package: ").append(context.getPackageName()).append('\n')
                .append("app_version: ").append(Utils.getAppVersionName()).append('\n')
                .append("morphe_version: ").append(Utils.getPatchesReleaseVersion()).append('\n')
                .append("android_api: ").append(Build.VERSION.SDK_INT).append('\n')
                .append("process: ").append(Application.getProcessName()).append('\n')
                .append("thread: ").append(thread == null ? "unknown" : thread.getName()).append('\n')
                .append("exception: ").append(throwable.getClass().getName()).append('\n')
                .append("\n[STACK TRACE]\n").append(stackTrace(throwable));
        String recent = LogBufferManager.snapshotForCrash(RECENT_EVENTS_MAX_CHARS);
        if (!recent.isEmpty()) report.append("\n[RECENT EVENTS]\n").append(recent).append('\n');
        // Messages and log lines can carry addresses and ids: a download names what it couldn't reach.
        return DiagnosticRedactor.redact(report.toString());
    }

    /** The whole trace, causes and suppressed ones included, as Java prints it. */
    private static String stackTrace(Throwable throwable) {
        StringWriter text = new StringWriter();
        try (PrintWriter writer = new PrintWriter(text)) {
            throwable.printStackTrace(writer);
        }
        return text.toString();
    }

    /** Forgets the exception kept last and the saves waiting, as in a new process. Called by the pause module's reset. */
    public static void resetForTests() {
        lastSaved = new WeakReference<>(null);
        synchronized (PENDING) {
            PENDING.clear();
        }
    }
}
