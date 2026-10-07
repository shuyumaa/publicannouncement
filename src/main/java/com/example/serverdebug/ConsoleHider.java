package com.example.serverdebug;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.core.filter.Filterable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Drops every log record of an invocation of this plugin's command, so an invocation leaves no
 * trace in the server console, in a control-panel console, or in {@code logs/latest.log}.
 *
 * <h2>Why a log filter and not an event</h2>
 * When a player types a command, the server writes {@code <player> issued server command:
 * /<command>} itself, straight to its logger. In Paper that call sits in
 * {@code ServerGamePacketListenerImpl#performUnsignedChatCommand}, immediately <em>before</em>
 * {@code PlayerCommandPreprocessEvent} is fired: by the time any plugin hears about the command the
 * line is already written, so no Bukkit event can suppress it. The logging layer is the only place
 * it can be stopped, and this class is the Log4j2 {@link org.apache.logging.log4j.core.Filter} that
 * returns {@link Result#DENY} for exactly those records and {@link Result#NEUTRAL} for everything
 * else.
 *
 * <h2>Where it is attached</h2>
 * To every appender of the live Log4j2 configuration: terminal/console, any control-panel console,
 * the server GUI console, the rolling file appender and any other. Log4j2 asks an appender for its
 * own filter before writing ({@code AppenderControl#isFilteredByAppender}), so a {@code DENY} here
 * stops the record at that appender. An appender that does not implement {@link Filterable} cannot
 * be asked, so the root logger is filtered as well in that case, which drops the record before any
 * appender sees it.
 *
 * <h2>What is matched</h2>
 * <ol>
 *   <li>a player-command line whose command label is one of this plugin's labels or aliases
 *       ({@code serverdebug}, {@code sdebug}, {@code sdbg} - read from plugin.yml at runtime);</li>
 *   <li>Bukkit's phrasing when a command handler throws, {@code executing command 'label'};</li>
 *   <li>any extra substring listed under {@code hidden-log-substrings} in config.yml.</li>
 * </ol>
 * A command run <em>through</em> the plugin, dispatched by the plugin's own proxy sender, never
 * reaches this filter: a command dispatched by a plugin is not logged as a player command at all.
 *
 * <p>The filter is stateless between calls and safe to share across appenders and logging threads.
 * This class references no Bukkit type and is loaded reflectively by the plugin, so that a server
 * whose logging backend is unreachable fails with an explicit report rather than a
 * {@link NoClassDefFoundError}; see {@code ServerDebugPlugin#installConsoleHider()}.</p>
 */
public final class ConsoleHider extends AbstractFilter {

    /** Prefix of the line the server writes for a command a player typed. */
    private static final String ISSUED_MARKER = "issued server command:";

    /** Prefix of CraftBukkit's line when a command handler throws, immediately followed by 'label'. */
    private static final String EXECUTING_MARKER = "executing command '";

    /** The filter currently installed, so that re-installing detaches the previous one first. */
    private static volatile ConsoleHider installed;

    private final Set<String> labels;
    private final Set<String> substrings;

    private ConsoleHider(Set<String> labels, Set<String> substrings) {
        // onMatch = DENY (drop the record), onMismatch = NEUTRAL (not ours, log it normally).
        super(Result.DENY, Result.NEUTRAL);
        this.labels = labels;
        this.substrings = substrings;
    }

    /**
     * Installs the filter, replacing any previously installed one.
     *
     * @param labels     this plugin's command labels and aliases, matched case-insensitively
     * @param substrings extra case-insensitive substrings to drop
     * @return a short description of what was filtered, for the plugin's check report
     * @throws IllegalStateException if there is nothing to attach to, in which case nothing is hidden
     */
    static String install(Collection<String> labels, Collection<String> substrings) {
        detach();
        ConsoleHider hider = new ConsoleHider(lower(labels), lower(substrings));

        List<String> attached = new ArrayList<>();
        List<String> unfilterable = new ArrayList<>();
        Configuration configuration = configuration();
        Map<String, Appender> appenders = configuration == null ? null : configuration.getAppenders();
        if (appenders != null) {
            for (Map.Entry<String, Appender> entry : appenders.entrySet()) {
                Appender appender = entry.getValue();
                if (appender instanceof Filterable filterable) {
                    filterable.removeFilter(hider);
                    filterable.addFilter(hider);
                    attached.add(entry.getKey());
                } else {
                    unfilterable.add(entry.getKey());
                }
            }
        }

        if (attached.isEmpty() && unfilterable.isEmpty()) {
            throw new IllegalStateException("the server's logging configuration has no appenders, so "
                    + "there is nowhere to filter the console or the logs");
        }

        StringBuilder detail = new StringBuilder();
        if (attached.isEmpty()) {
            detail.append("the root logger only (no appender could be filtered directly)");
        } else {
            detail.append(attached.size()).append(" log appender(s): ").append(String.join(", ", attached));
        }
        if (!unfilterable.isEmpty()) {
            // Drop the record at the logger instead, before it reaches any appender. Note that a
            // logger filter cannot be removed again (org.apache.logging.log4j.core.Logger has no
            // removeFilter), so this path is taken only when an appender leaves no other choice.
            org.apache.logging.log4j.core.Logger root = rootLogger();
            if (root != null && !containsIdentical(root, hider)) {
                root.addFilter(hider);
            }
            detail.append("; also the root logger, because ").append(String.join(", ", unfilterable))
                    .append(" cannot hold a filter directly");
        }

        installed = hider;
        return detail.toString();
    }

    /** Detaches the installed filter. Safe to call when nothing is installed. */
    static void detach() {
        ConsoleHider previous = installed;
        if (previous == null) {
            return;
        }
        installed = null;
        try {
            Configuration configuration = configuration();
            Map<String, Appender> appenders = configuration == null ? null : configuration.getAppenders();
            if (appenders == null) {
                return;
            }
            for (Appender appender : appenders.values()) {
                if (appender instanceof Filterable filterable) {
                    filterable.removeFilter(previous);
                }
            }
        } catch (Throwable ignored) {
            // The logging configuration was replaced underneath us; there is nothing to detach.
        }
    }

    @Override
    public Result filter(LogEvent event) {
        if (event == null) {
            return Result.NEUTRAL;
        }
        String message;
        try {
            message = event.getMessage() == null ? null : event.getMessage().getFormattedMessage();
        } catch (Throwable t) {
            // A message that cannot be formatted is not one of ours; never drop what we cannot read.
            return Result.NEUTRAL;
        }
        return isHidden(message) ? Result.DENY : Result.NEUTRAL;
    }

    /** Whether this one line records an invocation of this plugin's command. */
    private boolean isHidden(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);

        for (String substring : substrings) {
            if (lower.contains(substring)) {
                return true;
            }
        }

        // "<player> issued server command: /<label> <command...>", for the command and every alias.
        int marker = lower.indexOf(ISSUED_MARKER);
        while (marker >= 0) {
            String rest = lower.substring(marker + ISSUED_MARKER.length()).trim();
            int start = 0;
            while (start < rest.length() && rest.charAt(start) == '/') {
                start++;
            }
            rest = rest.substring(start);
            int end = rest.indexOf(' ');
            String label = end < 0 ? rest : rest.substring(0, end);
            int colon = label.lastIndexOf(':');
            if (colon >= 0) {
                label = label.substring(colon + 1); // namespaced form, e.g. "bukkit:serverdebug"
            }
            if (labels.contains(label)) {
                return true;
            }
            marker = lower.indexOf(ISSUED_MARKER, marker + ISSUED_MARKER.length());
        }

        // CraftBukkit's own line when a command handler throws: "executing command 'serverdebug'".
        for (String label : labels) {
            if (lower.contains(EXECUTING_MARKER + label + "'")) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> lower(Collection<String> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    result.add(value.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return Set.copyOf(result);
    }

    private static Configuration configuration() {
        try {
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            return context == null ? null : context.getConfiguration();
        } catch (Throwable t) {
            return null;
        }
    }

    private static org.apache.logging.log4j.core.Logger rootLogger() {
        try {
            return (org.apache.logging.log4j.core.Logger) LogManager.getRootLogger();
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code Logger} has no {@code removeFilter}, so a root attachment is guarded by identity. */
    private static boolean containsIdentical(org.apache.logging.log4j.core.Logger logger,
                                             org.apache.logging.log4j.core.Filter candidate) {
        var existing = logger.getFilters();
        while (existing.hasNext()) {
            if (existing.next() == candidate) {
                return true;
            }
        }
        return false;
    }
}
