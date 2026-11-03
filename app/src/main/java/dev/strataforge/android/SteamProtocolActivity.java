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
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
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
        TextView heading=new TextView(this);heading.setText("STEAM DIRECT CLIENT");
        heading.setTextColor(Color.WHITE);heading.setTextSize(22);root.addView(heading);
        TextView info=new TextView(this);info.setText("Use Steam Guard on another device to approve QR sign-in. No Steam password is entered into StrataForge. Download only games your Steam account can access.");
        info.setTextColor(Color.LTGRAY);info.setTextSize(14);info.setPadding(0,12,0,12);root.addView(info);
        root.addView(makeButton("Connect to Steam (QR)",()->connect()));
        state=new TextView(this);state.setText("Not connected");state.setTextColor(Color.WHITE);
        state.setTextSize(16);state.setPadding(0,12,0,12);root.addView(state);
        qrView=new ImageView(this);
        qrView.setAdjustViewBounds(true);
        qrView.setMaxHeight(460);
        root.addView(qrView,new LinearLayout.LayoutParams(-1,440));
        TextView libraryHeader=new TextView(this);
        libraryHeader.setText("MY STEAM LIBRARY");
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
        appIdInput=new EditText(this);appIdInput.setTextColor(Color.WHITE);appIdInput.setHintTextColor(Color.LTGRAY);
        appIdInput.setHint("Steam game AppID (e.g. 440)");appIdInput.setInputType(2);
        root.addView(appIdInput);
        downloadButton=makeButton("Download owned game (Windows files)",()->confirmDownload());
        downloadButton.setEnabled(false);root.addView(downloadButton);
        root.addView(makeButton("Disconnect / sign out",()->signOut()));
        TextView note=new TextView(this);note.setText("Downloaded PC games are files only. Windows-game execution is not implemented. Games install to Android/data/dev.strataforge.android/files/steamapps. Verify free space before downloading.");
        note.setTextColor(Color.LTGRAY);note.setPadding(0,12,0,0);root.addView(note);
    }
    private Button makeButton(String s,Runnable action){Button b=new Button(this);b.setAllCaps(false);b.setText(s);b.setOnClickListener(v->action.run());return b;}
    private void connect(){
        if(running.getAndSet(true)){show("Already connecting or connected");return;}
        show("Connecting to Steam network...");
        new Thread(()->{
            try {
                steamClient=new SteamClient();
                steamUser=steamClient.getHandler(SteamUser.class);
                steamApps=steamClient.getHandler(SteamApps.class);
                callbackManager=new CallbackManager(steamClient);
                callbackManager.subscribe(ConnectedCallback.class,c->beginQrAuthentication());
                callbackManager.subscribe(DisconnectedCallback.class,c->{show("Disconnected from Steam");running.set(false);loggedIn=false;runOnUiThread(()->downloadButton.setEnabled(false));});
                callbackManager.subscribe(LoggedOnCallback.class,this::onLoggedOn);
                callbackManager.subscribe(LoggedOffCallback.class,c->{loggedIn=false;show("Signed out: "+c.getResult());runOnUiThread(()->downloadButton.setEnabled(false));});
                callbackManager.subscribe(LicenseListCallback.class,this::onLicenseList);
                callbackManager.subscribe(PICSProductInfoCallback.class,this::onPicsInfo);
                steamClient.connect();
                while(running.get()){callbackManager.runWaitCallbacks(1000L);}
            } catch(Throwable e) {show("Steam connection error: "+e.getClass().getSimpleName()+": "+e.getMessage());running.set(false);}
        },"steam-callbacks").start();
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
            runOnUiThread(()->{if(!isDestroyed()){qrView.setImageBitmap(bitmap);state.setText("Scan this code with the Steam mobile app, then approve login.");}});
        } catch(Exception e){show("Unable to display Steam QR code: "+e.getMessage());}
    }
    private void onLoggedOn(LoggedOnCallback c){
        if(c.getResult()!=EResult.OK){show("Steam login rejected: "+c.getResult());return;}
        loggedIn=true;show("Authenticated to Steam. Loading licenses...");
    }
    private void onLicenseList(LicenseListCallback c){
        if(c.getResult()!=EResult.OK){show("Could not load Steam entitlements: "+c.getResult());return;}
        licenses=c.getLicenseList();
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
                Button select=makeButton(game.getValue()+"  •  Download",()->{
                    appIdInput.setText(String.valueOf(id));
                    confirmDownload();
                });
                gameList.addView(select);
            }
        });
    }
    private void confirmDownload(){
        if(!loggedIn || licenses.isEmpty()){show("Connect and load licenses first.");return;}
        int appId;
        try{appId=Integer.parseInt(appIdInput.getText().toString().trim());if(appId<=0)throw new Exception();}
        catch(Exception ex){show("Enter a valid numeric Steam AppID");return;}
        if(downloading){show("Another download is already running.");return;}
        File dir=new File(getExternalFilesDir(null),"steamapps");
        if(!dir.exists()&&!dir.mkdirs()){show("Cannot create game storage directory");return;}
        StatFs stats=new StatFs(dir.getAbsolutePath());
        long free=stats.getAvailableBytes();
        new AlertDialog.Builder(this).setTitle("Download Steam game "+appId)
            .setMessage("Available storage: "+(free/1048576)+" MiB. Game size is not yet known. Steam may reject games your account does not own. Download Windows PC game files here? This will use mobile data or Wi-Fi.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Download",(dialog,which)->download(appId,dir)).show();
    }
    private void download(int appId,File dir){
        downloading=true;
        new Thread(()->{
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
            }catch(Throwable e){show("Steam download error: "+e.getClass().getSimpleName()+": "+e.getMessage());}
            finally{currentDownload=null;downloading=false;}
        },"steam-depot").start();
    }
    @Override public void onDownloadStarted(DownloadItem item){show("Steam download started: "+item.getAppId());}
    @Override public void onDownloadCompleted(DownloadItem item){show("Steam download completed for AppID "+item.getAppId());}
    @Override public void onDownloadFailed(DownloadItem item,Throwable e){show("Steam download failed for "+item.getAppId()+": "+e.getMessage());}
    @Override public void onStatusUpdate(String message){show("Steam: "+message);}
    @Override public void onChunkCompleted(int depotId,float percent,long compressed,long uncompressed){
        show(String.format(Locale.US,"Depot %d: %.1f%% downloaded",depotId,percent*100f));
    }
    private void signOut(){
        if(currentDownload!=null){show("Finish or cancel the active game download before signing out.");return;}
        if(steamUser!=null){try{steamUser.logOff();}catch(Throwable ignored){}}
        if(steamClient!=null){try{steamClient.disconnect();}catch(Throwable ignored){}}
        loggedIn=false;running.set(false);licenses=Collections.emptyList();
        librarySyncing=false;loadingPackages=false;loadingApps=false;
        qrView.setImageDrawable(null);
        downloadButton.setEnabled(false);
        syncButton.setEnabled(false);
        gameList.removeAllViews();
        libraryStatus.setText("Signed out.");
        show("Signed out. Steam refresh token was not saved.");
    }
    @Override protected void onDestroy(){signOut();super.onDestroy();}
}
