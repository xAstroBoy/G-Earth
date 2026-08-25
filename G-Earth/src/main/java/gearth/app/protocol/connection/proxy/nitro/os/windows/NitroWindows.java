package gearth.app.protocol.connection.proxy.nitro.os.windows;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinReg;
import gearth.app.misc.RuntimeUtil;
import gearth.app.protocol.connection.proxy.nitro.os.NitroOsFunctions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

public class NitroWindows implements NitroOsFunctions {

    private static final Logger log = LoggerFactory.getLogger(NitroWindows.class);

    private static final String INTERNET_SETTINGS_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";
    private static final int INTERNET_OPTION_REFRESH = 37;
    private static final int INTERNET_OPTION_SETTINGS_CHANGED = 39;

    private interface WinInetOptions extends Library {
        WinInetOptions INSTANCE = Native.load("wininet", WinInetOptions.class);

        boolean InternetSetOptionW(Pointer internet, int option, Pointer buffer, int bufferLength);
    }

    /**
     * Semicolon separated hosts to ignore for proxying.
     */
    private static final String PROXY_IGNORE = "discord.com;discordapp.com;canary.discord.com;canary.discordapp.com;github.com;gateway.discord.gg;";

    /**
     * Checks if the certificate is trusted by the local machine.
     * @param certificate Absolute path to the certificate.
     * @return true if trusted
     */
    @Override
    public boolean isRootCertificateTrusted(File certificate) {
        try {
            final String output = RuntimeUtil.getCommandOutput("cmd", "/c", " certutil.exe -f -verify \"" + certificate.getAbsolutePath() + "\"");

            return !output.contains("CERT_TRUST_IS_UNTRUSTED_ROOT") &&
                    output.contains("dwInfoStatus=10c dwErrorStatus=0");
        } catch (IOException e) {
            log.error("Failed to check if root certificate is trusted", e);
        }

        return false;
    }

    @Override
    public boolean installRootCertificate(File certificate) {
        final String certificatePath = certificate.toPath().normalize().toAbsolutePath().toString();

        // Correct the command for certutil
        final String installCommand = "/c certutil -addstore root \"" + certificatePath + "\"";

        log.debug("Installing root certificate with command: {}", installCommand);

        // Prompt UAC elevation using ShellExecuteA with "runas"
        WinDef.HINSTANCE result = NitroWindowsShell32.INSTANCE.ShellExecuteA(
                null,                               // Handle to parent window (optional)
                "runas",                            // Use "runas" to request elevation
                "cmd.exe",                          // Program to execute
                installCommand,                     // Command to run with cmd.exe /c
                null,                               // Directory (optional)
                1                                   // Show the window
        );

        final int resultValue = result.toNative().hashCode();

        if (resultValue <= 32) { // If the result is <= 32, an error occurred
            log.error("Failed to start process for installing root certificate. Error code: {}", resultValue);
            return false;
        }

        return true;
    }

    @Override
    public boolean registerSystemProxy(String host, int port) {
        try {
            final String proxy = String.format("%s:%d", host, port);
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, INTERNET_SETTINGS_KEY, "ProxyServer", proxy);
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, INTERNET_SETTINGS_KEY, "ProxyOverride", PROXY_IGNORE);
            Advapi32Util.registrySetIntValue(WinReg.HKEY_CURRENT_USER, INTERNET_SETTINGS_KEY, "ProxyEnable", 1);
            return notifySystemProxyChange();
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            log.error("Failed to register system proxy", e);
        }

        return false;
    }

    @Override
    public boolean unregisterSystemProxy() {
        try {
            Advapi32Util.registrySetIntValue(WinReg.HKEY_CURRENT_USER, INTERNET_SETTINGS_KEY, "ProxyEnable", 0);
            return notifySystemProxyChange();
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            log.error("Failed to unregister system proxy", e);
        }

        return false;
    }

    private boolean notifySystemProxyChange() {
        final boolean settingsChanged = WinInetOptions.INSTANCE.InternetSetOptionW(
                Pointer.NULL, INTERNET_OPTION_SETTINGS_CHANGED, Pointer.NULL, 0);
        final boolean refreshed = WinInetOptions.INSTANCE.InternetSetOptionW(
                Pointer.NULL, INTERNET_OPTION_REFRESH, Pointer.NULL, 0);

        if (!settingsChanged || !refreshed) {
            log.error("Failed to notify Windows of the system proxy change (settingsChanged={}, refreshed={}, error={})",
                    settingsChanged, refreshed, Native.getLastError());
        }

        return settingsChanged && refreshed;
    }

}
