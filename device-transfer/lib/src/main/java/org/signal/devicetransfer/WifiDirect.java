package org.signal.devicetransfer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Build;
import android.os.HandlerThread;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.content.ContextCompat;

import org.signal.core.util.ThreadUtil;
import org.signal.core.util.concurrent.SignalExecutors;
import org.signal.core.util.logging.Log;
import org.signal.devicetransfer.WifiDirectUnavailableException.Reason;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Provide the ability to spin up a WiFi Direct network, advertise a network service,
 * discover a network service, and then connect two devices.
 */
@SuppressLint("MissingPermission")
public final class WifiDirect {

  private static final String TAG = Log.tag(WifiDirect.class);

  private static final IntentFilter intentFilter = new IntentFilter() {{
    addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
    addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
    addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
    addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
  }};

  public static final String SERVICE_INSTANCE = "_devicetransfer._signal.org";
  public static final String SERVICE_REG_TYPE = "_presence._tcp";

  private final Context                      context;
  private       WifiDirectConnectionListener connectionListener;
  private       WifiDirectCallbacks          wifiDirectCallbacks;
  private       WifiP2pManager               manager;
  private       WifiP2pManager.Channel       channel;
  private       WifiP2pDnsSdServiceRequest   serviceRequest;
  private final HandlerThread                wifiDirectCallbacksHandler;

  /**
   * Determine the ability to use WiFi Direct by checking if the device supports WiFi Direct
   * and the appropriate permissions have been granted.
   */
  public static @NonNull AvailableStatus getAvailability(@NonNull Context context) {
    if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
      Log.i(TAG, "Feature not available");
      return AvailableStatus.FEATURE_NOT_AVAILABLE;
    }

    WifiManager wifiManager = ContextCompat.getSystemService(context, WifiManager.class);
    if (wifiManager == null) {
      Log.i(TAG, "WifiManager not available");
      return AvailableStatus.WIFI_MANAGER_NOT_AVAILABLE;
    }

    if (Build.VERSION.SDK_INT >= 23 && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
      Log.i(TAG, "Fine location permission required");
      return AvailableStatus.FINE_LOCATION_PERMISSION_NOT_GRANTED;
    }

    return Build.VERSION.SDK_INT <= 23 || wifiManager.isP2pSupported() ? AvailableStatus.AVAILABLE
                                                                       : AvailableStatus.WIFI_DIRECT_NOT_AVAILABLE;
  }

  public WifiDirect(@NonNull Context context) {
    this.context                    = context.getApplicationContext();
    this.wifiDirectCallbacksHandler = SignalExecutors.getAndStartHandlerThread("wifi-direct-cb");
  }

  /**
   * Initialize {@link WifiP2pManager} and {@link WifiP2pManager.Channel} needed to interact
   * with the Android WiFi Direct APIs. This should have a matching call to {@link #shutdown()} to
   * release the various resources used to establish and maintain a WiFi Direct network.
   */
  public synchronized void initialize(@NonNull WifiDirectConnectionListener connectionListener) throws WifiDirectUnavailableException {
    if (isInitialized()) {
      Log.w(TAG, "Already initialized, do not need to initialize twice");
      return;
    }

    this.connectionListener = connectionListener;

    manager = ContextCompat.getSystemService(context, WifiP2pManager.class);
    if (manager == null) {
      Log.i(TAG, "Unable to get WifiP2pManager");
      shutdown();
      throw new WifiDirectUnavailableException(Reason.WIFI_P2P_MANAGER);
    }

    wifiDirectCallbacks = new WifiDirectCallbacks();
    channel             = manager.initialize(context, wifiDirectCallbacksHandler.getLooper(), wifiDirectCallbacks);
    if (channel == null) {
      Log.i(TAG, "Unable to initialize channel");
      shutdown();
      throw new WifiDirectUnavailableException(Reason.CHANNEL_INITIALIZATION);
    }

    context.registerReceiver(wifiDirectCallbacks, intentFilter);
  }

  /**
   * Clears and releases WiFi Direct resources that may have been created or in use. Also
   * shuts down the WiFi Direct related {@link HandlerThread}.
   * <p>
   * <i>Note: After this call, the instance is no longer usable and an entirely new one will need to
   * be created.</i>
   */
  public synchronized void shutdown() {
    Log.d(TAG, "Shutting down");

    connectionListener = null;

    if (manager != null) {
      retry(manager::clearServiceRequests, "clear service requests");
      retry(manager::stopPeerDiscovery, "stop peer discovery");
      retry(manager::clearLocalServices, "clear local services");
      manager = null;
    }

    if (channel != null) {
      channel.close();
      channel = null;
    }

    if (wifiDirectCallbacks != null) {
      context.unregisterReceiver(wifiDirectCallbacks);
      wifiDirectCallbacks = null;
    }

    wifiDirectCallbacksHandler.quit();
    wifiDirectCallbacksHandler.interrupt();
  }

  /**
   * Start advertising a transfer service that other devices can search for and decide
   * to connect to. Call on an appropriate thread as this method synchronously calls WiFi Direct
   * methods.
   */
  @WorkerThread
  public synchronized void startDiscoveryService() throws WifiDirectUnavailableException {
    ensureInitialized();

    WifiP2pDnsSdServiceInfo serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(SERVICE_INSTANCE, SERVICE_REG_TYPE, Collections.emptyMap());

    SyncActionListener addLocalServiceListener = new SyncActionListener("add local service");
    manager.addLocalService(channel, serviceInfo, addLocalServiceListener);

    SyncActionListener discoverPeersListener = new SyncActionListener("discover peers");
    manager.discoverPeers(channel, discoverPeersListener);

    if (!addLocalServiceListener.successful() || !discoverPeersListener.successful()) {
      throw new WifiDirectUnavailableException(Reason.SERVICE_START);
    }
  }

  /**
   * Start searching for a transfer service being advertised by another device. Call on an
   * appropriate thread as this method synchronously calls WiFi Direct methods.
   */
  @WorkerThread
  public synchronized void discoverService() throws WifiDirectUnavailableException {
    ensureInitialized();

    if (serviceRequest != null) {
      Log.w(TAG, "Discover service already called and active.");
      return;
    }

    WifiP2pManager.DnsSdTxtRecordListener txtListener = (fullDomain, record, device) -> {};

    WifiP2pManager.DnsSdServiceResponseListener serviceListener = (instanceName, registrationType, sourceDevice) -> {
      if (SERVICE_INSTANCE.equals(instanceName)) {
        Log.d(TAG, "Service found!");
        if (connectionListener != null) {
          connectionListener.onServiceDiscovered(sourceDevice);
        }
      } else {
        Log.d(TAG, "Found unusable service, ignoring.");
      }
    };

    manager.setDnsSdResponseListeners(channel, serviceListener, txtListener);

    serviceRequest = WifiP2pDnsSdServiceRequest.newInstance();

    SyncActionListener addServiceListener = new SyncActionListener("add service request");
    manager.addServiceRequest(channel, serviceRequest, addServiceListener);

    SyncActionListener startDiscovery = new SyncActionListener("discover services");
    manager.discoverServices(channel, startDiscovery);

    if (!addServiceListener.successful() || !startDiscovery.successful()) {
      manager.removeServiceRequest(channel, serviceRequest, null);
      serviceRequest = null;
      throw new WifiDirectUnavailableException(Reason.SERVICE_DISCOVERY_START);
    }
  }

  /**
   * Establish a WiFi Direct network by connecting to the given device address (MAC). An
   * address can be found by using {@link #discoverService()}.
   *
   * @param deviceAddress Device MAC address to establish a connection with
   */
  public synchronized void connect(@NonNull String deviceAddress) throws WifiDirectUnavailableException {
    ensureInitialized();

    WifiP2pConfig config = new WifiP2pConfig();
    config.deviceAddress = deviceAddress;
    config.wps.setup     = WpsInfo.PBC;

    if (serviceRequest != null) {
      manager.removeServiceRequest(channel, serviceRequest, LoggingActionListener.message("Remote service request"));
      serviceRequest = null;
    }

    SyncActionListener listener = new SyncActionListener("service connect");
    manager.connect(channel, config, listener);

    if (listener.successful()) {
      Log.i(TAG, "Successfully connected to service.");
    } else {
      throw new WifiDirectUnavailableException(Reason.SERVICE_CONNECT_FAILURE);
    }
  }

  private synchronized void retry(@NonNull ManagerRetry retryFunction, @NonNull String message) {
    int tries = 3;

    while ((tries--) > 0) {
      SyncActionListener listener = new SyncActionListener(message);
      retryFunction.call(channel, listener);
      if (listener.successful()) {
        return;
      }
      ThreadUtil.sleep(TimeUnit.SECONDS.toMillis(1));
    }
  }

  private synchronized boolean isInitialized() {
    return manager != null && channel != null;
  }

  private synchronized boolean isNotInitialized() {
    return manager == null || channel == null;
  }

  private void ensureInitialized() throws WifiDirectUnavailableException {
    if (isNotInitialized()) {
      Log.w(TAG, "WiFi Direct has not been initialized.");
      throw new WifiDirectUnavailableException(Reason.SERVICE_NOT_INITIALIZED);
    }
  }

  private interface ManagerRetry {
    void call(@NonNull WifiP2pManager.Channel a, @NonNull WifiP2pManager.ActionListener b);
  }

  private class WifiDirectCallbacks extends BroadcastReceiver implements WifiP2pManager.ChannelListener, WifiP2pManager.ConnectionInfoListener {
    @Override
    public void onReceive(@NonNull Context context, @NonNull Intent intent) {
      String action = intent.getAction();
      if (action != null) {
        switch (action) {
          case WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION:
            WifiP2pDevice localDevice = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
            if (localDevice != null && connectionListener != null) {
              connectionListener.onLocalDeviceChanged(localDevice);
            }
            break;
          case WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION:
            if (isNotInitialized()) {
              Log.w(TAG, "WiFi P2P broadcast connection changed action without being initialized.");
              return;
            }

            NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);

            if (networkInfo == null) {
              Log.w(TAG, "WiFi P2P broadcast connection changed action with null network info.");
              return;
            }

            if (networkInfo.isConnected()) {
              Log.i(TAG, "Connected to P2P network, requesting connection information.");
              manager.requestConnectionInfo(channel, this);
            } else {
              Log.i(TAG, "Disconnected from P2P network");
              if (connectionListener != null) {
                connectionListener.onNetworkDisconnected();
              }
            }
            break;
        }
      }
    }

    @Override
    public void onConnectionInfoAvailable(@NonNull WifiP2pInfo info) {
      Log.i(TAG, "Connection information available. group_formed: " + info.groupFormed + " group_owner: " + info.isGroupOwner);
      if (connectionListener != null) {
        connectionListener.onNetworkConnected(info);
      }
    }

    @Override
    public void onChannelDisconnected() {
      if (connectionListener != null) {
        connectionListener.onNetworkFailure();
      }
    }
  }

  /**
   * Provide a synchronous way to talking to Android's WiFi Direct code.
   */
  private static class SyncActionListener extends LoggingActionListener {

    private final CountDownLatch sync;

    private volatile int failureReason = -1;

    public SyncActionListener(@NonNull String message) {
      super(message);
      this.sync = new CountDownLatch(1);
    }

    @Override
    public void onSuccess() {
      super.onSuccess();
      sync.countDown();
    }

    @Override
    public void onFailure(int reason) {
      super.onFailure(reason);
      failureReason = reason;
      sync.countDown();
    }

    public boolean successful() {
      try {
        sync.await();
      } catch (InterruptedException ie) {
        throw new AssertionError(ie);
      }
      return failureReason < 0;
    }
  }

  private static class LoggingActionListener implements WifiP2pManager.ActionListener {

    private final String message;

    public static @NonNull LoggingActionListener message(@Nullable String message) {
      return new LoggingActionListener(message);
    }

    public LoggingActionListener(@Nullable String message) {
      this.message = message;
    }

    @Override
    public void onSuccess() {
      Log.i(TAG, message + " success");
    }

    @Override
    public void onFailure(int reason) {
      Log.w(TAG, message + " failure_reason: " + reason);
    }
  }

  public enum AvailableStatus {
    FEATURE_NOT_AVAILABLE,
    WIFI_MANAGER_NOT_AVAILABLE,
    FINE_LOCATION_PERMISSION_NOT_GRANTED,
    WIFI_DIRECT_NOT_AVAILABLE,
    AVAILABLE
  }

  public interface WifiDirectConnectionListener {
    void onLocalDeviceChanged(@NonNull WifiP2pDevice localDevice);

    void onServiceDiscovered(@NonNull WifiP2pDevice serviceDevice);

    void onNetworkConnected(@NonNull WifiP2pInfo info);

    void onNetworkDisconnected();

    void onNetworkFailure();
  }
}