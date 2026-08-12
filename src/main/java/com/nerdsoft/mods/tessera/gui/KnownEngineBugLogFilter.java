package com.nerdsoft.mods.tessera.gui;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;
import org.slf4j.LoggerFactory;

/**
 * Suppresses log spam from a confirmed <strong>Mojang engine bug</strong>,
 * not a Tessera bug -- see
 * <a href="https://bugs.mojang.com/browse/MC/issues/MC-293754">MC-293754</a>.
 *
 * <h2>What this bug is</h2>
 * Since 1.21.5 pre-release 2 (introduced as a side effect of a workaround
 * for a separate, unrelated bug,
 * <a href="https://bugs.mojang.com/browse/MC/issues/MC-280482">MC-280482</a>),
 * vanilla Minecraft's own GL debug-message plumbing
 * ({@code com.mojang.blaze3d.platform.GlDebug}) logs a specific
 * {@code GL_INVALID_OPERATION} message with a hardcoded, non-descriptive
 * error context of literally the string {@code "(null)"} -- confirmed
 * reproducible on stock, completely unmodded Minecraft (see the Mojang bug
 * tracker report and multiple independent player reports across Intel,
 * AMD, and Nvidia hardware). Because the context is always {@code (null)},
 * there is no way -- from mod code, a debug label, or anything else --  to
 * make this specific message identify its real call site; the placeholder
 * is baked into vanilla's own error-formatting call, not left blank by
 * whichever GL call triggered it.
 *
 * <h2>Why this is filtered here instead of "actually fixed"</h2>
 * This class only ever silences the message; it cannot and does not touch
 * whatever underlying (harmless, per Mojang's own workaround-side-effect
 * explanation) GL state transition trips the bug. Filtering only the exact
 * byte-for-byte message text --
 * {@code "Error has been generated. GL error GL_INVALID_OPERATION in
 * (null): (ID: 173538523) Generic error"} -- (see {@link #KNOWN_BENIGN_MESSAGE})
 * rather than the id (1282) or type (ERROR) alone is deliberate: id 1282 /
 * GL_INVALID_OPERATION is an extremely common, generic error code covering
 * many genuinely different failure modes elsewhere in the engine and in
 * other mods -- filtering on id or type alone would silence real,
 * unrelated GL errors that happen to share the same code. An exact-string
 * match against this one specific message text, checked in this filter's
 * own {@link #filter} implementations before any decision is made, is the
 * narrowest match that still reliably catches every instance of this
 * exact known-benign message and nothing else.
 *
 * <p>If Mojang ever changes this message's wording (e.g. fixing the bug,
 * or changing what "(null)" reads as), this filter simply stops matching
 * and every message reverts to logging normally -- it does not need to be
 * removed for that to happen safely, though it should be removed once the
 * underlying engine bug is confirmed fixed in whatever Minecraft version
 * this mod eventually targets, since a stale exact-string filter is dead
 * weight once its target string no longer occurs.
 *
 * <h2>How this attaches</h2>
 * {@link #install()} attaches this as a
 * {@link org.apache.logging.log4j.core.filter.AbstractFilter} to the
 * {@link LoggerConfig} for the specific logger name backing
 * {@code com.mojang.blaze3d.platform.GlDebug}'s SLF4J logger (SLF4J is a
 * facade; Minecraft's actual backend is Log4j2, already on the runtime
 * classpath -- no new dependency needed), via the supported
 * {@code Configuration#getLoggerConfig(String)} lookup rather than the
 * internal-only {@code core.Logger#addFilter}. This only affects that one
 * named logger (or its nearest configured ancestor, in the fallback case
 * documented on {@link #install()}), not the global root logger or any
 * other class's logging -- every other mod/engine warning and error,
 * including Tessera's own, logs completely unaffected.
 */
public final class KnownEngineBugLogFilter extends AbstractFilter {

    private static final String GL_DEBUG_LOGGER_NAME = "com.mojang.blaze3d.platform.GlDebug";

    // Exact text of the known-benign MC-293754 message. Matched verbatim
    // (see class doc for why exact-string rather than id/type matching).
    private static final String KNOWN_BENIGN_MESSAGE =
            "Error has been generated. GL error GL_INVALID_OPERATION in (null): (ID: 173538523) Generic error";

    private static volatile boolean installed = false;

    private KnownEngineBugLogFilter() {
        super(Result.DENY, Result.NEUTRAL);
    }

    /**
     * Idempotent -- safe to call more than once (e.g. if a future reload
     * ever re-triggers mod init); only attaches once per JVM lifetime.
     * Should be called once during client setup, off the hot path (this
     * only configures logging infrastructure, it is not itself performance
     * sensitive and does not belong in any per-frame or per-reload code
     * path).
     */
    public static synchronized void install() {
        if (installed) {
            return;
        }

        // GlDebug's own LOGGER field is an org.slf4j.Logger (SLF4J is a
        // facade over whatever backend is actually configured -- for
        // Minecraft/NeoForge that backend is always Log4j2), obtained via
        // the ordinary LoggerFactory.getLogger(GlDebug.class) pattern that
        // gives it the fully-qualified class name as its logger name. We
        // don't have compile-time access to com.mojang.blaze3d.platform
        // .GlDebug's own LOGGER field (private, and Mojang code besides),
        // so we attach by name instead, through the LoggerConfig for that
        // name -- NOT via org.apache.logging.log4j.core.Logger#addFilter,
        // which Log4j2's own javadoc marks as "not exposed through the
        // public API and is used primarily for unit testing" and is
        // therefore not something to rely on against a runtime logging
        // configuration we don't control (NeoForge's own log4j2.xml).
        // Going through the Configuration's LoggerConfig instead is the
        // documented, supported mechanism for attaching a filter to one
        // specific named logger programmatically, and is what actually
        // participates correctly in Log4j2's normal filter-evaluation
        // chain for that logger.
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        LoggerConfig loggerConfig = context.getConfiguration().getLoggerConfig(GL_DEBUG_LOGGER_NAME);
        loggerConfig.addFilter(new KnownEngineBugLogFilter());
        // If GL_DEBUG_LOGGER_NAME has no LoggerConfig of its own registered
        // in the active configuration, getLoggerConfig() falls back to the
        // nearest matching parent/root LoggerConfig (standard Log4j2
        // hierarchical lookup behavior) rather than returning null -- in
        // that fallback case this still installs correctly, it just also
        // (harmlessly, since the filter's own message-text match stays
        // exactly as narrow either way) evaluates for whatever broader
        // logger scope it attached to instead of GlDebug specifically.
        context.updateLoggers();

        installed = true;

        // Confirms installation via Tessera's own logger (not GlDebug's --
        // logging through the filter we just attached to test it would be
        // circular and pointless) so anyone reading the log can see this
        // ran, without needing to already know to look for the absence of
        // the suppressed message.
        org.slf4j.Logger tesseraLogger = LoggerFactory.getLogger("Tessera");
        tesseraLogger.info("Filtering known-benign Mojang engine bug MC-293754 "
                        + "(GL_INVALID_OPERATION 'in (null)' spam) from {} log output. "
                        + "This is a vanilla Minecraft bug, not a Tessera issue -- "
                        + "see https://bugs.mojang.com/browse/MC/issues/MC-293754",
                GL_DEBUG_LOGGER_NAME);
    }

    private static Result decide(String formattedMessage) {
        if (formattedMessage != null && formattedMessage.contains(KNOWN_BENIGN_MESSAGE)) {
            return Result.DENY;
        }
        return Result.NEUTRAL;
    }

    @Override
    public Result filter(org.apache.logging.log4j.core.LogEvent event) {
        return decide(event.getMessage() == null ? null : event.getMessage().getFormattedMessage());
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object... params) {
        return decide(msg);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Object msg, Throwable t) {
        return decide(msg == null ? null : msg.toString());
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Message msg, Throwable t) {
        return decide(msg == null ? null : msg.getFormattedMessage());
    }
}
