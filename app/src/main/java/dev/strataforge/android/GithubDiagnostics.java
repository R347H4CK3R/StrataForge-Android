package dev.strataforge.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Private GitHub issue delivery for redacted diagnostic snapshots.
 * Uses an optional build-time embedded, narrowly scoped GitHub token, or a user-configured encrypted token.
 */
final class GithubDiagnostics {
  private static final String ALIAS="strataforge.diagnostics.github.v1";
  private static final String PREF="diagnostics_github";
  private static final String ENDPOINT="https://api.github.com/repos/R347H4CK3R/StrataForge-Android/issues";
  private static final int MAX_BODY=24000;
  private static final Object LOCK=new Object();
  private GithubDiagnostics(){}
  private static SecretKey key()throws Exception{
    KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
    KeyStore.Entry e=ks.getEntry(ALIAS,null);
    if(e instanceof KeyStore.SecretKeyEntry)return ((KeyStore.SecretKeyEntry)e).getSecretKey();
    KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
    gen.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setKeySize(256).build());
    return gen.generateKey();
  }
  static void configure(Context c,String token)throws Exception{
    token=token.trim();
    if(token.length()<30||token.length()>512||!token.startsWith("github_pat_"))
      throw new IllegalArgumentException("Use a GitHub fine-grained personal access token");
    Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
    String iv=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP);
    String data=Base64.encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
    boolean ok=c.getSharedPreferences(PREF,0).edit().putString("iv",iv).putString("secret",data)
      .putBoolean("enabled",true).putBoolean("auto_upload_disabled",false).commit();
    if(!ok)throw new IOException("Could not save GitHub credentials");
  }
  private static String embeddedToken(Context c){
    try(InputStream input=c.getAssets().open("strataforge-diagnostics-token.txt")){
      ByteArrayOutputStream data=new ByteArrayOutputStream();
      byte[] chunk=new byte[1024];int n;
      while((n=input.read(chunk))!=-1){
        if(data.size()+n>1024)return null;
        data.write(chunk,0,n);
      }
      String raw=data.toString("UTF-8").trim();
      return raw.startsWith("github_pat_")?raw:null;
    }catch(Exception ignored){return null;}
  }
  static boolean isEnabled(Context c){
    SharedPreferences p=c.getSharedPreferences(PREF,0);
    return !p.getBoolean("auto_upload_disabled",false)
      && ((p.getBoolean("enabled",false)&&p.contains("secret")) || embeddedToken(c)!=null);
  }
  static void disable(Context c){
    c.getSharedPreferences(PREF,0).edit().remove("secret").remove("iv").putBoolean("enabled",false).putBoolean("auto_upload_disabled",true).apply();
  }
  private static String token(Context c)throws Exception{
    SharedPreferences p=c.getSharedPreferences(PREF,0);
    String iv=p.getString("iv",null),secret=p.getString("secret",null);
    if(iv==null||secret==null)return embeddedToken(c);
    Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(iv,Base64.DEFAULT)));
    return new String(cipher.doFinal(Base64.decode(secret,Base64.DEFAULT)),StandardCharsets.UTF_8);
  }
  private static String redact(String s){
    if(s==null)return "";
    s=s.replaceAll("(?i)(github_pat_[a-z0-9_]+|gh[pousr]_[a-z0-9_]+)","[REDACTED TOKEN]");
    s=s.replaceAll("(?i)(refresh[_-]?token|access[_-]?token|password|authorization|sessionid|steamloginsecure)[=:]\\s*[^\\s&,]+","$1=[REDACTED]");
    s=s.replaceAll("(?i)https://[^\\s]+/login[^\\s]*","[REDACTED LOGIN URL]");
    return s.length()>MAX_BODY?s.substring(0,MAX_BODY)+"\n[truncated]":s;
  }
  private static String read(File f,int cap)throws IOException{
    byte[] b=new byte[(int)Math.min(f.length(),cap)];
    try(FileInputStream in=new FileInputStream(f)){int off=0,n;while(off<b.length&&(n=in.read(b,off,b.length-off))>0)off+=n;return new String(b,0,off,StandardCharsets.UTF_8);}
  }
  static void uploadPending(Context c){
    if(!isEnabled(c))return;
    synchronized(LOCK){
      SharedPreferences prefs=c.getSharedPreferences(PREF,0);
      File dir=Diagnostics.directory(c);
      File[] crashes=dir.listFiles((parent,name)->name.startsWith("crash-")&&name.endsWith(".txt"));
      if(crashes!=null){
        Arrays.sort(crashes,Comparator.comparing(File::getName));
        for(File f:crashes){
          String marker="sent:"+f.getName();
          if(prefs.getBoolean(marker,false))continue;
          try{
            String body="StrataForge Android automatic crash report\n"+
              "Device: "+Build.MANUFACTURER+" "+Build.MODEL+"\nAndroid: "+Build.VERSION.RELEASE+
              "\nPlease review the attached sanitized stack trace:\n\n```\n"+redact(read(f,18000))+"\n```";
            publish(c,"[StrataForge Android] Crash "+f.getName(),body);
            prefs.edit().putBoolean(marker,true).commit();
          }catch(Exception e){android.util.Log.w("StrataForge","Diagnostic upload failed: "+e.getClass().getSimpleName());break;}
        }
      }
      File log=new File(dir,"session.log");
      if(!log.exists())return;
      long modified=log.lastModified();
      if(modified<=prefs.getLong("sessionUploadedModified",0))return;
      try{
        String text=read(log,MAX_BODY);
        String[] lines=text.split("\\r?\\n");
        StringBuilder errors=new StringBuilder();
        for(String line:lines)if(line.contains("[ERROR]")||line.contains("[FATAL]"))errors.append(line).append("\n");
        if(errors.length()>0){
          String signature=Integer.toHexString(errors.toString().hashCode());
          if(!prefs.getBoolean("error:"+signature,false)){
            publish(c,"[StrataForge Android] Download/runtime error "+signature,
                "Automatic sanitized diagnostic summary. No credentials are intentionally included.\n\n```\n"+
                redact(errors.toString())+"\n```");
            prefs.edit().putBoolean("error:"+signature,true).commit();
          }
        }
        prefs.edit().putLong("sessionUploadedModified",modified).commit();
      }catch(Exception e){android.util.Log.w("StrataForge","Diagnostic upload failed: "+e.getClass().getSimpleName());}
    }
  }
  private static void publish(Context c,String title,String body)throws Exception{
    String auth=token(c);if(auth==null)throw new IllegalStateException("GitHub not configured");
    HttpURLConnection conn=(HttpURLConnection)new URL(ENDPOINT).openConnection();
    conn.setRequestMethod("POST");conn.setConnectTimeout(15000);conn.setReadTimeout(20000);
    conn.setRequestProperty("Accept","application/vnd.github+json");
    conn.setRequestProperty("X-GitHub-Api-Version","2022-11-28");
    conn.setRequestProperty("Authorization","Bearer "+auth);
    conn.setRequestProperty("User-Agent","StrataForgeAndroidDiagnostics");
    conn.setRequestProperty("Content-Type","application/json; charset=utf-8");
    conn.setDoOutput(true);
    JSONObject payload=new JSONObject();payload.put("title",title);payload.put("body",redact(body));
    byte[] data=payload.toString().getBytes(StandardCharsets.UTF_8);
    try(OutputStream out=conn.getOutputStream()){out.write(data);}
    int status=conn.getResponseCode();conn.disconnect();
    if(status!=201)throw new IOException("GitHub returned HTTP "+status);
  }
}
