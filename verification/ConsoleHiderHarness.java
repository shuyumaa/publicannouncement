import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.builder.api.AppenderComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Standalone verification for ConsoleHider - the class that hides this plugin's invocations from
 * the console and the logs.
 *
 * <p>It builds a Log4j2 configuration with a console appender and a file appender, exactly as a
 * server has, installs the filter the way the plugin does (reflectively), then logs the lines the
 * server itself writes for a player command - in Paper's own format, taken from
 * {@code ServerGamePacketListenerImpl#performUnsignedChatCommand} - and asserts which ones survive.
 * It also asserts that ordinary log lines and other players' commands are untouched, that the
 * passcode never reaches either sink, and that detaching restores logging.
 *
 * <p>Build the plugin, then run:
 * <pre>
 *   CP="target/classes:/path/to/log4j-api.jar:/path/to/log4j-core.jar"
 *   javac -cp "$CP" -d /tmp/harness verification/ConsoleHiderHarness.java
 *   java  -cp "/tmp/harness:$CP" ConsoleHiderHarness
 * </pre>
 * Exit status is 0 when every check passes. This file is a development harness: it sits outside
 * {@code src/} and is not part of the plugin jar.
 */
public final class ConsoleHiderHarness {

    /**
     * Written to the log at the start of each phase, so the file's contents can be split per phase
     * without closing the file appender. Distinct per phase, because everything a phase logs comes
     * after its own marker.
     */
    private static final String MARKER_BEFORE = "HARNESS-MARKER-BEFORE";
    private static final String MARKER_AFTER = "HARNESS-MARKER-AFTER";

    private record Case(String name, boolean hidden, Runnable emit) {
    }

    private static Logger mc;
    private static Logger plugin;

    public static void main(String[] args) throws Exception {
        Path logFile = Files.createTempFile("consolehider-harness", ".log");

        // Capture the console the way a control panel captures stdout: replace System.out before
        // the appenders are created, so the console appender writes into the buffer.
        ByteArrayOutputStream consoleBytes = new ByteArrayOutputStream();
        PrintStream realOut = System.out;
        System.setOut(new PrintStream(consoleBytes, true, StandardCharsets.UTF_8));

        LoggerContext context = configure(logFile);

        // Only now: a logger created before the configuration is bound belongs to Log4j2's default
        // configuration instead, and would bypass everything asserted below.
        mc = LogManager.getLogger("net.minecraft.server.network.ServerGamePacketListenerImpl");
        plugin = LogManager.getLogger("ServerDebug");

        List<Case> cases = new ArrayList<>();
        addPlayerCommandCases(cases);
        addCraftBukkitCase(cases);
        addControlCases(cases);

        // ------------------------------------------------------------------ before installing
        marker(mc, MARKER_BEFORE);
        for (Case c : cases) {
            c.emit().run();
        }
        String consoleBefore = consoleBytes.toString(StandardCharsets.UTF_8);
        String fileBefore = afterMarker(Files.readString(logFile), MARKER_BEFORE);

        // ------------------------------------------------------------------ install the filter
        // Exactly as ServerDebugPlugin#installConsoleHider does it: reflectively, so that a server
        // without a reachable Log4j2 never has to load this class at all.
        Class<?> hider = Class.forName("com.example.serverdebug.ConsoleHider");
        Method install = hider.getDeclaredMethod("install", Collection.class, Collection.class);
        install.setAccessible(true);
        String attachment = String.valueOf(
                install.invoke(null, List.of("serverdebug", "sdebug", "sdbg"), List.of()));

        // ------------------------------------------------------------------ after installing
        consoleBytes.reset();
        marker(mc, MARKER_AFTER);
        for (Case c : cases) {
            c.emit().run();
        }
        String consoleAfter = consoleBytes.toString(StandardCharsets.UTF_8);
        String fileAfter = afterMarker(Files.readString(logFile), MARKER_AFTER);

        // ------------------------------------------------------------------ after detaching
        Method detach = hider.getDeclaredMethod("detach");
        detach.setAccessible(true);
        detach.invoke(null);
        consoleBytes.reset();
        emitFirstHiddenCase(cases);
        String consoleAfterDetach = consoleBytes.toString(StandardCharsets.UTF_8);

        // A second install must work too, because /serverdebug reload reinstalls the filter.
        String reinstalled = String.valueOf(
                install.invoke(null, List.of("serverdebug", "sdebug", "sdbg"), List.of()));
        consoleBytes.reset();
        emitFirstHiddenCase(cases);
        String consoleAfterReinstall = consoleBytes.toString(StandardCharsets.UTF_8);

        System.setOut(realOut);
        context.stop();

        // ------------------------------------------------------------------ report
        List<String> failures = new ArrayList<>();

        System.out.println("filter attached to: " + attachment);
        System.out.println("reinstall reported: " + reinstalled);
        System.out.println();

        System.out.println("== before install: the server's lines must be present as-is ==");
        int missingBefore = 0;
        for (Case c : cases) {
            if (!consoleBefore.contains(c.name())) {
                missingBefore++;
                failures.add("line was missing before install: " + c.name());
            }
        }
        System.out.println("  " + cases.size() + " line(s) logged, " + missingBefore + " missing");

        System.out.println();
        System.out.println("== after install: console ==");
        for (Case c : cases) {
            boolean visible = consoleAfter.contains(c.name());
            boolean want = !c.hidden();
            if (visible != want) {
                failures.add((want ? "wrongly hidden" : "NOT hidden") + ": " + c.name());
            }
            System.out.printf("  %-4s %-8s %s%n", visible == want ? "PASS" : "FAIL",
                    want ? "visible" : "hidden", shorten(c.name()));
        }

        System.out.println();
        System.out.println("== before install: file appender (logs/latest.log) ==");
        int missingBeforeFile = 0;
        for (Case c : cases) {
            if (!fileBefore.contains(c.name())) {
                missingBeforeFile++;
                failures.add("file appender was missing a line before install: " + c.name());
            }
        }
        System.out.println("  " + cases.size() + " line(s) logged, " + missingBeforeFile + " missing");

        System.out.println();
        System.out.println("== after install: file appender (logs/latest.log) ==");
        int fileMismatches = 0;
        for (Case c : cases) {
            if (fileAfter.contains(c.name()) != !c.hidden()) {
                fileMismatches++;
                failures.add("file appender disagrees: " + c.name());
            }
        }
        System.out.println(fileMismatches == 0
                ? "  matches the console for all " + cases.size() + " line(s)"
                : "  " + fileMismatches + " mismatch(es)");

        System.out.println();
        System.out.println("== secrets and cleanup ==");
        check(failures, "passcode 'retry' absent from the console after install",
                !consoleAfter.contains("retry"));
        check(failures, "passcode 'retry' absent from the log file after install",
                !fileAfter.contains("retry"));
        check(failures, "owner name absent from the console after install",
                !consoleAfter.contains("Induct1oN"));
        check(failures, "owner name absent from the log file after install",
                !fileAfter.contains("Induct1oN"));
        check(failures, "detach() restores logging",
                consoleAfterDetach.contains("issued server command: /serverdebug"));
        check(failures, "reinstall() hides again after a reload",
                !consoleAfterReinstall.contains("issued server command: /serverdebug"));

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("RESULT: all checks passed");
        } else {
            System.out.println("RESULT: " + failures.size() + " check(s) FAILED");
            for (String f : failures) {
                System.out.println("  - " + f);
            }
        }
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    /** The lines the server writes for a command a player typed, in Paper's format. */
    private static void addPlayerCommandCases(List<Case> cases) {
        cases.add(playerCommand("/serverdebug retry op Notch"));
        cases.add(playerCommand("/sdebug retry say hello"));
        cases.add(playerCommand("/sdbg retry time set day"));
        cases.add(playerCommand("/serverdebug hi"));
        cases.add(playerCommand("/serverdebug reload"));
        cases.add(playerCommand("/ServerDebug retry stop"));
        cases.add(playerCommand("//serverdebug retry x"));
        cases.add(playerCommand("/bukkit:serverdebug retry x"));
        cases.add(playerCommand("/serverdebug retry say issued server command: /op me"));
    }

    private static void addCraftBukkitCase(List<Case> cases) {
        cases.add(new Case("Unhandled exception executing command 'serverdebug' in plugin ServerDebug v1.0.0",
                true, () -> mc.error("Unhandled exception executing command '{}' in plugin {}",
                "serverdebug", "ServerDebug v1.0.0")));
    }

    /** Lines that must keep working: the filter must not be a blanket mute. */
    private static void addControlCases(List<Case> cases) {
        cases.add(playerCommand("/gamemode creative", "SomeOtherPlayer"));
        cases.add(playerCommand("/sdbgx foo", "SomeOtherPlayer"));
        cases.add(playerCommand("/serverdebugger foo", "SomeOtherPlayer"));
        cases.add(playerCommand("/op Notch", "SomeOtherPlayer"));
        cases.add(new Case("Starting minecraft server version 1.21.11", false,
                () -> mc.info("Starting minecraft server version {}", "1.21.11")));
        cases.add(new Case("Loaded plugin ServerDebug v1.0.0", false,
                () -> plugin.info("Loaded plugin ServerDebug v1.0.0")));
        cases.add(new Case("Notch joined the game", false,
                () -> mc.info("{} joined the game", "Notch")));
    }

    private static Case playerCommand(String prefixedCommand) {
        return playerCommand(prefixedCommand, "Induct1oN");
    }

    /** A line from the owner is expected to be hidden; another player's line is a control. */
    private static Case playerCommand(String prefixedCommand, String playerName) {
        return new Case(playerName + " issued server command: " + prefixedCommand,
                "Induct1oN".equals(playerName),
                () -> mc.info("{} issued server command: {}", playerName, prefixedCommand));
    }

    private static void emitFirstHiddenCase(List<Case> cases) {
        for (Case c : cases) {
            if (c.hidden()) {
                c.emit().run();
                return;
            }
        }
    }

    private static void marker(Logger logger, String tag) {
        logger.info(tag);
    }

    /** Everything logged after the given phase's marker, i.e. that phase's lines. */
    private static String afterMarker(String fileContents, String tag) {
        int index = fileContents.indexOf(tag);
        if (index < 0) {
            System.out.println("  WARNING: phase marker " + tag + " not found in the log file");
            return fileContents;
        }
        return fileContents.substring(index + tag.length());
    }

    private static void check(List<String> failures, String what, boolean ok) {
        System.out.println("  " + (ok ? "PASS" : "FAIL") + "  " + what);
        if (!ok) {
            failures.add(what);
        }
    }

    private static String shorten(String name) {
        return name.length() <= 78 ? name : name.substring(0, 75) + "...";
    }

    private static LoggerContext configure(Path logFile) {
        ConfigurationBuilder<BuiltConfiguration> builder =
                ConfigurationBuilderFactory.newConfigurationBuilder();
        builder.setConfigurationName("Harness");
        builder.setStatusLevel(Level.ERROR);

        AppenderComponentBuilder console = builder.newAppender("Console", "CONSOLE")
                .addAttribute("target", "SYSTEM_OUT")
                .addAttribute("follow", true)
                .add(builder.newLayout("PatternLayout")
                        .addAttribute("pattern", "%level %logger - %msg%n"));
        AppenderComponentBuilder file = builder.newAppender("File", "File")
                .addAttribute("fileName", logFile.toString())
                .addAttribute("append", false)
                .add(builder.newLayout("PatternLayout").addAttribute("pattern", "%msg%n"));

        builder.add(console);
        builder.add(file);
        builder.add(builder.newRootLogger(Level.INFO)
                .add(builder.newAppenderRef("Console"))
                .add(builder.newAppenderRef("File")));
        return Configurator.initialize(builder.build());
    }
}
