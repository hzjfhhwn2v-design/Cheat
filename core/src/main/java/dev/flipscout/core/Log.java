package dev.flipscout.core;

/** Minimal logging facade; each platform plugs in its own logger. */
public final class Log {
    public enum Level { DEBUG, INFO, WARN, ERROR }

    public interface Sink {
        void log(Level level, String message, Throwable error);
    }

    private static volatile Sink sink = new Sink() {
        @Override
        public void log(Level level, String message, Throwable error) {
            System.out.println("[FlipScout/" + level + "] " + message);
            if (error != null) {
                error.printStackTrace(System.out);
            }
        }
    };

    public static volatile boolean debugEnabled = false;

    private Log() {
    }

    public static void setSink(Sink newSink) {
        sink = newSink;
    }

    public static void debug(String message) {
        if (debugEnabled) {
            sink.log(Level.INFO, "[debug] " + message, null);
        }
    }

    public static void info(String message) {
        sink.log(Level.INFO, message, null);
    }

    public static void warn(String message) {
        sink.log(Level.WARN, message, null);
    }

    public static void warn(String message, Throwable error) {
        sink.log(Level.WARN, message, error);
    }

    public static void error(String message, Throwable error) {
        sink.log(Level.ERROR, message, error);
    }
}
