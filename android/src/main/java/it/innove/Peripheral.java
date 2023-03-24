package it.innove;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Nullable;
import android.util.Base64;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.RCTNativeAppEventEmitter;

import org.json.JSONException;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static android.os.Build.VERSION_CODES.LOLLIPOP;
import static com.facebook.react.common.ReactConstants.TAG;

/**
 * Peripheral wraps the BluetoothDevice and provides methods to convert to JSON.
 */
public class Peripheral extends BluetoothGattCallback {

	private static final String CHARACTERISTIC_NOTIFICATION_CONFIG = "00002902-0000-1000-8000-00805f9b34fb";
	public static final int GATT_INSUFFICIENT_AUTHENTICATION = 5;
	public static final int GATT_AUTH_FAIL = 137;

	private final BluetoothDevice device;
	private final Map<String, NotifyBufferContainer> bufferedCharacteristics;
	protected volatile byte[] advertisingDataBytes = new byte[0];
	protected volatile int advertisingRSSI;
	private volatile boolean connected = false;
	private volatile boolean connecting = false;
	private ReactContext reactContext;

	private BluetoothGatt gatt;

	private Callback connectCallback;
	private Callback retrieveServicesCallback;
	private Callback readCallback;
	private Callback readDescriptorCallback;
	private Callback readRSSICallback;
	private Callback writeCallback;
	private Callback writeDescriptorCallback;
	private Callback registerNotifyCallback;
	private Callback requestMTUCallback;

	private final Queue<Runnable> commandQueue = new ConcurrentLinkedQueue<>();
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private Runnable discoverServicesRunnable;
	private boolean commandQueueBusy = false;

	private List<byte[]> writeQueue = new ArrayList<>();

	public Peripheral(BluetoothDevice device, int advertisingRSSI, byte[] scanRecord, ReactContext reactContext) {
		this.device = device;
		this.bufferedCharacteristics = new ConcurrentHashMap<String, NotifyBufferContainer>();
		this.advertisingRSSI = advertisingRSSI;
		this.advertisingDataBytes = scanRecord;
		this.reactContext = reactContext;
	}

	public Peripheral(BluetoothDevice device, ReactContext reactContext) {
		this.device = device;
		this.bufferedCharacteristics = new ConcurrentHashMap<String, NotifyBufferContainer>();
		this.reactContext = reactContext;
	}

	private void sendEvent(String eventName, @Nullable WritableMap params) {
		synchronized (reactContext) {
			reactContext.getJSModule(RCTNativeAppEventEmitter.class).emit(eventName, params);
		}
	}

	private void sendConnectionEvent(BluetoothDevice device, String eventName, int status) {
		WritableMap map = Arguments.createMap();
		map.putString("peripheral", device.getAddress());
		if (status != -1) {
			map.putInt("status", status);
		}
		sendEvent(eventName, map);
		Log.d(BleManager.LOG_TAG, "Peripheral event (" + eventName + "):" + device.getAddress());
	}

	public void connect(final Callback callback, Activity activity) {
		mainHandler.post(() -> {
			if (!connected) {
				BluetoothDevice device = getDevice();
				this.connectCallback = callback;
				this.connecting = true;
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
					Log.d(BleManager.LOG_TAG, " Is Or Greater than M $mBluetoothDevice");
					gatt = device.connectGatt(activity, false, this, BluetoothDevice.TRANSPORT_LE);
				} else {
					Log.d(BleManager.LOG_TAG, " Less than M");
					try {
						Log.d(BleManager.LOG_TAG, " Trying TRANPORT LE with reflection");
						Method m = device.getClass().getDeclaredMethod("connectGatt", Context.class, Boolean.class,
								BluetoothGattCallback.class, Integer.class);
						m.setAccessible(true);
						Integer transport = device.getClass().getDeclaredField("TRANSPORT_LE").getInt(null);
						gatt = (BluetoothGatt) m.invoke(device, activity, false, this, transport);
					} catch (Exception e) {
						e.printStackTrace();
						Log.d(TAG, " Catch to call normal connection");
						gatt = device.connectGatt(activity, false, this);
					}
				}
			} else {
				if (gatt != null) {
					callback.invoke();
				} else {
					callback.invoke("BluetoothGatt is null");
				}
			}
		});
	}
	// bt_btif : Register with GATT stack failed.

	public void disconnect(final Callback callback, final boolean force) {
		mainHandler.post(() -> {
			connectCallback = null;
			connected = false;
			clearBuffers();
			commandQueue.clear();
			commandQueueBusy = false;

			if (gatt != null) {
				try {
					gatt.disconnect();
					if (force) {
						gatt.close();
						gatt = null;
						sendConnectionEvent(device, "BleManagerDisconnectPeripheral", BluetoothGatt.GATT_SUCCESS);
					}
					Log.d(BleManager.LOG_TAG, "Disconnect");
				} catch (Exception e) {
					sendConnectionEvent(device, "BleManagerDisconnectPeripheral", BluetoothGatt.GATT_FAILURE);
					Log.d(BleManager.LOG_TAG, "Error on disconnect", e);
				}
			} else
				Log.d(BleManager.LOG_TAG, "GATT is null");
			if (callback != null)
				callback.invoke();
		});
	}

	public WritableMap asWritableMap() {
		WritableMap map = Arguments.createMap();
		WritableMap advertising = Arguments.createMap();

		try {
			map.putString("name", device.getName());
			map.putString("id", device.getAddress()); // mac address
			map.putInt("rssi", advertisingRSSI);

			String name = device.getName();
			if (name != null)
				advertising.putString("localName", name);

			advertising.putMap("manufacturerData", byteArrayToWritableMap(advertisingDataBytes));

			// No scanResult to access so we can't check if peripheral is connectable
			advertising.putBoolean("isConnectable", true);

			map.putMap("advertising", advertising);
		} catch (Exception e) { // this shouldn't happen
			e.printStackTrace();
		}

		return map;
	}

	public WritableMap asWritableMap(BluetoothGatt gatt) {

		WritableMap map = asWritableMap();

		WritableArray servicesArray = Arguments.createArray();
		WritableArray characteristicsArray = Arguments.createArray();

		if (connected && gatt != null) {
			for (Iterator<BluetoothGattService> it = gatt.getServices().iterator(); it.hasNext();) {
				BluetoothGattService service = it.next();
				WritableMap serviceMap = Arguments.createMap();
				serviceMap.putString("uuid", UUIDHelper.uuidToString(service.getUuid()));

				for (Iterator<BluetoothGattCharacteristic> itCharacteristic = service.getCharacteristics()
						.iterator(); itCharacteristic.hasNext();) {
					BluetoothGattCharacteristic characteristic = itCharacteristic.next();
					WritableMap characteristicsMap = Arguments.createMap();

					characteristicsMap.putString("service", UUIDHelper.uuidToString(service.getUuid()));
					characteristicsMap.putString("characteristic", UUIDHelper.uuidToString(characteristic.getUuid()));

					characteristicsMap.putMap("properties", Helper.decodeProperties(characteristic));

					if (characteristic.getPermissions() > 0) {
						characteristicsMap.putMap("permissions", Helper.decodePermissions(characteristic));
					}

					WritableArray descriptorsArray = Arguments.createArray();

					for (BluetoothGattDescriptor descriptor : characteristic.getDescriptors()) {
						WritableMap descriptorMap = Arguments.createMap();
						descriptorMap.putString("uuid", UUIDHelper.uuidToString(descriptor.getUuid()));
						if (descriptor.getValue() != null) {
							descriptorMap.putString("value",
									Base64.encodeToString(descriptor.getValue(), Base64.NO_WRAP));
						} else {
							descriptorMap.putString("value", null);
						}

						if (descriptor.getPermissions() > 0) {
							descriptorMap.putMap("permissions", Helper.decodePermissions(descriptor));
						}
						descriptorsArray.pushMap(descriptorMap);
					}
					if (descriptorsArray.size() > 0) {
						characteristicsMap.putArray("descriptors", descriptorsArray);
					}
					characteristicsArray.pushMap(characteristicsMap);
				}
				servicesArray.pushMap(serviceMap);
			}
			map.putArray("services", servicesArray);
			map.putArray("characteristics", characteristicsArray);
		}

		return map;
	}

	static WritableMap byteArrayToWritableMap(byte[] bytes) throws JSONException {
		WritableMap object = Arguments.createMap();
		object.putString("CDVType", "ArrayBuffer");
		object.putString("data", bytes != null ? Base64.encodeToString(bytes, Base64.NO_WRAP) : null);
		object.putArray("bytes", bytes != null ? BleManager.bytesToWritableArray(bytes) : null);
		return object;
	}

	public boolean isConnected() {
		return connected;
	}

	public boolean isConnecting() {
		return connecting;
	}

	public BluetoothDevice getDevice() {
		return device;
	}

	@Override
	public void onServicesDiscovered(BluetoothGatt gatt, int status) {
		super.onServicesDiscovered(gatt, status);
		mainHandler.post(() -> {
			if (retrieveServicesCallback != null) {
				WritableMap map = this.asWritableMap(gatt);
				retrieveServicesCallback.invoke(null, map);
				retrieveServicesCallback = null;
			}
			completedCommand();
		});
	}

	@Override
	public void onConnectionStateChange(BluetoothGatt gatta, int status, final int newState) {

		Log.d(BleManager.LOG_TAG, "onConnectionStateChange to " + newState + " on peripheral: " + device.getAddress()
				+ " with status " + status);

		mainHandler.post(() -> {
			gatt = gatta;

			if (status != BluetoothGatt.GATT_SUCCESS) {
				gatt.close();
			}

			connecting = false;
			if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
				connected = true;

				discoverServicesRunnable = new Runnable() {
					@Override
					public void run() {
						try {
							gatt.discoverServices();
						} catch (NullPointerException e) {
							Log.d(BleManager.LOG_TAG, "onConnectionStateChange connected but gatt of Run method was null");
						}
						discoverServicesRunnable = null;
					}
				};

				mainHandler.post(discoverServicesRunnable);

				sendConnectionEvent(device, "BleManagerConnectPeripheral", status);

				if (connectCallback != null) {
					Log.d(BleManager.LOG_TAG, "Connected to: " + device.getAddress());
					connectCallback.invoke();
					connectCallback = null;
				}

			} else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {

				if (discoverServicesRunnable != null) {
					mainHandler.removeCallbacks(discoverServicesRunnable);
					discoverServicesRunnable = null;
				}

				List<Callback> callbacks = Arrays.asList(writeCallback, writeDescriptorCallback, retrieveServicesCallback, readRSSICallback,
						readCallback, readDescriptorCallback, registerNotifyCallback, requestMTUCallback);
				for (Callback currentCallback : callbacks) {
					if (currentCallback != null) {
						try {
							currentCallback.invoke("Device disconnected");
						} catch (Exception e) {
							e.printStackTrace();
						}				}
				}
				if (connectCallback != null) {
					connectCallback.invoke("Connection error");
					connectCallback = null;
				}
				writeCallback = null;
				writeQueue.clear();
				writeDescriptorCallback = null;
				readCallback = null;
				readDescriptorCallback = null;
				retrieveServicesCallback = null;
				readRSSICallback = null;
				registerNotifyCallback = null;
				requestMTUCallback = null;
				commandQueue.clear();
				commandQueueBusy = false;
				connectCallback = null;
				connected = false;
				clearBuffers();
				commandQueue.clear();
				commandQueueBusy = false;

				gatt.disconnect();
				gatt.close();
				gatt = null;
				sendConnectionEvent(device, "BleManagerDisconnectPeripheral", BluetoothGatt.GATT_SUCCESS);

			}

		});

	}

	public void updateRssi(int rssi) {
		advertisingRSSI = rssi;
	}

	public void updateData(byte[] data) {
		advertisingDataBytes = data;
	}

	public int unsignedToBytes(byte b) {
		return b & 0xFF;
	}

	//////

	@Override
	public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
		super.onCharacteristicChanged(gatt, characteristic);
		try {
			String charString = characteristic.getUuid().toString();
			String service = characteristic.getService().getUuid().toString();
			NotifyBufferContainer buffer = this.bufferedCharacteristics
					.get(this.bufferedCharacteristicsKey(service, charString));
			byte[] dataValue = characteristic.getValue();
			if (buffer != null) {
				buffer.put(dataValue);
				Log.d(BleManager.LOG_TAG, "onCharacteristicChanged-buffering: " +
						buffer.size() + " from peripheral: " + device.getAddress());

				if (buffer.isBufferFull()) {
					Log.d(BleManager.LOG_TAG, "onCharacteristicChanged sending buffered data " + buffer.size());

					// send'm and reset
					dataValue = buffer.items.array();
					buffer.resetBuffer();
				} else {
					return;
				}
			}
			Log.d(BleManager.LOG_TAG, "onCharacteristicChanged: " + BleManager.bytesToHex(dataValue)
					+ " from peripheral: " + device.getAddress());
			WritableMap map = Arguments.createMap();
			map.putString("peripheral", device.getAddress());
			map.putString("characteristic", charString);
			map.putString("service", service);
			map.putArray("value", BleManager.bytesToWritableArray(dataValue));
			sendEvent("BleManagerDidUpdateValueForCharacteristic", map);

		} catch (Exception e) {
			Log.d(BleManager.LOG_TAG, "onCharacteristicChanged ERROR: " + e.toString());
		}
	}

	@Override
	public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
		super.onCharacteristicRead(gatt, characteristic, status);

		mainHandler.post(() -> {
			if (status != BluetoothGatt.GATT_SUCCESS) {
				if (status == GATT_AUTH_FAIL || status == GATT_INSUFFICIENT_AUTHENTICATION) {
					Log.d(BleManager.LOG_TAG, "Read needs bonding");
				}
				readCallback.invoke("Error reading " + characteristic.getUuid() + " status=" + status, null);
				readCallback = null;
			} else if (readCallback != null) {
				final byte[] dataValue = copyOf(characteristic.getValue());
				readCallback.invoke(null, BleManager.bytesToWritableArray(dataValue));
				readCallback = null;
			}
			completedCommand();
		});

	}

	@Override
	public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
		super.onCharacteristicWrite(gatt, characteristic, status);

		mainHandler.post(() -> {
			if (writeQueue.size() > 0) {
				byte[] data = writeQueue.get(0);
				writeQueue.remove(0);
				doWrite(characteristic, data, writeCallback);
			} else if (status != BluetoothGatt.GATT_SUCCESS) {
				if (status == GATT_AUTH_FAIL || status == GATT_INSUFFICIENT_AUTHENTICATION) {
					Log.d(BleManager.LOG_TAG, "Write needs bonding");
					// *not* doing completedCommand()
					return;
				}
				writeCallback.invoke( "Error writing " + characteristic.getUuid() + " status=" + status, null);
				writeCallback = null;
			} else if (writeCallback != null) {
				writeCallback.invoke();
				writeCallback = null;
			}
			completedCommand();
		});
	}

	public void onDescriptorRead (BluetoothGatt gatt,
								  BluetoothGattDescriptor descriptor,
								  int status) {
		if (status != BluetoothGatt.GATT_SUCCESS) {
			if (status == GATT_AUTH_FAIL || status == GATT_INSUFFICIENT_AUTHENTICATION) {
				Log.d(BleManager.LOG_TAG, "Read needs bonding");
				// *not* doing completedCommand()
				return;
			}
			sendBackrError(readDescriptorCallback, "Error reading " + descriptor.getUuid() + " status=" + status);
		} else if (readDescriptorCallback != null) {
			final byte[] dataValue = copyOf(descriptor.getValue());
			final Callback callback = readDescriptorCallback;
			readDescriptorCallback = null;
			mainHandler.post(new Runnable() {
				@Override
				public void run() {
					callback.invoke(null, BleManager.bytesToWritableArray(dataValue));
				}
			});
		}

		completedCommand();
	}

	public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
		mainHandler.post(() -> {
			if (writeDescriptorCallback != null) {
				if (status == BluetoothGatt.GATT_SUCCESS) {
					writeDescriptorCallback.invoke();
					Log.d(BleManager.LOG_TAG, "onDescriptorWrite success");
				} else {
					writeDescriptorCallback.invoke("Error writing descriptor stats=" + status, null);
					Log.e(BleManager.LOG_TAG, "Error writing descriptor stats=" + status);
				}

				writeDescriptorCallback = null;
			} else if (registerNotifyCallback != null) {
					if (status == BluetoothGatt.GATT_SUCCESS) {
						registerNotifyCallback.invoke();
						Log.d(BleManager.LOG_TAG, "onDescriptorWrite success");
					} else {
						registerNotifyCallback.invoke("Error writing descriptor status=" + status, null);
						Log.e(BleManager.LOG_TAG, "Error writing descriptor status=" + status);
					}

				registerNotifyCallback = null;
			} else {
				Log.e(BleManager.LOG_TAG, "onDescriptorWrite with no callback");
			}

			completedCommand();
		});
	}

	@Override
	public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
		super.onReadRemoteRssi(gatt, rssi, status);

		mainHandler.post(() -> {
			if (readRSSICallback != null) {
				if (status == BluetoothGatt.GATT_SUCCESS) {
					updateRssi(rssi);
					readRSSICallback.invoke(null, rssi);
				} else {
					readRSSICallback.invoke("Error reading RSSI status=" + status, null);
				}

				readRSSICallback = null;
			}

			completedCommand();
		});
	}

	private String bufferedCharacteristicsKey(String serviceUUID, String characteristicUUID) {
		return serviceUUID + "-" + characteristicUUID;
	}

	private void clearBuffers() {
		for (Map.Entry<String, NotifyBufferContainer> entry : this.bufferedCharacteristics.entrySet())
			entry.getValue().resetBuffer();
	}

	private void setNotify(UUID serviceUUID, UUID characteristicUUID, final Boolean notify, Callback callback) {
		if (! isConnected() || gatt == null) {
			callback.invoke("Device is not connected", null);
			completedCommand();
			return;
		}

		BluetoothGattService service = gatt.getService(serviceUUID);
		final BluetoothGattCharacteristic characteristic = findNotifyCharacteristic(service, characteristicUUID);

		if (characteristic == null) {
			callback.invoke("Characteristic " + characteristicUUID + " not found");
			completedCommand();
			return;
		}

		if (! gatt.setCharacteristicNotification(characteristic, notify)) {
			callback.invoke("Failed to register notification for " + characteristicUUID);
			completedCommand();
			return;
		}

		final BluetoothGattDescriptor descriptor = characteristic.getDescriptor(UUIDHelper.uuidFromString(CHARACTERISTIC_NOTIFICATION_CONFIG));
		if (descriptor == null) {
			callback.invoke("Set notification failed for " + characteristicUUID);
			completedCommand();
			return;
		}

		// Prefer notify over indicate
		byte[] value;
		if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
			Log.d(BleManager.LOG_TAG, "Characteristic " + characteristicUUID + " set NOTIFY");
			value = notify ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
		} else if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
			Log.d(BleManager.LOG_TAG, "Characteristic " + characteristicUUID + " set INDICATE");
			value = notify ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
		} else {
			String msg = "Characteristic " + characteristicUUID + " does not have NOTIFY or INDICATE property set";
			Log.d(BleManager.LOG_TAG, msg);
			callback.invoke(msg);
			completedCommand();
			return;
		}
		final byte[] finalValue = notify ? value : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
		final Callback finalCallback = callback;

		boolean result = false;
		try {
			result = gatt.setCharacteristicNotification(characteristic, notify);
			// Then write to descriptor
			descriptor.setValue(finalValue);
			registerNotifyCallback = finalCallback;
			result &= gatt.writeDescriptor(descriptor);
		} catch(Exception e) {
			Log.d(BleManager.LOG_TAG, "Exception in setNotify", e);
		}

		if (! result) {
			callback.invoke( "writeDescriptor failed for descriptor: " + descriptor.getUuid(), null);
			registerNotifyCallback = null;
			completedCommand();
		}
	}

	public void registerNotify(UUID serviceUUID, UUID characteristicUUID, Integer buffer, Callback callback) {
		if (!enqueue(() -> {
			Log.d(BleManager.LOG_TAG, "registerNotify");
			if (buffer > 1) {
				Log.d(BleManager.LOG_TAG, "registerNotify using buffer");
				String bufferKey = this.bufferedCharacteristicsKey(serviceUUID.toString(), characteristicUUID.toString());
				this.bufferedCharacteristics.put(bufferKey, new NotifyBufferContainer(buffer));
			}
			this.setNotify(serviceUUID, characteristicUUID, true, callback);
		})) {
			Log.e(BleManager.LOG_TAG, "Could not enqueue setNotify command to register notify");
		}
	}

	public void removeNotify(UUID serviceUUID, UUID characteristicUUID, Callback callback) {
		if (!enqueue(() -> {
			Log.d(BleManager.LOG_TAG, "removeNotify");
			String bufferKey = this.bufferedCharacteristicsKey(serviceUUID.toString(), characteristicUUID.toString());
			if (this.bufferedCharacteristics.containsKey(bufferKey)) {
				NotifyBufferContainer buffer = this.bufferedCharacteristics.get(bufferKey);
				this.bufferedCharacteristics.remove(bufferKey);
			}
			this.setNotify(serviceUUID, characteristicUUID, false, callback);
		})) {
			Log.e(BleManager.LOG_TAG, "Could not enqueue setNotify command to remove notify");
		}
	}

	// Some devices reuse UUIDs across characteristics, so we can't use
	// service.getCharacteristic(characteristicUUID)
	// instead check the UUID and properties for each characteristic in the service
	// until we find the best match
	// This function prefers Notify over Indicate
	private BluetoothGattCharacteristic findNotifyCharacteristic(BluetoothGattService service,
																 UUID characteristicUUID) {

		try {
			// Check for Notify first
			List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
			for (BluetoothGattCharacteristic characteristic : characteristics) {
				if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
						&& characteristicUUID.equals(characteristic.getUuid())) {
					return characteristic;
				}
			}

			// If there wasn't Notify Characteristic, check for Indicate
			for (BluetoothGattCharacteristic characteristic : characteristics) {
				if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
						&& characteristicUUID.equals(characteristic.getUuid())) {
					return characteristic;
				}
			}

			// As a last resort, try and find ANY characteristic with this UUID, even if it
			// doesn't have the correct properties
			return service.getCharacteristic(characteristicUUID);
		} catch (Exception e) {
			Log.e(BleManager.LOG_TAG, "Error retriving characteristic " + characteristicUUID, e);
			return null;
		}
	}

	public void read(UUID serviceUUID, UUID characteristicUUID, final Callback callback) {
		enqueue(() -> {
			if (!isConnected() || gatt == null) {
				callback.invoke("Device is not connected", null);
				completedCommand();
				return;
			}

			BluetoothGattService service = gatt.getService(serviceUUID);
			final BluetoothGattCharacteristic characteristic = findReadableCharacteristic(service, characteristicUUID);

			if (characteristic == null) {
				callback.invoke("Characteristic " + characteristicUUID + " not found.", null);
				completedCommand();
				return;
			}

			readCallback = callback;
			if (!gatt.readCharacteristic(characteristic)) {
				callback.invoke("Read failed", null);
				readCallback = null;
				completedCommand();
			}

		});
	}

	public void readDescriptor(UUID serviceUUID, UUID characteristicUUID, UUID descriptorUUID, Callback callback) {

		if (!isConnected() || gatt == null) {
			callback.invoke("Device is not connected", null);
			return;
		}

		BluetoothGattService service = gatt.getService(serviceUUID);
		final BluetoothGattDescriptor descriptor = findDescriptor(service, characteristicUUID, descriptorUUID);

		if (descriptor == null) {
			callback.invoke("Descriptor " + descriptorUUID + " not found.", null);
			return;
		}

		final Callback reactcb = callback;
		boolean result = commandQueue.add(new Runnable() {
			@Override
			public void run() {
				readDescriptorCallback = reactcb;
				if (!gatt.readDescriptor(descriptor)) {
					readDescriptorCallback = null;
					sendBackrError(reactcb, "Read failed");
					completedCommand();
				}
			}
		});

		if (result) {
			nextCommand();
		} else {
			Log.d(BleManager.LOG_TAG, "Could not queue read descriptor command");
		}
	}

	private void sendBackrError(final Callback callback, final String message) {
		new Handler(Looper.getMainLooper()).post(new Runnable() {
				@Override
				public void run() {
					callback.invoke(message, null);
				}
			});
	}

	private byte[] copyOf(byte[] source) {
		if (source == null) return new byte[0];
		final int sourceLength = source.length;
		final byte[] copy = new byte[sourceLength];
		System.arraycopy(source, 0, copy, 0, sourceLength);
		return copy;
	}

	private boolean enqueue(Runnable command) {
		final boolean result = commandQueue.add(command);
		if (result) {
			nextCommand();
		} else {
			Log.d(BleManager.LOG_TAG, "could not enqueue command");
		}
		return result;
	}

	private void completedCommand() {
		commandQueue.poll();
		commandQueueBusy = false;
		nextCommand();
	}

	private void nextCommand() {
		synchronized (this) {
			if (commandQueueBusy) {
				Log.d(BleManager.LOG_TAG, "Command queue busy");
				return;
			}

			final Runnable nextCommand = commandQueue.peek();
			if (nextCommand == null) {
				Log.d(BleManager.LOG_TAG, "Command queue empty");
				return;
			}

			// Check if we still have a valid gatt object
			if (gatt == null) {
				Log.d(BleManager.LOG_TAG, "Error, gatt is null");
				commandQueue.clear();
				commandQueueBusy = false;
				return;
			}

			// Execute the next command in the queue
			commandQueueBusy = true;
			mainHandler.post(new Runnable() {
				@Override
				public void run() {
					try {
						nextCommand.run();
					} catch (Exception ex) {
						Log.d(BleManager.LOG_TAG, "Error, command exception");
						completedCommand();
					}
				}
			});
		}
	}

	public void readRSSI(final Callback callback) {
		if (!enqueue(() -> {
			if (!isConnected()) {
				callback.invoke("Device is not connected", null);
				completedCommand();
				return;
			} else if (gatt == null) {
				callback.invoke("BluetoothGatt is null", null);
				completedCommand();
				return;
			} else {
				readRSSICallback = callback;
				if (!gatt.readRemoteRssi()) {
					callback.invoke("Read RSSI failed", null);
					readRSSICallback = null;
					completedCommand();
				}
			}
		})) {
			Log.d(BleManager.LOG_TAG, "Could not queue readRemoteRssi command");
		}
	}

	public void refreshCache(Callback callback) {
		enqueue(() -> {
			try {
				Method localMethod = gatt.getClass().getMethod("refresh", new Class[0]);
				if (localMethod != null) {
					boolean res = ((Boolean) localMethod.invoke(gatt, new Object[0])).booleanValue();
					callback.invoke(null, res);
				} else {
					callback.invoke("Could not refresh cache for device.");
				}
			} catch (Exception localException) {
				Log.e(TAG, "An exception occured while refreshing device");
				callback.invoke(localException.getMessage());
			} finally {
				completedCommand();
			}
		});
	}

	public void retrieveServices(Callback callback) {
		enqueue(() -> {
			if (!isConnected()) {
				callback.invoke("Device is not connected", null);
				completedCommand();
				return;
			} else if (gatt == null) {
				callback.invoke("BluetoothGatt is null", null);
				completedCommand();
				return;
			} else {
				this.retrieveServicesCallback = callback;
				gatt.discoverServices();
			}
		});
	}

	// Some peripherals re-use UUIDs for multiple characteristics so we need to
	// check the properties
	// and UUID of all characteristics instead of using
	// service.getCharacteristic(characteristicUUID)
	private BluetoothGattCharacteristic findReadableCharacteristic(BluetoothGattService service,
																   UUID characteristicUUID) {

		if (service != null) {
			int read = BluetoothGattCharacteristic.PROPERTY_READ;

			List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
			for (BluetoothGattCharacteristic characteristic : characteristics) {
				if ((characteristic.getProperties() & read) != 0
						&& characteristicUUID.equals(characteristic.getUuid())) {
					return characteristic;
				}
			}

			// As a last resort, try and find ANY characteristic with this UUID, even if it
			// doesn't have the correct properties
			return service.getCharacteristic(characteristicUUID);
		}

		return null;
	}

	private BluetoothGattDescriptor findDescriptor(BluetoothGattService service, UUID characteristicUUID, UUID descriptorUUID) {
		if (service != null) {
			int read = BluetoothGattCharacteristic.PROPERTY_READ;

			List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
			for (BluetoothGattCharacteristic characteristic : characteristics) {

				if (characteristicUUID.equals(characteristic.getUuid())) {
					BluetoothGattDescriptor descriptor = characteristic.getDescriptor(descriptorUUID);
					if (descriptor != null) {
				    	return descriptor;
					}
				}
			}
		}

		return null;
	}

	public boolean doWrite(final BluetoothGattCharacteristic characteristic, byte[] data, final Callback callback) {
		final byte[] copyOfData = copyOf(data);
		return enqueue(new Runnable() {
			@Override
			public void run() {
				characteristic.setValue(copyOfData);
				if (characteristic.getWriteType() == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
					writeCallback = callback;
				else
					writeCallback = null;
				if (!gatt.writeCharacteristic(characteristic)) {
					// write without response, caller will handle the callback
					if (writeCallback != null) {
						writeCallback.invoke("Write failed", writeCallback);
						writeCallback = null;
					}
					completedCommand();
				}
			}
		});
	}

	public void write(UUID serviceUUID, UUID characteristicUUID, byte[] data, Integer maxByteSize, Integer queueSleepTime, Callback callback, int writeType) {
		enqueue(() -> {
			if (!isConnected() || gatt == null) {
				callback.invoke("Device is not connected", null);
				completedCommand();
				return;
			}

			BluetoothGattService service = gatt.getService(serviceUUID);
			BluetoothGattCharacteristic characteristic = findWritableCharacteristic(service, characteristicUUID, writeType);

			if (characteristic == null) {
				callback.invoke("Characteristic " + characteristicUUID + " not found.");
				completedCommand();
				return;
			}

			characteristic.setWriteType(writeType);

			if (data.length <= maxByteSize) {
				if (! doWrite(characteristic, data, callback)) {
					callback.invoke("Write failed");
				} else {
					if (BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE == writeType) {
						callback.invoke();
					}
				}
			} else {
				int dataLength = data.length;
				int count = 0;
				byte[] firstMessage = null;
				List<byte[]> splittedMessage = new ArrayList<>();

				while (count < dataLength && (dataLength - count > maxByteSize)) {
					if (count == 0) {
						firstMessage = Arrays.copyOfRange(data, count, count + maxByteSize);
					} else {
						byte[] splitMessage = Arrays.copyOfRange(data, count, count + maxByteSize);
						splittedMessage.add(splitMessage);
					}
					count += maxByteSize;
				}
				if (count < dataLength) {
					// Other bytes in queue
					byte[] splitMessage = Arrays.copyOfRange(data, count, data.length);
					splittedMessage.add(splitMessage);
				}

				if (BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT == writeType) {
					writeQueue.addAll(splittedMessage);
					if (! doWrite(characteristic, firstMessage, callback)) {
						writeQueue.clear();
						writeCallback = null;
						callback.invoke("Write failed");
					}
				} else {
					try {
						boolean writeError = false;
						if (! doWrite(characteristic, firstMessage, callback)) {
							writeError = true;
							callback.invoke("Write failed");
						}
						if (! writeError) {
							Thread.sleep(queueSleepTime);
							for (byte[] message : splittedMessage) {
								if (! doWrite(characteristic, message, callback)) {
									writeError = true;
									callback.invoke("Write failed");
									break;
								}
								Thread.sleep(queueSleepTime);
							}
							if (! writeError) {
								callback.invoke();
							}
						}
					} catch (InterruptedException e) {
						callback.invoke("Error during writing");
					}
				}
			}

			completedCommand();
		});
	}
	public boolean doDescriptorWrite(final BluetoothGattDescriptor descriptor, byte[] data, final Callback callback) {
		final byte[] copyOfData = copyOf(data);
		boolean result = commandQueue.add(new Runnable() {
			@Override
			public void run() {
				descriptor.setValue(copyOfData);
				writeDescriptorCallback = callback;
				if (!gatt.writeDescriptor(descriptor)) {
					sendBackrError(writeDescriptorCallback, "Write failed");
					writeDescriptorCallback = null;
					completedCommand();
				}
			}
		});

		if (result) {
			nextCommand();
		} else {
			Log.d(BleManager.LOG_TAG, "Could not queue write characteristic command");
		}

		return result;
	}
	public void writeDescriptor(UUID serviceUUID, UUID characteristicUUID, UUID descriptorUUID, byte[] data, Callback callback) {
		if (!isConnected() || gatt == null) {
			callback.invoke("Device is not connected", null);
			return;
		}

		BluetoothGattService service = gatt.getService(serviceUUID);
		BluetoothGattDescriptor descriptor = findDescriptor(service, characteristicUUID, descriptorUUID);

		if (descriptor == null) {
			callback.invoke("Descriptor " + descriptorUUID + " not found.");
			return;
		}

	 	if (!doDescriptorWrite(descriptor, data, callback)) {
			callback.invoke("Write failed");
			writeDescriptorCallback = null;
		}
	}


	public void requestConnectionPriority(int connectionPriority, Callback callback) {
		enqueue(() -> {
			if (gatt != null) {
				if (Build.VERSION.SDK_INT >= LOLLIPOP) {
					boolean status = gatt.requestConnectionPriority(connectionPriority);
					callback.invoke(null, status);
				} else {
					callback.invoke("Requesting connection priority requires at least API level 21", null);
				}
			} else {
				callback.invoke("BluetoothGatt is null", null);
			}

			completedCommand();
		});
	}

	public void requestMTU(int mtu, Callback callback) {
		enqueue(() -> {
			if (!isConnected()) {
				callback.invoke("Device is not connected", null);
				completedCommand();
				return;
			}

			if (gatt == null) {
				callback.invoke("BluetoothGatt is null", null);
				completedCommand();
				return;
			}

			if (Build.VERSION.SDK_INT >= LOLLIPOP) {
				requestMTUCallback = callback;
				if (!gatt.requestMtu(mtu)) {
					requestMTUCallback.invoke("Request MTU failed", null);
					requestMTUCallback = null;
					completedCommand();
				}
			} else {
				callback.invoke("Requesting MTU requires at least API level 21", null);
				completedCommand();
			}
		});
	}

	@Override
	public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
		super.onMtuChanged(gatt, mtu, status);
		mainHandler.post(() -> {
			if (requestMTUCallback != null) {
				if (status == BluetoothGatt.GATT_SUCCESS) {
					requestMTUCallback.invoke(null, mtu);
				} else {
					requestMTUCallback.invoke("Error requesting MTU status = " + status, null);
				}

				requestMTUCallback = null;
			}

			completedCommand();
		});
	}

	// Some peripherals re-use UUIDs for multiple characteristics so we need to
	// check the properties
	// and UUID of all characteristics instead of using
	// service.getCharacteristic(characteristicUUID)
	private BluetoothGattCharacteristic findWritableCharacteristic(BluetoothGattService service,
																   UUID characteristicUUID, int writeType) {
		try {
			// get write property
			int writeProperty = BluetoothGattCharacteristic.PROPERTY_WRITE;
			if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
				writeProperty = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE;
			}

			List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
			for (BluetoothGattCharacteristic characteristic : characteristics) {
				if ((characteristic.getProperties() & writeProperty) != 0
						&& characteristicUUID.equals(characteristic.getUuid())) {
					return characteristic;
				}
			}

			// As a last resort, try and find ANY characteristic with this UUID, even if it
			// doesn't have the correct properties
			return service.getCharacteristic(characteristicUUID);
		} catch (Exception e) {
			Log.e(BleManager.LOG_TAG, "Error on findWritableCharacteristic", e);
			return null;
		}
	}

	private String generateHashKey(BluetoothGattCharacteristic characteristic) {
		return generateHashKey(characteristic.getService().getUuid(), characteristic);
	}

	private String generateHashKey(UUID serviceUUID, BluetoothGattCharacteristic characteristic) {
		return String.valueOf(serviceUUID) + "|" + characteristic.getUuid() + "|" + characteristic.getInstanceId();
	}

}
