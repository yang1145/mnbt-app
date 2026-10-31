package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A terminal session: a byte stream coupled to a terminal emulator.
 * <p>
 * This is a modified version of the Termux class of the same name. Upstream couples the session to a
 * local pseudo-terminal created through JNI; here the process end is replaced by a pluggable
 * {@link Channel} so the same emulator and view can render a remote shell (an SSH session).
 * <p>
 * Emulation starts once the size is known, via {@link #updateSize(int, int, int, int)}. All terminal
 * emulation and {@link TerminalSessionClient} callbacks happen on the main thread; the caller feeds
 * bytes in from any thread through {@link #onRemoteOutput(byte[], int, int)}.
 */
public class TerminalSession extends TerminalOutput {

    /**
     * The process end of the session. Implementations must tolerate being called from the session's
     * private writer thread and after {@link #close()}.
     */
    public interface Channel {

        /** Send bytes as terminal input. Called from a private thread, never from the main thread. */
        void write(byte[] data, int offset, int count);

        /** Request a new window size. Called from the main thread. */
        void resize(int columns, int rows);

        /** Terminate the session. Must be idempotent. */
        void close();
    }

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_PROCESS_EXITED = 4;

    private static final int STATE_NEW = 0;
    private static final int STATE_RUNNING = 1;
    private static final int STATE_EXITED = 2;

    public final String mHandle = UUID.randomUUID().toString();

    volatile TerminalEmulator mEmulator;

    /**
     * A queue written to from the transport thread when the remote end outputs, and read by the main
     * thread to process by the terminal emulator.
     */
    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(4096);
    /**
     * A queue written to from the main thread due to user interaction, and read by the writer thread
     * which forwards it to the {@link Channel}.
     */
    final ByteQueue mTerminalToProcessIOQueue = new ByteQueue(4096);
    /** Buffer to write translate code points into utf8 before writing to mTerminalToProcessIOQueue */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    private volatile int mState = STATE_NEW;

    /** The exit status of the session. Only valid once {@link #isRunning()} returns false. */
    private int mExitStatus;

    private final Channel mChannel;
    private final Integer mTranscriptRows;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    private static final String LOG_TAG = "TerminalSession";

    public TerminalSession(Channel channel, Integer transcriptRows, TerminalSessionClient client) {
        this.mChannel = channel;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the transport of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            if (mState == STATE_RUNNING) mChannel.resize(columns, rows);
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Set the terminal emulator's window size and start terminal emulation.
     *
     * @param columns The number of columns in the terminal window.
     * @param rows    The number of rows in the terminal window.
     */
    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);

        synchronized (this) {
            if (mState == STATE_NEW) mState = STATE_RUNNING;
        }
        mChannel.resize(columns, rows);

        new Thread("TermSessionOutputWriter[" + mHandle + "]") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                try {
                    while (true) {
                        int bytesToWrite = mTerminalToProcessIOQueue.read(buffer, true);
                        if (bytesToWrite == -1) return;
                        mChannel.write(buffer, 0, bytesToWrite);
                    }
                } catch (Exception e) {
                    // The channel went away while we were blocked; the session is being torn down.
                }
            }
        }.start();

        // Bytes may have arrived from the transport before a view was attached. Drain them now.
        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
    }

    /**
     * Feed output from the remote end into the emulator. Safe to call from any thread.
     */
    public void onRemoteOutput(byte[] data, int offset, int count) {
        if (count <= 0 || mState == STATE_EXITED) return;
        // Blocks while the queue is full, which is the backpressure upstream relies on too.
        if (!mProcessToTerminalIOQueue.write(data, offset, count)) return;
        // With no emulator yet the bytes stay queued and are drained at the end of
        // initializeEmulator(); posting now would only make the handler dereference null.
        if (mEmulator == null) return;
        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
    }

    /**
     * Notify the session that the remote end has gone away. Safe to call from any thread, and safe
     * to call more than once; only the first call has an effect.
     *
     * @param exitStatus A platform exit code, or a negated signal number.
     */
    public void onRemoteClosed(int exitStatus) {
        mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, exitStatus));
    }

    /** Write data to the remote end. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (mState == STATE_RUNNING) mTerminalToProcessIOQueue.write(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** Finish this terminal session by closing the transport. */
    public void finishIfRunning() {
        if (!isRunning()) return;
        try {
            mChannel.close();
        } catch (Exception e) {
            Logger.logWarn(mClient, LOG_TAG, "Failed closing channel: " + e.getMessage());
        }
        // The transport may never report the teardown back (it is already closed), so drive the
        // exit path ourselves. cleanupResources() is idempotent, so a late callback is harmless.
        onRemoteClosed(0);
    }

    /**
     * Cleanup resources when the session ends.
     *
     * @return true if this call performed the transition, false if the session had already ended.
     */
    boolean cleanupResources(int exitStatus) {
        synchronized (this) {
            if (mState == STATE_EXITED) return false;
            mState = STATE_EXITED;
            mExitStatus = exitStatus;
        }

        // Stop the writer thread and close the I/O queues.
        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();
        try {
            mChannel.close();
        } catch (Exception e) {
            Logger.logWarn(mClient, LOG_TAG, "Failed closing channel: " + e.getMessage());
        }
        return true;
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public boolean isRunning() {
        return mState != STATE_EXITED;
    }

    /** Only valid if not {@link #isRunning()}. */
    public synchronized int getExitStatus() {
        return mExitStatus;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[4 * 1024];

        MainThreadHandler() {
            // Upstream relies on the implicit "current thread must have a Looper" behaviour, which
            // breaks when a session is created from a background thread as ours are.
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            TerminalEmulator emulator = mEmulator;
            if (emulator == null) {
                // No view has been attached yet, so there is nothing to render into. Leave the
                // queued bytes alone — initializeEmulator() drains them once a size is known — but
                // still run the exit path so a session that dies before it is ever shown does not
                // leave its client waiting.
                if (msg.what == MSG_PROCESS_EXITED && cleanupResources((Integer) msg.obj)) {
                    mClient.onSessionFinished(TerminalSession.this);
                }
                return;
            }

            int bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
            if (bytesRead > 0) {
                emulator.append(mReceiveBuffer, bytesRead);
                notifyScreenUpdate();
            }

            if (msg.what == MSG_PROCESS_EXITED) {
                int exitCode = (Integer) msg.obj;
                if (!cleanupResources(exitCode)) return;

                String exitDescription = "\r\n[会话已结束";
                if (exitCode > 0) {
                    // Non-zero process exit.
                    exitDescription += "，退出码 " + exitCode;
                } else if (exitCode < 0) {
                    // Negated signal.
                    exitDescription += "，信号 " + (-exitCode);
                }
                exitDescription += "]";

                byte[] bytesToWrite = exitDescription.getBytes(StandardCharsets.UTF_8);
                emulator.append(bytesToWrite, bytesToWrite.length);
                notifyScreenUpdate();

                mClient.onSessionFinished(TerminalSession.this);
            }
        }

    }

}
