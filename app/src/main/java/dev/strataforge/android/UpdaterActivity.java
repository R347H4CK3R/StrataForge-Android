package dev.strataforge.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.graphics.Color;
import android.widget.*;
import androidx.core.content.FileProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipFile;

public class UpdaterActivity extends Activity {
  private static final String RELEASE_API="https://api.github.com/repos/R347H4CK3R/StrataForge-Android-Releases/releases/latest";
  private static final long MAX_BYTES=400L*1024*1024;
  private final Handler main=new Handler(Looper.getMainLooper());
  private TextView info;
  private Button checkButton;
  private File pendingFile;
  private boolean busy=false;
  private static class Asset {String url,name;long size;Asset(String u,String n,long s){url=u;name=n;size=s;}}
  private void status(String s){main.post(()->{if(!isFinishing()&&!isDestroyed()){info.setText(s);}});}
  @Override public void onCreate(Bundle state){
    super.onCreate(state);
    LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(24,24,24,24);body.setBackgroundColor(Color.rgb(12,18,26));
    setContentView(body);
    TextView title=new TextView(this);title.setText("STRATAFORGE UPDATES");title.setTextSize(23);title.setTextColor(Color.WHITE);body.addView(title);
    info=new TextView(this);info.setTextColor(Color.WHITE);info.setTextSize(16);info.setPadding(0,22,0,16);
    info.setText("Checking for newer releases...");body.addView(info);
    checkButton=new Button(this);checkButton.setText("Check for updates");checkButton.setOnClickListener(v->check());body.addView(checkButton);
    TextView note=new TextView(this);note.setTextColor(Color.LTGRAY);note.setText("StrataForge checks its public release feed. Updates require your approval in Android's installer. Existing app data is retained if the package signature matches.");body.addView(note);
    check();
  }
  private static boolean validHost(URL url){
    String host=url.getHost().toLowerCase(Locale.ROOT);
    return url.getProtocol().equalsIgnoreCase("https") && url.getPort()==-1 && url.getUserInfo()==null &&
        (host.equals("api.github.com")||host.equals("github.com")||host.equals("release-assets.githubusercontent.com")||host.equals("objects.githubusercontent.com"));
  }
  private static HttpURLConnection open(URL address)throws IOException{
    URL current=address;
    for(int i=0;i<7;i++){
      if(!validHost(current))throw new IOException("Untrusted update server");
      HttpURLConnection c=(HttpURLConnection)current.openConnection();
      c.setInstanceFollowRedirects(false);c.setConnectTimeout(12000);c.setReadTimeout(30000);
      c.setRequestProperty("User-Agent","StrataForgeAndroid-Updater");
      c.setRequestProperty("Accept","application/vnd.github+json");
      int code=c.getResponseCode();
      if(code>=300&&code<400){
        String loc=c.getHeaderField("Location");c.disconnect();
        if(loc==null)throw new IOException("Redirect missing Location");
        current=new URL(current,loc);continue;
      }
      if(code!=200){c.disconnect();throw new IOException("Update server returned HTTP "+code);}
      return c;
    }
    throw new IOException("Too many redirects");
  }
  private static byte[] readSmall(InputStream in,int cap)throws IOException{
    ByteArrayOutputStream out=new ByteArrayOutputStream();
    byte[] buf=new byte[8192];int n;
    while((n=in.read(buf))!=-1){if(out.size()+n>cap)throw new IOException("Response too large");out.write(buf,0,n);}
    return out.toByteArray();
  }
  private void check(){
    if(busy)return;busy=true;checkButton.setEnabled(false);
    status("Checking StrataForge releases...");
    new Thread(()->{
      try{
        HttpURLConnection c=open(new URL(RELEASE_API));
        JSONObject release;
        try(InputStream in=c.getInputStream()){release=new JSONObject(new String(readSmall(in,1024*1024),"UTF-8"));}
        finally{c.disconnect();}
        JSONArray assets=release.optJSONArray("assets");
        Asset apk=null,sha=null;
        if(assets!=null)for(int i=0;i<assets.length();i++){
          JSONObject x=assets.getJSONObject(i);
          String name=x.optString("name"),url=x.optString("browser_download_url");
          if(!validHost(new URL(url)))continue;
          if(name.endsWith(".apk"))apk=new Asset(url,name,x.optLong("size",-1));
          if(name.endsWith(".sha256"))sha=new Asset(url,name,x.optLong("size",-1));
        }
        if(apk==null||sha==null)throw new IOException("Release has no APK + SHA-256 checksum");
        if(apk.size<=0||apk.size>MAX_BYTES)throw new IOException("Invalid APK size");
        String tag=release.optString("tag_name","unknown");
        String current=getPackageManager().getPackageInfo(getPackageName(),0).versionName;
        Asset finalApk=apk,finalSha=sha;
        main.post(()->{
          busy=false;checkButton.setEnabled(true);
          if(!isFinishing()&&!isDestroyed()){
            info.setText("Installed: "+current+"\nLatest release: "+tag+"\nAPK: "+finalApk.name);
            new AlertDialog.Builder(this).setTitle("Update StrataForge?")
              .setMessage("Check the downloaded APK's signature and checksum before requesting installation. Install release "+tag+"?")
              .setNegativeButton("Not now",null)
              .setPositiveButton("Download and verify",(dialog,which)->download(finalApk,finalSha)).show();
          }
        });
      }catch(Exception e){status("Unable to check for updates: "+e.getMessage());main.post(()->{busy=false;checkButton.setEnabled(true);});}
    },"update-check").start();
  }
  private void download(Asset apk,Asset checksum){
    if(busy)return;busy=true;checkButton.setEnabled(false);
    status("Downloading and verifying APK...");
    new Thread(()->{
      File file=null;
      try{
        HttpURLConnection check=open(new URL(checksum.url));
        String shaText;
        try(InputStream in=check.getInputStream()){shaText=new String(readSmall(in,2048),"UTF-8").trim();}
        finally{check.disconnect();}
        String expected=shaText.split("\\s+")[0].toLowerCase(Locale.ROOT);
        if(!expected.matches("[a-f0-9]{64}"))throw new IOException("Invalid SHA-256 checksum");
        File dir=new File(getCacheDir(),"updates");if(!dir.exists()&&!dir.mkdirs())throw new IOException("Cannot create cache");
        file=File.createTempFile("strataforge-update-",".apk",dir);
        HttpURLConnection connection=open(new URL(apk.url));
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        long transferred=0;
        try(InputStream in=connection.getInputStream();FileOutputStream out=new FileOutputStream(file)){
          byte[] buf=new byte[32768];int n;
          while((n=in.read(buf))!=-1){
            transferred+=n;if(transferred>MAX_BYTES)throw new IOException("APK exceeds allowed size");
            digest.update(buf,0,n);out.write(buf,0,n);
          }
        }finally{connection.disconnect();}
        if(transferred!=apk.size)throw new IOException("APK length mismatch");
        StringBuilder sum=new StringBuilder();
        for(byte x:digest.digest())sum.append(String.format(Locale.ROOT,"%02x",x&255));
        if(!expected.equals(sum.toString()))throw new IOException("SHA-256 mismatch");
        try(ZipFile zip=new ZipFile(file)){if(zip.getEntry("AndroidManifest.xml")==null)throw new IOException("Not an APK");}
        PackageManager pm=getPackageManager();
        PackageInfo installed=pm.getPackageInfo(getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo incoming=pm.getPackageArchiveInfo(file.getAbsolutePath(),PackageManager.GET_SIGNING_CERTIFICATES);
        if(incoming==null||!getPackageName().equals(incoming.packageName))throw new IOException("Wrong app package");
        if(incoming.getLongVersionCode()<=installed.getLongVersionCode())throw new IOException("No newer version available");
        Signature[] oldSigners=installed.signingInfo.getApkContentsSigners();
        Signature[] newSigners=incoming.signingInfo.getApkContentsSigners();
        if(oldSigners==null||newSigners==null||oldSigners.length!=newSigners.length||
            !new HashSet<>(Arrays.asList(oldSigners)).equals(new HashSet<>(Arrays.asList(newSigners))))
            throw new IOException("APK signing certificate mismatch");
        File ready=file;
        main.post(()->{if(!isFinishing()&&!isDestroyed()){pendingFile=ready;status("Verified APK signature and checksum. Opening Android installer.");install(ready);}});
      }catch(Exception e){if(file!=null)file.delete();status("Update failed: "+e.getMessage());}
      finally{main.post(()->{busy=false;checkButton.setEnabled(true);});}
    },"update-download").start();
  }
  private void install(File file){
    if(!getPackageManager().canRequestPackageInstalls()){
      new AlertDialog.Builder(this).setTitle("Allow updates")
        .setMessage("Android requires you to allow StrataForge to request APK installation.")
        .setPositiveButton("Settings",(dialog,w)->startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName()))))
        .setNegativeButton("Cancel",null).show();
      return;
    }
    try{
      Uri uri=FileProvider.getUriForFile(this,getPackageName()+".updates",file);
      Intent intent=new Intent(Intent.ACTION_VIEW);
      intent.setDataAndType(uri,"application/vnd.android.package-archive");
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      startActivity(intent);
    }catch(Exception e){status("Could not open Android installer: "+e.getMessage());}
  }
  @Override protected void onResume(){super.onResume();if(pendingFile!=null&&getPackageManager().canRequestPackageInstalls()){File f=pendingFile;pendingFile=null;install(f);}}
}
