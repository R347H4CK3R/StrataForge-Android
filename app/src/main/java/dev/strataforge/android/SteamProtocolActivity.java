package dev.strataforge.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.StatFs;
import android.view.Gravity;
import android.widget.*;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import in.dragonbra.javasteam.depotdownloader.DepotDownloader;
import in.dragonbra.javasteam.depotdownloader.IDownloadListener;
import in.dragonbra.javasteam.depotdownloader.data.AppItem;
import in.dragonbra.javasteam.depotdownloader.data.DownloadItem;
import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.steam.authentication.AuthPollResult;
import in.dragonbra.javasteam.steam.authentication.AuthSessionDetails;
import in.dragonbra.javasteam.steam.authentication.QrAuthSession;
import in.dragonbra.javasteam.steam.handlers.steamapps.License;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest;
import in.dragonbra.javasteam.steam.handlers.steamapps.SteamApps;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSProductInfoCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSProductInfo;
import in.dragonbra.javasteam.types.KeyValue;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.LicenseListCallback;
import in.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails;
import in.dragonbra.javasteam.steam.handlers.steamuser.SteamUser;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOffCallback;
import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager;
import in.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import android.os.SystemClock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class SteamProtocolActivity extends Activity implements IDownloadListener {
    private TextView state;
    private ImageView qrView;
    private EditText appIdInput;
    private Button downloadButton;
    private final AtomicBoolean running=new AtomicBoolean(false);
    private volatile boolean loggedIn=false;
    private volatile boolean downloading=false;
    private SteamClient steamClient;
    private SteamUser steamUser;
    private CallbackManager callbackManager;
    private List<License> licenses=Collections.emptyList();
    private volatile DepotDownloader currentDownload;
    private SteamApps steamApps;
    private LinearLayout gameList;
    private TextView libraryStatus;
    private Button syncButton;
    private ProgressBar downloadProgress;
    private ProgressBar scanProgress,hashProgress;
    private TextView scanLabel,hashLabel,transferLabel;
    private Button cancelDownloadButton;
    private final ScheduledExecutorService repairWatchdog=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"strataforge-repair-watchdog");t.setDaemon(true);return t;
    });
    private volatile long lastDownloadEvent;
    private volatile boolean cancelRequested=false;
    private volatile boolean stalledRecovery=false;
    private volatile int autoRetries=0;
    private static final long STALL_TIMEOUT=5*60*1000L;
    private TextView downloadLabel;
    private volatile String tokenAccount=null;
    private volatile String pendingRefreshToken=null;
    private volatile boolean attemptingSavedSession=false;
    private volatile boolean explicitSignOut=false;
    private int selectedAppId=0;
    private volatile int lastLoggedPercent=-10;
    private final ArrayList<PICSRequest> packageRequests=new ArrayList<>();
    private final ArrayList<PICSRequest> appRequests=new ArrayList<>();
    private final LinkedHashMap<Integer,String> ownedGames=new LinkedHashMap<>();
    private int packageCursor=0;
    private int appCursor=0;
    private boolean loadingPackages=false;
    private boolean loadingApps=false;
    private volatile boolean librarySyncing=false;

    private void show(String msg){
        runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())state.setText(msg);});
    }
    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16,12,16,12);root.setBackgroundColor(Color.rgb(13,18,27));
        ScrollView sc=new ScrollView(this);sc.addView(root);setContentView(sc);
        TextView heading=new TextView(this);heading.setText("STRATAFORGE  /  STEAM");
        heading.setTextColor(Color.WHITE);heading.setTextSize(22);root.addView(heading);
        TextView info=new TextView(this);info.setText("Your Steam library on Android. Sign in once with Steam Guard; StrataForge reconnects securely when reopened.");
        info.setTextColor(Color.LTGRAY);info.setTextSize(14);info.setPadding(0,12,0,12);root.addView(info);
        root.addView(makeButton("Sign in / Reconnect to Steam",()->connect()));
        state=new TextView(this);state.setText("Not connected");state.setTextColor(Color.WHITE);
        state.setTextSize(16);state.setPadding(0,12,0,12);root.addView(state);
        qrView=new ImageView(this);
        qrView.setAdjustViewBounds(true);
        qrView.setMaxHeight(460);
        qrView.setVisibility(android.view.View.GONE);
        root.addView(qrView,new LinearLayout.LayoutParams(-1,330));
        TextView libraryHeader=new TextView(this);
        libraryHeader.setText("STEAM  /  LIBRARY");
        libraryHeader.setTextColor(Color.WHITE);
        libraryHeader.setTextSize(21);
        libraryHeader.setPadding(0,12,0,4);
        root.addView(libraryHeader);
        libraryStatus=new TextView(this);
        libraryStatus.setTextColor(Color.LTGRAY);
        libraryStatus.setText("Sign in to load your games.");
        root.addView(libraryStatus);
        syncButton=makeButton("Refresh owned games",()->loadLibrary());
        syncButton.setEnabled(false);
        root.addView(syncButton);
        gameList=new LinearLayout(this);
        gameList.setOrientation(LinearLayout.VERTICAL);
        root.addView(gameList);
        TextView downloadsHeading=new TextView(this);
        downloadsHeading.setText("DOWNLOADS");
        downloadsHeading.setTextColor(Color.WHITE);downloadsHeading.setTextSize(21);
        downloadsHeading.setPadding(0,16,0,4);root.addView(downloadsHeading);
        TextView stages=new TextView(this);stages.setText("REPAIR STAGES");stages.setTextSize(17);
        stages.setTextColor(Color.WHITE);root.addView(stages);
        scanLabel=new TextView(this);scanLabel.setText("1. Scan existing files — idle");
        scanLabel.setTextColor(Color.LTGRAY);root.addView(scanLabel);
        scanProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        scanProgress.setMax(1000);scanProgress.setProgress(0);root.addView(scanProgress,new LinearLayout.LayoutParams(-1,12));
        hashLabel=new TextView(this);
        hashLabel.setText("2. Hash local files — idle (not Steam manifest verification)");
        hashLabel.setTextColor(Color.LTGRAY);root.addView(hashLabel);
        hashProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        hashProgress.setMax(1000);hashProgress.setProgress(0);root.addView(hashProgress,new LinearLayout.LayoutParams(-1,12));
        transferLabel=new TextView(this);transferLabel.setText("3. Steam depot transfer — idle");
        transferLabel.setTextColor(Color.LTGRAY);root.addView(transferLabel);
        downloadProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        downloadProgress.setMax(1000);downloadProgress.setProgress(0);
        downloadProgress.setVisibility(android.view.View.GONE);
        root.addView(downloadProgress,new LinearLayout.LayoutParams(-1,14));
        downloadLabel=new TextView(this);
        downloadLabel.setText("No active downloads");downloadLabel.setTextSize(14);
        downloadLabel.setTextColor(Color.LTGRAY);downloadLabel.setPadding(0,8,0,12);
        root.addView(downloadLabel);
        cancelDownloadButton=makeButton("Cancel active repair (keep files)",()->cancelRepair());
        cancelDownloadButton.setEnabled(false);root.addView(cancelDownloadButton);
        repairWatchdog.scheduleWithFixedDelay(()->{
            if(!downloading||cancelRequested||stalledRecovery||currentDownload==null)return;
            long elapsed=SystemClock.elapsedRealtime()-lastDownloadEvent;
            if(elapsed<STALL_TIMEOUT)return;
            stalledRecovery=true;
            Diagnostics.error("repair-stall",new IllegalStateException(
              "No Steam downloader event for "+elapsed/1000+" seconds, AppID "+selectedAppId));
            runOnUiThread(()->{
                transferLabel.setText("3. Transfer stalled — restarting once if safe");
                downloadLabel.setText("No Steam response for 5 minutes. Recovering current session...");
            });
            if(autoRetries<1){
                autoRetries++;
                DepotDownloader active=currentDownload;
                if(active!=null)try{active.close();}catch(Throwable ex){Diagnostics.error("repair-close",ex);}
            }else{
                runOnUiThread(()->transferLabel.setText("3. Still stalled. Cancel, reconnect and retry manually."));
            }
        },30,30,TimeUnit.SECONDS);
        appIdInput=new EditText(this);appIdInput.setTextColor(Color.WHITE);appIdInput.setHintTextColor(Color.LTGRAY);
        appIdInput.setHint("Steam game AppID (e.g. 440)");appIdInput.setInputType(2);
        root.addView(appIdInput);
        downloadButton=makeButton("Download owned game (Windows files)",()->confirmDownload());
        downloadButton.setEnabled(false);root.addView(downloadButton);
        root.addView(makeButton("Sign out of Steam",()->new AlertDialog.Builder(this)
            .setTitle("Sign out of Steam?")
            .setMessage("Forget the saved Steam session on this tablet?")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Sign out",(dialog,which)->signOut())
            .show()));
        connectIfSaved();
        TextView note=new TextView(this);note.setText("Downloaded PC games are files only. Windows-game execution is not implemented. Games install to Android/data/dev.strataforge.android/files/steamapps. Verify free space before downloading.");
        note.setTextColor(Color.LTGRAY);note.setPadding(0,12,0,0);root.addView(note);
    }
    private Button makeButton(String s,Runnable action){Button b=new Button(this);b.setAllCaps(false);b.setText(s);b.setOnClickListener(v->action.run());return b;}
    private void connectIfSaved(){
        try{
            String[] saved=SteamSessionStore.read(this);
            if(saved!=null){
                state.setText("Restoring Steam session for "+saved[0]+"...");
                connect();
            }
        }catch(Exception ex){
            SteamSessionStore.clear(this);
            state.setText("Saved Steam session unavailable. Scan Steam Guard to sign in.");
        }
    }
    private void connect(){
        if(running.getAndSet(true)){show("Already connecting or connected");return;}
        explicitSignOut=false;
        show("Connecting to Steam network...");
        new Thread(()->{
            try {
                steamClient=new SteamClient();
                steamUser=steamClient.getHandler(SteamUser.class);
                steamApps=steamClient.getHandler(SteamApps.class);
                callbackManager=new CallbackManager(steamClient);
                callbackManager.subscribe(ConnectedCallback.class,c->onSteamConnected());
                callbackManager.subscribe(DisconnectedCallback.class,c->{
                    loggedIn=false;
                    runOnUiThread(()->{downloadButton.setEnabled(false);syncButton.setEnabled(false);});
                    if(explicitSignOut || c.isUserInitiated()){
                        running.set(false);show("Steam disconnected");
                    }else{
                        show("Steam connection lost. Reconnecting...");
                        new Thread(()->{
                            try{Thread.sleep(1500);if(running.get()&&steamClient!=null)steamClient.connect();}
                            catch(Exception e){show("Reconnect failed: "+e.getMessage());}
                        },"steam-reconnect").start();
                    }
                });
                callbackManager.subscribe(LoggedOnCallback.class,this::onLoggedOn);
                callbackManager.subscribe(LoggedOffCallback.class,c->{loggedIn=false;show("Signed out: "+c.getResult());runOnUiThread(()->downloadButton.setEnabled(false));});
                callbackManager.subscribe(LicenseListCallback.class,this::onLicenseList);
                callbackManager.subscribe(PICSProductInfoCallback.class,this::onPicsInfo);
                steamClient.connect();
                while(running.get()){callbackManager.runWaitCallbacks(1000L);}
            } catch(Throwable e) {show("Steam connection error: "+e.getClass().getSimpleName()+": "+e.getMessage());running.set(false);}
        },"steam-callbacks").start();
    }
    private void onSteamConnected(){
        try{
            String[] session=SteamSessionStore.read(this);
            if(session!=null){
                attemptingSavedSession=true;
                tokenAccount=session[0];
                LogOnDetails details=new LogOnDetails();
                details.setUsername(session[0]);details.setAccessToken(session[1]);
                details.setLoginID(20332);
                show("Signing in as "+session[0]+"...");
                steamUser.logOn(details);
                return;
            }
        }catch(Exception error){
            SteamSessionStore.clear(this);
            show("Saved Steam login expired. Starting Steam Guard...");
        }
        attemptingSavedSession=false;
        beginQrAuthentication();
    }
    private void beginQrAuthentication(){
        show("Requesting Steam Guard QR login...");
        new Thread(()->{
            try {
                AuthSessionDetails auth=new AuthSessionDetails();
                QrAuthSession session=steamClient.getAuthentication().beginAuthSessionViaQR(auth).get();
                session.setChallengeUrlChanged(changed->showQr(changed.getChallengeUrl()));
                showQr(session.getChallengeUrl());
                AuthPollResult result=session.pollingWaitForResult().get();
                LogOnDetails login=new LogOnDetails();
                login.setUsername(result.getAccountName());
                login.setAccessToken(result.getRefreshToken());
                login.setLoginID(20332);
                tokenAccount=result.getAccountName();
                pendingRefreshToken=result.getRefreshToken();
                show("Steam Guard approved. Logging in...");
                steamUser.logOn(login);
            } catch(Exception e){show("Steam Guard authentication failed: "+e.getClass().getSimpleName()+": "+e.getMessage());}
        },"steam-auth").start();
    }
    private void showQr(String url){
        if(url==null || !url.startsWith("https://"))return;
        try {
            BitMatrix matrix=new QRCodeWriter().encode(url,BarcodeFormat.QR_CODE,440,440);
            int w=matrix.getWidth(),h=matrix.getHeight();Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
            for(int y=0;y<h;y++)for(int x=0;x<w;x++)bitmap.setPixel(x,y,matrix.get(x,y)?Color.BLACK:Color.WHITE);
            runOnUiThread(()->{
                if(!isDestroyed()){
                    qrView.setVisibility(android.view.View.VISIBLE);
                    qrView.setImageBitmap(bitmap);
                    state.setText("Scan this code in Steam Guard and approve sign-in.");
                }
            });
        } catch(Exception e){show("Unable to display Steam QR code: "+e.getMessage());}
    }
    private void onLoggedOn(LoggedOnCallback c){
        if(c.getResult()!=EResult.OK){
            Diagnostics.log("steam-auth","Login rejected: "+c.getResult());
            if(attemptingSavedSession){
                attemptingSavedSession=false;
                SteamSessionStore.clear(this);
                show("Saved Steam login expired. Requesting Steam Guard...");
                beginQrAuthentication();
            }else show("Steam login rejected: "+c.getResult());
            return;
        }
        attemptingSavedSession=false;
        loggedIn=true;
        Diagnostics.log("steam-auth","Authenticated Steam session");
        if(pendingRefreshToken!=null && tokenAccount!=null){
            try{SteamSessionStore.save(this,tokenAccount,pendingRefreshToken);}
            catch(Exception e){show("Steam connected, but secure session storage failed");}
            finally{pendingRefreshToken=null;}
        }
        runOnUiThread(()->{qrView.setVisibility(android.view.View.GONE);qrView.setImageDrawable(null);});
        show("Steam signed in as "+(tokenAccount==null?"your account":tokenAccount)+". Loading library...");
    }
    private void onLicenseList(LicenseListCallback c){
        if(c.getResult()!=EResult.OK){show("Could not load Steam entitlements: "+c.getResult());return;}
        licenses=c.getLicenseList();
        Diagnostics.log("steam-library","Received "+licenses.size()+" package licenses");
        show("Steam authenticated. "+licenses.size()+" licenses loaded.");
        runOnUiThread(()->{downloadButton.setEnabled(true);syncButton.setEnabled(true);});
        loadLibrary();
    }
    private void loadLibrary(){
        if(!loggedIn||steamApps==null||licenses.isEmpty()){
            show("Sign into Steam and wait for licenses before syncing your library.");
            return;
        }
        if(librarySyncing){show("Library sync is already running.");return;}
        librarySyncing=true;
        loadingPackages=true;loadingApps=false;
        packageCursor=0;appCursor=0;
        packageRequests.clear();appRequests.clear();ownedGames.clear();
        Set<Integer> uniquePackages=new LinkedHashSet<>();
        for(License l:licenses)if(l.getPackageID()>0)uniquePackages.add(l.getPackageID());
        for(Integer id:uniquePackages)packageRequests.add(new PICSRequest(id));
        runOnUiThread(()->{gameList.removeAllViews();libraryStatus.setText("Resolving "+packageRequests.size()+" owned Steam packages...");syncButton.setEnabled(false);});
        nextPackageBatch();
    }
    private void nextPackageBatch(){
        if(!loadingPackages)return;
        if(packageCursor>=packageRequests.size()){
            loadingPackages=false;loadingApps=true;
            appRequests.clear();
            for(Integer id:ownedGames.keySet())appRequests.add(new PICSRequest(id));
            appCursor=0;
            runOnUiThread(()->libraryStatus.setText("Found "+ownedGames.size()+" game IDs. Loading game names..."));
            nextAppBatch();
            return;
        }
        int end=Math.min(packageCursor+40,packageRequests.size());
        ArrayList<PICSRequest> batch=new ArrayList<>(packageRequests.subList(packageCursor,end));
        packageCursor=end;
        try{steamApps.picsGetProductInfo(Collections.emptyList(),batch);}
        catch(Throwable t){finishLibrary("Steam package lookup failed: "+t.getMessage());}
    }
    private void nextAppBatch(){
        if(!loadingApps)return;
        if(appCursor>=appRequests.size()){
            loadingApps=false;
            finishLibrary("Library synced: "+ownedGames.size()+" games found.");
            return;
        }
        int end=Math.min(appCursor+40,appRequests.size());
        ArrayList<PICSRequest> batch=new ArrayList<>(appRequests.subList(appCursor,end));
        appCursor=end;
        try{steamApps.picsGetProductInfo(batch,Collections.emptyList());}
        catch(Throwable t){finishLibrary("Steam game details failed: "+t.getMessage());}
    }
    private void onPicsInfo(PICSProductInfoCallback callback){
        try{
            if(loadingPackages){
                for(Map.Entry<Integer,PICSProductInfo> record:callback.getPackages().entrySet()){
                    KeyValue root=record.getValue().getKeyValues();
                    KeyValue appIds=root.get("appids");
                    for(KeyValue child:appIds.getChildren()){
                        String value=child.getValue();
                        if(value==null||!value.matches("[0-9]+"))value=child.getName();
                        try{int id=Integer.parseInt(value);if(id>0)ownedGames.put(id,"Game "+id);}catch(Exception ignored){}
                    }
                }
                if(!callback.isResponsePending())nextPackageBatch();
                return;
            }
            if(loadingApps){
                for(Map.Entry<Integer,PICSProductInfo> record:callback.getApps().entrySet()){
                    KeyValue root=record.getValue().getKeyValues();
                    String name=root.get("common").get("name").getValue();
                    if(name==null||name.trim().isEmpty())name="Game "+record.getKey();
                    if(ownedGames.containsKey(record.getKey()))ownedGames.put(record.getKey(),name);
                }
                if(!callback.isResponsePending())nextAppBatch();
            }
        }catch(Throwable t){finishLibrary("Library sync error: "+t.getMessage());}
    }
    private void finishLibrary(String message){
        librarySyncing=false;loadingPackages=false;loadingApps=false;
        show(message);
        runOnUiThread(()->{
            if(isFinishing()||isDestroyed())return;
            syncButton.setEnabled(loggedIn);
            gameList.removeAllViews();
            ArrayList<Map.Entry<Integer,String>> games=new ArrayList<>(ownedGames.entrySet());
            games.sort((a,b)->a.getValue().compareToIgnoreCase(b.getValue()));
            libraryStatus.setText(message);
            if(games.isEmpty()){
                TextView empty=new TextView(this);
                empty.setText("No game titles resolved. Try Refresh owned games or check Steam account licenses.");
                empty.setTextColor(Color.LTGRAY);
                gameList.addView(empty);
            }
            for(Map.Entry<Integer,String> game:games){
                int id=game.getKey();
                LinearLayout tile=new LinearLayout(this);
                tile.setOrientation(LinearLayout.HORIZONTAL);
                tile.setGravity(android.view.Gravity.CENTER_VERTICAL);
                tile.setPadding(12,12,12,12);
                tile.setBackgroundColor(Color.rgb(28,36,52));
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
                lp.setMargins(0,4,0,4);
                gameList.addView(tile,lp);
                TextView title=new TextView(this);
                title.setText(game.getValue()+"\n"+GameInstallStore.status(getApplicationContext(),id)+" | Steam AppID "+id);
                title.setTextColor(Color.WHITE);
                title.setTextSize(15);
                tile.addView(title,new LinearLayout.LayoutParams(0,-2,1));
                Button install=makeButton("Install",()->{
                    selectedAppId=id;
                    appIdInput.setText(String.valueOf(id));
                    confirmDownload();
                });
                tile.addView(install);
                Button verify=new Button(this);
                verify.setText("Verify / Repair");
                verify.setOnClickListener(v->{
                    String status=GameInstallStore.verify(getApplicationContext(),id);
                    new AlertDialog.Builder(this).setTitle("Steam AppID "+id)
                      .setMessage(status+"\n\nRepair uses the Steam downloader and preserves existing downloaded files.")
                      .setNegativeButton("Cancel",null)
                      .setPositiveButton("Repair",(dialog,which)->{
                        selectedAppId=id;appIdInput.setText(String.valueOf(id));confirmDownload();
                      }).show();
                });
                tile.addView(verify);
            }
        });
    }
    private void confirmDownload(){
        if(!loggedIn || licenses.isEmpty()){show("Connect and load licenses first.");return;}
        int appId;
        try{appId=Integer.parseInt(appIdInput.getText().toString().trim());if(appId<=0)throw new Exception();}
        catch(Exception ex){show("Enter a valid numeric Steam AppID");return;}
        if(downloading){show("Another download is already running.");return;}
        autoRetries=0;
        File dir=new File(getExternalFilesDir(null),"steamapps");
        if(!dir.exists()&&!dir.mkdirs()){show("Cannot create game storage directory");return;}
        StatFs stats=new StatFs(dir.getAbsolutePath());
        long free=stats.getAvailableBytes();
        new AlertDialog.Builder(this).setTitle("Download Steam game "+appId)
            .setMessage("Available storage: "+(free/1048576)+" MiB. Game size is not yet known. Steam may reject games your account does not own. Download Windows PC game files here? This will use mobile data or Wi-Fi.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Download",(dialog,which)->{
                downloadButton.setEnabled(false);
                show("Checking download prerequisites...");
                new Thread(()->{
                    RuntimeHealth.Result health=RuntimeHealth.preflight(getApplicationContext(),dir);
                    runOnUiThread(()->{
                        downloadButton.setEnabled(true);
                        if(health.ok)download(appId,dir);
                        else show(health.detail);
                    });
                },"steam-preflight").start();
            }).show();
    }
    private void cancelRepair(){
        if(!downloading)return;
        cancelRequested=true;
        Diagnostics.log("repair","User requested safe cancellation for AppID "+selectedAppId);
        transferLabel.setText("3. Cancel requested — preserving partial files");
        DepotDownloader active=currentDownload;
        if(active!=null)new Thread(()->{
            try{active.close();}catch(Throwable ex){Diagnostics.error("repair-cancel",ex);}
        },"repair-cancel").start();
    }
    private void scanAndHash(int appId){
        File folder=GameInstallStore.findGame(getApplicationContext(),appId);
        ArrayList<File> files=new ArrayList<>();
        if(folder!=null){
            ArrayDeque<File> pending=new ArrayDeque<>();pending.add(folder);
            while(!pending.isEmpty()){
                if(cancelRequested)return;
                File next=pending.removeFirst();
                File[] children=next.listFiles();
                if(children==null)continue;
                for(File child:children){
                    if(child.isDirectory())pending.add(child);else if(child.isFile())files.add(child);
                }
                final int discovered=files.size();
                runOnUiThread(()->scanLabel.setText("1. Scanning: "+discovered+" files identified"));
            }
        }
        long bytes=0;for(File f:files)bytes+=f.length();
        final long totalBytes=bytes;final int count=files.size();
        runOnUiThread(()->{
            scanProgress.setIndeterminate(false);
            scanProgress.setProgress(1000);
            scanLabel.setText("1. Scan complete: "+count+" files / "+GameInstallStore.size(totalBytes));
            hashLabel.setText("2. Calculating local SHA-1 checksums (Steam manifest not yet compared)");
        });
        long hashed=0;int completed=0;
        byte[] buffer=new byte[64*1024];
        for(File f:files){
            if(cancelRequested)return;
            try(FileInputStream stream=new FileInputStream(f)){
                MessageDigest sha=MessageDigest.getInstance("SHA-1");
                int n;while((n=stream.read(buffer))!=-1){
                    if(cancelRequested)return;
                    sha.update(buffer,0,n);hashed+=n;
                }
                sha.digest();completed++;
            }catch(Exception failure){Diagnostics.error("repair-hash",failure);}
            final int pct=totalBytes==0?1000:(int)Math.min(1000,hashed*1000/totalBytes);
            final int done=completed;
            runOnUiThread(()->{
                hashProgress.setProgress(pct);
                hashLabel.setText("2. Local hash audit "+done+"/"+count+" files ("+(pct/10)+"%)");
            });
        }
        runOnUiThread(()->{
            hashProgress.setProgress(1000);
            hashLabel.setText("2. Local SHA-1 audit complete; Steam will handle depot validation");
            transferLabel.setText("3. Preparing Steam depots; waiting for transfer events");
        });
        Diagnostics.log("repair","Local audit completed for "+appId+": "+count+" files "+GameInstallStore.size(totalBytes));
    }
    private void download(int appId,File dir){
        if(downloading){show("A download is already running");return;}
        downloading=true;cancelRequested=false;stalledRecovery=false;
        lastDownloadEvent=SystemClock.elapsedRealtime();
        RuntimeHealth.startDownload(appId);
        selectedAppId=appId;
        lastLoggedPercent=-10;
        GameInstallStore.completed(getApplicationContext(),appId,false);
        Diagnostics.log("game-"+appId,"Download requested");
        runOnUiThread(()->{
            downloadProgress.setVisibility(android.view.View.VISIBLE);
            downloadProgress.setIndeterminate(true);
            downloadLabel.setText("Preparing Steam AppID "+appId+"...");
        });
        runOnUiThread(()->{
            scanProgress.setIndeterminate(true);
            hashProgress.setIndeterminate(false);
            scanProgress.setProgress(0);hashProgress.setProgress(0);
            scanLabel.setText("1. Starting file scan...");
            hashLabel.setText("2. Waiting for scan...");
            transferLabel.setText("3. Waiting for local audit...");
            cancelDownloadButton.setEnabled(true);
        });
        new Thread(()->{
            try{
                scanAndHash(appId);
                if(cancelRequested){
                    downloading=false;
                    RuntimeHealth.finishDownload();
                    runOnUiThread(()->{
                        cancelDownloadButton.setEnabled(false);
                        transferLabel.setText("3. Cancelled before Steam transfer; files preserved");
                    });
                    return;
                }
            }catch(Throwable e){Diagnostics.error("repair-scan",e);}
            try(DepotDownloader downloader=new DepotDownloader(steamClient,licenses,true)){
                currentDownload=downloader;
                downloader.addListener(this);
                AppItem item=new AppItem(
                    appId,true,dir.getAbsolutePath(),"public","",false,
                    "windows",false,"64",false,"english",false,
                    Collections.emptyList(),Collections.emptyList(),true,false
                );
                downloader.add(item);
                downloader.finishAdding();
                show("Downloading Steam game "+appId+"...");
                downloader.awaitCompletion();
                downloader.removeListener(this);
            }catch(Throwable e){
                if(cancelRequested){
                    Diagnostics.log("repair","Cancelled AppID "+appId+"; files kept");
                }else Diagnostics.error("game-"+appId,e);
                if(!cancelRequested && !stalledRecovery && RuntimeHealth.recoverable(e)){
                    show("Temporary download failure. Retry is available; partial verified files are preserved.");
                    Diagnostics.log("health-fallback","Transient network failure detected for game "+appId+"; manual retry offered");
                    runOnUiThread(()->{
                        new AlertDialog.Builder(this).setTitle("Resume interrupted download?")
                            .setMessage("A temporary connection error occurred. Steam will verify existing files when restarted. Retry?")
                            .setNegativeButton("Later",null)
                            .setPositiveButton("Retry",(dialog,which)->{
                                RuntimeHealth.Result health=RuntimeHealth.retryCheck(getApplicationContext(),dir);
                                if(health.ok)download(appId,dir);else show(health.detail);
                            }).show();
                    });
                }else show("Steam download error: "+e.getClass().getSimpleName()+": "+e.getMessage());
            }finally{
                currentDownload=null;downloading=false;RuntimeHealth.finishDownload();
                runOnUiThread(()->cancelDownloadButton.setEnabled(false));
                if(stalledRecovery&&!cancelRequested&&autoRetries==1){
                    stalledRecovery=false;autoRetries=2;
                    RuntimeHealth.Result health=RuntimeHealth.preflight(getApplicationContext(),dir);
                    if(health.ok)runOnUiThread(()->download(appId,dir));
                    else show("Auto-repair blocked: "+health.detail);
                }
            }
        },"steam-depot").start();
    }
    @Override public void onDownloadStarted(DownloadItem item){
        lastDownloadEvent=SystemClock.elapsedRealtime();
        RuntimeHealth.progress();
        show("Steam download started: "+item.getAppId());
        Diagnostics.log("game-"+item.getAppId(),"Download started");
        runOnUiThread(()->{
            downloadProgress.setVisibility(android.view.View.VISIBLE);
            downloadProgress.setIndeterminate(true);
            downloadLabel.setText("Downloading game "+item.getAppId()+": preparing depot files...");
        });
    }
    @Override public void onDownloadCompleted(DownloadItem item){
        show("Steam download completed for AppID "+item.getAppId());
        lastDownloadEvent=SystemClock.elapsedRealtime();
        Diagnostics.log("game-"+item.getAppId(),"Download completed");
        runOnUiThread(()->{
            downloadProgress.setIndeterminate(false);
            downloadProgress.setProgress(1000);
            transferLabel.setText("3. Steam depot download completed");
            GameInstallStore.completed(getApplicationContext(),item.getAppId(),true);
            downloadLabel.setText("Steam AppID "+item.getAppId()+" downloaded. "+GameInstallStore.status(getApplicationContext(),item.getAppId()));
        });
    }
    @Override public void onDownloadFailed(DownloadItem item,Throwable e){
        show("Steam download failed for "+item.getAppId()+": "+e.getMessage());
        Diagnostics.error("game-"+item.getAppId(),e);
        runOnUiThread(()->{downloadProgress.setIndeterminate(false);downloadLabel.setText("Download failed: "+e.getMessage());});
    }
    @Override public void onStatusUpdate(String message){
        lastDownloadEvent=SystemClock.elapsedRealtime();
        RuntimeHealth.progress();
        show("Steam: "+message);
    }
    @Override public void onChunkCompleted(int depotId,float percent,long compressed,long uncompressed){
        lastDownloadEvent=SystemClock.elapsedRealtime();
        RuntimeHealth.progress();
        float fraction=Math.max(0,Math.min(1,percent));
        int tenPercent=(int)(fraction*10);
        if(tenPercent>lastLoggedPercent){lastLoggedPercent=tenPercent;Diagnostics.log("game-"+selectedAppId,"Depot "+depotId+" "+Math.round(fraction*100)+" percent");}
        runOnUiThread(()->{
            downloadProgress.setIndeterminate(false);
            downloadProgress.setProgress(Math.round(fraction*1000));
            transferLabel.setText(String.format(Locale.US,"3. Depot %d chunks: %.1f%%",depotId,fraction*100));
            downloadLabel.setText(String.format(Locale.US,"Depot %d: %.1f%%  |  %,d MB transferred",
                depotId,fraction*100,(compressed/1048576)));
        });
    }
    private void signOut(){
        if(currentDownload!=null){show("Finish or cancel the active game download before signing out.");return;}
        explicitSignOut=true;
        SteamSessionStore.clear(this);
        tokenAccount=null;pendingRefreshToken=null;
        if(steamUser!=null){try{steamUser.logOff();}catch(Throwable ignored){}}
        if(steamClient!=null){try{steamClient.disconnect();}catch(Throwable ignored){}}
        loggedIn=false;running.set(false);licenses=Collections.emptyList();
        librarySyncing=false;loadingPackages=false;loadingApps=false;
        qrView.setImageDrawable(null);
        downloadButton.setEnabled(false);
        syncButton.setEnabled(false);
        gameList.removeAllViews();
        libraryStatus.setText("Signed out.");
        show("Signed out. Secure Steam login removed from tablet.");
    }
    @Override protected void onDestroy(){
        // Closing the screen must never erase the saved Steam login.
        running.set(false);
        repairWatchdog.shutdownNow();
        try{if(steamClient!=null)steamClient.disconnect();}catch(Exception ignored){}
        super.onDestroy();
    }
}
