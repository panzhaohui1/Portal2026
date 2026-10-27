package moe.fuqiuluo.locationprobe;

import android.content.Context;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.Process;

import java.lang.reflect.Method;

/**
 * Read-only Portal2026v2 verification probe for Android 13 app_process.
 *
 * The process must be launched by adb shell.  The program deliberately has no
 * Android manifest and does not request, change, or persist device settings.
 */
public final class LocationProbe {
    private static final String PORTAL_PROVIDER = "portal2026v2";
    private static final String SHELL_PACKAGE = "com.android.shell";
    private static final int SHELL_UID = 2000;
    private static final int EXIT_OK = 0;
    private static final int EXIT_MISMATCH = 10;
    private static final int EXIT_UNAVAILABLE = 11;
    private static final int EXIT_USAGE = 64;

    private static String initializationStage = "not_started";
    private static String initializationError = "none";

    private LocationProbe() {
    }

    public static void main(String[] args) {
        if (args == null || args.length == 0) {
            printUsage();
            System.exit(EXIT_USAGE);
            return;
        }

        if (args.length == 1 && "--help".equals(args[0])) {
            printUsage();
            return;
        }

        if (args.length == 1 && "--ipc-status".equals(args[0])) {
            System.exit(runIpcStatus(null));
            return;
        }

        if (args.length == 1 && "--fused-status".equals(args[0])) {
            System.exit(runFusedStatus());
            return;
        }

        if (args.length == 3 && "--ipc-verify".equals(args[0])) {
            ExpectedLocation expected = parseExpectedLocation(args[1], args[2]);
            if (expected == null) {
                printStatus("UNAVAILABLE");
                System.exit(EXIT_UNAVAILABLE);
                return;
            }
            System.exit(runIpcStatus(expected));
            return;
        }

        if (args.length == 3 && "--stop-verified".equals(args[0])) {
            ExpectedLocation expected = parseExpectedLocation(args[1], args[2]);
            if (expected == null) {
                printStatus("UNAVAILABLE");
                System.exit(EXIT_UNAVAILABLE);
                return;
            }
            System.exit(runVerifiedStop(expected));
            return;
        }

        printUsage();
        System.exit(EXIT_USAGE);
    }

    private static int runIpcStatus(ExpectedLocation expected) {
        LocationManager locationManager = prepareLocationManager();
        if (locationManager == null) {
            System.err.println("CONTEXT_UNAVAILABLE stage=" + initializationStage + " type=" + initializationError);
            printStatus("UNAVAILABLE");
            return EXIT_UNAVAILABLE;
        }

        IpcCheck check = queryPortalIpc(locationManager);
        if (!check.transportAvailable) {
            printStatus("UNAVAILABLE");
            return EXIT_UNAVAILABLE;
        }
        if (!check.stateReady || (expected != null && !check.matches(expected))) {
            printStatus("IPC_NOT_READY");
            return EXIT_MISMATCH;
        }

        printStatus("IPC_READY");
        return EXIT_OK;
    }

    private static int runFusedStatus() {
        LocationManager manager = prepareLocationManager();
        if (manager == null) {
            printStatus("UNAVAILABLE");
            return EXIT_UNAVAILABLE;
        }
        try {
            Bundle exchange = new Bundle();
            if (!manager.sendExtraCommand(PORTAL_PROVIDER, "exchange_key", exchange)) {
                printStatus("UNAVAILABLE");
                return EXIT_UNAVAILABLE;
            }
            String key = exchange.getString("key");
            CommandReply reply = key == null ? null : sendAuthenticated(manager, key, "sync_config");
            if (reply == null || !reply.accepted || !reply.extras.containsKey("disable_fused_location")) {
                printStatus("UNAVAILABLE");
                return EXIT_UNAVAILABLE;
            }
            printStatus(reply.extras.getBoolean("disable_fused_location") ? "FUSED_BLOCKED" : "FUSED_ALLOWED");
            return EXIT_OK;
        } catch (Throwable ignored) {
            printStatus("UNAVAILABLE");
            return EXIT_UNAVAILABLE;
        }
    }

    private static int runVerifiedStop(ExpectedLocation expected) {
        LocationManager manager = prepareLocationManager();
        if (manager == null) {
            printStatus("UNAVAILABLE");
            return EXIT_UNAVAILABLE;
        }
        IpcCheck check = queryPortalIpc(manager);
        if (!check.transportAvailable || !check.stateReady || !check.matches(expected)) {
            printStatus("STOP_NOT_ATTEMPTED");
            return EXIT_MISMATCH;
        }
        try {
            Bundle exchange = new Bundle();
            if (!manager.sendExtraCommand(PORTAL_PROVIDER, "exchange_key", exchange)) {
                printStatus("UNAVAILABLE");
                return EXIT_UNAVAILABLE;
            }
            String key = exchange.getString("key");
            CommandReply stop = key == null ? null : sendAuthenticated(manager, key, "stop");
            CommandReply state = key == null ? null : sendAuthenticated(manager, key, "is_start");
            if (stop == null || !stop.accepted || state == null || !state.accepted ||
                    !state.extras.containsKey("is_start") || state.extras.getBoolean("is_start")) {
                printStatus("STOP_FAILED");
                return EXIT_UNAVAILABLE;
            }
            printStatus("STOPPED");
            return EXIT_OK;
        } catch (Throwable ignored) {
            printStatus("UNAVAILABLE");
            return EXIT_UNAVAILABLE;
        }
    }

    /**
     * Creates a system context through ActivityThread, then derives the
     * com.android.shell package context used for the public LocationManager.
     */
    private static LocationManager prepareLocationManager() {
        if (Process.myUid() != SHELL_UID) {
            return null;
        }

        try {
            initializationStage = "main_looper";
            if (Looper.getMainLooper() == null) {
                Looper.prepareMainLooper();
            }
            initializationStage = "activity_thread";
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = invokeStaticNoArg(activityThreadClass, "currentActivityThread");
            if (activityThread == null) {
                activityThread = invokeStaticNoArg(activityThreadClass, "systemMain");
            }
            if (activityThread == null) {
                return null;
            }

            initializationStage = "system_context";
            Object contextObject = invokeNoArg(activityThreadClass, "getSystemContext", activityThread);
            if (!(contextObject instanceof Context)) {
                return null;
            }

            Context systemContext = (Context) contextObject;
            initializationStage = "shell_context";
            Context shellContext = systemContext.createPackageContext(SHELL_PACKAGE, 0);
            if (!SHELL_PACKAGE.equals(shellContext.getPackageName())) {
                return null;
            }

            initializationStage = "location_service";
            Object service = shellContext.getSystemService(Context.LOCATION_SERVICE);
            if (service instanceof LocationManager) {
                return (LocationManager) service;
            }
        } catch (Throwable error) {
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            initializationError = cause.getClass().getSimpleName();
        }
        return null;
    }

    private static Object invokeStaticNoArg(Class<?> type, String name) throws Exception {
        return invokeNoArg(type, name, null);
    }

    private static Object invokeNoArg(Class<?> type, String name, Object receiver) throws Exception {
        Method method;
        try {
            method = type.getMethod(name);
        } catch (NoSuchMethodException ignored) {
            method = type.getDeclaredMethod(name);
            method.setAccessible(true);
        }
        return method.invoke(receiver);
    }

    /**
     * Performs the complete Portal command handshake and all three readbacks.
     * The returned values remain in memory only long enough for comparison.
     */
    private static IpcCheck queryPortalIpc(LocationManager locationManager) {
        try {
            return queryPortalIpcInternal(locationManager);
        } catch (Throwable ignored) {
            return IpcCheck.unavailable();
        }
    }

    private static IpcCheck queryPortalIpcInternal(LocationManager locationManager) {
        Bundle exchange = new Bundle();
        final boolean exchangeAccepted;
        try {
            exchangeAccepted = locationManager.sendExtraCommand(
                    PORTAL_PROVIDER, "exchange_key", exchange
            );
        } catch (Throwable ignored) {
            return IpcCheck.unavailable();
        }

        String key = exchange.getString("key");
        if (!exchangeAccepted || key == null || key.length() == 0) {
            return IpcCheck.unavailable();
        }

        CommandReply startReply = sendAuthenticated(locationManager, key, "is_start");
        CommandReply locationReply = sendAuthenticated(locationManager, key, "get_location");
        CommandReply configReply = sendAuthenticated(locationManager, key, "get_fixed_config");
        if (startReply == null || locationReply == null || configReply == null
                || !startReply.accepted || !locationReply.accepted || !configReply.accepted) {
            return IpcCheck.unavailable();
        }

        Bundle start = startReply.extras;
        Bundle location = locationReply.extras;
        Bundle config = configReply.extras;

        boolean hasStart = start.containsKey("is_start");
        boolean enabled = hasStart && start.getBoolean("is_start");

        boolean hasLocation = location.containsKey("lat") && location.containsKey("lon");
        double locationLatitude = hasLocation ? location.getDouble("lat") : Double.NaN;
        double locationLongitude = hasLocation ? location.getDouble("lon") : Double.NaN;

        boolean configValid = config.containsKey("fixed_config_valid")
                && config.getBoolean("fixed_config_valid");
        boolean fixedMode = config.containsKey("fixed_location_mode")
                && config.getBoolean("fixed_location_mode");
        boolean hasConfig = config.containsKey("latitude")
                && config.containsKey("longitude")
                && config.containsKey("altitude")
                && config.containsKey("accuracy");
        double configLatitude = hasConfig ? config.getDouble("latitude") : Double.NaN;
        double configLongitude = hasConfig ? config.getDouble("longitude") : Double.NaN;
        double configAltitude = hasConfig ? config.getDouble("altitude") : Double.NaN;
        float configAccuracy = hasConfig ? config.getFloat("accuracy") : Float.NaN;

        boolean payloadValid = hasStart
                && hasLocation
                && configValid
                && fixedMode
                && hasConfig
                && validLatitude(locationLatitude)
                && validLongitude(locationLongitude)
                && validLatitude(configLatitude)
                && validLongitude(configLongitude)
                && finite(configAltitude)
                && finite(configAccuracy)
                && configAccuracy >= 0.0f;

        boolean stateReady = enabled
                && payloadValid
                && same(locationLatitude, configLatitude)
                && same(locationLongitude, configLongitude);

        return new IpcCheck(
                true,
                stateReady,
                locationLatitude,
                locationLongitude,
                configLatitude,
                configLongitude
        );
    }

    private static CommandReply sendAuthenticated(
            LocationManager locationManager,
            String key,
            String commandId
    ) {
        Bundle extras = new Bundle();
        extras.putString("command_id", commandId);
        try {
            boolean accepted = locationManager.sendExtraCommand(
                    PORTAL_PROVIDER, key, extras
            );
            return new CommandReply(accepted, extras);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ExpectedLocation parseExpectedLocation(String latitudeText, String longitudeText) {
        try {
            double latitude = Double.parseDouble(latitudeText);
            double longitude = Double.parseDouble(longitudeText);
            if (!validLatitude(latitude) || !validLongitude(longitude)) {
                return null;
            }
            // Portal's fixed-location policy canonicalizes both signed zero values.
            return new ExpectedLocation(latitude == 0.0 ? 0.0 : latitude,
                    longitude == 0.0 ? 0.0 : longitude);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean validLatitude(double value) {
        return finite(value) && value >= -90.0 && value <= 90.0;
    }

    private static boolean validLongitude(double value) {
        return finite(value) && value >= -180.0 && value <= 180.0;
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static boolean same(double left, double right) {
        return left == right;
    }

    private static void printStatus(String status) {
        System.out.println(status);
    }

    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  --ipc-status");
        System.err.println("  --fused-status");
        System.err.println("  --ipc-verify <latitude> <longitude>");
        System.err.println("  --stop-verified <latitude> <longitude>");
        System.err.println("Output: IPC_READY | IPC_NOT_READY | UNAVAILABLE");
    }

    private static final class CommandReply {
        private final boolean accepted;
        private final Bundle extras;

        private CommandReply(boolean accepted, Bundle extras) {
            this.accepted = accepted;
            this.extras = extras;
        }
    }

    private static final class IpcCheck {
        private final boolean transportAvailable;
        private final boolean stateReady;
        private final double locationLatitude;
        private final double locationLongitude;
        private final double configLatitude;
        private final double configLongitude;

        private IpcCheck(
                boolean transportAvailable,
                boolean stateReady,
                double locationLatitude,
                double locationLongitude,
                double configLatitude,
                double configLongitude
        ) {
            this.transportAvailable = transportAvailable;
            this.stateReady = stateReady;
            this.locationLatitude = locationLatitude;
            this.locationLongitude = locationLongitude;
            this.configLatitude = configLatitude;
            this.configLongitude = configLongitude;
        }

        private static IpcCheck unavailable() {
            return new IpcCheck(false, false,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }

        private boolean matches(ExpectedLocation expected) {
            return stateReady
                    && same(locationLatitude, expected.latitude)
                    && same(locationLongitude, expected.longitude)
                    && same(configLatitude, expected.latitude)
                    && same(configLongitude, expected.longitude);
        }
    }

    private static final class ExpectedLocation {
        private final double latitude;
        private final double longitude;

        private ExpectedLocation(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }
}
