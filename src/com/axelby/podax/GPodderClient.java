package com.axelby.podax;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Vector;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.os.Handler;

import com.google.api.client.util.Base64;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

public class GPodderClient {

	private static class Config {
		public String mygpo = "https://gpodder.net/";
		public String mygpo_feedservice = "https://mygpo-feedservice.appspot.com/";
		public long update_timeout = 604800L;
	}
	
	private static Config _config;
	private static Calendar _configRefresh = null;

	static {
		verifyCurrentConfig();
	}

	public static void verifyCurrentConfig() {
		if (_configRefresh == null || _configRefresh.before(new GregorianCalendar())) {
			_config = retrieveGPodderConfig();

			// do NOT use basic auth over HTTP without SSL
			if (_config.mygpo.startsWith("http://"))
				_config.mygpo = "https://" + _config.mygpo.substring(7);
			if (_config.mygpo_feedservice.startsWith("http://"))
				_config.mygpo_feedservice = "https://" + _config.mygpo_feedservice.substring(7);

			_configRefresh = new GregorianCalendar();
			_configRefresh.add(Calendar.MILLISECOND, (int) _config.update_timeout);
		}
	}

	private static Config retrieveGPodderConfig() {
		Config config = new Config();

		try {
			URL url = new URL("http://gpodder.net/clientconfig.json");
			HttpURLConnection conn = (HttpURLConnection)url.openConnection();
			JsonReader reader = new JsonReader(new InputStreamReader(conn.getInputStream()));
			reader.beginObject();
			
			// get mygpo
			reader.nextName(); // should be mygpo
			reader.beginObject();
			reader.nextName(); // should be baseurl
			config.mygpo = reader.nextString();
			reader.endObject();

			// get mygpo-feedservice
			reader.nextName(); // should be mygpo-feedservice
			reader.beginObject();
			reader.nextName(); // should be baseurl
			config.mygpo_feedservice = reader.nextString();
			reader.endObject();

			// get update_timeout
			reader.nextName();
			config.update_timeout = reader.nextLong();

			reader.endObject();
			reader.close();
		} catch (IOException e) {
			e.printStackTrace();
		}

		return config;
	}

	private Context _context;
	private String _username;
	private String _password;
	private String _sessionId;

	public GPodderClient(Context context, String username, String password) {
		_context = context;
		_username = username;
		_password = password;
	}

	public Thread authorizeInBackground(final Handler handler,
			final GPodderAuthenticatorActivity activity) {
		final Runnable runnable = new Runnable() {
			public void run() {
				final boolean isValid = authenticate();
				if (handler == null || activity == null) {
					return;
				}
				handler.post(new Runnable() {
					public void run() {
						activity.onAuthenticationResult(isValid);
					}
				});
			}
		};
		final Thread t = new Thread() {
			@Override
			public void run() {
				try {
					runnable.run();
				} finally {

				}
			}
		};
		t.start();
		return t;
	}

	private void writePost(HttpsURLConnection conn, String toPost)
			throws IOException {
		conn.setDoOutput(true);
		OutputStream output = null;
		try {
		     output = conn.getOutputStream();
		     output.write(toPost.getBytes());
		} finally {
		     if (output != null) try { output.close(); } catch (IOException logOrIgnore) {}
		}
	}

	public HttpsURLConnection createConnection(URL url) throws IOException, Exception {
		TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {
			public java.security.cert.X509Certificate[] getAcceptedIssuers() {
				return new java.security.cert.X509Certificate[] {};
			}

			public void checkClientTrusted(X509Certificate[] chain,
					String authType) throws CertificateException {
			}

			public void checkServerTrusted(X509Certificate[] chain,
					String authType) throws CertificateException {
			}
		} };

		HttpsURLConnection conn;

		// Install the all-trusting trust manager
		SSLContext sc = SSLContext.getInstance("TLS");
		sc.init(null, trustAllCerts, new java.security.SecureRandom());
		HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
		
		conn = (HttpsURLConnection)url.openConnection();

		if (_sessionId == null) {
			// basic authentication
			String toBase64 = _username + ":" + _password;
			conn.addRequestProperty("Authorization", "basic " + new String(Base64.encode(toBase64.getBytes())));
		} else {
			conn.addRequestProperty("Cookie", "sessionid=" + _sessionId);
		}

		// gpodder cert does not resolve on android
		conn.setHostnameVerifier(new HostnameVerifier() {
			public boolean verify(String hostname, SSLSession session) {
				return true;
			}
		});

		return conn;
	}

	protected boolean authenticate() {
		verifyCurrentConfig();

		URL url;
		HttpsURLConnection conn = null;
		try {
			url = new URL(_config.mygpo + "api/2/auth/" + _username + "/login.json");
			conn = createConnection(url);
			writePost(conn, " ");

			conn.connect();

			int code = conn.getResponseCode();
			if (code != 200)
				return false;

			for (String val : conn.getHeaderFields().get("Set-Cookie")) {
				String[] data = val.split(";")[0].split("=");
				if (data[0].equals("sessionid"))
					_sessionId = data[1];
			}

			return true;
		} catch (IOException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			if (conn != null)
				conn.disconnect();
		}
		return true;
	}

	public Integer getSubscriptionChanges(long lastCheck) {
		verifyCurrentConfig();

		Integer timestamp = null;

		boolean anyInserted = false;
		URL url;
		HttpsURLConnection conn = null;
		try {
			url = new URL(_config.mygpo + "api/2/subscriptions/" + _username + "/podax.json?since=" + String.valueOf(lastCheck));
			conn = createConnection(url);

			conn.connect();

			int code = conn.getResponseCode();
			if (code != 200)
				return timestamp;

			InputStream stream = conn.getInputStream();
			JsonReader reader = new JsonReader(new InputStreamReader(stream));
			reader.beginObject();

			// get add
			while (reader.hasNext()) {
				String key = reader.nextName();
				if (key.equals("timestamp")) {
					timestamp = reader.nextInt();
				} else if (key.equals("add")) {
					reader.beginArray();
					while (reader.hasNext()) {
						String newUrl = reader.nextString();

						ContentValues values = new ContentValues();
						values.put(SubscriptionProvider.COLUMN_GPODDER_SYNCTIME, timestamp);
						// if we can update something then we already have it in the DB
						if (_context.getContentResolver().update(SubscriptionProvider.URI, values, "url = ?", new String[] { newUrl }) == 0) {
							// updated 0 records so do an insert
							values.put(SubscriptionProvider.COLUMN_URL, newUrl);
							_context.getContentResolver().insert(SubscriptionProvider.URI, values);
							anyInserted = true;
						}
					}
					reader.endArray();
				} else if (key.equals("remove")) {
					reader.beginArray();
					while (reader.hasNext()) {
						_context.getContentResolver().delete(SubscriptionProvider.URI, "url = ?", new String[] { reader.nextString() });
					}
					reader.endArray();
				}
			}

			reader.endObject();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			if (conn != null)
				conn.disconnect();
		}

		if (anyInserted)
			UpdateService.updateSubscriptions(_context);

		return timestamp;
	}

	public Integer sendSubscriptions() {
		verifyCurrentConfig();

		Integer timestamp = null;

		String[] projection = { SubscriptionProvider.COLUMN_URL };
		Cursor c = _context.getContentResolver().query(SubscriptionProvider.URI, projection, "gpodder_synctime IS NULL", null, null);
		Vector<String> toAdd = new Vector<String>();
		while (c.moveToNext())
			toAdd.add(c.getString(0));
		c.close();

		if (toAdd.size() == 0)
			return timestamp;

		URL url;
		HttpsURLConnection conn = null;
		try {
			url = new URL(_config.mygpo + "api/2/subscriptions/" + _username + "/podax.json");
			conn = createConnection(url);

			conn.setDoOutput(true);
			OutputStreamWriter streamWriter = new OutputStreamWriter(conn.getOutputStream());
			JsonWriter writer = new JsonWriter(streamWriter);
			writer.beginObject();

			writer.name("add");
			writer.beginArray();
			for (String s : toAdd)
				writer.value(s);
			writer.endArray();

			writer.name("remove");
			writer.beginArray();
			writer.endArray();

			writer.endObject();
			streamWriter.close();

			conn.connect();

			int code = conn.getResponseCode();
			if (code != 200)
				return timestamp;

			InputStream stream = conn.getInputStream();
			JsonReader reader = new JsonReader(new InputStreamReader(stream));

			reader.beginObject();
			while (reader.hasNext()) {
				String key = reader.nextName();
				if (key.equals("timestamp")) {
					timestamp = reader.nextInt();
				} else if (key.equals("update_urls")) {
					// this is an array of arrays with two elements
					// the first is the url passed in and the second is the proper santized url
					reader.beginArray();
					while (reader.hasNext()) {
						String oldUrl = reader.nextString();
						String newUrl = reader.nextString();

						ContentValues values = new ContentValues();
						values.put(SubscriptionProvider.COLUMN_GPODDER_SYNCTIME, timestamp);
						values.put(SubscriptionProvider.COLUMN_URL, newUrl);
						_context.getContentResolver().update(SubscriptionProvider.URI, values, "url = ?", new String[] { oldUrl });
						toAdd.remove(oldUrl);
					}
				}
			}

			// update the gpodder synctime
			for (String s : toAdd) {
				ContentValues values = new ContentValues();
				values.put(SubscriptionProvider.COLUMN_GPODDER_SYNCTIME, timestamp);
				_context.getContentResolver().update(SubscriptionProvider.URI, values, "url = ?", new String[] { s });
			}
		} catch (MalformedURLException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			c.close();
		}

		return timestamp;
	}

}
