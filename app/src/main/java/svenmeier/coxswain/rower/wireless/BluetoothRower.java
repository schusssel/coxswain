package svenmeier.coxswain.rower.wireless;

import android.Manifest;
import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Criteria;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import propoid.util.content.Preference;
import svenmeier.coxswain.BuildConfig;
import svenmeier.coxswain.Coxswain;
import svenmeier.coxswain.R;
import svenmeier.coxswain.bluetooth.BlueUtils;
import svenmeier.coxswain.bluetooth.BluetoothActivity;
import svenmeier.coxswain.bluetooth.Fields;
import svenmeier.coxswain.rower.Rower;
import svenmeier.coxswain.util.PermissionBlock;

public class BluetoothRower extends Rower {

	private static final int CONNECT_TIMEOUT_MILLIS = 10000;

	/**
	 * Timeout after which we re-enable notifications.
	 */
	private static final int NOTIFICATIONS_TIMEOUT = 2000;

	private final Context context;

	private final Handler handler = new Handler();

	private final Preference<String> devicePreference;
	
	private final Preference<Boolean> devicePreferenceRemember;

	private ArrayDeque<Connection> connections = new ArrayDeque<>();

	public BluetoothRower(Context context, Callback callback) {
		super(context, callback);

		this.context = context;

		devicePreference = Preference.getString(context, R.string.preference_bluetooth_heart_device);
		devicePreferenceRemember = Preference.getBoolean(context, R.string.preference_bluetooth_heart_device_remember);
	}

	@Override
	public void open() {
		if (connections.isEmpty()) {
			connect(new Permissions());
		}
	}

	@Override
	public void reset() {
		Connection last = connections.peekLast();
		if (last instanceof GattConnection) {
			((GattConnection) last).reset();
		}

		super.reset();
	}

	private void fireDisconnected() {
		handler.post(new Runnable() {
			@Override
			public void run() {
				callback.onDisconnected();
			}
		});
	}
	
	@Override
	public void close() {
		while (connections.isEmpty() == false) {
			connections.pop().close();
		}
	}

	@Override
	public String getName() {
		return "Bluetooth Rower";
	}

	private void connect(Connection connection) {
		this.connections.push(connection);
		
		connection.open();
	}

	private interface Connection {

		void open();

		void close();
	}

	private class Permissions extends PermissionBlock implements Connection {

		public Permissions() {
			super(context);
		}

		@Override
		public void open() {
			acquirePermissions(Manifest.permission.ACCESS_FINE_LOCATION);
		}

		@Override
		public void close() {
			abortPermissions();
		}

		@Override
		protected void onPermissionsApproved() {
			connect(new LocationServices());
		}
	}

	private class LocationServices extends BroadcastReceiver implements Connection {

		private boolean registered;

		public void open() {
			if (isRequired()) {
				if (isEnabled() == false) {
					toast(context.getString(R.string.bluetooth_rower_no_location));

					Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
					intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
					context.startActivity(intent);

					IntentFilter filter = new IntentFilter();
					filter.addAction(LocationManager.MODE_CHANGED_ACTION);
					context.registerReceiver(this, filter);
					registered = true;

					return;
				}
			}

			proceed();
		}

		/**
		 * Location services must be enabled for Apps built for M and running on M or later.
		 */
		private boolean isRequired() {
			boolean builtForM = context.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.M;
			boolean runningOnM = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;

			return builtForM && runningOnM;
		}

		private boolean isEnabled() {
			LocationManager manager = (LocationManager)context.getSystemService(Context.LOCATION_SERVICE);

			Criteria criteria = new Criteria();
			criteria.setAccuracy(Criteria.ACCURACY_COARSE);
			criteria.setAltitudeRequired(false);
			criteria.setBearingRequired(false);
			criteria.setCostAllowed(true);
			criteria.setPowerRequirement(Criteria.NO_REQUIREMENT);

			String provider = manager.getBestProvider(criteria, true);

			return provider != null && LocationManager.PASSIVE_PROVIDER.equals(provider) == false;
		}

		@Override
		public void onReceive(Context context, Intent intent) {
			if (connections.peek() == this && isEnabled()) {
				proceed();
			}
		}

		private void proceed() {
			connect(new Bluetooth());
		}

		@Override
		public void close() {
			if (registered) {
				context.unregisterReceiver(this);
				registered = false;
			}
		}
	}

	@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
	private class Bluetooth extends BroadcastReceiver implements Connection {

		private BluetoothAdapter adapter;

		private boolean registered;

		@Override
		public void open() {
			BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
			adapter = manager.getAdapter();

			if (adapter == null) {
				toast(context.getString(R.string.bluetooth_rower_no_bluetooth));
				return;
			}

			IntentFilter filter = new IntentFilter();
			filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
			filter.addAction(LocationManager.MODE_CHANGED_ACTION);
			context.registerReceiver(this, filter);
			registered = true;

			if (adapter.isEnabled() == false) {
				adapter.enable();
			} else {
				proceed();
			}
		}

		@Override
		public void onReceive(Context context, Intent intent) {
			int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0);
			if (state == BluetoothAdapter.STATE_ON) {
				if (connections.peek() == this) {
					proceed();
				}
			} else if (state == BluetoothAdapter.STATE_OFF) {
				fireDisconnected();
			}
		}

		private void proceed() {
			connect(new SelectionConnection());
		}

		@Override
		public void close() {
			if (registered) {
				context.unregisterReceiver(this);
				registered = false;
			}

			adapter = null;
		}
	}

	private class SelectionConnection extends BroadcastReceiver implements Connection {

		boolean registered = false;

		@Override
		public void open() {
			String address = devicePreference.get();
			if (address != null && devicePreferenceRemember.get()) {
				proceed(address);
				return;
			}

			String name = context.getString(R.string.bluetooth_rower);
			IntentFilter filter = BluetoothActivity.start(context, name, BlueUtils.SERVICE_FITNESS_MACHINE.toString());
			context.registerReceiver(this, filter);
			registered = true;
		}

		@Override
		public final void onReceive(Context context, Intent intent) {
			if (connections.peek() == this) {
				String address = intent.getStringExtra(BluetoothActivity.DEVICE_ADDRESS);

				devicePreference.set(address);
				devicePreferenceRemember.set(intent.getBooleanExtra(BluetoothActivity.DEVICE_REMEMBER, false));

				proceed(address);
			}
		}

		private void proceed(String address) {
			connect(new GattConnection(address));
		}

		@Override
		public void close() {
			if (registered) {
				context.unregisterReceiver(this);
				registered = false;
			}
		}
	}

	@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
	private class GattConnection extends BluetoothGattCallback implements Connection, Runnable {

		private final String address;

		private KeepAlive keepAlive = new KeepAlive();

		private BluetoothAdapter adapter;

		private BluetoothGatt connected;

		private BluetoothGattCharacteristic rowerData;

		private BluetoothGattCharacteristic controlPoint;

		GattConnection(String address) {
			this.address = address;
		}

		private void select() {
			devicePreference.set(null);
			devicePreferenceRemember.set(false);

			connect(new SelectionConnection());
		}

		@Override
		public synchronized void open() {
			BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);

			adapter = manager.getAdapter();

			try {
				BluetoothDevice device = adapter.getRemoteDevice(address);
				Log.d(Coxswain.TAG, "bluetooth rower connecting " + address);
				connected = device.connectGatt(context, false, this);

				handler.removeCallbacks(this);
				handler.postDelayed(this, CONNECT_TIMEOUT_MILLIS);
			} catch (IllegalArgumentException invalid) {
				select();
			}
		}

		@Override
		public synchronized void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
			if (adapter == null) {
				return;
			}

			String address = gatt.getDevice().getAddress();

			if (newState == BluetoothProfile.STATE_CONNECTED) {
				Log.d(Coxswain.TAG, "bluetooth rower connected " + address);

				connected = gatt;
				connected.discoverServices();
			} else if (newState == BluetoothProfile.STATE_DISCONNECTED ) {
				if (connected != null && connected.getDevice().getAddress().equals(address)) {
					Log.d(Coxswain.TAG, "bluetooth rower disconnected " + address);

					if (adapter.isEnabled()) {
						toast(context.getString(R.string.bluetooth_rower_disconnected, address));

						if (rowerData == null) {
							// only select when no successful connection yet
							select();
							return;
						}
					}

					fireDisconnected();
				}
			}
		}

		private void disconnect() {
			if (connected != null) {
				connected.close();
				connected = null;
			}

			rowerData = null;
			controlPoint = null;
		}

		public synchronized void close() {
			disconnect();

			adapter = null;
		}

		public void reset() {
			if (controlPoint != null) {
				if (BlueUtils.write(connected, controlPoint, 0x01) == false) {
					Log.d(Coxswain.TAG, "bluetooth no rower data reset");
				}
			}
		}

		@Override
		public synchronized void onServicesDiscovered(BluetoothGatt gatt, int status) {
			if (connected == null) {
				return;
			}

			BluetoothGattService service = gatt.getService(BlueUtils.SERVICE_FITNESS_MACHINE);
			if (service == null) {
				Log.d(Coxswain.TAG, "bluetooth no fitness machine");
			} else {
				// the comm module hangs up as soon as something is written to the control point :/
				//controlPoint = service.getCharacteristic(BlueUtils.CHARACTERISTIC_CONTROL_POINT);
				if (controlPoint == null) {
					Log.d(Coxswain.TAG, "bluetooth no control point");
				} else {
					if (BlueUtils.write(connected, controlPoint, 0x00) == false) {
						Log.d(Coxswain.TAG, "bluetooth no rower data request control");
					}
				}

				rowerData = service.getCharacteristic(BlueUtils.CHARACTERISTIC_ROWER_DATA);
				if (rowerData == null) {
					Log.d(Coxswain.TAG, "bluetooth no rower data");
				} else {
					if (BlueUtils.enableNotification(gatt, rowerData)) {
						handler.post(new Runnable() {
							@Override
							public void run() {
								callback.onConnected();
							}
						});
						return;
					}
					Log.d(Coxswain.TAG, "bluetooth no rower data notification");
				}
			}

			rowerData = null;
			toast(context.getString(R.string.bluetooth_rower_not_found, gatt.getDevice().getAddress()));

			select();
		}

		@Override
		public synchronized void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
			if (connected == null) {
				return;
			}

			Fields fields = new Fields(characteristic, Fields.UINT16);

			if (fields.flag(0) == false) { // more data
				strokeRate = fields.get(Fields.UINT8) / 2; // stroke rate 0.5
				strokes = fields.get(Fields.UINT16); // stroke count
			}
			if (fields.flag(1)) {
				fields.get(Fields.UINT8); // average stroke rate
			}
			if (fields.flag(2)) {
				distance = fields.get(Fields.UINT16) +
						(fields.get(Fields.UINT8) << 16); // total distance
			}
			if (fields.flag(3)) {
				speed = 500 *100 / fields.get(Fields.UINT16); // instantaneous pace
			}
			if (fields.flag(4)) {
				fields.get(Fields.UINT16); // average pace
			}
			if (fields.flag(5)) {
				power = fields.get(Fields.SINT16); // instantaneous power
			}
			if (fields.flag(6)) {
				fields.get(Fields.SINT16); // average power
			}
			if (fields.flag(7)) {
				fields.get(Fields.SINT16); // resistance level
			}
			if (fields.flag(8)) { // expended energy
				energy = fields.get(Fields.UINT16); // total energy
				fields.get(Fields.UINT16); // energy per hour
				fields.get(Fields.UINT8); // energy per minute
			}
			if (fields.flag(9)) {
				int heartRate = fields.get(Fields.UINT8); // heart rate
				if (heartRate > 0) {
					pulse = heartRate;
				}
			}
			if (fields.flag(10)) {
				fields.get(Fields.UINT8); // metabolic equivalent 0.1
			}
			if (fields.flag(11)) {
				int elapsedTime = fields.get(Fields.UINT16); // elapsed time
				int delta = Math.abs(elapsedTime - duration);
				//  erroneous  values are sent on minute boundaries, so ignore these deltas
				if (delta >= 58 && delta <= 60) {
					// 359 ... 300 ... 360
					// 599 ... 659 ... 600
					// 478 ... 420 ... 479
					Log.d(Coxswain.TAG, String.format("bluetooth rower erroneous elapsed time %s, duration is %s", elapsedTime, duration));
				} else {
					duration = elapsedTime;
				}
			}
			if (fields.flag(12)) {
				fields.get(Fields.UINT16); // remaining time
			}

			keepAlive.onNotification();

			notifyMeasurement();
		}

		/**
		 * @see BluetoothRower#CONNECT_TIMEOUT_MILLIS
		 */
		@Override
		public void run() {
			if (connected != null && rowerData == null) {
				toast(context.getString(R.string.bluetooth_heart_failed, connected.getDevice().getAddress()));

				select();
			}
		}

		/**
		 * Notifications from the S4 comm module time out every other minute,
		 * thus we re-enabled the notification when no new notification has
		 * been received within a timeout.
		 *
		 * @see #NOTIFICATIONS_TIMEOUT
		 */
		private class KeepAlive implements Runnable {

			/**
			 * Notification was received.
			 */
			public void onNotification() {
				handler.removeCallbacks(this);
				handler.postDelayed(this, NOTIFICATIONS_TIMEOUT);
			}

			/**
			 * Notifications timed out.
			 */
			@Override
			public void run() {
				if (rowerData != null) {
					Log.d(Coxswain.TAG, "bluetooth rower notifications time-out");

					if (BuildConfig.DEBUG) {
						toast(context.getString(R.string.bluetooth_rower_notification_timeout));
					}

					// re-enable notification
					BlueUtils.enableNotification(connected, rowerData);
				}
			}
		}
	}
}