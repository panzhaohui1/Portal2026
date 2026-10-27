package moe.fuqiuluo.locationprobe;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.WindowManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Explicit-command-only probe. The host performs the authenticated provider
 * preflight; this ordinary app never attempts that IPC itself.
 * The default Activity path does not obtain a LocationManager or read a
 * location. No Location is retained after a probe.
 */
public final class ProbeActivity extends Activity {
    private static final String TAG = "LocationProbe";
    private static final String ACTION_CONFIRMED_TEST =
            "moe.fuqiuluo.locationprobe.action.RUN_CONFIRMED_TEST";
    private static final String EXTRA_COMMAND = "probe_command";
    private static final String EXTRA_EXPECTED = "probe_expected";
    private static final String EXTRA_CONFIRMATION = "probe_confirmation";
    private static final String TEST_COMMAND = "TEST 1,2";
    private static final String DENIED_COMMAND = "TEST_DENIED";
    private static final String UPDATES_COMMAND = "TEST_UPDATES";
    private static final String DENIED_LISTENER_COMMAND = "TEST_DENIED_LISTENER";
    private static final String FUSED_CURRENT_COMMAND = "TEST_FUSED_CURRENT";
    private static final String DENIED_FUSED_COMMAND = "TEST_DENIED_FUSED";
    private static final String STOP_MONITOR_COMMAND = "TEST_STOP_MONITOR";
    private static final String ACTION_STOP_SIMULATION_SUCCESS =
            "moe.fuqiuluo.locationprobe.action.STOP_SIMULATION_SUCCESS";
    private static final String EXPECTED_TEST_POINT = "1,2";
    private static final String CONFIRMATION_IPC_READY_TEST_POINT = "IPC_READY_1,2";
    private static final long CURRENT_TIMEOUT_MILLIS = 20000L;
    private static final long UPDATES_TIMEOUT_MILLIS = 8000L;
    private static final long DENIED_LISTENER_WAIT_MILLIS = 5000L;
    private static final long STOP_MONITOR_READY_TIMEOUT_MILLIS = 20000L;
    private static final long STOP_MONITOR_MARKER_TIMEOUT_MILLIS = 60000L;
    private static final long STOP_MONITOR_AFTER_MARKER_MILLIS = 6000L;
    private static final String FUSED_PROVIDER = "fused";

    private enum Result {
        MATCH,
        MISMATCH,
        UNAVAILABLE
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }

        if (!ACTION_CONFIRMED_TEST.equals(intent.getAction())
                || !EXPECTED_TEST_POINT.equals(intent.getStringExtra(EXTRA_EXPECTED))
                || !CONFIRMATION_IPC_READY_TEST_POINT.equals(
                intent.getStringExtra(EXTRA_CONFIRMATION))) {
            finish();
            return;
        }

        String command = intent.getStringExtra(EXTRA_COMMAND);
        if (TEST_COMMAND.equals(command)) {
            runInWorker(this::runFixedLocationProbe);
        } else if (DENIED_COMMAND.equals(command)) {
            runInWorker(this::runDeniedProbe);
        } else if (UPDATES_COMMAND.equals(command)) {
            runInWorker(this::runUpdatesProbe);
        } else if (DENIED_LISTENER_COMMAND.equals(command)) {
            runInWorker(this::runDeniedListenerProbe);
        } else if (FUSED_CURRENT_COMMAND.equals(command)) {
            runInWorker(this::runFusedCurrentProbe);
        } else if (DENIED_FUSED_COMMAND.equals(command)) {
            runInWorker(this::runDeniedFusedProbe);
        } else if (STOP_MONITOR_COMMAND.equals(command)) {
            runInWorker(this::runStopMonitorProbe);
        } else {
            finish();
        }
    }

    private void runInWorker(Runnable probe) {
        new Thread(() -> {
            try {
                probe.run();
            } finally {
                runOnUiThread(this::finish);
            }
        }, "location-probe").start();
    }

    @SuppressLint("MissingPermission")
    private void runFixedLocationProbe() {
        LocationManager locationManager = getSystemService(LocationManager.class);
        if (locationManager == null) {
            logOverall(Result.UNAVAILABLE);
            return;
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            logProviderStatus("GPS", "last", Result.UNAVAILABLE);
            logProviderStatus("GPS", "current", Result.UNAVAILABLE);
            logProviderStatus("network", "last", Result.UNAVAILABLE);
            logProviderStatus("network", "current", Result.UNAVAILABLE);
            logOverall(Result.UNAVAILABLE);
            return;
        }

        ExecutorService callbackExecutor = Executors.newSingleThreadExecutor();
        try {
            Result gpsLast = readLastKnown(locationManager, LocationManager.GPS_PROVIDER);
            Result gpsCurrent = readCurrent(locationManager, LocationManager.GPS_PROVIDER, callbackExecutor);
            Result networkLast = readLastKnown(locationManager, LocationManager.NETWORK_PROVIDER);
            Result networkCurrent = readCurrent(locationManager, LocationManager.NETWORK_PROVIDER, callbackExecutor);

            logProviderStatus("GPS", "last", gpsLast);
            logProviderStatus("GPS", "current", gpsCurrent);
            logProviderStatus("network", "last", networkLast);
            logProviderStatus("network", "current", networkCurrent);
            logOverall(aggregate(gpsLast, gpsCurrent, networkLast, networkCurrent));
        } finally {
            callbackExecutor.shutdownNow();
        }
    }

    @SuppressLint("MissingPermission")
    private void runDeniedProbe() {
        LocationManager locationManager = getSystemService(LocationManager.class);
        if (locationManager == null) {
            logOverall(Result.UNAVAILABLE);
            return;
        }

        try {
            Location result = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Log.i(TAG, result == null ? "RESULT UNAVAILABLE" : "RESULT NOT_DENIED");
        } catch (SecurityException denied) {
            Log.i(TAG, "RESULT DENIED");
        } catch (RuntimeException unavailable) {
            Log.i(TAG, "RESULT UNAVAILABLE");
        }
    }

    @SuppressLint("MissingPermission")
    private void runUpdatesProbe() {
        LocationManager locationManager = getSystemService(LocationManager.class);
        if (locationManager == null) {
            logOverall(Result.UNAVAILABLE);
            return;
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            logProviderStatus("GPS", "last", Result.UNAVAILABLE);
            logProviderStatus("GPS", "updates", Result.UNAVAILABLE);
            logProviderStatus("network", "last", Result.UNAVAILABLE);
            logProviderStatus("network", "updates", Result.UNAVAILABLE);
            logProviderStatus(FUSED_PROVIDER, "last", Result.UNAVAILABLE);
            logProviderStatus(FUSED_PROVIDER, "updates", Result.UNAVAILABLE);
            logOverall(Result.UNAVAILABLE);
            return;
        }

        HandlerThread callbackThread = new HandlerThread("location-probe-updates");
        callbackThread.start();
        try {
            Result gpsLast = readLastKnown(locationManager, LocationManager.GPS_PROVIDER);
            Result gpsUpdates = readUpdates(locationManager, LocationManager.GPS_PROVIDER, callbackThread);
            Result networkLast = readLastKnown(locationManager, LocationManager.NETWORK_PROVIDER);
            Result networkUpdates = readUpdates(locationManager, LocationManager.NETWORK_PROVIDER, callbackThread);
            Result fusedLast = readLastKnown(locationManager, FUSED_PROVIDER);
            Result fusedUpdates = readUpdates(locationManager, FUSED_PROVIDER, callbackThread);

            logProviderStatus("GPS", "last", gpsLast);
            logProviderStatus("GPS", "updates", gpsUpdates);
            logProviderStatus("network", "last", networkLast);
            logProviderStatus("network", "updates", networkUpdates);
            logProviderStatus(FUSED_PROVIDER, "last", fusedLast);
            logProviderStatus(FUSED_PROVIDER, "updates", fusedUpdates);
            logOverall(aggregate(
                    gpsLast,
                    gpsUpdates,
                    networkLast,
                    networkUpdates,
                    fusedLast,
                    fusedUpdates
            ));
        } finally {
            closeCallbackThread(callbackThread);
        }
    }

    @SuppressLint("MissingPermission")
    private void runDeniedListenerProbe() {
        LocationManager locationManager = getSystemService(LocationManager.class);
        if (locationManager == null) {
            Log.i(TAG, "RESULT UNAVAILABLE");
            return;
        }

        HandlerThread callbackThread = new HandlerThread("location-probe-denied-listener");
        callbackThread.start();
        CountDownLatch callbackLatch = new CountDownLatch(1);
        AtomicReference<Boolean> callbackObserved = new AtomicReference<>(false);
        LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                callbackObserved.set(true);
                callbackLatch.countDown();
            }
        };

        boolean securityDenied = false;
        boolean requestCompleted = false;
        try {
            try {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        0L,
                        0.0f,
                        listener,
                        callbackThread.getLooper()
                );
                requestCompleted = true;
            } catch (SecurityException denied) {
                securityDenied = true;
            } catch (RuntimeException unavailable) {
                Log.i(TAG, "RESULT UNAVAILABLE");
                return;
            }

            try {
                callbackLatch.await(DENIED_LISTENER_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                Log.i(TAG, "RESULT UNAVAILABLE");
                return;
            }

            if (securityDenied) {
                Log.i(TAG, callbackObserved.get()
                        ? "RESULT DENIED_WITH_CALLBACK"
                        : "RESULT DENIED_NO_CALLBACK");
            } else if (requestCompleted) {
                Log.i(TAG, "RESULT NOT_DENIED");
            } else {
                Log.i(TAG, "RESULT UNAVAILABLE");
            }
        } finally {
            try {
                locationManager.removeUpdates(listener);
            } catch (RuntimeException ignored) {
                // The probe has no safe location-bearing diagnostic to emit.
            }
            closeCallbackThread(callbackThread);
        }
    }

    @SuppressLint("MissingPermission")
    private void runFusedCurrentProbe() {
        LocationManager locationManager = getSystemService(LocationManager.class);
        if (locationManager == null) {
            logProviderStatus(FUSED_PROVIDER, "current", Result.UNAVAILABLE);
            return;
        }

        ExecutorService callbackExecutor = Executors.newSingleThreadExecutor();
        try {
            Result result = readCurrent(locationManager, FUSED_PROVIDER, callbackExecutor);
            logProviderStatus(FUSED_PROVIDER, "current", result);
        } finally {
            callbackExecutor.shutdownNow();
        }
    }

    @SuppressLint("MissingPermission")
    private void runDeniedFusedProbe() {
        LocationManager locationManager = getSystemService(LocationManager.class);
        if (locationManager == null) {
            Log.i(TAG, "RESULT UNAVAILABLE");
            return;
        }

        HandlerThread callbackThread = new HandlerThread("location-probe-denied-fused");
        callbackThread.start();
        CountDownLatch callbackLatch = new CountDownLatch(1);
        AtomicBoolean callbackObserved = new AtomicBoolean(false);
        LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                callbackObserved.set(true);
                callbackLatch.countDown();
            }
        };

        boolean securityDenied = false;
        boolean requestCompleted = false;
        try {
            try {
                locationManager.requestLocationUpdates(
                        FUSED_PROVIDER,
                        0L,
                        0.0f,
                        listener,
                        callbackThread.getLooper()
                );
                requestCompleted = true;
            } catch (SecurityException denied) {
                securityDenied = true;
            } catch (RuntimeException unavailable) {
                Log.i(TAG, "RESULT UNAVAILABLE");
                return;
            }

            try {
                callbackLatch.await(DENIED_LISTENER_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                Log.i(TAG, "RESULT UNAVAILABLE");
                return;
            }

            if (securityDenied) {
                Log.i(TAG, callbackObserved.get()
                        ? "RESULT FUSED_DENIED_WITH_CALLBACK"
                        : "RESULT FUSED_DENIED_NO_CALLBACK");
            } else if (requestCompleted) {
                Log.i(TAG, "RESULT FUSED_NOT_DENIED");
            } else {
                Log.i(TAG, "RESULT UNAVAILABLE");
            }
        } finally {
            try {
                locationManager.removeUpdates(listener);
            } catch (RuntimeException ignored) {
                // Do not print or retain a location-bearing diagnostic.
            }
            closeCallbackThread(callbackThread);
        }
    }

    @SuppressLint("MissingPermission")
    private void runStopMonitorProbe() {
        LocationManager locationManager = getSystemService(LocationManager.class);
        if (locationManager == null) {
            Log.i(TAG, "RESULT UNAVAILABLE");
            return;
        }

        HandlerThread callbackThread = new HandlerThread("location-probe-stop-monitor");
        callbackThread.start();
        Handler callbackHandler = new Handler(callbackThread.getLooper());
        CountDownLatch readyLatch = new CountDownLatch(1);
        CountDownLatch markerLatch = new CountDownLatch(1);
        AtomicInteger matchingCallbackCount = new AtomicInteger(0);
        AtomicBoolean ready = new AtomicBoolean(false);
        AtomicBoolean markerReceived = new AtomicBoolean(false);
        AtomicBoolean postStopMatch = new AtomicBoolean(false);
        LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (classify(location) != Result.MATCH) {
                    return;
                }
                if (!ready.get()) {
                    if (matchingCallbackCount.incrementAndGet() >= 2
                            && ready.compareAndSet(false, true)) {
                        readyLatch.countDown();
                    }
                } else if (markerReceived.get()) {
                    postStopMatch.set(true);
                }
            }
        };
        BroadcastReceiver stopReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null
                        || !ACTION_STOP_SIMULATION_SUCCESS.equals(intent.getAction())
                        || !ready.get()) {
                    return;
                }
                markerReceived.set(true);
                markerLatch.countDown();
            }
        };

        boolean receiverRegistered = false;
        boolean requestStarted = false;
        try {
            IntentFilter stopFilter = new IntentFilter(ACTION_STOP_SIMULATION_SUCCESS);
            registerReceiver(
                    stopReceiver,
                    stopFilter,
                    Manifest.permission.DUMP,
                    callbackHandler,
                    Context.RECEIVER_EXPORTED
            );
            receiverRegistered = true;

            try {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        0L,
                        0.0f,
                        listener,
                        callbackThread.getLooper()
                );
                requestStarted = true;
            } catch (RuntimeException unavailable) {
                Log.i(TAG, "RESULT UNAVAILABLE");
                return;
            }

            try {
                if (!readyLatch.await(
                        STOP_MONITOR_READY_TIMEOUT_MILLIS,
                        TimeUnit.MILLISECONDS
                )) {
                    Log.i(TAG, "RESULT UNAVAILABLE");
                    return;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                Log.i(TAG, "RESULT UNAVAILABLE");
                return;
            }

            Log.i(TAG, "RESULT STOP_MONITOR_READY");
            try {
                if (!markerLatch.await(
                        STOP_MONITOR_MARKER_TIMEOUT_MILLIS,
                        TimeUnit.MILLISECONDS
                )) {
                    Log.i(TAG, "RESULT UNAVAILABLE");
                    return;
                }
                Thread.sleep(STOP_MONITOR_AFTER_MARKER_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                Log.i(TAG, "RESULT UNAVAILABLE");
                return;
            }

            Log.i(TAG, postStopMatch.get()
                    ? "RESULT STOP_FAKE_AFTER"
                    : "RESULT STOP_NO_FAKE_AFTER");
        } catch (RuntimeException unavailable) {
            Log.i(TAG, "RESULT UNAVAILABLE");
        } finally {
            if (requestStarted) {
                try {
                    locationManager.removeUpdates(listener);
                } catch (RuntimeException ignored) {
                    // Do not print or retain a location-bearing diagnostic.
                }
            }
            if (receiverRegistered) {
                try {
                    unregisterReceiver(stopReceiver);
                } catch (RuntimeException ignored) {
                    // The receiver is best-effort cleanup for this bounded probe.
                }
            }
            callbackHandler.removeCallbacksAndMessages(null);
            closeCallbackThread(callbackThread);
        }
    }

    @SuppressLint("MissingPermission")
    private static Result readLastKnown(LocationManager locationManager, String provider) {
        try {
            Location location = locationManager.getLastKnownLocation(provider);
            return classify(location);
        } catch (RuntimeException unavailable) {
            return Result.UNAVAILABLE;
        }
    }

    @SuppressLint("MissingPermission")
    private static Result readCurrent(
            LocationManager locationManager,
            String provider,
            ExecutorService callbackExecutor
    ) {
        CountDownLatch callbackLatch = new CountDownLatch(1);
        AtomicReference<Result> callbackResult = new AtomicReference<>(Result.UNAVAILABLE);
        CancellationSignal cancellationSignal = new CancellationSignal();
        try {
            locationManager.getCurrentLocation(
                    provider,
                    cancellationSignal,
                    callbackExecutor,
                    location -> {
                        callbackResult.set(classify(location));
                        callbackLatch.countDown();
                    }
            );
            if (!callbackLatch.await(CURRENT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                cancellationSignal.cancel();
                return Result.UNAVAILABLE;
            }
            return callbackResult.get();
        } catch (RuntimeException unavailable) {
            cancellationSignal.cancel();
            return Result.UNAVAILABLE;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cancellationSignal.cancel();
            return Result.UNAVAILABLE;
        }
    }

    @SuppressLint("MissingPermission")
    private static Result readUpdates(
            LocationManager locationManager,
            String provider,
            HandlerThread callbackThread
    ) {
        CountDownLatch callbackLatch = new CountDownLatch(1);
        AtomicReference<Result> callbackResult = new AtomicReference<>(Result.UNAVAILABLE);
        LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                callbackResult.set(classify(location));
                callbackLatch.countDown();
            }
        };

        try {
            locationManager.requestLocationUpdates(
                    provider,
                    0L,
                    0.0f,
                    listener,
                    callbackThread.getLooper()
            );
            if (!callbackLatch.await(UPDATES_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                return Result.UNAVAILABLE;
            }
            return callbackResult.get();
        } catch (RuntimeException unavailable) {
            return Result.UNAVAILABLE;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.UNAVAILABLE;
        } finally {
            try {
                locationManager.removeUpdates(listener);
            } catch (RuntimeException ignored) {
                // Do not print or retain a location-bearing diagnostic.
            }
        }
    }

    private static void closeCallbackThread(HandlerThread callbackThread) {
        callbackThread.quitSafely();
        try {
            callbackThread.join(1000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static Result classify(Location location) {
        if (location == null) {
            return Result.UNAVAILABLE;
        }

        double latitude = location.getLatitude();
        double longitude = location.getLongitude();
        if (Double.isFinite(latitude)
                && Double.isFinite(longitude)
                && Double.compare(latitude, 1.0d) == 0
                && Double.compare(longitude, 2.0d) == 0) {
            return Result.MATCH;
        }
        return Result.MISMATCH;
    }

    private static Result aggregate(Result... results) {
        boolean unavailable = false;
        for (Result result : results) {
            if (result == Result.MISMATCH) {
                return Result.MISMATCH;
            }
            if (result == Result.UNAVAILABLE) {
                unavailable = true;
            }
        }
        return unavailable ? Result.UNAVAILABLE : Result.MATCH;
    }

    private static void logProviderStatus(String provider, String source, Result result) {
        Log.i(TAG, "RESULT " + provider + " " + source + " " + result.name());
    }

    private static void logOverall(Result result) {
        Log.i(TAG, "RESULT " + result.name());
    }
}
