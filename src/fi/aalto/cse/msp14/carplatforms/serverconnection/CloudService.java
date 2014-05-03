package fi.aalto.cse.msp14.carplatforms.serverconnection;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;

import fi.aalto.cse.msp14.carplatforms.exceptions.IllegalThreadUseException;
import fi.aalto.cse.msp14.carplatforms.obd2_client.BootStrapperTask;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.Process;
import android.util.Log;

/**
 * 
 * @author Maria
 */
public class CloudService extends Service implements ServerConnectionInterface {

	private static final String LOCK_TAG = "obd2datatocloud";
//	private static final String URI = "http://82.130.19.148:8090/test.php";
	private static final String URI = "http://10.0.10.11:8090/test.php";
	private static final String URI_SERVER = "http://ec2-54-186-67-231.us-west-2.compute.amazonaws.com:9000/addDataPoint";
	
	private PowerManager.WakeLock wakelock;
	private static long t = System.nanoTime();
	
	private boolean keepalive;
	private boolean waitingForConnection;
	
	private LinkedBlockingQueue<SaveDataMessage> messages;
	private ConnectionListener conStateBCListener;
	private CloudConnection cloud;
	private SaveDataMessage current;
	
	private static boolean started = false;
	
	// Messaging related stuff
	final Messenger messenger = new Messenger(new IncomingHandler());
	ArrayList<Messenger> statusListeners = new ArrayList<Messenger>(); // All status listeners

	// These are identifiers for messages.
	public static final int MSG_START = 0;
	public static final int MSG_STOP = 1;
	public static final int MSG_REGISTER_AS_STATUS_LISTENER = 2;
	public static final int MSG_UNREGISTER_AS_STATUS_LISTENER = 3;
	public static final int MSG_GET_XML = 4;
	public static final int MSG_MSG = 5;
	public static final int MSG_PUB_STATUS = 6;
	public static final int MSG_PUB_STATUS_TXT = 7;
	
	/**
	 * 
	 */
	public CloudService() {
		current = null;
		keepalive = true;
	}
	
	/**
	 * 
	 * @return
	 */
    public static boolean isRunning() {
        return started;
    }
	
	/**
	 * 
	 * @author Maria
	 *
	 */
	class IncomingHandler extends Handler { // Handler of incoming messages from clients.
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MSG_GET_XML:
            	// TODO Send response to msg.replyTo
                break;
            case MSG_MSG:
            	// TODO handle message
                break;
            case MSG_REGISTER_AS_STATUS_LISTENER:
            	System.out.println("SERVICE GOT MESSAGE " + msg.replyTo + " " + t);
            	statusListeners.add(msg.replyTo);
            	break;
            case MSG_UNREGISTER_AS_STATUS_LISTENER:
            	statusListeners.remove(msg.replyTo);
            	break;
            case MSG_STOP: // Not used
            	CloudService.this.fullStop();
            	break;
            case MSG_START: // Not used
            	CloudService.this.start();
            	break;
            default:
                super.handleMessage(msg);
            }
        }
    }
	
	/**
	 * Requests wake lock which keeps CPU running even if the device is otherwise sleeping: 
	 * that way data is fetched from OBD and sent to cloud even if the screen is shut.
	 */
	private void requestWakeLock() {
		PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
		wakelock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOCK_TAG);
		wakelock.acquire();
	}	

	@Override
	public void onCreate() {
		System.out.println("SERVICE ON CREATE " + started);
	    if (!started) {
		    started = true;
			messages = new LinkedBlockingQueue<SaveDataMessage>();
	
			// Create notification which tells that this service is running.
			NotificationManager mNotifyManager =
			        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
			Notification.Builder nBuild = 
			new Notification.Builder(this)
			    .setContentTitle("OBD2 client")
			    .setContentText("Service is running")
			    .setSmallIcon(android.R.drawable.ic_notification_overlay)
			    .setOngoing(true);
		    Notification noti = nBuild.build();
		    int id = 1;
		    String tag = "obd2ServerNotifyer";
		    mNotifyManager.notify(tag, id, noti);
		    // Notification created!

			requestWakeLock();
			cloud = new CloudConnection();
			Thread t = new Thread(cloud);
			t.setPriority(Process.THREAD_PRIORITY_BACKGROUND);
			t.start();
			
			new BootStrapperTask(this).execute();
		}
	}

	  
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		 Log.i("LocalService", "Received start id " + startId + ": " + intent);
		return START_STICKY;
	}

	@Override
	public IBinder onBind(Intent intent) {
		return messenger.getBinder();
	}

	@Override
	public void onDestroy() {
		System.out.println("ON DESTROY");
		NotificationManager mNotifyManager =
		        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		int id = 1;
	    String tag = "obd2ServerNotifyer";
	    mNotifyManager.cancel(tag, id);
		stop();
	    started = false;
	}

	public void start() {
		
	}
	
	/**
	 * 
	 */
	private void fullStop() {
		this.stopSelf();
	}
	/**
	 * 
	 */
	private void stop() {
		System.out.println("STOP CALLED!");
		keepalive = false;
		synchronized(cloud) {
			cloud.notify();
		}
		
		if (wakelock != null) {
			try {
				wakelock.release();
				wakelock = null;
			} catch (Exception e) {
				System.out.println(e.toString());
				// Do nothing
			}
		}
		if (this.conStateBCListener != null) {
			try {
				this.unregisterReceiver(conStateBCListener);
			} catch (Exception e) {
				// It was not registered after all, so, ignore this.
				System.out.println(e.toString());
			}
		}
	}
	
	@Override
	public void sendMessage(SaveDataMessage message) {
		this.messages.offer(message);
		synchronized(cloud) {
			cloud.notify();
		}
	}

	@Override
	public void connectionAvailable() {
		System.out.println("CONNECTION AVAILABLE");
		if (this.waitingForConnection) {
			System.out.println("Connection available");
			this.waitingForConnection = false;
			this.unregisterReceiver(conStateBCListener);
			conStateBCListener = null;
			synchronized(cloud) {
				this.cloud.notify();
			}
		}
	}
	
	/**
	 * Dynamically register connectivity listener.
	 */
	private void waitForConnection() {
		if (!waitingForConnection) {
			System.out.println("Wait for connection");
			this.waitingForConnection = true;
			conStateBCListener = new ConnectionListener(this);
			this.registerReceiver(conStateBCListener, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
		}
	}
	
	/**
	 * This is just a Runnable that takes care of the actual sending.
	 * @author Maria
	 *
	 */
	private final class CloudConnection implements Runnable {
		@Override
		public void run() {
			new Thread(new Runnable() {

				@Override
				public void run() {
					for (int i = 0; i < 120; i++) {
						try {
							Thread.sleep(1000);
							Log.i("TESTING_SERVICE", "SLEPT NOW " + i);
						} catch (Exception e) {
							
						}
					}
				}
				
			}).start();
			while (keepalive) {
				if (current == null) {
					current = CloudService.this.messages.poll();
				}
				if (current != null) { // If it is still null, then there is no use to send anything.
					try {
						System.out.println("Send ");
						HttpClient httpclient = new DefaultHttpClient();  
						HttpPost request = new HttpPost(URI);  
						request.setEntity(new StringEntity(current.toString()));
						HttpResponse response = httpclient.execute(request);
						if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
							current = null;
						} else {
							
							// Something happened. What TODO now? 
						}
					} catch (ClientProtocolException e) {
						// Protocol error. What can one do!
					} catch (IOException e) {
						// No connection!
						ConnectivityManager connMgr = (ConnectivityManager) CloudService.this.getSystemService(Context.CONNECTIVITY_SERVICE);
				        android.net.NetworkInfo wifi = connMgr
				                .getNetworkInfo(ConnectivityManager.TYPE_WIFI);
				        android.net.NetworkInfo mobile = connMgr
				                .getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
			        	System.out.println(!isAvailable(wifi) + " and " + !isAvailable(mobile));
				        if (!isAvailable(wifi) && !isAvailable(mobile)) {
				        	waitForConnection();
				        	System.out.println("Registered!");
				        }
					} catch (Exception e) {
						// Sending failed
						// Other exception
					}
				}
				while(keepalive && (CloudService.this.messages.isEmpty() || waitingForConnection)) {
					System.out.println("Wait");
					synchronized(cloud) {
						try {
							cloud.wait();
							System.out.println("Continue");
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}
				}
			}
			System.out.println("THREAD STOPPED");
		}

		/**
		 * 
		 * @param networkInfo
		 * @return
		 */
		private boolean isAvailable(NetworkInfo info) {
			if (info != null && info.isConnected()) {
				return true;
			}
			return false;
		}
	}

	/**
	 * Note! This method MUST be called from a separate thread!
	 * Otherwise it might block if network is slow or not available.
	 * @throws IllegalThreadUseException If this method is called for any reason from main thread.
	 */
	public void getXML() throws IllegalThreadUseException {
		if (Looper.myLooper() == Looper.getMainLooper()) {
			throw new IllegalThreadUseException("This method must NOT be called from UI thread!");
		}
		// Connect server and fetch XML specs
		HttpClient httpclient = new DefaultHttpClient();  
		HttpGet request = new HttpGet(URI + "");  
		try {
			HttpResponse response = httpclient.execute(request);
			if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
				// Yay! Now we got the wanted XML specs!
				// So. What exactly TODO now?
			} else {
				// Something happened. What TODO now? 
			}
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		} catch (ClientProtocolException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
