package co.epitre.aelf_lectures.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.util.Log;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import co.epitre.aelf_lectures.NetworkStatusMonitor;
import co.epitre.aelf_lectures.SyncPrefActivity;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Created by jean-tiare on 22/05/17.
 */

interface LectureFutureProgressListener {
    void onLectureLoaded(LecturesController.WHAT what, AelfDate when, List<LectureItem> lectures);
}

// Custom caching resolver
class AelfDns implements Dns {
    private static final String TAG = "AelfDns";
    private static final InetAddress[] fallbackAelfAddresses;
    private static LinkedHashMap<String, InetAddress[]> addressCache = new LinkedHashMap<>();

    static {
        InetAddress[] fallbackAelfAddressesCandidate;
        try {
            fallbackAelfAddressesCandidate = new InetAddress[]{
                    InetAddress.getByAddress(new byte[]{(byte) 149, (byte) 202, (byte) 174, (byte) 110}), // vps-aelf.epitre.co
                    InetAddress.getByAddress(new byte[]{(byte) 164, (byte) 132, (byte) 231, (byte) 241}), // sbg-01.prod.epitre.co
                    InetAddress.getByAddress(new byte[]{(byte) 51,  (byte) 255, (byte) 39,  (byte) 30})   // gra-01.prod.epitre.co
            };
        } catch (UnknownHostException e) {
            fallbackAelfAddressesCandidate = new InetAddress[]{};
        }
        fallbackAelfAddresses = fallbackAelfAddressesCandidate;
    }

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        if (hostname == null) {
            throw new UnknownHostException("hostname == null");
        }

        // Attempt cache based resolution
        synchronized (addressCache) {
            if (addressCache.containsKey(hostname)) {
                Log.d(TAG, "Resolved "+hostname+" from cache");
                return Arrays.asList(addressCache.get(hostname));
            }
        }

        // Attempt system based resolution
        try {
            InetAddress[] candidates = InetAddress.getAllByName(hostname);
            synchronized (addressCache) {
                Log.d(TAG, "Caching "+hostname);
                addressCache.put(hostname, candidates);
            }
            return Arrays.asList(candidates);
        } catch (UnknownHostException e) {
            // Nothing to do
        }

        // If all DNS are down / look broken / ... return a static / hard-coded list of IPs
        Log.e(TAG, "Failed to resolve '"+hostname+"' attempting fallback to static IP list");
        return lookupDefault(hostname);
    }

    private static List<InetAddress> lookupDefault(String hostname) throws UnknownHostException {
        switch (hostname) {
            case "api.app.epitre.co":
            case "beta.api.app.epitre.co":
                return Arrays.asList(fallbackAelfAddresses);
            default:
                throw new UnknownHostException(hostname);
        }
    }
}

// Load lecture from network. Bring cancel and timeout support
public class LectureFuture implements Future<List<LectureItem>> {
    public static final String API_ENDPOINT = "https://api.app.epitre.co";
    private static final String TAG = "LectureFuture";

    /**
     * Internal state
     */
    private long retryBudget = 3;
    private Context ctx;
    private SharedPreferences preference = null;
    private NetworkStatusMonitor networkStatusMonitor;
    public LecturesController.WHAT what;
    public AelfDate when;

    /**
     * HTTP Client
     */
    private long startTime;
    private Call call = null;
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS) // Was 60 seconds
            .writeTimeout  (60, TimeUnit.SECONDS) // Was 10 minutes
            .readTimeout   (60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .dns(new AelfDns())
            .build();

    /**
     * Pending result
     */
    private boolean cancelled = false;
    private Semaphore Work = new Semaphore(1);
    private IOException pendingIoException = null;
    private List<LectureItem> pendingLectures = null;
    private LectureFutureProgressListener listener;

    public LectureFuture(Context ctx, LecturesController.WHAT what, AelfDate when, LectureFutureProgressListener listener) throws IOException {
        this.listener = listener;
        this.what = what;
        this.when = when;
        this.ctx = ctx;

        // Grab preferences
        preference = PreferenceManager.getDefaultSharedPreferences(ctx);
        networkStatusMonitor = NetworkStatusMonitor.getInstance();

        // Mark work start
        startTime = System.nanoTime();
        try {
            Work.acquire();
        } catch (InterruptedException e) {
            throw new IOException(e);
        }

        // Build and start request
        startRequest();
    }

    private void startRequest()  {
        // Re-Init state
        this.pendingLectures = null;
        this.pendingIoException = null;

        // Build feed URL
        boolean pref_nocache = preference.getBoolean("pref_participate_nocache", false);
        String Url = buildUrl(what, when);
        Log.d(TAG, "Getting "+Url+" remaining attempts: "+retryBudget);

        // Build request + headers
        Request.Builder requestBuilder = new Request.Builder();
        requestBuilder.url(Url);
        if (pref_nocache) {
            requestBuilder.addHeader("x-aelf-nocache", "1");
        }
        Request request = requestBuilder.build();

        // Build and enqueue the call
        call = client.newCall(request);
        call.enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                pendingIoException = e;
                completeOrRetry();
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                IOException err = null;
                try {
                    onHttpResponse(response);
                } catch (IOException e) {
                    // Ignore this exception IF we are going to retry
                    err = e;
                } catch (XmlPullParserException e) {
                    // Parser exceptions tends to occur on connection error while parsing
                    err = new IOException(e);
                } finally {
                    completeOrRetry(err);
                }
            }
        });
    }

    //
    // Retry engine
    //

    private boolean canRetry() {
        if (pendingLectures != null) return false;
        if (cancelled)               return false;
        if (retryBudget <= 0)        return false;
        if (!networkStatusMonitor.isNetworkAvailable()) return false;
        return true;
    }

    private void completeOrRetry(IOException e) throws IOException {
        // If we can't retry, complete
        if(!canRetry()) {
            Work.release();
            if (e != null) {
                throw e;
            }
            return;
        }

        // Start retry
        retryBudget--;
        startRequest();
    }

    private void completeOrRetry() {
        try {
            completeOrRetry(null);
        } catch (IOException e) {
            // Can not fail as it only forwards the argument
        }
    }

    //
    // Future Interface
    //

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (isDone()) {
            return false;
        }
        Log.i(TAG, "Cancelling future");
        cancelled = true;
        call.cancel();
        return true;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public boolean isDone() {
        return cancelled || Work.availablePermits() > 0;
    }

    @Override
    public List<LectureItem> get() throws InterruptedException, ExecutionException {
        if (cancelled) {
            Log.i(TAG, "Getting cancelled result");
            throw new CancellationException();
        }

        // Wait for work to complete
        Work.acquire();
        Work.release();

        // Check for pending exception
        if (pendingIoException != null) {
            Log.i(TAG, "Getting exception result");
            throw new ExecutionException(pendingIoException);
        }

        // Return result
        Log.i(TAG, "Getting result");
        return pendingLectures;
    }

    @Override
    public List<LectureItem> get(long timeout, @NonNull TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        if (cancelled) {
            throw new CancellationException();
        }

        // Wait for work to complete
        if (!Work.tryAcquire(timeout, unit)) {
            throw  new TimeoutException();
        }
        Work.release();

        // Check for pending exception
        if (pendingIoException != null) {
            throw new ExecutionException(pendingIoException);
        }

        // Return result
        return pendingLectures;
    }

    //
    // HTTP Request callbacks
    //

    private void onHttpResponse(Response response) throws IOException, XmlPullParserException {
        // Grab response
        InputStream in = null;
        try {
            in = response.body().byteStream();
            pendingLectures = AelfRssParser.parse(in);
            if (pendingLectures == null) {
                Log.w(TAG, "Failed to load lectures from network");
            }
        } catch (XmlPullParserException e) {
            Log.e(TAG, "Failed to parse API result", e);
            pendingIoException = new IOException(e);
        } catch (IOException e) {
            Log.w(TAG, "Failed to load lectures from network");
            pendingIoException = e;
        } catch (Exception e) {
            pendingIoException = new IOException(e);
        } finally {
            if(in != null) {
                in.close();
            }
        }

        if (pendingIoException != null) {
            throw pendingIoException;
        }

        if (listener != null) {
            listener.onLectureLoaded(what, when, pendingLectures);
        }
    }

    //
    // Helpers
    //

    private String buildUrl(LecturesController.WHAT what, AelfDate when) {
        boolean pref_beta = preference.getBoolean("pref_participate_beta", false);
        String endpoint = preference.getString("pref_participate_server", "");
        String Url = "%s/%d/office/%s/%s.rss?region=%s";

        // If the URL was not overloaded, build it
        if (endpoint.equals("")) {
            endpoint = API_ENDPOINT;

            // If applicable, switch to beta
            if (pref_beta) {
                endpoint = endpoint.replaceAll("^(https?://)", "$1beta.");
            }
        }

        // Fill placeholders
        String region = preference.getString(SyncPrefActivity.KEY_PREF_REGION, "romain");
        Url = String.format(Locale.US, Url,
                endpoint,
                preference.getInt("version", -1),
                what.urlName(),
                when.toIsoString(),
                region
        );

        return Url;
    }
}
